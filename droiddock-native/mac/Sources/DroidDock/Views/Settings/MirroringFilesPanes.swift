import SwiftUI

// MARK: - Mirroring & Media

struct MirroringSettingsPane: View {
    @Environment(AppModel.self) private var model
    @AppStorage("droiddock.mirrorInWindow") private var mirrorInWindow = false
    @AppStorage(MirrorSession.pauseHiddenAdbKey) private var pauseHiddenAdb = false
    @AppStorage(MirrorSession.preferAdbKey) private var preferAdb = true

    var body: some View {
        let c = model.config ?? DroidConfig()
        SettingsForm {
            DDSection(footer: tr("Wi-Fi needs no ADB. ADB and Desktop need scrcpy.")) {
                Picker(tr("Start with"), selection: model.cfg(\.defaultMirrorMode, "defaultMirrorMode")) {
                    Text("Wi-Fi").tag("wifi"); Text("ADB").tag("adb"); Text(tr("Desktop")).tag("desktop")
                }
                DescribedToggle(tr("Mirror inside the main window"), detail: tr("Off pops the phone out into its own window."), isOn: $mirrorInWindow)
                DescribedToggle(tr("Phone audio"), detail: tr("Play the phone's audio on this Mac while mirroring. Wi-Fi needs Android 10+; some apps block capture."),
                                isOn: model.cfg(\.mirrorAudio, "mirrorAudio"))
            }

            DDSection(tr("Screen-share prompt"), footer: tr("ADB mirroring never shows Android's “Start recording or casting?” prompt. Allowing screen capture can also silence it for Wi-Fi mirroring, but Android may still ask on some versions.")) {
                DescribedToggle(tr("Prefer ADB mirroring when available"),
                                detail: tr("With an ADB device connected, Start mirroring uses ADB instead of Wi-Fi, so the phone isn't asked to approve capture."),
                                isOn: $preferAdb)
                ProjectionGrantRow()
            }

            DDSection(footer: tr("Saves about 1-3% CPU while the window is hidden. When you come back the picture can freeze for a few seconds until the phone sends its next keyframe. Wi-Fi mirroring always pauses, without that freeze.")) {
                DescribedToggle(tr("Pause USB mirroring while its window is hidden"), isOn: $pauseHiddenAdb)
            }

            DDSection(tr("Quality"), footer: tr("Applies to Wi-Fi and ADB. Lower the bit rate if the mirror stutters on a busy network.")) {
                CommitSlider(title: tr("Bit rate"), range: 2...30, value: c.mirrorBitrateMbps, format: { "\($0) Mb/s" }) { model.setSetting("mirrorBitrateMbps", $0) }
                Picker(tr("Frame rate"), selection: model.cfg(\.mirrorFps, "mirrorFps")) {
                    ForEach([30, 45, 60, 90], id: \.self) { Text("\($0) fps").tag($0) }
                }
                Picker(tr("Resolution cap"), selection: model.cfg(\.mirrorMaxSize, "mirrorMaxSize")) {
                    Text(tr("Phone's own")).tag(0)
                    ForEach([1920, 1280, 720], id: \.self) { Text("\($0) px").tag($0) }
                }
                Picker(tr("Video codec"), selection: model.cfg(\.mirrorCodec, "mirrorCodec")) {
                    Text("H.264").tag("h264"); Text("H.265").tag("h265")
                }
                LabeledContent(tr("Defaults")) {
                    Button(tr("Reset")) {
                        model.setSetting("mirrorBitrateMbps", 12); model.setSetting("mirrorFps", 60); model.setSetting("mirrorMaxSize", 0)
                        model.toast(.ok, tr("Mirror quality reset to defaults"))
                    }
                }
            }

            DDSection(tr("Desktop mode & app windows"), footer: tr("A virtual display on the phone. “Window layout” decides whether Android serves desktop, tablet or phone layouts.")) {
                Picker(tr("Display size"), selection: model.cfg(\.desktopDisplaySize, "desktopDisplaySize")) {
                    Text(tr("Auto")).tag("")
                    Text("1280×800").tag("1280x800"); Text("1920×1080").tag("1920x1080"); Text("2560×1440").tag("2560x1440")
                }
                Picker(tr("Window layout"), selection: model.cfg(\.desktopUiMode, "desktopUiMode")) {
                    Text(tr("Desktop")).tag("desktop"); Text(tr("Tablet")).tag("tablet"); Text(tr("Phone")).tag("phone")
                }
                DescribedToggle(tr("Resize display with the window"), detail: tr("scrcpy 4.0 or newer."), isOn: model.cfg(\.desktopFlex, "desktopFlex"))
                DescribedToggle(tr("Open apps on this Mac"), detail: tr("Clicking an app in Apps opens its own Mac window. Hold Option for the other behaviour."),
                                isOn: model.cfg(\.openAppsOnMac, "openAppsOnMac"))
                DescribedToggle(tr("Show Android bars in app windows"), isOn: model.cfg(\.appWindowChrome, "appWindowChrome"))
                DescribedToggle(tr("Keep apps running when the window closes"), detail: tr("Hands the app back to the phone. scrcpy 3.1 or newer."),
                                isOn: model.cfg(\.appWindowKeepAlive, "appWindowKeepAlive"))
            }

            DDSection(tr("scrcpy")) {
                DescribedToggle(tr("Low-level keyboard"), detail: tr("Sends keys as a virtual USB keyboard. Fixes non-Latin layouts and games. scrcpy 2.4 or newer."),
                                isOn: model.cfg(\.scrcpyUhid, "scrcpyUhid"))
                DescribedToggle(tr("Keep the phone awake"), isOn: model.cfg(\.scrcpyStayAwake, "scrcpyStayAwake"))
                DescribedToggle(tr("Blank the phone screen"), detail: tr("The phone still responds; it just isn't showing anything."),
                                isOn: model.cfg(\.scrcpyTurnScreenOff, "scrcpyTurnScreenOff"))
                DescribedToggle(tr("Float mirror windows on top"), isOn: model.cfg(\.scrcpyAlwaysOnTop, "scrcpyAlwaysOnTop"))
            }

            FreeformSection()
        }
    }
}

/// "Skip the screen-share prompt": the phone's PROJECT_MEDIA app-op, read and set over ADB.
private struct ProjectionGrantRow: View {
    @Environment(AppModel.self) private var model
    @State private var status: ProjectionStatus?
    @State private var busy = false

    private var serial: String? { model.devices.first { $0.state == "device" }?.serial }

    var body: some View {
        if serial != nil {
            LabeledContent {
                HStack(spacing: DD.Space.sm) {
                    Button(tr("Allow")) { act { try await Core.shared.adbProjectionGrant(allow: true) } }
                        .disabled(busy || status?.allowed == true)
                    Button(tr("Undo")) { act { try await Core.shared.adbProjectionGrant(allow: false) } }
                        .disabled(busy || status?.allowed != true)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("Skip the screen-share prompt"))
                    Text(status.map { $0.allowed ? tr("Allowed on your phone") : tr("Not allowed") } ?? tr("Checking…"))
                        .font(DD.Font.caption).foregroundStyle(DD.dim)
                }
            }
            .task(id: serial) { act { try await Core.shared.adbProjectionStatus() } }
        }
    }

    private func act(_ f: @escaping () async throws -> ProjectionStatus) {
        busy = true
        Task {
            do { status = try await f() } catch { model.toast(.bad, error.localizedDescription) }
            busy = false
        }
    }
}

private struct FreeformSection: View {
    @State private var status: FreeformStatus?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        DDSection(tr("Freeform windows on the phone"),
                  footer: tr("These are Android developer settings on your phone; they stay if you uninstall DroidDock. Revert restores what was there before.")) {
            if let s = status {
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(Array(["enable_freeform_support", "force_desktop_mode_on_external_displays", "enable_non_resizable_multi_window"].enumerated()), id: \.offset) { i, k in
                        Text("\(k): \((i < s.values.count ? s.values[i] : nil) ?? "unset")").font(DD.Font.monoSmall).foregroundStyle(DD.dim)
                    }
                    if let sdk = s.sdk {
                        Text(s.supported ? "Android API \(sdk)" : "Android API \(sdk) — " + tr("needs Android 15 or newer"))
                            .font(DD.Font.caption).foregroundStyle(DD.dim)
                    }
                }
            } else {
                Text(error ?? tr("Connect an ADB device to manage these.")).foregroundStyle(DD.dim)
            }
            LabeledContent(tr("Draggable windows")) {
                HStack(spacing: DD.Space.sm) {
                    Button(status?.enabled == true ? tr("Already on") : tr("Enable")) { act { try await Core.shared.adbFreeformEnable() } }
                        .disabled(busy || status?.supported != true || status?.enabled == true)
                    Button(tr("Revert")) { act { try await Core.shared.adbFreeformRevert() } }.disabled(busy)
                    IconButton(systemImage: "arrow.clockwise", label: tr("Refresh")) { act { try await Core.shared.adbFreeformStatus() } }
                }
            }
        }
        .task { act { try await Core.shared.adbFreeformStatus() } }
    }

    private func act(_ f: @escaping () async throws -> FreeformStatus) {
        busy = true
        Task {
            do { status = try await f(); error = nil } catch { status = nil; self.error = error.localizedDescription }
            busy = false
        }
    }
}

// MARK: - Files & Sharing

struct FilesSettingsPane: View {
    @Environment(AppModel.self) private var model
    @State private var webdav: WebdavStatus?
    @State private var webdavBusy = false
    @State private var webdavError: String?
    @State private var backfilling = false

    var body: some View {
        let c = model.config ?? DroidConfig()
        SettingsForm {
            DDSection(tr("Mac files for the phone"),
                      footer: tr("Adds a Mac Files tab to the phone app for the folders below. Nothing outside this list is reachable. Muted while DroidDock is paused.")) {
                DescribedToggle(tr("Let the phone browse these folders"), isOn: model.cfg(\.macFsEnabled, "macFsEnabled"))
                ForEach(c.macFsRoots, id: \.self) { root in
                    LabeledContent {
                        IconButton(systemImage: "minus.circle", label: tr("Remove")) {
                            model.setSetting("macFsRoots", c.macFsRoots.filter { $0 != root })
                        }
                    } label: {
                        HStack(spacing: DD.Space.md) {
                            Image(systemName: "folder.fill").foregroundStyle(DD.dim).frame(width: 20)
                            Text((root as NSString).abbreviatingWithTildeInPath).font(DD.Font.mono).lineLimit(1).truncationMode(.middle)
                        }
                    }
                }
                LabeledContent(tr("Add folder")) {
                    Button(tr("Choose…")) {
                        Task {
                            guard let u = await FilePicker.chooseFolder(title: tr("Choose a folder to share")) else { return }
                            if !c.macFsRoots.contains(u.path) { model.setSetting("macFsRoots", c.macFsRoots + [u.path]) }
                        }
                    }
                }
            }

            DDSection(tr("Phone in Finder"),
                      footer: c.webdavWritable ? tr("Saving from any Mac app writes straight to the phone. Takes effect the next time you mount.")
                                               : tr("Read-only. Use the Files screen to upload, rename or delete.")) {
                DescribedToggle(tr("Allow writing to the phone"), detail: tr("Off by default on purpose: a bug on the write path damages files on the phone."),
                                isOn: model.cfg(\.webdavWritable, "webdavWritable"))
                LabeledContent {
                    if webdav?.running == true {
                        Button(tr("Unmount")) { webdavAct { try await Core.shared.webdavStop() } }.disabled(webdavBusy)
                    } else {
                        Button(webdavBusy ? tr("Mounting…") : tr("Mount in Finder")) { webdavAct { try await Core.shared.webdavStart() } }
                            .disabled(webdavBusy || !model.isConnected)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("Phone storage as a volume"))
                        if let m = webdav?.mountPoint, webdav?.running == true { Text(m).font(DD.Font.monoSmall).foregroundStyle(DD.dim) }
                        else if let e = webdavError { Text(e).font(DD.Font.caption).foregroundStyle(DD.badText) }
                        else if !model.isConnected { Text(tr("Needs a linked phone.")).font(DD.Font.caption).foregroundStyle(DD.dim) }
                    }
                }
            }

            DDSection(tr("Photo sync")) {
                DescribedToggle(tr("Auto-sync new photos and videos"), detail: tr("New shots land in the folder below automatically."),
                                isOn: model.cfg(\.photoSyncEnabled, "photoSyncEnabled"))
                LabeledContent(tr("Destination")) {
                    HStack(spacing: DD.Space.sm) {
                        Text(c.photoSyncDest.map { ($0 as NSString).abbreviatingWithTildeInPath } ?? "~/Pictures/DroidDock")
                            .font(DD.Font.mono).foregroundStyle(DD.dim).lineLimit(1).truncationMode(.middle).frame(maxWidth: 220, alignment: .trailing)
                        Button(tr("Choose…")) {
                            Task { if let u = await FilePicker.chooseFolder(title: tr("Photo sync destination")) { model.setSetting("photoSyncDest", u.path) } }
                        }
                    }
                }
                LabeledContent {
                    Button(tr("Back-fill")) {
                        backfilling = true
                        Task { do { try await Core.shared.photoSyncBackfill() } catch { model.toast(.bad, error.localizedDescription) }; backfilling = false }
                    }.disabled(backfilling || !model.isConnected)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("Back-fill existing library"))
                        Text(syncCaption).font(DD.Font.caption).foregroundStyle(DD.dim).monospacedDigit()
                    }
                }
            }

            DDSection(tr("Quick Share"),
                      footer: tr("Makes this Mac appear in the Quick Share sheet of nearby Android, ChromeOS and Windows devices. Every transfer must be accepted here and the code must match. While on, the Mac is visible to everyone nearby.")) {
                DescribedToggle(tr("Receive files from nearby devices"), isOn: model.cfg(\.quickShareEnabled, "quickShareEnabled"))
            }
        }
        .task { webdav = try? await Core.shared.webdavStatus() }
    }

    private var syncCaption: String {
        if let p = model.photoSync, p.total > 0, p.done < p.total {
            return tr("Syncing {done} of {total}", ["done": Int(p.done), "total": Int(p.total)])
        }
        return tr("Pull everything already on the phone, not just new items.")
    }
    private func webdavAct(_ f: @escaping () async throws -> WebdavStatus) {
        webdavBusy = true; webdavError = nil
        Task {
            do { webdav = try await f() } catch { webdavError = error.localizedDescription }
            webdavBusy = false
        }
    }
}
