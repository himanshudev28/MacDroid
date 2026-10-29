import AppKit
import ObjectiveC

/// An Apple-style volume / brightness overlay, drawn when the phone changes either.
///
/// Why our own: macOS shows its HUD only for the hardware keys. `set volume`, a CoreAudio write and
/// DisplayServices all change the level silently, and the one thing that does raise the system HUD (a synthetic
/// media key) moves the level by a fixed step, so it can't express "set to 63%". The step buttons still use media
/// keys in the core, so those get the real HUD. Absolute slider sets come here.
///
/// One panel is reused for every update: a drag just moves the bar and pushes the fade-out back. It never takes
/// focus, ignores the mouse and follows the user across Spaces.
@MainActor
final class SystemHUD {
    static let shared = SystemHUD()

    struct Payload: Decodable {
        var kind: String
        var level: Double
        var muted: Bool?
    }

    private var panel: NSPanel?
    private let icon = NSImageView()
    private let bar = LevelBar()
    private var hideTask: Task<Void, Never>?
    private var shownKind = ""

    private let size = NSSize(width: 300, height: 56)

    func show(_ p: Payload) {
        if NativeOSD.show(kind: p.kind, level: p.level, muted: p.muted ?? false) { return }
        showCustom(p)
    }

    private func showCustom(_ p: Payload) {
        let panel = panel ?? makePanel()
        self.panel = panel
        let level = min(max(p.level, 0), 1)
        let muted = p.muted ?? false

        if shownKind != p.kind || icon.image == nil || p.kind == "volume" {
            icon.image = Self.symbol(kind: p.kind, level: level, muted: muted)
            shownKind = p.kind
        }
        bar.level = muted ? 0 : level

        position(panel)
        if !panel.isVisible || panel.alphaValue < 1 {
            panel.alphaValue = 1
            panel.orderFrontRegardless()
        }
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1200))
            guard !Task.isCancelled, let self, let p = self.panel else { return }
            NSAnimationContext.runAnimationGroup({ $0.duration = 0.25; p.animator().alphaValue = 0 },
                                                 completionHandler: { MainActor.assumeIsolated { if p.alphaValue == 0 { p.orderOut(nil) } } })
        }
    }

    /// Screen coordinates of the panel, for a window-only screenshot in tests.
    var windowNumber: Int? { panel.map { $0.windowNumber } }

    private static func symbol(kind: String, level: Double, muted: Bool) -> NSImage? {
        let name: String
        if kind == "brightness" {
            name = level < 0.5 ? "sun.min.fill" : "sun.max.fill"
        } else if muted || level <= 0 {
            name = "speaker.slash.fill"
        } else if level < 0.34 {
            name = "speaker.wave.1.fill"
        } else if level < 0.67 {
            name = "speaker.wave.2.fill"
        } else {
            name = "speaker.wave.3.fill"
        }
        let cfg = NSImage.SymbolConfiguration(pointSize: 17, weight: .medium)
        return NSImage(systemSymbolName: name, accessibilityDescription: nil)?.withSymbolConfiguration(cfg)
    }

    private func position(_ panel: NSPanel) {
        let screen = NSScreen.main ?? NSScreen.screens.first
        guard let f = screen?.visibleFrame else { return }
        panel.setFrameOrigin(NSPoint(x: f.midX - size.width / 2, y: f.minY + 72))
    }

    private func makePanel() -> NSPanel {
        let p = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isOpaque = false; p.backgroundColor = .clear; p.hasShadow = true
        p.level = .statusBar; p.hidesOnDeactivate = false; p.ignoresMouseEvents = true
        p.isReleasedWhenClosed = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient, .ignoresCycle]

        let fx = NSVisualEffectView(frame: NSRect(origin: .zero, size: size))
        fx.material = .hudWindow; fx.blendingMode = .behindWindow; fx.state = .active
        fx.wantsLayer = true
        fx.layer?.cornerRadius = 18; fx.layer?.cornerCurve = .continuous; fx.layer?.masksToBounds = true

        icon.contentTintColor = .labelColor
        icon.imageScaling = .scaleProportionallyDown
        icon.frame = NSRect(x: 18, y: 14, width: 30, height: 28)
        bar.frame = NSRect(x: 60, y: 24, width: size.width - 60 - 22, height: 8)
        fx.addSubview(icon); fx.addSubview(bar)
        p.contentView = fx
        return p
    }
}

/// The macOS-style pill: dim track, bright fill. Layer-backed, no animation on level changes so a drag tracks 1:1.
private final class LevelBar: NSView {
    var level: Double = 0 { didSet { if level != oldValue { needsLayout = true } } }
    private let fill = CALayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.cornerRadius = 4; layer?.masksToBounds = true
        layer?.backgroundColor = NSColor.labelColor.withAlphaComponent(0.22).cgColor
        fill.backgroundColor = NSColor.labelColor.withAlphaComponent(0.95).cgColor
        layer?.addSublayer(fill)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        fill.frame = CGRect(x: 0, y: 0, width: bounds.width * level, height: bounds.height)
        CATransaction.commit()
    }
}

/// The genuine macOS volume / brightness HUD, via the private OSD.framework (`OSDManager`, hosted by OSDUIHelper).
///
/// Written from the public shape of the call (class `OSDManager`, selector
/// `showImage:onDisplayID:priority:msecUntilFade:filledChiclets:totalChiclets:locked:`, image codes 1 = brightness,
/// 3 = speaker, 4 = muted speaker), as documented by the MIT-licensed MonitorControl project
/// (github.com/MonitorControl/MonitorControl, `OSDUtils.swift`). No code copied.
/// Private API: if the framework, class or selector is missing we return false and the caller draws its own overlay.
@MainActor
enum NativeOSD {
    private typealias ShowFn = @convention(c) (AnyObject, Selector, Int64, UInt32, UInt32, UInt32, UInt32, UInt32, Bool) -> Void
    private static let imageBrightness: Int64 = 1
    private static let imageSpeaker: Int64 = 3
    private static let imageMuted: Int64 = 4
    private static let totalChiclets: UInt32 = 64
    private static let selector = NSSelectorFromString("showImage:onDisplayID:priority:msecUntilFade:filledChiclets:totalChiclets:locked:")

    private static var manager: AnyObject?
    private static var imp: ShowFn?
    private static var loaded = false
    private static var logged = false

    private static func load() {
        guard !loaded else { return }
        loaded = true
        _ = dlopen("/System/Library/PrivateFrameworks/OSD.framework/OSD", RTLD_LAZY)
        if NSClassFromString("OSDManager") == nil {
            Bundle(path: "/System/Library/PrivateFrameworks/OSD.framework")?.load()
        }
        guard let cls = NSClassFromString("OSDManager") as? NSObject.Type,
              cls.responds(to: NSSelectorFromString("sharedManager")),
              let m = cls.perform(NSSelectorFromString("sharedManager"))?.takeUnretainedValue(),
              m.responds(to: selector),
              let method = class_getInstanceMethod(type(of: m), selector) else { return }
        manager = m
        imp = unsafeBitCast(method_getImplementation(method), to: ShowFn.self)
    }

    /// Returns true when the system HUD was asked to draw.
    static func show(kind: String, level: Double, muted: Bool) -> Bool {
        load()
        guard let m = manager, let imp else {
            if !logged { logged = true; NSLog("[DroidDock] volume/brightness HUD: OSD.framework unavailable, using custom overlay") }
            return false
        }
        if !logged { logged = true; NSLog("[DroidDock] volume/brightness HUD: native macOS OSD") }
        let l = min(max(level, 0), 1)
        let image: Int64 = kind == "brightness" ? imageBrightness : (muted ? imageMuted : imageSpeaker)
        let filled = (kind != "brightness" && muted) ? 0 : UInt32((l * Double(totalChiclets)).rounded())
        imp(m, selector, image, CGMainDisplayID(), 0x1f4, 1000, filled, totalChiclets, false)
        return true
    }
}
