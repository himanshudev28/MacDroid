import SwiftUI

/// Self-contained settings sections for the WP7 features. Embed with `NativeExtrasSettings()` inside a grouped
/// `Form` (it emits `Section`s) and reads `AppModel` from the environment.
struct NativeExtrasSettings: View {
    @AppStorage(NowPlayingBridge.defaultsKey) private var nowPlaying = true
    @AppStorage(PairingLock.defaultsKey) private var lockQR = false
    @State private var reportCount = CrashReporter.reportFiles().count

    var body: some View {
        Group {
            DDSection(tr("Keyboard shortcuts"),
                            footer: tr("Work from any app. Nothing is set until you record a shortcut. Esc cancels, ⌫ clears.")) {
                ForEach(HotKeyAction.allCases) { a in
                    LabeledContent { HotKeyRecorder(action: a).controlSize(.small) } label: { Label(a.title, systemImage: a.symbol) }
                }
            }

            DDSection(tr("Control Center"),
                            footer: tr("Shows what the phone is playing in Control Center and lets your Mac's media keys control it.")) {
                Toggle(isOn: $nowPlaying) { Label(tr("Phone media in Control Center"), systemImage: "play.rectangle") }
                    .toggleStyle(.switch)
            }

            DDSection(tr("Privacy")) {
                DescribedToggle(tr("Lock the pairing code"),
                                detail: tr("Ask for Touch ID or your password before the pairing QR is shown."), isOn: $lockQR)
            }

            DDSection(tr("Notification click actions"),
                            footer: tr("Choose what happens on your Mac when you click a banner from a phone app. Without a rule, DroidDock opens its Notifications page.")) {
                NotificationClickActionsEditor()
            }

            DDSection(tr("Crash reports"),
                            footer: tr("Saved on this Mac only, in ~/Library/Logs/DroidDock. Nothing is ever sent anywhere.")) {
                LabeledContent {
                    HStack(spacing: DD.Space.sm) {
                        Button(tr("Show in Finder")) { CrashReporter.revealInFinder() }
                        Button(tr("Clear"), role: .destructive) { CrashReporter.clear(); reportCount = 0 }.disabled(reportCount == 0)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Label(tr("Reports"), systemImage: "ladybug")
                        Text(reportCount == 0 ? tr("No crashes recorded.") : tn("{n} report", "{n} reports", reportCount))
                            .font(DD.Font.caption).foregroundStyle(DD.dim)
                    }
                }
            }
        }
        .onChange(of: nowPlaying) { _, _ in NowPlayingBridge.shared.refresh() }
        .onAppear { reportCount = CrashReporter.reportFiles().count }
    }
}
