import Foundation
import CoreBluetooth

/// Bluetooth LE fallback, layer L1 (docs/BLE-NEARBY-DESIGN.md §4, §9b).
///
/// This file owns CoreBluetooth and nothing else: it splits and rejoins ATT values and shuttles opaque
/// L2 frames to and from the Rust core. Handshake, encryption, compression, the rotating id and every
/// policy decision (when to scan, when to hand back to Wi-Fi) live in Rust.
///
/// Privacy contract: **no `CBCentralManager` is created until the core says `power on`**, which it does
/// only when the user turned "Bluetooth fallback" on. Reading `CBManager.authorization` (a class property)
/// never prompts. When the mode was synced from the phone the core sends `prompt:false`; if Bluetooth has
/// not been authorised yet we then stay silent and let the UI offer "Allow Bluetooth".
final class BleService: NSObject, @unchecked Sendable {
    static let shared = BleService()

    private let svc = CBUUID(string: "B5800E35-6F66-41DF-A777-60B974C4B5F9")
    private let m2p = CBUUID(string: "B5800E36-6F66-41DF-A777-60B974C4B5F9")
    private let p2m = CBUUID(string: "B5800E37-6F66-41DF-A777-60B974C4B5F9")
    private let infoC = CBUUID(string: "B5800E38-6F66-41DF-A777-60B974C4B5F9")
    private let maxFrame = 65_536

    /// Everything below is touched only on this queue (also the CBCentralManager's delegate queue).
    private let queue = DispatchQueue(label: "droiddock.ble", qos: .userInitiated)
    private var central: CBCentralManager?
    private var scanWanted = false
    private var rids: [Data] = []
    private var scanRestart: DispatchSourceTimer?
    private var conns: [UUID: Link] = [:]
    private var installed = false

    /// One GATT connection.
    private final class Link {
        let peripheral: CBPeripheral
        var m2p: CBCharacteristic?
        var p2m: CBCharacteristic?
        var connectTimer: DispatchSourceTimer?
        var rssiTimer: DispatchSourceTimer?
        var registered = false          // `ble_connected` sent to the core
        var ready = false               // ...and acknowledged: inbound frames may flow
        var pendingRx: [Data] = []
        var rxSeq: UInt64 = 0
        var reasm = Reassembler()
        var txSeq: UInt8 = 0
        var frames: [Data] = []         // queued L2 frames
        var fragments: [Data] = []      // the frame being written
        init(_ p: CBPeripheral) { peripheral = p }
    }

    // MARK: Install (sinks only; no CoreBluetooth objects)

    /// Hooks the core's stream-3 frames and `ble-control` events. Safe at launch: creates no manager.
    func install() {
        queue.async {
            guard !self.installed else { return }
            self.installed = true
        }
        Core.shared.bleTxSink = { [weak self] data in self?.queue.async { self?.enqueueTx(data) } }
        Core.shared.bleControlSink = { [weak self] data in self?.queue.async { self?.handleControl(data) } }
    }

    /// The user tapped "Allow Bluetooth": this is the one place a prompt may be triggered on purpose.
    func requestAuthorization() {
        queue.async {
            if self.central == nil { self.makeCentral() }
        }
    }

    static var authorizationName: String {
        switch CBManager.authorization {
        case .notDetermined: "notDetermined"
        case .restricted: "restricted"
        case .denied: "denied"
        case .allowedAlways: "allowed"
        @unknown default: "notDetermined"
        }
    }

    // MARK: Control from the core

    private func handleControl(_ data: Data) {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let op = o["op"] as? String else { return }
        switch op {
        case "power":
            if (o["on"] as? Bool) == true {
                let prompt = (o["prompt"] as? Bool) ?? false
                if central == nil {
                    if !prompt && CBManager.authorization == .notDetermined {
                        report(radio: "unknown")      // stay silent; the UI offers "Allow Bluetooth"
                    } else {
                        makeCentral()
                    }
                }
            } else {
                tearDown()
            }
        case "scan":
            if (o["on"] as? Bool) == true {
                rids = ((o["rids"] as? [String]) ?? []).compactMap { Self.data(hex: $0) }
                scanWanted = true
                startScanIfPossible()
            } else {
                scanWanted = false
                stopScan()
            }
        case "drop":
            if let s = o["conn"] as? String, let id = UUID(uuidString: s), let l = conns[id] {
                central?.cancelPeripheralConnection(l.peripheral)
            }
        default: break
        }
    }

    private func makeCentral() {
        central = CBCentralManager(delegate: self, queue: queue,
                                   options: [CBCentralManagerOptionShowPowerAlertKey: false])
    }

    private func tearDown() {
        scanWanted = false
        stopScan()
        for l in conns.values { central?.cancelPeripheralConnection(l.peripheral) }
        for (_, l) in conns where l.registered { fire("ble_closed", ["conn": l.peripheral.identifier.uuidString, "reason": "poweredOff"]) }
        conns.removeAll()
        central?.delegate = nil
        central = nil
    }

    // MARK: Scanning

    private func startScanIfPossible() {
        guard scanWanted, let c = central, c.state == .poweredOn else { return }
        c.stopScan()
        c.scanForPeripherals(withServices: [svc], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        // A changed advertisement (rid rotation) may not be re-reported: restart every 60 s.
        scanRestart?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 60, repeating: 60)
        t.setEventHandler { [weak self] in
            guard let self, self.scanWanted, let c = self.central, c.state == .poweredOn, self.conns.isEmpty else { return }
            c.stopScan()
            c.scanForPeripherals(withServices: [self.svc], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        }
        t.resume()
        scanRestart = t
    }

    private func stopScan() {
        scanRestart?.cancel(); scanRestart = nil
        central?.stopScan()
    }

    // MARK: Reporting to the core

    private func report(radio: String? = nil) {
        let r = radio ?? Self.radioName(central?.state ?? .unknown)
        fire("ble_radio", ["radio": r, "auth": Self.authorizationName])
    }

    private static func radioName(_ s: CBManagerState) -> String {
        switch s {
        case .unknown: "unknown"
        case .resetting: "resetting"
        case .unsupported: "unsupported"
        case .unauthorized: "unauthorized"
        case .poweredOff: "poweredOff"
        case .poweredOn: "poweredOn"
        @unknown default: "unknown"
        }
    }

    /// Fire-and-forget core command.
    private func fire(_ cmd: String, _ args: [String: Any], body: Data? = nil) {
        Task.detached { _ = try? await Core.shared.raw(cmd, args, body: body) }
    }

    private static func data(hex: String) -> Data? {
        guard hex.count % 2 == 0 else { return nil }
        var d = Data(); var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            d.append(b); i = j
        }
        return d
    }

    // MARK: Sending (core -> phone)

    private func enqueueTx(_ data: Data) {
        guard data.count > 16 else { return }
        let uuid = data.withUnsafeBytes { raw -> UUID in
            let b = raw.bindMemory(to: UInt8.self)
            return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
        }
        guard let l = conns[uuid] else { return }
        l.frames.append(data.subdata(in: 16..<data.count))
        pump(l)
    }

    private func pump(_ l: Link) {
        let p = l.peripheral
        guard let ch = l.m2p else { return }
        while true {
            if l.fragments.isEmpty {
                guard !l.frames.isEmpty else { return }
                let frame = l.frames.removeFirst()
                l.fragments = fragment(frame, max: p.maximumWriteValueLength(for: .withoutResponse), seq: &l.txSeq)
            }
            guard p.canSendWriteWithoutResponse else { return }   // resumes in peripheralIsReady
            p.writeValue(l.fragments.removeFirst(), for: ch, type: .withoutResponse)
            if l.fragments.isEmpty {
                fire("ble_tx_done", ["conn": p.identifier.uuidString, "n": 1])
            }
        }
    }

    /// L1: `[hdr][START? len u24 BE][data]`, hdr bit7 START, bit6 END, bits0-5 seq. `max` is the largest ATT value,
    /// header included (payload 181 bytes at 182; 178 on the first packet).
    private func fragment(_ frame: Data, max: Int, seq: inout UInt8) -> [Data] {
        let maxv = Swift.max(max, 5)
        var out: [Data] = []
        var off = 0
        let len = frame.count
        while off < len || out.isEmpty {
            let first = out.isEmpty
            let n = Swift.min(maxv - (first ? 4 : 1), len - off)
            let last = off + n == len
            var hdr = seq & 0x3F
            if first { hdr |= 0x80 }
            if last { hdr |= 0x40 }
            var v = Data([hdr])
            if first { v.append(contentsOf: [UInt8((len >> 16) & 0xFF), UInt8((len >> 8) & 0xFF), UInt8(len & 0xFF)]) }
            v.append(frame.subdata(in: off..<(off + n)))
            out.append(v)
            seq = (seq &+ 1) & 0x3F
            off += n
        }
        return out
    }

    /// L1 receiver. Any error means: drop the connection (the link is reliable, so a gap is a bug).
    struct Reassembler {
        var buf = Data(); var want = 0; var next: UInt8?; var active = false
        mutating func push(_ v: Data, maxFrame: Int) -> Result<Data?, Error> {
            struct Bad: Error {}
            guard let hdr = v.first else { return .failure(Bad()) }
            let start = hdr & 0x80 != 0, end = hdr & 0x40 != 0, seq = hdr & 0x3F
            if let n = next, n != seq { return .failure(Bad()) }
            next = (seq &+ 1) & 0x3F
            let body: Data
            if start {
                guard v.count >= 4 else { return .failure(Bad()) }
                want = Int(v[v.startIndex + 1]) << 16 | Int(v[v.startIndex + 2]) << 8 | Int(v[v.startIndex + 3])
                if want > maxFrame { return .failure(Bad()) }
                buf = Data(); active = true
                body = v.subdata(in: (v.startIndex + 4)..<v.endIndex)
            } else {
                guard active else { return .failure(Bad()) }
                body = v.subdata(in: (v.startIndex + 1)..<v.endIndex)
            }
            buf.append(body)
            if buf.count > want { return .failure(Bad()) }
            if end {
                active = false
                guard buf.count == want else { return .failure(Bad()) }
                let f = buf; buf = Data()
                return .success(f)
            }
            return .success(nil)
        }
    }

    // MARK: Receiving (phone -> core)

    private func deliver(_ l: Link, frame: Data) {
        guard l.ready else { l.pendingRx.append(frame); return }
        let seq = l.rxSeq; l.rxSeq += 1
        fire("ble_rx", ["conn": l.peripheral.identifier.uuidString, "seq": seq], body: frame)
    }

    private func close(_ l: Link, reason: String) {
        l.connectTimer?.cancel(); l.rssiTimer?.cancel()
        conns[l.peripheral.identifier] = nil
        if l.registered { fire("ble_closed", ["conn": l.peripheral.identifier.uuidString, "reason": reason]) }
        if scanWanted, conns.isEmpty { startScanIfPossible() }
    }
}

extension BleService: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        report()
        if central.state == .poweredOn { startScanIfPossible() }
        else if central.state == .poweredOff || central.state == .unauthorized {
            for l in conns.values { close(l, reason: "poweredOff") }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard scanWanted, conns.isEmpty else { return }
        guard let sd = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let rid = sd[svc], rids.contains(rid) else { return }
        // Never cache identifiers: the phone's address rotates, so every connect starts from a fresh match.
        central.stopScan()
        let l = Link(peripheral)
        conns[peripheral.identifier] = l
        peripheral.delegate = self
        // CoreBluetooth's connect never times out; cancel after 10 s.
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 10)
        t.setEventHandler { [weak self, weak l] in
            guard let self, let l, !l.registered else { return }
            self.central?.cancelPeripheralConnection(l.peripheral)
            self.close(l, reason: "timeout")
        }
        t.resume()
        l.connectTimer = t
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([svc])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if let l = conns[peripheral.identifier] { close(l, reason: "error") }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let l = conns[peripheral.identifier] { close(l, reason: error == nil ? "disconnected" : "error") }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let s = peripheral.services?.first(where: { $0.uuid == svc }) else {
            central?.cancelPeripheralConnection(peripheral); return
        }
        peripheral.discoverCharacteristics([m2p, p2m, infoC], for: s)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let l = conns[peripheral.identifier] else { return }
        l.m2p = service.characteristics?.first { $0.uuid == m2p }
        l.p2m = service.characteristics?.first { $0.uuid == p2m }
        guard let n = l.p2m, l.m2p != nil else { central?.cancelPeripheralConnection(peripheral); return }
        peripheral.setNotifyValue(true, for: n)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let l = conns[peripheral.identifier], characteristic.uuid == p2m else { return }
        guard error == nil, characteristic.isNotifying else { central?.cancelPeripheralConnection(peripheral); return }
        guard !l.registered else { return }
        l.registered = true
        l.connectTimer?.cancel()
        let id = peripheral.identifier
        let maxWrite = peripheral.maximumWriteValueLength(for: .withoutResponse)
        // The phone sends P_HELLO on the CCCD write, so frames can arrive before the core knows this
        // connection: buffer them until `ble_connected` is acknowledged, then flush in order.
        Task.detached { [weak self] in
            _ = try? await Core.shared.raw("ble_connected", ["conn": id.uuidString, "maxWrite": maxWrite], body: nil)
            self?.queue.async {
                guard let self, let l = self.conns[id] else { return }
                l.ready = true
                let held = l.pendingRx; l.pendingRx = []
                for f in held { self.deliver(l, frame: f) }
            }
        }
        // RSSI for the UI only, every 10 s.
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 2, repeating: 10)
        t.setEventHandler { peripheral.readRSSI() }
        t.resume()
        l.rssiTimer = t
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let l = conns[peripheral.identifier], characteristic.uuid == p2m, let v = characteristic.value else { return }
        switch l.reasm.push(v, maxFrame: maxFrame) {
        case .success(let f): if let f { deliver(l, frame: f) }
        case .failure: central?.cancelPeripheralConnection(peripheral)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        if let l = conns[peripheral.identifier] { pump(l) }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard error == nil, let l = conns[peripheral.identifier], l.registered else { return }
        fire("ble_rssi", ["conn": peripheral.identifier.uuidString, "rssi": RSSI.intValue])
    }
}
