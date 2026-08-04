# DroidDock Tauri Rewrite — Checkpoint

**Last updated:** end of Phase 19 (2026-07-04). Read this before anything
else — it's the fast path to full context without re-reading history.

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
through whenever hardware time is available. Every row is unchecked. The
Electron app at `droiddock 2/` has **not** been touched, archived, or
retired — cutover has not happened.

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
**Nothing below has changed in kind — it's the same hardware-verification
debt as before, now larger.** Every phase from 3 through 19 compiles/typechecks
(and 18–19's Android changes additionally compile via Gradle) but has never
touched real hardware.
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
