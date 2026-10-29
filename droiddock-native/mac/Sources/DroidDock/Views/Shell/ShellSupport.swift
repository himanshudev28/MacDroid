import AppKit
import CoreImage
import Observation
import SwiftUI

// MARK: - QR

/// Crisp pairing QR: CoreImage generator, integer nearest-neighbour upscale (no blur when the card scales it).
enum PairingQR {
    static func image(for text: String, module: CGFloat = 8) -> NSImage? {
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(text.utf8), forKey: "inputMessage")
        f.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f.outputImage else { return nil }
        let scaled = out.samplingNearest().transformed(by: CGAffineTransform(scaleX: module, y: module))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }
}

func shellDataBytes(_ s: String) -> Data? {
    guard let payload = s.split(separator: ",", maxSplits: 1).last else { return nil }
    return Data(base64Encoded: String(payload), options: .ignoreUnknownCharacters)
}

/// "2m", "3h", "Yesterday" style stamp for a millisecond epoch.
func shellRelativeTime(_ ms: Double?) -> String? {
    guard let ms, ms > 0 else { return nil }
    let secs = Date().timeIntervalSince1970 - ms / 1000
    if secs < 45 { return tr("now") }
    if secs < 3600 { return "\(max(1, Int(secs / 60)))m" }
    if secs < 86_400 { return "\(Int(secs / 3600))h" }
    return Date(timeIntervalSince1970: ms / 1000).formatted(.dateTime.month(.abbreviated).day())
}

func shellBytes(_ v: Double) -> String { ByteCountFormatter.string(fromByteCount: Int64(v), countStyle: .file) }

// MARK: - Shell state (card assets, recents, call timer)

/// Everything the phone card needs that is not already in `AppModel`: wallpaper (+ tint, computed once),
/// pairing QR, album art, recent apps and their icons, the ring toggle and the call timer.
@Observable @MainActor
final class ShellState {
    static let shared = ShellState()
    static let recentsKey = "droiddock.recentApps"

    private(set) var wallpaper: NSImage?
    private(set) var tint: Color?
    private(set) var qr: NSImage?
    private(set) var artwork: NSImage?
    private(set) var recents: [String] = UserDefaults.standard.stringArray(forKey: recentsKey) ?? []
    private(set) var appNames: [String: String] = [:]
    private(set) var appIcons: [String: NSImage] = [:]
    private(set) var callStart: Date?
    var ringing = false
    /// User tapped "Pair a phone" on an offline card.
    var forcePairing = false

    @ObservationIgnored private var started = false
    @ObservationIgnored private var qrURL: String?
    @ObservationIgnored private var artSrc: String?
    @ObservationIgnored private var wasConnected = false

    func start() {
        guard !started else { return }
        started = true
        track()
    }

    private func track() {
        let m = AppModel.shared
        withObservationTracking {
            _ = m.isConnected; _ = m.mediaArt; _ = m.activeCall?.state; _ = m.pairing?.url
        } onChange: { [weak self] in
            Task { @MainActor in self?.sync(); self?.track() }
        }
        sync()
    }

    private func sync() {
        let m = AppModel.shared
        if let url = m.pairing?.url, url != qrURL { qrURL = url; qr = PairingQR.image(for: url) }
        if m.mediaArt != artSrc {
            artSrc = m.mediaArt
            if let src = m.mediaArt {
                Task { [weak self] in
                    let img = await ImageCache.shared.image(fromDataURL: src, maxPixel: 600)
                    guard let self, self.artSrc == src else { return }
                    self.artwork = img
                }
            } else { artwork = nil }
        }
        let live = m.activeCall.map { ["active", "dialing"].contains($0.state) } ?? false
        if live { if callStart == nil { callStart = Date() } } else { callStart = nil }
        if m.isConnected != wasConnected {
            wasConnected = m.isConnected
            if m.isConnected { forcePairing = false; loadPhoneAssets() }
            else { wallpaper = nil; tint = nil; ringing = false }
        }
    }

    private func loadPhoneAssets() {
        Task {
            if let s = try? await Core.shared.wallpaperGet(), let img = await ImageCache.shared.image(fromDataURL: s, maxPixel: 900) {
                guard wasConnected else { return }
                wallpaper = img
                tint = img.ddAverageColor()
            }
        }
        Task {
            if let apps = try? await Core.shared.appsList() {
                appNames = Dictionary(apps.map { ($0.pkg, $0.label) }, uniquingKeysWith: { a, _ in a })
            }
        }
        for pkg in recents { loadIcon(pkg) }
    }

    /// Main window closed to the menu bar: drop everything only the window needs (reloaded on reopen).
    func purgeWindowAssets() {
        wallpaper = nil; tint = nil; appIcons = [:]
        ImageCache.shared.purge()
    }
    /// Window shown again: bring back what `purgeWindowAssets` dropped.
    func restoreWindowAssets() {
        if wasConnected, wallpaper == nil { loadPhoneAssets() }
    }

    private func loadIcon(_ pkg: String) {
        guard appIcons[pkg] == nil else { return }
        Task { if let img = await PhoneIconStore.shared.load(pkg) { appIcons[pkg] = img } }
    }

    // Recents (Mac-side list of apps launched from here, as in the old app).
    func recordRecent(_ pkg: String) {
        recents = ([pkg] + recents.filter { $0 != pkg }).prefix(8).map { $0 }
        UserDefaults.standard.set(recents, forKey: Self.recentsKey)
        loadIcon(pkg)
    }
    func removeRecent(_ pkg: String) {
        recents.removeAll { $0 == pkg }
        UserDefaults.standard.set(recents, forKey: Self.recentsKey)
    }
    func recentApps() -> [DDRecentApp] {
        recents.prefix(5).map { pkg in
            DDRecentApp(id: pkg, name: appNames[pkg] ?? (pkg.split(separator: ".").last.map(String.init) ?? pkg),
                        icon: appIcons[pkg])
        }
    }

    /// When the phone last reported a media state. `positionAsOf` must be that moment, not "now": a stamp taken on every
    /// call reset the progress bar whenever any view re-evaluated, so it never advanced.
    @ObservationIgnored private var mediaStamp = (state: MediaState?.none, at: Date())

    func nowPlaying(_ m: AppModel) -> DDNowPlaying? {
        guard let md = m.media, md.active else { return nil }
        if mediaStamp.state != md { mediaStamp = (md, Date()) }
        return DDNowPlaying(title: md.title ?? tr("Unknown track"), artist: md.artist, app: md.app,
                            isPlaying: md.playing ?? false, position: max(0, (md.pos ?? 0) / 1000),
                            duration: max(0, (md.dur ?? 0) / 1000), positionAsOf: mediaStamp.at,
                            artwork: artwork, trackKey: md.trackKey)
    }
}

// MARK: - Actions shared by the card, dashboard, menu bar, Dock menu, hotkeys and the Phone menu

@MainActor
enum ShellActions {
    private static var model: AppModel { AppModel.shared }

    static func requireLink() -> Bool {
        if model.isConnected { return true }
        model.toast(.info, tr("No phone linked"))
        return false
    }

    static func pickAndSend() {
        guard requireLink() else { return }
        Task {
            let urls = await FilePicker.open(multiple: true, title: tr("Send to phone"), prompt: tr("Send"))
            sendFiles(urls)
        }
    }

    static func sendFiles(_ urls: [URL]) {
        let files = urls.filter { $0.isFileURL }
        guard !files.isEmpty, requireLink() else { return }
        Task {
            var sent = 0
            for u in files {
                do { try await Core.shared.fsPush(localPath: u.path, dest: "/sdcard/Download"); sent += 1 }
                catch { model.toast(.bad, "\(u.lastPathComponent): \(error.localizedDescription)") }
            }
            if sent == 1 { model.toast(.ok, tr("Sent {name} to Downloads", ["name": files[0].lastPathComponent])) }
            else if sent > 1 { model.toast(.ok, tn("Sent {n} file to Downloads", "Sent {n} files to Downloads", sent)) }
        }
    }

    static func screenshot() {
        guard requireLink() else { return }
        Task { do { _ = try await Core.shared.phoneScreenshot() } catch { model.toast(.bad, error.localizedDescription) } }
    }

    static func toggleRing() {
        guard requireLink() else { return }
        let next = !ShellState.shared.ringing
        Task {
            do {
                ShellState.shared.ringing = try await Core.shared.ringPhone(on: next)
                if ShellState.shared.ringing { model.toast(.info, tr("Ringing your phone. It stops by itself after a minute.")) }
            } catch { model.toast(.bad, error.localizedDescription) }
        }
    }

    static func pushClipboard() {
        guard requireLink() else { return }
        Task {
            do { try await Core.shared.clipboardPushNow(); model.toast(.ok, tr("Clipboard sent to phone")) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }

    static func toggleMirror(source: String = "screen") {
        if model.mirror != nil || MirrorSession.shared.isActive { MirrorSession.shared.stop(); return }
        guard requireLink() else { return }
        Task { await MirrorSession.shared.start(source: source) }
    }

    static func togglePause() {
        let until: Double? = model.isPaused ? nil : (Date().timeIntervalSince1970 + 3600) * 1000
        Task { _ = try? await Core.shared.pauseSet(until: until) }
    }

    static func media(_ c: DDMediaCommand) {
        Task {
            switch c {
            case .previous: try? await Core.shared.mediaCmd("prev")
            case .next: try? await Core.shared.mediaCmd("next")
            case .playPause: try? await Core.shared.mediaCmd(model.media?.playing == true ? "pause" : "play")
            case .seek(let s): try? await Core.shared.mediaCmd("seek", value: (s * 1000).rounded())
            }
        }
    }

    static func launchApp(_ pkg: String) {
        guard requireLink() else { return }
        ShellState.shared.recordRecent(pkg)
        Task { do { try await Core.shared.appLaunch(pkg: pkg) } catch { model.toast(.bad, error.localizedDescription) } }
    }

    static func openUpdate() {
        Task {
            if let s = try? await Core.shared.updateInstall(), let u = URL(string: s) { NSWorkspace.shared.open(u) }
            else { model.toast(.bad, tr("Could not open the release page")) }
        }
    }

    static func showSettings() {
        model.selection = .settings
        WindowControl.showMain()
    }
}

extension Core {
    /// `menubar_opened`: the panel is visible, so the core can refresh the title and phone data.
    func menubarOpened() async throws { try await invoke("menubar_opened") }
}

// MARK: - Automation (`droiddock://`)

@MainActor
enum ShellRouter {
    static func install() {
        ShellState.shared.start()
        AppModel.shared.onAutomation = { handle($0) }
        #if DEBUG
        DebugPanelWindow.showIfRequested()
        if let d = ProcessInfo.processInfo.environment["DD_START_DEST"].flatMap({ Destination(rawValue: $0) }) { AppModel.shared.selection = d }
        #endif
    }

    static func handle(_ a: AutomationEvent) {
        let model = AppModel.shared
        let serial = model.devices.first { $0.state == "device" }?.serial
        switch a.action {
        case "open":
            WindowControl.showMain()
        case "mirror":
            model.selection = .mirror
            Task { await MirrorSession.shared.start(source: "screen") }
        case "mirror-adb":
            guard let serial else { model.toast(.info, tr("Connect the phone over ADB to mirror this way")); return }
            Task { do { try await Core.shared.adbMirror(serial: serial) } catch { model.toast(.bad, error.localizedDescription) } }
        case "desktop":
            guard let serial else { model.toast(.info, tr("Connect the phone over ADB to use desktop mode")); return }
            Task { do { try await Core.shared.adbDesktop(serial: serial) } catch { model.toast(.bad, error.localizedDescription) } }
        case "app":
            guard let pkg = a.pkg else { return }
            if model.config?.openAppsOnMac == true, let serial {
                ShellState.shared.recordRecent(pkg)
                Task { do { try await Core.shared.adbMirrorApp(serial: serial, package: pkg, newDisplay: false) }
                       catch { model.toast(.bad, error.localizedDescription) } }
            } else { ShellActions.launchApp(pkg) }
        case "clipboard-push":
            ShellActions.pushClipboard()
        default:
            dlog("automation: unhandled \(a.action)")
        }
    }
}

#if DEBUG
/// `DD_SHOW_PANEL=1`: hosts the menu-bar panel in a plain window so it can be screenshotted without the status item.
@MainActor
enum DebugPanelWindow {
    static var window: NSWindow?
    static func showIfRequested() {
        guard ProcessInfo.processInfo.environment["DD_SHOW_PANEL"] != nil else { return }
        let host = NSHostingController(rootView: MenuBarPanel().environment(AppModel.shared).ddAppearance(AppearanceSettings.shared))
        let w = NSWindow(contentViewController: host)
        w.title = "panel"; w.styleMask = [.titled, .closable]
        w.setFrameOrigin(NSPoint(x: 60, y: 200)); w.makeKeyAndOrderFront(nil)
        window = w
    }
}
#endif
