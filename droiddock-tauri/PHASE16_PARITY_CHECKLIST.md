# Phase 16 — Parity Validation & Cutover Checklist

**What this file is:** the feature-by-feature checklist the PRD (Part 4, Phase 16)
calls for — run the Tauri app and the Electron app (`droiddock 2/`) side by side
against the same real phone for several days, and tick each row only once it's
been *exercised on real hardware*, not just "looks right."

**What this file is NOT:** proof that parity passed. Nobody has run this pass
yet. Every row below is `[ ]` until you personally verify it. Phases 3–15 are
all still unverified-on-hardware per `checkpoint.md`'s BATCH NOTEs — that
verification and this parity pass can reasonably happen together, but don't
skip straight to "cutover" (archiving `droiddock 2/`) until every row here is
checked AND the acceptance criterion below is true.

**Acceptance (from the PRD):** the Android app cannot distinguish the two;
nothing regressed.

**Cutover step (last row, do not do early):** once everything below passes,
`git mv "droiddock 2" "droiddock 2 (archived)"` (or equivalent) — keep it in
git history, don't delete. This file intentionally does not perform that step
automatically; it's a one-way-feeling change on a directory that isn't mine to
retire without you having actually run the comparison.

---

## Pairing & connection
- [ ] Fresh QR pairing (new token) succeeds on first scan
- [ ] Manual pairing (typed token/IP) succeeds
- [ ] Reconnect after Mac sleep/wake
- [ ] Reconnect after phone Wi-Fi toggle off/on
- [ ] Newest-wins: pairing from a second device kicks the first cleanly
- [ ] 5s auth timeout on garbage/no-hello connection

## Files
- [ ] Browse `/sdcard`, breadcrumbs work
- [ ] Upload (drag-drop and file picker), progress bar accurate, cancel works
- [ ] Download, progress bar accurate, cancel works
- [ ] Rename, delete
- [ ] Large file (>100MB) transfer doesn't stall or corrupt

## Photos
- [ ] Grid loads, lazy thumbnails populate scrolling down
- [ ] Open photo/video (pulls + opens in default Mac app)
- [ ] Video duration displays correctly

## Messages / Contacts / Calls
- [ ] SMS threads list, open a thread, message bodies correct
- [ ] Send SMS from Mac, confirm delivery on phone
- [ ] Contacts list loads, search works
- [ ] Outbound call (Contacts → call) dials the phone
- [ ] Incoming call shows view-only overlay (Wi-Fi path)
- [ ] With ADB attached: outbound call CallOverlay upgrades to rich mute/
      speaker/DTMF/duration controls

## Notifications
- [ ] Native banner appears for a phone notification
- [ ] Reply from banner delivers to the right conversation
- [ ] Dismiss from Mac clears the phone notification
- [ ] In-app Notifications panel stays in sync with dedupe

## Clipboard
- [ ] Copy on phone → paste on Mac
- [ ] Copy on Mac → paste on phone
- [ ] Toggling `clipboardSync` off stops both directions

## Media controls
- [ ] Play/pause/next/prev reach the phone's active media session
- [ ] Seek and volume slider both work (confirm `media-cmd` int semantics)

## Screen Mirror / Camera
- [ ] Wi-Fi Mirror: pop-out opens phone-shaped, aspect-locked
- [ ] Tap/swipe/type/back/home/recents control the phone
- [ ] Closing pop-out stops the phone's foreground cast notification
- [ ] No visible stutter at typical Wi-Fi latency, 30fps
- [ ] Camera tab: front/back flip works from inside the pop-out

## ADB / Devices tab
- [ ] Plugged-in device (USB debugging on) shows in Devices tab
- [ ] Go Wireless, QR pair, code pair all succeed
- [ ] Screenshot, volume slider via ADB
- [ ] ADB Mirror / ADB Camera spawn scrcpy correctly
- [ ] Background reconnect scheduler re-attaches after unplug/replug

## Native polish
- [ ] Launch at login toggle survives a full reboot
- [ ] Tray Pause 1h/8h/indefinite mutes notification banners + clipboard
- [ ] Timed pause auto-resumes at deadline without restarting the app
- [ ] Tray menu status line reflects live connection state

## Packaging
- [ ] Push a real `vX.Y.Z` tag, confirm GitHub Actions produces a `.app`/`.dmg`
      on the Release page (never actually exercised in CI yet)
- [ ] Fresh install on a clean Mac account: unsigned right-click-to-open works

## Multi-day parallel run
- [ ] Run Tauri app as the daily driver for several days with Electron app
      available as fallback; log any behavior gap here as you find it:

  (add rows as needed)

---

## Only after every box above is checked
- [ ] `git mv "droiddock 2" "droiddock 2 (archived)"` and commit
- [ ] Update `checkpoint.md`: mark Phase 16 COMPLETE, note the date parity was
      confirmed
