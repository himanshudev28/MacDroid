<div align="center">

# 🔗 DroidDock

### Your Android phone and your Mac, finally in sync.

Clipboard, notifications, files, photos, messages, calls and more — flowing seamlessly
between your phone and your Mac over your local Wi‑Fi.

![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Android-1f6feb?style=for-the-badge)
![Mac app](https://img.shields.io/badge/Mac-Electron%20%2B%20React-47848F?style=for-the-badge&logo=electron&logoColor=white)
![Android app](https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Link](https://img.shields.io/badge/link-LAN%20WebSocket-FFB454?style=for-the-badge)

</div>

---

## ✨ What is this?

**DroidDock** is a personal **Android ↔ Mac bridge**. Pair your phone with your Mac once,
and the two stay quietly connected on your network — so the things you do on one device
just show up on the other. Copy text on your phone and paste it on your Mac. Reply to a
text from your keyboard. Drag a file onto the Mac window and it lands on your phone.
Browse your gallery, mirror your screen, take a call — all from your desk.

It's two apps that talk to each other:

| | App | Built with |
|---|---|---|
| 🖥️ | **Mac client** — [`droiddock 2/`](droiddock%202/) | Electron · React · Tailwind |
| 📱 | **Android companion** — [`droiddock-android/`](droiddock-android/) | Kotlin · Jetpack Compose |

---

## 🚀 Features

| | Feature | What it does |
|:--:|---|---|
| 📋 | **Clipboard sync** | Mac → phone automatically; phone → Mac automatically (via the accessibility service) or manually — with an **Auto / Manual** toggle. |
| 🔔 | **Notification mirroring** | Phone notifications on your Mac, with **inline reply** and dismiss. |
| 📁 | **File transfer & browser** | Drag‑and‑drop both ways with live progress; browse, download, upload, **rename**, **delete** and **search** phone storage. |
| 🖼️ | **Photos & Videos** | Browse thumbnails, open full‑res in Preview/QuickTime, download originals. |
| 💬 | **Messages** | Read and **reply to SMS** from your Mac, threads sync live. |
| 👤 | **Contacts** | Browse and search your phone's contacts. |
| 📞 | **Phone calls** | Place calls from the Mac; incoming‑call alerts with caller ID. |
| 🎵 | **Media remote** | Now‑Playing card with transport + volume control. |
| 🪞 | **Screen mirroring** | Mirror and control your phone (via scrcpy over ADB). |
| 🔌 | **Smart pairing** | QR or manual IP, auto‑reconnect, "forget this Mac", and a **Pause** mode (1h / 8h / until you resume). |

---

## 🧠 How it works

The apps speak over a **token‑gated WebSocket on your LAN** — request/response messages
keyed by `reqId`, plus a compact binary frame protocol for file and thumbnail transfers.
ADB (and `scrcpy`) act as a second, faster transport for USB‑only features like screen
mirroring, call control and device volume.

```mermaid
flowchart LR
    subgraph MAC["🖥️  Mac app · Electron + React"]
        UI["React UI<br/>(Files · Photos · Messages · …)"]
        SRV["WebSocket server<br/>:48484"]
        ADB["ADB / scrcpy"]
    end
    subgraph PHONE["📱  Android app · Kotlin + Compose"]
        BS["BridgeService<br/>(foreground)"]
        CM["ConnectionManager<br/>WebSocket client"]
        A11Y["Clipboard<br/>Accessibility service"]
    end
    UI --- SRV
    CM <==>|"token-gated LAN WebSocket<br/>JSON + binary frames"| SRV
    ADB -.->|"USB / Wi-Fi ADB<br/>mirror · calls · volume"| PHONE
    BS --- CM
    A11Y --- CM
```

Pair once with a QR code (or type the IP), and the two reconnect on their own whenever
they're both open on the same network.

---

## 🛠️ Getting started

### Prerequisites (one‑time)

```bash
brew install node                              # Node.js 22+
brew install --cask android-platform-tools     # adb
brew install scrcpy                            # screen mirroring (optional)
```

On the phone: **Settings → About phone → tap *Build number* 7×**, then enable **USB debugging**.

### ▶️ Run the Mac app

```bash
cd "droiddock 2"
npm install
npm run dev          # launch in development
npm run dist         # or build a packaged .app (output in dist/)
```

> 💡 If your terminal exports `ELECTRON_RUN_AS_NODE=1` (some IDE terminals do), Electron
> boots as plain Node and crashes with `electron.app … whenReady undefined`.
> Launch with `env -u ELECTRON_RUN_AS_NODE npm run dev`.

### 📲 Build & install the Android app

Open [`droiddock-android/`](droiddock-android/) in **Android Studio** and press **Run**, or:

```bash
cd droiddock-android
./gradlew installDebug      # builds and installs on a connected phone
```

On first launch, grant the permissions the app requests (Notification access,
SMS · Contacts · Calls, All‑files access). For automatic phone → Mac clipboard, enable
the **DroidDock Clipboard** accessibility service.

---

## 🔐 Pairing

1. Put the Mac and phone on the **same Wi‑Fi network**.
2. On the **Mac**, open **Pair Device** — it shows a QR code plus the manual IP/token.
3. On the **phone**, tap **Pair with Mac** and scan the QR, or choose **Enter IP manually**.

That's it — they auto‑reconnect from then on.

---

## 🗂️ Project structure

```
DroidDock/
├─ droiddock 2/            🖥️  Mac app  (Electron + React)
│  ├─ src/main/            ·  adb · wifi · transfer · main process
│  └─ src/renderer/        ·  React UI + components
├─ droiddock-android/      📱  Android app  (Kotlin + Compose)
│  └─ app/src/main/        ·  services, repos, Compose UI
├─ img/  ·  img_featureGuide/  ·  LinkMyDriod MAcApp/   🎨  UI/UX reference screenshots
└─ README.md
```

> The screenshot folders are **design references** used for feature parity — the visual
> language the apps were modelled after, not shots of the build itself.

---

## ⚠️ Notes & limitations

- The LAN link is a plain WebSocket gated by a pairing token — perfect for a trusted home
  network; TLS is a future enhancement.
- **Android 13+ / Samsung One UI block background clipboard reads for every app.** So
  phone → Mac auto‑clipboard is done through the accessibility service: it reads the
  copied text straight from accessibility events and sends it the moment a "copied" toast
  confirms a real copy — no clipboard access required.
- Screen mirroring uses `scrcpy` over ADB, so it needs USB or Wi‑Fi ADB connected.

---

<div align="center">

**Built with ❤️ for a friction‑free desk.**

</div>
