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

/// Go menu: one list and one set of ⌘ keys in every layout (⌘0 Dashboard, ⌘1…⌘7 the daily screens), so the menu, the tab
/// tooltips and muscle memory agree whichever layout is active.
struct NavigationCommands: Commands {
    let model: AppModel
    @AppStorage(AppLayout.defaultsKey) private var layoutRaw = AppLayout.phoneTabs.rawValue

    private var layout: AppLayout { AppLayout(rawValue: layoutRaw) ?? .phoneTabs }

    private func item(_ d: Destination) -> some View {
        Group {
            if let key = d.shortcutKey {
                Button(d.title) { model.selection = d }.keyboardShortcut(KeyEquivalent(key), modifiers: .command)
            } else {
                Button(d.title) { model.selection = d }
            }
        }
    }

    var body: some Commands {
        CommandMenu(tr("Go")) {
            ForEach(Destination.goShortcuts) { item($0) }
            Divider()
            ForEach(Destination.goOthers) { item($0) }
            Divider()
            // ⌘, belongs to the Settings scene; this item opens the in-window Settings page.
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
            Button(tr("Open Command Bar")) { CommandBarController.shared.toggle() }
            Divider()
            Button(tr("Take Screenshot")) { ShellActions.screenshot() }
            Button(tr("Ring Phone")) { ShellActions.toggleRing() }
            Divider()
            Button(model.isPaused ? tr("Resume Notifications") : tr("Pause Notifications for 1 Hour")) { ShellActions.togglePause() }
        }
    }
}
