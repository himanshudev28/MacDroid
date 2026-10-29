//! Notification mirroring (Phase 4).
//!
//! Implements per the Spike B verdict: plain (non-reply) notifications go
//! through `tauri-plugin-notification`; replyable ones go through a direct
//! `mac-notification-sys` call (whose `NSUserNotificationCenter` path is the
//! only one that captures inline-reply text — the plugin's desktop backend
//! never wires a reply option). Both are gated exactly like wifi.js:
//!   - the in-app NOTIFS panel list is populated unconditionally (the
//!     `notification` / `call` events are emitted by ws_server regardless);
//!   - the native macOS banner requires BOTH `notifications` AND `nativeNotifs`
//!     to be true (`config.notifications && config.native_notifs`), except the
//!     incoming-call alert, which checks only `nativeNotifs` — same as wifi.js.
//!
//! Dedupe matches wifi.js's `liveNotifs`: `hash = "${title}|${text}"`, keyed by
//! the notification `key`. Same key + same hash → skip (identical repost);
//! different hash → show; map is evicted oldest-first past `MAX_LIVE`.
//!
//! Known limitation vs Electron (documented in the compatibility report):
//! neither backend can *programmatically close* an already-delivered macOS
//! banner, so `notification-removed` clears the in-app panel and the dedupe
//! entry but cannot retract a native banner already on screen. The banner
//! auto-dismisses on its own, so this is cosmetic.

use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::collections::VecDeque;
use std::sync::{Mutex, Once};
use crate::host::{AppHandle, Emitter, Manager};

const MAX_LIVE: usize = 200;

/// Mirrors wifi.js's `liveNotifs` Map (key → content hash), insertion-ordered
/// for oldest-first eviction.
#[derive(Default)]
pub struct NotifState {
    live: Mutex<VecDeque<(String, String)>>,
}

fn config(app: &AppHandle) -> crate::config::Config {
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

/// `hash = "${title}|${text}"`, exactly as wifi.js and NotifListener.kt compute it.
fn content_hash(title: &str, text: &str) -> String {
    format!("{title}|{text}")
}

/// Returns true if this (key, hash) is a duplicate of a still-live notification.
/// Otherwise records it (evicting the oldest past MAX_LIVE) and returns false.
fn is_duplicate(app: &AppHandle, key: &str, hash: &str) -> bool {
    let state = app.state::<NotifState>();
    let mut live = state.live.lock().unwrap();
    if let Some(entry) = live.iter_mut().find(|(k, _)| k == key) {
        if entry.1 == hash {
            return true; // same key, same content → identical repost, ignore
        }
        entry.1 = hash.to_string(); // same key, new content → update + show
        return false;
    }
    live.push_back((key.to_string(), hash.to_string()));
    while live.len() > MAX_LIVE {
        live.pop_front();
    }
    false
}

fn forget(app: &AppHandle, key: &str) {
    let state = app.state::<NotifState>();
    state.live.lock().unwrap().retain(|(k, _)| k != key);
}

// ── Incoming notification ────────────────────────────────────────────────

pub fn on_notification(app: &AppHandle, m: &Value) {
    // Backfill (link-up replay) populates the in-app list only, never a banner
    // — wifi.js: `if (!msg.backfill) showNotification(msg)`.
    if m.get("backfill").and_then(Value::as_bool) == Some(true) {
        return;
    }
    let cfg = config(app);
    // Phase 14: the Mac-initiated tray pause also mutes the native banner
    // (new Mac-side "quiet hours" behavior — the in-app panel list below
    // still gets populated unconditionally, same as always).
    if !(cfg.notifications && cfg.native_notifs) || cfg.is_paused() {
        return;
    }
    // Per-app mute. Keyed on the package (Tier B added `pkg` to the wire) rather
    // than the display label, so two apps sharing a name can't mute each other.
    let app_opts = match m.get("pkg").and_then(Value::as_str) {
        Some(pkg) => {
            // `silent` and `muted` both skip the banner; the in-app list was
            // already fed by ws_server. Only `alert` falls through.
            if cfg.notif_tier(pkg) != "alert" {
                return;
            }
            cfg.app_notif(pkg)
        }
        None => crate::config::AppNotif::default(),
    };
    // Per-app priority override: `never` keeps it in the list only.
    if app_opts.priority == "never" {
        return;
    }

    // AirSync v4 parity: low-importance notifications land in the panel but
    // don't interrupt. Android already decided this is background noise —
    // re-raising it as a banner overrides the phone's own judgement.
    // `always` (per-app override) banners regardless of the phone's priority.
    if app_opts.priority != "always"
        && matches!(m.get("priority").and_then(Value::as_str), Some("low") | Some("min"))
    {
        return;
    }
    // A progress notification updates continuously; a banner per percent would
    // be unusable. The panel shows the live bar instead.
    if m.get("progressMax").and_then(Value::as_i64).is_some_and(|v| v > 0)
        || m.get("progressIndeterminate").and_then(Value::as_bool) == Some(true)
    {
        return;
    }

    // wifi.js: `String(msg.key || randomUUID())` — a keyless notification gets
    // a throwaway unique key so it's never deduped (folding them all onto one
    // shared "" slot would wrongly suppress unrelated keyless notifications).
    let key = m
        .get("key")
        .and_then(Value::as_str)
        .filter(|k| !k.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    let title = m.get("title").and_then(Value::as_str).unwrap_or("");
    let text = m.get("text").and_then(Value::as_str).unwrap_or("");
    let app_label = m.get("app").and_then(Value::as_str).unwrap_or("");

    if is_duplicate(app, &key, &content_hash(title, text)) {
        return;
    }

    let replyable = m.get("replyable").and_then(Value::as_bool) == Some(true);
    // Banner title = app label if present (like the phone's own banner), body =
    // "title: text" when both exist, matching the Electron banner content.
    let banner_title = if app_label.is_empty() { title } else { app_label };
    let banner_body = if title.is_empty() || app_label.is_empty() {
        text.to_string()
    } else {
        format!("{title}\n{text}")
    };

    // The host (SwiftUI) draws the banner. A replyable one carries `replyKey`;
    // the host answers with the `notif_reply` command. Kept quiet (the phone
    // already buzzed).
    native_notification_ext(
        app,
        &key,
        banner_title,
        &banner_body,
        replyable.then(|| key.clone()),
        app_opts.sound,
        !app_opts.preview,
    );
}

/// Ask the host to post a native banner: `native-notification
/// {id,title,body,replyKey?,sound}`.
pub fn native_notification(
    app: &AppHandle,
    id: &str,
    title: &str,
    body: &str,
    reply_key: Option<String>,
    sound: bool,
) {
    native_notification_ext(app, id, title, body, reply_key, sound, false);
}

/// As [`native_notification`], plus `hidePreview`: the host replaces the
/// message text with a neutral line (per-app privacy option).
pub fn native_notification_ext(
    app: &AppHandle,
    id: &str,
    title: &str,
    body: &str,
    reply_key: Option<String>,
    sound: bool,
    hide_preview: bool,
) {
    let _ = app.emit(
        "native-notification",
        json!({ "id": id, "title": title, "body": body, "replyKey": reply_key, "sound": sound, "hidePreview": hide_preview }),
    );
}

pub fn on_removed(app: &AppHandle, key: Option<&str>) {
    if let Some(key) = key {
        forget(app, key);
    }
}

// ── Incoming call alert ──────────────────────────────────────────────────

/// Ringing incoming call. Distinct from the notification path: gated on
/// `nativeNotifs` ONLY (wifi.js `showCall` ignores the `notifications` master),
/// and rings rather than staying silent. The rich in-app overlay is Phase 9 —
/// this is just the OS-level heads-up.
pub fn on_call(app: &AppHandle, m: &Value) {
    let cfg = config(app);
    if !cfg.native_notifs || cfg.is_paused() {
        return;
    }
    let name = m.get("name").and_then(Value::as_str).unwrap_or("");
    let number = m.get("number").and_then(Value::as_str).unwrap_or("");
    // wifi.js: subtitle = name||number||"Unknown"; body = (number&&name)?number:"on your phone".
    let subtitle = if !name.is_empty() {
        name
    } else if !number.is_empty() {
        number
    } else {
        "Unknown"
    };
    let via = m.get("app").and_then(Value::as_str).filter(|a| !a.is_empty());
    let body = match via {
        // Internet call (WhatsApp…): say which app it rings in.
        Some(label) => format!("{label} call"),
        None if !number.is_empty() && !name.is_empty() => number.to_string(),
        None => "on your phone".to_string(),
    };
    native_notification(app, "call", &format!("Incoming Call — {subtitle}"), &body, None, true);
}

/// Call ended / idle. No native banner to retract (see module note); the UI
/// hides its overlay off the `call` idle event.
pub fn on_call_cleared(_app: &AppHandle) {}
