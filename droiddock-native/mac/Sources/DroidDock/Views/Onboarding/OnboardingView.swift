import AppKit
import Observation
import SwiftUI
import UserNotifications

/// First-run flow: welcome, pair, permissions, done. Shown once (`droiddock.onboarded`), re-openable from Help.
@Observable @MainActor
final class OnboardingState {
    static let shared = OnboardingState()
    static let doneKey = "droiddock.onboarded"
    var isShowing = false

    func showIfFirstRun() {
        guard !UserDefaults.standard.bool(forKey: Self.doneKey), !isShowing else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            if !UserDefaults.standard.bool(forKey: Self.doneKey) { isShowing = true }
        }
    }
    func reopen() { WindowControl.showMain(); isShowing = true }
    func finish() {
        UserDefaults.standard.set(true, forKey: Self.doneKey)
        isShowing = false
    }
}

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    private let state = OnboardingState.shared

    enum Step: Int, CaseIterable { case welcome, pair, permissions, done }
    #if DEBUG
    @State private var step: Step = Step(rawValue: Int(ProcessInfo.processInfo.environment["DD_ONBOARD_STEP"] ?? "") ?? 0) ?? .welcome
    #else
    @State private var step: Step = .welcome
    #endif

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ZStack {
                switch step {
                case .welcome: WelcomeStep(next: { go(.pair) })
                case .pair: PairStep(next: { go(.permissions) })
                case .permissions: PermissionsStep(next: { go(.done) })
                case .done: DoneStep(finish: { state.finish() })
                }
            }
            .id(step)
            .ddTransition(Self.stepTransition)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(width: 560, height: 560)
        .clipped()
        .onAppear { if model.isConnected { step = .permissions } }
        .task(id: model.isConnected) {
            // Let the "connected" check land and read before moving on. Keyed on the connection, so a drop during the
            // wait (or the sheet closing) cancels the advance.
            guard model.isConnected, step == .pair else { return }
            try? await Task.sleep(for: .milliseconds(900))
            if !Task.isCancelled, model.isConnected, step == .pair { go(.permissions) }
        }
        .interactiveDismissDisabled(false)
        .onExitCommand { state.finish() }
    }

    private func go(_ s: Step) { withDDAnimation(Motion.state) { step = s } }

    /// Forward-only flow: the next step slides in from the trailing side while the old one leaves toward the leading side
    /// (faster, so it clears first). `.ddTransition` turns this into a plain fade under Reduce Motion.
    private static let stepTransition = AnyTransition.asymmetric(
        insertion: .offset(x: 28).combined(with: .opacity),
        removal: .offset(x: -28).combined(with: .opacity).animation(Motion.exit))

    private var topBar: some View {
        HStack {
            HStack(spacing: 6) {
                ForEach(Step.allCases, id: \.rawValue) { s in
                    Capsule().fill(s.rawValue <= step.rawValue ? Color.accentColor : DD.panel3)
                        .frame(width: s == step ? 18 : 6, height: 6)
                }
            }
            .ddAnimation(Motion.select, value: step)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(tr("Step {n} of {m}", ["n": step.rawValue + 1, "m": Step.allCases.count]))
            Spacer()
            if step != .done {
                Button(tr("Skip")) { state.finish() }
                    .buttonStyle(.borderless).controlSize(.small).foregroundStyle(DD.dim)
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(.horizontal, DD.Space.xl).padding(.top, DD.Space.lg).frame(height: 44)
    }
}

// MARK: - Shared pieces

/// One-shot entrance for a step's parts: rise 6 pt + fade, `Motion.stagger(index)` apart. Fade only under Reduce Motion.
private struct StepRise: ViewModifier {
    var index: Int
    @Environment(\.accessibilityReduceMotion) private var envReduceMotion
    @State private var shown = false
    func body(content: Content) -> some View {
        let reduced = OnboardingMotion.reduced(envReduceMotion)
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduced ? 0 : 6)
            .onAppear {
                guard !shown else { return }
                withAnimation((reduced ? Motion.reducedFade : Motion.content).delay(0.06 + Motion.stagger(index))) { shown = true }
            }
    }
}

private extension View {
    func stepRise(_ index: Int) -> some View { modifier(StepRise(index: index)) }
}

private struct StepScaffold<Content: View>: View {
    var title: String
    var message: String
    var actionTitle: String
    var action: () -> Void
    @ViewBuilder var content: Content
    var body: some View {
        VStack(spacing: DD.Space.md) {
            Text(title).font(DD.Font.largeTitle).foregroundStyle(DD.fg).multilineTextAlignment(.center)
                .stepRise(0)
            Text(message).font(DD.Font.body).foregroundStyle(DD.dim).multilineTextAlignment(.center)
                .frame(maxWidth: 400).fixedSize(horizontal: false, vertical: true)
                .stepRise(1)
            content.padding(.top, DD.Space.sm)
                .stepRise(2)
            Spacer(minLength: DD.Space.md)
            Button(actionTitle, action: action)
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .padding(.bottom, DD.Space.xl)
                .stepRise(3)
        }
        .padding(.horizontal, DD.Space.xl)
    }
}

// MARK: - Steps

private struct WelcomeStep: View {
    var next: () -> Void
    var body: some View {
        VStack(spacing: DD.Space.lg) {
            Spacer(minLength: 0)
            OnboardingHero()
            StepScaffold(title: tr("Welcome to DroidDock"),
                         message: tr("Your Android phone, right on your Mac. Notifications, messages, files, and your screen, over your own Wi-Fi."),
                         actionTitle: tr("Get Started"), action: next) {
                HStack(spacing: DD.Space.xl) {
                    feature("bell.badge", tr("Notifications"), Color(nsColor: .systemRed))
                    feature("folder", tr("Files"), Color(nsColor: .systemBlue))
                    feature("rectangle.on.rectangle", tr("Mirror"), Color(nsColor: .systemPurple))
                    feature("doc.on.clipboard", tr("Clipboard"), Color(nsColor: .systemOrange))
                }
            }
            .frame(maxHeight: .infinity)
        }
    }
    private func feature(_ symbol: String, _ title: String, _ tint: Color) -> some View {
        VStack(spacing: 6) {
            SymbolTile(systemImage: symbol, tint: tint, size: 40)
            Text(title).font(DD.Font.caption).foregroundStyle(DD.dim)
        }
    }
}

private struct PairStep: View {
    @Environment(AppModel.self) private var model
    var next: () -> Void
    @State private var shell = ShellState.shared

    var body: some View {
        StepScaffold(title: tr("Pair your phone"),
                     message: tr("Open DroidDock on your Android, tap Pair with Mac, and scan this code. Both devices need the same Wi-Fi."),
                     actionTitle: model.isConnected ? tr("Continue") : tr("I'll do this later"), action: next) {
            VStack(spacing: DD.Space.md) {
                ZStack {
                    if model.isConnected {
                        connectedTile
                            .ddTransition(.asymmetric(insertion: .scale(scale: 0.94).combined(with: .opacity), removal: .opacity))
                    } else {
                        LockedQRView {
                            ZStack {
                                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white)
                                if let qr = shell.qr {
                                    Image(nsImage: qr).interpolation(.none).resizable().scaledToFit().padding(14)
                                        .accessibilityLabel(tr("Pairing QR code"))
                                        .transition(.opacity)
                                } else { ProgressView().controlSize(.small) }
                            }
                            .frame(width: 188, height: 188)
                            .ddHairline(RoundedRectangle(cornerRadius: 18, style: .continuous), DD.line)
                        }
                        .transition(.opacity.animation(Motion.exit))
                    }
                }
                .frame(width: 188, height: 188)
                .ddAnimation(Motion.pop, value: model.isConnected)
                if model.isConnected {
                    StatusPill(text: tr("Connected to {name}", ["name": model.phoneName ?? tr("your phone")]), kind: .link)
                        .transition(.opacity)
                } else {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.mini)
                        Text(tr("Waiting for your phone…")).font(DD.Font.callout).foregroundStyle(DD.dim)
                    }
                }
            }
            .ddAnimation(Motion.state, value: model.isConnected)
        }
    }

    private var connectedTile: some View { ConnectedTile() }
}

/// Replaces the QR the moment the phone connects: a green check that pops in, then bounces once as it settles.
private struct ConnectedTile: View {
    @Environment(\.accessibilityReduceMotion) private var envReduceMotion
    @State private var bounce = false
    var body: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(DD.ok.opacity(0.14))
            .overlay(
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 64, weight: .regular))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(DD.ok)
                    .symbolEffect(.bounce, value: bounce)
            )
            .ddHairline(RoundedRectangle(cornerRadius: 18, style: .continuous), DD.ok.opacity(0.35))
            .frame(width: 188, height: 188)
            .accessibilityHidden(true)   // the "Connected to …" pill below says it
            .task {
                // A symbol effect fires on a value *change*, so flip it once the pop-in has mostly landed.
                // Under Reduce Motion the check just fades in; no bounce.
                guard !OnboardingMotion.reduced(envReduceMotion) else { return }
                try? await Task.sleep(for: .milliseconds(160))
                bounce = true
            }
    }
}

private struct PermissionsStep: View {
    @Environment(AppModel.self) private var model
    var next: () -> Void
    @AppStorage("droiddock.onboard.files") private var filesOK = false
    @State private var notifs = false
    @State private var ax = false

    var body: some View {
        StepScaffold(title: tr("Allow a few things"),
                     message: tr("These switch on live as you grant them. You can change any of them later in System Settings."),
                     actionTitle: tr("Continue"), action: next) {
            VStack(spacing: 0) {
                row("bell.badge", tr("Notifications"), tr("Show your phone's notifications as Mac banners."),
                    done: notifs, action: tr("Allow")) { requestNotifications() }
                Divider().padding(.leading, 56)
                row("network", tr("Local Network"), tr("Lets your Mac and phone find each other."),
                    done: model.isConnected, action: tr("Open Settings")) {
                    open("x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork")
                }
                Divider().padding(.leading, 56)
                row("folder", tr("Files"), tr("Save received files to your Downloads folder."),
                    done: filesOK, action: tr("Allow")) { probeDownloads() }
                Divider().padding(.leading, 56)
                row("cursorarrow.click.2", tr("Accessibility"), tr("Optional. Needed to control your Mac from your phone."),
                    done: ax, action: tr("Open Settings")) { Task { try? await Core.shared.openAccessibilitySettings() } }
            }
            .padding(.vertical, DD.Space.xs)
            .frame(maxWidth: 440)
            .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(DD.panel))
            .overlay(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).strokeBorder(DD.containerEdge, lineWidth: 1))
        }
        .task {
            await UIActivity.shared.pollWhileWindowVisible(every: .milliseconds(1200), tolerance: .milliseconds(300)) { await refresh() }
        }
    }

    private func row(_ symbol: String, _ title: String, _ detail: String, done: Bool, action: String,
                     perform: @escaping () -> Void) -> some View {
        HStack(spacing: DD.Space.md) {
            SymbolTile(systemImage: symbol, tint: done ? DD.ok : DD.dim, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(DD.Font.headline).foregroundStyle(DD.fg)
                Text(detail).font(DD.Font.callout).foregroundStyle(DD.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: DD.Space.sm)
            if done {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 18))
                    .symbolRenderingMode(.hierarchical).foregroundStyle(DD.ok)
                    .accessibilityLabel(tr("Allowed"))
                    // Granting is the payoff of this step: the check pops in (spring, a hint of bounce) as the button fades.
                    .ddTransition(.asymmetric(insertion: .scale(scale: 0.6).combined(with: .opacity).animation(Motion.pop),
                                              removal: .opacity))
            } else {
                Button(action, action: perform).buttonStyle(SecondaryButtonStyle(compact: true))
            }
        }
        .padding(.horizontal, DD.Space.md).padding(.vertical, DD.Space.sm)
        .ddAnimation(Motion.content, value: done)
        .accessibilityElement(children: .combine)
    }

    private func refresh() async {
        if Self.notificationsAvailable {
            let s = await UNUserNotificationCenter.current().notificationSettings()
            notifs = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
        }
        ax = (try? await Core.shared.accessibilityTrusted()) ?? false
    }

    /// `UNUserNotificationCenter.current()` asserts (SIGABRT) outside a .app bundle, e.g. the bare debug binary.
    /// Same check as `NotificationService.available`.
    private static var notificationsAvailable: Bool {
        Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app"
    }

    private func requestNotifications() {
        guard Self.notificationsAvailable else { return }
        Task {
            let center = UNUserNotificationCenter.current()
            let s = await center.notificationSettings()
            if s.authorizationStatus == .denied {
                open("x-apple.systempreferences:com.apple.preference.notifications")
            } else {
                _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            }
            await refresh()
        }
    }

    private func probeDownloads() {
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        if let dir, (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) != nil { filesOK = true }
    }

    private func open(_ s: String) { if let u = URL(string: s) { NSWorkspace.shared.open(u) } }
}

private struct DoneStep: View {
    var finish: () -> Void
    var body: some View {
        VStack(spacing: DD.Space.lg) {
            Spacer(minLength: 0)
            OnboardingDoneMark()
            StepScaffold(title: tr("You're all set"),
                         message: tr("DroidDock lives in your menu bar too. Close the window any time; it keeps running."),
                         actionTitle: tr("Start Using DroidDock"), action: finish) {
                VStack(alignment: .leading, spacing: DD.Space.sm) {
                    tip(tr("Mirror your phone"), ["⌘", "⇧", "M"])
                    tip(tr("Send a file"), ["⌘", "U"])
                    tip(tr("Settings"), ["⌘", ","])
                }
            }
            .frame(maxHeight: .infinity)
        }
    }
    private func tip(_ title: String, _ keys: [String]) -> some View {
        HStack {
            Text(title).font(DD.Font.body).foregroundStyle(DD.dim)
            Spacer(minLength: DD.Space.xl)
            KeyboardShortcutHint(keys: keys)
        }
        .frame(width: 240)
    }
}
