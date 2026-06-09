# DroidDock — Complete (Phases 1–5)

A personal Android ↔ Mac bridge.
**Phase 1 (USB via ADB):** device info + battery, file browser with two-way transfer, screenshots, screen mirroring (scrcpy).
**Phase 2 (Wi-Fi):** pair once with a QR code, then two-way clipboard sync with the DroidDock Android companion app.
**Phase 3 (Notifications):** phone notifications appear as native macOS notifications — with inline reply for chat apps.
**Phase 4 (Messages · Media · Calls):** read & send SMS from a Messages tab, control the phone's music from the sidebar, and get incoming-call alerts with caller ID.
**Phase 5 (Polish):** wireless ADB (mirror & files with no cable), phone-as-webcam via OBS, custom icon, and packaging into a real double-clickable `.app`.

## Prerequisites (one-time)

1. **Node.js 22+** — `brew install node`
2. **Android platform tools** — `brew install --cask android-platform-tools`
3. **scrcpy** (screen mirroring) — `brew install scrcpy`
4. **Phone:** Settings → About phone → tap *Build number* 7× → Developer options → enable *USB debugging*
5. **Companion app:** open the `droiddock-android` project in Android Studio and Run it on your phone (see its README)

## Run

```bash
cd droiddock
npm install
npm run dev
```

## Pairing (Phase 2)

1. Mac and phone on the **same Wi-Fi network**
2. In the Mac app sidebar: **WI-FI LINK → PAIR** (a QR appears)
3. On the phone: open DroidDock → **PAIR WITH MAC** → scan
4. If macOS asks to allow incoming network connections — **Allow**

After pairing, the apps reconnect automatically whenever both are on the network.

## Clipboard rules

- **Mac → phone:** automatic. Copy anything on the Mac; it lands on the phone ~1s later.
- **Phone → Mac:** one tap (Android blocks silent background clipboard reads). Three ways:
  - select text → ⋮ → **Send to Mac**
  - share sheet → **Send to Mac**
  - Quick Settings tile **Send to Mac** (add it: pull down shade → pencil/edit → drag tile in)

## Notifications (Phase 3)

1. In the Android app tap **ENABLE NOTIFICATION ACCESS** and switch DroidDock on.
2. On the Mac, the first notification may ask permission — allow notifications for **Electron** in System Settings → Notifications.
3. Chat apps with quick-reply (WhatsApp, Telegram, Messages…) show a **Reply** field right in the macOS banner; your reply is injected back into the app on the phone.
4. The bell icon on the WI-FI LINK card mutes/unmutes mirroring. Ongoing/system/media notifications are filtered out automatically.

## Phase 4 features

- **Messages tab** (top of the main area): your SMS threads with contact names. Click a thread, read the conversation, type and hit Enter — it sends through your phone's SIM (normal carrier rates). New incoming texts refresh the view live.
- **Now Playing card** (sidebar, appears when music plays on the phone): track + artist, prev/play/pause/next, and a volume slider for the phone's media volume.
- **Call alerts:** when the phone rings, a macOS notification shows who's calling (contact name when available). It clears when you answer or the call ends.
- One-time on the phone: tap **GRANT SMS · CONTACTS · CALLS** in the Android app and accept all prompts (sideloaded app — no store restrictions apply).

## Phase 5 features

**Go wireless (cable-free ADB).** With the phone plugged in once, sidebar → **Go wireless**. The app flips the phone's ADB to TCP, finds its Wi-Fi IP, connects, and remembers the address — unplug and keep using Files, Mirror, Screenshot, Camera. It quietly auto-reconnects whenever no device is attached. The device card badge shows USB vs WI-FI. (Resets when the phone reboots — plug in once and tap again.)

**Phone camera → Mac webcam.** Sidebar → **Phone camera** opens your phone's rear camera in a window (needs Android 12+ and scrcpy ≥ 2.2). To use it in Zoom/Meet/Teams: install OBS (`brew install --cask obs`), add a *macOS Screen Capture* source pointed at the "DroidDock — Camera" window, click **Start Virtual Camera**, then pick *OBS Virtual Camera* in your video app. Front camera from Terminal: `scrcpy --video-source=camera --camera-facing=front`.

**Package as a real app.** `npm run dist` builds `dist/mac-arm64/DroidDock.app` (own icon included) — drag it to /Applications and launch from Spotlight like any app. It's unsigned, which is fine because *you* built it locally (no quarantine flag). Want a shareable .dmg later? Change `"target": ["dir"]` to `["dmg"]` in package.json.

## Troubleshooting

- **ADB NOT FOUND** — install platform tools, relaunch.
- **Unauthorized device** — tap *Allow* on the phone; or Developer options → *Revoke USB debugging authorisations* → replug.
- **Phone won't pair over Wi-Fi** — same network? macOS Settings → Network/Firewall: allow Electron/DroidDock. Re-scan the QR after the Mac changes networks (its IP changed).
- **Clipboard stops when phone sleeps** — in the Android app tap *Allow background (battery)* and accept.
- **Mirror does nothing** — `brew install scrcpy`.

## Config & security notes

Pairing token + port live in `~/Library/Application Support/droiddock/droiddock.json` (delete to regenerate). The link is plain WebSocket on your LAN, gated by that token — fine for a home network; TLS is on the Phase 5 wishlist.

## Roadmap

- ~~Phase 1 — USB basics~~ ✅
- ~~Phase 2 — Wi-Fi pairing + clipboard~~ ✅
- ~~Phase 3 — notification mirroring with quick replies~~ ✅
- ~~Phase 4 — SMS threads, media remote, call alerts~~ ✅
- ~~Phase 5 — wireless ADB, webcam via OBS, icon + .app packaging~~ ✅

## Ideas if you ever want more

TLS on the Wi-Fi link, UI themes, sending files from the phone's share sheet, multi-phone support, launching Android apps from the Mac. The protocol and structure are ready for all of them — bring any one of these back to Claude and build it in an evening.
