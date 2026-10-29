#if DEBUG
import AppKit

/// QA only. `DD_TEST_TITLEBAR=1` logs the main window frame and the standard buttons (frame + hit-test), then runs the
/// sequence in `DD_TEST_SEQ` (comma list of `toggle`, `click-min`, `restore`, `wait`), logging `window.frame` after each.
@MainActor
enum DebugWindowProbe {
    static func runIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard env["DD_TEST_TITLEBAR"] == "1" else { return }
        let seq = (env["DD_TEST_SEQ"] ?? "").split(separator: ",").map(String.init)
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            report("start")
            for step in seq {
                switch step {
                case let t where t.hasPrefix("go:"): AppModel.shared.selection = Destination(rawValue: String(t.dropFirst(3))) ?? .dashboard
                case let t where t.hasPrefix("pane:"): UserDefaults.standard.set(String(t.dropFirst(5)), forKey: SettingsPane.defaultsKey)
                case let t where t.hasPrefix("layout:"): UserDefaults.standard.set(String(t.dropFirst(7)), forKey: AppLayout.defaultsKey)
                case let t where t.hasPrefix("theme:"): AppearanceSettings.shared.theme = ThemeChoice(rawValue: String(t.dropFirst(6))) ?? .system
                case "phone:show": ShellChrome.shared.setPhoneHidden(ShellChrome.currentLayout, false)
                case "phone:hide": ShellChrome.shared.setPhoneHidden(ShellChrome.currentLayout, true)
                case "collapse": ShellChrome.shared.setCollapsed(ShellChrome.currentLayout, true)
                case "expand": ShellChrome.shared.setCollapsed(ShellChrome.currentLayout, false)
                case let t where t.hasPrefix("invoke:"):
                    let parts = t.dropFirst(7).split(separator: "|", maxSplits: 1).map(String.init)
                    let args = parts.count > 1 ? ((try? JSONSerialization.jsonObject(with: Data(parts[1].replacingOccurrences(of: "~", with: ",").utf8))) as? [String: Any] ?? [:]) : [:]
                    let cmd = parts[0]
                    Task { do { let d = try await Core.shared.raw(cmd, args, body: nil); dlog("probe invoke \(cmd) -> \(String(decoding: d.prefix(600), as: UTF8.self))") } catch { dlog("probe invoke \(cmd) failed: \(error)") } }
                case let t where t.hasPrefix("wmirror:"): let src = String(t.dropFirst(8)); Task { await MirrorSession.shared.start(source: src) }
                case let t where t.hasPrefix("mkey:"): MirrorSession.shared.input.key(String(t.dropFirst(5)))
                case let t where t.hasPrefix("mtap:"):
                    let p = t.dropFirst(5).split(separator: ";").compactMap { Double($0) }
                    if p.count == 2 { MirrorSession.shared.input.pointerDown(x: p[0], y: p[1]); try? await Task.sleep(for: .milliseconds(80)); MirrorSession.shared.input.pointerUp(x: p[0], y: p[1]) }
                case let t where t.hasPrefix("waitfile:"):
                    // QA sync point: block until the file exists (touch it from the shell), then remove it.
                    let path = String(t.dropFirst(9))
                    for _ in 0..<600 where !FileManager.default.fileExists(atPath: path) { try? await Task.sleep(for: .milliseconds(500)) }
                    try? FileManager.default.removeItem(atPath: path)
                case let t where t.hasPrefix("nclick:"):
                    // nclick:<pkg-or-app substring>[|own|banner] simulates clicking that notification's banner.
                    let a = t.dropFirst(7).split(separator: "|").map(String.init)
                    let m = a[0].lowercased()
                    let list = AppModel.shared.notifications
                    dlog("probe nclick candidates: \(Set(list.map { "\($0.pkg ?? "?")/\($0.app ?? "?")" }).sorted())")
                    if let n = list.first(where: { ($0.pkg ?? "").lowercased().contains(m) || ($0.app ?? "").lowercased().contains(m) }) {
                        dlog("probe nclick -> key=\(n.key) pkg=\(n.pkg ?? "?")")
                        if a.count > 1 && a[1] == "own" { NotificationClickActions.shared.openInOwnWindow(key: n.key) }
                        else { NotificationClickActions.shared.handleBannerClick(key: n.key) }
                    } else { dlog("probe nclick: no match for \(m)") }
                case "mhint": MirrorSession.shared.debugShowControlHint()
                case "mmute": MirrorSession.shared.toggleMute()
                case "mpin": MirrorSession.shared.onTop.toggle()
                case "mflip": MirrorSession.shared.flipCamera()
                case "mscreenshot": MirrorSession.shared.screenshot()
                case "mpop": MirrorSession.shared.setPlacement(inWindow: false)
                case "minwin": MirrorSession.shared.setPlacement(inWindow: true)
                case "banner": BannerCenter.shared.show(NativeNotification(id: "probe-\(Int.random(in: 0...9999))", title: "QA Test", body: "[DroidDock QA test]\nThis is a test banner, not a real message", replyKey: "k", sound: false))
                case let t where t.hasPrefix("bstress"): await BannerCenter.shared.debugStress(shotPrefix: t.count > 8 ? String(t.dropFirst(8)) : nil)
                case "banner:plain": BannerCenter.shared.show(NativeNotification(id: "probe-\(Int.random(in: 0...9999))", title: "Paytm", body: "Received 10 rupees", replyKey: nil, sound: false))
                case "notifdump": dlog("probe notifs total=\(AppModel.shared.notifications.count) replyable=\(AppModel.shared.notifications.filter { $0.replyable == true }.map { "\($0.app ?? "?")" })")
                case let t where t.hasPrefix("bshot:"): if let w = NSApp.windows.first(where: { $0 is BannerPanel && $0.isVisible }) { capture(w.windowNumber, String(t.dropFirst(6))); dlog("probe banner frame=\(NSStringFromRect(w.frame))") } else { dlog("probe no banner visible") }
                case let t where t.hasPrefix("ball:"):
                    let ws = NSApp.windows.filter { $0 is BannerPanel && $0.isVisible }
                    dlog("probe ball count=\(ws.count) frames=\(ws.map { NSStringFromRect($0.frame) })")
                    for (i, w) in ws.enumerated() { capture(w.windowNumber, "\(t.dropFirst(5))-\(i).png") }
                case let t where t.hasPrefix("callstate:"):
                    let st = String(t.dropFirst(10))
                    AppModel.shared.debugSetCall(st)
                case "mainhide": window?.orderOut(nil); dlog("probe main window hidden visible=\(window?.isVisible ?? false)")
                case "calldismiss": CallOverlayController.shared.dismiss()
                case let t where t.hasPrefix("callshot:"):
                    if let w = NSApp.windows.first(where: { $0.isVisible && $0.className.contains("CallPanel") }) { capture(w.windowNumber, String(t.dropFirst(9))); dlog("probe call panel frame=\(NSStringFromRect(w.frame))") } else { dlog("probe no call panel visible") }
                case "breply": if let w = NSApp.windows.first(where: { $0 is BannerPanel && $0.isVisible }) { dlog("probe banner count=\(NSApp.windows.filter { $0 is BannerPanel && $0.isVisible }.count) size=\(w.frame.size)") }
                case let t where t.hasPrefix("def:"):
                    // QA: def:key=value sets this binary's own UserDefaults (bool/number/string).
                    let kv = t.dropFirst(4).split(separator: "=", maxSplits: 1).map(String.init)
                    if kv.count == 2 { let ud = UserDefaults.standard; if kv[1] == "true" || kv[1] == "false" { ud.set(kv[1] == "true", forKey: kv[0]) } else if let d = Double(kv[1]) { ud.set(d, forKey: kv[0]) } else { ud.set(kv[1], forKey: kv[0]) } }
                case let t where t.hasPrefix("glass:"):
                    AppearanceSettings.shared.liquidOpacity = Double(String(t.dropFirst(6))) ?? 1
                case let t where t.hasPrefix("reduce:"): AppearanceSettings.shared.reduceTransparencyPreference = t.hasSuffix("on")
                case let t where t.hasPrefix("nsearch:"): NotifSettingsDebug.shared.search = String(t.dropFirst(8))
                case let t where t.hasPrefix("gear:"): NotifSettingsDebug.shared.openPkg = String(t.dropFirst(5))
                case let t where t.hasPrefix("popshot:"):
                    // Capture the frontmost non-main visible window of ours (an open popover).
                    if let w = NSApp.windows.first(where: { $0.isVisible && $0 !== window && $0.className.contains("Popover") }) { capture(w.windowNumber, String(t.dropFirst(8))) } else { dlog("probe no popover window; mine: \(NSApp.windows.filter { $0.isVisible }.map { $0.className })") }
                case "fake:notifs": AppModel.shared.debugFakeNotifications()
                case "fake:content": AppModel.shared.debugFakeContent()
                case "fake:content-bad": AppModel.shared.debugFakeContent(badDuration: true)
                case "menubar:open": MenuBarController.shared.open()
                case "menubar:close": MenuBarController.shared.close()
                case "menubar:close-now": MenuBarController.shared.close(animated: false)
                case let t where t.hasPrefix("mbshot:"):
                    let n = MenuBarController.shared.debugWindowNumber
                    dlog("probe menubar window=\(n) frame=\(String(describing: MenuBarController.shared.debugPanelFrame))")
                    if n > 0 { capture(n, String(t.dropFirst(7))) }
                case let t where t.hasPrefix("mbseq:"):
                    // mbseq:<prefix>|<count>|<interval ms>: open the panel, then capture it <count> times without waiting on each.
                    let p = t.dropFirst(6).split(separator: "|").map(String.init)
                    let count = Int(p.count > 1 ? p[1] : "") ?? 6, gap = Int(p.count > 2 ? p[2] : "") ?? 100
                    MenuBarController.shared.open()
                    for i in 0..<count {
                        let n = MenuBarController.shared.debugWindowNumber
                        if n > 0 { captureAsync(n, "\(p[0])-\(i).png") }
                        try? await Task.sleep(for: .milliseconds(gap))
                    }
                case let t where t.hasPrefix("mbclose:"):
                    let p = t.dropFirst(8).split(separator: "|").map(String.init)
                    let count = Int(p.count > 1 ? p[1] : "") ?? 4, gap = Int(p.count > 2 ? p[2] : "") ?? 100
                    MenuBarController.shared.close()
                    for i in 0..<count {
                        let n = MenuBarController.shared.debugWindowNumber
                        if n > 0 { captureAsync(n, "\(p[0])-\(i).png") }
                        try? await Task.sleep(for: .milliseconds(gap))
                    }
                case "fake:on": AppModel.shared.debugFakeConnected()
                case "fake:rtt": AppModel.shared.debugFakeConnected(rtt: 23)
                case "layoutlive:classic", "layoutlive:phoneTabs", "layoutlive:grouped", "layoutlive:minimal":
                    // Same path as the Settings picker: AppStorage write + WindowFit via the layout observer.
                    UserDefaults.standard.set(String(step.dropFirst(11)), forKey: AppLayout.defaultsKey)
                case "toggle": ShellChrome.shared.toggleSidebar(ShellChrome.currentLayout)
                case "click-min": clickButton(.miniaturizeButton)
                case "click-close-probe": hitTest(.closeButton)
                case "restore": window?.deminiaturize(nil)
                case let t where t.hasPrefix("shot:"): shot(String(t.dropFirst(5)))
                case let t where t.hasPrefix("scrcpy:"):
                    let serial = String(t.dropFirst(7))
                    Task { do { try await Core.shared.scrcpyEmbeddedStart(serial: serial) } catch { dlog("probe scrcpy failed: \(error)") } }
                case let t where t.hasPrefix("mshot:"):
                    let n = MirrorSession.shared.debugWindowNumber
                    dlog("probe mirror window=\(n) phase=\(MirrorSession.shared.phase)")
                    if n > 0 { capture(n, String(t.dropFirst(6))) }
                case let t where t.hasPrefix("msize:"):
                    let p = t.dropFirst(6).split(separator: "x").compactMap { Double($0) }
                    if p.count == 2, let w = NSApp.windows.first(where: { $0.windowNumber == MirrorSession.shared.debugWindowNumber }) {
                        w.setFrame(NSRect(origin: w.frame.origin, size: w.frame.size), display: true)
                        let s = w.delegate?.windowWillResize?(w, to: NSSize(width: p[0], height: p[1])) ?? NSSize(width: p[0], height: p[1])
                        w.setFrame(NSRect(origin: w.frame.origin, size: s), display: true)
                        dlog("probe mirror frame=\(NSStringFromRect(w.frame))")
                    }
                case "mstop": MirrorSession.shared.stop()
                case let t where t.hasPrefix("size:"):
                    let p = t.dropFirst(5).split(separator: "x").compactMap { Double($0) }
                    if p.count == 2, let w = window { w.setContentSize(NSSize(width: p[0], height: p[1])) }
                default: break
                }
                try? await Task.sleep(for: .seconds(1.5))
                report(step)
            }
        }
    }

    private static func shot(_ path: String) {
        guard let w = window else { return }
        capture(w.windowNumber, path)
    }

    /// Window-only capture of one of our own windows.
    static func capture(_ windowNumber: Int, _ path: String) {
        let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        p.arguments = ["-x", "-o", "-l", String(windowNumber), path]
        try? p.run(); p.waitUntilExit()
    }

    private static func captureAsync(_ windowNumber: Int, _ path: String) {
        let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        p.arguments = ["-x", "-o", "-l", String(windowNumber), path]
        try? p.run()
    }

    private static var cached: NSWindow?
    private static var window: NSWindow? { if let w = WindowControl.mainWindow { cached = w }; return cached }

    private static func report(_ tag: String) {
        guard let w = window else { dlog("probe[\(tag)] no window"); return }
        dlog("probe[\(tag)] opaque=\(w.isOpaque) bgAlpha=\(w.backgroundColor.alphaComponent) glass=\(AppearanceSettings.shared.effectiveGlassOpacity)")
        dlog("probe[\(tag)] frame=\(NSStringFromRect(w.frame)) content=\(NSStringFromSize(w.contentLayoutRect.size)) mini=\(w.isMiniaturized)")
        for b in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] { hitTest(b) }
    }

    private static func point(_ type: NSWindow.ButtonType) -> (NSWindow, NSButton, NSPoint)? {
        guard let w = window, let b = w.standardWindowButton(type), let sv = b.superview else { return nil }
        let inWin = sv.convert(NSPoint(x: b.frame.midX, y: b.frame.midY), to: nil)
        return (w, b, inWin)
    }

    private static func hitTest(_ type: NSWindow.ButtonType) {
        guard let (w, b, p) = point(type) else { return }
        // contentView.hitTest takes a point in its superview's coordinates.
        let cv = w.contentView
        let hit = cv?.superview.flatMap { $0.hitTest($0.convert(p, from: nil)) }
        dlog("probe  \(type.rawValue) frame=\(NSStringFromRect(b.frame)) hit=\(hit.map { String(describing: Swift.type(of: $0)) } ?? "nil") isButton=\(hit === b)")
    }

    private static func clickButton(_ type: NSWindow.ButtonType) {
        guard let (w, _, p) = point(type) else { return }
        let screen = w.convertPoint(toScreen: p)
        let h = NSScreen.screens.first?.frame.height ?? 0
        let cg = CGPoint(x: screen.x, y: h - screen.y)
        // The Mac is in use: only click when the topmost window under the point is ours.
        let me = ProcessInfo.processInfo.processIdentifier
        let list = (CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]]) ?? []
        let top = list.first { info in
            guard let b = info[kCGWindowBounds as String] as? [String: Double], (info[kCGWindowAlpha as String] as? Double ?? 1) > 0
            else { return false }
            return CGRect(x: b["X"] ?? 0, y: b["Y"] ?? 0, width: b["Width"] ?? 0, height: b["Height"] ?? 0).contains(cg)
        }
        guard (top?[kCGWindowOwnerPID as String] as? Int32) == me else {
            dlog("probe click SKIPPED: topmost window at \(cg) is \(top?[kCGWindowOwnerName as String] ?? "none"), not ours")
            return
        }
        dlog("probe click at \(cg) screens=\(NSScreen.screens.map(\.frame)) winScreen=\(String(describing: w.screen?.frame)) key=\(w.isKeyWindow) vis=\(w.isVisible)")
        // postToPid: needs no Accessibility grant (the HID tap silently drops synthetic events without it).
        CGWarpMouseCursorPosition(cg)
        for t in [CGEventType.mouseMoved, .leftMouseDown, .leftMouseUp] {
            let e = CGEvent(mouseEventSource: nil, mouseType: t, mouseCursorPosition: cg, mouseButton: .left)
            e?.setIntegerValueField(.mouseEventWindowUnderMousePointer, value: Int64(w.windowNumber))
            e?.setIntegerValueField(.mouseEventClickState, value: 1)
            e?.post(tap: .cgSessionEventTap)
        }
        dlog("probe mouse now \(NSEvent.mouseLocation) trusted=\(AXIsProcessTrusted())")
    }
}
#endif
