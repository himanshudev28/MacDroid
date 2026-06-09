# MacDroid — DroidDock

A personal **Android ↔ Mac bridge**. Pair your phone with your Mac and get clipboard
sync, notification mirroring, file transfer, photos & videos, SMS, contacts, calls,
media remote, screen mirroring, and more — over your local Wi‑Fi (with USB/ADB as an
optional faster transport).

This repo contains both halves of the project plus the reference UI screenshots.

## Repository layout

| Folder | What it is |
|---|---|
| [`droiddock 2/`](droiddock%202/) | **Mac app** — Electron + React desktop client. |
| [`droiddock-android/`](droiddock-android/) | **Android app** — Kotlin + Jetpack Compose companion. |
| [`LinkMyDriod MAcApp/`](LinkMyDriod%20MAcApp/) | Reference screenshots of the Mac UI. |
| [`img/`](img/) · [`img_featureGuide/`](img_featureGuide/) | Reference screenshots of the Android UI / feature guide. |

## How it works

The two apps talk over a **token‑gated WebSocket on your LAN** (request/response keyed
by `reqId`, plus a binary frame protocol for file/thumbnail transfers). ADB/scrcpy is a
second transport used for USB‑only features (screen mirror, call control, device volume).
Pair once with a QR code (or enter the IP manually) and they auto‑reconnect whenever both
apps are open on the same network.

## Features

- **Pairing** — QR or manual IP, auto‑reconnect, "forget this Mac", and a Pause mode
  (1h / 8h / until‑resume) that also tells the Mac to stop trying.
- **Clipboard** — Mac→Android automatic; Android→Mac automatic via the accessibility
  service (with an Auto/Manual toggle), or manually via the share sheet / Quick Settings
  tile / "Send to Mac".
- **Notifications** — phone notifications on the Mac, with inline reply and dismiss.
- **File transfer & browser** — drag‑and‑drop both ways, progress, plus browse / download /
  upload / rename / delete / search of phone storage.
- **Photos & Videos** — browse thumbnails, open full‑res in Preview/QuickTime, download.
- **SMS, Contacts, Calls** — read/reply to texts, browse contacts, place calls from the Mac.
- **Media remote** and **screen mirroring** (via scrcpy over ADB).

## Prerequisites (one‑time)

1. **Node.js 22+** — `brew install node`
2. **Android platform tools** — `brew install --cask android-platform-tools`
3. **scrcpy** (screen mirroring) — `brew install scrcpy`
4. **Phone:** enable Developer options → USB debugging (for ADB features).

## Run the Mac app

```bash
cd "droiddock 2"
npm install
npm run dev
```

Build a packaged `.app`: `npm run dist` (output in `dist/`).

> Note: if you launch it from a terminal that exports `ELECTRON_RUN_AS_NODE=1`
> (e.g. some IDE integrated terminals), Electron boots as plain Node and crashes with
> `electron.app … whenReady undefined`. Launch with `env -u ELECTRON_RUN_AS_NODE npm run dev`.

## Build & install the Android app

Open `droiddock-android/` in **Android Studio** and press **Run**, or from the CLI:

```bash
cd droiddock-android
./gradlew installDebug      # builds and installs on a connected phone
```

On first launch: pair with the Mac (QR or manual IP), then grant the permissions the app
asks for (Notification access, SMS · Contacts · Calls, All‑files access) and — for
automatic Android→Mac clipboard — enable the **DroidDock Clipboard** accessibility service.

## Pairing

1. Mac and phone on the **same Wi‑Fi network**.
2. On the Mac: open **Pair Device** (shows a QR code + manual IP/token).
3. On the phone: tap **Pair with Mac** and scan the QR, or choose **Enter IP manually**.

## Notes & limitations

- The LAN link is plain WebSocket gated by a pairing token — fine for a trusted home
  network; TLS is a future enhancement.
- Android 13+/Samsung One UI block background clipboard reads for all apps, so
  Android→Mac auto‑clipboard is implemented via the accessibility service (reads the
  copied text from accessibility events and sends it when a "copied" toast fires).
