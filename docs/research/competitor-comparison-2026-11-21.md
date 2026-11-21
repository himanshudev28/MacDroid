# DroidDock vs open-source competitors — 2026-11-21

Research only; no DroidDock code was changed. Six parallel code reads (Sonnet) plus a star/activity
ranking (Haiku), then spot-checks against DroidDock's own source. Raw per-competitor notes with
file citations: [`competitor-notes-2026-11-21/`](competitor-notes-2026-11-21/). Clones lived in the
session scratchpad (not the repo).

**License key** — 🟢 permissive (MIT/Apache/BSD/Unlicense): adapt with credit · 🔴 GPL/MPL/no
license: ideas only, clean-room, copy no code or doc text. Wire constants (ports, UUIDs, AT
commands, packet names) are interface facts and may be reimplemented.

**Sessions** — `bt-calls` (Classic BT / HFP calls), `camera`, `bt-offline` (BLE fallback link),
`hangs` (freezes, reliability), `new` (no current owner).

---

## 1. Ranked recommendations

Ranked by value ÷ effort, with session fit as tie-breaker. V/E = value / effort (H/M/L, S/M/L).

| # | Change | V/E | Source (license) | Session |
|---|---|---|---|---|
| 1 | **ADB mirror: switch on scrcpy's control socket.** We launch the server with `control=false` (`core/src/scrcpy_client.rs:192`) and spawn one `adb shell input` process per tap/key, ASCII-only text (`core/src/mirror.rs:288-336`). Speaking scrcpy's control protocol gives multi-touch, scroll, Unicode text, clipboard (as shell uid — bypasses Android 10+ background clipboard block) and on-demand keyframe ("reset video"), and ends per-event process spawns. | H/M | scrcpy `server/.../control/ControlMessageReader.java` 🟢 Apache | hangs |
| 2 | **Hard deadline + cleanup on every connect/handshake/write**, and specifically rework the BLE connect. Our watchdog already cancels at 10 s (`BleService.swift:362-378`) — the bug is that connect *never completes*. Differences to test vs AirSync (same CoreBluetooth no-timeout problem, same 10 s watchdog workaround): constant connectable service UUID (we match rotating service data, `BleService.swift:359`), auth only after full service discovery, phone pauses advertising while Wi-Fi is up, drop BLE only after Wi-Fi stable 5 s, scan restart every 10 s with stale-device pruning. KDE adds: one serialized connect at a time, drop the duplicate when inbound/outbound race, 500 ms settle before first read. | H/S-M | AirSync `BLECentralManager.swift`, `BleGattServer.kt` 🔴 MPL; KDE BT backend 🔴 GPL; Tether `SocketTLS.swift` 🔴 GPL | bt-offline, hangs |
| 3 | **Bluetooth-Classic call control (no audio).** phone-link (MIT) shows the working recipe: Mac acts as HFP Hands-Free, opens RFCOMM to the phone's Audio Gateway (UUID 0x111F) via IOBluetooth using already-paired SDP records, speaks AT commands itself (`AT+BRSF`, `CIND`, `CMER`, `CLIP`, `CCWA`, `CLCC`, `ATA`, `AT+CHUP`, `ATD`), parses `RING`/`+CLIP`/`+CIEV`. Gives answer/decline/hang-up/dial/caller ID with Wi-Fi off. **It does not give call audio** — no SCO is opened; phone-link's README states macOS exposes no voice link to apps. Untried lead worth ≤2 h: legacy `IOBluetoothHandsFreeDevice` / `IOBluetoothSCOAudioDevice`. | H/M-L | phone-link `engine/crates/calling/src/control/{mod,at,link}.rs`, `gui/PhoneLinkKit/Edges/PhoneBluetoothAdapter.swift` 🟢 MIT | bt-calls |
| 4 | **Camera: decide notarization first.** A CoreMediaIO camera extension (the only way Zoom/Meet/Teams see a virtual camera) needs Developer ID signing, the System Extension entitlement + provisioning profile, and notarization. Self-signed/SwiftPM builds fail ("Extension not found in App bundle"); DAL plug-ins aren't loaded by modern apps. No OSS Android→Mac virtual camera exists without a paid team. **Now:** expose the phone camera as a local MJPEG/RTSP stream or a capturable window + "Use in OBS" guide (OBS Virtual Camera is notarized). **Later, with a paid account:** extension with host-app decode, sink→source stream, ~3-frame jitter buffer, host-clock PTS restamp. | H/S now, L later | phone-link `PhoneCameraExtension/CameraDevice.swift` 🟢 MIT; Cam2Camera 🔴 no license | camera |
| 5 | **Wi-Fi mirror encoder: add `KEY_REPEAT_PREVIOUS_FRAME_AFTER` (~100 ms)** and scrcpy's retry-at-smaller-size on encoder failure; check codec reset in place on rotation (EOS→reconfigure, 300 ms resize debounce, SPS/PPS before every IDR). `MirrorService.kt` sets priority/latency/operating-rate but not repeat-frame. | H/S | scrcpy `SurfaceEncoder.java:100-340`, `DisplayResizeDebouncer.java` 🟢 Apache | hangs |
| 6 | **Socket-level keepalive on the Mac listener** (Darwin `SO_KEEPALIVE` + `TCP_KEEPALIVE` idle 30 s / `TCP_KEEPINTVL` 10 s / `TCP_KEEPCNT` 3). We have app-level pings (`link_quality.rs`, 4 s / 20 s) and OkHttp `pingInterval(20s)` on the phone, but no kernel keepalive, so a half-open socket the app loop isn't touching can linger. Note: Linux's `TCP_USER_TIMEOUT` doesn't exist on Darwin (use `TCP_RXT_CONNDROPTIME`). Cheap insurance. | M/S | idea from KDE `lanlinkprovider.cpp` (OS API, no license issue) | hangs |
| 7 | **Android 15 hidden OTPs:** when on adb/USB link, run `appops set com.droiddock.app RECEIVE_SENSITIVE_NOTIFICATIONS allow`, and add it as a Setup Check row. Without it Android 15 redacts verification codes, breaking our OTP Copy. Not present in our code today. | M-H/S | Sefirah 🔴 GPL (one adb command; trivial to write) | new |
| 8 | **Quick Share send: 64 KiB → 512 KiB chunks** (`core/src/quickshare/client.rs:43`); NearDrop and rquickshare use 512 KiB. Plus **"Show on phone"**: over our WS link, launch Android's Quick Share receive screen so the phone becomes visible to the Mac without BLE (a Mac can't emit the 0xFE2C service-data advertisement that wakes Android — CoreBluetooth can't set service data). Fallback: NearDrop's QR wake. | M-H/S | NearDrop 🟢 Unlicense; rquickshare 🔴 GPL (ideas) | new |
| 9 | **Pause Mac media when the phone rings**, resume only what we paused, 2 s debounce; ringing card → on-call pill with timer. | M/S | Tether `CallMediaPauser.swift`, `CallCard.swift` 🔴 GPL; KDE telephony 🔴 | bt-calls |
| 10 | **Homebrew cask** (`livecheck` github_latest + `zap` list). Tether and NearDrop ship casks un-notarized with a fixed self-signed cert — the same model we use. AirSync additionally uses Sparkle. | M-H reach/S | AirSync `airsync.rb` 🔴 MPL (a cask is a few lines; write our own) | new |
| 11 | **Quick Share receive UX:** Accept/Decline actions + PIN on the notification; auto-accept for favourite/paired devices ("accept and pair"). Every request currently prompts (`quickshare/server.rs:232`). | M/S-M | NearDrop 🟢; LocalSend 🟢 Apache | new |
| 12 | **Disconnect grace that ignores Mac-side network changes** (NWPathMonitor: "phone left" vs "Mac changed Wi-Fi") before triggering BLE fallback / reconnect UI / away-lock. | M/S | Tether `AwayLock.swift` 🔴 GPL | bt-offline, hangs |
| 13 | **Keep control frames moving during transfers.** KDE sends payloads on separate on-demand TLS sockets; we multiplex everything on one WS. Either a second WS for bulk binary or strict priority interleave. If BLE ever carries bulk data, use credit-based channel multiplexing (KDE BT framing). | M-H/M | KDE 🔴 GPL (ideas) | hangs, bt-offline |
| 14 | **Incremental SMS sync**: per-thread checksum, Mac sends known threads, phone returns only changed ones; offline send queue flushed on reconnect. Cuts reconnect bursts (and BLE load — `sms-threads` is BLE-eligible). | M/S-M | Sefirah `SMSHelper.kt` 🔴 GPL | hangs, bt-offline |
| 15 | **Second screen via ADB with scrcpy's virtual-display flags** (TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, TOUCH_FEEDBACK_DISABLED; OWN_FOCUS on 14; IME policy; flex resize from Mac window size). Our MediaProjection path can only use `FLAG_PUBLIC` (`MirrorService.kt:222`); the richer flags need shell uid. Could reduce the keyguard/keeper workarounds. | H/M | scrcpy `NewDisplayCapture.java:213-235` 🟢 Apache | hangs |
| 16 | **Screen-off while mirroring** via `setDisplayPower(false)` (scrcpy `--turn-screen-off`), restored by a cleanup process if the client dies. ADB mode only. | M/S | scrcpy `CleanUp.java` 🟢 | hangs |
| 17 | **App Intents / Shortcuts + Share extension "Send to phone"** with device chooser; hand-off via private pasteboard + distributed notification, not a URL scheme (a web page can't push files). Blocked today: no Xcode, SwiftPM won't run `appintentsmetadataprocessor`, extensions are awkward. Keep AppleScript; revisit if Xcode becomes available. | H/L (tooling) | Tether `TetherShare/ShareHandoff.swift` 🔴 GPL; LocalSend 🟢 | new |
| 18 | **Pairing SAS + timeout:** 8-hex code from SHA-256 of both public keys, 30 s accept window — covers "scanned the wrong QR". | M/S | KDE `PairingHandler.kt` 🔴 | new |
| 19 | **Mirror window polish:** drop `.apk` → `adb install`, other files → push; shortcut set (fullscreen, rotate, fit, screen off); record by muxing H.264 packets with AVAssetWriter (no ffmpeg). | M/S | scrcpy `input_manager.c`, `file_pusher.c` 🟢 | new |
| 20 | **App audio over ADB mirror / call-audio primitive:** scrcpy's `AudioPlaybackCapture` (Android 13+, hidden AudioPolicy, `voiceCommunicationCaptureAllowed`) is the same primitive the bt-calls session already proved via adb (CALL_AUDIO_INTERCEPTION). Handset's player caps latency (40 ms prebuffer, 120 ms cap, drop oldest). | M general / H calls, M | scrcpy 🟢; handset 🟢 Apache | bt-calls |
| 21 | **Updater hardening:** verify bundle ID matches and build is higher, detect App Translocation / read-only install location before swapping. (We already verify an Ed25519 signature.) | M/S | phone-link `UpdateInstaller.swift` 🟢 MIT | new |
| 22 | **Fake Bluetooth peer for tests** (scripted HFP/BLE peer) so bt-offline and bt-calls can be tested without the phone lock. | M/M | phone-link `ScriptedBluetooth` (`edge.rs`, `calling/src/control/testing.rs`) 🟢 MIT | bt-offline, bt-calls |
| 23 | **Reverse dial / wake pokes:** phone also listens (TLS) so the Mac can connect when the phone can't reach it (hotspot, guest Wi-Fi, Tailscale); AirSync phone listens for Mac wake pokes. Security cost: a listening port on the phone — design carefully. | M/M | Sefirah 🔴, AirSync 🔴 | bt-offline |
| 24 | **Notification extras:** `silent` flag for FLAG_ONLY_ALERT_ONCE updates, group-summary/ongoing filter, icon-hash cache (send icon only on change), "only when phone screen is off", suppress banner when the native Mac app (e.g. WhatsApp) is running. Per-app mute already exists (`NotificationsPane.swift`). | M/S-M | KDE, GSConnect, Tether 🔴 | new |
| 25 | Smaller: trusted-networks list (no advertising on café Wi-Fi); passive scan for 0xFE2C to notice "phone is sharing nearby"; "Use Mac Shortcut" from phone via `/usr/bin/shortcuts`; run-command / presenter plugins; OEM auto-start helper (Xiaomi/OPPO/Vivo) in Setup Check; redacted exportable log bundle; Quick Share resume over WebDAV Range. | L-M/S | mixed | mixed |

### Not worth doing
- **Bluetooth call audio on the Mac via HFP** — nobody has it (phone-link, Sefirah on Mac/Linux, KDE, Tether all lack it); Sefirah gets it on Windows only through the OS's PhoneLineTransportDevice. Spend at most the 2 h spike in #3.
- **Wi-Fi Direct / bandwidth upgrade for Quick Share** — large, no Mac project has it.
- **Copying AirSync's transport** — plain `ws://` + AES key in QR and plaintext BLE auth token; ours (pinned TLS, HMAC'd discovery) is stronger.
- **phone-link's architecture** — it has no Android app; it rides Microsoft's Link to Windows cloud relay with a Microsoft sign-in.

### Agent claims rejected after checking our code
- "DroidDock's camera is only a scrcpy window" — false; the ADB camera (`adb.rs:1254`) is an extra; the Wi-Fi camera runs Camera2 in `MirrorService.kt`/`CameraLenses.kt` with no adb.
- "Add Finder mount of phone storage" — already exists (`webdav.rs` + `mount_webdav`). Tether's NFS soft-mount and phone-link's File Provider are alternatives only if WebDAV mounting hangs Finder.
- "Add capability negotiation" — exists (`caps` in hello/welcome, `protocol.rs`).
- "Add per-app notification filter" / "constant-time token compare" / "connectedDevice FGS type" — all exist.

---

## 2. Landscape (GitHub, 2026-11-21)

Search API rate limit cut discovery at 19 rows; webcam/SMS/mirror-on-Mac queries were not run.

| Repo | ★ | Last push | License | What | Activity |
|---|---|---|---|---|---|
| Genymobile/scrcpy | 155k | 11-19 | Apache-2.0 | Mirror + control over adb | active |
| localsend/localsend | 100k | 11-21 | Apache-2.0 | LAN file/text transfer | active |
| barry-ran/QtScrcpy | 33k | 10-15 | Apache-2.0 | Qt scrcpy GUI, multi-device | active |
| viarotel-org/escrcpy | 12.4k | 10-26 | Apache-2.0 | Electron scrcpy GUI | active |
| grishka/NearDrop | 6.4k | 09-19 | Unlicense | Mac Quick Share receiver | active |
| SimonAKing/scrcpy-gui | 4.2k | 11-11 | MIT | scrcpy GUI | active |
| KDE/kdeconnect-kde | 4.2k | 11-21 | GPL-2/3 | KDE Connect desktop | active |
| GSConnect | 3.8k | 10-18 | GPL-2.0+ | KDE Connect for GNOME | active |
| Martichou/rquickshare | 3.7k | 05-01 | GPL-3.0 | Quick Share Linux/mac | slow |
| shrimqy/Sefirah (+Android 1.0k) | 3.1k | 11-20 | GPL-3.0 | Phone Link alt. (Windows) | active |
| KDE/kdeconnect-android | 1.5k | 11-20 | GPL-2/3 | KDE Connect Android | active |
| andyholmes/valent | 0.9k | 11-15 | GPL-3.0 | KDE Connect for GNOME | active |
| sameerasw/airsync-mac (+android 296) | 0.9k | 11-16 | MPL-2.0 (+paid EULA) | Closest Mac-native rival | active |
| subhashhhhhh/Tether | 278 | 11-01 | GPL-2.0 | Native Swift Mac KDE-Connect client | active |
| nnnlog/phone-link (+fork) | 31 | — | MIT | Mac client for Link to Windows + BT HFP control | small |

Also read: handset (Apache, Rust/Mac scrcpy client), dex-for-mac (MIT), Cam2Camera (no license),
droidcam-obs-plugin (GPL-2), StreamBerry-Cam (MIT).

---

## 3. Feature comparison (deep-dive set)

✅ has · ◐ partial · — none

| Area | DroidDock | AirSync | KDE Connect / GSConnect | Tether | Sefirah | phone-link | scrcpy GUIs |
|---|---|---|---|---|---|---|---|
| Notifications + reply | ✅ banners, reply, OTP copy, open as Mac window | ✅ (+AI summaries macOS 26) | ✅ per-app filter | ✅ | ✅ | ✅ (via MS) | — |
| SMS | ✅ threads, search | ◐ | ✅ | ✅ | ✅ incremental sync | ✅ | — |
| Call control (Wi-Fi) | ✅ incl. WhatsApp | ✅ (paid) | ◐ mute only | ◐ notify, media pause | ✅ | ✅ | — |
| Call control (BT Classic) | — | — | — | — | Windows only | ✅ HFP AT | — |
| Call audio on Mac | — | — | — | — | Windows only | — | ◐ via adb audio capture |
| Clipboard | ✅ text+images | ✅ | ✅ | ✅ | ✅ | ◐ | ✅ (adb) |
| Files / Finder mount | ✅ browser + WebDAV mount | ◐ | ✅ SFTP mount | ✅ NFS mount | ✅ | ✅ File Provider | push only |
| Quick Share | ✅ send+receive | — | — | — | — | — | — |
| Mirror without adb | ✅ MediaProjection | ✅ (paid) | — | — | — | ◐ Desktop View | — |
| Mirror via adb | ◐ scrcpy, no control socket | — | — | — | scrcpy | — | ✅ full |
| Second screen | ✅ | — | ◐ virtual monitor plugin | — | — | — | ✅ `--new-display` |
| Camera feed | ✅ Wi-Fi + adb | — | — | — | — | ✅ CMIO extension | ✅ (Linux v4l2 only) |
| Offline link | ◐ BLE (flaky) | ◐ BLE | ✅ BT RFCOMM | — | — | BT Classic | USB |
| Security | ✅ pinned TLS, HMAC discovery | weak (ws:// + AES) | ✅ TLS TOFU + SAS | ✅ (SecureTransport) | ✅ TLS + key check | MS cloud | adb |
| Mac remote from phone | ✅ | ◐ | ✅ mousepad/keyboard/presenter | ✅ | ◐ | — | — |
| Distribution | GitHub + Ed25519 updater, self-signed | Sparkle, cask, Play Store, paid tier | distro / Play | cask + own updater, self-signed | Store | notarized | brew/GitHub |

**Where DroidDock is ahead:** Quick Share both ways, adb-free mirroring + second screen + app
windows, WhatsApp call control, OTP copy, pinned-TLS pairing, BLE fallback at all (most have none),
16 languages, native footprint (~0 % idle CPU). The gaps are reliability (BLE connect, ADB input
path, half-open sockets), Bluetooth-Classic paths, and distribution polish (cask, notarization).

---

## 4. Per-session hand-off

- **bt-calls:** #3 (HFP AT control, MIT phone-link — adaptable), #9, #20, #22. Don't promise HFP audio; 2 h spike on `IOBluetoothHandsFreeDevice` max. Pairing note: `IOBluetoothDevicePair.replyUserConfirmation` is a no-op on current macOS; phone-link uses a private CoreBluetooth pairing agent (`PhoneBluetoothAdapter.swift:234-260`). Unverified: a phone already on a car/headset HFP may refuse a second HF.
- **bt-offline:** #2 first (connect never completes — try constant connectable UUID + auth after discovery), #12, #13, #22; longer-term consider Classic RFCOMM (KDE UUID `185f3df4-3268-4e3f-9fca-d4d5059915bd`, needs an IOBluetooth RFCOMM test on hardware) since the phone already holds a Classic bond with this Mac.
- **camera:** #4 — notarization/paid-account decision before any extension work; OBS route now.
- **hangs:** #1, #5, #6, #13, #14, #15, #16.
- **new (unowned):** #7, #8, #10, #11, #17-#19, #21, #24.
