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
use std::sync::Mutex;
use tauri::{AppHandle, Manager};
use tauri_plugin_notification::NotificationExt;

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
    if let Some(pkg) = m.get("pkg").and_then(Value::as_str) {
        if cfg.muted_apps.iter().any(|p| p == pkg) {
            return;
        }
    }

    // AirSync v4 parity: low-importance notifications land in the panel but
    // don't interrupt. Android already decided this is background noise —
    // re-raising it as a banner overrides the phone's own judgement.
    if matches!(m.get("priority").and_then(Value::as_str), Some("low") | Some("min")) {
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

    if replyable {
        show_reply_banner(app, &key, banner_title, &banner_body);
    } else {
        // Plain banner via the plugin. Kept quiet (the phone already buzzed) —
        // wifi.js sends normal notifs with `silent:true`.
        let _ = app
            .notification()
            .builder()
            .title(banner_title)
            .body(&banner_body)
            .show();
    }
}

/// Replyable notification via `mac-notification-sys`. `send_notification` blocks
/// on the notification's run loop until the user interacts, so it runs on a
/// blocking thread; a captured reply is pushed to the phone as
/// `{ "type":"reply", "key", "text" }` (the exact shape ConnectionManager.kt reads).
fn show_reply_banner(app: &AppHandle, key: &str, title: &str, body: &str) {
    let app = app.clone();
    let key = key.to_string();
    let title = title.to_string();
    let body = body.to_string();

    tauri::async_runtime::spawn_blocking(move || {
        use mac_notification_sys::{MainButton, Notification, NotificationResponse};
        let mut opts = Notification::new();
        opts.main_button(MainButton::Response("Reply"));
        match mac_notification_sys::send_notification(&title, None, &body, Some(&opts)) {
            // wifi.js gates on `if (text && ...)` — an empty reply is not sent.
            Ok(NotificationResponse::Reply(text)) if !text.is_empty() => {
                let ws = app.state::<SharedState>().inner().clone();
                tauri::async_runtime::spawn(async move {
                    ws_server::push(&ws, json!({ "type": "reply", "key": key, "text": text })).await;
                });
            }
            _ => {}
        }
    });
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
    let body = if !number.is_empty() && !name.is_empty() {
        number
    } else {
        "on your phone"
    };
    let _ = app
        .notification()
        .builder()
        .title(format!("Incoming Call — {subtitle}"))
        .body(body)
        .show();
}

/// Call ended / idle. No native banner to retract (see module note); the UI
/// hides its overlay off the `call` idle event.
pub fn on_call_cleared(_app: &AppHandle) {}
