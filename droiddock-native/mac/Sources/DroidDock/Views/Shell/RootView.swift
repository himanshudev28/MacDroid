import SwiftUI

/// Window root: picks the shell from `droiddock.layout` (live), applies appearance + RTL, hosts toasts and onboarding.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @AppStorage(AppLayout.defaultsKey) private var layoutRaw = AppLayout.phoneTabs.rawValue
    @State private var chrome = ShellChrome.shared
    @State private var onboarding = OnboardingState.shared
    #if DEBUG
    /// QA: `DD_SHOW_SHEET=health|pair|quickshare` opens that sheet at launch (screenshots).
    @State private var debugSheet = ProcessInfo.processInfo.environment["DD_SHOW_SHEET"] ?? ""
    #endif

    var body: some View {
        let layout = AppLayout(rawValue: layoutRaw) ?? .phoneTabs
        let required = chrome.requiredWidth(layout)
        Group {
            switch layout {
            case .phoneTabs: PhoneTabsShell()
            case .classic: ClassicShell()
            case .grouped: GroupedShell()
            case .minimal: MinimalShell()
            }
        }
        // Layout switch (Settings › Appearance): an instant swap. Animating two NavigationSplitViews (the old one fading
        // out while the new one measured its columns) left uneven columns and a stalled main thread; each shell has a
        // stable identity so its split view is built fresh with its own column widths.
        .id(layout)
        .transaction(value: layoutRaw) { $0.animation = nil; $0.disablesAnimations = true }
        .liquidWindowBackground()
        .frame(minWidth: required, minHeight: 600)
        // Layout switch or panel/sidebar change: grow the window to the new layout's real minimum (animated unless
        // Reduce Motion is on, clamped to the screen).
        // A second pass after the swapped shell has measured: a grow issued while the old split view was still tearing down can be eaten.
        .onChange(of: required) { _, w in
            WindowFit.grow(to: w)
            Task { @MainActor in try? await Task.sleep(for: .milliseconds(500)); WindowFit.grow(to: chrome.requiredWidth(layout)) }
        }
        .onChange(of: layoutRaw) { _, _ in
            Task { @MainActor in try? await Task.sleep(for: .milliseconds(300)); WindowFit.grow(to: chrome.requiredWidth(AppLayout(rawValue: layoutRaw) ?? .phoneTabs)) }
        }
        .task { try? await Task.sleep(for: .milliseconds(150)); WindowFit.grow(to: required) }
        .overlay(alignment: .bottom) { ToastStack() }
        .sheet(isPresented: $onboarding.isShowing) {
            OnboardingView().environment(model).ddAppearance(AppearanceSettings.shared)
        }
        #if DEBUG
        .sheet(isPresented: Binding(get: { debugSheet == "health" || debugSheet == "pair" }, set: { if !$0 { debugSheet = "" } })) {
            Group { if debugSheet == "pair" { WirelessPairSheet() } else { HealthSheet() } }
                .environment(model).ddAppearance(AppearanceSettings.shared)
        }
        .task {
            if debugSheet == "toast" {
                try? await Task.sleep(for: .seconds(4))
                model.toast(.ok, "Screenshot saved to Downloads")
                model.toast(.bad, "Couldn't reach the phone. Check that it's on the same Wi-Fi.")
            }
            if debugSheet == "quickshare" {
                try? await Task.sleep(for: .seconds(1))
                model.quickShare = QuickShareRequest(id: 1, peer: "Pixel 8 (Ada)", pin: "4821",
                    files: [QuickShareFile(name: "IMG_2041.jpg", size: 3_400_000, mime: "image/jpeg"),
                            QuickShareFile(name: "notes.pdf", size: 180_000, mime: "application/pdf")])
            }
        }
        #endif
        .ddAppearance(AppearanceSettings.shared)
        .environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)
        #if DEBUG
        .task {
            // QA: DD_CYCLE_LAYOUTS=<seconds> cycles the layout every N seconds (stress test for the shell swap).
            guard let secs = Double(ProcessInfo.processInfo.environment["DD_CYCLE_LAYOUTS"] ?? ""), secs > 0 else { return }
            let all = AppLayout.allCases
            for i in 0..<10 {
                try? await Task.sleep(for: .seconds(secs))
                UserDefaults.standard.set(all[(i + 1) % all.count].rawValue, forKey: AppLayout.defaultsKey)
            }
            dlog("layout cycle done")
        }
        #endif
        .onAppear {
            WindowControl.openMain = { openWindow(id: "main") }
            ShellState.shared.start()
            onboarding.showIfFirstRun()
        }
    }
}

/// Bottom-centre toasts from `AppModel.toasts`.
struct ToastStack: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        DDToastHost(toasts: model.toasts.map { DDToastItem(id: $0.id, kind: kind($0.kind), text: $0.text) },
                    onDismiss: { model.dismissToast($0.id) })
    }

    private func kind(_ k: Toast.Kind) -> DDToastKind {
        switch k { case .ok: .ok; case .bad: .bad; case .info: .info }
    }
}
