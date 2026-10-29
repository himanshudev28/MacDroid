import SwiftUI

// MARK: - Notifications pane

/// Settings › Notifications: global behaviour, call alerts, and per-app controls (mute toggle + options popover).
struct NotificationsSettingsPane: View {
    @Environment(AppModel.self) private var model
    @AppStorage(NotifPrefs.bannerStyleKey) private var bannerStyle = "default"
    @AppStorage(NotifPrefs.callAlertKey) private var callAlert = "popup"
    @AppStorage(NotifPrefs.callRingKey) private var callRing = false
    @AppStorage(NotifPrefs.openOnClickKey) private var openOnClick = true
    @AppStorage(NotifPrefs.keepInCenterKey) private var keepInCenter = true
    @AppStorage(NotifPrefs.bannerSecondsKey) private var bannerSeconds = 5
    @State private var search = ""

    struct AppRow: Identifiable, Equatable { let pkg: String; let name: String; var id: String { pkg } }

    private var tiers: [String: String] {
        var t: [String: String] = [:]
        for p in model.config?.mutedApps ?? [] { t[p] = "muted" }
        if let o = model.config?.raw["notifTiers"]?.objectValue {
            for (k, v) in o { if let s = v.stringValue { t[k] = s } }
        }
        return t
    }
    private func tier(_ pkg: String) -> NotifTier { NotifTier(rawValue: tiers[pkg] ?? "alert") ?? .alert }

    /// Every app that ever sent a notification: this session's list, the remembered apps, and any app with settings.
    private var allApps: [AppRow] {
        var names = KnownNotifApps.all()
        for n in model.notifications where n.type != "call" { if let p = n.pkg { names[p] = n.app ?? names[p] ?? p } }
        for p in tiers.keys where names[p] == nil { names[p] = p }
        for p in model.config?.raw["notifAppSettings"]?.objectValue?.keys.map({ $0 }) ?? [] where names[p] == nil { names[p] = p }
        return names.map { AppRow(pkg: $0.key, name: $0.value) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    private var shownApps: [AppRow] {
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return allApps }
        return allApps.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.pkg.localizedCaseInsensitiveContains(q) }
    }

    /// Picker value: the core's `nativeNotifs` gate is the "Off" choice, so old configs keep meaning the same thing.
    private var styleBinding: Binding<String> {
        Binding(get: { (model.config?.nativeNotifs ?? true) ? (bannerStyle == "droiddock" ? "droiddock" : "default") : "off" },
                set: { v in
            if v == "off" { model.setSetting("nativeNotifs", false) }
            else { bannerStyle = v; if model.config?.nativeNotifs == false { model.setSetting("nativeNotifs", true) } }
        })
    }

    #if DEBUG
    private var debug = NotifSettingsDebug.shared
    #endif

    var body: some View {
        let c = model.config ?? DroidConfig()
        SettingsForm {
            DDSection(tr("Notifications"), symbol: "bell.badge") {
                DescribedToggle(tr("Phone notifications"), detail: tr("Show your phone's notifications on the Mac."),
                                symbol: "bell.badge.fill", tint: Color(nsColor: .systemRed), isOn: model.cfg(\.notifications, "notifications"))
                LabeledContent {
                    HStack(spacing: DD.Space.sm) {
                        Picker("", selection: styleBinding) {
                            Text(tr("Default")).tag("default")
                            Text(tr("DroidDock banner")).tag("droiddock")
                            Text(tr("Off")).tag("off")
                        }
                        .labelsHidden().fixedSize()
                        Button(tr("Test")) { sendTest() }.disabled(styleBinding.wrappedValue == "off")
                    }
                } label: {
                    RowLabel(title: tr("System notifications"),
                             detail: tr("Default uses macOS banners and falls back to DroidDock's own if they are off."),
                             symbol: "macwindow.badge.plus", tint: Color(nsColor: .systemOrange))
                }
                CommitSlider(title: tr("Banner duration"), range: 2...20, step: 1, value: bannerSeconds,
                             format: { tr("{n} s", ["n": $0]) }) { bannerSeconds = $0 }
                    .disabled(styleBinding.wrappedValue == "off")
                DescribedToggle(tr("Keep in Notification Center"),
                                detail: tr("When DroidDock shows its own banner, also keep the notification in macOS Notification Center."),
                                symbol: "list.bullet.rectangle.portrait", tint: Color(nsColor: .systemIndigo), isOn: $keepInCenter)
                    .disabled(styleBinding.wrappedValue == "off")
                DescribedToggle(tr("Sync notification dismissals"), detail: tr("Dismissing a notification here dismisses it on the phone, and the other way round."),
                                symbol: "arrow.triangle.2.circlepath", tint: Color(nsColor: .systemGreen), isOn: model.cfg(\.syncNotifDismissals, "syncNotifDismissals"))
                DescribedToggle(tr("Open app on notification click"), detail: tr("Clicking a banner opens that conversation on your phone in a pop-up, or the Mac app you chose below."),
                                symbol: "cursorarrow.click", tint: Color(nsColor: .systemBlue), isOn: $openOnClick)
                DescribedToggle(tr("Low battery alerts"), detail: tr("One banner when the phone falls below the threshold while off the charger."),
                                symbol: "battery.25percent", tint: Color(nsColor: .systemYellow), isOn: model.cfg(\.lowBatteryAlert, "lowBatteryAlert"))
                if c.lowBatteryAlert {
                    CommitSlider(title: tr("Alert threshold"), range: 5...50, step: 5, value: c.lowBatteryPct,
                                 format: { "\($0)%" }) { model.setSetting("lowBatteryPct", $0) }
                }
            }

            DDSection(tr("Call alerts"), symbol: "phone.arrow.down.left",
                      footer: tr("Pop-up shows a floating panel with Answer and Decline. Banner uses a notification banner. Once a call is answered, its controls always appear.")) {
                LabeledContent {
                    Picker("", selection: $callAlert) {
                        Text(tr("Pop-up")).tag("popup")
                        Text(tr("Banner")).tag("banner")
                        Text(tr("Off")).tag("off")
                    }
                    .labelsHidden().fixedSize()
                } label: {
                    RowLabel(title: tr("Call alert"), detail: tr("How a ringing call is announced on the Mac."),
                             symbol: "phone.fill", tint: Color(nsColor: .systemGreen))
                }
                DescribedToggle(tr("Ring for calls"), detail: tr("Play a ringtone on the Mac while your phone rings."),
                                symbol: "bell.and.waves.left.and.right.fill", tint: Color(nsColor: .systemPink), isOn: $callRing)
            }

            DDSection(tr("App notifications"), symbol: "square.grid.2x2",
                      footer: tr("Off keeps an app out of banners; it still appears in the Notifications list. The gear opens that app's options.")) {
                if allApps.isEmpty {
                    HStack(spacing: DD.Space.md) {
                        Image(systemName: "bell.slash").foregroundStyle(DD.dim)
                        Text(tr("Phone apps appear here after they send a notification.")).foregroundStyle(DD.dim)
                    }
                } else {
                    HStack(spacing: DD.Space.md) {
                        TextField("", text: $search, prompt: Text(tr("Search apps"))).labelsHidden().textFieldStyle(.roundedBorder)
                        Text(tr("All apps")).foregroundStyle(DD.dim)
                        Toggle("", isOn: allAppsBinding).labelsHidden().toggleStyle(.switch)
                            .help(tr("Turn banners on or off for every app in the list"))
                            .accessibilityLabel(tr("All apps"))
                    }
                    if shownApps.isEmpty {
                        Text(tr("No apps match.")).foregroundStyle(DD.dim)
                    } else {
                        ForEach(shownApps) { a in
                            NotificationAppRow(app: a, tier: tier(a.pkg),
                                               onToggle: { setTier(a.pkg, $0 ? (tier(a.pkg) == .muted ? .alert : tier(a.pkg)) : .muted) },
                                               setTier: { setTier(a.pkg, $0) })
                        }
                    }
                }
            }

            DDSection(tr("Notification click actions"),
                      footer: tr("Choose what happens on your Mac when you click a banner from a phone app. Without a rule, DroidDock opens the notification on your phone. Each app's gear can change that.")) {
                NotificationClickActionsEditor()
                    .listRowInsets(EdgeInsets())
            }
        }
        #if DEBUG
        .onChange(of: debug.search) { _, v in search = v }
        #endif
    }

    private var allAppsBinding: Binding<Bool> {
        Binding(get: { !shownApps.isEmpty && shownApps.allSatisfy { tier($0.pkg) != .muted } },
                set: { on in
            var t = tiers
            for a in shownApps { t[a.pkg] = on ? (tier(a.pkg) == .muted ? "alert" : tier(a.pkg).rawValue) : "muted" }
            model.setSetting("notifTiers", t)
        })
    }

    private func setTier(_ pkg: String, _ tier: NotifTier) {
        var t = tiers; t[pkg] = tier.rawValue
        model.setSetting("notifTiers", t)
    }

    private func sendTest() {
        NotificationService.shared.post(NativeNotification(
            id: "dd-test-\(Int.random(in: 0...99999))", title: "DroidDock",
            body: "Ada\n" + tr("This is a test notification. If you can read this, banners work."), replyKey: nil, sound: false, hidePreview: nil))
    }
}

/// One app: icon, name, gear (options popover) and the on/off switch.
private struct NotificationAppRow: View {
    let app: NotificationsSettingsPane.AppRow
    let tier: NotifTier
    let onToggle: (Bool) -> Void
    let setTier: (NotifTier) -> Void
    @State private var showOptions = false
    #if DEBUG
    private var debug = NotifSettingsDebug.shared
    #endif

    init(app: NotificationsSettingsPane.AppRow, tier: NotifTier, onToggle: @escaping (Bool) -> Void, setTier: @escaping (NotifTier) -> Void) {
        self.app = app; self.tier = tier; self.onToggle = onToggle; self.setTier = setTier
    }

    var body: some View {
        HStack(spacing: DD.Space.md) {
            PhoneAppIcon(pkg: app.pkg, name: app.name, size: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(app.name).lineLimit(1)
                Text(tier == .muted ? tr("Banners off") : (tier == .silent ? tr("Silent") : app.pkg))
                    .font(DD.Font.caption).foregroundStyle(DD.dim).lineLimit(1).truncationMode(.middle)
            }
            Spacer(minLength: DD.Space.sm)
            Button { showOptions = true } label: { Image(systemName: "gearshape") }
                .buttonStyle(.borderless).help(tr("Options for {app}", ["app": app.name]))
                .accessibilityLabel(tr("Options for {app}", ["app": app.name]))
                .popover(isPresented: $showOptions, arrowEdge: .trailing) {
                    AppNotifOptions(app: app, tier: tier, setTier: setTier)
                }
            Toggle("", isOn: Binding(get: { tier != .muted }, set: onToggle))
                .labelsHidden().toggleStyle(.switch)
                .accessibilityLabel(tr("Notifications from {app}", ["app": app.name]))
        }
        #if DEBUG
        .onChange(of: debug.openPkg) { _, p in if p == app.pkg { showOptions = true } }
        #endif
    }
}

/// The per-app options popover.
private struct AppNotifOptions: View {
    @Environment(AppModel.self) private var model
    let app: NotificationsSettingsPane.AppRow
    let tier: NotifTier
    let setTier: (NotifTier) -> Void
    @State private var clickMode: String

    init(app: NotificationsSettingsPane.AppRow, tier: NotifTier, setTier: @escaping (NotifTier) -> Void) {
        self.app = app; self.tier = tier; self.setTier = setTier
        _clickMode = State(initialValue: NotificationClickActions.shared.mode(for: app.pkg))
    }

    private var s: AppNotifSettings { (model.config ?? DroidConfig()).appNotif(app.pkg) }
    private func bind<T>(_ kp: WritableKeyPath<AppNotifSettings, T>) -> Binding<T> {
        Binding(get: { s[keyPath: kp] }, set: { v in var n = s; n[keyPath: kp] = v; model.setAppNotif(app.pkg, n) })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.md) {
            HStack(spacing: DD.Space.md) {
                PhoneAppIcon(pkg: app.pkg, name: app.name, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(app.name).font(DD.Font.headline).lineLimit(1)
                    Text(app.pkg).font(DD.Font.monoSmall).foregroundStyle(DD.dim).lineLimit(1).truncationMode(.middle)
                }
            }
            Divider()
            Toggle(tr("Show on Mac"), isOn: Binding(get: { tier != .muted }, set: { setTier($0 ? .alert : .muted) }))
            LabeledContent(tr("When clicked")) {
                Picker("", selection: $clickMode) {
                    if NotificationClickActions.shared.windowSerial != nil { Text(tr("Open app in its own window")).tag("window") }
                    Text(tr("Open on phone (pop-up)")).tag("phone")
                    Text(tr("Open DroidDock")).tag("droiddock")
                    Text(tr("Do nothing")).tag("none")
                }.labelsHidden().fixedSize()
                .onChange(of: clickMode) { _, v in NotificationClickActions.shared.setMode(v, for: app.pkg) }
            }
            Group {
                LabeledContent(tr("Banner style")) {
                    Picker("", selection: Binding(get: { tier }, set: setTier)) {
                        Text(tr("Banner")).tag(NotifTier.alert)
                        Text(tr("Silent – list only")).tag(NotifTier.silent)
                        Text(tr("Off")).tag(NotifTier.muted)
                    }.labelsHidden().fixedSize()
                }
                Toggle(tr("Play sound"), isOn: bind(\.sound))
                Toggle(tr("Show message preview"), isOn: bind(\.preview))
                LabeledContent(tr("Priority")) {
                    Picker("", selection: bind(\.priority)) {
                        Text(tr("Use phone's")).tag("phone")
                        Text(tr("Always alert")).tag("always")
                        Text(tr("Never alert")).tag("never")
                    }.labelsHidden().fixedSize()
                }
                Toggle(tr("Sync dismissals for this app"), isOn: bind(\.syncDismissals))
            }
            Text(tr("Hiding the preview shows “New notification” in banners instead of the message."))
                .font(DD.Font.caption).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
        }
        .toggleStyle(.switch)
        .padding(DD.Space.lg)
        .frame(width: 320)
    }
}

#if DEBUG
/// QA: `gear:<pkg>` opens that app's options popover.
@MainActor @Observable
final class NotifSettingsDebug {
    static let shared = NotifSettingsDebug()
    var openPkg: String?
    var search = ""
}
#endif
