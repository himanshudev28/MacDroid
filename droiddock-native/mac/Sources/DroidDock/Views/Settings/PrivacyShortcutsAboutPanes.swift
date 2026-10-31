import SwiftUI

// MARK: - Remote control & Privacy

struct PrivacySettingsPane: View {
    @Environment(AppModel.self) private var model
    @AppStorage(PairingLock.defaultsKey) private var lockQR = false
    @State private var axTrusted = true
    @State private var spacesBound = false
    @State private var spacesFixFailed = false
    @State private var reportCount = CrashReporter.reportFiles().count

    var body: some View {
        let remote = model.config?.remoteControl ?? false
        SettingsForm {
            DDSection(tr("Remote control"),
                      footer: tr("Adds a trackpad, keyboard, media, lock, brightness and volume to the phone app. Anyone holding your paired phone gets this, so leave it off unless you want it.")) {
                DescribedToggle(tr("Let the phone control this Mac"), isOn: model.cfg(\.remoteControl, "remoteControl"))
                if remote {
                    LabeledContent {
                        StatusPill(text: axTrusted ? tr("Granted") : tr("Not granted"), kind: axTrusted ? .ok : .bad,
                                   systemImage: axTrusted ? "checkmark" : "exclamationmark")
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tr("Accessibility permission"))
                            Text(axTrusted ? tr("macOS lets DroidDock click and type for the phone.")
                                           : tr("Until granted, macOS silently discards every synthesised click and key."))
                                .font(DD.Font.caption).foregroundStyle(DD.dim)
                        }
                    }
                    if !axTrusted {
                        VStack(alignment: .leading, spacing: DD.Space.sm) {
                            Text(tr("If DroidDock already looks ticked in Privacy & Security › Accessibility, the tick is stale: macOS ties the grant to the exact app binary, and every update replaces it. Reset permission removes the stale row and asks again."))
                                .font(DD.Font.caption).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
                            HStack {
                                Button(tr("Reset permission")) { Task { _ = try? await Core.shared.accessibilityReset() } }
                                Button(tr("Open System Settings")) { Task { try? await Core.shared.openAccessibilitySettings() } }
                            }
                        }
                    }
                }
            }

            if spacesBound {
                DDSection(tr("Desktops"), footer: spacesFixFailed ? tr("That didn't take. Right-click DroidDock in the Dock › Options › Assign To › None.") : nil) {
                    LabeledContent {
                        Button(tr("Keep on one desktop")) {
                            Task {
                                let ok = (try? await Core.shared.spacesBindingClear()) ?? false
                                spacesBound = !ok; spacesFixFailed = !ok
                            }
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tr("DroidDock is assigned to every desktop"))
                            Text(tr("The Dock's Assign To setting puts its windows on whichever desktop you switch to."))
                                .font(DD.Font.caption).foregroundStyle(DD.dim)
                        }
                    }
                }
            }

            DDSection(tr("Privacy")) {
                DescribedToggle(tr("Lock the pairing code"), detail: tr("Ask for Touch ID or your password before the pairing QR is shown."), isOn: $lockQR)
            }

            DDSection(tr("Crash reports"), footer: tr("Saved on this Mac only, in ~/Library/Logs/DroidDock. Nothing is ever sent anywhere.")) {
                LabeledContent {
                    HStack(spacing: DD.Space.sm) {
                        Button(tr("Show in Finder")) { CrashReporter.revealInFinder() }
                        Button(tr("Clear"), role: .destructive) { CrashReporter.clear(); reportCount = 0 }.disabled(reportCount == 0)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("Reports"))
                        Text(reportCount == 0 ? tr("No crashes recorded.") : tn("{n} report", "{n} reports", reportCount))
                            .font(DD.Font.caption).foregroundStyle(DD.dim)
                    }
                }
            }
        }
        .task(id: remote) {
            spacesBound = (try? await Core.shared.spacesBindingActive()) ?? false
            guard remote else { return }
            await UIActivity.shared.pollWhileWindowVisible(every: .seconds(2), tolerance: .milliseconds(500)) {
                axTrusted = (try? await Core.shared.accessibilityTrusted()) ?? true
            }
        }
        .onAppear { reportCount = CrashReporter.reportFiles().count }
    }
}

// MARK: - Shortcuts

struct ShortcutsSettingsPane: View {
    @AppStorage(NowPlayingBridge.defaultsKey) private var nowPlaying = true

    var body: some View {
        SettingsForm {
            DDSection(tr("Global shortcuts"), footer: tr("Work from any app. Nothing is set until you record a shortcut. Esc cancels, ⌫ clears.")) {
                ForEach(HotKeyAction.allCases) { a in
                    LabeledContent {
                        HotKeyRecorder(action: a).controlSize(.small)
                    } label: {
                        HStack(spacing: DD.Space.md) {
                            Image(systemName: a.symbol).foregroundStyle(DD.dim).frame(width: 20)
                            Text(a.title)
                        }
                    }
                }
            }
            DDSection(tr("Control Center"), footer: tr("Shows what the phone is playing in Control Center and lets your Mac's media keys control it.")) {
                DescribedToggle(tr("Phone media in Control Center"), isOn: $nowPlaying)
            }
        }
        .onChange(of: nowPlaying) { _, _ in NowPlayingBridge.shared.refresh() }
    }
}

// MARK: - Health

struct HealthSettingsPane: View {
    @State private var showSheet = false
    var body: some View {
        SettingsForm {
            DDSection(footer: tr("Almost everything that goes wrong with DroidDock goes wrong quietly. The setup check lists each permission both devices need, what breaks without it, and a button that opens the screen to grant it.")) {
                LabeledContent {
                    Button(tr("Run Setup Check")) { showSheet = true }.buttonStyle(PrimaryButtonStyle(compact: true))
                } label: {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tr("Setup check"))
                            Text(tr("Checks the Mac and the linked phone.")).font(DD.Font.caption).foregroundStyle(DD.dim)
                        }
                    } icon: { Image(systemName: "stethoscope").foregroundStyle(DD.dim) }
                }
            }
        }
        .sheet(isPresented: $showSheet) { HealthSheet() }
    }
}

// MARK: - About

struct AboutSettingsPane: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        SettingsForm {
            DDSection {
                VStack(spacing: DD.Space.sm) {
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable().frame(width: 72, height: 72)
                        .accessibilityHidden(true)
                    Text("DroidDock").font(DD.Font.title)
                    Text(tr("Version {v}", ["v": model.appVersion])).font(DD.Font.callout).foregroundStyle(DD.dim)
                    Text(tr("Your Android phone, on your Mac.")).font(DD.Font.callout).foregroundStyle(DD.dim)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, DD.Space.md)
            }
            DDSection(tr("Technical")) {
                LabeledContent(tr("Link port")) { Text(verbatim: String(model.config?.port ?? 0)).font(DD.Font.mono).foregroundStyle(DD.dim) }
                LabeledContent(tr("Discovery port")) { Text(verbatim: "\((model.config?.port ?? 0) + 1) UDP").font(DD.Font.mono).foregroundStyle(DD.dim) }
            }
            DDSection(tr("Links"), footer: tr("DroidDock uses scrcpy (Apache 2.0) and Android platform tools for ADB features. Quick Share is an independent implementation of the open protocol. Licenses are in the app bundle.")) {
                Link(destination: URL(string: "https://github.com/himanshudev28/MacDroid")!) {
                    Label(tr("Project on GitHub"), systemImage: "link")
                }
                Link(destination: URL(string: "https://github.com/himanshudev28/MacDroid/releases")!) {
                    Label(tr("Release notes"), systemImage: "doc.text")
                }
            }
        }
    }
}
