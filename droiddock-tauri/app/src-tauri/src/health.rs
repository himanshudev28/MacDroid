//! One answer to "why isn't this working?", covering both devices.
//!
//! # The problem this solves
//!
//! Nearly every way DroidDock breaks is *silent*, and each silence used to have
//! its own ad-hoc handling. Mirror video keeps flowing with the phone's
//! accessibility service off while every tap is discarded. The Notifications
//! tab is empty whether notification access is missing or the phone is simply
//! quiet. Remote control from the phone no-ops when this Mac's Accessibility
//! grant went stale — which happens on *every* update, because the app is
//! ad-hoc signed and TCC keys on the binary's hash. A second copy of the app
//! holding port 48484 leaves a window that looks perfectly healthy and can
//! never accept a phone.
//!
//! Individually each has a toast somewhere. Collectively they had no home, and
//! all of them are things you only discover after trying the broken feature and
//! drawing the wrong conclusion from it. This asks both devices up front.
//!
//! # What is deliberately *not* checked
//!
//! **macOS notification authorization.** `tauri-plugin-notification`'s
//! `permission_state()` is a stub on desktop that returns `Granted`
//! unconditionally, and neither `NSUserNotificationCenter` (what
//! `mac-notification-sys` drives) nor a preferences read gives a trustworthy
//! answer. Rather than render a green row that means nothing, notifications get
//! an *informational* row that says plainly it can't be read back and offers
//! the button to go look. A check that cannot fail is worse than no check: it
//! actively rules out the real cause.
//!
//! The phone half of the list comes from `PermissionHealth.kt` over the link —
//! see there for why a fix can only leave a notification on some phones.

use crate::mac_remote;
use crate::ws_server::{self, SharedState};
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Manager};

/// One row of the panel.
///
/// `detail` describes the **broken** state, so the UI shows it only when `ok`
/// is false. Writing rows that read correctly both ways produced limp text that
/// explained neither.
#[derive(Debug, Clone, Serialize)]
pub struct HealthItem {
    pub id: String,
    pub ok: bool,
    /// `"error"` a headline feature is dead · `"warn"` an optional one is, or
    /// the link is fragile · `"info"` context rather than a fault.
    pub severity: String,
    pub title: String,
    pub detail: String,
    /// The id [`fix`] understands, or `None` when there is nothing to launch.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fix: Option<String>,
    /// `"mac"` or `"phone"` — which device the row is about, and therefore
    /// where the user has to go.
    pub side: String,
}

impl HealthItem {
    fn mac(id: &str, ok: bool, severity: &str, title: &str, detail: &str, fix: Option<&str>) -> Self {
        Self {
            id: id.into(),
            ok,
            severity: severity.into(),
            title: title.into(),
            detail: detail.into(),
            fix: fix.map(str::to_string),
            side: "mac".into(),
        }
    }
}

/// Probe both devices. The phone half is skipped rather than faked when nothing
/// is linked — an unanswerable question gets a row saying so, not a guess.
pub async fn check(app: &AppHandle, state: &SharedState) -> Vec<HealthItem> {
    let mut items = mac_items(app);

    if !ws_server::is_connected(state).await {
        items.push(HealthItem::mac(
            "phone-link",
            false,
            "info",
            "Phone not linked",
            "Nothing is connected over Wi-Fi, so the phone's own permissions can't be \
             read. Link it and check again.",
            None,
        ));
        return items;
    }

    if !ws_server::phone_has_cap(state, "health").await {
        items.push(HealthItem::mac(
            "phone-outdated",
            false,
            "info",
            "Phone app is older than this check",
            "The linked phone doesn't understand the health request, so only this Mac's \
             side is shown. Update DroidDock on the phone to see its permissions here.",
            None,
        ));
        return items;
    }

    let mut body = serde_json::Map::new();
    body.insert("type".into(), Value::from("health"));
    match ws_server::request_default(state, body).await {
        Ok(reply) => items.extend(phone_items(&reply)),
        Err(e) => items.push(HealthItem::mac(
            "phone-unreachable",
            false,
            "warn",
            "The phone didn't answer",
            &format!("{e}. The link may have dropped between opening this panel and asking."),
            None,
        )),
    }
    items
}

fn mac_items(app: &AppHandle) -> Vec<HealthItem> {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let mut items = Vec::new();

    // Remote control is off by default, so a missing grant is only a *fault*
    // when the feature that needs it is switched on. Off, it is worth stating
    // once and no more — a red row for a feature you deliberately disabled is
    // noise, and noise is what makes people stop reading these panels.
    let trusted = mac_remote::accessibility_trusted();
    items.push(HealthItem::mac(
        "mac-accessibility",
        trusted || !cfg.remote_control,
        if cfg.remote_control { "error" } else { "info" },
        "Accessibility (this Mac)",
        "Not granted, so every click, keystroke and volume press sent from the phone is \
         ignored. Updating DroidDock revokes this silently and macOS does not say so: the \
         row stays in Privacy & Security with its switch still on, but it is dead, because \
         the app is ad-hoc signed and the grant is keyed to the exact binary. Unticking and \
         re-ticking usually does not help — use Settings → System → Reset permission.",
        Some("mac-accessibility"),
    ));

    items.push(HealthItem::mac(
        "mac-port",
        ws_server::is_listening(),
        "error",
        "The link's port",
        "Nothing is listening. Almost always a second copy of DroidDock — /Applications and \
         a dev build, say — holding the port first. The loser looks completely healthy and \
         can never accept a phone. Quit the other copy, or change the port in Settings.",
        None,
    ));

    // See the module doc: this one is a pointer, not a probe. It is here
    // because "phone notifications never appear on the Mac" is the single most
    // common report, and its usual cause is one switch in System Settings that
    // nothing in this app can read.
    items.push(HealthItem::mac(
        "mac-notifications",
        true,
        "info",
        "macOS notifications",
        "Whether macOS lets DroidDock post banners can't be read back by the app, so this          row can't tell you if it's on. If phone notifications aren't appearing on the Mac          and the phone's notification access below is granted, this switch is the next          thing to check.",
        Some("mac-notifications"),
    ));

    items
}

/// Translate the phone's rows. Anything malformed is dropped rather than
/// rendered half-blank: the list is the one thing in the app whose job is to be
/// trustworthy about state.
fn phone_items(reply: &Value) -> Vec<HealthItem> {
    reply
        .get("items")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|it| {
                    Some(HealthItem {
                        id: it.get("id")?.as_str()?.to_string(),
                        ok: it.get("ok")?.as_bool()?,
                        severity: it.get("severity")?.as_str()?.to_string(),
                        title: it.get("title")?.as_str()?.to_string(),
                        detail: it.get("detail")?.as_str()?.to_string(),
                        fix: it.get("fix").and_then(Value::as_str).map(str::to_string),
                        side: "phone".into(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Outcome of pressing Fix, in words the caller can show directly.
///
/// The phone distinguishes opening the screen from leaving a notification —
/// see `PermissionHealth.openFix` — and that distinction has to survive to the
/// toast, or a user stares at an unchanged phone waiting for a screen that was
/// never raised.
pub async fn fix(state: &SharedState, id: &str) -> Result<String, String> {
    match id {
        "mac-accessibility" => {
            mac_remote::open_accessibility_settings();
            Ok("Opened Privacy & Security → Accessibility".into())
        }
        "mac-notifications" => {
            open_pane("x-apple.systempreferences:com.apple.Notifications-Settings.extension").await
        }
        _ => {
            let mut body = serde_json::Map::new();
            body.insert("type".into(), Value::from("health-fix"));
            body.insert("id".into(), Value::from(id));
            let reply = ws_server::request_default(state, body).await?;
            if let Some(err) = reply.get("error").and_then(Value::as_str) {
                return Err(err.to_string());
            }
            match reply.get("result").and_then(Value::as_str) {
                Some("opened") => Ok("Opened the settings screen on your phone".into()),
                // Not a success and not a failure: the phone did something, but
                // the user still has to act on the handset.
                Some("notified") => Ok("Tap the DroidDock notification on your phone to \
                                        open the settings screen — granting \"Display over \
                                        other apps\" lets it open directly next time"
                    .into()),
                _ => Err("The phone has no way to open that screen".into()),
            }
        }
    }
}

async fn open_pane(url: &str) -> Result<String, String> {
    tokio::process::Command::new("open")
        .arg(url)
        .status()
        .await
        .map_err(|e| e.to_string())
        .and_then(|s| {
            if s.success() {
                Ok("Opened System Settings".into())
            } else {
                Err("System Settings refused to open that pane".into())
            }
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// A phone row missing any required field is dropped, not rendered with a
    /// blank title. The panel's whole value is that you can believe it.
    #[test]
    fn malformed_phone_rows_are_dropped() {
        let reply = json!({
            "items": [
                { "id": "a11y", "ok": false, "severity": "error", "title": "T", "detail": "D", "fix": "a11y" },
                { "id": "nope", "ok": false, "severity": "error", "title": "T" },
                { "ok": true, "severity": "info", "title": "T", "detail": "D" },
            ]
        });
        let items = phone_items(&reply);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].id, "a11y");
        assert_eq!(items[0].side, "phone");
        assert_eq!(items[0].fix.as_deref(), Some("a11y"));
    }

    /// A row with no `fix` is a statement, not a button — the field has to stay
    /// absent rather than arriving as an empty string the UI would render.
    #[test]
    fn missing_fix_stays_absent() {
        let reply = json!({
            "items": [
                { "id": "x", "ok": true, "severity": "info", "title": "T", "detail": "D" }
            ]
        });
        let items = phone_items(&reply);
        assert_eq!(items.len(), 1);
        assert!(items[0].fix.is_none());
        let encoded = serde_json::to_value(&items[0]).unwrap();
        assert!(encoded.get("fix").is_none());
    }

    #[test]
    fn no_items_array_is_empty_not_a_panic() {
        assert!(phone_items(&json!({})).is_empty());
        assert!(phone_items(&json!({ "items": "nonsense" })).is_empty());
    }
}
