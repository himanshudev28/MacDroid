import AppKit

/// What a script wants to do; only the verbs that send data, ring or mirror are guarded.
enum ScriptVerb {
    case ring, sendClipboard, sendFile(names: [String]), sendSMS(to: String), mirror

    /// File sends are never covered by "Always Allow": the path is arbitrary (`~/.ssh/id_rsa`).
    var alwaysAsks: Bool { if case .sendFile = self { return true }; return false }

    var prompt: (title: String, detail: String) {
        switch self {
        case .ring: return (tr("Allow a script to ring your phone?"), tr("A script is asking DroidDock to make your phone ring."))
        case .sendClipboard: return (tr("Allow a script to send your clipboard to the phone?"), tr("The current Mac clipboard would be copied to your phone."))
        case .sendFile(let names):
            let shown = names.prefix(5).map { "• " + $0 }.joined(separator: "\n")
            let more = names.count > 5 ? "\n" + tr("and {n} more", ["n": names.count - 5]) : ""
            let head = tn("A script wants to send {n} file to your phone's Downloads folder.", "A script wants to send {n} files to your phone's Downloads folder.", names.count)
            return (tr("Allow a script to send files to your phone?"), head + "\n\n" + shown + more)
        case .sendSMS(let to): return (tr("Allow a script to send a text message?"), tr("A script wants to send a text from your phone to {number}.", ["number": to]))
        case .mirror: return (tr("Start mirroring your phone?"), tr("A link or script asked DroidDock to show your phone screen on this Mac."))
        }
    }
}

/// Confirmation for scripted actions. Once the user picks "Always Allow" (`scriptingAllowSend`), it stops asking,
/// except for file sends. Never blocks the main run loop (see `ConfirmPanel`).
@MainActor
enum ScriptGuard {
    static var allowAlways: Bool {
        get { UserDefaults.standard.bool(forKey: OTPPrefs.scriptingAllowSendKey) }
        set { UserDefaults.standard.set(newValue, forKey: OTPPrefs.scriptingAllowSendKey) }
    }

    static func authorize(_ verb: ScriptVerb) async -> Bool {
        if allowAlways, !verb.alwaysAsks { return true }
        let p = verb.prompt
        let detail = p.detail + "\n\n" + tr("You can change this in Settings.")
        if verb.alwaysAsks {
            return await ConfirmPanel.ask(title: p.title, detail: detail, buttons: [tr("Allow"), tr("Don't Allow")]) == 0
        }
        switch await ConfirmPanel.ask(title: p.title, detail: detail, buttons: [tr("Allow Once"), tr("Always Allow"), tr("Don't Allow")]) {
        case 0: return true
        case 1: allowAlways = true; return true
        default: return false
        }
    }
}

/// Confirmation for `droiddock://` links that change something. Any web page can open such a link, so the Mac asks first.
/// One question at a time: links that arrive while one is open are dropped.
@MainActor
enum URLRouteGuard {
    static func confirm(action: String, pkg: String?) async -> Bool {
        let (title, detail): (String, String)
        switch action {
        case "clipboard-push": (title, detail) = (tr("Send your clipboard to the phone?"), tr("A link or script asked DroidDock to copy your Mac clipboard to your phone."))
        case "mirror-adb": (title, detail) = (tr("Start mirroring your phone?"), tr("A link or script asked DroidDock to show your phone screen on this Mac."))
        case "desktop": (title, detail) = (tr("Start desktop mode?"), tr("A link or script asked DroidDock to start desktop mode on your phone."))
        case "app": (title, detail) = (tr("Open an app from your phone?"), tr("A link or script asked DroidDock to open {app}.", ["app": pkg ?? "?"]))
        default: (title, detail) = (tr("Allow this request?"), tr("A link or script asked DroidDock to do something with your phone."))
        }
        return await ConfirmPanel.ask(title: title, detail: detail, buttons: [tr("Allow"), tr("Don't Allow")]) == 0
    }
}

/// A small floating confirmation window. Unlike `NSAlert.runModal()` it does not run a nested modal loop, so core events
/// (notifications, calls) keep flowing while it is open. Only one at a time: `ask` returns nil at once if one is showing.
/// Result = index of the pressed button (the first is the default), nil if closed, escaped or dropped.
@MainActor
final class ConfirmPanel: NSObject, NSWindowDelegate {
    private static var current: ConfirmPanel?
    private var cont: CheckedContinuation<Int?, Never>?
    private var panel: NSPanel?

    static func ask(title: String, detail: String, buttons: [String]) async -> Int? {
        guard current == nil else { return nil }
        let c = ConfirmPanel()
        current = c
        let r = await withCheckedContinuation { (k: CheckedContinuation<Int?, Never>) in
            c.cont = k
            c.show(title: title, detail: detail, buttons: buttons)
        }
        current = nil
        return r
    }

    private func show(title: String, detail: String, buttons: [String]) {
        let p = NSPanel(contentRect: .zero, styleMask: [.titled, .closable], backing: .buffered, defer: false)
        p.level = .floating
        p.isReleasedWhenClosed = false
        p.delegate = self
        let icon = NSImageView(image: NSApp.applicationIconImage)
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.widthAnchor.constraint(equalToConstant: 56).isActive = true
        icon.heightAnchor.constraint(equalToConstant: 56).isActive = true
        let t = NSTextField(wrappingLabelWithString: title)
        t.font = .boldSystemFont(ofSize: 13); t.preferredMaxLayoutWidth = 330
        let d = NSTextField(wrappingLabelWithString: detail)
        d.font = .systemFont(ofSize: NSFont.smallSystemFontSize); d.preferredMaxLayoutWidth = 330
        let text = NSStackView(views: [t, d]); text.orientation = .vertical; text.alignment = .leading; text.spacing = 6
        let top = NSStackView(views: [icon, text]); top.orientation = .horizontal; top.alignment = .top; top.spacing = 14
        let btns = buttons.enumerated().map { i, name -> NSButton in
            let b = NSButton(title: name, target: self, action: #selector(pressed(_:)))
            b.tag = i; b.bezelStyle = .rounded
            if i == 0 { b.keyEquivalent = "\r" }
            return b
        }
        let spacer = NSView(); spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let row = NSStackView(views: [spacer] + btns.reversed()); row.orientation = .horizontal; row.spacing = 8
        let root = NSStackView(views: [top, row]); root.orientation = .vertical; root.alignment = .leading; root.spacing = 18
        root.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 16, right: 20)
        row.translatesAutoresizingMaskIntoConstraints = false
        row.widthAnchor.constraint(equalTo: top.widthAnchor).isActive = true
        p.contentView = root
        root.layoutSubtreeIfNeeded()
        p.setContentSize(root.fittingSize)
        p.center()
        panel = p
        NSApp.activate(ignoringOtherApps: true)
        p.makeKeyAndOrderFront(nil)
    }

    @objc private func pressed(_ b: NSButton) { finish(b.tag) }
    func windowWillClose(_ notification: Notification) { finish(nil) }

    private func finish(_ i: Int?) {
        guard let k = cont else { return }
        cont = nil
        panel?.delegate = nil
        panel?.close()
        panel = nil
        k.resume(returning: i)
    }
}
