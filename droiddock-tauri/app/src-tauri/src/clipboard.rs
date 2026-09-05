//! Two-way clipboard sync (Phase 3).
//!
//! Faithful to wifi.js: same 1s cadence, same `{ "type":"clipboard", "text" }`
//! wire shape both directions, same `lastClipFromPhone` / `lastClipSeen`
//! echo-loop guards. The one internal difference (per the PRD's own note) is
//! the poll: Electron reads the full clipboard text every second and string-
//! compares; here we read NSPasteboard's `changeCount` first and only pull the
//! text when it actually changed — identical external behaviour, cheaper.
//!
//! The whole feature is gated by the single `clipboardSync` config flag, which
//! is exactly what the Electron app does (`clipboardEnabled()` guards BOTH the
//! outbound watcher and the inbound write). The PRD mentioned an "Auto/Manual"
//! toggle, but the reference source has no such mode — only this on/off — so
//! parity means one switch, not two. Flagged in the compatibility report.

use crate::ws_server::{self, SharedState};
use objc2_app_kit::{NSPasteboard, NSPasteboardTypeString};
use objc2_foundation::NSString;
use serde_json::{json, Value};
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Manager};

/// Echo-loop guards, mirroring wifi.js's module-level `lastClipFromPhone` /
/// `lastClipSeen`. `last_change_count` is the extra NSPasteboard-changeCount
/// bookkeeping that lets us skip reading unchanged clipboards.
#[derive(Default)]
pub struct ClipboardGuard {
    last_from_phone: Mutex<Option<String>>,
    last_seen: Mutex<Option<String>>,
    last_change_count: Mutex<isize>,
}

fn enabled(app: &AppHandle) -> bool {
    let app_state = app.state::<crate::AppState>();
    let cfg = app_state.config.lock().unwrap();
    // Phase 14: the Mac-initiated tray pause also mutes clipboard sync — the
    // reference has no such Mac-side "quiet hours" concept, so this is new
    // behavior, but a sensible read of "pause DroidDock" than only touching
    // the ADB reconnect scanner.
    cfg.clipboard_sync && !cfg.is_paused()
}

// ── NSPasteboard access (each helper creates + drops its own Retained so no
//    !Send handle ever escapes into an async frame) ─────────────────────────

fn read_change_count() -> isize {
    NSPasteboard::generalPasteboard().changeCount()
}

fn read_clipboard_text() -> Option<String> {
    unsafe {
        let pb = NSPasteboard::generalPasteboard();
        pb.stringForType(NSPasteboardTypeString)
            .map(|s| s.to_string())
    }
}

/// Write text to the pasteboard and return the resulting `changeCount`.
fn write_clipboard_text(text: &str) -> isize {
    unsafe {
        let pb = NSPasteboard::generalPasteboard();
        pb.clearContents();
        let ns = NSString::from_str(text);
        pb.setString_forType(&ns, NSPasteboardTypeString);
        pb.changeCount()
    }
}

// ── Inbound: phone → Mac ─────────────────────────────────────────────────

/// A `clipboard` message arrived from the phone. Write it to the Mac
/// pasteboard, gated by `clipboardSync`, and arm the echo guards so the next
/// poll tick doesn't bounce it straight back. Mirrors wifi.js's receive branch
/// (`lastClipFromPhone = msg.text; clipboard.writeText(msg.text)`).
pub fn on_incoming(app: &AppHandle, raw: &Value) {
    if !enabled(app) {
        return;
    }
    // wifi.js guards on `typeof msg.text === 'string'`.
    let Some(text) = raw.get("text").and_then(Value::as_str) else {
        return;
    };

    let guard = app.state::<ClipboardGuard>();
    *guard.last_from_phone.lock().unwrap() = Some(text.to_string());

    let new_count = write_clipboard_text(text);
    // Our own write bumps changeCount; record it so the next tick sees "no
    // change" and skips entirely — the changeCount equivalent of Electron's
    // lastClipFromPhone string-compare, only cheaper.
    *guard.last_change_count.lock().unwrap() = new_count;
}

// ── Outbound: Mac → phone (explicit push) ────────────────────────────────

/// Send whatever is on the Mac's pasteboard to the phone right now.
///
/// This is the phone card's "send clipboard" action — an explicit user request,
/// so unlike the watcher it deliberately ignores the `last_seen` /
/// `last_from_phone` echo guards: re-sending text the phone already has is
/// exactly what was asked for, not a loop. It still respects `clipboardSync`
/// and the global pause, because those mean "don't move clipboard data", and
/// it still arms `last_from_phone` so the next watcher tick doesn't treat the
/// send as a fresh local copy and duplicate it.
#[tauri::command]
pub async fn clipboard_push_now(
    app: AppHandle,
    state: tauri::State<'_, SharedState>,
) -> Result<(), String> {
    if !enabled(&app) {
        return Err("Clipboard sync is off".into());
    }
    if !ws_server::is_connected(&state).await {
        return Err("No phone linked".into());
    }

    // Read + guard bookkeeping in a sync block so no Retained<NSPasteboard>
    // or MutexGuard is alive across the await below.
    let text = {
        let Some(text) = read_clipboard_text() else {
            return Err("Clipboard is empty".into());
        };
        if text.is_empty() {
            return Err("Clipboard is empty".into());
        }
        let guard = app.state::<ClipboardGuard>();
        *guard.last_seen.lock().unwrap() = Some(text.clone());
        *guard.last_change_count.lock().unwrap() = read_change_count();
        text
    };

    ws_server::push(&state, json!({ "type": "clipboard", "text": text })).await;
    Ok(())
}

// ── Outbound: Mac → phone (1s watcher) ───────────────────────────────────

/// Poll the pasteboard once per second and forward genuine local changes to
/// the phone. Runs for the app's lifetime.
pub async fn run(app: AppHandle, state: SharedState) {
    // Seed the guards from whatever is on the pasteboard at startup, exactly
    // like wifi.js's `lastClipSeen = clipboard.readText()` before watching —
    // without this, the first tick treats the pre-existing clipboard as a
    // fresh copy and pushes it to the phone unprompted.
    {
        let guard = app.state::<ClipboardGuard>();
        *guard.last_change_count.lock().unwrap() = read_change_count();
        *guard.last_seen.lock().unwrap() = read_clipboard_text();
    }

    let mut ticker = tokio::time::interval(Duration::from_secs(1));
    // Parking below can leave the interval arbitrarily far behind. `Burst` (the
    // default) would then fire every missed tick back-to-back on reconnect —
    // an hour offline meaning 3600 immediate pasteboard reads. `Delay` fires
    // once, now, and restarts the cadence from there, which is what "poll every
    // second while linked" actually means.
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;

        // wifi.js's watcher early-returns on `!phone` BEFORE reading, so a
        // copy made while the link is down stays "unseen" and is delivered on
        // reconnect — skipping the whole tick (not advancing changeCount /
        // last_seen) reproduces that.
        if !ws_server::is_connected(&state).await {
            // …and rather than re-ticking into this same `continue` once a
            // second for as long as the Mac sits unpaired, sleep on the link
            // itself. Same semantics, no wakeups: the pasteboard is not read,
            // the guards do not advance, and the first poll after a phone
            // arrives happens immediately instead of up to a second later.
            ws_server::await_connected(&state).await;
            continue;
        }

        // Compute what (if anything) to send in a fully synchronous block, so
        // no Retained<NSPasteboard> / MutexGuard is ever alive across an await.
        let to_send: Option<String> = {
            if !enabled(&app) {
                None
            } else {
                let guard = app.state::<ClipboardGuard>();
                let count = read_change_count();
                let mut last_count = guard.last_change_count.lock().unwrap();
                if count == *last_count {
                    None
                } else {
                    *last_count = count;
                    drop(last_count);
                    decide_outbound(&guard, read_clipboard_text())
                }
            }
        };

        if let Some(text) = to_send {
            ws_server::push(&state, json!({ "type": "clipboard", "text": text })).await;
        }
    }
}

/// wifi.js watcher logic, verbatim:
/// `if (text && text !== lastClipSeen && text !== lastClipFromPhone) { lastClipSeen = text; send } else { lastClipSeen = text }`
/// i.e. `lastClipSeen` always resyncs to the current clipboard; we only send
/// when the text is non-empty and matches neither guard.
fn decide_outbound(guard: &ClipboardGuard, text: Option<String>) -> Option<String> {
    let Some(text) = text else {
        return None;
    };
    if text.is_empty() {
        // Empty clipboard is treated as "nothing to sync" (the `text &&` guard),
        // but still resync last_seen so a later identical copy isn't missed.
        *guard.last_seen.lock().unwrap() = Some(text);
        return None;
    }

    let differs = {
        let last_seen = guard.last_seen.lock().unwrap();
        let last_from_phone = guard.last_from_phone.lock().unwrap();
        last_seen.as_deref() != Some(text.as_str())
            && last_from_phone.as_deref() != Some(text.as_str())
    };

    *guard.last_seen.lock().unwrap() = Some(text.clone());
    if differs {
        Some(text)
    } else {
        None
    }
}
