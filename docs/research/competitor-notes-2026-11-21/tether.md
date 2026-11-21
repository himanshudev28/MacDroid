# Tether (subhashhhhhh/Tether, GPL-2.0, Swift, ~13k LOC) — findings
Clone: clones/tether. Ideas only. Notarized: NO (self-signed "Tether Self-Signed" cert, docs/signing.md; Homebrew cask `subhashhhhhh/tether/tether`; custom in-app updater, no Sparkle: Tether/Updates/UpdateInstaller.swift, BundleSignature.swift). macOS 15+, universal arch, 2.6 MB DMG, 23 MB idle, 0% CPU. Claims 5 tabs/device (Overview, Messages, Notifications, Files, Commands). NO Bluetooth anywhere (grep CoreBluetooth = 0 hits). Wi-Fi/Tailscale only. Roadmap lists Shortcuts/App Intents, widgets, Spotlight search as "planned" (not built).

## What is Mac-specific and how
- TLS: Network.framework NOT used for the link (KDE upgrades plaintext TCP to TLS in place); raw BSD socket + SecureTransport, with poll()-bounded handshake (10 s) and write (15 s) deadlines instead of sleep loops (Tether/Network/SocketTLS.swift, DeviceConnection.swift:110-111). Cert pinning by fingerprint post-handshake.
- Discovery: UDP broadcast + NWBrowser Bonjour (MDNSDiscovery.swift) + DNSServiceRegister + Tailscale CLI `tailscale status --json` unicast identity (TailscaleDiscovery.swift) + Connect-by-address.
- Notifications: UNUserNotificationCenter with UNTextInputNotificationAction reply, dismiss, Copy Code, mute categories (Plugins/NotificationPlugin.swift:78-135, 341); attachments = sender photo or app icon (NotificationImages.swift, docs/notification-images.md); dismiss on Mac syncs to phone; WhatsApp-for-Mac dedupe via NSRunningApplication (NativeNotificationFilter.swift).
- Calls: floating NSPanel card top-right, Silence button, morphs to on-call pill with timer (Services/CallCard.swift); pauses Mac media during calls and resumes only what it paused (CallMediaPauser.swift).
- Mac Now Playing read via vendored BSD MediaRemoteAdapter run through /usr/bin/perl (NowPlayingReader.swift, Resources/mediaremote-adapter.pl) because macOS 15.4 blocks MediaRemote for non-Apple processes.
- Phone as Finder drive: loopback NFSv3 server + local-only Bonjour hostname + `mount_nfs` soft mount, no sudo/kext; auto-unmount on disconnect/quit/crash cleanup (Files/NFS/PhoneMount.swift, PhoneNFSServer.swift). Also SFTP/ADB transports.
- Share extension "Share > Tether" with device chooser; handoff via private named pasteboard + DistributedNotification (not URL scheme, anti-abuse) (TetherShare/ShareViewController.swift, ShareHandoff.swift).
- Away lock: lock Mac 30 s after phone disconnect unless Mac's own network changed (NWPathMonitor guard) (Services/AwayLock.swift).
- Run Mac Shortcuts from phone via /usr/bin/shortcuts (Plugins/RunCommandPlugin.swift:28-80).
- Liquid Glass on macOS 26 via .glassEffect with material fallback (Views/Components/Glass.swift); MenuBarExtra popover (TetherApp.swift:66).

## Ideas for DroidDock
| # | Idea | Do what | Val | Eff | Session |
|---|------|---------|-----|-----|---------|
| 1 | Deadline-bounded handshake/IO | Audit DroidDock Rust/Swift connect paths so every handshake/write/BLE connect has an explicit deadline and poll()/timeout, never an unbounded wait; Tether's SocketTLS.swift is the pattern (10 s handshake, 15 s write, 50 ms re-drive). Relevant to BLE connect hang ~10 s and UI hangs. | H | S | hangs, bt-offline |
| 2 | Disconnect detection + grace logic | Tether detects phone loss in ~15 s and distinguishes "Mac changed network" via NWPathMonitor to avoid false disconnect actions. Apply NWPathMonitor-gated flap suppression before triggering BLE fallback / reconnect UI (AwayLock.swift:20-60). | M | S | bt-offline, hangs |
| 3 | Pause/resume Mac media on calls | When phone rings/in-call, pause Mac Now Playing and resume only if we paused it (2 s debounce between ringing->talking). Cheap polish, doubly relevant if Mac call audio lands. CallMediaPauser.swift. | H | S | bt-calls |
| 4 | Call card UX | Ringing card -> on-call pill with timer; DroidDock can add real Answer/Decline (Tether can't). Compare with DroidDock banners; add timer pill + contact photo. CallCard.swift. | M | S | bt-calls |
| 5 | Finder-mounted phone drive | Mount phone files as a Finder volume via loopback NFS/WebDAV + `mount_nfs -o soft` with local Bonjour name; DroidDock already has WebDAV core (webdav.rs) -> `mount_webdav` or NFS gives Finder-native drive without sudo. Soft mount + unmount on disconnect/crash cleanup avoids Finder beachballs (a "hangs" lesson). PhoneMount.swift. | H | M | new (hangs-adjacent) |
| 6 | App Intents / Shortcuts | Expose Ring phone, Send file, Send SMS, Battery, Mirror, Place call as App Intents (Tether only has it on roadmap, so first-mover). DroidDock has AppleScript sdef; App Intents gives Shortcuts/Siri/Spotlight/Action button. | H | M | new |
| 7 | Run Mac Shortcuts from phone | Phone lists user's Shortcuts (`/usr/bin/shortcuts list`) and runs them; opt-in toggle. RunCommandPlugin.swift. | M | S | new |
| 8 | Share extension with device chooser | "Share > DroidDock" appex; multi-device chooser; handoff via private NSPasteboard + DistributedNotification, not a URL scheme (security). Check if DroidDock has one; otherwise add. ShareHandoff.swift. | H | M | new |
| 9 | Notification polish | Dedupe against native Mac apps (WhatsApp/Telegram running -> suppress mirrored banner, click opens native app); sender photo as UNNotificationAttachment; dismiss sync. DroidDock has banners/reply; add native-app dedupe + Notification Center fallback (persisted history). NativeNotificationFilter.swift. | M | S | new |
| 10 | Mac Now Playing -> phone | Tether reads Mac Now Playing on macOS 15.4+ via MediaRemoteAdapter (BSD, vendorable w/ credit) run through /usr/bin/perl. DroidDock has media remote; verify it still works on 15.4+/26, else adopt this trick. NowPlayingReader.swift. | M | M | new |
| 11 | Battery alerts + widgets | Battery 15%/charged notifications; WidgetKit widget for battery/Now Playing (Tether roadmap only). Needs appex + app group; SwiftPM-only build (no Xcode) makes widgets costly. | L-M | L | new |
| 12 | Distribution | They ship Homebrew cask (tap) + 2.6 MB DMG + self-signed. DroidDock should add a Homebrew cask (low effort, avoids "can't be opened"), keep stable self-signed cert so Accessibility grant survives updates (signing.md notes this). | M | S | new |
| 13 | Tailscale discovery | Uses local `tailscale status --json` and unicasts identity to the user's own phones only (filters to own account, ignores shared devices). DroidDock "mostly works" over Tailscale: adopt auto-discovery of own-tailnet phones as the discovery gap-closer. TailscaleDiscovery.swift. | M | S | new |
| 14 | OTP detection heuristics | Keyword-gated regexes skipping amounts/dates/years/phones; auto-copy off by default (clipboard loss). Compare to DroidDock OTP copy; tests in TetherTests/OneTimeCodeTests.swift. | L | S | new |
| 15 | Away lock | Opt-in "lock Mac when phone leaves" w/ 30 s grace; BLE presence (RSSI) could make DroidDock's better, plus proximity unlock is out of scope. | M | S | bt-offline |

## Where Tether is weaker (don't copy)
No Bluetooth, no call answer/decline, no call audio, no mirroring/camera, SMS search "planned", no notarization, requires stock KDE Connect limits. Nothing to learn on bt-calls/camera beyond items 3-4.

## Top 5
1. Hard deadlines on every connect/handshake/write (SocketTLS.swift) - hangs/bt-offline.
2. NWPathMonitor-gated disconnect grace - bt-offline/hangs.
3. App Intents/Shortcuts (unbuilt even by Tether) + Share extension with chooser - new.
4. Finder-mounted phone drive via soft loopback NFS - new/hangs.
5. Pause/resume media on calls + call card timer pill - bt-calls; Homebrew cask distribution - new.
