# DroidDock Android Companion (Phases 2–4)

Keeps a Wi-Fi link to the DroidDock Mac app: clipboard sync, notification mirroring with replies, SMS from the Mac, media remote, and call alerts.

## Build & install (sideload — no Play Store)

1. Install **Android Studio** on your Mac (free).
2. *Open* this folder. First sync downloads Gradle 8.9 + SDK 35 automatically (a few minutes).
3. Phone plugged in with USB debugging on → press **Run ▶**. The app installs and launches.

## First run on the phone

1. Allow the notification permission (the link runs as a small persistent notification).
2. Tap **PAIR WITH MAC** and scan the QR from the Mac app (sidebar → WI-FI LINK → PAIR).
3. Tap **ALLOW BACKGROUND (BATTERY)** so Android doesn't kill the link when the screen is off.
4. Tap **ENABLE NOTIFICATION ACCESS** and switch DroidDock on (required for mirroring + media remote).
4b. Tap **GRANT SMS · CONTACTS · CALLS** and accept (required for the Messages tab and call alerts).
5. Optional: add the **Send to Mac** Quick Settings tile (edit your QS panel).

## How sending text to the Mac works

Android blocks background clipboard reads, so phone → Mac is always one explicit tap:
select text → ⋮ → *Send to Mac* · share sheet → *Send to Mac* · QS tile · or the button in the app.
Mac → phone needs nothing — it just appears in your clipboard.

## Files

- `ConnectionManager.kt` — WebSocket client, auto-reconnect, clipboard in/out
- `BridgeService.kt` — foreground service keeping the link alive
- `SendActivities.kt` — tile helper, text-selection action, share target
- `ClipTileService.kt` — Quick Settings tile
- `NotifListener.kt` — captures notifications, executes replies from the Mac
- `SmsRepo.kt` — SMS threads/messages/send + contact lookup
- `MediaRemote.kt` — media session watcher + transport/volume control
- `CallReceiver.kt` — incoming call broadcasts → Mac alerts
- `MainActivity.kt` — Compose UI (pairing, status, manual send)
