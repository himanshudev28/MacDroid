import SwiftUI

@main
struct DroidDockApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel.shared

    var body: some Scene {
        Window("DroidDock", id: "main") {
            RootView()
                .environment(model)
                .sheet(item: $model.quickShare) { req in
                    QuickShareReceivePrompt(request: req).environment(model)
                }
        }
        .defaultSize(width: 1200, height: 760)
        .windowResizability(.contentMinSize)
        .commands {
            NavigationCommands(model: model)
            PhoneCommands(model: model)
        }

        // The menu bar item and its panel are `MenuBarController` (MenuBar/), installed by the app delegate: a plain
        // MenuBarExtra(.window) can not morph out of the status item.

        // ⌘, (the app menu's "Settings…" comes with this scene).
        Settings {
            SettingsRoot()
                .environment(model)
                .ddAppearance(AppearanceSettings.shared)
                .environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)
        }
    }
}

/// Go menu. ⌘1…⌘5 follow the active layout, so the menu and the toolbar tabs always agree.
struct NavigationCommands: Commands {
    let model: AppModel
    @AppStorage(AppLayout.defaultsKey) private var layoutRaw = AppLayout.phoneTabs.rawValue

    private var layout: AppLayout { AppLayout(rawValue: layoutRaw) ?? .phoneTabs }

    private func item(_ title: String, _ key: Character?, _ go: @escaping () -> Void) -> some View {
        Group {
            if let key { Button(title, action: go).keyboardShortcut(KeyEquivalent(key), modifiers: .command) }
            else { Button(title, action: go) }
        }
    }

    var body: some Commands {
        CommandMenu(tr("Go")) {
            switch layout {
            case .phoneTabs:
                ForEach(Array(PhoneTabsShell.Tab.allCases.enumerated()), id: \.element) { i, t in
                    item(t.title, Character("\(i + 1)")) { model.selection = t.items[0] }
                }
                Divider()
                ForEach(PhoneTabsShell.extras.filter { $0 != .settings }) { d in item(d.title, nil) { model.selection = d } }
            case .minimal:
                ForEach(Array(MinimalShell.tabs.enumerated()), id: \.element) { i, d in
                    item(d.title, Character("\(i + 1)")) { model.selection = d }
                }
                Divider()
                ForEach(MinimalShell.overflow) { d in item(d.title, nil) { model.selection = d } }
            case .classic, .grouped:
                ForEach(Destination.allCases.filter { $0.shortcutKey != nil }) { d in
                    item(d.title, d.shortcutKey) { model.selection = d }
                }
            }
            Divider()
            // ⌘, belongs to the Settings scene; this item opens the in-window (Classic/Minimal) page.
            Button(Destination.settings.title) { model.selection = .settings }
        }
        CommandGroup(replacing: .sidebar) {
            let c = ShellChrome.shared
            if !ShellChrome.phoneIsSidebar(layout) {
                Button(c.isCollapsed(layout) ? tr("Expand sidebar") : tr("Collapse sidebar")) { c.toggleSidebar(layout) }
                    .keyboardShortcut("s", modifiers: [.command, .control])
            }
            Button(c.isPhoneHidden(layout) ? tr("Show phone") : tr("Hide phone")) { c.togglePhone(layout) }
                .keyboardShortcut("s", modifiers: [.command, .option])
        }
        CommandGroup(after: .help) {
            Button(tr("Welcome to DroidDock…")) { OnboardingState.shared.reopen() }
        }
    }
}

/// Phone menu: every card action, with its shortcut (HIG: toolbar/card actions are also menu commands).
struct PhoneCommands: Commands {
    let model: AppModel
    var body: some Commands {
        CommandMenu(tr("Phone")) {
            Button(tr("Send File…")) { ShellActions.pickAndSend() }.keyboardShortcut("u", modifiers: .command)
            Button(tr("Send Mac Clipboard")) { ShellActions.pushClipboard() }.keyboardShortcut("v", modifiers: [.command, .shift])
            Divider()
            Button(model.mirror != nil ? tr("Stop Mirror") : tr("Mirror Screen")) { ShellActions.toggleMirror() }
                .keyboardShortcut("m", modifiers: [.command, .shift])
            Button(tr("Take Screenshot")) { ShellActions.screenshot() }
            Button(tr("Ring Phone")) { ShellActions.toggleRing() }
            Divider()
            Button(model.isPaused ? tr("Resume Notifications") : tr("Pause Notifications for 1 Hour")) { ShellActions.togglePause() }
        }
    }
}
