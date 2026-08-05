# Phase 16 — Parity Validation & Cutover Checklist

**What this file is:** the feature-by-feature checklist the PRD (Part 4, Phase 16)
calls for — run the Tauri app and the Electron app (`droiddock 2/`) side by side
against the same real phone for several days, and tick each row only once it's
been *exercised on real hardware*, not just "looks right."

**What this file is NOT:** proof that parity passed. The checkboxes track
**hardware verification**, which is not the same thing as whether the feature is
written. Almost all of it *is* written — see the `code` tags — but code being
present is precisely the claim this pass exists to distrust. Don't skip straight
to "cutover" (archiving `droiddock 2/`) until every row here is checked AND the
acceptance criterion below is true.

**Two axes, tracked separately:**

| Marker | Meaning |
|---|---|
| `[x]` / `[ ]` | Exercised on the real phone, or not. **Only hardware ticks this.** |
| `code ✔` | Implemented in `app/` — command + handler + UI present |
| `code ◐` | Implemented, but a known gap or unresolved question is attached |
| `code ✘` | Not written yet |

Code status below was surveyed on 2026-08-05 against `app/src-tauri/src/*.rs`
(Tauri command list + `ws_server.rs` message dispatch) and `app/src/components/`.
It is a static read of the source, not a runtime claim.

> **Coverage note (2026-08-05).** This file previously stopped at Phase 15 —
> `checkpoint.md` claimed it covered Phases 17–19 too, and it did not. The
> Phase 17/18/19 sections and the whole "AirSync parity" half below were added
> in this pass, so the checklist now covers everything that has been built,
> not just the PRD's Part 4.

**Acceptance (from the PRD):** the Android app cannot distinguish the two;
nothing regressed.

**Cutover step (last row, do not do early):** once everything below passes,
`git mv "droiddock 2" "droiddock 2 (archived)"` (or equivalent) — keep it in
git history, don't delete. This file intentionally does not perform that step
automatically; it's a one-way-feeling change on a directory that isn't mine to
retire without you having actually run the comparison.

---

## Hardware evidence so far (2026-08-04 session)

Not enough to tick any behaviour row, but not nothing — recorded here so the
next session doesn't re-derive it:

- The phone links to the Mac over Wi-Fi and **stays linked across app restarts**,
  on both the dev build and the release bundle. Partial support for the two
  reconnect rows; neither specific trigger (sleep/wake, Wi-Fi toggle) was tested.
- **Mac→phone screen control proven end to end** — but via
  `tools/fake_mac.py`, *not* through the Tauri UI, and only `mirror-key home` +
  `mirror-swipe` + the `control-unavailable` fallback. Tap, type, back and
  recents remain untested, so the Mirror row below stays open.
- Prerequisite discovered the hard way: on Android 13+ a **sideloaded** APK
  can't enable its accessibility service until *Settings → Apps → DroidDock →
  ⋮ → Allow restricted settings* is tapped, and **reinstalling turns it off
  again**. Every mirror-control row depends on this being on.

---

## Pairing & connection
- [ ] Fresh QR pairing (new token) succeeds on first scan · `code ✔`
- [ ] Manual pairing (typed token/IP) succeeds · `code ✔`
- [ ] Reconnect after Mac sleep/wake · `code ✔`
- [ ] Reconnect after phone Wi-Fi toggle off/on · `code ✔` (broadcast + mDNS fallback)
- [ ] Newest-wins: pairing from a second device kicks the first cleanly · `code ✔` (`ws_server.rs` — a new valid hello closes the previous socket under one lock)
- [ ] 5s auth timeout on garbage/no-hello connection · `code ✔` (`AUTH_TIMEOUT`, `ws_server.rs:16`)

## Files
- [ ] Browse `/sdcard`, breadcrumbs work · `code ✔`
- [ ] Upload (drag-drop and file picker), progress bar accurate, cancel works · `code ✔`
- [ ] Download, progress bar accurate, cancel works · `code ✔`
- [ ] Rename, delete · `code ✔`
- [ ] Large file (>100MB) transfer doesn't stall or corrupt · `code ✔` (chunked + backpressured; size never exercised)

## Photos
- [ ] Grid loads, lazy thumbnails populate scrolling down · `code ✔`
- [ ] Open photo/video (pulls + opens in default Mac app) · `code ✔`
- [ ] Video duration displays correctly · `code ✔`

## Messages / Contacts / Calls
- [ ] SMS threads list, open a thread, message bodies correct · `code ✔`
- [ ] Send SMS from Mac, confirm delivery on phone · `code ✔`
- [ ] Contacts list loads, search works · `code ✔` (client-side filter over name + number)
- [ ] Outbound call (Contacts → call) dials the phone · `code ✔`
- [ ] Incoming call shows view-only overlay (Wi-Fi path) · `code ✔`
- [ ] With ADB attached: outbound call CallOverlay upgrades to rich mute/
      speaker/DTMF/duration controls · `code ✔`

## Notifications
- [ ] Native banner appears for a phone notification · `code ✔`
- [ ] Reply from banner delivers to the right conversation · `code ✔`
- [ ] Dismiss from Mac clears the phone notification · `code ✔`
- [ ] In-app Notifications panel stays in sync with dedupe · `code ✔`

## Clipboard
- [ ] Copy on phone → paste on Mac · `code ✔`
- [ ] Copy on Mac → paste on phone · `code ✔`
- [ ] Toggling `clipboardSync` off stops both directions · `code ✔` (single flag gates both, `clipboard.rs`)

## Media controls
- [ ] Play/pause/next/prev reach the phone's active media session · `code ✔`
- [ ] Seek and volume slider both work (confirm `media-cmd` int semantics) · `code ◐` — the int-semantics question in this row is still open; it's exactly what hardware settles

## Screen Mirror / Camera
- [ ] Wi-Fi Mirror: pop-out opens phone-shaped, aspect-locked · `code ◐` — the window-sizing fix is unverified; checkpoint flags it as needing eyes on screen
- [ ] Tap/swipe/type/back/home/recents control the phone · `code ✔` — swipe + home proven via `fake_mac.py`; tap/type/back/recents untested, and none tested through the Tauri UI
- [ ] Closing pop-out stops the phone's foreground cast notification · `code ✔`
- [ ] No visible stutter at typical Wi-Fi latency, 30fps · `code ✔`
- [ ] Camera tab: front/back flip works from inside the pop-out · `code ✔`

## ADB / Devices tab
- [ ] Plugged-in device (USB debugging on) shows in Devices tab · `code ✔`
- [ ] Go Wireless, QR pair, code pair all succeed · `code ✔`
- [ ] Screenshot, volume slider via ADB · `code ✔`
- [ ] ADB Mirror / ADB Camera spawn scrcpy correctly · `code ✔`
- [ ] Background reconnect scheduler re-attaches after unplug/replug · `code ✔`

## Native polish
- [ ] Launch at login toggle survives a full reboot · `code ✔`
- [ ] Tray Pause 1h/8h/indefinite mutes notification banners + clipboard · `code ✔`
- [ ] Timed pause auto-resumes at deadline without restarting the app · `code ✔`
- [ ] Tray menu status line reflects live connection state · `code ◐` — the status fix is unverified; checkpoint flags it alongside the Dashboard one

## Packaging
- [x] Push a real `vX.Y.Z` tag, confirm GitHub Actions produces a `.app`/`.dmg`
      on the Release page · `code ✔` — **done 2026-08-04**: `v1.0.0`, both CI jobs
      green, assets `DroidDock_1.0.0_aarch64.dmg`, `DroidDock_aarch64.app.tar.gz`,
      `DroidDock-Android.apk`. Caveat recorded in `checkpoint.md`: the release was
      meant to be a draft and published anyway, because both jobs write the same
      release and the Android job's `draft` setting disagreed (fixed in `dba74af`).
- [ ] Fresh install on a clean Mac account: unsigned right-click-to-open works · `code ✔` — the release bundle was launched, but on the dev account, not a clean one

---

# Post-parity features (Phases 17–19)

These are not Electron-parity rows — the Electron app has no equivalent, so
there is nothing to compare against. Tick them on "does it work", not "does it
match".

## Phase 17 — open-in-place with edit writeback
- [ ] Double-click a phone file in Files → it opens in the default Mac app · `code ✔`
- [ ] Save it → the phone's copy is **overwritten**, not saved as `name (2).ext` · `code ✔`
      (this was the CRITICAL bug; `transfer::push` now carries an explicit `overwrite`)
- [ ] Save while the phone is away → badge shows pending, and it retries on reconnect · `code ◐`
      — retries **only** on reconnect, never in-session; a phone that comes back
      via a route that doesn't re-handshake won't trigger it
- [ ] Two files with the same basename from different folders don't collide · `code ✔`

## Phase 18 — photo auto-sync
- [ ] Enable in Settings, take a photo → it lands in `~/Pictures/DroidDock` · `code ✔`
- [ ] Change the destination folder → next sync uses the new one · `code ✔`
- [ ] "Back-fill existing library" downloads everything already on the phone · `code ✔`
- [ ] Go offline, take photos, reconnect → the missed ones arrive · `code ✔`
- [ ] Photos taken *during* a running pass are picked up · `code ✔` — **fixed 2026-08-05**;
      the ping used to be dropped outright, now the running pass re-checks
- [ ] Two same-model phones don't skip each other's photos · `code ✔` (ledger keyed on `deviceId`)

## Phase 19 — reverse file browsing (Mac Files on the phone)
- [ ] Off by default: the phone has no "Mac Files" tab until Settings enables it · `code ✔`
- [ ] Enabled → the tab appears and lists the configured roots · `code ✔`
- [ ] Pull a file → it lands complete on the phone (no truncation) · `code ✔`
      (leading-chunk drop fixed by the `mac-fs-pull-ready` ack)
- [ ] `../` or a symlink pointing outside a root is refused · `code ✔` (8 unit tests)
- [ ] Global pause blocks browsing · `code ✔`
- [ ] Cancel a large pull mid-flight · `code ✘` — **not implemented**: `mac-fs-pull`
      never joins `TransferRegistry`, so there is no cancel, no progress event,
      and a disconnect mid-pull isn't aborted with the rest

---

# AirSync parity (UI Tier A, Tiers B–D, v4 changelog, menu bar/battery/widget)

Everything in this half was built after the PRD ended, against
`AIRSYNC_GAP_ANALYSIS.md`. **None of it has been seen running**, with the two
exceptions noted under "Hardware evidence" above.

## Tier A — the shell
- [ ] Icon rail reaches all 15 destinations, including below the fold on a short window · `code ✔`
- [ ] Phone card shows live battery, and the volume popover moves phone volume · `code ✔`
- [ ] Connection pill shows the real transport + RTT; popover lists IP and ADB serial · `code ✔`
- [ ] Mini-player reflects what's playing and its transport buttons work · `code ✔`
- [ ] Quick actions: send file, browse, mute banners, send clipboard · `code ✔`
- [ ] Onboarding runs once on a fresh profile and never again · `code ✔`
- [ ] Menu-bar panel opens on **left**-click; Pause/Quit menu on **right**-click · `code ✔`
      ⚠️ this is a deliberate behaviour change from the old tray — see `checkpoint.md`
- [ ] Notifications group by app; the toolbar toggles back to a flat list, and it persists · `code ✔`
- [ ] Settings' 6 categories contain every control the old flat page had · `code ✔`
- [ ] Window-opacity slider changes the vibrancy through-view · `code ◐` — never seen rendered
- [ ] Live clock ticks on the minute boundary · `code ✔`

## Tier B — phone card content
- [ ] Phone wallpaper appears behind the card after the handshake · `code ✔`
- [ ] Album art replaces it while music plays, and reverts when it stops · `code ✔`
- [ ] Apps grid lists every launcher app with real icons · `code ✔`
- [ ] App search prefix-ranks (typing "ma" puts Maps above Gmail) · `code ✔`
- [ ] Launching an app from the grid opens it on the phone · `code ✔`
- [ ] Recent-apps row populates and launches · `code ✔`
- [ ] Notification rows show the sending app's real icon · `code ✔`

## Tier C — connectivity
- [ ] Link-quality pill grades good/fair/weak, and shows `stalled` when the phone goes silent · `code ✔`
- [ ] mDNS discovery finds the Mac on a network that drops UDP broadcast · `code ✔`
- [ ] Encryption **off** (default): an older phone still connects · `code ✔`
- [ ] Encryption **on**: handshake completes and every feature still works · `code ◐` —
      has never completed a real handshake; **and it covers JSON only**, binary
      frames (files, thumbnails, mirror video) stay in the clear by design

## Tier D
- [ ] Desktop mode opens a scrcpy virtual display at the configured size · `code ✔`
- [ ] Double-click an app in the grid → it opens in its own Mac window · `code ✔`
- [ ] Remote control **off** (default): the phone has no Remote tab · `code ✔`
- [ ] Remote control on: trackpad, keys and text drive this Mac · `code ✔`
- [ ] Per-app notification muting silences the banner but still logs the notification · `code ✔`

## AirSync v4 changelog items
- [ ] Notification progress bars update (a download shows moving progress, no banner spam) · `code ✔`
- [ ] Notification action buttons fire the right action by index · `code ✔`
- [ ] `low`/`min` priority notifications land in the panel with no banner · `code ✔`
- [ ] ADB device picker appears with two devices and the choice sticks · `code ✔`
- [ ] Flex display size setting is honoured by desktop mode · `code ✔`
- [ ] Default mirror mode badges the chosen route in the Mirror tab · `code ✔`
- [ ] Menu-bar unread count appears and clears when the panel opens · `code ✔`
- [ ] Call controls in the menu-bar panel (ADB attached) mute/speaker/hang up · `code ✔`

## Menu bar, battery, widget
- [ ] Menu-bar text mode / battery style / max length each take effect immediately · `code ✔`
- [ ] "Paused until HH:MM" shows **local** time · `code ✔` — **fixed 2026-08-05**
      (it rendered UTC)
- [ ] Low-battery alert fires once per discharge, re-arms on charge · `code ✔`
- [ ] Status widget floats on top, survives a Space switch, drags by any point · `code ◐`
      — the drag fix (`data-tauri-drag-region`) is unverified

## Mac → phone (added 2026-08-05)
- [ ] Phone's Home screen shows this Mac's name and battery · `code ✔` (`mac-info`)
- [ ] Desktop Mac (no battery) reads "Plugged in", not "0%" · `code ✔`
- [ ] Turning "Share this Mac's status" off makes the row disappear on the phone · `code ✔`
- [ ] Mac media control from the phone's Remote tab: play/pause, next, prev, volume, mute · `code ✔`
      (needs Accessibility permission, same as the rest of remote control)
- [ ] Drop a file anywhere in the window (outside Files) → it sends to Download · `code ✔`
- [ ] Dropping onto the **Files** view still uploads to the browsed folder, once, not twice · `code ✔`

---

## Multi-day parallel run
- [ ] Run Tauri app as the daily driver for several days with Electron app
      available as fallback; log any behavior gap here as you find it:

  (add rows as needed)

---

## Only after every box above is checked
- [ ] `git mv "droiddock 2" "droiddock 2 (archived)"` and commit
- [ ] Update `checkpoint.md`: mark Phase 16 COMPLETE, note the date parity was
      confirmed
