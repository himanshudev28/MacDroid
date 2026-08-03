# AirSync → DroidDock gap analysis

**Reference:** [sameerasw/airsync-mac](https://github.com/sameerasw/airsync-mac) @ main
(~46k lines Swift/SwiftUI, 1959 commits) + <https://sameerasw.com/airsync>.
**Subject:** `droiddock-tauri/app` (~11k lines Rust + React/TS) + `droiddock-android`.

Read at source level, not from the README/landing page — both undersell what
the app actually does.

---

## 1. The architectural difference that drives everything else

AirSync and DroidDock made opposite structural choices, and almost every UI
difference below is downstream of this one:

| | AirSync | DroidDock |
|---|---|---|
| Shell | `NavigationSplitView` — sidebar is **the phone**, detail is **3 tabs** | Sidebar is a **13-item nav list**, detail is the current view |
| Sidebar content | 220×460 phone-shaped glass card: wallpaper, clock, battery, media, recent apps, quick actions | Nav links + a 2-line status footer |
| Detail tabs | Notifications · Apps · Settings (+ QR when unpaired) | Dashboard, Files, Photos, Messages, Contacts, Calls, Notifications, Clipboard, Media, Mirror, Camera, Devices, Settings |

AirSync treats the phone as a **persistent ambient object** you glance at, with
a small number of things you *do*. DroidDock treats the phone as **a filesystem
with thirteen drawers**. DroidDock actually has *more* features surfaced
(Messages, Contacts, Photos, Calls have no AirSync equivalent) — but they're
flattened into a nav list with no sense of a live device present.

**That phone card is the single highest-impact thing to copy.** It's what every
screenshot on the landing page is selling.

---

## 2. UI / interaction gaps

| # | AirSync has | DroidDock | Where in AirSync |
|---|---|---|---|
| U1 | **Phone card** — wallpaper bg, 3D-ish glass, rounded 24, live content | `LinkPulse` + text | `PhoneView.swift`, `ScreenView.swift` |
| U2 | **Wallpaper sync** — phone wallpaper pushed on handshake, cached per device, faded in | none | `AppState.deviceWallpapers`, `+Handlers.swift:123` |
| U3 | **Album art** replaces wallpaper while music plays | no album art anywhere (not in wire protocol) | `PhoneView.updateImage()` |
| U4 | **Live phone clock** — 85pt SF Rounded, glass-carved glyphs, animates between stacked/row layout | none | `TimeView.swift` |
| U5 | **Connection status pill** — wifi/tailscale, ADB state + wired/wireless mode, QuickShare, BLE; popover w/ IP, ADB serial picker | static "Linked over Wi-Fi" text | `ConnectionStatusPill.swift` |
| U6 | **Quick actions row** — Send file (⌘F), Browse (⌘B), Mute notifs, Send clipboard | scattered across tabs | `ScreenView.swift:40-117` |
| U7 | **Battery + volume in one strip**, volume popover with **haptic feedback** per 5% tick | battery on Dashboard only, volume in Media tab | `DeviceStatusView.swift` |
| U8 | **Media mini-player in sidebar** — title/artist marquee, seekbar w/ haptics, prev/play/next, **like/unlike**, auto-collapse when nothing plays | separate Media tab, no like, no auto-collapse | `MediaPlayerView.swift` |
| U9 | **Recent apps row** — 5 icons, click launches that app into scrcpy | none | `RecentAppsGridView.swift` |
| U10 | **Apps grid** — every Android app, icons, search w/ prefix-ranking, launch, pin | none | `AppGridView.swift` |
| U11 | **Notification stacking** — grouped by app, toggle stacked/expanded from toolbar | flat chronological list | `NotificationView.swift`, `notificationStacks` |
| U12 | **Notification app icons, action buttons, progress bars, ongoing flag** | title/body/reply only | `Model/Notification.swift` |
| U13 | **Per-app notification settings** — allow/deny per package | global on/off | `AppNotificationSettingsView.swift` |
| U14 | **Rich menu-bar panel** — 5 stacked glass segments (top/call/discovery/media/notifications), staggered entrance | tray menu: status + pause + quit | `MenubarView.swift`, `MenubarSegments.swift` |
| U15 | **Onboarding flow** — Welcome → Install Android → Mirroring setup → Plus features | none | `Screens/OnboardingView/` |
| U16 | **What's New tour** — popovers pointing at newly-added UI | none | `WhatsNewTourManager.swift` |
| U17 | **Settings as categorized sidebar** — 9 sections (My Mac, Sync, Notifications, Mirroring, QuickShare, Menubar, Apple Intelligence, Appearance, Plus) | one flat scrolling view | `SettingsSidebarView.swift`, `SettingsTab.swift` |
| U18 | **Appearance settings** — window opacity slider, "pretend older OS" style toggle, app icon variants | none | `AppearanceSettingsView.swift`, `RuntimeUI.swift` |
| U19 | **Sidebar mirroring** — the phone card *becomes* the live mirror in place | pop-out window only | `SidebarMirrorView.swift` |
| U20 | **Floating navbar + side control bar** as separate non-focusable windows over the mirror | controls inside the mirror window | `FloatingNavbar.swift`, `SideControlBar.swift` |
| U21 | **Weak-connection overlay** — slides up, "Looking for <device>…" + Disconnect | none | `HomeView.ConnectionWeakOverlay` |
| U22 | **Drag & drop** files onto the window to send | file picker only | `DropTargetModifier.swift` |
| U23 | **Keyboard shortcuts throughout** — ⌘P mirror, ⌘⇧P alt-mirror, ⌘⇧S sidebar mirror, ⌘D desktop, ⌘1/⌘2 tabs, ⌃space play/pause, ⌃←/→ | almost none | everywhere |
| U24 | **Liquid Glass done natively** — `glassEffect` on macOS 26 w/ `.thinMaterial` fallback, `GlassEffectContainer`, `matchedGeometryEffect` morphs | CSS `.glass-chrome` + native vibrancy on sidebar only | `GlassBoxView.swift` |
| U25 | i18n via Crowdin (`L("key")` everywhere) | English hardcoded | `Localizer.swift` |
| U26 | Sparkle auto-update + crash manager | none | `Screens/Updater/` |

---

## 3. Feature / protocol gaps

| # | AirSync has | DroidDock | Notes |
|---|---|---|---|
| F1 | **mDNS/Bonjour** advertise + browse, **UDP broadcast**, **BLE transport**, all unified | UDP broadcast only (`discovery.rs`, 43 lines) | `Core/Discovery/`, `Core/BLE/` |
| F2 | **QuickConnect** — auto-reconnect to known device, BLE→Wi-Fi handoff ("Switch" button) | manual re-pair | `QuickConnectManager.swift` |
| F3 | **AES E2E encryption** of the link | plaintext WS + shared token | `CryptoUtil.swift`, Keychain-stored |
| F4 | **Mac remote control from phone** — keyboard, mouse move/click/scroll, media keys, volume, brightness, lock screen, screensaver | **parked as "Phase 20"** | `MacRemoteManager.swift`, `+Handlers.swift:688-740` |
| F5 | **Mac→phone info sync** — Mac battery/status shown on the phone | none | `MacInfoSyncManager.swift` |
| F6 | **Mac media control from phone** (`media-control` CLI) | none | `NowPlayingCLI.swift`, `NowPlayingPublisher.swift` |
| F7 | **Google Quick Share / Nearby Share** — full protobuf + UKEY2 + secure-message impl | none | `Core/QuickShare/` (~12k lines incl. generated pb) |
| F8 | **Desktop mode** — scrcpy `--new-display`, "wireless DeX" | none | `ADBConnector`, `NativeDesktopMirrorView` |
| F9 | **Native scrcpy stream** — own server push, H.264 decode, **Metal** render | spawns the scrcpy binary as a detached window | `Core/Remote/Scrcpy/` |
| F10 | **App icons sync** from phone, cached to disk | none | `handleAppIcons`, `ImageEncoder.swift` |
| F11 | **Call control** — end/mute/speaker/DTMF over the link *and* ADB | ADB-only (`adb.rs`); Wi-Fi is view-only | checkpoint Phase 9 note |
| F12 | **WebDAV** file access | Wi-Fi `fs-*` only; ADB `fsTransport()` deliberately not ported | `WebDAVManager.swift` |
| F13 | **AppleScript automation** surface | none | `AppleScriptSupport.swift` (1072 lines) |
| F14 | **Ping/keepalive + connection-quality metric** | ping/pong types exist, unused for quality | `WebSocketServer+Ping.swift` |
| F15 | Licensing/trial/Gumroad | n/a — personal app, **don't copy** | — |

### What DroidDock has that AirSync does *not*
Worth protecting — these are real differentiators and shouldn't be lost in a
restructure: **SMS threads + send**, **Contacts**, **Photos browser with
thumbnails**, **Photo auto-sync w/ ledger**, **open-in-place edit writeback**,
**reverse Mac-file browsing from the phone**, **Wi-Fi screen mirror without
ADB** (AirSync's mirroring is ADB/scrcpy-only), **camera feed**.

---

## 4. Honest precondition

`checkpoint.md` records that **Phases 3–19 have never run on real hardware**,
and lists **3 CRITICAL and 2 MAJOR open findings** (Phase 19 `mac-fs-list`
empty-path errors immediately; Phase 17 writeback never overwrites, it creates
`name (2).ext`; Phase 19 `mac-fs-pull` drops leading chunks; Phase 18 ledger
collides on same-model phones; Phase 19 reverse browsing is on-by-default with
no toggle).

Building a large new feature surface on top of that is a real risk: new bugs
become indistinguishable from old ones. Recommended order is **fix the 5 open
findings → then UI shell → then new features**, but that's a call for the
project owner, not a blocker.
