<div align="center">

# 🔗 DroidDock

### Your Android phone and your Mac, finally in sync.

Clipboard · notifications · files · photos · messages · calls · screen mirror · camera —
flowing seamlessly between your phone and your Mac over your local Wi‑Fi.

**A truly native Mac app** — SwiftUI on a Rust core, built to Apple's Human Interface
Guidelines, in a small `.dmg` with no bundled browser. *(Earlier releases shipped an
Electron and then a Tauri client; both are kept in the repo for reference.)*

![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Android-1f6feb?style=for-the-badge)
![Mac app](https://img.shields.io/badge/Mac-SwiftUI%20%2B%20Rust-F05138?style=for-the-badge&logo=swift&logoColor=white)
![Android app](https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Link](https://img.shields.io/badge/link-LAN%20WebSocket-FFB454?style=for-the-badge)

[![Website](https://img.shields.io/badge/%F0%9F%8C%90%20Website-Screenshots%20%26%20demo-F5A623?style=for-the-badge)](https://himanshudev28.github.io/DroidDockWebsite/)

**[See it in action → himanshudev28.github.io/DroidDockWebsite/](https://himanshudev28.github.io/DroidDockWebsite/)**

</div>

---

## ✨ What is this?

**DroidDock** is a personal **Android ↔ Mac bridge**. Pair your phone once with a QR code
and the two stay quietly connected on your network — so the things you do on one device
just show up on the other. Copy text on your phone and paste it on your Mac. Reply to a
text from your keyboard. Drag a file onto the Mac window and it lands on your phone.
Browse your gallery, mirror your screen, take a call — all from your desk.

It's two apps that talk to each other:

| | App | Built with |
|---|---|---|
| 🖥️ | **Mac client** — [`droiddock-native/`](droiddock-native/) | SwiftUI · Rust core (C ABI) |
| 📱 | **Android companion** — [`droiddock-android/`](droiddock-android/) | Kotlin · Jetpack Compose |

---

## 📸 Screenshots

<div align="center">

### 🖥️ On the Mac

<table>
<tr>
<td width="50%" align="center">
  <img src="docs/shots/mac-dashboard.webp" alt="DroidDock Dashboard on macOS — the paired phone in the side rail, with battery, Android version and shortcuts to Files, Messages and Mirror" width="100%" />
  <br><strong>Dashboard</strong><br><sub>The phone lives in the rail — battery, Android version, one tap to anything</sub>
</td>
<td width="50%" align="center">
  <img src="docs/shots/mac-notifications.webp" alt="Phone notifications mirrored on the Mac, grouped by app, with a Show on Mac toggle" width="100%" />
  <br><strong>Notifications</strong><br><sub>Grouped by app, dismissible, with inline reply — or off with one toggle</sub>
</td>
</tr>
<tr>
<td width="50%" align="center">
  <img src="docs/shots/mac-mirror.webp" alt="The phone screen mirrored in its own resizable macOS window over Wi-Fi" width="100%" />
  <br><strong>Screen mirror</strong><br><sub>Its own window — move it, resize it, pin it on top</sub>
</td>
<td width="50%" align="center">
  <img src="docs/shots/mac-apps.webp" alt="A searchable grid of every app installed on the phone, launchable from the Mac" width="100%" />
  <br><strong>Apps</strong><br><sub>Every phone app, searchable, launched from your keyboard</sub>
</td>
</tr>
</table>

### 📱 On the phone

<table>
<tr>
<td width="33%" align="center">
  <img src="docs/shots/phone-home.webp" alt="The DroidDock Android home screen showing the connected Mac, its battery and Mac media controls" width="100%" />
  <br><strong>Home</strong><br><sub>Which Mac, how full its battery is, what it's playing</sub>
</td>
<td width="33%" align="center">
  <img src="docs/shots/phone-control.webp" alt="The Mac Remote tab on Android — a trackpad, arrow keys and a field for typing on the Mac" width="100%" />
  <br><strong>Mac Remote</strong><br><sub>Trackpad, arrow keys, and a keyboard for the Mac</sub>
</td>
<td width="33%" align="center">
  <img src="docs/shots/phone-clipboard.webp" alt="The Android clipboard tab, with a history toggle and a box for sending text to the Mac" width="100%" />
  <br><strong>Clipboard</strong><br><sub>Copy on either device — history optional, cleared on close</sub>
</td>
</tr>
</table>

<sub>More at <a href="https://himanshudev28.github.io/DroidDockWebsite/">himanshudev28.github.io/DroidDockWebsite</a></sub>

</div>

---

## 🚀 Features

| | Feature | What it does |
|:--:|---|---|
| 📋 | **Clipboard sync** | Mac → phone automatically; phone → Mac automatically or manually — **Auto / Manual** toggle. Works on Android 13+ / Samsung (uses accessibility events, not clipboard reads). **Images** cross too: Mac → phone automatically, phone → Mac from the tile, widget or share sheet. |
| 🔔 | **Notification mirroring** | Phone notifications on your Mac as floating banners with **inline reply** and dismiss — kept in macOS **Notification Center** too if you like, with an adjustable on-screen time (5 s by default). Incoming calls shown with caller ID. |
| 📁 | **File transfer & browser** | Drag-and-drop both ways with live progress; browse, download, upload, **rename**, **delete** and **search** phone storage. Android Files tab shows an **upload queue** with real-time progress bar, transfer speed, and a **recent transfers** history with colored file-type badges. |
| 🖼️ | **Photos & Videos** | Browse thumbnails, open full-res in Preview / QuickTime, download originals. |
| 💬 | **Messages** | Polished 2-pane SMS chat — conversation list with avatar initials, search, day dividers, composer. Threads sync live. |
| 👤 | **Contacts** | Browse and search your phone's contacts. |
| 📞 | **Phone calls** | Place calls from the Mac, and **answer, decline, hang up, mute and switch to speaker** from it — over Wi-Fi, no ADB. Incoming-call alerts with caller ID. (Keypad tones stay ADB-only: Android only lets the default dialer send them.) |
| 🩺 | **Setup check** | One panel listing every permission both devices need, what breaks without each, and a Fix button that opens the exact settings screen — on either device. |
| 🔊 | **Ring my phone** | Find a phone down the back of the sofa. Plays on the alarm stream, so silent mode doesn't mute it; stops from the Mac, from its own notification, or after a minute. |
| 📸 | **Mirror stills & recordings** | Save a PNG of the phone screen, or record it to MP4, straight from the mirror window. |
| 🎵 | **Media remote** | Now-Playing card with transport + volume control. |
| 🪞 | **Screen mirroring** | Mirror **and control** your phone over Wi-Fi (MediaProjection + H.264) — tap, swipe, scroll, type and use the nav bar from your Mac. Pops out into a phone-shaped, always-on-top window. **No ADB, no scrcpy, no Developer Options.** |
| 📷 | **Phone camera** | Use your phone's back or front camera as a Mac webcam-style feed — live, switchable, no ADB. |
| 📲 | **Apps grid** | Your phone's app drawer on the Mac, with real icons, prefix-ranked search and pinning. Click an app to launch it **on the phone** or **in its own Mac window** — a switch in the grid's header (and Settings → Mirroring) picks which, and holding **⌥ Option** always does the other one. |
| 🤖 | **Auto Mirror mode** | Grant "Display over other apps" once — after that the Mac can start screen/camera instantly with no per-session tap on the phone. |
| 🔌 | **Smart pairing** | **Custom QR scan screen** (glowing amber corner brackets, animated status pill) or manual IP entry; auto-reconnect; "Forget this Mac"; **Pause** mode (1h / 8h / until resume). |
| 🖥️ | **Your Mac, on your phone** | The phone's Home screen shows the Mac's **name, battery and charging state**, plus what it's playing — with working transport keys, **volume**, **brightness**, **screensaver** and **lock**. Off unless you enable remote control. |
| 📶 | **Bluetooth fallback** | When Wi-Fi drops, the link moves to Bluetooth LE so notifications, calls and messages keep arriving, and moves back when Wi-Fi returns. Choose *Wi-Fi only*, *automatic fallback*, or *Use Bluetooth now*. |
| 🖥️ | **Second screen** | Mirror a separate, phone-shaped Android screen with its own home screen and apps in a Mac window — your phone's own screen is never touched. |
| 🪟 | **Notifications open as Mac windows** | Click a notification and its app opens in its own Mac window on a virtual display, with a **Back · Home · Recent** bar underneath. Closing the window closes the app; it never jumps onto the phone. |
| 📞 | **WhatsApp & internet calls** | WhatsApp (and other app) calls show the caller's name in the Mac pop-up; **Decline** and **Hang up** work from the Mac, and **Answer** taps the real button on the phone. |
| 🗂️ | **Transfers page** | Every file moving either way, with live speed — drop files on **Send to Phone**, send to a nearby Android with **Quick Share**, and **Clear History** when you're done. |
| 🌐 | **Sixteen languages** | English · العربية · বাংলা · Deutsch · Español · Français · हिन्दी · Bahasa Indonesia · Italiano · 日本語 · 한국어 · Português · Русский · Türkçe · Tiếng Việt · 简体中文 · 繁體中文. Follows your system language or pick one in Settings; the layout mirrors right-to-left for Arabic. |
| 🌗 | **Light & dark themes** | Both apps follow the system theme or pin one, with a warm palette and adjustable glass on the Mac. The Android light palette is contrast-measured, not eyeballed. |
| ⬆️ | **Update alerts** | The Mac app checks GitHub once a day and opens the new release for you; the Android APK is signed with a stable release key so it updates in place. |
| 📱 | **Device management** | Remembered Macs, Quick Connect, Disconnect, and switching between Macs — discovered over mDNS. |
| 📜 | **Clipboard history** | This session's clips, both directions. Memory-only and capped on purpose — persisting it would be a plaintext log of passwords and OTPs. |
| ⚡ | **Quick Settings tiles** | Toggle the connection and the accessibility service straight from the Android shade. |

---

## 🎨 Design

### Mac app — native SwiftUI, Apple HIG

The Mac client is written in **SwiftUI** and follows the **Apple Human Interface Guidelines**:

- System materials, colours and SF Symbols, so it matches macOS in light and dark mode
- Your choice of layout: a dashboard with the phone card, or a classic sidebar
- A menu bar panel, floating banners and a call pop-up that never steal focus
- Respects Reduce Motion, Dynamic Type sizes and VoiceOver labels
- Light on resources: images are downsampled and freed when the window closes

### Android app — Material Design 3

The Android companion follows **Material Design 3** throughout:

- Same graphite palette aligned with the Mac — `#0D0D12` ink, `#F5A623` amber, `#34C759` green
- All-vector icons from **Material Icons Extended** — zero emoji anywhere in the UI
- Animated connection hero card with `AnimatedContent` transitions and ambient gradient glow
- MD3 `Switch`, `Card`, `Button`, `AlertDialog`, `FilledTonalButton`, `Surface` — native look and feel
- Collapsible feature guide with `AnimatedVisibility` step lists
- Amber scan-to-connect screen with glowing corner brackets and pulsing green dot

---

## 🧠 How it works

The apps speak over a **token-gated WebSocket on your LAN** — request/response messages
keyed by `reqId`, plus a compact binary frame protocol for file, thumbnail and video
transfers. Screen capture uses **MediaProjection + MediaCodec H.264** on the phone;
the Mac decodes it in hardware with **VideoToolbox**. Touch/input is injected back
through the **AccessibilityService**. When Wi-Fi is gone, the same messages travel over a
**Bluetooth LE** link instead.

```mermaid
flowchart LR
    subgraph MAC["🖥️  Mac app · SwiftUI + Rust core"]
        UI["SwiftUI app\n(Files · Photos · Messages · Mirror …)"]
        SRV["Rust core\nWebSocket server :48484\n+ Bluetooth LE central"]
        ADB["ADB (fallback)"]
    end
    subgraph PHONE["📱  Android app · Kotlin + Compose"]
        BS["BridgeService\n(foreground)"]
        CM["ConnectionManager\nWebSocket client"]
        MP["MirrorService\nMediaProjection + H.264"]
        A11Y["AccessibilityService\nClipboard + gestures"]
    end
    UI --- SRV
    CM <==>|"token-gated LAN WebSocket\nJSON + binary frames"| SRV
    CM -.->|"Bluetooth LE fallback"| SRV
    ADB -.->|"optional USB / Wi-Fi ADB"| PHONE
    BS --- CM --- MP
    A11Y --- CM
```

Pair once — they auto-reconnect whenever both apps are open on the same network.

---

## ⬇️ Install (the easy way — no build tools)

Grab the prebuilt apps from the [**Releases**](../../releases) page:

1. **Mac** — download `DroidDock-<version>.dmg` (Apple Silicon, macOS 14 or later), open
   it and drag **DroidDock.app** to Applications. Then **right-click the app → Open** →
   **Open** for the first launch only.

   > The app is ad-hoc signed, not notarized (that needs a paid Apple Developer
   > account), so macOS won't run it straight from a double-click. If right-click →
   > Open doesn't offer you an **Open** button, clear the download flag instead:
   >
   > ```
   > xattr -dr com.apple.quarantine /Applications/DroidDock.app
   > ```
2. **Android** — download `DroidDock-Android.apk` and sideload it (allow "install unknown apps").
3. Open the Mac app → **Pair Device** → scan the QR with the phone app. Done.

> ⬆️ **Coming from the Tauri app?** Its built-in updater can't install the native app,
> so download the new `.dmg` once by hand. From then on the native app tells you when a
> new version is out. The Android APK keeps updating itself, as it has since v2.0.0.
>
> ⚠️ **Upgrading from v1.0.0 or earlier needs one manual uninstall + reinstall of the
> Android app.** Releases up to v1.0.0 shipped a *debug-signed* APK whose key changed
> every build, and Android refuses to replace an APK with one signed by a different
> certificate. This is a one-time break.

> ✅ **No ADB, no scrcpy, no Developer Options needed** — including for **screen
> mirroring and phone camera**. Everything runs over the Wi-Fi app link. `adb` is
> still auto-downloaded the first time it's needed as an optional power-user tool.

### 📺 Screen mirroring & phone camera

Tap **Screen** or **Camera** in the Mac's Mirror tab.

- **Normal mode** — a notification appears on the phone; tap it to approve the
  one-time "Allow screen capture" prompt. The phone screen pops out into a
  phone-shaped always-on-top Mac window. Tap, swipe, scroll, type and use the
  nav bar from your Mac keyboard and mouse.
- **Auto mode** (recommended) — grant "Display over other apps" once in the Android
  app's settings. After that the capture dialog pops up directly with no notification
  tap, and camera starts instantly. Stopping from the Mac clears the phone's cast
  indicator completely.

### 📲 Opening phone apps

The **Apps** tab lists every launchable app on the phone. Click one and it opens
in one of two places:

- **On the phone** (the default) — the app launches on the handset, ready to be
  watched from the Mirror tab.
- **On this Mac** — the app opens in its own Mac window on a virtual Android
  display, and the phone's own screen is left untouched. Needs ADB and scrcpy;
  with no ADB device connected the click falls back to launching on the phone
  rather than doing nothing.

Pick the default with the **Phone / Mac** switch in the Apps header, or
**Settings → Mirroring → Open apps on this Mac** — they are the same setting.
Holding **⌥ Option** while clicking always does whichever one is *not* the
default, so both are always one modifier away.

How that Mac window looks is governed by the rest of **Settings → Mirroring**:
*Window layout* decides whether Android serves a desktop or a magnified-phone
layout, *Show Android bars* keeps or drops the launcher/status/nav bars, and
*Keep apps running when the window closes* hands the app back to the phone
instead of killing it.

### 🔔 Mac notifications

Phone notifications appear as native macOS alerts. To enable:

1. Open DroidDock on both devices and connect.
2. Grant **Notification Access** in the Android app.
3. On macOS, go to **System Settings → Notifications → DroidDock** and enable Allow Notifications.

---

## 🧑‍💻 Build from source

Needs the Xcode 16 command-line tools (Swift 6) and [Rust](https://rustup.rs) stable with the
`aarch64-apple-darwin` target for the Mac app, and JDK 17 for Android. No Xcode project and
no Node.

```bash
# Mac app  (Rust core + SwiftUI)
rustup target add aarch64-apple-darwin
droiddock-native/scripts/build.sh   # → droiddock-native/build/DroidDock.app (ad-hoc signed)
droiddock-native/scripts/dmg.sh     # → droiddock-native/build/DroidDock-<version>.dmg
droiddock-native/scripts/run.sh     # development run with a throwaway data folder

# Android app
cd droiddock-android
./gradlew installDebug   # or open in Android Studio → Run
```

> 💡 The first build compiles the whole Rust dependency tree and takes a few
> minutes; later builds are incremental. If the app starts but no phone can
> connect, check nothing else is holding port `48484` — a second copy of
> DroidDock (or the old Tauri app) will do it.

Releases are built automatically by
[`.github/workflows/release.yml`](.github/workflows/release.yml) — push a tag
(`git tag v2.5.0 && git push origin v2.5.0`) and the `.dmg`, a zipped `.app` and the
`.apk` are built and attached to a GitHub Release, with notes taken from that tag's
section in `CHANGELOG.md`. The version is stamped from the tag, so there is nothing to
bump by hand.

### First-run Android permissions

Grant these when the app asks (they're all needed for the full feature set):

| Permission | Feature |
|---|---|
| Notification access | Mirrors phone notifications to Mac |
| SMS · Contacts · Calls | Messages, contacts, call alerts |
| All-files access | File browser and transfer |
| Accessibility service ("Clipboard & Screen Control") | Auto clipboard phone → Mac **and every Mac-side tap, swipe and nav button** |
| Display over other apps | Auto Mirror mode (no per-session prompt) |
| Battery — Unrestricted | Keeps the link alive when screen is off |

> ⚠️ **Android 13+ blocks the accessibility toggle for sideloaded apps.** If
> "Clipboard & Screen Control" won't turn on (or switches itself back off), go to
> **Settings → Apps → DroidDock → ⋮ → Allow restricted settings** first, then enable
> it under **Settings → Accessibility → Installed apps**.
>
> **Reinstalling the APK turns it off again** — so re-enable it after every update.
> With it off, the mirror still streams video but every tap, swipe and nav press
> from the Mac is silently discarded; the Mac now shows a toast telling you so.

---

## 🔐 Pairing

1. Put the Mac and phone on the **same Wi-Fi network**.
2. On the **Mac**, open **Pair Device** — it shows a QR code and the manual IP/token.
3. On the **phone**, tap **Pair with Mac** — the custom scan screen opens:
   - Point your camera at the QR on the Mac → paired instantly.
   - Or tap **Pair Manually** and type the IP + token the Mac shows.

They auto-reconnect from then on. Use **Pause** from the phone (power icon) to stop
reconnect attempts for 1h / 8h / indefinitely without unpairing.

---

## 🗂️ Project structure

```
DroidDock/
├─ droiddock-native/          🖥️  Mac app  (SwiftUI · Rust core)
│  ├─ core/                   ·  Rust: phone link, Bluetooth LE, ADB/scrcpy,
│  │                          ·  notifications, transfers, Quick Share (C ABI + JSON)
│  ├─ mac/                    ·  SwiftUI app, 17 languages (SwiftPM, no Xcode project)
│  └─ scripts/                ·  build · dmg · dev run · i18n checks
├─ droiddock-tauri/           🗄️  previous Mac app (Tauri 2) — no longer released
│  └─ tools/fake_mac.py       ·  test harness: drives Mac→phone messages
├─ droiddock-android/         📱  Android app  (Kotlin + Compose)
│  └─ app/src/main/java/      ·  services · repos · Compose screens
├─ droiddock 2/               🗄️  retired Electron client — kept read-only as
│                             ·  the protocol reference the rewrite matches
├─ .github/workflows/         ·  CI release workflow
└─ README.md
```

---

## ⚠️ Notes & limitations

- The LAN link is a WebSocket gated by a pairing token — fine for a trusted home
  network. **Optional AES-256-GCM** (off by default, Settings → Security) encrypts
  JSON control messages, keyed off the pairing token. **Scope, stated plainly:**
  binary frames — file chunks, thumbnails, app icons, mirror video — stay in the
  clear, because wrapping them means surgery on the hot transfer/mirror loops.
  This is *not* end-to-end encryption of everything, and nothing in the UI claims
  it is. Full TLS is still a future enhancement.
- **Updating the Mac app revokes its Accessibility permission**, and macOS does not
  tell you. Because DroidDock is ad-hoc signed rather than Developer ID signed, the
  permission is recorded against a hash of the exact app binary
  (`designated => cdhash H"…"`), and every release is a different binary. The row
  stays in **Privacy & Security → Accessibility** with the switch still on, but the
  Mac ignores every remote click and keystroke. Fix it with **Settings → System →
  Reset permission** in DroidDock, then tick DroidDock when macOS asks. Unticking
  and re-ticking the stale row often doesn't help — it's the row itself that's dead.
  A Developer ID signature is the only thing that removes this for good.
- **Call control over Wi-Fi has three limits, all Android's.** The Answer and
  hang-up buttons need the Calls permission and are hidden until it is granted,
  rather than shown and dead. Mute and speaker are reported back from the phone
  after the change, because the dialer that owns the call can override the audio
  route. Keypad tones need ADB — `Call.playDtmfTone` is reachable only from the
  device's default dialer, which DroidDock does not and should not claim to be.
- **A "Fix" in the setup check may only leave a notification.** Android restricts
  starting activities from the background; holding "Display over other apps" is
  the exemption. Without it, the phone posts a tappable notification instead of
  raising the settings screen, and the Mac says which of the two happened.
- **The setup check cannot read macOS's notification permission.** The API a
  desktop app can call returns "granted" unconditionally, so that row is marked
  informational rather than shown as a green tick that proves nothing.
- **The Finder mount is read-only until you say otherwise.** Turning on
  *Settings → Mac files → Allow writing to the phone* makes it read-write; it is
  off by default because a bug on that path damages files on the phone, where a
  read bug only shows a wrong listing. Finder's `.DS_Store` and `._` sidecars
  are discarded rather than written. Moving a file between folders is not
  supported — renaming in place is.
- **Clipboard images are automatic Mac → phone only.** Android refuses
  background clipboard reads, and the accessibility event that rescues copied
  *text* carries no pixels — so the phone can send a picture when you ask it to
  (tile, widget, share sheet) and cannot notice one by itself.
- **Only one copy of DroidDock can run at a time** — they compete for port `48484`,
  and the loser can never accept a phone. The app now says so instead of looking
  healthy and doing nothing.
- **Android 13+ / Samsung One UI block background clipboard reads.** Phone → Mac
  auto-clipboard uses the accessibility service to read copied text from
  accessibility events the moment a "copied" toast fires — no clipboard read required.
- Screen mirroring uses **MediaProjection**: the phone asks for a one-time "Allow
  screen capture" consent each session (Android OS requirement). **Auto mode**
  works around this by keeping the system dialog visible without a notification tap
  — and reusing the projection across Mac reconnects where possible.
- Stopping mirroring from the Mac (closing the window or clicking STOP) fully stops
  the Android foreground service — the phone's cast/screen-share indicator clears
  immediately.

---

## 🧭 Roadmap

- [x] Wi-Fi app link — clipboard / notifications / messages / files / photos, no ADB
- [x] `adb` auto-downloads on first run (no manual platform-tools install)
- [x] One-click scrcpy install via Homebrew
- [x] Prebuilt `.app` + `.apk` via CI on each tag
- [x] Screen mirroring over the app link (MediaProjection + H.264, no ADB/scrcpy)
- [x] Touch/keyboard/nav control injection via AccessibilityService
- [x] Phone camera over the app link (front/back, no ADB)
- [x] Pop-out phone-shaped mirror window (always-on-top, scrcpy-style)
- [x] Auto Mirror mode — no per-session prompt with overlay permission
- [x] Custom QR scan screen with glowing corner brackets
- [x] Polished Messages UI — 2-pane chat with avatars, search, day dividers
- [x] Native macOS notifications with inline reply + incoming call alerts
- [x] **Mac UI redesign** — Apple HIG + Liquid Glass (glassmorphism, Geist font, layered depth)
- [x] **Android UI redesign** — Material Design 3 (all-vector icons, MD3 components, dynamic palette)
- [x] **Phone → Mac file transfer** — send files from the Android app directly to Mac Downloads with live amber progress bar
- [x] **Android Files tab** — upload queue with real-time progress bars, transfer speed, percentage; recent transfers history with colored file-type badges and direction/time labels
- [x] **Mac client rewritten on Tauri 2** — Rust backend, ~6 MB `.dmg` instead of a
      bundled Chromium
- [x] **Phone card** — wallpaper backdrop, live clock, battery, album art, recent apps
- [x] **Apps grid** — every launchable app with real icons, prefix-ranked search,
      launch or open in its own Mac window
- [x] **Menu-bar panel** — battery / now-playing / notifications without opening the app
- [x] **Link quality probe** + mDNS discovery as a third reconnect fallback
- [x] **Opt-in AES-256-GCM** for JSON control messages (see Notes for scope)
- [x] **Desktop mode** — mirror a virtual Android display, phone stays usable
- [x] **Per-app notification muting**
- [x] **Mac status + Now Playing on the phone** — battery, charging, and real media
      transport keys that drive any Mac app
- [x] **Mac volume / brightness / screensaver / lock** from the phone
- [x] **Light & dark themes** on both apps (Android light palette contrast-measured)
- [x] **Self-updating** — in-app updater on Mac, release-signed APK on Android
- [x] **Device management** — remembered Macs, Quick Connect, switch, mDNS discovery
- [x] **Clipboard history**, session-only and capped
- [x] **Quick Settings tiles** for connection + accessibility
- [x] **Lock the phone from the Mac** (no unlock — Android exposes no such API)
- [x] Window-wide drag & drop, configurable mirror quality, Android unit tests in CI
- [x] **Call control over Wi-Fi** — answer, decline, hang up, mute, speaker without ADB
- [x] **Setup check** — every permission on both devices, with a Fix button per row
- [x] **Ring my phone**, **mirror stills and recordings**, **clipboard images**
- [x] **Writable Finder mount** — opt-in, with Finder's `.DS_Store` litter filtered out
- [x] **Sixteen languages** in both apps, with a **Language** setting — Arabic included,
      and the layout mirrors for it. Adding another is one file plus one line
- [x] **Native Mac app** — SwiftUI on a Rust core, replacing the Tauri client
- [x] **Bluetooth LE fallback** when Wi-Fi drops, with automatic return
- [x] **Second screen** and **notification app windows** with Back / Home / Recent
- [x] **WhatsApp & internet calls** — caller name, decline and hang up from the Mac
- [ ] Talk and listen on phone calls through the Mac (proof of concept in progress)
- [ ] TLS on the LAN link
- [ ] Audio streaming (Mac → phone; phone → Mac shipped in v2.2.0)
- [ ] Second language shipped (the machinery is in place, no catalog yet)

---

## 🤝 Collaborate

Got a feature idea, a bug, or just want to make DroidDock better?
All suggestions and contributions are welcome — open an issue or a pull request on GitHub.

- **Feature request / idea** → [open an issue](../../issues/new) and describe what you'd like to see
- **Bug report** → [open an issue](../../issues/new) with steps to reproduce and your Android / macOS versions
- **Pull request** → fork the repo, make your change, and open a PR — even small fixes are appreciated

If you want to discuss a bigger idea before coding, just start a [discussion](../../discussions) or drop a comment in an issue.
Everyone who contributes gets credited in the release notes.

---

<div align="center">

**Built with ❤️ for a friction-free desk.**

</div>

---

## ☕ Support

<div align="center">

If DroidDock saves you time, a small tip keeps the ☕ flowing. 🙏

<br>

**Scan to pay — works with any UPI app**

<img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&margin=12&data=upi%3A%2F%2Fpay%3Fpa%3D9120741461%40ybl%26pn%3DHimanshu%26cu%3DINR" alt="UPI QR Code" width="180" />

`9120741461@ybl`

PhonePe · GPay · Paytm · BHIM · any UPI app

<br>

[![GitHub Stars](https://img.shields.io/github/stars/himanshudev28/MacDroid?style=for-the-badge&logo=github&color=F5A623&logoColor=white&label=⭐%20Star%20on%20GitHub)](https://github.com/himanshudev28/MacDroid)
[![Website](https://img.shields.io/badge/%F0%9F%8C%90%20Visit%20the%20website-F5A623?style=for-the-badge)](https://himanshudev28.github.io/DroidDockWebsite/)

</div>
