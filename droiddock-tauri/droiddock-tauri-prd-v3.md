# DroidDock Mac Client — Tauri 2 PRD v3 (Phase-Wise Build Plan + Post-Parity Features)

**Stack:** Tauri 2 · Rust · React 19 · Tailwind 4
**Mission:** Drop-in replacement for the existing Electron Mac client. The existing Kotlin Android companion app must pair and work with this app **exactly** as it does with the Electron app, with **zero changes on the Android side**. The Android app must not be able to distinguish the old Electron app from the new Tauri app.
**Build model:** One phase = one Claude Code session. Each phase has one testable goal. A phase does not start until the previous phase's acceptance criteria pass on real hardware (verified by the user — Claude Code cannot verify hardware behavior itself).

---

## Part 1 — Compatibility Mandate (applies to every phase)

1. **The Android app is frozen.** Its wire protocol is the fixed contract. All request/response formats, message structures, authentication, endpoints/ports, and data formats stay identical. This freeze holds through Phase 16. Post-parity phases (Part 6) introduce the first Android-side changes — always capability-gated so mismatched app versions degrade gracefully instead of breaking pairing.
2. **The communication method is already known — do not rediscover it.** The apps speak a token-gated plain WebSocket (`ws://`, no TLS) on the LAN, port 48484, carrying JSON control messages plus a binary frame protocol, with UDP broadcast discovery on port 48485. There is no Bluetooth, no HTTP API, no cloud relay. ADB/USB exists only as an optional power-user fallback path (Phase 13). The full invariants are in Part 2; verify against the reference source when in doubt, but do not burn session context re-deriving what is documented here.
3. **Electron-specific APIs are replaced with Tauri/Rust equivalents that preserve external behavior.** The mapping is in Part 3. "External behavior" means: what the Android app observes on the wire, and what the user observes on screen — internal structure is free to differ.
4. **Reuse what is reusable — and only that.**
   - Frontend: **PORT.** React components come over close to verbatim; replace the `window.api` preload bridge with a thin shim backed by Tauri `invoke()` + `listen()` so component code changes stay minimal.
   - Backend: **REWRITE.** `wifi.js` / `transfer.js` / `adb.js` are reimplemented in Rust. Do not run the Node code, embed it as a sidecar, or push protocol logic into the webview.
5. **Nothing gets removed or broken.** Every feature that works between the Electron and Kotlin apps today must work here. Parity first: no new features, no protocol "improvements," no TLS, no schema changes. Anything beyond parity is flagged as a written proposal, never implemented unprompted. Approved post-parity features live in Part 6 and start only after Phase 16 passes.
6. **Every compatibility issue found gets explained.** Each phase ends with a mandatory report: (a) what was implemented, (b) every compatibility assumption made and which reference file it came from, (c) every compatibility issue encountered and how it was resolved, (d) exactly what the user must verify on hardware before the next phase begins.
7. **Never invent a message schema.** When a message shape is ambiguous, read the reference source and match it. If the source itself is ambiguous, stop and ask.

### Reference source (read-only — never modify)
Old repo lives as a sibling directory, one level up from `droiddock-tauri/` (i.e. `../droiddock 2/` and `../droiddock-android/`):
- Protocol + server behavior: `../droiddock 2/src/main/wifi.js`
- File transfer + thumbnails: `../droiddock 2/src/main/transfer.js`
- ADB/scrcpy fallback: `../droiddock 2/src/main/adb.js`
- IPC surface the UI expects: `../droiddock 2/src/preload/index.js`
- Android side of the protocol: `../droiddock-android/app/src/main/java/com/droiddock/app/ConnectionManager.kt` (message dispatch), `TransferManager.kt` (binary protocol), per-feature files (`SmsRepo.kt`, `ContactsRepo.kt`, `PhotoRepo.kt`, `FileRepo.kt`, `MediaRemote.kt`, `NotifListener.kt`, `MirrorService.kt`, `AccessibilityControl.kt`)
- UI to port: `../droiddock 2/src/renderer/src/` (components + `index.css`)

Generated code for this rewrite lives in `droiddock-tauri/app/` (a subfolder, kept separate from this PRD and `CLAUDE.md` at the repo root) — all `npm`/`cargo`/`tauri` commands in every phase below run from there.

---

## Part 2 — Protocol Invariants (the contract, byte-for-byte)

- WebSocket server on port **48484**, `0.0.0.0`, plain `ws://`.
- Auth: phone sends `{"type":"hello","token":…,"name":…,"caps":[…]}`. Validate token against stored config; reply `{"type":"welcome","name":<mac name>}`. Close any socket that hasn't authenticated within **5 seconds**.
- Single phone: a new valid `hello` closes the previous phone's socket ("newest wins").
- Request/response: Mac→phone requests carry an incrementing `reqId`; phone replies echo it; pending requests time out (**8s** default) and reject with an error.
- Capability gating: store `caps` from `hello`; never send a message type the phone didn't advertise a capability for (`caps` absent = old app = empty list = new message types never sent).
- Binary frames: first byte = kind. **Kind 3** = H.264 mirror video frame — `[3][flags][payload]`, flags bit0 = keyframe. Any other leading byte = file-transfer chunk routed to the transfer manager.
- UDP discovery on **port+1** (48485): reply to the exact string `DROIDDOCK:DISCOVER:<token>` with the exact string `DROIDDOCK:HERE`.
- Pairing payload: `droiddock://pair?v=1&name=…&ips=…&port=…&token=…`
- Config: JSON in the app data dir holding `token` (UUID), `port`, prefs — same semantics as Electron's `droiddock.json` (token generated on first launch, persisted).
- Message `type` vocabulary (authoritative list lives in `wifi.js`'s switch + `ConnectionManager.kt`'s `when` block): `hello`, `welcome`, `clipboard`, `notification`, `notification-removed`, `reply`, `reply-result`, `device-info`, `media`, `media-cmd`, `sms-changed`, `call`, `action-call`, `action-sms`, `pause`, `resume`, `ping`/`pong`, `fs-list`, `fs-delete`, `fs-rename`, `photos-list`, `photo-thumb-error`, `phone-push*`, `mirror-start`, `mirror-stop`, `mirror-started`, `mirror-stopped`, `mirror-error`, `camera-start`, `camera-stop`, `camera-flip`, `mirror-tap`, `mirror-swipe`, `mirror-key`, `mirror-text`.

---

## Part 3 — Electron API → Tauri/Rust Replacement Map

| Electron API in use today | Tauri/Rust replacement | External behavior preserved |
|---|---|---|
| `ws` WebSocketServer (Node) | `tokio-tungstenite` server | Same port, handshake, timeouts, framing. Note: no direct `bufferedAmount` equivalent — backpressure needs its own design (resolved in Phase 5, not before) |
| `node:dgram` UDP socket | `tokio::net::UdpSocket` | Byte-identical discovery strings |
| `Notification` (`hasReply`, `on('reply')`) | `tauri-plugin-notification`, or plugin + native `UNUserNotificationCenter` bridge | Per Spike B verdict (Phase 0.3) — inline reply must survive the migration |
| `clipboard.readText()/writeText()` + 1s poll | `tauri-plugin-clipboard-manager` + poll loop | Same 1s cadence, same echo-loop guards |
| `app.getPath('userData'/'downloads')` | Tauri path API / `dirs` crate | Same config file semantics, same Downloads landing dir |
| `BrowserWindow` (hiddenInset, always-on-top mirror window) | Tauri window API (main window + separate mirror window) | Same traffic-light region, same always-on-top phone-shaped pop-out |
| Tray/menu-bar | Tauri tray API | Same background-presence behavior |
| `contextBridge` + `ipcRenderer` preload | `invoke()` commands + `listen()` events behind a `window.api`-shaped shim | React components keep calling the same interface |
| `child_process` spawn (adb, scrcpy) | `tokio::process::Command` | Same auto-download-on-first-use, same Homebrew install trigger |
| WebCodecs `VideoDecoder` (Chromium) | WKWebView WebCodecs **or** native VideoToolbox path | Per Spike A verdict (Phase 0.2) — this is the migration's #1 risk |
| `qrcode` npm package | Keep in frontend (pure JS, runs fine in the webview) | Same pairing QR |
| `electron-builder` | Tauri bundler (`tauri build`) | Same unsigned `.app`, same tag-triggered CI release flow |

---

## Part 4 — Phases

### Phase 0 — Environment, tooling, and validation spikes
**Goal:** Resolve the two migration-killing risks before any real code exists.

- **0.1 Toolchain:** Rust (`rustup`), Xcode CLT, Tauri CLI. Scaffold into `droiddock-tauri/app/` via `npm create tauri-app@latest` (template `react-ts`) — a subfolder so generated code stays separate from this PRD and `CLAUDE.md` at the repo root. Acceptance: hello-world builds and runs.
- **0.2 Spike A — video decode:** Standalone throwaway project. Capture a real 10–30s H.264 Annex-B stream from the current Electron app's mirror session (temporary debug dump). Test WebCodecs `VideoDecoder` in Tauri's WKWebView → canvas. If it fails or stutters: test native VideoToolbox decode → raw frames to webview. **Output: written verdict** naming the chosen path and its cost to Phase 11.
- **0.3 Spike B — replyable notifications:** Standalone throwaway project. Test `tauri-plugin-notification` for macOS reply actions. If unsupported: spike a minimal `UNUserNotificationCenter` native bridge for the reply case only. **Output: written verdict.**
- **0.4 Protocol corpus capture (start in the Electron app today, before the rewrite begins):** add a temporary ~10-line tap in `wifi.js` that appends every inbound/outbound JSON message (plus binary frame kind-bytes and lengths) to a JSONL log; then just use DroidDock normally for a day or two. The captured corpus of real traffic becomes ground truth: Phase 2 writes serde round-trip tests against it, and later phases verify exact shapes (SMS threads, contacts, photos-list responses) offline via `cargo test` instead of guessing from Kotlin and burning hardware-test cycles.

**Gate:** Phase 1 does not start until 0.2 and 0.3 both have written verdicts, and 0.4's tap is capturing (the corpus accumulates passively while the spikes run).

### Phase 1 — App shell
**Goal:** Running menu-bar Tauri app, visual chrome in place, nothing wired to real data.
- hiddenInset-equivalent window, tray icon, sidebar with all tabs present but empty (Dashboard, Devices, Files, Photos, Messages, Contacts, Calls, Mirror, Settings).
- Port design tokens: graphite palette (`#0D0D12`, `#14141B`), Geist + JetBrains Mono, glassmorphism CSS — copy `index.css` close to verbatim.
- Config storage in Rust (`droiddock.json` equivalent: token UUID, port, prefs).
- One working `invoke()` round-trip (return stored config) proving the IPC boundary.
- Set the bundle identifier + icons in `app/src-tauri/tauri.conf.json` now so `tauri build` produces an installable `.app` from Phase 1 onward; end every later phase with a smoke `tauri build` (run from `app/`), so packaging problems surface incrementally instead of all at once in Phase 15.
**Acceptance:** launches, tray shows, sidebar navigates empty shells, config file created with a real token on first run.

### Phase 2 — Protocol types + WebSocket server + discovery
**Goal:** The unmodified Android app completes the `hello`/`welcome` handshake and stays connected.
- `protocol.rs`: serde enum covering the full message vocabulary (Part 2). Read `wifi.js` + `ConnectionManager.kt` for exact shapes. Write serde round-trip tests against the 0.4 corpus — every captured message must deserialize into the enum and re-serialize equivalently before this phase is called done.
- `ws_server.rs`: tokio-tungstenite server replicating auth timeout, token check, newest-wins, `caps` storage, `reqId` request/response with timeout.
- `discovery.rs`: UDP `DISCOVER`/`HERE`, byte-for-byte.
- Dashboard UI: connection status, QR for `droiddock://pair?…`, manual IP/token display.
**Acceptance:** existing Android app pairs via QR or manual entry, shows "linked" on both sides. Nothing else works yet.

### Phase 3 — Clipboard sync
- Two-way clipboard via plugin + 1s watcher; `lastClipFromPhone`/`lastClipSeen` echo-loop guards exactly as `wifi.js` does; Auto/Manual toggle in Settings. Implementation note: Electron reads the full clipboard text every second and string-compares; in Rust, poll NSPasteboard `changeCount` per tick and only read text when it actually changed — identical external behavior, cheaper.
**Acceptance:** copy both directions works, no echo loops, toggle works.

### Phase 4 — Notifications
- Implement per Spike B verdict. `notification` show with dedupe-by-key + content hash (no identical reposts); `notification-removed` dismiss sync; reply flow (`reply` out, `reply-result` feedback); `call` (state `ringing`) → distinct alert + in-app panel forward; `notifications`/`nativeNotifs` config split preserved.
**Acceptance:** phone notification appears on Mac, inline reply round-trips, dismiss syncs, incoming call shows caller ID.

### Phase 5 — File transfer & browser
- **First:** resolve the backpressure design (no `bufferedAmount` in tokio-tungstenite — bounded channel + ack, or manual outstanding-bytes tracking). Decide before writing chunked send. While here, benchmark chunk size / pipelining against the Electron baseline on real hardware (reference target: OEM tools claim ~100 photos in 8s for bulk pulls) — pick the chunk size empirically rather than copying Electron's constants blindly.
- `transfer.rs`: binary chunk framing (avoid kind-3 collision), `fs-list`/`fs-delete`/`fs-rename` handlers, push/pull with progress + cancel matching `transfer.js` semantics.
- Port `FileBrowser.jsx`: drag-and-drop, search, rename/delete, progress UI.
**Acceptance:** drag file Mac→phone with live progress; browse/download/rename/delete/search phone storage.

### Phase 6 — Photos & videos
- `photos-list` (offset/limit), thumbnail transfer, `photo-thumb-error` handling, open full-res in Preview/QuickTime (`open -a`). Port `PhotosView.jsx` + `MediaCard.jsx`. A local Mac-side thumbnail cache keyed by media ID is permitted — internal optimization, zero protocol change, kills thumbnail re-fetch on every tab reopen.
**Acceptance:** grid loads thumbnails progressively; click opens full-res natively.

### Phase 7 — Messages (SMS)
- `sms-changed` push → refetch. Confirm exact thread/send message types from `SmsRepo.kt` + `MainActivity.kt` — read, don't assume. Port `MessagesView.jsx` (2-pane, avatars, search, day dividers, composer).
**Acceptance:** threads load, send from Mac delivers via phone, live incoming updates.

### Phase 8 — Contacts
- Contacts list request/response (shape from `ContactsRepo.kt`). Port `ContactsView.jsx`.
**Acceptance:** list loads, search filters.

### Phase 9 — Calls
- `action-call` outbound (Mac triggers phone dial). Port `CallsView.jsx` + `CallOverlay.jsx`. (Incoming alert already done in Phase 4.)
**Acceptance:** Mac-initiated call dials on phone; incoming overlay shows caller ID.

### Phase 10 — Media remote
- `media` metadata push handling; `media-cmd` outbound (transport + volume). Port the now-playing card.
**Acceptance:** metadata updates live; controls control phone playback.

*Phases 8–10 are deliberately small; if Phase 7 went smoothly they may be run as one combined Claude Code session — keep the three acceptance checks separate regardless.*

### Phase 11 — Screen mirroring
- Implement decode per Spike A verdict. Handle `mirror-start/stop/started/stopped/error`; route kind-3 binary frames (coordinate with Phase 5's transfer path — no collisions). Pop-out always-on-top phone-shaped window (separate Tauri window, port `MirrorWindow.jsx` behavior). Outbound input: `mirror-tap/swipe/key/text` (Android's `AccessibilityControl.kt` handles receipt, unchanged).
**Acceptance:** mirror starts (consent or Auto mode), window pops out, tap/swipe/type/nav from Mac controls phone, closing fully stops the phone's foreground service and clears its cast indicator.

### Phase 12 — Camera feed
- `camera-start/stop/flip`, reusing Phase 11's pipeline. Port `CameraView.jsx`.
**Acceptance:** back/front feed live on Mac; flip works; stop clears phone indicator.

### Phase 13 — ADB fallback (power-user path)
- `adb.rs`: process spawning, ADB auto-download-on-first-use (port logic from `adb.js`), scrcpy Homebrew install trigger, USB device detection in Devices tab.
**Acceptance:** matches current `SetupModal`/`DevicesView` flow.

### Phase 14 — Native integration polish
- **Deep link task removed — verified against source:** the Mac app never registers the `droiddock://` protocol; the URL exists only as the QR payload consumed by the Android app's intent filter (`wifi.js:139`). No `tauri-plugin-deep-link` needed.
- Launch-at-login (`tauri-plugin-autostart`); tray menu completeness (quit/pause/status); Pause/resume 1h/8h/indefinite matching the Android `pausedUntil` semantics — implement the Mac side to the existing contract, do not change it.
**Acceptance:** autostart survives reboot; tray menu complete; pause/resume works from both sides.

### Phase 15 — Packaging & CI
- `app/src-tauri/tauri.conf.json` (bundle ID, icons, entitlements). Signing stays unsigned/right-click-to-open unless the user decides otherwise — notarization is a paid decision, confirm before assuming. Rewrite the `mac` job in `.github/workflows/release.yml` to build from `droiddock-tauri/app/`: Rust toolchain + `tauri build`/`tauri-action`, same `v*` tag trigger.
**Acceptance:** pushing a version tag attaches a `.app`/`.dmg` to the GitHub Release.

### Phase 16 — Parity validation & cutover
- Feature-by-feature checklist from this PRD, verified side-by-side against the Electron app. Run both in parallel on the real daily setup for several days. Only then archive `droiddock 2/` (keep in git history).
**Acceptance:** the Android app cannot distinguish the two; nothing regressed.

---

## Part 5 — Session protocol (every Claude Code session)

1. Open with: "Do Phase N from this PRD file."
2. Claude Code proposes file structure + approach first; user approves before implementation.
3. No touching files that belong to later phases. If a phase is too big mid-session, propose a split — don't sprawl.
4. End with the mandatory Part 1 §6 compatibility report.
5. User verifies acceptance criteria on real hardware before the next phase starts.

---

## Part 6 — Post-Parity Phases (competitor-inspired — DO NOT start before Phase 16 passes)

Origin: gap analysis against OPPO O+ Connect for Mac. Ordered by effort, smallest first. Phase 17 is Mac-only; Phases 18–19 end the Android freeze — from here on, every Android/protocol change must be capability-gated so a version-mismatched pair degrades gracefully (feature hides) and never breaks pairing.

### Phase 17 — Open-in-place with edit-writeback (Mac-only, zero protocol changes)
**Goal:** Double-click a file in the Mac file browser → it opens in its native Mac app → saving pushes the changes back to the phone automatically. (O+ Connect's "edit on Mac, syncs back" experience, without a filesystem mount.)

- Pull the file to a session edit-cache dir (`~/Library/Application Support/DroidDock/edit-cache/<session>/`), open via `open`, watch it with the `notify` crate (FSEvents-backed, MIT license).
- Debounce save events (editors often write multiple times per save); once settled, push the file back to its original phone path via the existing transfer push with overwrite semantics.
- Conflict rule: last-writer-wins. Toast on successful writeback; on failure (phone offline), keep the dirty copy, retry on reconnect, show a "pending sync" badge in the browser row.
- Cache lifecycle: cleared per session; enforce a size cap.
- **No Android changes. No protocol changes.** Uses only existing pull/push paths.

**Acceptance:** open a `.txt` and a `.jpg` from phone storage, edit and save in a Mac app — phone copy updates within seconds; disconnect mid-edit → writeback retries and succeeds on reconnect.

### Phase 18 — Photo auto-sync (first Android change since the freeze)
**Goal:** New photos/videos taken on the phone land automatically in `~/Pictures/DroidDock` on the Mac — an iPhone-Photos-style pipe, fully on-LAN.

- **Android:** `MediaStore` `ContentObserver` in `BridgeService` → debounced new push message `photos-changed` carrying new item IDs since last acknowledged sync point. Advertise cap `photosync` in `hello`.
- **Mac:** Settings toggle + destination picker (default `~/Pictures/DroidDock`). Ledger (SQLite or JSON) of synced MediaStore IDs; on `photos-changed` — or on reconnect, by diffing against the ledger — queue downloads through the existing transfer path with visible progress.
- Rules: sync forward from enable-time by default, with an explicit opt-in "back-fill existing library" action; honors global Pause mode; LAN-only by nature.
- **Caps-gated:** Mac never expects `photos-changed` unless `photosync` is present; an un-updated phone app keeps working exactly as before.

**Acceptance:** take a photo → it appears on the Mac within seconds, phone untouched; go offline, take photos, reconnect → missed items backfill; ledger prevents duplicates across restarts.

### Phase 19 — Reverse file browsing (Mac files from the phone)
**Goal:** A "Mac Files" tab in the Android app to browse and pull files from the Mac — O+ Connect's announced Files-app feature, done the userland way.

- **Protocol:** phone-originated request/response — mirror the `reqId` scheme in the phone→Mac direction (separate ID space or an origin flag; decide explicitly, don't overload the existing one silently). New messages: `mac-fs-list` (path → entries), `mac-fs-pull` (path → binary chunks over the existing frame protocol, direction reversed).
- **Mac capability advertisement:** extend `welcome` with `caps: ["macfs", …]` — an additive JSON field older phone builds ignore safely.
- **Security surface — explicit decision, not a default:** expose only configured roots (Desktop/Documents/Downloads by default, user-configurable in Settings). Never expose `/` implicitly. This is the one post-parity feature that widens what a paired phone can reach on the Mac — treat the root allowlist as a hard invariant like the token check.
- **Android:** new tab (list, breadcrumbs, pull-with-progress into Downloads), reusing the existing transfer progress components. Tab hides entirely when the Mac's `welcome` lacks `macfs`.

**Acceptance:** phone browses Mac Documents and pulls a file with live progress; pairing against an older Mac build (no `macfs` cap) → tab is hidden, everything else unaffected.

### Explicitly rejected from the gap analysis (documented so they don't resurface as scope creep)
- **Off-LAN relay** — conflicts with the zero-egress constraint. Instead: document that DroidDock works over Tailscale/WireGuard for remote access, zero infrastructure added.
- **Drag-from-Mac-into-a-phone-app** — requires OEM remote-desktop split-screen context; not reachable from a userland app. Skip.
- **Reverse control (Mac desktop mirrored to and controlled from the phone)** — legitimate but large (ScreenCaptureKit capture + CGEvent injection + a phone-side viewer). Parked as a candidate Phase 20, needs its own PRD section before any session touches it.
