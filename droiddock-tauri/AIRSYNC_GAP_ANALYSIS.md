# AirSync → DroidDock gap analysis

> **Status (2026-08-04): Tiers A–D built.** Every headline feature on
> <https://sameerasw.com/airsync> now has a DroidDock equivalent. What remains
> unbuilt is listed in §5 at the bottom, with the reason for each. Re-verified
> against airsync-mac @ `81c13b3` (2026-08-02) and the live landing page.

> **§2 and §3 are the pre-build snapshot and were never revised.** Their
> "DroidDock" columns describe the app as it stood *before* Tiers A–D, so most
> of the "none" entries there are now wrong (U1–U18, F1, F4, F8, F10 are all
> built). **§5 and §6 are the current state** — read those.

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


---

## 5. Status after Tiers A–D

### Website headline features — all covered
| AirSync claims | DroidDock |
|---|---|
| Android Mirror | ✅ Wi-Fi mirroring (no ADB needed — AirSync can't do this) **and** ADB/scrcpy |
| Notification Sync, "grouped & stacked" | ✅ grouped by app, expand/collapse, toggle back to a flat list |
| Reply to Notifications | ✅ (pre-existing) + per-app banner muting |
| Clipboard Sync | ✅ (pre-existing) + explicit "send now" |
| Media Control | ✅ full tab + mini-player on the phone card |
| Send Files | ✅ (pre-existing) + quick action |
| Desktop Mode | ✅ scrcpy `--new-display`, plus "open one app in its own Mac window" |
| Wallpaper View | ✅ wallpaper + album art on the phone card |
| AES End-to-End Encryption | ⚠️ **partial and labelled as such** — JSON control messages only; binary frames (files, thumbnails, mirror video) stay in the clear. See `crypto.rs`. |
| Zero cloud / open source | ✅ already true |

### Built beyond the original Tier A scope
Apps grid + icon sync · recent apps · live clock · connection pill with real
RTT · mDNS/Bonjour discovery · link-quality grading (`good`/`fair`/`weak`/
`stalled`) · menu-bar panel · onboarding · categorised settings · window
opacity · Mac remote control from the phone (opt-in) · per-app notification
muting.

### Menu bar / battery / widget (built 2026-08-04)
| AirSync+ item | DroidDock |
|---|---|
| MenuBar Customizations | ✅ text mode, battery style, album-art layout — **except font size**, which Tauri gives no API for (max-length cap offered instead) |
| Low Battery Alerts ("Soon" in AirSync) | ✅ shipped, with threshold + once-per-discharge logic |
| Widgets ("Soon" in AirSync) | ✅ as a floating always-on-top panel. **Not** a WidgetKit widget — that needs a Swift extension Tauri can't ship |

### Deliberately NOT built, with reasons
| Item | Why not |
|---|---|
| **Quick Share / Nearby Share** (F7) | ~12k lines of reverse-engineered protobuf + UKEY2. Enormous, and DroidDock's own file transfer already works both ways. |
| **Licensing / trial / Gumroad** (F15) | Personal app. Nothing to sell. |
| **BLE transport** (F1, partial) | Real fallback value, but a whole second transport stack. mDNS closed most of the "can't find the Mac" gap for far less. |
| **Native Metal scrcpy decode** (F9) | DroidDock already decodes its own Wi-Fi mirror stream via WebCodecs; this would only improve the ADB path, which already works by spawning scrcpy. |
| **Apple Intelligence summaries** | Needs a macOS 15.1+ framework dependency for a cosmetic feature. |
| **AppleScript surface, Sparkle auto-update, i18n** | Real but unrelated to the AirSync feature parity this was scoped to; each is its own project. |
| **Full binary-frame encryption** | Would mean surgery on the highest-throughput, least-verified code (transfer + mirror hot loops). Flagged rather than half-done. |

### Still open, worth doing later
- ~~Notification **action buttons** and **progress bars** (U12)~~ — **already
  built**, in the "AirSync v4 changelog match" pass. This row was stale: the
  phone does send `actions[]`/`progress`/`priority`, `notif-action` fires one
  back by index, and `NotificationsView` renders both. Corrected 2026-08-05.
- ~~**Mac → phone info sync** and **Mac media control from the phone**
  (F5/F6)~~ — **built 2026-08-05.** `mac_info.rs` pushes `{name, battery,
  charging, hasBattery}` on connect and every 60s (caps-gated `"macinfo"`,
  Settings-toggleable, on by default); the phone's Home screen renders it.
  Media control rides the existing `remote` message as a `media` action with
  its own closed allow-list (`playpause`/`next`/`prev`/`volup`/`voldown`/
  `mute`), posted as real `NX_KEYTYPE_*` HID keys — so it drives whatever app
  owns the Mac's now-playing session, with no `media-control` CLI dependency.
  **Scope: control only.** Reading the Mac's now-playing *metadata* back to the
  phone needs the private MediaRemote framework and was not attempted.
- ~~**Drag & drop** files onto the window (U22)~~ — **built 2026-08-05.** A
  window-wide drop target that stands down while the Files view is on screen,
  since `FilesView` owns its own (more specific) drop handling.
- **In-card / sidebar mirroring** (U19) — mirroring inside the phone card
  instead of a pop-out window. Still open; see §6.
- ~~The five pre-existing CRITICAL/MAJOR bugs~~ — **all fixed** (2026-08-04),
  along with the pre-existing reqId-misroute hole. The `adb.rs` work fixed 5 of
  7 items; the tracker-child-on-quit and screenshot-filename items are still
  open and documented as deliberate (both self-limiting/cosmetic).

---

## 6. Still not built, as of 2026-08-05

Kept as an explicit ledger so nothing sits in the gap between "done" and
"deliberately skipped" without a reason attached.

| Item | Status | Why |
|---|---|---|
| **U19** in-card mirroring | open, worth doing | Needs the main window to attach a mirror `Channel` the way the pop-out does. Real work in a path that currently works, so it wants its own session rather than a drive-by. |
| **U20** floating navbar / side control bar | open, low value | Separate non-focusable windows over the mirror. Our mirror already carries the same controls in-window; this is AirSync's layout preference, not a missing capability. |
| **U16** What's New tour | open, low value | Popovers pointing at newly-added UI. Meaningful for a shipping product with a userbase; this is a personal app whose changelog is `checkpoint.md`. |
| **U18** "pretend older OS" style toggle, app-icon variants | open, cosmetic | Icon variants need designed assets. The rest of U18 (window opacity, accent) is built. |
| **U7** haptic feedback on the volume/seek scrubbers | open, marginal | `NSHapticFeedbackManager` only does anything on a Force Touch trackpad, and never for a mouse or external keyboard. |
| **Phase 20 reverse control** — streaming the *Mac's screen* to the phone | not started | Feasible, but it is a feature-sized project: screen capture + encode on the Mac, a decoder and a new tab on the phone. The *input* half already exists (`mac_remote.rs`); only the video half is missing. Needs a PRD section first, per this project's own standing rule. |
| **F12** WebDAV | deliberate | Wi-Fi `fs-*` already covers the same ground. |
| **Full binary-frame encryption** | deliberate | Surgery on the least-verified hot loops. Flagged rather than half-done. |
| F7 Quick Share · F1 BLE · F9 Metal decode · F13 AppleScript · U25 i18n · U26 Sparkle · Apple Intelligence | deliberate | Unchanged from §5 above. |
