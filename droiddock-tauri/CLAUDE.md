# CLAUDE.md — DroidDock Tauri Rewrite (Mac client)

**The authoritative spec is `droiddock-tauri-prd-v3.md` in this repo root.** Read Part 1 (compatibility mandate) and Part 2 (protocol invariants) before doing anything; the session protocol is Part 5. If anything here ever conflicts with the PRD, the PRD wins.

## Repo layout
- `droiddock-tauri/` root: this file, the PRD, captured `protocol-corpus/` (gitignored — real personal data), spike verdict notes. Never generated code.
- `droiddock-tauri/app/`: the actual Tauri app (`src/`, `src-tauri/`, `package.json`, `src-tauri/tauri.conf.json`, …), scaffolded starting Phase 0.1. All `cargo`/`npm`/`tauri` commands run from here.

## Standing rules (every session)
- Work exactly one phase per session, in PRD order. Never start a phase before the previous phase's acceptance criteria passed on real hardware (the user verifies — you cannot).
- Propose file structure + approach first; wait for approval before implementing.
- The Android app (`../droiddock-android/`) is the protocol source of truth. Never invent a message schema — read the reference source, or the 0.4 captured corpus, and match it. If both are ambiguous, stop and ask.
- Dependencies: OSI-permissive only (MIT/Apache-2.0/BSD). Zero data egress. No features beyond the current phase.
- End every phase with the Part 1 §6 compatibility report and a smoke `tauri build` (run from `app/`).
- **Never hand-patch a built `.app`.** Copying a bare `cargo build --release` binary into `DroidDock.app/Contents/MacOS/app` produces a window that opens blank white and can't be dragged: `tauri.conf.json` carries `devUrl: http://localhost:1420`, and only a build driven by the Tauri CLI resolves the webview to the embedded `dist/` instead of that URL. No content also means no `data-tauri-drag-region`, which is why "blank" and "won't drag" arrive together. Always `npm run tauri build -- --bundles app` and install the whole bundle. (Re-signing after any such patch also mints a new cdhash, which silently revokes the Accessibility grant — see README's notes section.)

## Reference source (read-only)
- Protocol/server: `../droiddock 2/src/main/wifi.js` · transfer: `.../transfer.js` · ADB: `.../adb.js` · IPC surface: `.../src/preload/index.js`
- Android protocol side: `../droiddock-android/app/src/main/java/com/droiddock/app/` (`ConnectionManager.kt`, `TransferManager.kt`, per-feature repos/services)
- UI to port: `../droiddock 2/src/renderer/src/`
- Captured real message corpus (once Phase 0.4 runs): `./protocol-corpus/*.jsonl` (repo root, sibling to `app/` — reference it from Rust tests as `../../protocol-corpus/`)
