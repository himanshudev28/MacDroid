# KDE Connect + GSConnect vs DroidDock (code-level findings)

Clones: scratchpad/clones/{kdeconnect-kde,kdeconnect-android,gnome-shell-extension-gsconnect} (shallow, HEAD 2026-11-21).
Paths below are relative to those. A = kdeconnect-android/src/main/java/org/kde/kdeconnect, K = kdeconnect-kde.

## Licenses (exact, from SPDX headers)
- kdeconnect-kde + kdeconnect-android: `GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL` (some LGPL libs in LICENSES/). IDEAS ONLY. Wire protocol (packet type names, ports 1716-1764, UUIDs) is a documented interface; reimplementing compatible *constants* is fine, copying code is not.
- GSConnect: GPL-2.0-or-later (MPL-2.0/CC assets also in LICENSES/). IDEAS ONLY.
- Multiplexing protocol doc (K/core/backends/bluetooth/"Multiplexing protocol.md") is a spec; clean-room implement from the description of the idea, do not copy text/code.

## Protocol and connection

### Discovery (A/backends/lan/LanLinkProvider.kt, MdnsDiscovery.kt; K/core/backends/lan/lanlinkprovider.cpp)
- UDP broadcast identity packet to 255.255.255.255:1716, plus mDNS (NSD) `_kdeconnect._udp`. Receiver of UDP connects BACK via TCP to sender's advertised tcpPort (1716-1764 range) and sends its identity; TLS roles are then reversed (the side that accepted TCP acts as TLS *client*; see SslHelper.convertToSslSocket clientMode). 
- Anti-abuse: `rateLimitByIp` + `rateLimitByDeviceId` (MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE), non-private-address drop (`isAllowedAddress`), tcpPort range check, `socket.setSoTimeout(10s)` for identity handshake, identity size cap (MAX_UDP_PACKET_SIZE), BoundedLineReader helper.
- Network-change driven: ConnectivityManager NetworkCallback on wifi/vpn/ethernet/BT/USB/WifiAware transports (BackgroundService.kt ~l.290) -> `onNetworkChange` -> debounced (NETWORK_CHANGE_DEBOUNCE_MS) re-broadcast + restart mDNS. mDNS discovery runs in a bounded window (`stopDiscoveryRunnable`) to release MulticastLock (battery).
- Trusted-network list by SSID (helpers/TrustedNetworkHelper.kt): don't announce on untrusted Wi-Fi (cafe). 
- Custom device IP list for networks where broadcast is blocked (customDeviceAddresses).

### TLS, pairing, TOFU (A/helpers/security/SslHelper.kt, PairingHandler.kt)
- Each device: self-signed RSA cert, CN = deviceId. TLSv1.2 forced ("1.3 causes issues on some devices").
- Unpaired: trust-all TrustManager (needClientAuth set for server side) -> then *verification key* shown on both screens: first 8 hex chars of SHA-256 over sorted(pubkeyA||pubkeyB) + (protocol v8) pairing timestamp. User eyeballs the 8-char code on both devices (PairingHandler.getVerificationKey). Paired: per-device SSLContext containing ONLY that device's pinned cert (trustedSslContextCache). v8 adds timestamp so a replayed/old pair request is rejected (>1800s style check in PairingHandler ~l.78) and pairing request auto-times out after 30 s (`delay(30.seconds)`).
- Cert CN must equal deviceId in identity packet (LanLinkProvider/BluetoothLinkProvider.isCertificateValid); trusted device with changed cert is refused.
- DroidDock today: QR-embedded fingerprint pinning (stronger for first pairing: out-of-band) but no numeric SAS fallback for manual/no-camera pair, no pairing timeout/timestamp replay protection that I could confirm.

### Protocol versioning (A/helpers/DeviceHelper.kt PROTOCOL_VERSION = 8)
- `protocolVersion` int in identity packet, incoming/outgoing packet-type capability lists in identity (plugins advertise `incomingCapabilities`/`outgoingCapabilities`; a plugin is enabled only if the peer lists a matching type). `compareProtocolVersion()` drives a "peer is newer, update me" hint in UI (ui/compose/extensions/device/DeviceExtensions.kt). Gives forward-compat without breaking older peers.

### Keepalive / dead-link detection
- K/core/backends/lan/lanlinkprovider.cpp `configureSocket` l.655-690: SO_KEEPALIVE + TCP_KEEPIDLE 30s / KEEPINTVL 10s / KEEPCNT 3 + TCP_USER_TIMEOUT 60s (Darwin uses TCP_KEEPALIVE for idle). Comment: stale "ESTABLISHED" link otherwise stays `isReachable` ~7800 s (bug 476747). No app-level ping; relies on kernel. Android side only `setKeepAlive(true)` (LanLinkProvider.kt l.282) so desktop is the one that cuts.
- => DroidDock (hangs, bt-offline): apply the same socket options on the Mac WS listener sockets (Rust `socket2`/`libc`, Darwin TCP_KEEPALIVE+KEEPINTVL+KEEPCNT) so a vanished phone is dropped in ~60 s and UI/link state flips to "offline" instead of hanging sends. Value H, effort S, license: none (OS API). Session: hangs, bt-offline.

### Bluetooth backend (A/backends/bluetooth/*.kt, K/core/backends/bluetooth/*)
- Classic RFCOMM (SPP), NOT BLE. Fixed service UUID `185f3df4-3268-4e3f-9fca-d4d5059915bd` (plus byte-reversed variant for buggy SDP stacks) registered via `listenUsingRfcommWithServiceRecord("KDE Connect", UUID)` on Android; the other side `createRfcommSocketToServiceRecord`. Only works between *OS-bonded* devices (user pairs in Bluetooth settings first). No Wi-Fi needed at all - that is the offline value.
- Android periodically `fetchUuidsWithSdp()` on bonded devices (ACTION_UUID receiver) to see if the peer advertises the KDE UUID, then connects; desktop side `connectTimer` interval 30 s calls onStartDiscovery (kde bluetoothlinkprovider.cpp l.32). `canRunKdeConnect()` skips LE-only and audio/peripheral/wearable/imaging classes because SDP paging an absent device blocks the radio ~5 s (BluetoothLinkProvider.kt l.~320-345). Dedup race: if inbound socket arrived while outbound connecting, drop the new one (`sockets` map, l.~400).
- 500 ms `Thread.sleep` after connect "to let bluetooth initialize" before first read.
- Multiplexer (ConnectionMultiplexer.kt, doc "Multiplexing protocol.md"): ONE RFCOMM socket carries many logical channels. Frame = type(1) | len(2 BE) | channelUUID(16) | data. Types: PROTOCOL_VERSION(0, must be first, min/max u16), OPEN(1), CLOSE(2), READ(3: credit of N more bytes), WRITE(4). Main channel UUID a0d0aaf4-1072-4d81-aa35-902a954b1266 is implicit. Credit-based flow control: a writer may only send what reader credited -> no head-of-line blocking of small JSON packets while a big file streams on another channel; payloads get their own channel (BluetoothUploadJob/DownloadJob). Role-agnostic, no handshake.
- No TLS over BT: identity packet (newline JSON) carries PEM cert; trust = BT link-layer encryption + cert pinned vs TrustedDevices store (isCertificateValid). Then same pairing/verification-key flow.
- Link priority: LanLinkProvider.priority = 20, Bluetooth = 10, loopback = 0 (A/Device.kt l.347-353 sorts links by provider priority; sendPacket picks first link that accepts). A device can hold several links simultaneously; LAN wins when present, BT is silent hot-standby. 
- Takeaways for DroidDock bt-offline: (1) our BLE GATT path is far more fragile than classic RFCOMM for a bonded phone<->Mac pair; KDE's approach works with a plain byte stream. BUT macOS exposes NO public RFCOMM *server/client for third-party apps without MFi?* - IOBluetooth `IOBluetoothDevice.openRFCOMMChannelSync` and `IOBluetoothSDPServiceRecord` DO exist on macOS (K uses Qt Bluetooth on macOS via IOBluetooth), so an RFCOMM transport from the Mac (client side, phone listens with `listenUsingRfcommWithServiceRecord`) is feasible and avoids BLE MTU/connect-hang issues. Needs a one-time OS-level bond (phone+Mac pair in Bluetooth settings) - that same bond is also what bt-calls (HFP) needs, so shared UX. Value H, effort L. Session: bt-offline (and bt-calls bond). Validate on hardware first: macOS 14+ IOBluetooth RFCOMM from a sandbox-less app works; entitlement com.apple.security.device.bluetooth only if sandboxed.
  (2) Even if we stay on BLE: copy the *framing* idea - one logical stream, 3-byte header + channel id + credit-based READ messages, so large transfers can't starve control JSON. Value M, effort M.
  (3) Skip SDP/connect attempts for devices that can't host us, and pace reconnect (30 s timer) to keep radio free - matches our "connect hangs ~10 s": do a single serialized connect with hard timeout + cancel and exponential backoff instead of parallel attempts. Value H, effort S. Session: bt-offline, hangs.
  (4) Keep LAN priority > BT and keep both links live; send over best, fail over on write error (Device.kt). DroidDock should treat BLE as standby with periodic cheap liveness (see below). Value M, effort M.

### Reconnection
- No bespoke backoff: reconnect is event-driven (network callback, UDP rebroadcast on every network change, mDNS window, BT 30 s timer). `Device.kt` keeps plugins loaded with `keepAliveWhenUnreachable` for plugins that need to survive link loss (e.g., battery/mpris receivers). KDE also persists paired devices' last IP for direct connect (customDevices).

## Android service reliability (A/BackgroundService.kt, AndroidManifest.xml)
- Foreground service with `foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission (API 34+). `startForeground` in try/catch for ForegroundServiceStartNotAllowedException then returns START_STICKY. Persistent notification is user-toggleable (`isPersistentNotificationEnabled`) - they run a sticky service without notification if the user opts out. Restart triggers: RECEIVE_BOOT_COMPLETED receiver, network callback `refresh` intent, notification-listener service binding (NotificationListenerService keeps process alive on many OEMs). They do NOT prompt for battery-optimization exemption in code (grep found no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); they rely on connectedDevice FGS + NotificationListener. 
- DroidDock: confirm BridgeService declares connectedDevice type and that our own setup-check panel has a "battery optimization / Samsung sleeping apps" row. Value M, effort S. Session: hangs.

## Plugin gaps (features DroidDock lacks or only partly has)
Plugin dirs: K/plugins/*, A/plugins/*, GSConnect src/service/plugins/*.js.
| Plugin | What | DroidDock status | Value | Effort | Session |
|---|---|---|---|---|---|
| runcommand / remotecommands | Mac-defined (or phone-defined) named shell commands runnable from the other device; phone shows list from Mac and triggers. | Lacks. Phone-side "run Mac command" list (user-defined in Mac settings, executed with allowlist) | M | S-M | new |
| presenter + pointer | Phone as slide clicker/laser pointer (gyro -> pointer deltas, volume keys -> next/prev) A/plugins/presenter | We have trackpad remote; add Presenter mode (volume keys, big next/prev, optional laser on Mac overlay) | M | S | new |
| digitizer | Phone as drawing tablet (pen events -> Mac), A/plugins/digitizer, K/plugins/digitizer | Lacks | L | M | new |
| remotekeyboard | Mac keyboard types into the phone's focused field (IME or a11y) A/plugins/remotekeyboard(+ime) | We have a11y; verify we support Mac keyboard -> phone text field (also on mirror). | M | S | new |
| findmyphone | ring phone | HAVE | - | - | - |
| mousepad/remotecontrol | phone as trackpad | HAVE (Mac remote) | - | - | - |
| shareinputdevices (K) | Mac keyboard/mouse flow to another device (barrier-style) | Lacks; potentially tie into virtual-display second screen (move cursor to phone screen) | M | L | new |
| virtualmonitor (K) | Phone as external display (needs krfb) | HAVE via virtual display second screen (we're ahead) | - | - | - |
| sftp | Phone runs SSH/SFTP server, Mac mounts | We use WebDAV; fine. Their per-session random password + pinned key is a decent pattern | L | - | - |
| mpris / mprisremote / pausemusic | media control both directions; pause music on incoming call | We have Now Playing remote. "Pause Mac media when phone rings" is cheap and missing: Value M, Effort S, session bt-calls/new |
| telephony + mmtelephony | ringing/talking/missedCall events, mute ringer, SMS | HAVE (richer) | - | - | - |
| connectivity-report | signal strength / network type per SIM | Lacks; trivial status chip in menu bar panel | L | S | new |
| battery | level + charging + low-battery threshold notification (event "thresholdEvent") | Likely have battery; add "phone battery low" Mac notification if missing | L | S | new |
| systemvolume / remotesystemvolume | per-stream phone volume sliders on desktop | Lacks per-sink phone volume (media/ring/alarm); M value, S effort | M | S | new |
| screensaver-inhibit/lockdevice | lock Mac when phone leaves / Bluetooth proximity | Lacks "lock Mac when phone link lost / phone not near" - strong fit with BLE presence. Value M, Effort S-M (security-sensitive: opt-in, debounce) | bt-offline |
| share | files/URLs/text; multi-file composite transfer job over ONE payload stream per file; open URL on other side | HAVE files; check "send URL -> open in Mac browser" and "send text" quick-actions via share sheet. L | S | new |
| notifications (A/plugins/notifications) | see next section | partial | H | M | new |
| contacts | vCard sync into Mac address book/KDE contacts via packets (uid timestamps for delta sync) | We have contacts lookups; delta sync by timestamp is the idea | L | M | new |
| clipboard | has `syncTimestamp` per packet to resolve loops / stale sync on reconnect (connect packet) | Check we do not echo-loop; add a monotonic timestamp. M | S | hangs |
| ping | trivial reachability test | Our setup check panel could expose a "ping phone / round-trip ms" button. L | S | hangs |

## Notifications (A/plugins/notifications/NotificationsPlugin.kt, NotificationsSettingsFragment.kt, AppDatabase.kt; GSConnect src/service/plugins/notification.js)
- Per-app allow-list stored in Room DB (AppDatabase) with a settings screen listing all installed apps with toggles; GSConnect mirrors this: apps are auto-registered on first notification (`_applications[appName]` in notification.js l.249) with enabled flag, shown in prefs. 
- Filters: skip FLAG_GROUP_SUMMARY, own notifications, ongoing/persistent (option), "show only when screen is off" pref (`pref_notification_screen_off_key`), "privacy: hide content" option.
- Dedup of icon payload: SHA hash of icon bytes per key (`notificationsIcons[key]`); icon sent as payload only when hash changed, otherwise only `payloadHash` -> the Mac reuses cached icon. Saves bandwidth/battery on chatty apps. 
- `silent` flag: update of an existing notification with FLAG_ONLY_ALERT_ONCE (not conversation) is sent silent so the Mac does not re-ping on every progress/typing update. 
- Cancel packet carries notification key -> bidirectional dismiss; reply via RepliableNotification with request ids.
- DroidDock: add per-app mute list + "silent update" semantics + icon-hash cache + group-summary filter (check which we have). Value H, effort M. Session: new.

## UI/UX worth copying
- KDE Connect pairing UX (A/ui/compose/screen/device/DevicePairingScreen.kt): big verification key text shown during "Pair request sent/received", Accept/Reject, 30 s timeout. DroidDock: show the SAS code on both ends as a second factor even for QR pairing (QR gives pinning; SAS covers "scan wrong QR" cases). Value M, effort S. Session: new.
- Device list with per-device "reachable / paired / available" sections and plugin toggles per device with warnings listing missing permissions per plugin (A/ui/compose/extensions/device). Our setup-check could be per-feature with "Fix" buttons. Value M, effort M.
- GSConnect (src/shell/*, src/service/ui/*): device menu integrated into GNOME Quick Settings/Shell panel with per-device submenu of actions (GMenu model exported over D-Bus, `src/shell/gmenu.js`), file-manager context menu "Send to device" (nautilus-extension/nautilus-gsconnect.py), browser extension (webextension/) "Send tab to phone". DroidDock equivalents: Finder context menu / Share extension ("Send to phone"), Safari/Chrome "send tab" extension, Shortcuts/AppleScript actions (we have sdef). Value M-H (Share extension), effort M. Session: new.
- GSConnect messaging window (src/service/ui/messaging.js) + contacts chooser, SMS "new thread" autocompletion; mousepad window; all use native toolkit. Same lesson as our SwiftUI approach.
- Per-device "keybindings" (src/shell/keybindings.js) - global hotkeys per action (ring phone, send file). We have only ⌥Space; allow user-bindable global shortcuts for ring/clipboard-send/mirror. L-M value, S effort.

## Security notes worth encoding
- Pair-request timestamp (v8) bound into verification key + 30 s accept timeout -> replay/MITM-timing resistance (PairingHandler.kt).
- Rate limit connections per IP and per deviceId; drop non-RFC1918 UDP sources; cap identity packet size and line length (BoundedLineReader.kt); `MAX_PACKET_SIZE` discard in K landevicelink.cpp. DroidDock's Mac WS server on 0.0.0.0:48484: confirm per-IP handshake rate limit and message size caps (hangs/DoS angle). Value M, effort S.
- `ConstantTimeCompare.kt` for secret compares. Check our token compare in Rust uses subtle/constant-time. Value L, effort S.
- Untrusted-network allow-list (SSID) so the Mac/phone do not advertise on cafe Wi-Fi. Value M, effort M (needs location permission on Android for SSID -> they gate behind `hasPermissions`; on Mac, CoreWLAN SSID also needs Location permission on 14+).
- Cert changes for trusted device are refused rather than silently re-trusted (TOFU strictness).

## Battery / perf choices
- mDNS discovery windowed, not continuous (MulticastLock held only during window).
- Debounced network-change handler, UDP rebroadcast only on change (no periodic beacon while connected).
- Icon hash dedup; payloads on separate on-demand TLS sockets (ports from 1739; PAYLOAD_TRANSFER_MIN_PORT) so control socket stays responsive (A/backends/lan/LanLink.kt l.~182, 10 s accept timeout). DroidDock already uses binary frames on the same WS: risk of head-of-line blocking big files vs control (relevant to hangs). Idea: open a second WS/connection per large transfer, or chunk+interleave with priority for control frames. Value M-H, effort M. Session: hangs.
- Plugins load only when peer advertises matching capability and only while reachable (Device.kt l.701), so idle receivers are not running.

## Top ideas (ranked) - summary table
1. TCP keepalive tuning (30/10/3 + USER_TIMEOUT 60 s) on Mac WS server; flip link to offline fast. H/S, no license issue. hangs, bt-offline.
2. Evaluate Classic RFCOMM via IOBluetooth as the offline transport instead of/in addition to BLE GATT (KDE's model, bonded devices, fixed service UUID, no Wi-Fi). H/L, ideas only (GPL). bt-offline (+bt-calls shares the OS bond).
3. Credit-based multiplexed framing over the single BT stream (type|len|channel|data, READ credits). M/M, ideas only. bt-offline.
4. Single serialized BT connect with hard timeout, skip non-capable devices, 30 s paced retry, drop duplicate inbound/outbound sockets. H/S. bt-offline, hangs.
5. Keep control frames from being starved by file transfers (separate payload connection or priority interleave). M-H/M. hangs.
6. Notification quality: per-app allow-list, silent-update flag, icon hash cache, group-summary + ongoing filter, only-when-screen-off. H/M. new.
7. SAS verification code + 30 s pairing timeout + timestamp in pairing (on top of QR pinning). M/S. new.
8. Handshake hardening: per-IP/deviceId rate limit, private-IP-only, size caps, constant-time token compare. M/S. hangs.
9. Capability negotiation: protocolVersion + incoming/outgoing capability lists so old phone/new Mac degrade per feature. M/M. new.
10. New plugins: runcommand (Mac-defined commands), presenter mode, pause-media-on-ring, lock-Mac-on-phone-away, per-stream volume, Finder/Share-extension "send to phone". M/S each. new (lock-on-away touches bt-offline).
Also: trusted-SSID allowlist (M/M), user-bindable global shortcuts (L/S), connectivity report chip (L/S).

## Things DroidDock already does better (do not regress)
QR-embedded cert pinning (out-of-band), virtual-display second screen + app windows, SMS threads/search, call control with WhatsApp name/answer, Quick Share, in-app Ed25519 updater, 16 languages, single-socket binary frames with H.264 mirror. KDE/GSConnect have no mirror, no per-app windows, no Quick Share, no call audio either (telephony is notification + mute only).
