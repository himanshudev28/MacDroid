import AppKit
import Carbon.HIToolbox
import Observation
import SwiftUI

/// What a global shortcut can do. Each case drives the same code path as the matching `droiddock://` route.
enum HotKeyAction: String, CaseIterable, Codable, Identifiable, Sendable {
    case showHide, sendClipboard, toggleMirror, ringPhone, screenshot, playPause, menuBarPanel
    var id: String { rawValue }

    var title: String {
        switch self {
        case .showHide: tr("Show or hide DroidDock")
        case .sendClipboard: tr("Send Mac clipboard to phone")
        case .toggleMirror: tr("Start or stop screen mirror")
        case .ringPhone: tr("Ring phone")
        case .screenshot: tr("Take phone screenshot")
        case .playPause: tr("Play or pause phone media")
        case .menuBarPanel: tr("Open menu-bar panel")
        }
    }
    var symbol: String {
        switch self {
        case .showHide: "macwindow"
        case .sendClipboard: "doc.on.clipboard"
        case .toggleMirror: "rectangle.on.rectangle"
        case .ringPhone: "bell.and.waves.left.and.right"
        case .screenshot: "camera.viewfinder"
        case .playPause: "playpause"
        case .menuBarPanel: "menubar.rectangle"
        }
    }
    /// Optional starting points offered as a one-click suggestion; never bound automatically.
    var suggestion: KeyCombo? {
        let m = UInt32(controlKey | optionKey)
        switch self {
        case .showHide: return KeyCombo(keyCode: UInt32(kVK_ANSI_D), modifiers: m)
        case .sendClipboard: return KeyCombo(keyCode: UInt32(kVK_ANSI_V), modifiers: m)
        case .toggleMirror: return KeyCombo(keyCode: UInt32(kVK_ANSI_M), modifiers: m)
        case .ringPhone: return KeyCombo(keyCode: UInt32(kVK_ANSI_R), modifiers: m)
        case .screenshot: return KeyCombo(keyCode: UInt32(kVK_ANSI_S), modifiers: m)
        case .playPause: return KeyCombo(keyCode: UInt32(kVK_ANSI_P), modifiers: m)
        case .menuBarPanel: return nil
        }
    }
}

/// A key plus Carbon modifier flags (`cmdKey`, `optionKey`, `controlKey`, `shiftKey`).
struct KeyCombo: Codable, Equatable, Sendable {
    var keyCode: UInt32
    var modifiers: UInt32

    init(keyCode: UInt32, modifiers: UInt32) { self.keyCode = keyCode; self.modifiers = modifiers }

    init?(event: NSEvent) {
        let f = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        var m: UInt32 = 0
        if f.contains(.control) { m |= UInt32(controlKey) }
        if f.contains(.option) { m |= UInt32(optionKey) }
        if f.contains(.shift) { m |= UInt32(shiftKey) }
        if f.contains(.command) { m |= UInt32(cmdKey) }
        self.init(keyCode: UInt32(event.keyCode), modifiers: m)
    }

    /// At least one of ⌃⌥⌘ — a bare letter or ⇧+letter would hijack typing everywhere.
    var isValidGlobal: Bool { modifiers & UInt32(controlKey | optionKey | cmdKey) != 0 }

    /// Combos macOS or every app already owns; allowed but flagged.
    var systemReservedName: String? {
        let cmdOnly = modifiers == UInt32(cmdKey)
        switch (Int(keyCode), cmdOnly) {
        case (kVK_Space, true): return "Spotlight"
        case (kVK_Tab, true): return tr("the app switcher")
        case (kVK_ANSI_Q, true): return tr("Quit")
        case (kVK_ANSI_H, true): return tr("Hide")
        case (kVK_ANSI_W, true): return tr("Close window")
        default: return nil
        }
    }

    var glyphs: String {
        var s = ""
        if modifiers & UInt32(controlKey) != 0 { s += "⌃" }
        if modifiers & UInt32(optionKey) != 0 { s += "⌥" }
        if modifiers & UInt32(shiftKey) != 0 { s += "⇧" }
        if modifiers & UInt32(cmdKey) != 0 { s += "⌘" }
        return s + Self.keyName(keyCode)
    }

    static func keyName(_ code: UInt32) -> String {
        switch Int(code) {
        case kVK_Return: return "↩"
        case kVK_Tab: return "⇥"
        case kVK_Space: return "Space"
        case kVK_Delete: return "⌫"
        case kVK_Escape: return "⎋"
        case kVK_LeftArrow: return "←"
        case kVK_RightArrow: return "→"
        case kVK_UpArrow: return "↑"
        case kVK_DownArrow: return "↓"
        case kVK_ForwardDelete: return "⌦"
        case kVK_Home: return "↖"
        case kVK_End: return "↘"
        case kVK_PageUp: return "⇞"
        case kVK_PageDown: return "⇟"
        case kVK_F1: return "F1"; case kVK_F2: return "F2"; case kVK_F3: return "F3"; case kVK_F4: return "F4"
        case kVK_F5: return "F5"; case kVK_F6: return "F6"; case kVK_F7: return "F7"; case kVK_F8: return "F8"
        case kVK_F9: return "F9"; case kVK_F10: return "F10"; case kVK_F11: return "F11"; case kVK_F12: return "F12"
        default: break
        }
        // Translate through the current keyboard layout so non-US layouts show the right letter.
        guard let src = TISCopyCurrentKeyboardLayoutInputSource()?.takeRetainedValue(),
              let ptr = TISGetInputSourceProperty(src, kTISPropertyUnicodeKeyLayoutData) else { return "#\(code)" }
        let layout = unsafeBitCast(ptr, to: CFData.self)
        let data = unsafeBitCast(CFDataGetBytePtr(layout), to: UnsafePointer<UCKeyboardLayout>.self)
        var dead: UInt32 = 0
        var len = 0
        var chars = [UniChar](repeating: 0, count: 4)
        let status = UCKeyTranslate(data, UInt16(code), UInt16(kUCKeyActionDisplay), 0, UInt32(LMGetKbdType()),
                                    OptionBits(kUCKeyTranslateNoDeadKeysBit), &dead, 4, &len, &chars)
        guard status == noErr, len > 0 else { return "#\(code)" }
        return String(utf16CodeUnits: chars, count: len).uppercased()
    }
}

private let hotKeySignature: OSType = 0x4444_484B   // "DDHK"

/// Carbon callback: hop to the main actor with the pressed hot-key id.
private func hotKeyEventHandler(_: EventHandlerCallRef?, _ event: EventRef?, _: UnsafeMutableRawPointer?) -> OSStatus {
    var hk = EventHotKeyID()
    let st = GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                               nil, MemoryLayout<EventHotKeyID>.size, nil, &hk)
    guard st == noErr, hk.signature == hotKeySignature else { return OSStatus(eventNotHandledErr) }
    let id = Int(hk.id)
    DispatchQueue.main.async { MainActor.assumeIsolated { HotKeys.shared.fire(id: id) } }
    return noErr
}

/// System-wide shortcuts via `RegisterEventHotKey` (no Accessibility permission). Bindings live in
/// UserDefaults `droiddock.hotkeys` as JSON `{action: {keyCode, modifiers}}`; nothing is bound by default.
@Observable @MainActor
final class HotKeys {
    static let shared = HotKeys()
    static let defaultsKey = "droiddock.hotkeys"

    private(set) var bindings: [HotKeyAction: KeyCombo] = [:]
    private var refs: [HotKeyAction: EventHotKeyRef] = [:]
    private var handler: EventHandlerRef?
    private var suspended = false
    private var ringing = false

    private init() { bindings = Self.load() }

    // MARK: Persistence

    private static func load() -> [HotKeyAction: KeyCombo] {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let raw = try? JSONDecoder().decode([String: KeyCombo].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: raw.compactMap { k, v in HotKeyAction(rawValue: k).map { ($0, v) } })
    }

    private func save() {
        let raw = Dictionary(uniqueKeysWithValues: bindings.map { ($0.key.rawValue, $0.value) })
        if let data = try? JSONEncoder().encode(raw) { UserDefaults.standard.set(data, forKey: Self.defaultsKey) }
    }

    // MARK: Binding

    func combo(for action: HotKeyAction) -> KeyCombo? { bindings[action] }

    /// The action already using `combo` (other than `except`), for the recorder's conflict warning.
    func conflict(for combo: KeyCombo, except: HotKeyAction) -> HotKeyAction? {
        bindings.first { $0.key != except && $0.value == combo }?.key
    }

    func set(_ combo: KeyCombo?, for action: HotKeyAction) {
        if let combo { bindings[action] = combo } else { bindings[action] = nil }
        save()
        registerAll()
    }

    // MARK: Registration

    /// Call once at launch; safe to call again.
    func start() {
        installHandlerIfNeeded()
        registerAll()
    }

    /// Pause while a recorder is listening, so pressing a bound combo records instead of firing.
    func suspend(_ on: Bool) {
        suspended = on
        registerAll()
    }

    private func installHandlerIfNeeded() {
        guard handler == nil else { return }
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let st = InstallEventHandler(GetApplicationEventTarget(), hotKeyEventHandler, 1, &spec, nil, &handler)
        if st != noErr { dlog("hotkeys: InstallEventHandler failed \(st)") }
    }

    private func registerAll() {
        for (_, ref) in refs { UnregisterEventHotKey(ref) }
        refs.removeAll()
        guard !suspended else { return }
        for (idx, action) in HotKeyAction.allCases.enumerated() {
            guard let combo = bindings[action], combo.isValidGlobal else { continue }
            var ref: EventHotKeyRef?
            let id = EventHotKeyID(signature: hotKeySignature, id: UInt32(idx))
            let st = RegisterEventHotKey(combo.keyCode, combo.modifiers, id, GetApplicationEventTarget(), 0, &ref)
            if st == noErr, let ref {
                refs[action] = ref
                dlog("hotkeys: registered \(action.rawValue) = \(combo.glyphs)")
            } else {
                dlog("hotkeys: could not register \(action.rawValue) = \(combo.glyphs) (status \(st))")
                AppModel.shared.toast(.bad, tr("Shortcut {keys} is taken by another app", ["keys": combo.glyphs]))
            }
        }
    }

    // MARK: Dispatch

    fileprivate func fire(id: Int) {
        guard HotKeyAction.allCases.indices.contains(id) else { return }
        let action = HotKeyAction.allCases[id]
        dlog("hotkeys: fired \(action.rawValue)")
        perform(action)
    }

    func perform(_ action: HotKeyAction) {
        let model = AppModel.shared
        switch action {
        case .showHide:
            if NSApp.isActive, let w = WindowControl.mainWindow, w.isVisible { w.orderOut(nil); NSApp.hide(nil) }
            else { WindowControl.showMain() }
        case .sendClipboard:
            guard model.isConnected else { model.toast(.info, tr("No phone linked")); return }
            model.openURL("droiddock://clipboard/push")
        case .toggleMirror:
            if model.mirror != nil { Task { try? await Core.shared.mirrorStop() } }
            else { model.openURL(model.devices.contains { $0.state == "device" } ? "droiddock://mirror/adb" : "droiddock://mirror") }
        case .ringPhone:
            guard model.isConnected else { model.toast(.info, tr("No phone linked")); return }
            let next = !ringing
            Task {
                do { ringing = try await Core.shared.ringPhone(on: next) }
                catch { model.toast(.bad, error.localizedDescription) }
            }
        case .screenshot:
            guard let dev = model.devices.first(where: { $0.state == "device" }) else {
                model.toast(.info, tr("Connect the phone over ADB to take a screenshot")); return
            }
            Task {
                do {
                    let path = try await Core.shared.adbScreenshot(serial: dev.serial)
                    model.toast(.ok, tr("Screenshot saved"))
                    if FileManager.default.fileExists(atPath: path) {
                        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
                    }
                } catch { model.toast(.bad, error.localizedDescription) }
            }
        case .playPause:
            guard model.media?.active == true else { model.toast(.info, tr("Nothing is playing on the phone")); return }
            let cmd = model.media?.playing == true ? "pause" : "play"
            Task { try? await Core.shared.mediaCmd(cmd) }
        case .menuBarPanel:
            Self.openStatusItemPanel()
        }
    }

    /// `MenuBarExtra` exposes no API to open itself: click its status-bar button.
    private static func openStatusItemPanel() {
        for w in NSApp.windows where String(describing: type(of: w)).contains("StatusBarWindow") {
            if let btn = findButton(in: w.contentView) { btn.performClick(nil); return }
        }
        dlog("hotkeys: status item button not found")
    }

    private static func findButton(in view: NSView?) -> NSStatusBarButton? {
        guard let view else { return nil }
        if let b = view as? NSStatusBarButton { return b }
        for s in view.subviews { if let b = findButton(in: s) { return b } }
        return nil
    }
}

// MARK: - Recorder control

/// Click, press a combo; Esc cancels, ⌫ clears. Shows ⌃⌥⇧⌘ glyphs and warns about conflicts.
struct HotKeyRecorder: View {
    let action: HotKeyAction
    private let keys = HotKeys.shared
    @State private var recording = false
    @State private var monitor: Any?
    @State private var warning: String?

    var body: some View {
        VStack(alignment: .trailing, spacing: DD.Space.xs) {
            HStack(spacing: DD.Space.sm) {
                Button(action: toggle) {
                    Text(label)
                        .font(DD.Font.bodyEmphasis.monospacedDigit())
                        .foregroundStyle(recording ? DD.fg : (keys.combo(for: action) == nil ? DD.dim : DD.fg))
                        .frame(minWidth: 96)
                }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityLabel(action.title)
                .accessibilityValue(keys.combo(for: action)?.glyphs ?? tr("Not set"))
                if keys.combo(for: action) != nil {
                    IconButton(systemImage: "xmark.circle.fill", label: tr("Clear shortcut")) { keys.set(nil, for: action); warning = nil }
                } else if let s = action.suggestion {
                    Button(s.glyphs) { apply(s) }
                        .buttonStyle(.plain).font(DD.Font.caption).foregroundStyle(DD.dim)
                        .help(tr("Use suggested shortcut"))
                }
            }
            if let warning {
                Text(warning).font(DD.Font.caption).foregroundStyle(DD.warn)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onDisappear { stop() }
    }

    private var label: String {
        if recording { return tr("Press shortcut…") }
        return keys.combo(for: action)?.glyphs ?? tr("Record")
    }

    private func toggle() { recording ? stop() : start() }

    private func start() {
        recording = true
        warning = nil
        keys.suspend(true)
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown]) { e in
            handle(e)
            return nil
        }
    }

    private func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        if recording { recording = false; keys.suspend(false) }
    }

    private func handle(_ e: NSEvent) {
        switch Int(e.keyCode) {
        case kVK_Escape: stop(); return
        case kVK_Delete, kVK_ForwardDelete:
            if e.modifierFlags.intersection(.deviceIndependentFlagsMask).isEmpty { keys.set(nil, for: action); warning = nil; stop(); return }
        default: break
        }
        guard let combo = KeyCombo(event: e) else { return }
        guard combo.isValidGlobal else { warning = tr("Include ⌃, ⌥ or ⌘ so it doesn't interfere with typing."); return }
        stop()
        apply(combo)
    }

    private func apply(_ combo: KeyCombo) {
        var notes: [String] = []
        if let other = keys.conflict(for: combo, except: action) {
            notes.append(tr("Also used by “{name}” — only one will fire.", ["name": other.title]))
        }
        if let sys = combo.systemReservedName {
            notes.append(tr("This is a system shortcut ({name}).", ["name": sys]))
        }
        warning = notes.isEmpty ? nil : notes.joined(separator: " ")
        keys.set(combo, for: action)
    }
}
