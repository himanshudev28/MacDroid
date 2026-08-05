# DroidDock Tauri Rewrite — Checkpoint

**Last updated:** 2026-08-05 — Android crash fixes + AirSync mobile parity
Phases 1–4 (see those two sections near the bottom, above "Design decisions").
Read this before anything else — it's the fast path to full context without
re-reading history.

> **Two things that changed the shape of the project on 2026-08-05, worth
> knowing before reading the phase history below:**
>
> 1. **The Android app had a guaranteed daily crash** — a `dataSync` foreground
>    service against Android 15's 6-hour cap, with no `onTimeout`. Fixed by
>    moving to `connectedDevice`. Six other crash paths went with it.
> 2. **The Android app now has unit tests and runs them in CI.** It previously
>    had no test source set at all. Nine tests, mutation-checked.

> **BATCH NOTE (Phases 16–19, a fourth explicit override):** The user
> explicitly instructed building straight through from Phase 16 to the end of
> the PRD (Phase 19) in one continuous run, without waiting for the hardware
> verification gate this project's own standing rules require between phases
> — the same kind of override as the Phases 3–10/11/12–15 batches above, but
> this time it also skips past a gate the PRD treats as a hard sequencing
> requirement, not just a testing nicety: **Phase 16 ("parity validation &
> cutover") is not a coding phase — its own acceptance criterion is a
> multi-day, real-hardware, side-by-side comparison against the Electron app,
> something no amount of code can substitute for.** So Phase 16 was *not*
> executed — instead, `PHASE16_PARITY_CHECKLIST.md` (repo root) was written as
> the concrete, buildable deliverable: a feature-by-feature checklist to run
> through once real hardware time is available. **Nobody has run that
> checklist yet, and the Electron app (`droiddock 2/`) has NOT been archived**
> — do not treat Phase 16 as "done" or start acting as if cutover happened.
>
> Phases 17, 18, and 19 were then built as pure code (all buildable parts of
> those phases), each independently code-reviewed by a fresh adversarial pass
> and fixed until `cargo build`, `cargo test`, and `npx tsc --noEmit` were
> clean (30 Rust tests total by the end of Phase 19: 4 protocol + 4 edit_cache
> baseline + 4 edit_cache fixes + 6 photo_sync + 6 mac_fs = 23 as counted by
> `cargo test`'s own tally, plus the pre-existing 0 from main/doc-tests).
> Phase 18's Android changes and Phase 19's Android changes (including its
> first-ever new UI tab) both passed a real `./gradlew :app:compileDebugKotlin`
> compile check, not just a read-through — but **compiling is not the same as
> working**: none of Phases 16 through 19, and for that matter none of Phases
> 3 through 15 either, have been exercised on real hardware. The full,
> ever-growing unverified surface now spans clipboard through reverse file
> browsing. See "Immediate next steps" below for the honest state of the
> world and what actually needs your phone before any of this is trustworthy.
>
> **This also means the Android freeze was broken (Phases 18 and 19 both
> touch `droiddock-android/`) before Phase 16 ever passed** — the PRD's own
> Part 6 header says post-parity phases start "only after Phase 16 passes."
> That didn't happen; it was explicitly overridden. If hardware testing
> later turns up that Phases 3–15's foundations have real bugs, Phases 17–19
> were built on top of that unverified foundation and may need rework too.

> **BATCH NOTE (Phases 3–10):** The user explicitly authorised building Phases
> 3 through 10 back-to-back in ONE session without the usual per-phase hardware
> verification gate — they will test all eight together in one pass. So unlike
> Phases 0–2, **none of Phases 3–10 has been verified on real hardware yet.**
> Everything below compiles (`cargo build`, `cargo test`, `tsc --noEmit`, and a
> full `tauri build` → `DroidDock.app` all pass) and is byte-shape-faithful to
> the reference source, but the acceptance criteria are still OPEN. The ordered
> test list is in the session's final consolidated report. Later phases that
> assume an earlier batch phase works (e.g. every list feature relies on Phase
> 2's reqId request/response, which Phase 2 DID verify live) are flagged there.
>
> **Phase 11 was built immediately after, in a separate session, on the same
> explicit instruction to keep going without waiting for the 3–10 hardware
> pass.** So Phase 11 is ALSO unverified on hardware, and additionally depends
> on Phase 5's transfer path (binary framing) and Phase 2's ws_server plumbing
> both actually working — if hardware testing of 3–10 turns up a bug in
> `ws_server.rs`'s binary routing or the outbox/backpressure path, re-check
> Phase 11's kind-3 handling isn't affected before assuming it's clean.
>
> **BATCH NOTE (Phases 12–15):** Same explicit user authorisation, a third
> time, in a fourth session: build Phases 12 through 15 back-to-back with no
> per-phase hardware gate. **None of Phases 12–15 has been verified on real
> hardware/CI either.** `cargo build`, `cargo test` (4/4 pass), `tsc --noEmit`,
> and a full `tauri build` all pass — the release `.app` was built successfully
> and launched standalone (ran for 40+ seconds without crashing); only the DMG
> packaging step was killed early on explicit instruction ("don't build the
> app, just run it") since that step's sandboxed-shell/`hdiutil` limitation was
> already known-broken since Phase 0 and isn't a new regression. Phase 13
> (ADB) additionally depends on Phase 11's `mirror.rs`/pop-out-window plumbing
> for its scrcpy mirror/camera launchers, and Phase 14's pause depends on
> Phase 13's `AdbState` existing — if hardware testing turns up a bug in
> either, re-check the dependents aren't affected before assuming they're
> clean. The GitHub Actions `mac` job (Phase 15) has NOT been run in CI (no
> tag was pushed) — its correctness rests on local `tauri build` succeeding
> plus the YAML parsing cleanly, not an actual Actions run.

**File hierarchy:** this checkpoint (decisions + status) → `CLAUDE.md`
(standing rules, pointer) → `droiddock-tauri-prd-v3.md` (the actual spec,
source of truth for anything this file summarizes).

---

## Repo layout (verified, not assumed)
```
MAcapp/                          ← real repo root, NOT "MacDroid/" (that
├─ droiddock 2/                    path in early docs was wrong and has
├─ droiddock-android/               been corrected)
├─ droiddock-tauri/
│  ├─ CLAUDE.md
│  ├─ droiddock-tauri-prd-v3.md
│  ├─ checkpoint.md               ← this file
│  ├─ protocol-corpus/            ← sanitized Phase 0.4 capture (gitignored)
│  └─ app/                        ← scaffolded Tauri project lives HERE,
│                                    not at droiddock-tauri/ root
└─ spikes/                        ← throwaway Phase 0.2/0.3 projects
   ├─ spike-a-video-decode/
   └─ spike-b-notifications/
```

## Where things actually are, right now
- **Phase 0 — COMPLETE.** Toolchain installed, scaffold done. Spike A
  (video decode): **PASS** — WKWebView's WebCodecs handles the real
  captured `avc1.42E01E` Annex-B stream with 0 decode errors; no native
  VideoToolbox bridge needed for Phase 11. Spike B (notifications):
  **PASS with a path** — `tauri-plugin-notification` alone can't do reply
  capture; a direct `mac-notification-sys` call (already a transitive MIT
  dep) does, confirmed end-to-end. Both taps that supported these spikes
  (0.4 protocol-corpus logger, 0.2 mirror-frame capture) have since been
  **removed** from `wifi.js` — verified via `git diff` that the file is
  now byte-identical to the last commit.
- **Phase 1 — COMPLETE, then redesigned same day.** Built once as a
  custom-palette shell, then redirected mid-build to **Apple's Liquid
  Glass** direction (see Design decisions below) per explicit request.
  Final state: window (1120×720, min 920×600, `titleBarStyle: Overlay` +
  `hiddenTitle: true`), menu-bar tray (Quit only), sidebar with the 9
  Phase-1 tabs, `config.rs` (token/port/notifications/nativeNotifs/
  deviceName, camelCase on disk), one working `invoke()` round-trip,
  bundle id `com.droiddock.mac` (deliberately distinct from Electron's
  `com.droiddock.local` — separate config/token, fresh QR pairing
  expected, not a bug).
- **Phase 2 — COMPLETE.** `protocol.rs` (full real message vocabulary,
  verified against source — not the PRD's own Part 2 summary, which
  turned out incomplete), `ws_server.rs` (handshake, newest-wins, auth
  timeout, reqId scaffold), `discovery.rs` (UDP, byte-for-byte), Dashboard
  with real QR + manual pairing + live status. **Verified against the
  real Android app during the session** — hello/welcome, newest-wins, and
  the 5s auth timeout each confirmed live via a throwaway WS test client,
  and separately the user's actual phone connected live over LAN and
  showed "Linked over Wi-Fi" on the Dashboard mid-session.

## Phases 3–10 — what got built this batch (unverified, see BATCH NOTE)
New Rust modules in `app/src-tauri/src/`: `clipboard.rs` (NSPasteboard
changeCount watcher + echo guards), `notifications.rs` (dedupe + native banner
via tauri-plugin-notification, reply via mac-notification-sys), `transfer.rs`
(9-byte binary framing `[1B kind][4B id BE][4B seq BE]`, KIND_DATA=1/KIND_THUMB=2,
256KiB chunks, 4MiB backpressure via a BOUNDED outbox channel — the
`bufferedAmount` replacement, two-map recv/send registry to avoid transferId
collisions, phone-push inbound). `ws_server.rs` grew: `push`/`request`/
`request_thumb`/`send_binary`, inbound event forwarding (mirrors wifi.js
`onForward`), binary-frame routing, disconnect `abort_all` for transfers, and
the outbox is now bounded (`OUTBOX_CAP=16`). `config.rs` added `clipboardSync`
(default-true). `lib.rs` exposes ~20 Tauri commands + manages ClipboardGuard/
NotifState + spawns the clipboard watcher. New deps: tauri-plugin-notification,
tauri-plugin-dialog, mac-notification-sys, objc2-app-kit `NSPasteboard` feature,
tokio `fs`/`io-util`; npm `@tauri-apps/plugin-dialog`.

Frontend: new `lib/bridge.ts` (invoke+listen shim, the `window.droid`
replacement), `lib/ui.ts`, `components/Icon.tsx` (inline SVGs — NO lucide dep),
`Toasts.tsx`, `EmptyState.tsx`, `CallOverlay.tsx`, and views `FilesView` /
`PhotosView` / `MessagesView` / `ContactsView` / `CallsView` /
`NotificationsView` / `ClipboardView` / `MediaView` / `SettingsView`. `App.tsx`
rewired with all tabs + event wiring; `Sidebar.tsx` gained Notifications /
Clipboard / Media tabs + live status + notif badge. Ported faithfully but
adapted amber→`--color-accent` for the Liquid Glass look; icons are inline SVG.

**Key compatibility calls (full list in the session's final report):**
- Clipboard: source has NO Auto/Manual mode (PRD said it did) — it's a single
  `clipboardSync` on/off gating both directions. Built to source.
- Phase 9 Calls: rich in-call controls (hang up/mute/speaker/DTMF) are
  ADB-only (Phase 13). Over Wi-Fi we built outbound `action-call` dial + a
  VIEW-ONLY incoming/dialing overlay + static CallsView. Did NOT invent Wi-Fi
  control messages (Android frozen).
- Notifications: can't programmatically close an already-delivered macOS
  banner (no API in either backend) — `notification-removed` clears the in-app
  panel + dedupe entry but not a live banner (it auto-dismisses anyway).
- Media: outbound wire type is `media-cmd` (NOT `media:cmd`, that's the IPC
  name); `value` is always an int (`optInt`): ms for seek, 0..volMax for setvol.
- Backpressure: no `bufferedAmount` in tokio-tungstenite → replaced by the
  bounded 16×256KiB outbox = ~4MiB `MAX_INFLIGHT` window.

## Phase 11 — screen mirroring (unverified, see BATCH NOTE)
New Rust module `mirror.rs`: 5 Tauri commands (`mirror_popout`, `mirror_stop`,
`mirror_focus`, `mirror_set_on_top`, `mirror_input`), an on-demand
`WebviewWindowBuilder`-created pop-out window (label `"mirror"`, 360×760 /
min 200×240, `decorations(false)`, loads `index.html#mirror`), native
aspect-ratio lock via `NSWindow::setContentAspectRatio` (objc2-app-kit,
verified present in the installed 0.3.2 bindings — same raw-AppKit pattern as
`appearance.rs`/`clipboard.rs`), and a `MirrorState` (last-started payload,
for replay if the window is still loading when the phone announces the
stream). `ws_server.rs`: `route_text` gained real `mirror-started/stopped/error`
forwarding (was a Phase-2 no-op); `route_binary` gained real kind-3 handling
(was reserved-but-ignored) — both delegate to `mirror.rs`.

Frontend: ported `MirrorWindow.tsx` (WebCodecs `VideoDecoder` → canvas,
tap/swipe/wheel/keyboard input, back/home/recents/pin/close controls) and
`MirrorView.tsx` (Wi-Fi Mirror card fully wired; ADB Mirror card rendered
inert — real ADB wiring is Phase 13, not invented early). `main.tsx` now
branches on `location.hash === "#mirror"` to mount `MirrorWindow` instead of
`App`, mirroring the Electron reference's `#mirror` route for its pop-out
`BrowserWindow`. `App.tsx`'s Mirror tab swapped its `ViewPlaceholder` for the
real view (deleted `ViewPlaceholder.tsx` — no longer referenced anywhere,
every Phase-1 tab is now wired).

**Key compatibility calls:**
- Binary frame delivery: kind-3 mirror frames are forwarded to the pop-out
  window as `{key, data}` where `data` is base64 (plain `emit`, not a
  `tauri::ipc::Channel`) — simplest correct option; flagged as the first thing
  to swap for a `Channel` if hardware testing shows stutter at 6 Mbps/30fps.
- Faithfully preserved a reference-app quirk rather than "fixing" it: in
  `index.js`, `mirror-started`/`mirror-stopped`/`mirror-error` are forwarded
  ONLY to the pop-out window, never the main window — so the main window's
  Screen-tab card (`MirrorView`) never actually receives `mirror-started` in
  the shipped Electron app either, and its busy spinner only clears via
  `mirror-error` or the user closing the pop-out (which the reference's
  `mirrorWin.on('closed', ...)` DOES forward to the main window as
  `mirror-stopped` — reproduced exactly via `emit_to("main", ...)` in the
  Rust `Destroyed` handler). Not treated as a bug to fix; parity means
  reproducing it.
- No camera-launch entry point added to the UI (that's `CameraView.jsx`,
  Phase 12) even though `MirrorWindow`'s ported component already renders a
  camera-mode UI correctly if invoked — just nothing in Phase 11 triggers it.
- `tauri build` succeeds through `.app` bundling; the DMG step still fails in
  this sandboxed shell (same pre-existing Finder/AppleScript-automation
  limitation noted at Phase 0 — not a Phase 11 regression).

## Phase 12 — camera feed (unverified, see BATCH NOTE)
Almost entirely frontend: `mirror.rs`'s `mirror_popout("camera")` and
`mirror_input` already handled `camera-start{facing:"back"}`/`camera-flip`
generically since Phase 11 (confirmed by re-reading the reference — Electron's
`CameraView.jsx` is itself just two launcher buttons, not a video component;
the actual stream reuses the screen-mirror pop-out/pipeline 1:1, same
`#mirror` route, same kind-3 frames). Added: `CameraView.tsx` (ported Wi-Fi
launcher card + inert ADB card, mirroring `MirrorView.tsx`'s shape), a
`camera` Sidebar/nav tab, and a `camera` icon in `Icon.tsx`. Zero Rust changes,
zero Android changes needed.

## Phase 13 — ADB/scrcpy fallback (unverified, see BATCH NOTE)
New Rust module `adb.rs` (~1400 lines): full-fidelity port of every exported
`adb.js` function — tool resolution (`which` + augmented PATH + candidate
list), auto-download-on-first-use (`reqwest` + rustls, shells out to
`/usr/bin/unzip`/`tar` rather than a new archive crate dep), Homebrew scrcpy
install, `adb track-devices -l` parsing/tracking (own async loop, 2s
respawn backoff), a from-scratch background mDNS/tcp reconnect scheduler
(polling reimplementation of `scheduleMdns`/`runMdnsScan`, not a literal
`setTimeout` port), wireless pairing (code + QR, the latter with its own
cancellable token loop), screenshot, device volume get/set, live call control
(end/mute/speaker/DTMF + state polling), and scrcpy mirror/camera spawn
(detached, own OS window). `AdbState` is the new managed state (tool paths,
device list, tracker/poll handles, qr token, `paused` flag). `Config` gained
`deviceGuid`/`tcpAddr`/`autoReconnect`/`paused_until`. New deps: `reqwest`
(rustls-tls, no OpenSSL), tokio `process` feature.

Frontend: ported `SetupModal.tsx` (scrcpy one-click Homebrew install; adb
itself has no install button — silent background auto-download, matching the
reference exactly) and `WirelessPairModal.tsx` (QR + code-entry tabs, ported
near-verbatim from `WirelessPairModal.jsx`). New `DevicesView.tsx` (ADB
device card / App-Link card / Tools card / Volume slider / Actions list) and
a `devices` Sidebar tab — confirmed against the reference's own `App.jsx`
(`case 'devices': <DevicesView .../>` exists there too, this isn't an
invented tab). `MirrorView.tsx`/`CameraView.tsx`'s previously-inert "ADB
Mirror"/"ADB Camera" cards are now live (spawn scrcpy against the connected
ADB device). `CallOverlay.tsx` gained a full rich in-call UI (mute/speaker/
DTMF/duration, ported from `CallOverlay.jsx`) that a Mac-initiated dial
upgrades into automatically the moment ADB `call-state` polling starts
reporting back — incoming calls stay view-only, exactly like the shipped
Electron app (confirmed `call:startPolling` is dead code there, never invoked
from its renderer, so incoming calls never get the rich overlay in the
reference either).

**Key compatibility calls:**
- **Deliberately NOT ported:** `adb.js`'s `fsTransport()` — the reference
  silently prefers ADB pull/push/list/rm/rename over the Wi-Fi link whenever a
  live device is connected, for Files/Photos. Porting that would mean
  reworking `FilesView`/`PhotosView` from a single Wi-Fi transport into a
  transport-aware one — bigger than "matches current SetupModal/DevicesView
  flow" calls for, and not flagged as deferred anywhere the way calls/mirror/
  camera were. The underlying primitives (`pull`/`push_paths`/`list_dir`/
  `rm`/`rename`/`list_images`/`list_videos`/`list_media`/`file_bytes`/
  `device_info`) are ported in `adb.rs` for fidelity (`#[allow(dead_code)]`,
  documented) but have no Tauri command wired to a UI path. **Explicit,
  known gap** — Files/Photos work over Wi-Fi only, same as Phases 3–10 built
  them; USB/wireless-ADB is a pure speed/reliability upgrade path nothing
  currently uses.
- Mac-initiated "Go Wireless"/"Reconnect"/pairing/screenshot/volume/call
  control all require a live ADB device — same requirement as the reference.
- scrcpy mirror/camera are separate, undecorated OS windows (not embedded),
  matching `spawn(..., {detached:true, stdio:'ignore'})` exactly.
- The background reconnect scheduler is a *polling* reimplementation (checks
  every 1s, tracks its own interval/idle state) rather than a literal
  `setTimeout` chain — functionally equivalent (10s→30s backoff after 5
  idle minutes, resets on any live device), not byte-identical timing.

## Phase 14 — native integration polish (unverified, see BATCH NOTE)
New module `tray.rs`: full tray menu (status line + Pause 1h/8h/indefinite +
Resume + Quit, rebuilt via `tray.set_menu()` on every state change) and
`tauri-plugin-autostart` (LaunchAgent) wired behind two new commands
(`autostart_get`/`autostart_set`). **Both are net-new, not ports** — confirmed
by re-reading `index.js` in full: the Electron app has no `Tray`, no `Menu`,
no `app.setLoginItemSettings` anywhere; Phase 1's Quit-only tray was already
the only precedent.

Pause/resume is genuinely two distinct mechanisms, kept separate on purpose:
1. **Phone-initiated** (existing Android capability): the phone sends
   `{"type":"pause","until":...}` then closes its own socket. `ws_server.rs`
   now actually handles this (`route_text`'s "pause" arm — previously a no-op
   TODO per the BATCH NOTE from Phase 3–10's research). Its only real Mac-side
   effect, matching `wifi.js` exactly, is pausing `adb.rs`'s reconnect
   scanner (`AdbState.paused`) — cleared again on the next successful hello.
   `"resume"` is handled too but is dead code in the shipped Android app
   (`ConnectionManager.resume()` sends no message at all, confirmed via
   source read) — ported for fidelity, harmless.
2. **Mac-initiated** (new, tray/Settings-driven): `Config.paused_until`
   (epoch-ms, persisted, `is_paused()` helper), toggled via the tray or a new
   Settings "Pause DroidDock" control (1h/8h/∞/Resume). Since
   `ConnectionManager.kt` has **zero receive-side handling for an inbound
   pause/resume message** (confirmed via source read — it only ever *sends*
   pause, never *handles* one), a Mac→phone pause command would be silently
   ignored by the frozen Android app. So this pause only mutes local
   reactions: native notification banners (`notifications.rs`) and clipboard
   sync (`clipboard.rs`) both gate on `!cfg.is_paused()` now; the ADB
   reconnect scanner gates on either pause source. A 30s background loop
   auto-resumes once a timed pause's deadline passes (survives an app
   restart mid-pause since it's a persisted timestamp, not a live timer).
   **This interpretation of "Pause/resume 1h/8h/indefinite" was a judgment
   call** given the PRD's "matching the Android pausedUntil semantics" is
   about the *shape* (duration-based, persisted) not a literal reused wire
   message — flag if the intent was actually a Mac→phone message the Android
   app would need a (currently absent) receive handler for.

Frontend: `SettingsView.tsx` gained a SYSTEM section (Launch-at-login toggle +
Pause/Resume buttons); `App.tsx` subscribes to a new `"config"` event so
tray-driven or auto-expired pause changes reflect live outside `setSetting`'s
own return value.

## Phase 15 — packaging & CI (unverified, see BATCH NOTE)
`tauri.conf.json`: added `bundle.copyright` and `bundle.category` (`"Utility"`
— Tauri's own enum, which maps to `public.app-category.utilities` on macOS,
matching the Electron `package.json`'s `mac.category`). Confirmed via source
read: no entitlements file exists or is needed (app ships unsigned/ad-hoc,
same as the Electron precedent — the private AppKit API calls in
`appearance.rs`/`clipboard.rs`/`notifications.rs` are in-process, not
cross-process Apple Events, so they need no entitlements when unsigned).
Signing/notarization stays an explicitly open, undecided question (flagged
below) — not assumed.

`.github/workflows/release.yml`'s `mac` job rewritten to build
`droiddock-tauri/app` via `dtolnay/rust-toolchain@stable` + `Swatinem/
rust-cache@v2` + `tauri-apps/tauri-action@v0` (new, wasn't referenced
anywhere before), same `push: tags: ['v*']` trigger and `permissions:
contents: write` (untouched, shared with the still-present `android` job —
NOT touched). `tauri-action` auto-attaches the `.app`/`.dmg` to the GitHub
Release keyed off the pushed tag, satisfying the acceptance criterion
directly — no separate `softprops/action-gh-release` step needed the way the
old Electron job used one. **The Electron `mac` job's `npx electron-builder`
step is retired from CI** (the Electron app itself still builds fine locally;
it's just no longer what CI publishes) — this is intentional per the PRD, not
an oversight, but flag if `droiddock 2/` was expected to keep shipping in
parallel through Phase 16.

**Verified:** YAML parses cleanly (Ruby's `YAML.load_file`, since no `pyyaml`
was available in this shell). **Not verified:** an actual GitHub Actions run
(no tag was pushed) — `tauri-action`'s exact input names/behavior are trusted
from its documented interface, not confirmed against a live CI execution.

## Phase 16 — parity validation & cutover (checklist written, NOT executed)
See the BATCH NOTE at the top of this file. This phase's own PRD acceptance
criterion is a multi-day, real-hardware, side-by-side run against the
Electron app — nothing about it can be "built." What was actually produced:
`PHASE16_PARITY_CHECKLIST.md` (repo root, sibling to this file) — a
feature-by-feature checklist covering every phase 0–15 feature plus the new
Phase 17–19 features (added retroactively once those existed), ready to run
through whenever hardware time is available. The
Electron app at `droiddock 2/` has **not** been touched, archived, or
retired — cutover has not happened.

**Updated 2026-08-05.** The checklist now tracks two axes rather than one: the
`[ ]` boxes still mean *exercised on real hardware* (all but one still open),
and a separate `code ✔/◐/✘` tag per row records whether the feature is written.
Nearly everything is `code ✔` — the survey found a command + handler + view for
essentially every row — which is worth knowing but is not evidence of parity;
distrusting exactly that inference is why this pass exists. Four rows carry
`code ◐` because a known gap is attached (`media-cmd` int semantics, mirror
window sizing, tray status line). **One row is now genuinely ticked:** the
packaging/CI row, satisfied by the real `v1.0.0` tag and its green jobs.

## Phase 17 — open-in-place with edit-writeback (unverified, see BATCH NOTE)
New Rust module `edit_cache.rs`: double-click a phone file in Files → pulls it
(reusing the existing `transfer::pull`) into
`~/Library/Application Support/DroidDock/edit-cache/<session-uuid>/<hash-
bucket>/<basename>` (each phone path gets its own hash-bucket subdirectory so
two different phone paths sharing a basename, e.g. `DCIM/IMG.jpg` vs
`Download/IMG.jpg`, can never collide on disk), opens it via
`tauri_plugin_opener` (same crate `photo_open` already uses), and watches it
with the `notify` crate (FSEvents) for saves. A generation-counter-per-path
debounce (700ms) plus a per-path `tokio::sync::Mutex<()>` write-lock ensure
only the freshest save gets pushed back and pushes for the same file never
run concurrently. Writeback reuses the existing `transfer::push` (no protocol
changes — Files already has this exact push flow, this just triggers it
automatically). State tracked in a `manifest.json` (atomic temp+rename write,
same pattern `config.rs`/all this project's other on-disk state files use).
A 300MB cache cap evicts oldest already-synced, not-currently-watched files
first (never a `pending`/unsynced one). Failed writebacks (phone offline)
stay flagged `pending` and retry automatically on the next successful
reconnect (hooked into the same hello/welcome point Phase 14's clipboard-
pause-clear already used), guarded against overlapping retry passes by an
RAII-guarded `AtomicBool` (a plain manual `store(false)` was tried first,
found by review to wedge permanently `true` on a panic, then fixed to a
drop-guard). New Tauri command `fs_open_in_place`; new event `edit-sync`
(`{localPath, phonePath, status: "syncing"|"synced"|"pending", error}`); new
command `fs_pending_syncs` (hydrates the Files browser's pending-sync badge
on mount/tab-switch, added after review found the badge was live-event-only
and went blank if a failure happened while the user was on another tab).
`EditCacheState` is `Option`-wrapped managed state (`None` if the app data
dir is unavailable) so nothing panics if it's absent — every call site checks.

**Review findings fixed:** basename collisions across different phone paths
sharing a name (hash-bucket fix), non-atomic manifest writes (temp+rename
fix), eviction picking a currently-watched file as a victim (exclusion fix),
the `retrying`-flag panic-safety gap (RAII guard fix), `.`/`..` basenames
silently mis-resolving the pull destination (sanitize fix), overlapping
concurrent writebacks for the same file racing and letting an older save win
(per-path write-lock fix), eviction silently orphaning a file it failed to
delete (skip-and-retry-others fix), and the pending-badge-blank-after-tab-
switch gap above. 10 unit tests cover the manifest/eviction/sanitize logic.

**Known limitation, not fixed:** a phone that's permanently gone (re-paired
away, factory reset) leaves its still-pending edits pinned in the cache
forever with no user-facing "discard this pending edit" action — would need
a follow-up UI affordance if it ever matters in practice.

## Phase 18 — photo auto-sync (unverified, see BATCH NOTE; first Android change since the freeze)
**Android** (`ConnectionManager.kt`, `BridgeService.kt`): `CAPS` gained
`"photosync"`. `BridgeService` registers a `MediaStore` `ContentObserver` on
both `Images.Media`/`Video.Media` (mirrors the existing `smsObserver`
pattern exactly, 800ms debounce), sending a bare, payload-less
`{"type":"photos-changed"}` ping on any change — **deliberately no item IDs
on the wire and no cursor tracked phone-side**; all diffing lives Mac-side
against a ledger, so a missed ping (offline, race, whatever) can never cause
a permanent gap, only a slightly-late catch-up.

**Mac** (`photo_sync.rs`, new module): on `photos-changed` (gated on a new
`Config.photo_sync_enabled`, default off) or on a caps-gated successful
reconnect (mirrors Phase 17's retry-on-reconnect hook — this covers "went
offline, took photos, reconnected → missed items backfill" for free, since
the phone tracks nothing), re-pages through the **existing** `photos-list`
request (same one the Photos tab already uses), diffs item IDs against a
JSON ledger (`<app data dir>/photo-sync-ledger.json`, atomic per-item
writes), and downloads new items sequentially via the **existing**
`transfer::pull` into `Config.photo_sync_dest` (default
`~/Pictures/DroidDock`, lazily resolved so changing the setting takes effect
next sync). New Tauri command `photo_sync_backfill` (manual "sync everything
already on the phone" action, ignores the enabled toggle, still honors
global Pause). New event `photosync-progress`. `SettingsView.tsx` gained a
PHOTO SYNC section (toggle, folder picker, backfill button, inline progress).

**Review findings fixed:** the ledger was initially a bare
`HashSet<i64>` of MediaStore ids with no per-device scoping — re-pairing a
*different* phone (or a factory-reset same phone, whose MediaStore ids can
restart low) could silently skip genuinely-new photos that collided with an
old device's already-synced ids. Fixed by namespacing the ledger by the
phone's `hello.name` (the only per-phone identifier available on every
connection, ADB-paired or not — `PhoneHandle` gained a `name` field and
`ws_server::current_phone_name()` exposes it to `photo_sync`/`lib.rs`'s
backfill command). Old-format ledger files deserialize as empty (one-time
re-download of already-present files, never data loss) rather than erroring.
Also fixed: a `create_dir_all` failure on the destination folder could leave
the Settings progress indicator stuck at "Syncing 0/N…" forever, since no
terminal event fired — now emits a `done==total`, error-carrying terminal
event so the existing frontend clear-logic (`done >= total`) fires correctly
without needing a frontend change. 6 unit tests added (device-scoping,
ledger round-trip, legacy-format tolerance, `unique_dest` collisions).

**Verified via a real `./gradlew :app:compileDebugKotlin` run** (not just a
read-through) — the Kotlin changes actually compile.

## Phase 19 — reverse file browsing (unverified on hardware, see BATCH NOTE; security-reviewed)
The first feature that widens what a paired phone can reach on the Mac — a
"Mac Files" tab on the phone (Android's first new UI since the freeze) can
browse and pull from specific allow-listed Mac folders (default
`~/Desktop`/`~/Documents`/`~/Downloads`, Settings-editable, new
`Config.mac_fs_roots`).

**Protocol** (explicitly decided, not left ambiguous, per the PRD's own
instruction to do so): phone-originated `mac-fs-list{reqId,path}` /
`mac-fs-pull{reqId,path}`, replied to with `mac-fs-list-result`/`-error` and
`mac-fs-pull-begin{reqId,transferId,size}` → existing KIND_DATA binary
frames under a Mac-allocated `transferId` (reuses `transfer.rs`'s existing
`MAC_ID_BASE` allocator) → `mac-fs-pull-done`/`-error`. The phone's `reqId`
is a `"pfs"`-prefixed string (`"pfs1"`, `"pfs2"`, …) — a namespace
deliberately disjoint from the Mac's own numeric `req_seq`/`thumb_seq`
reqIds and from the existing `"pp${seq}"` phone-push convention, confirmed
by review to never cross-route into the wrong pending-table on either side.
`welcome` gained an additive `caps: Vec<String>` field (`#[serde(default,
skip_serializing_if = "Vec::is_empty")]` — needed the skip-if-empty half
specifically because a real captured corpus message has no `caps` field at
all, and without it round-tripping would inject a spurious `"caps":[]`);
Mac always advertises `["macfs"]`. Android's new tab is entirely absent
(not just disabled) unless `"macfs"` is present, reactively via a
`StateFlow`, and bounces the user back to Home if caps disappear mid-session
(reconnect to an older Mac).

**Mac-side security (`mac_fs.rs`, new module):** `check_root()` canonicalizes
the requested path and every configured root, then requires the target be a
canonicalized descendant of (or equal to) at least one root — rejects
nonexistent paths (canonicalize fails closed), rejects `../`-style
traversal, rejects symlinks planted inside an allowed root that point
outside every root (verified with a real on-disk symlink in a unit test, not
just a string check), and correctly fails closed on an empty root list
(no vacuous-allow bug). 8 unit tests cover inside-root/traversal/outside/
symlink-escape/symlink-to-another-root cases. An independent adversarial
security review (told to assume a compromised paired phone and try to find a
read-outside-allowlist bypass) found **no critical bypass**. It found one
real gap, since fixed: `mac-fs-pull` had no concurrency limit, so a
compromised/broken phone could spam simultaneous pulls with no bound — fixed
with a `tokio::sync::Semaphore` capping concurrent pulls to 4, rejecting
excess requests immediately (`mac-fs-pull-error`) rather than queuing
unbounded waiters.

**Known, pre-existing, NOT fixed as part of this phase:** the review also
surfaced that `route_text`'s reqId fast-path resolves *any* inbound message
whose `reqId` parses as a bare `u64`, regardless of `type` — a malicious
phone could in principle craft a message with a small numeric `reqId` that
gets misrouted into an unrelated in-flight Mac-initiated request (contacts/
sms/fs-list/etc.) instead of its intended handler. **This predates Phase 19
entirely** (it governs every reqId'd request type, not just the new
`mac-fs-*` ones, and Phase 19's own `"pfs"`-prefixed reqIds are immune to it
since they never parse as `u64`) — flagged here because this phase's
security review is what surfaced it, not because this phase caused it. Worth
a dedicated look in a future session; out of scope to fix blind here.

**Android:** `ConnectionManager.kt` gained the caps-read path + a `"pfs"`
pending-reply registry; `TransferManager.kt` gained a parallel receive path
for `mac-fs-pull` downloads (reuses the existing KIND_DATA framing, lands
files at the same hardcoded `/sdcard/Download/` the existing Mac→phone push
path already uses — a deliberate consistency choice, not a new destination
convention); `MainActivity.kt` gained the new Compose tab (copies `FilesTab`'s
structural pattern). **Verified via a real `./gradlew :app:compileDebugKotlin`
run**, twice.

## Full-codebase review session (2026-07-04 evening) — fixes applied + open findings

A separate review session (user: "built till phase 11, facing some issue")
audited the whole app + cross-checked wifi.js/index.js/TransferManager.kt/
ConnectionManager.kt. It ran CONCURRENTLY with the session that landed
Phases 12–19, so its fixes below are already merged into the shared tree
(verified end-state: `cargo build`+`test`, `tsc --noEmit`, and full
`tauri build` incl. DMG all pass).

**Fixed this session (hardware-verify with everything else):**
- **Mirror replay race (the probable "Phase 11 issue"):** pop-out could
  permanently miss `mirror-started` — Rust emitted it right after
  `WebviewWindowBuilder.build()`, before the window's JS was listening
  (Electron replays on `did-finish-load`; Tauri events to a loading webview
  are dropped). Now: `mirror_attach` command — the window registers a raw
  `tauri::ipc::Channel` when mounted and receives the pending
  `mirror-started` as the return value. Frames now ride that channel as raw
  `[flags][payload]` ArrayBuffers (base64-JSON-event path deleted — that was
  the checkpoint's own flagged perf risk). `setContentAspectRatio` moved onto
  the main thread (`run_on_main_thread`; it was called from a tokio worker).
  All mirror debug eprintln/console.log stripped.
- **transfer.rs:** early `fs-push-error`/`fs-cancel` arriving mid-stream was
  swallowed by a `try_recv` matching only Cancel → push hung forever after
  streaming finished. Channel-closed-without-Done no longer renames a
  partial `.part` into place.
- **ws_server.rs:** reconnect-mid-pull wedge fixed (newest-wins takeover now
  runs `transfers.abort_all()` — the evicted socket's own cleanup skips it
  by design); hello take+insert made atomic (two simultaneous hellos could
  leave a ghost connection); `notification` gets the `Date.now()` time
  fallback; each ringing `call` gets a unique `call-<ts>` key + `time` (was
  static `"call-incoming"` — consecutive calls collapsed into one NOTIFS
  card); `reply-result` now emits the wifi-event toast ("Reply sent from
  Mac") — nothing listened to the old `reply-result` event.
- **config.rs:** `save()` is atomic temp+rename (an interrupted in-place
  write corrupts droiddock.json, and `load_or_create` reacts by regenerating
  a FRESH TOKEN = silent unpair); unknown keys round-trip via
  `#[serde(flatten)] extra`.
- **discovery.rs:** token checked against in-memory `AppState` (was
  `load_or_create` per UDP packet — any LAN host could force disk reads, and
  a racing/corrupt read would REGENERATE the token from the discovery path).
- **clipboard.rs:** watcher seeds guards from the startup pasteboard (no
  more unprompted push of the pre-existing clipboard) and skips ticks while
  disconnected (a copy made offline now syncs on reconnect, like Electron).
- **notifications.rs:** keyless notifications get a UUID key (all shared the
  `""` dedupe slot — unrelated keyless notifs suppressed each other); empty
  inline replies are no longer sent (wifi.js gates on `text &&`).
- **tauri.conf.json:** CSP was `null` → now set (`default-src 'self'`,
  `ipc:`/`http://ipc.localhost` connect-src, `data:`/`blob:` img-src). The
  phone supplies strings rendered in the webview; standard second line of
  defense. If anything webview-loaded breaks mysteriously, check the CSP
  first.

**OPEN FINDINGS — ALL SIX NOW FIXED (2026-08-04).** Kept below for the record;
see "Open findings — ALL FIXED" further down for what each fix was. The
`adb.rs` items in this list are the only ones still outstanding.
- **CRITICAL / Phase 19:** the phone's Mac Files tab opens with `path:""`;
  the Mac has no empty-path branch (`canonicalize("")` errors out) and no
  root-discovery message — the feature errors immediately on open.
- **CRITICAL / Phase 17:** writeback reuses `fs-push-begin`, but Android's
  `FileRepo.uniqueDest` NEVER overwrites — every save lands as
  `name (2).ext`, `name (3).ext`, … while reporting "synced"; the original
  file is never updated.
- **CRITICAL / Phase 19:** `mac-fs-pull` streams chunks immediately after
  `mac-fs-pull-begin`; Android registers its receiver in a coroutine AFTER
  replying, so leading chunks can be silently dropped (truncated file saved
  — Android's `MacFsPullReceiver.finish()` has no size check) or the pull
  hangs. Needs the same ready-ack handshake `fs-push` already has.
- **MAJOR / Phase 18:** photo-sync ledger keyed by `hello.name`
  (= "MANUFACTURER MODEL") — two same-model phones share one bucket and
  silently skip each other's photos.
- **MAJOR / Phase 19:** reverse file browsing is ON by default
  (Desktop/Documents/Downloads), has no enable toggle, and ignores the
  Phase 14 pause — inconsistent with photo-sync's opt-in posture.
  (`check_root` itself verified sound: canonicalize-before-compare,
  component-wise `starts_with`, symlink/`..` escapes covered by tests.)
- **adb.rs (fixes were applied by an agent, then clobbered by a concurrent
  sync of this tree; re-apply deliberately):** (1) no `kill_on_drop(true)`
  on `run()`/`run_env()` — every timed-out adb call leaks the child (the 1s
  call poller can accumulate dozens); (2) scrcpy launched via
  `std::process::Command::spawn()` with the Child dropped — zombie per
  session, and scrcpy's console spam inherits app stdio (reference launches
  detached with stdio ignored); (3) the `adb track-devices` child is never
  killed on quit; (4) `parse_volume_get` splits on unanchored `"is "`;
  (5) `extract_ip_port` can pair an IP with a distant later number (the JS
  regex requires adjacency); (6) `device_info`'s 4 adb calls run
  sequentially (reference: Promise.all); (7) screenshot filename deviates
  from the reference format.
- **Minor (Phases 14–19):** tray "Paused until" renders UTC, not local
  time; `mac-fs-list` is awaited inline in the WS read loop (a slow/hung
  root stalls ALL inbound routing — spawn it like `mac-fs-pull`);
  `mac-fs-pull` isn't in `TransferRegistry` (uncancellable, no progress
  events, not covered by disconnect abort); a failed writeback only retries
  on reconnect, never in-session; `photos-changed` during a running sync
  pass is dropped with no re-check afterward; permanently-failing photo-sync
  items re-toast an error on every pass; edit-cache watcher/lock maps only
  shrink via cap-eviction; the SettingsView roots editor accepts relative
  paths that can never match `check_root`.

## UI Tier A — AirSync-inspired shell (2026-08-04; unverified on hardware)

Driven by a comparison against [sameerasw/airsync-mac](https://github.com/sameerasw/airsync-mac);
the full gap table is in `AIRSYNC_GAP_ANALYSIS.md` (repo root, sibling to this
file). **Scope was explicitly "Tier A — the UI", keeping all 13 views**, and
the user's standing instruction for this work was *"do not break my existing
flow — some buttons/tabs have different work"*. So every change below is
either additive or presentation-only; **no view's internals or actions were
modified**. `FilesView`'s double-click split (directory → open, file →
edit-in-place), the Enter-to-send fields, and every existing toggle handler
are untouched.

**The structural change.** AirSync's sidebar *is the phone*; DroidDock's was a
13-row nav list. Now: a 56px icon rail (all 13 destinations, same Connect /
Conversations / Library grouping, labels moved to tooltips) + a 256px phone
panel + content. `Sidebar.tsx` was deleted; its nav vocabulary moved to
`lib/nav.ts` (`ViewId` now lives there — `DashboardView`'s import was
repointed).

**New:** `components/phone/` — `PhoneCard` (the persistent live-phone object),
`ConnectionPill` (+ popover: device, Mac address, ADB serial/mode, pause
state), `PhoneClock` (local `Date()`, minute-boundary ticks — AirSync's
`TimeView` also uses the Mac's own clock, so no protocol addition), `MiniPlayer`
(reuses the existing `media` push / `media-cmd`), `StatusStrip` (battery glyph
drawn to the real percentage, volume popover), `QuickActions`.
`components/Rail.tsx`, `components/Onboarding.tsx` (4 steps, skippable, first
run only), `components/MenubarPanel.tsx`, `lib/nav.ts`, `lib/appearance.ts`.

**Backdrop seam:** AirSync paints the phone's real wallpaper (or current album
art) behind that card. The wire protocol carries neither, so the card renders a
generated aurora keyed off the live macOS accent. `PhoneCard`'s `artwork` prop
is the seam — pass a data URL and it takes over with a cross-fade, no other
change. That's the first thing to wire if Tier B (wallpaper/album-art sync)
ever happens.

**Changed (presentation only):**
- `NotificationsView` — grouped-by-app stacking with per-group expand and a
  toolbar toggle back to the flat list (persisted, `notifStacks`). Defaults to
  grouped. `NotifRow`/`CallRow` and every reply/dismiss action are byte-identical.
- `SettingsView` — one scrolling page became a 6-category two-pane view.
  Categories are a 1:1 remap of the existing sections; **no control changed
  group**. New `Appearance` pane: window-opacity slider (CSS `--app-opacity` on
  `.app-surface`, revealing the already-configured native vibrancy; stored in
  `localStorage`, not `droiddock.json`, since it's a display preference with no
  bearing on the link) + a read-only system-accent swatch.
- `Icon.tsx` — optional `title` prop (renders `<title>`; stays `aria-hidden`
  without one).

**New Rust:** `clipboard::clipboard_push_now` (explicit "send clipboard now" —
deliberately bypasses the `last_seen`/`last_from_phone` echo guards, since
re-sending is what was asked; still honours `clipboardSync` + pause, and still
arms the guards so the next watcher tick doesn't duplicate it).
`tray::menubar_hide` / `tray::open_main_window` + the panel window itself.

**⚠️ The one behavioural change to flag:** the tray icon's **left-click now
opens the menu-bar panel; the Pause/Resume/Quit menu moved to right-click**
(`show_menu_on_left_click(false)`). That's the standard macOS menu-bar-app
split and what AirSync does — no menu item was removed or moved — but it *is* a
different mouse button than before. Two-line revert in `tray.rs::build` if
unwanted.

**Deliberately NOT done:** global drag-and-drop file send. `FilesView` already
registers its own `onDragDropEvent`; a second app-level listener would
double-handle every drop. Left entirely to `FilesView`.

**Verified:** `npx tsc --noEmit` clean, `npm run build` clean, `cargo build`
clean, `cargo test` 23/23 pass. **NOT verified:** anything on real hardware or
even a running app — the phone card's live data (battery, media, ADB pill),
the menu-bar panel's window positioning under the tray icon, and its
click-away dismissal have never been seen rendered. Same standing debt as
Phases 3–19.

## AirSync v4 changelog match (2026-08-04; unverified on hardware)

Checked the v4 "What's new" list item by item. Built the achievable gaps:

- **Notification progress, priority, action buttons.** Android now sends
  `progress`/`progressMax`/`progressIndeterminate`, `priority`, `ongoing`, and
  up to 3 non-reply action labels; `notif-action` fires one back by index (index,
  not label — two buttons can share a label). Two subtleties: the re-post dedupe
  hash now *includes* progress, or every update after the first would be
  swallowed as a duplicate; and the blanket `isOngoing` drop had to make an
  exception for progress notifications, since a download is ongoing by nature
  and was previously discarded outright. Mac-side, `low`/`min` priority and any
  progress notification land in the panel but raise **no banner** — Android
  already judged them background, and a banner per percent is unusable.
- **ADB device picker.** Previously auto-picked whichever device enumerated
  first, silently reassigning every ADB action when a second phone appeared.
  Devices tab now shows a picker when more than one is ready; the explicit
  choice survives until that device disappears.
- **Flex display.** `--new-display=<W>×<H>` for desktop mode, Settings-selectable
  (Auto / 1280×800 / 1920×1080 / 2560×1440).
- **Default mirror mode.** Wi-Fi / ADB / Desktop; the Mirror tab badges the
  chosen route instead of always highlighting Wi-Fi.
- **Menubar unread count.** `●N` beside the menu-bar text, cleared when the
  panel opens. Backfill on connect is deliberately *not* counted, or every
  reconnect would badge the menu bar.
- **Call controls in the menubar panel.** Mute / speaker / hang up without
  raising the main window. ADB-only, same constraint the main overlay has —
  there is no Wi-Fi control message for these and inventing one would need an
  Android receive path that doesn't exist.

**Already had, no work needed:** Bonjour discovery (Tier C), ADB QR pairing,
mirror navigation buttons + keyboard + scroll, wireless mirroring, seekbar sync,
easier app muting, improved app search, new settings UI (Mac), keyboard
shortcuts, efficiency pass, UI tooltips, **device-lost pop-up**.

**Not built, and why:** BLE nearby transport (a whole second transport stack) ·
WebDAV remote browsing (the one substantive gap left) · Quick Share pop-up
(~12k lines of reverse-engineered protobuf) · Apple Intelligence summarisation
(macOS 15.1+ framework for a cosmetic feature) · Control-Centre playback
(needs `MPNowPlayingInfoCenter`) · menu-bar text marquee (we truncate; macOS
gives no marquee for status items) · TouchID for QR scan · Lottie animations,
new app icon, custom error reporting (cosmetic/infra) · native-mirroring
sub-items (our Wi-Fi mirror is a different implementation that already covers
navigation, keyboard and wireless).

**Verified:** `cargo test` **49/49**, `cargo build` zero warnings,
`tsc --noEmit`, `npm run build`, `./gradlew :app:compileDebugKotlin`.

## Menu bar, low-battery alerts, status widget (2026-08-04; unverified on hardware)

The last three AirSync+ items. New `statusbar.rs` holds the phone's
battery/media snapshot that all three read, rather than each re-deriving it.

**Menu-bar customisation.** `Config.menubar_text` (`none`/`battery`/`media`/
`device`), `menubar_battery_style` (`percent`/`bar`/`both`), `menubar_max_len`,
`menubar_album_art` (`none`/`thumb`/`background`, applied in the panel's
now-playing card). New Settings › Menu bar pane; the title repaints immediately
on change and on every `device-info`/`media` push.

> **One AirSync setting is NOT reproduced: menu-bar font size.** Tauri exposes
> only `TrayIcon::set_title`, not the underlying `NSStatusItem` button, so
> there is no supported way to set an attributed title. Rather than ship a
> slider that does nothing, the max-length cap is offered instead — it controls
> how much menu bar the app occupies, which is what the font setting is for.
> If pixel-accurate parity ever matters this needs raw AppKit access to the
> status item, which Tauri doesn't hand out.

**Low battery alerts.** `low_battery_alert` (default **on**) +
`low_battery_pct` (default 20). Fires on a *downward crossing* only: below
threshold, not charging, and not already alerted at or below that level.
Charging or recovering above the threshold re-arms it, so a phone hovering at
the boundary produces one banner per discharge rather than one per
`device-info` push. Honours the global pause. **AirSync markets this but hasn't
shipped it** (its own onboarding marks it "Soon").

**Status widget.** A borderless, always-on-top, drag-anywhere window at
`#widget` (same routing trick as the mirror pop-out and menu-bar panel),
`visible_on_all_workspaces` so it survives a Space switch. Battery bar +
now-playing + transport. Toggle in Settings and in the tray menu; persisted, so
it reopens on restart.

> **This is deliberately not a macOS Widget.** Real ones are WidgetKit — a
> Swift app extension embedded in the bundle — which a Tauri app cannot
> produce. The floating panel delivers the same glanceable value; the Settings
> copy and the component doc both say so rather than implying parity. AirSync
> hasn't shipped its widgets either ("Soon").

**Verified:** `cargo test` **48/48** (7 new, covering battery-style rendering,
char-boundary truncation on multi-byte titles, and the alert's
once-per-discharge/re-arm/pause behaviour), `cargo build` zero warnings,
`tsc --noEmit`, `npm run build`, `./gradlew :app:compileDebugKotlin`.

## Open findings — ALL FIXED (2026-08-04, after Tiers B–D)

The 3 CRITICAL + 2 MAJOR findings that had been sitting unfixed since the
2026-07-04 review session are now closed, plus the pre-existing reqId
misroute. **Still unverified on hardware** — these are code fixes with unit
tests, not a hardware pass.

1. **CRITICAL / Phase 19 — Mac Files errored the instant it opened.** The
   phone's tab opens with `path: ""`, which hit `canonicalize("")` and failed.
   Added `mac_fs::list_roots()`: an empty path now returns the configured
   roots as a synthetic top level. Root entries carry an extra absolute
   `path` field (skip-if-none, so ordinary entries are unchanged) because a
   root's display name is only its basename and the phone has no base to join
   it to; `macFsEntryPath()` prefers that field when present. 3 tests.
2. **CRITICAL / Phase 17 — edit writeback never overwrote anything.** It reused
   `fs-push-begin`, and Android's `FileRepo.uniqueDest` never overwrites, so
   every save landed as `name (2).ext`, `name (3).ext`, … while the Mac
   reported "synced" and the file the user was actually editing was never
   touched. `transfer::push` gained an explicit `overwrite` parameter: `true`
   only from the writeback path, `false` for user-initiated pushes (where
   not clobbering a same-named file is the right default). Android honours it
   in `beginPush`.
3. **CRITICAL / Phase 19 — `mac-fs-pull` dropped its leading chunks.** The Mac
   streamed immediately after `mac-fs-pull-begin`, but the phone only registers
   its receiver *after* handling that reply, so the first chunks went nowhere —
   and `MacFsPullReceiver.finish()` had no size check, so the result was a
   silently truncated file reported as success. Added a `mac-fs-pull-ready`
   ack: the Mac arms a waiter *before* announcing the transfer and blocks until
   the phone confirms its receiver exists. A 5s timeout means an older phone
   build degrades to the previous (racy) behaviour instead of hanging.
   `finish()` now also fails a short file rather than returning it.
4. **MAJOR / Phase 18 — photo-sync ledger collided on same-model phones.** It
   was keyed on `hello.name` (= "MANUFACTURER MODEL"), so two identical phones
   shared one bucket and each skipped the other's photos as already-synced.
   `hello` gained an optional `deviceId` — a UUID the phone generates once and
   persists in `Prefs`. `current_phone_key()` prefers it and falls back to the
   name for older builds. The old `current_phone_name()` accessor was **deleted**
   rather than left in place: two near-identical accessors is exactly how a
   future caller picks the name-keyed one again.
5. **MAJOR / Phase 19 — reverse browsing was on by default.** No toggle, and it
   ignored the global pause. Now `Config.mac_fs_enabled`, **default off**, with
   a Settings switch. The Mac only advertises the `"macfs"` cap while it's on,
   so the phone's tab is absent rather than broken; both handlers re-check the
   flag and the pause per request rather than trusting the advert. While
   fixing this, `mac-fs-list` was also moved off the inbound read loop (it was
   awaited inline — a slow or hung root stalled routing for every other
   feature).
6. **Pre-existing since Phase 2 — reqId fast-path could be hijacked.** Any
   message whose numeric `reqId` matched an in-flight request resolved it,
   regardless of `type`, so a crafted reply could be delivered as the answer to
   an unrelated request (contacts, sms, fs-list…). The pending table now stores
   the expected reply *family* alongside each waiter and verifies it before
   resolving. Family = the request type with a trailing `-begin` stripped
   (`fs-push-begin` is answered by `fs-push`/`fs-push-error`), matched as
   `type == family || type == "family-…"`. 5 tests, including that an untyped
   request can never be resolved.

### `adb.rs` items from the same review — also fixed
These were the last outstanding entries in the old OPEN FINDINGS list, and
three of them are process leaks, so they double as resource fixes:

- **Timed-out adb calls leaked their child process.** `run()` now sets
  `kill_on_drop(true)`; on timeout the future is dropped and the child went on
  running unsupervised. The 1s call-state poller made this a steady leak.
- **Every scrcpy session left a zombie**, and scrcpy's console output inherited
  the app's stdio. All four spawn sites are now detached with stdio nulled
  (matching the Electron reference's `{detached:true, stdio:'ignore'}`), and
  the `Child` is reaped on a detached thread instead of being dropped.
- **`parse_volume_get` split on an unanchored `"is "`** — a device name
  containing "is " (e.g. "Louis Phone") shifted the parse onto the wrong token.
  Now anchored on the last occurrence.
- **`extract_ip_port` could pair an IP with a distant later number.** The JS
  regex it ports requires `ip:port` adjacency; it now does too.
- **`device_info` made 4 sequential adb round-trips** where the reference uses
  `Promise.all` — four ~200ms shells in series was a visibly slow Devices tab.
  Now `tokio::join!`.

Not fixed: the `adb track-devices` child on quit (item 3) — it already has a
`stop` flag and kills itself on the next read, and the screenshot filename
format (item 7), which is cosmetic.

**Verified:** `cargo test` **41/41** (10 new), `cargo build` **zero warnings**,
`tsc --noEmit`, `npm run build`, `./gradlew :app:compileDebugKotlin`.

## Tiers B, C, D — AirSync feature parity (2026-08-04; unverified on hardware)

Built straight through on explicit instruction ("proceed b,c,d one by one").
Full before/after table in `AIRSYNC_GAP_ANALYSIS.md` §5. **Android was
unfrozen** for all three tiers, same as Phases 18–19.

### Tier B — fill the phone card
New protocol, all **caps-gated** so an older phone build simply never receives
the requests: `wallpaper` and `apps-list` are numeric-reqId request/reply pairs
shaped exactly like `photos-list`; `app-icon` answers over the **existing**
KIND_THUMB binary frame (so it inherits the disjoint thumb-reqId namespace, the
12s timeout and the `*-error` path for free); `app-launch` is fire-and-forget.
`hello.caps` gained `"wallpaper"`/`"apps"`.

- **Android:** `AppsRepo.kt` (LAUNCHER-intent query + a `<queries>` manifest
  element rather than the broad `QUERY_ALL_PACKAGES` permission; icons as PNG
  because adaptive icons are transparent outside the mask), `WallpaperRepo.kt`
  (720px JPEG; needs the MANAGE_EXTERNAL_STORAGE the app already holds, and
  fails readably if declined). `MediaRemote` gained album art + a `trackKey` —
  **art rides along only on a track change**, because `push()` fires once a
  second while playing and a ~40 KB base64 image per tick would dwarf every
  other message on the link combined. `NotifListener` now sends `pkg`.
- **Mac:** `wallpaper_get`/`apps_list`/`app_icon`/`app_launch`, an in-memory
  icon cache (`lib/appIcons.ts` — deliberately NOT persisted; a few hundred
  base64 PNGs is the wrong thing to put in localStorage), the Apps grid with
  prefix-ranked search, a recents row, real app icons on notification rows, and
  wallpaper/album-art wired into `PhoneCard`'s `artwork` seam.

### Tier C — connectivity
- **Link quality** (`link_quality.rs`): JSON `ping{t}` → `pong{t}` every 4s,
  EMA-smoothed, published as `good`/`fair`/`weak`/`stalled`. Deliberately not
  OkHttp's protocol ping — that one is answered by the phone's TCP stack whether
  or not the app is processing messages, so it can't detect the case this
  exists for. `stalled` (socket open, phone silent) now drives the lost-link
  strip and tints the connection pill.
- **mDNS** (`mdns.rs`, new dep `mdns-sd`, Apache-2.0/MIT): advertises
  `_droiddock._tcp` as a **third** fallback after known IPs and UDP broadcast.
  UDP broadcast is untouched. **No secret is published** — the TXT record has
  the Mac's name only; auth is still the WS token. Android browses via
  `NsdManager` (`MdnsDiscovery.kt`), which always tears the browse down.
- **AES-256-GCM** (`crypto.rs` + `LinkCrypto.kt`, opt-in, default off):
  HKDF-SHA256 over the pairing token, `{"type":"enc","n":…,"d":…}` envelope,
  fresh random nonce per message. Negotiated `hello.caps` → `welcome.caps`, and
  it engages **only if the user enabled it**, so turning it on can never strand
  an older phone. `welcome` itself is necessarily plaintext (it's the message
  that says encryption is on). **Every** JSON write on both sides was routed
  through one sealing helper — a stray `socket.send(obj.toString())` would
  silently emit plaintext on an encrypted session, which is the one failure mode
  optional crypto must not have. 6 unit tests cover round-trip, token binding,
  nonce non-reuse, tampering, truncation and malformed envelopes.
  **⚠️ Scope, stated in the module doc and the Settings copy: JSON control
  messages only.** Binary frames (file chunks, thumbnails, app icons, mirror
  video) stay in the clear — wrapping them means surgery on the hot
  transfer/mirror loops. This is NOT "end-to-end encryption of everything", and
  nothing in the UI claims it is.

### Tier D
- **Desktop mode** — `adb_desktop` (scrcpy `--new-display`), plus
  `adb_mirror_app` to open one app in its own Mac window (double-click in the
  Apps grid). Needs Android 11+ / scrcpy 2.5+; older combinations fail at spawn
  with scrcpy's own message rather than silently degrading.
- **Mac remote control** (`mac_remote.rs`) — the PRD's parked Phase 20, now
  requested explicitly. **Gated harder than anything else in the app** because
  it moves *authority*, not data: off by default (`Config.remote_control`),
  caps-gated (`"remote"` advertised only while on), re-checked on every inbound
  event, muted by the global pause, and restricted to a fixed key/mouse/scroll
  vocabulary with **no** shell or arbitrary-keycode verb. macOS Accessibility
  permission is a second, OS-enforced gate this code cannot bypass. Android got
  a caps-gated "Remote" tab (trackpad + arrows + text), same absent-unless-
  advertised pattern as Mac Files.
- **Per-app notification muting** — now possible because Tier B added `pkg`.
  Mutes the macOS banner only; the in-app list still records everything.

### Resource pass (requested)
- **~10 stacked `backdrop-filter` layers removed** from the phone card. Each
  blurred element forces its own compositing pass every frame, and they sat over
  an already-opaque backdrop (wallpaper/aurora + scrim) with essentially nothing
  to reveal. Replaced with a flat `.on-glass` fill — visually identical over an
  opaque parent. `backdrop-filter` is kept only for popovers/modals, which float
  over arbitrary app content where the blur is real.
- **Heavy views memoised** (`FilesView`, `PhotosView`, `AppsView`,
  `SettingsView`, `ClipboardView`, `CallsView`). The phone pushes `media` once a
  second while playing; without this, every tick re-rendered thumbnail grids and
  file lists whose props hadn't changed.
- `PhoneClock` ticks on the minute boundary, not every second (59 of 60 renders
  were producing identical output).
- Album art is sent once per track, not per tick (see Tier B).

**Verified:** `npx tsc --noEmit`, `npm run build`, `cargo build` (zero
warnings), `cargo test` **31/31**, `./gradlew :app:compileDebugKotlin`. **NOT
verified:** any of it on real hardware. Nothing in Tiers B–D has been seen
running, and the encryption and remote-control paths in particular have never
completed a real handshake. Same standing debt as Phases 3–19.

## First hardware session (2026-08-04 afternoon) — "the app doesn't work"

The session opened with the app dead: frozen UI, and the phone reconnecting in a
loop being rejected. **Everything below was found by running it, not by
reading it.** Landed as `4257024`, `e981b13`, `c1af853`, `dba74af` on `main`.

### The one bug that caused most of it

`adb.rs::tools_status` locked the same mutex twice inside a single struct
literal:

```rust
ToolsStatus {
    adb: state.adb.lock().unwrap().is_some(),   // guard taken…
    ...
    adb_path: state.adb.lock().unwrap().clone(), // …still alive here
}
```

Temporaries in a struct expression live until the whole struct is built, and
`std::sync::Mutex` is not reentrant, so **the first call to this function
deadlocked forever, holding `AdbState.adb`**. Confirmed with a standalone
`rustc` repro and with a live `sample(1)` of the running process showing two
threads parked in `tools_status → Mutex::lock`.

The cascade is the reason it presented as a *networking* failure:

1. `adb_tools` is a **sync** command, so it runs on the **main thread**. The
   frontend calls it on mount → the macOS main thread froze. That is the
   "nothing reflects on the Mac" symptom.
2. `adb::init → activate_adb → tools_status` blocked a worker on the same lock.
3. Every async worker that then touched the tray (`emit_status →
   statusbar::refresh_title → tray::set_title`, which blocks on the main
   thread) piled up behind it, until the networking runtime had **no thread
   left to poll its IO driver** — at which point `accept()` stopped waking.

**This invalidates a prior conclusion recorded in the code.** The dedicated
networking thread in `lib.rs` was added because `TcpListener::accept()` was
"entered and never woke", and its comment blamed a defect in Tauri's
`async_runtime`. That diagnosis was wrong; it was this lock all along. The
thread is kept (isolation is still worth having) but its comment now records
the real cause, and the dead `RUNTIME`/`install_runtime` helper written for
that theory is deleted. **Do not cite the old claim in future work.**

Regression test: `tools_status_does_not_deadlock_on_itself` runs it on a worker
under a 5s deadline, so a recurrence fails the suite instead of hanging it.

### The recurring theme: everything failed silently

Each of these cost real debugging time purely because nothing was reported.
Treat "the button does nothing" as a bug in its own right.

- **`scrcpy` failures.** All four spawn sites nulled stdio and detached, and
  `spawn()` succeeding only proves the binary is executable. On this machine
  scrcpy died ~20ms after launch (`dyld: Library not loaded:
  …libbluray.2.dylib` — Homebrew had moved `libbluray` to `.3` while `ffmpeg
  7.1_3` was still linked against `.2`; fixed with `brew reinstall ffmpeg`).
  ADB mirror/camera therefore did nothing at all, forever, with no message.
  `reap()` now captures stderr and toasts an exit inside 3s.
- **Screen control.** Every gesture needs the accessibility service; without it
  each one hit `service ?: return@post` while mirror video kept streaming, so
  the Mac looked broken. The phone now replies `control-unavailable`
  (throttled 5s) and the Mac explains it.
- **Port already in use.** `ws_server::run` gave up after ten failed binds with
  only an `eprintln`. A second copy of DroidDock (`/Applications` plus a dev
  build) made the app look completely healthy while never accepting a phone.
  It now says so. **Watch for this** — it is easy to hit during development.
- **Pairing QR could hand out a fake token.** `getPairingInfo` returned
  `demo-token-123` outside Tauri, and `vite` serves the same bundle at
  `localhost:1420` in a plain browser. Scanning *that* QR stores a token the
  Mac will never accept — a strong candidate for the original
  `HANDSHAKE REJECT: token mismatch`. It now rejects rather than inventing one.

### Other fixes

- **`wifi-status` was emit-only.** Nothing could *query* the link state, so any
  view mounting after the phone connected sat at "not linked": the Dashboard on
  every revisit, the menu-bar panel and the widget permanently. Added a
  `wifi_status` command; all four subscribers now **seed from it on mount** and
  keep the listener for changes. A listener alone only reports transitions,
  never the state you arrived in.
- **Drag regions never worked anywhere.** `.drag`/`.no-drag` set
  `-webkit-app-region`, which despite the prefix is a **Chromium extension
  WebKit never implemented** — inert in WKWebView. The frameless mirror pop-out
  and the status widget could not be moved at all, and the main window's header
  carried a comment about "giving the traffic lights a drag region" that had
  never been true. All converted to `data-tauri-drag-region` (applied to label
  elements too — Tauri tests the attribute on the *event target*), plus
  `core:window:allow-start-dragging` in the capability. The CSS rules are
  deleted with a note so nobody reaches for them again.
- **Mirror/camera window sizing.** `apply_aspect` hardcoded a 760px height and
  solved width from the aspect ratio → ~1287px for a landscape camera source,
  with no clamp to the display. It also locked the aspect of the *raw video*
  while the content box additionally holds the 36px bar, so every resize
  letterboxed a little more. Now fits the monitor on whichever axis binds and
  locks the real content ratio.
- **Camera flip was invisible.** It rendered correctly all along, but as a bare
  28px glyph between pin and close it read as "there is no way to switch
  cameras". Now a labelled button that also reports which camera is live.
- **Rail clipped destinations silently.** 15 buttons need ~700px; `minHeight`
  is 600 and the scrollbar was hidden, so entries simply weren't there. Added a
  scroll affordance.
- **Config round-trip is now tested.** A parse failure in `load_or_create`
  falls back to `Config::default()`, which **mints a fresh token and silently
  unpairs the phone**. The file on disk predated most Tier B–D fields, so the
  first settings change would have been the first time the full shape was ever
  written. Two tests cover the round-trip and the legacy 6-key file.
- Restored the CSP that had been set to `null` while debugging (plus
  `ws://localhost:1420`, without which the strict policy silently kills vite
  HMR), and removed the debug scaffolding left in `index.html`/`App.tsx`.

### Hardware verification — the debt is partly paid

For the first time, parts of this stack have been **observed working on the
real phone** (Galaxy S21 FE, Android 16), not just compiled.

- The phone links to the Mac over Wi-Fi and stays linked across app restarts;
  verified against both the dev build and the **release bundle**.
- `npm run tauri build` produces `DroidDock.app` + the `.dmg` — the same
  artifacts CI ships — and the release bundle was launched and linked.
- **Mac→phone screen control works end to end.** Verified with
  **`droiddock-tauri/tools/fake_mac.py`** (kept in the repo — it is the only
  way found to exercise Mac→phone messages without clicking the real UI): a
  ~120-line stand-in for the Mac's WebSocket server that authenticates the
  phone and fires control messages at it. Stop the real app first so it frees
  port 48484, then e.g.
  `python3 tools/fake_mac.py '{"type":"mirror-key","key":"home"}'`.
  - `mirror-key home` (`performGlobalAction`) → phone left My Files for the
    launcher, confirmed via `dumpsys window | grep mCurrentFocus`.
  - `mirror-swipe` (`dispatchGesture`) → notification shade opened, confirmed
    by screenshot diff.
  - With accessibility disabled (`Bound services:{}`), the phone replied
    `{"type":"control-unavailable"}` — the new path, proven.

**The blocker was never the code.** `enabled_accessibility_services` was
`null`. On Android 13+ a **sideloaded** APK cannot enable an accessibility
service until *Settings → Apps → DroidDock → ⋮ → Allow restricted settings* is
tapped, and **reinstalling the APK turns the service off again**. Every
Mac-side tap, swipe and nav press depends on it, and so does auto-clipboard —
which is why the phone's permission row is renamed **"Clipboard & Screen
Control"**; labelling it "Auto Clipboard" is what hid the dependency.

### Release v1.0.0 (shipped)

Tags had reached `v0.9.2` (Electron) while the Tauri app was still `0.1.0`, so
a tag would have published "v1.0.0" containing `DroidDock_0.1.0_aarch64.dmg`.
All version declarations were aligned to **1.0.0** — `tauri.conf.json` (which
names the bundles), `package.json`, `package-lock.json`, `Cargo.toml`, and the
Android `versionName` (with `versionCode` 2 → 3, since Android rejects an
update that doesn't increment it). CI: **both jobs green**, assets
`DroidDock_1.0.0_aarch64.dmg`, `DroidDock_aarch64.app.tar.gz`,
`DroidDock-Android.apk`.

**CI trap worth remembering:** the release was configured as a draft
(`releaseDraft: true` on the mac job) and **published anyway**. Both jobs write
the *same* release for a tag, and `softprops/action-gh-release` defaults to
`draft: false`, so the Android job took the draft the mac job had just created
and published it. Whichever job finishes last decides — **the two settings must
always agree** (fixed in `dba74af`). v1.0.0 is public as a result; converting it
back needs `gh release edit v1.0.0 --draft`.

### Housekeeping

`target/` had grown to **13 GB** of stale incremental artifacts. Cleaned along
with the retired Electron app's `node_modules` (508 MB — its `src/` reference
is untouched), Gradle output and `.DS_Store`s: repo **14 G → 144 M**. Nothing
git-tracked was inside any deleted path (checked first). The Tauri app's
`node_modules` was deliberately kept. **The next `npm run tauri dev` does a
one-time full recompile.**

### Still NOT verified

- Tiers B–D through the real UI: wallpaper, apps grid + icons, opt-in
  encryption, Mac remote control, desktop mode, per-app notification muting.
  The link and screen control are proven; these are not.
- The mirror/camera window sizing fix, and the Dashboard/menu-bar status fix —
  both need eyes on the screen, which ADB can't provide.
- `PHASE16_PARITY_CHECKLIST.md` remains unrun — every behaviour row is still
  open. Only the packaging/CI row is ticked, and that one was satisfied by the
  `v1.0.0` release rather than by a parity session.

## Gap-closing pass (2026-08-05; unverified on hardware)

A cross-check of `AIRSYNC_GAP_ANALYSIS.md` and this file **against the actual
source** turned up items claimed open that were built, items built that no doc
mentioned, and three verified-open minor findings. Fixed the docs, then built
the bounded gaps.

### Doc corrections
- **The gap analysis said notification action buttons + progress bars (U12)
  were still open.** They were built in the v4 pass — `notif_action` is a
  registered command, `NotificationsView` renders both. Row corrected; a future
  session was being told to build something that already ships.
- **`PHASE16_PARITY_CHECKLIST.md` did not actually cover Phases 17–19**, though
  this file claimed it did, and covered none of Tiers A–D. It now has sections
  for Phase 17/18/19, Tiers A–D, the v4 items, the menu-bar/battery/widget work
  and the features below — 49 rows became ~100.
- §2/§3 of the gap analysis are a pre-Tier-A snapshot and read as if nothing is
  built. Banner added pointing at §5/§6 instead.
- New **§6 "Still not built"** ledger, so U16/U18/U19/U20/U7/Phase 20 each carry
  a reason rather than sitting unaccounted between "done" and "skipped".

### Built
- **Mac → phone info sync (F5).** New `mac_info.rs`: `{name, battery, charging,
  hasBattery}` pushed on connect and on a 60s tick. Battery via `pmset -g batt`
  rather than an IOKit interop dependency for one integer — 4 parser tests
  cover laptop/charging/desktop-with-no-battery/garbage. Caps-gated `"macinfo"`
  both ways, `Config.mac_info_sync` **default on** (it only ever *sends*
  read-only status to an already-paired device — deliberately not gated like
  `mac_remote`, which moves authority), honours the global pause. Android shows
  it as a card on Home, cleared on disconnect so a stale reading can't linger.
- **Mac media control from the phone (F6).** A `media` action on the existing
  `remote` message, with its own closed allow-list. Media keys are not virtual
  key codes — they must be posted as an `NSSystemDefined` event with subtype 8,
  so this builds the AppKit event and posts its backing `CGEvent`. That means
  it drives whatever app owns the Mac's now-playing session with **no
  `media-control` CLI** (AirSync shells out to one). Inherits every existing
  gate: off unless `remote_control` is on, caps-gated, pause-muted,
  Accessibility-gated by macOS. **Control only** — reading the Mac's
  now-playing metadata back needs the private MediaRemote framework and wasn't
  attempted.
- **Window-wide drag & drop (U22).** Previously declined because `FilesView`
  registers its own `onDragDropEvent` and a second listener would double-send.
  Resolved by having the app-level listener stand down while Files is the
  active view, read through a ref so the OS-level registration doesn't churn on
  every navigation. FilesView is untouched.

### Verified-open findings, now fixed
- **Tray "Paused until HH:MM" rendered UTC.** The function's own doc comment
  said local. Now `localtime_r` (new direct `libc` dep — already in the tree
  transitively), which handles DST for the instant in question where a fixed
  offset wouldn't.
- **`photos-changed` arriving mid-pass was dropped.** The running pass had
  already built its item list, so those photos waited for an unrelated later
  trigger. Now a `pending` flag: whoever holds the run flag drains it, so a
  burst collapses into at most one extra pass.

### Still open after this pass
`mac-fs-pull` still isn't in `TransferRegistry` (no cancel, no progress, not
covered by disconnect abort); edit-writeback still retries only on reconnect;
the `adb track-devices` child still isn't killed on quit (self-limiting) and
the screenshot filename still deviates (cosmetic). U19 in-card mirroring,
U20 floating navbar, U16, U18's icon variants, U7 haptics and Phase 20 are
listed with reasons in the gap analysis §6.

**Verified:** `cargo build` zero warnings, `cargo test` **61/61**,
`npx tsc --noEmit`, `npm run build`, `./gradlew :app:compileDebugKotlin`.
**NOT verified:** any of it on hardware. In particular nobody has seen the Mac
status card on the phone, and the media keys have never been posted on a Mac
with Accessibility actually granted.

## Retheme — light/dark, warm palette, adjustable glass (2026-08-05; unverified on hardware)

Requested: the rail's icons didn't say what they were, the layout didn't
respond to window width, there was no light theme, and the visual direction
should be warm cream + brown/amber with adjustable glassmorphism.

**Two themes, one token layer.** `index.css` now defines every colour twice —
`:root[data-theme='dark']` (espresso, not the old blue-grey near-black) and
`[data-theme='light']` (cream) — in OKLCH, and a single `@theme` block maps them
onto the `--color-*` names every component already used. No component needed a
colour change to gain a light mode. Contrast was **computed, not eyeballed**:
the light theme's accent/ok/warn had to go considerably darker than they first
looked right, because they carry status text *and* sit under white text, and
both directions owe 4.5:1. `--dd-faint` is the one token below that, and is
documented as decoration-only.

**Accent.** The macOS system accent no longer overwrites `--color-accent`; it
is parked in `--dd-system-accent` and Settings picks which one wins. Otherwise
a Mac set to blue drags one cool button into a warm palette, and switching back
would have needed a restart.

**Glass is one slider, not a checkbox.** It drives blur radius, saturation
push-back, and the alpha of both the chrome and the content surface together —
tuned separately they stop reading as one material. At 0 everything is flat and
opaque with zero compositing cost, which is also the escape hatch if
translucency is unreadable on a given desktop.

> **`tauri.conf.json` now has `transparent: true`.** It was `false`, which is
> why the previously-shipped window-opacity slider did nothing: `.app-surface`
> never consumed `--app-opacity`, and an opaque window has nothing to reveal
> anyway. Glass needs the transparent window to blend against. Flag if this
> causes any rendering oddity — glass 0 makes the surface opaque again without
> touching the config.

**Rail.** Expands to 184px with labels and group headings, collapses to the
56px icon rail. Expanded is the default. The Devices and Apps glyphs were
redrawn (a bare microchip read as "hardware settings"; a sharp 2×2 grid is the
universal "grid view" icon), and "Media" is now "Now playing" — it was
ambiguous next to Photos.

**Responsive.** Breakpoints derived from what the columns cost, not device
sizes: under 1000px the rail drops its labels, under 1120 the phone panel
narrows, under 860 it hides. Both are *overrides* — the user's own choice is
never written over, so widening the window restores exactly what they had, and
the title bar says which one is being suppressed rather than letting a panel
silently vanish.

**Menu-bar panel Space fix.** It was `always_on_top` without
`visible_on_all_workspaces`, and hid on *any* `Focused(false)`. Switching
desktops dragged it to the new Space, showed it, and the ensuing focus change
hid it again — "appears for a second, then goes". Now it's explicitly
all-Spaces, and the blur-hide re-checks focus 180ms later so an incidental
focus change (Space switch, display reconfiguration) doesn't dismiss it while a
real click-away still does.

**Main-window Spaces bug — found and fixed.** The main window's
`collectionBehavior` was `NSWindowCollectionBehaviorDefault` (0), i.e. nothing
explicit, which is why it appeared on whichever desktop you switched to.
`appearance::pin_to_own_space` now sets `Managed | FullScreenPrimary` and
clears `CanJoinAllSpaces`/`MoveToActiveSpace`/`Transient` at setup, on the main
thread. It logs the before→after transition once, so a regression is visible in
stdout rather than silent. (The menu-bar-panel fix above is separate and still
stands; the user reported no problem there.)

**Responsive width bug — found by running it.** `useWindowWidth` seeded from
`window.innerWidth` at first render and only updated on `resize`. In this
webview that first read happens before layout settles, and if the user never
drags the window `resize` never fires — so the stale value stuck and collapsed
*both* the rail and the phone panel on a window with room for them. Now
re-measures on mount and observes `document.documentElement`. Worth knowing for
any future work here: **vite HMR preserves `useState`, so a bad measured value
survives a hot reload** and only a full page reload shows the fix.

**Verified on screen, not just compiled** (2026-08-05, dev build, real phone
linked): dark theme, light theme, expanded rail with labels + group headings,
collapsed rail, the phone panel narrowing at <1120, amber accent throughout,
and the phone card keeping its own dark backdrop in the light theme (correct —
its contents are white-on-glass). One defect found this way and fixed: the
rail's scroll-fade sat on top of the last destination and half-erased its
label.

### Follow-up pass, same day — three defects found by looking at it

**The phone card was stretched, not narrow.** It was `h-full`, so on a tall
window a 224px-wide card grew past 700px — about 1:3.2, narrower than any real
handset, which squeezed the now-playing title to a few characters. Now
`aspect-[10/19] max-h-full`, centred in whatever space is left, and the panel's
wide step went 256 → 288.

**Album art / the now-playing card vanished on any late mount.** `media` was
emit-only *and* the phone attaches `art` only on a track change (a ~40 KB image
per 1s tick would dwarf the whole link — the right call on the wire). So
anything that subscribed mid-track — a webview reload, the menu-bar panel, the
status widget — saw no artwork and no player card until the next song started.
Two fixes, both Mac-side, no protocol change:
1. `statusbar::on_media` now caches the artwork against its `trackKey` and
   re-attaches it to every subsequent tick, so what gets emitted is always a
   complete message rather than one that depends on when you subscribed. The
   `trackKey` check is what stops a paused-then-new-song sequence showing the
   previous album's cover.
2. New `media_state` command; `App` seeds from it on mount. **This is the third
   instance of the same bug shape in this codebase** (after `wifi-status` and
   the Files pending-sync badge): an emit-only event leaves every late
   subscriber rendering an empty state over live data. Worth checking for it
   whenever a new push event is added.

**Right-click showed the WebKit menu** (Reload / Back / Inspect Element) — a
webview artefact leaking through, and "Reload" in particular looks destructive.
`main.tsx` now suppresses `contextmenu` except inside a text field or over a
selection, where a menu is genuinely useful.

### Phone-card controls — recents, and a lock button that can't lie

**The recents row needed to explain itself.** Apps appear there because you
launched them *from the Mac* — it's a local shortcut list, not a view of the
phone's task stack — which is why one can show up without the phone being
touched. It now says so in its tooltip, and hovering **the row** (not each
icon) reveals a × on every entry. Per-icon hover was tried first and rejected:
a control that only appears once you're already over the exact thing you want
is one nobody discovers.

**Lock button, capability-gated.** The status strip's second slot used to hold
the mini-player toggle, which drew a *volume* glyph whenever nothing was
playing — two identical speaker icons side by side, the second doing something
unrelated to sound. That slot is now "lock phone screen" (riding the existing
`mirror-key` message → `GLOBAL_ACTION_LOCK_SCREEN`), and the player toggle
moved to a chevron that points the way the panel will move.

The button is **hidden unless the connected phone advertises `"lock"`**, which
required plumbing the phone's `hello.caps` through `WifiStatus` to the
frontend. Two reasons that matters more than it looks:
- an un-updated APK silently ignores an unknown `mirror-key`, so without the
  gate the Mac shows a button that does nothing — which is exactly what
  happened on first try;
- `GLOBAL_ACTION_LOCK_SCREEN` is API 28+, so the phone only advertises the cap
  on Android 9 or newer.

**There is deliberately no "unlock".** Android exposes no API for it at any
privilege a sideloaded app can reach, and shouldn't — a Mac that could unlock
the phone would defeat the lock screen. The button says "Lock phone screen".

### Phone card sizing — two passes, and why the first was wrong

`aspect-[10/19] max-h-full` looked right in isolation and was wrong in place:
the aspect ratio *capped* the card at what its width allowed, so it sat short
in a column with plenty of height going spare. Reverted to `h-full` with an
explicit `max-h-[720px]`.

**The cap and the panel width are one decision, not two.** 300 × 760 (the first
attempt at "taller") is 1:2.5 — still thinner than any real handset. The
shipped pair is a 352px panel and a 720px cap, giving a ~332 × 720 card at
1:2.17, which is a 6.5" phone. Changing either number alone re-breaks it.

**Legibility over album art.** The scrim was a single vertical gradient that
thinned to 10% black across the middle — exactly where the 68px clock sits.
Over a text-heavy cover the clock and date were unreadable. Now two layers: the
vertical pass for the top/bottom bands where the pill and controls live, plus
`.phone-clock-scrim`, a radial pool centred at 40% height under the clock. A
uniform scrim strong enough for the worst cover would erase the artwork the
card exists to show. The clock also gained a two-stop text shadow (tight for
glyph edges, wide for block separation) and the date went from `white/60` —
invisible on a light cover — to `white/90` with its own shadow.

**The mini player needed an active session, nothing more.** It was gated on
`active && (title || artist)`, which hid it for sessions publishing artwork and
position but no metadata — a browser tab, mostly — even though the transport
and seek bar work fine for those. `MiniPlayer` already falls back to "Unknown
track".

**Verified on screen:** card proportions at 1:2.17, clock and date legible over
a text-heavy album cover, album art present on a fresh mount mid-track, the
Apps grid, Messages, and the rail's scroll fade no longer clipping "Clipboard".
**NOT verified:** the context-menu suppression (couldn't synthesise a
right-click from a script), the glass slider at its extremes, the Settings
Appearance pane, and `system` theme following a live macOS appearance switch.
**Verified:** `cargo build`, `cargo test` 68/68, `tsc --noEmit`, `npm run build`.

## Android crash fixes (2026-08-05) — "DroidDock keeps crashing on my phone"

Reported symptom, no stack trace available (no device attached), so these came
from reading the code. Seven fixes; the first is the one that matches
"crashes repeatedly, by itself".

1. **The foreground service was guaranteed to be killed once a day.**
   `BridgeService` ran as a `dataSync` FGS. Since **Android 15 that type is
   capped at 6 hours per 24**; on expiry the system calls `Service.onTimeout()`
   and, if the service doesn't stop within seconds, kills the process with
   `ForegroundServiceDidNotStopInTimeException`. `onTimeout` was never
   overridden. Worse, the service returns `START_STICKY`, so the system
   relaunched it and `startForeground` from the background with the quota spent
   throws `ForegroundServiceStartNotAllowedException` — crash, restart, crash.
   The phone is on Android 16 with `targetSdk 35`, so this applied.
   **Fix:** switched to **`connectedDevice`** (untimed, and the honest
   description of a long-lived link to an external device), added
   `FOREGROUND_SERVICE_CONNECTED_DEVICE` + `CHANGE_WIFI_MULTICAST_STATE` (a
   prerequisite for that type, and genuinely used by mDNS), kept `dataSync` as
   an OEM fallback, overrode **both** `onTimeout` overloads (Android 15 calls
   the 1-arg, Android 16 the 2-arg, and the base 2-arg does *not* fall through),
   and made `startForeground` degrade through tiers instead of throwing.
2. **Any exception on the OkHttp reader thread killed the process.** OkHttp
   doesn't catch what a listener throws, so a single bad message — or a revoked
   permission inside any of ~40 handlers in `ConnectionManager.onMessage` —
   reached the thread's default handler. Now contained per-message.
3. **`setPrimaryClip` was posted to the main thread unguarded.** Oversized
   clips throw `TransactionTooLargeException`, One UI throws `SecurityException`.
   Guarded, and the clip capped at 200k chars.
4. **`getItemAt(0)` on an empty clip**, in two places (the Files "Send
   Clipboard" button and the QS tile activity). A cleared clip yields a non-null
   `primaryClip` with zero items; `getItemAt(0)` throws. Deterministic crash.
5. `MirrorService.startForegroundNotif` sat outside its `try`.
6. `MdnsDiscovery` leaked a `ServiceInfoCallback` per reconnect round —
   hundreds of live multicast listeners over an evening with the Mac off.
7. `MirrorService.stop()` used `startService`, illegal from the background,
   which is exactly where a Mac-side "stop mirroring" arrives — it failed
   silently and the phone kept casting. Falls back to `stopService`.

**Two regressions caught in the fixes themselves, before shipping:** the first
mDNS rewrite unregistered on the first address-less `onServiceUpdated`, which
would have made WiFi-switch recovery *worse*; and the clipboard cap set
`lastFromMac` to the untruncated text, so echo suppression would have missed and
bounced the clip back to the Mac.

**Not verified on hardware — no device was attached, so which of these was
actually firing is still unknown.** Getting the crash log is still worth doing.

## AirSync mobile parity, Phases 1–4 (2026-08-05; unverified on hardware)

Driven by `future update /airSyncMobile.mp4` + `airsyncmobile.pdf`. Gap analysis
and the full plan live in **`AIRSYNC_MOBILE_GAP.md`**; this is the summary.

The survey's main finding: **most of the request already existed** — media
transport, both clipboard directions, per-app notification muting, mouse/keys/
text remote, manual pairing. The genuinely missing set was smaller than it
looked.

- **Phase 1 — theme + device management.** New `Theme.kt`: `DroidColors`,
  light/dark palettes, `LocalDroidColors`, `ThemeMode` in `Prefs`, segmented
  picker, system-bar polarity following the theme. The 14 palette names in
  `MainActivity.kt` became `@Composable get()` accessors, so ~200 call sites
  were untouched and the compiler proved none read a colour outside a
  composable. Light palette **measured**, not eyeballed: first pass had seven
  pairs at 3.7–4.4:1, retuned until the worst is 4.58:1.
  `KnownDevice` list in `Prefs` (capped 8, keyed by token), `lastSeenAt`
  stamped on link-up with the working IP promoted, Last-Connected-Device card
  with Quick Connect / Disconnect / switch, Available Devices on Connect fed by
  a new `MdnsDiscovery.browse`.
  **Disconnect reuses Pause** rather than a second teardown path, and QR /
  manual / switch-device funnel through one `ConnectionManager.onPaired`.
  `Prefs.clear()` no longer wipes everything — it was destroying `deviceId`
  (which the Mac keys photo-sync on) and the theme choice.
- **Phase 2 — Mac controls.** `lock` (⌘⌃Q via the existing CGEvent path),
  `screensaver` (`ScreenSaverEngine`), `brightness` (`NX_KEYTYPE_BRIGHTNESS_*`,
  same HID path as the media keys), `volume_set` (osascript, absolute — so it
  gets a real slider). Brightness is **stepped, not absolute**: an absolute
  level needs private CoreDisplay. Current volume rides the existing `mac-info`
  push rather than a new message type.
- **Phase 3 — Now Playing.** New `mac_media.rs`. **The private `MediaRemote`
  framework is not usable** — macOS 15.4 gated `MRMediaRemoteGetNowPlayingInfo`
  behind an entitlement Apple doesn't issue — so metadata comes from Music and
  Spotify over AppleScript, plus the active browser tab **only when its host is
  on a media allow-list**. A bank or mail tab is never read; there are tests
  asserting exactly that, and that `youtube.com.evil.test` doesn't match.
  **The player card shows whenever the Mac accepts remote control, not only
  when a title is known** — transport keys are real HID media keys and drive
  every Mac app including YouTube in Chrome, so hiding them when the label is
  unavailable would remove working controls.
  Also: Clipboard tab (session history both directions, memory-only and capped
  — it would otherwise be a plaintext log of passwords and OTPs), Connection QS
  tile, and `requestAddTileService` buttons.
- **Phase 4 — Tailscale + settings.** The Mac already advertised its tailnet
  address (`get_if_addrs` takes every non-loopback v4), so no Mac change was
  needed — but **the phone was throwing it away**: `ips.take(2)`/`take(4)`
  could evict the `100.x` entry, the one address nothing can rediscover since
  both discovery paths are link-local. `trimAddresses` now protects it. Plus an
  "Expand networking" toggle that skips the two link-local probes (~4.5s/round),
  editable device name, local IP readout.
- **Accessibility, answering "does the Mac app ask for the grant?" — it did
  not, and the Settings hint *claimed it would*.** Nothing in the app had ever
  called an API that prompts, so `CGEvent::post` silently discarded every remote
  action and the feature looked broken rather than un-permitted. Added
  `AXIsProcessTrusted` plus a warning row deep-linking to the exact pane. The
  built-in `AXIsProcessTrustedWithOptions` prompt was deliberately *not* used:
  macOS shows it once ever, so a user who dismissed it could never get it back.

### Android finally has a test harness

Previously **none** — no `src/test`, no JUnit dependency, nothing. Added JUnit 4
and nine tests over the address-book rules (`isTailnetAddress`,
`trimAddresses`, `KnownDevice.toPairing`) — the logic deciding whether the phone
can still reach the Mac.

**Mutation-checked rather than trusted:** reverting `trimAddresses` to the old
`take(max)` fails exactly the two tests describing the eviction bug. Wired into
`.github/workflows/release.yml`, which was running no Android tests at all.

### Defects found in this session's own work, before shipping

Worth recording because each was caught by a check rather than by luck:
`Tile.setSubtitle` is API 29 against `minSdk 26` (a `NoSuchMethodError` raised
inside the system's shade process); a `remember` keyed on the device-name field
re-enumerated network interfaces on every keystroke; `mac-info` read the volume
with a blocking `Command::output()` on the async runtime; `MdnsDiscovery.find`
returned the alphabetically-first Mac rather than the one that answered; and a
`clean_title` test caught two transforms short-circuiting instead of composing.

### Status

`./gradlew testDebugUnitTest assembleDebug` (9 tests), `cargo test` 68/68,
`tsc --noEmit`, `vite build` — all clean. **Nothing in either section above is
verified on real hardware.** The tests cover pure logic only; everything visual,
every permission prompt, and the whole `mac-media` AppleScript path are
unexercised. The AppleScript reads will raise macOS Automation dialogs the first
time they touch Music, Spotify or a browser.

**Deliberately not built:** "Use Blur" (needs `RenderEffect`, API 31+, for a
decorative effect that fights the flat card design).

### Phone-card controls pass (2026-08-05, unverified on screen)

**The recents row needed explaining, not just fixing.** It shows apps launched
*from the Mac* — a local list, nothing to do with the phone's task stack — so
an entry can appear without the phone being touched, which reads as "why is
this pinned here". There was also no way off the list except launching eight
other apps to push it off the end. Added `removeRecent()` and a hover ×, plus a
tooltip on the row saying what "recent" means here.

**Two identical speaker icons sat side by side in the status strip.** The
second was the mini-player toggle, which drew a *volume* glyph whenever nothing
was playing. The toggle now draws a chevron pointing the way the panel will
move, and that slot holds a **Lock phone** button instead.

> **Lock only — there is deliberately no unlock.** Android exposes no unlock API
> at any privilege a sideloaded app can reach, and a Mac that could unlock the
> phone would defeat its lock screen. `AccessibilityControl.key("lock")` maps to
> `GLOBAL_ACTION_LOCK_SCREEN` (API 28+; older devices no-op rather than crash).
> It rides the existing `mirror-key` message, so it inherits the same
> accessibility gate and the same `control-unavailable` reply every other
> screen-control action has.

**Four clock styles** (Settings › Appearance › Phone clock): `row` (default),
`stacked` (hour over minute), `mono` (tabular, with seconds), `minimal` (light
weight, no date). Only `mono` ticks every second — the others stay on the minute
boundary, since a display without seconds would waste 59 renders out of 60.

**Verified:** `tsc --noEmit`, `npm run build`, `cargo test` 68/68,
`./gradlew :app:compileDebugKotlin`. **NOT verified on screen:** the lock
button, the recents ×, and the four clock styles — the dev app was in active
use and driving it further would have meant hijacking a window mid-session.

### Phone-card controls + clock styles (2026-08-05, later)

- **Recents row was unexplained.** Apps appeared there with no way to remove
  them and no hint why. It is a *Mac-side* list — apps launched from the Apps
  grid, not the phone's task stack — so the tooltip now says so, and hovering
  the row reveals a × on every icon. Hover is tracked on the row rather than
  per icon deliberately: a control that only appears once you're already over
  the exact icon you want is a control nobody finds.
- **Two speaker glyphs sat side by side.** The second was the mini-player
  toggle, which drew a *volume* icon whenever nothing was playing — an icon
  with no relationship to what the button did. It now draws a chevron pointing
  the way the panel will move, and the freed slot holds a **Lock phone**
  button (`mirror-key{key:"lock"}` → `GLOBAL_ACTION_LOCK_SCREEN`).
- **The lock button is caps-gated, and had to be.** Shipped ungated first and
  it silently did nothing on the installed APK, which is the worst possible
  outcome for a button. The phone advertises `"lock"` only on API 28+ (where
  `GLOBAL_ACTION_LOCK_SCREEN` exists), and the Mac hides the button without it.
  **There is deliberately no "unlock"** — Android exposes no API for it at any
  privilege a sideloaded app can reach, and a Mac that could unlock the phone
  would defeat the lock screen.
- **Four clock styles** (`row`/`stacked`/`mono`/`minimal`) in Settings ›
  Appearance. Only `mono` shows seconds, and only `mono` pays for a 1s tick —
  the others stay on the minute boundary, which is 59 saved renders a minute.

**Verified:** `cargo build`, `cargo test` 68/68, `tsc --noEmit`,
`npm run build`, `./gradlew :app:compileDebugKotlin`. **NOT verified on
hardware:** the lock action itself — it needs an APK built from this tree, and
the phone in use is running an older one (its `hello.caps` has no `"lock"`,
which is exactly why the button is currently hidden rather than dead).

### The player chevron now owns the backdrop too (2026-08-05, later still)

Collapsing the mini player used to leave the album art filling the card, which
contradicted the gesture: you'd said "not now" and the app kept showing you the
cover full-bleed. The chevron now means one thing — *show what's playing, or
don't* — and swaps the backdrop back to the phone's wallpaper on the way.

The design call, since it isn't obvious: the counter-argument is that album art
is useful ambient information when collapsed. It's weaker than it looks,
because collapsing **buys no space** — the card is a fixed frame, the clock
just grows. So the trade isn't "art vs room", it's "art vs a bigger clock",
and tying them is the coherent option.

Two mechanics worth keeping:

- **Both images stay mounted and cross-fade on opacity.** Swapping one `src`
  flashes — the browser drops the old texture before the new one decodes — and
  a full-bleed flash on a toggle reads as a rendering fault. 450ms, deliberately
  unhurried for a whole-backdrop change.
- **`grid-rows-[1fr] → [0fr]`** collapses the player without anyone knowing its
  height; a fixed `max-height` would either clip a two-line title or leave dead
  space under a one-line one. The wrapper's margin animates with it, or the
  collapsed player leaves a 10px ghost gap that makes the whole thing look
  half-finished.

`PhoneCard` now takes `wallpaper` and `albumArt` separately instead of one
pre-resolved `artwork`, because the state that chooses between them
(`playerOpen`) lives inside the card, not at the call site.

**Verified:** `cargo test` 68/68, `tsc --noEmit`, `npm run build`, and both
generated utilities confirmed present in the built CSS (`duration-450`,
`grid-rows-[0fr]` — worth checking, since a silently-dropped arbitrary class
would make the animation simply not happen). **NOT seen running:** the
cross-fade itself. The Spaces fix means the window no longer follows to the
active Space, so scripted screenshots couldn't reach it.

### The accessibility service was mislabelled, and Lock depended on it

Reported as "the lock button needs the *Clipboard* accessibility permission,
and I have to turn that off for my banking apps." Investigating turned up a
worse problem than the one reported.

**One service does everything.** `ClipAccessibilityService.onServiceConnected`
sets `AccessibilityControl.service = this`, so the row labelled **"DroidDock
Clipboard"** was also driving every Mac→phone gesture — tap, swipe, type, back,
home, recents — and the Lock button. Nothing in its name said so.

**Its consent text was wrong.** `a11y_desc` claimed *"Only the clipboard is
used — no screen content is read"*, and the config XML carried a comment
asserting `canRetrieveWindowContent=false`, directly above the attribute set to
`true`. It has to be true — `typeText` finds the focused field via
`findFocus(FOCUS_INPUT)` and calls `ACTION_SET_TEXT`. That description is what a
user reads when deciding to grant the permission, so it is now accurate and the
label is "DroidDock — Clipboard & Screen Control".

**The banking conflict cannot be fixed, only routed around.** Those apps call
`AccessibilityManager.getEnabledAccessibilityServiceList()` and refuse to run if
it is non-empty — they don't inspect *which* service or what it declares.
Narrowing flags, renaming, or splitting into two services changes nothing.
Recorded in `accessibility_config.xml` so the next person doesn't try.

Two routes around it, both shipped:

- **`LockAdmin`** — a device-admin receiver declaring exactly one policy,
  `force-lock`. `DevicePolicyManager.lockNow()` needs no accessibility service,
  so Lock survives with it off. Opt-in and separate; the accessibility path
  still works, so declining costs nothing that existed before. The policy list
  is deliberately one item — the system shows it on the grant screen, and it
  should stay readable in a line.
- **One-tap off** — `AccessibilityService.disableSelf()` behind a "Turn off"
  action in Settings › Permissions. Android has no API to turn one back *on*,
  so that direction deep-links to the Settings page rather than pretending.

`ConnectionManager` tries `LockAdmin.lock()` before the accessibility gate and
falls through when the admin isn't granted. `CAPS` became `caps()` — evaluated
per connection, so granting device admin is reflected at the next handshake
instead of needing an app restart.

**Verified:** `:app:compileDebugKotlin`, `:app:processDebugMainManifest`,
`:app:processDebugResources` all clean. **NOT verified:** any of it on the
phone — in particular whether granting device admin actually satisfies the
user's banking apps, which is the assumption the whole `LockAdmin` path rests
on. `:app:assembleDebug` currently fails in this environment on an unrelated
NDK `llvm-strip` toolchain error.

### Settings layout, menu-bar panel, more clock styles

- **Settings rows squeezed their labels to one word.** `Field` was
  `justify-between` with a `shrink-0` control — which the control has to be, a
  squashed segmented picker is unusable — so the label column absorbed every
  pixel the control wanted. A four-option picker ("Desktop display size") left
  the label ~100px wide. Now `flex-wrap` with `min-w-52` on the label, so the
  control drops to its own line at any pane width with no breakpoint to keep in
  sync.
- **Menu-bar notifications drew initials, not app icons.** The main
  Notifications view already resolves `useAppIcon(pkg)`; the panel never did.
  Same cache, so an app you've already seen costs nothing.
- **`menubarAlbumArt` looked like a dead setting — fourth instance of the
  emit-only bug.** The panel assigned `art` only from a `media` event, and the
  phone attaches art only on a track change; a panel opened mid-song therefore
  had none, so "thumb" and "background" rendered identically to "none". Seeded
  from `media_state()` on mount, like `App` and `wifi_status` before it.
  **This exact shape has now bitten four times** (`wifi-status`, the Files
  pending-sync badge, `media` in `App`, and here): a window or view that mounts
  after an emit-only event renders an empty state over live data. New push
  events should ship with a query command in the same change.
- **Panel entrance animation** — a 180ms drop-and-settle from under the menu
  bar, so it reads as coming out of the icon rather than blinking into place.
- **Four more clock styles**: `neon`, `outline`, `pixel`, `gradient`, all
  accent-tinted. They differ only in how the glyphs are *painted*, so they're a
  CSS class on the same tree rather than four more branches. Each owns its own
  shadow — an outline has no fill to shadow and a gradient's fill is
  transparent, so the shared `text-shadow` is applied only when none of them is
  active. No pixel font is bundled (new font, new licence); the pixel read comes
  from geometry — mono family, wide tracking, single-axis slab shadow.

**Verified:** `cargo build` (0 errors), `cargo test` 70/70, `tsc --noEmit`,
`npm run build`, and all six new classes confirmed present in the built CSS.
**NOT seen running.**

### Close-to-hide, lock-screen wallpaper, per-row dismiss

- **Closing the window stranded the app — the worst bug of the session.** There
  was *no* window-event handling at all. Tauri's default close destroys the
  webview; the tray kept the process alive, so the app sat in the Dock with no
  window and no way back except Force Quit. Fixed as a pair, and it has to be a
  pair: `CloseRequested` on `"main"` calls `prevent_close()` + `hide()`, and
  `RunEvent::Reopen` (the Dock-icon click) shows it again. Hiding without
  handling Reopen strands the window just as thoroughly. Scoped to `"main"` —
  the mirror pop-out relies on `Destroyed` firing to tell the phone to stop
  casting.
- **The phone card wore the wrong wallpaper.** `WallpaperRepo` read
  `WallpaperManager.drawable`, which is the *home* wallpaper. On a phone with
  different home and lock images (the One UI default) the card showed a picture
  the lock screen never displays — while the card is deliberately imitating a
  lock screen. Now tries `getWallpaperFile(FLAG_LOCK)` first; it returns null
  rather than throwing when no separate lock wallpaper is set, which is exactly
  when the home one is correct anyway. **Still fetched once per connection**, so
  changing wallpaper needs a reconnect to show up.
- **Per-notification dismiss existed but was invisible.** It lived in the action
  row under `opacity-0 group-hover:opacity-100`, so "Clear" (all) looked like
  the only option. Added a × in the row header that keeps its layout slot at all
  times and only fades its ink — no reflow on hover, and always findable.
- **Eight clock styles overflowed the settings pane.** A segmented control is a
  single row by convention, which held at 2–4 options and broke at 8. `Choice`
  now wraps and caps at `max-w-md`.
- **`pixel` → `bubble`.** The pixel style tried to fake an LCD font with a slab
  shadow and looked like a mistake. Replaced with the chunky sticker read: fat
  accent fill inside a thick light outline, stacked 2×2. `paint-order: stroke
  fill` is what makes it work — it draws the stroke *under* the fill so the
  outline grows outward; painted on top it eats 6px off every numeral from the
  inside. `outline`'s stroke went 1.6px → 3px, which at a 68px glyph is the
  difference between "unfinished" and "drawn".
- **Desktop mode was pixelated because nothing set a bit rate.** scrcpy defaults
  to 8 Mbps, tuned for a phone-shaped stream; desktop mode drives ~3× the pixels
  at the same budget. Now `--video-bit-rate=16M --max-fps=60`.

**Verified:** `cargo build` (0 errors), `cargo test` 70/70, `tsc --noEmit`,
`npm run build`, `:app:compileDebugKotlin`, `:app:processDebugResources`.
**NOT seen running.**

### Mirror quality is configurable now (both transports)

Both encoders were running on constants nobody had revisited: the phone's
MediaCodec at **6 Mbps / 30 fps / 1280px**, and scrcpy at its own 8 Mbps
default. Neither was chosen for a LAN, and desktop mode drives ~3× a phone
view's pixels on that same budget — which is the whole reason it looked mushy.

One quality group in Settings › Mirroring drives both, because "how good does
the mirror look" is one question to a user even though it reaches two encoders:
bit rate (2–30 Mbps, default 12), frame rate (30/45/60/90, default 60),
resolution cap (default: the phone's own), and a Reset button.

- **Wi-Fi**: `mirror-start` / `camera-start` gained additive `bitrate`/`fps`/
  `maxSize` fields. A phone build that predates them ignores them and keeps its
  old defaults rather than failing to start.
- **ADB**: one `scrcpy_quality_args()` shared by the mirror and desktop
  launchers, so the two can't drift apart.
- **Android**: the request lands on `MirrorService` companion statics rather
  than Intent extras. The message arrives in `ConnectionManager` and the
  encoder is configured three hops later (permission activity → service →
  `startEncoder`), so extras would mean changing four signatures for three
  integers read once. Defaults equal the old hardcoded values.

Values are clamped at `set_setting` (the boundary the frontend writes through),
again in `scrcpy_quality_args`, and again in `MirrorService.setQuality`.

Also: clock sizes went up ~20% — the card grew to 332×720 and the type never
followed, so it read small in it. Settings' content pane gained
`overflow-x-hidden` and tighter minimums; at narrow widths its rows were
spilling sideways *over the phone panel* rather than wrapping.

**Verified:** `cargo build` (0 errors), `cargo test` 70/70, `tsc --noEmit`,
`npm run build`, `:app:compileDebugKotlin`. **NOT seen running**, and the
Wi-Fi quality path in particular has never been exercised end to end.

## Android UI pass — decluttering, two dead buttons, and Settings jank (2026-08-05, late; **partly verified on hardware**)

First session where the phone was unlocked and attached for most of the work,
so several things here are *seen working*, not just compiled. Device:
Samsung SM-G990B2 (S21 FE), Android 15, debug build.

### Two buttons that were genuinely dead, both silently

**1. "Grant" on Lock Without Accessibility did nothing at all.** The intent was
launched with `FLAG_ACTIVITY_NEW_TASK`. `DeviceAdminAdd` returns a result to
its caller, so it refuses to run in a task of its own and finishes itself in
`onCreate`:

```
I/ActivityTaskManager: START u0 {act=android.app.action.ADD_DEVICE_ADMIN flg=0x10000000 …}
W/SecDeviceAdminAdd:  Cannot start ADD_DEVICE_ADMIN as a new task
I/SurfaceFlinger:     Removed ActivityRecord{… SecDeviceAdminAdd}
```

Created and destroyed in the same frame. Nothing throws, so the surrounding
`runCatching` had nothing to catch — the button just looked broken. Now goes
through an `ActivityResultLauncher`, which can only start from the host
activity's task, so the flag cannot creep back in. The constraint is written
into `LockAdmin.enableIntent`'s KDoc because nothing in the API hints at it.
**Verified**: prompt appears and keeps focus; `dumpsys device_policy` shows
`com.droiddock.app/.LockAdminReceiver` active with exactly `force-lock`.

**2. Enter / Space / Esc / Tab felt unresponsive.** `RemoteKey` used Material3's
`Button`, which forces **24dp content padding per side**. At a quarter-row or a
46dp d-pad cell that is 48dp of mandatory padding inside a smaller button, so
labels wrapped to two lines and the caps looked and felt dead — they were in
fact receiving taps the whole time. Replaced with `RemoteKeyBase` (a `Box`, no
imposed padding, 46dp min height) plus haptic feedback on every key: the result
of these keys lands on the *Mac*, so without a local cue there is no evidence
the tap registered. D-pad now uses drawn arrow icons — `↑←↓→` as text renders as
an off-centre hairline on One UI. **Verified on screen.**

### Screen control and auto-clipboard are now separate grants

One switch titled "Clipboard & Screen Control" used to carry both, because both
ride the same accessibility service. They are different grants in a user's mind
and the service never required them to move together. Now three rows:
**Accessibility service** (the prerequisite, with Enable / Turn off),
**Auto clipboard**, **Mac screen control**. New `Prefs.screenControl` mirrors
into `AccessibilityControl.enabled`, which `available()` gates on — so turning
screen control off makes the Mac report control unavailable (an
already-handled path) while auto-clipboard keeps working. Seeded from Prefs in
`ClipAccessibilityService.onServiceConnected`, so it survives a restart.
**Verified on screen.**

Also added `A11yTileService` — a QS tile that switches the service off in one
tap (banking apps refuse to run while *any* accessibility service is enabled).
It drives the pre-existing `AccessibilityControl.disableSelf()`; a duplicate
`turnOff()` written before noticing that was deleted. Android lets a service
disable itself but never enable itself, so the off→on direction can only
deep-link to Settings. The tile service had been registered in the manifest
with **no "Add" row in Settings**, i.e. no way to actually add it — now listed
in Settings › Quick Settings Tiles beside the Connection and Clipboard tiles.

### `control-unavailable` now carries a reason

There are three causes with three different fixes, and the Mac was showing one
message for all of them — pointing at "Settings › Auto Clipboard › Enable",
a row that no longer exists after the split above. The phone now sends
`reason`, and `ws_server.rs` picks the wording:

| `reason` | Fix the message points at |
|---|---|
| `service` | system Settings › Accessibility |
| `disabled` | DroidDock Settings › Permissions › Mac screen control |
| `lock-needs-admin` | grant Lock Without Accessibility (needs no a11y service at all) |

`lock-needs-admin` is only reachable after `LockAdmin.lock` has already
declined, so the admin is definitely not granted. Additive and optional — an
older Mac ignores the field and shows generic text.

### Settings was the only laggy screen, and it was two syscalls

Both sat inside `LazyColumn` items, so they re-ran **every time the row scrolled
back into view**, on the composition thread:

- `ConnectionManager.localIpAddress()` — enumerates every interface and asks
  each whether it is up. Now resolved on `Dispatchers.IO`, seeded from a new
  `lastKnownLocalIp` so a revisit paints immediately instead of blinking
  through a placeholder. (Second bug in this one line: it was originally keyed
  on `name`, re-running per keystroke in the field below it.)
- `clipAccessibilityEnabled()` + `LockAdmin.isActive()` — two binder round
  trips. `deviceAdmin` joined `PermissionSnapshot`; both now arrive from the
  2s off-thread poll and are passed into `ScreenControlRows`. The `tick`
  counter is gone; a `justDisabled` flag keyed on `serviceOn` covers the one
  case the poll can't (`disableSelf()` unbinds asynchronously).

Measured with `dumpsys gfxinfo`, same device and build:

```
Settings entry     90th 32ms   2/12 janky      (was 200ms — worst screen in the app)
Settings scroll    90th 21ms   6.0% janky      (JIT-warmed)
Settings scroll    90th 48ms   14.5% janky     (cold, first pass after install)
```

**The cold/warm gap is mostly JIT in a debug build** — don't read the cold
number as the shipped experience, and expect the first scroll after any
install to be the worst one. Other tabs could not be scroll-profiled for
comparison: they render too little content to scroll at all, which is itself
most of why "other pages feel smooth".

### Smaller

- Settings footer read `DroidDock · 0.9.1`, three releases behind the manifest.
  Now `BuildConfig.VERSION_NAME` (`buildConfig = true` added to
  `buildFeatures` for it) so it cannot drift again.
- `MacNowPlayingCard` play/pause icon is optimistic — `optimistic ?: media?.playing`,
  reset by `remember(media)` — so the glyph flips on tap instead of waiting for
  the Mac to report back.

### Environment trap (cost a build failure)

`~/Library/Android/sdk/ndk/27.0.12077973` is an **aborted download**: two files,
`source.properties` and `.installer/.installData`, no toolchain. AGP believes
that version is installed and fails `stripDebugDebugSymbols` on a missing
`llvm-strip`. Moved to `27.0.12077973.aborted-download`; the complete
`27.1.12297006` beside it is used instead. If a future session sees
`A problem occurred starting process 'command '…/llvm-strip''`, this is it.

### Verified vs not

**Seen working on the phone:** the three split permission rows updating live
from the poll, the device-admin prompt and its resulting `force-lock` grant, the
Mac Remote layout (arrow icons, tall trackpad, single-line Enter/Esc/Space/Tab),
the Settings jank numbers above, all three tiles in the merged manifest.

**Not verified:** the Mac's Lock button actually locking the phone via device
admin — that needs the Mac to send `mirror-key{key:"lock"}` and the phone is the
WS *client*, so it can't be driven from the CLI. Trace from the phone's receive
side if it misbehaves. Also unverified: the new toast strings (**the Mac app
needs a rebuild** — `cargo build` clean, 70/70 tests, but the running binary is
the old one), and Lock/Screensaver/Brightness/Volume driving the Mac.

**Note for the next session:** reinstalling the APK drops the accessibility
grant every time, and Samsung clears `enabled_accessibility_services` with it.
Re-granting over adb is safe *only* after reading the current value — blindly
`settings put` clobbers any other service the user has enabled.

### Settings rows: container queries, and the width cap that made it worse

Two mistakes, one after the other, both mine:

1. `Field` was `justify-between` with a `shrink-0` control, so the label column
   absorbed everything the control wanted and collapsed to one word per line.
2. The fix — capping the control at `max-w-72` so it wrapped sooner — was worse
   than the bug. It forced *four*-option pickers onto two ragged lines even on a
   wide window, so every row looked broken instead of just the eight-option one.

The cap is gone. `Choice` wraps only when it genuinely must, and `Field` now
stacks label-over-control by default, going side by side at `@2xl` — a
**container** query, not a viewport one. That distinction is the point: this
pane's width depends on the phone panel and the rail, not the window, so a
viewport breakpoint would happily put a row side-by-side inside a 300px pane.

Verified in the built CSS that all six `@2xl:` variants and the
`container-type: inline-size` rule actually emit — an arbitrary or unsupported
variant that Tailwind silently drops would leave the layout permanently stacked
with nothing to show for it.

**Verified:** `cargo build` (0 errors), `cargo test` 70/70, `tsc --noEmit`,
`npm run build`, container-query CSS confirmed present. **NOT seen running** —
scripted navigation to the Settings pane kept landing in other apps, and I
stopped rather than keep driving the user's screen.

## Design decisions (final direction — supersedes earlier attempts)
1. ❌ Rejected: carrying over Electron's existing design tokens as-is.
2. ❌ Rejected: per-section/per-tab rainbow color scheme.
3. ❌ Rejected: custom vivid indigo/purple accent — reads as generic
   "AI-generated app" aesthetic.
4. ❌ Rejected: custom "quiet confidence" amber palette — dead, don't
   resurrect the hex values.
5. ✅ **Shipped: Apple's Liquid Glass (macOS Tahoe)**, not an invented
   palette. Two layers:
   - **Layer 1 (native):** NOT a standalone `window-vibrancy` crate dep —
     Tauri 2.11.5 already wraps it internally via `tauri.conf.json`'s
     `windowEffects` config (`effects:["sidebar"]`,
     `state:"followsWindowActiveState"`, `radius:12`). Requires
     `transparent: true` + `macOSPrivateApi: true` + the
     `macos-private-api` Cargo feature. **This forecloses Mac App Store
     distribution — user explicitly accepted that tradeoff**, since
     Phase 15 already plans unsigned GitHub-Releases distribution.
   - **Layer 2 (CSS):** `.glass-chrome` on the sidebar only, never on
     scrolling content, **no `backdrop-filter`** there (native vibrancy
     already supplies the blur — stacking would double up for nothing);
     just a translucent tint + specular-sheen gradient + bright top
     inner-highlight (added after the first pass read as "flat, not
     glass" — the native vibrancy WAS genuinely wired correctly; a
     maximized window against a plain desktop just has very little
     content behind it for `NSVisualEffectBlendingMode::BehindWindow` to
     blur — that's real AppKit behavior, not a bug, and the sheen sells
     the "glass" read regardless).
   - Accent color: `NSColor.controlAccentColor()` via objc2/objc2-app-kit
     (confirmed achievable, fallback `#0A84FF` if the native read ever
     fails) — not invented.
   - Light/dark: `prefers-color-scheme`, real WebKit support, vibrancy
     auto-adapts too.
   - Accessibility: `prefers-reduced-motion` — genuine WebKit support,
     plain CSS media query. **`prefers-reduced-transparency` has ZERO
     WebKit support** (verified via caniuse: unsupported through Safari
     27 / iOS 26.5) — bridged natively instead via
     `NSWorkspace.accessibilityDisplayShouldReduceTransparency()` set as
     a `data-reduce-transparency` attribute.
   - Icons: simple single-tone system-style glyphs (already true of the
     ported Sidebar icon set), tinted with the accent color.
   - Fonts: Electron's CSS pulled Geist/JetBrains Mono from a Google
     Fonts CDN at runtime — that violates zero-egress. Bundling them
     locally (`@fontsource/*`) pulls in OFL-1.1-licensed files, outside
     `CLAUDE.md`'s MIT/Apache-2.0/BSD allowlist. **User chose: system
     font fallback** (`-apple-system`/`ui-monospace`) — zero egress, zero
     new license, close enough visually. Revisit only if pixel-perfect
     Geist branding ever matters.

## Standing project decisions (won't change without explicit re-discussion)
- **Session model:** originally "one phase per session, never auto-chain."
  **Overridden a fourth time for Phases 16–19** (see the top BATCH NOTE) —
  the user explicitly asked for the rest of the PRD built back-to-back
  without waiting for hardware gates. The underlying *reason* for the
  original rule hasn't changed (hardware verification still depends on you,
  still hasn't happened) — only the build sequencing did. Don't read the
  fact that Phases 16–19 exist as code as evidence the verification need
  went away.
- **Testing model:** hardware-only. No mock-phone/corpus-replay tool
  (explicitly declined) — every phase's acceptance criteria needs your
  actual phone. (Phase 2 additionally got throwaway Node.js WS-client
  smoke tests for handshake/newest-wins/timeout — those are one-off
  verification aids run during the session, not a permanent test tool,
  and don't replace your own hardware check.) Phases 18 and 19 additionally
  got a real `./gradlew :app:compileDebugKotlin` compile check each — a
  meaningfully stronger signal than a read-through, but still not hardware
  verification.
- **Phase 13 (ADB/scrcpy fallback) stays in scope.**
- **Android app is no longer frozen** — Phases 18 and 19 both touch
  `droiddock-android/`, per Part 6 of the PRD's own design (post-parity
  phases end the freeze). **This happened before Phase 16 actually passed**
  (see BATCH NOTE) — an explicit override, not an oversight, but it means
  the freeze's original safety property ("nothing Android-side can regress
  while parity is being validated") no longer holds until Phase 16 actually
  runs.
- **Parity first, always** — this project-level principle was explicitly
  overridden for sequencing (Phases 17–19 built ahead of Phase 16 passing),
  but no protocol "improvements" beyond what Phases 17–19's own PRD text
  called for were made — no TLS, no scope creep beyond the three approved
  post-parity features.
- **License/signing/CI hardening** — explicitly deprioritized, personal-
  use app, not worth the overhead right now.
- Every edit to the *existing* Electron app (`droiddock 2/src/main/
  wifi.js`) gets reviewed as a diff before applying and checked against
  git as the safety net. (Confirmed working as intended in Phase 2 — the
  temp-tap removal left the file byte-identical to the last commit.) Phases
  17–19 made zero edits to `droiddock 2/` — it remains completely untouched.

## Immediate next steps, in order

> **Pick up here (2026-08-05, late).** See "Android UI pass — decluttering, two
> dead buttons, and Settings jank" above for what just landed. Three concrete
> things are waiting, in order:
>
> 1. **Rebuild and restart the Mac app.** Rust is clean (70/70) but the running
>    binary predates the `control-unavailable` `reason` field, so it still shows
>    the old toast pointing at a Settings row that no longer exists.
> 2. **Press Lock on the Mac** with the phone's accessibility service off and
>    device admin on (that is the phone's current state). This is the one path
>    that could not be driven from the CLI — the phone is the WebSocket client.
> 3. **Exercise Lock / Screensaver / Brightness / Volume** against the Mac; they
>    need Accessibility granted *on the Mac*, and that grant breaks on every
>    rebuild because the app is ad-hoc signed (`TeamIdentifier=not set`) and TCC
>    keys on the code signature.
>
> Everything else below still stands.

> **Updated 2026-08-05.** The single highest-value action is unchanged and has
> only grown: **attach the phone.** Two sessions of Android work — seven crash
> fixes and four parity phases — are compile-and-unit-test verified only, and
> the crash fixes in particular were written from reading the code because no
> device was attached to pull a stack trace from. Start there:
> `adb logcat` + `dumpsys dropbox` to confirm *which* crash was actually firing,
> then `./gradlew installDebug`. Note the APK signing trap: CI ships a
> **debug**-signed APK from the runner's keystore, so a locally built one will
> not install over it (signature mismatch), and uninstalling loses the pairing
> and the accessibility/notification grants.

**Partly superseded — see "First hardware session (2026-08-04 afternoon)"
above.** The app has now run on real hardware: the Wi-Fi link, the release
bundle and Mac→phone screen control are verified, and the startup deadlock that
made the app appear dead is fixed. The verification debt is smaller but still
real — Phases 3–19 and Tiers B–D are otherwise still compile-verified only, and
the checklist below has not been run. **Start any hardware pass by confirming
the phone's accessibility service is on** (see that section); with it off, a
large share of the app silently does nothing.
1. Run `PHASE16_PARITY_CHECKLIST.md` (repo root) — this now covers Phases
   0–19 in one place and is the single source of truth for what to test, in
   what order. It supersedes the old itemized list that used to live here
   (clipboard → notifications → files → photos → … → mirror → camera → ADB →
   native polish → packaging → the Phase 17/18/19 rows added at the bottom).
2. Do not archive `droiddock 2/` or otherwise treat cutover as done until
   every row in that checklist is checked AND you've run both apps in
   parallel for the multi-day period the PRD's Phase 16 acceptance criterion
   actually asks for.
3. Once Phase 16 genuinely passes, Phases 17–19 are already built — no
   further coding session is needed for them unless the hardware pass turns
   up a bug (entirely possible, since none of Phases 3–19 have run on a real
   phone yet, and 17–19 were built on top of that same unverified
   foundation).
4. The one pre-existing, NOT-yet-fixed issue flagged during Phase 19's
   security review (see that phase's notes above: `route_text`'s numeric-
   reqId fast-path can misroute a crafted reply into an unrelated in-flight
   request) is worth a dedicated look in a future session — it predates this
   batch and wasn't blind-fixed here.

**Session boundary — nothing further was built beyond this point.** The PRD's
Part 4 ends at Phase 16, and Part 6 (post-parity) ends at Phase 19 — that's
the full current spec, and it's now fully built (modulo the hardware
verification debt above). Phase 20 ("reverse control" — mirroring and
controlling the Mac's screen *from* the phone) is explicitly parked in the
PRD ("needs its own PRD section before any session touches it") and was
**not drafted or started** — offered in this session and explicitly declined
for now ("if everything done then stop"). Don't start Phase 20 in a future
session without the user first asking for it and a PRD section for it
existing — it wasn't waved through by this session's "keep going" instruction,
which was scoped to "to the end of the current PRD," not open-ended.

## Open questions not yet resolved (don't let these get silently decided)
- **Phase 14's "Pause DroidDock" interpretation was a judgment call** (see
  Phase 14's compatibility notes above): implemented as a Mac-local "mute
  notifications + clipboard + ADB reconnect" toggle, since the Android app
  has no receive-side handling for an inbound pause/resume message at all. If
  the actual intent was a Mac→phone pause command, that now COULD be added
  (the Android freeze ended at Phase 18) but hasn't been — surface this
  before assuming the current behavior is "done."
- **Files/Photos still Wi-Fi-only** — Phase 13 deliberately did not port
  `adb.js`'s `fsTransport()` dual-transport fallback (ADB pull/push preferred
  over Wi-Fi when a device is plugged in). If USB-speed file transfer turns
  out to matter, that's unscoped additional work, not a Phase 13 bug.
- Notarization / Apple Developer Program membership — still undecided,
  flagged at Phase 15, no decision needed yet.
- The local capture files at `~/Library/Application Support/droiddock/`
  (`protocol-corpus.jsonl`, `mirror-capture.bin`) still exist on disk —
  their taps are removed and their job is done (corpus already extracted
  + sanitized into the repo), but nobody's explicitly asked to delete the
  raw local files yet. Low priority, your call whenever.
- **Phase 16 has not actually passed** — everything above through Phase 19
  is real, compiling, (mostly) reviewed code, but "parity validated and cut
  over" is a hardware-verified state this project has not reached. Don't let
  a future session (or a future you, reading this quickly) treat "Phase 19
  is built" as "the rewrite is done" — `droiddock 2/` is still the thing
  actually relied on until `PHASE16_PARITY_CHECKLIST.md` is run for real.
- **`route_text`'s numeric-reqId fast-path can misroute a crafted reply**
  into an unrelated in-flight Mac-initiated request (surfaced by Phase 19's
  security review, pre-existing since the reqId scheme was first built in
  Phase 2, not introduced by Phase 19's own `"pfs"`-namespaced reqIds, which
  are immune to it). Not fixed here — needs a dedicated look, since the fix
  touches core routing logic shared by every reqId'd message type.
- **Phase 19's `mac-fs-pull` concurrency cap (4 simultaneous transfers)** was
  chosen as a reasonable-sounding default during the post-build security
  review, not derived from any PRD number or hardware measurement — revisit
  if real usage shows it's too tight (slow perceived Mac-Files browsing) or
  unnecessary (single-phone personal use may never approach 4 concurrent
  pulls anyway).
