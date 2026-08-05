//! Menu-bar title, low-battery alerts, and the floating status widget.
//!
//! All three read the same thing — the phone's latest battery/media snapshot —
//! so they share one place to hold it rather than each re-deriving state from
//! events they'd have to subscribe to separately.
//!
//! **On font size:** AirSync's equivalent settings include menu-bar font size.
//! Tauri exposes only `TrayIcon::set_title`, not the underlying `NSStatusItem`
//! button, so there is no supported way to set an attributed title from here.
//! Rather than ship a slider that does nothing, the length cap below is offered
//! instead — it controls how much menu bar the app occupies, which is what the
//! font setting is actually for.

use crate::config::Config;
use serde_json::Value;
use std::sync::Mutex;
use tauri::{AppHandle, Manager};

/// Latest phone state, as far as the menu bar is concerned.
#[derive(Default, Clone)]
pub struct PhoneSnapshot {
    pub battery: Option<i64>,
    pub charging: bool,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub playing: bool,
    /// Unread notifications since the panel was last opened — shown as a count
    /// beside the menu-bar text, the way AirSync v4's glance does.
    pub unread: u32,
    /// Last battery level we alerted on, so the alert fires once per crossing
    /// rather than on every `device-info` push while the phone sits at 19%.
    alerted_at: Option<i64>,
    /// The last complete `media` message, replayed to late subscribers.
    pub media: Option<Value>,
    /// Cached base64 artwork and the `trackKey` it belongs to — see `on_media`.
    art: Option<String>,
    art_track: String,
}

#[derive(Default)]
pub struct StatusState(pub Mutex<PhoneSnapshot>);

/// A `device-info` arrived. Updates the menu bar and may raise a low-battery alert.
pub fn on_device_info(app: &AppHandle, raw: &Value) {
    let level = raw.get("battery").and_then(Value::as_i64);
    let charging = raw.get("charging").and_then(Value::as_bool).unwrap_or(false);

    let alert = {
        let state = app.state::<StatusState>();
        let mut snap = state.0.lock().unwrap();
        snap.battery = level;
        snap.charging = charging;
        decide_battery_alert(&mut snap, &config(app))
    };

    if let Some(pct) = alert {
        raise_low_battery(app, pct);
    }
    refresh_title(app);
}

/// One more notification arrived. Counted for the menu-bar glance only — the
/// panel and the in-app list keep their own lists.
pub fn on_notification(app: &AppHandle) {
    {
        let state = app.state::<StatusState>();
        let mut snap = state.0.lock().unwrap();
        snap.unread = snap.unread.saturating_add(1);
    }
    refresh_title(app);
}

/// The panel was opened (or notifications cleared) — the badge has been seen.
pub fn clear_unread(app: &AppHandle) {
    {
        let state = app.state::<StatusState>();
        state.0.lock().unwrap().unread = 0;
    }
    refresh_title(app);
}

/// A `media` push arrived.
/// Fold one `media` push into the cache and return the *complete* message —
/// i.e. this tick's fields plus the artwork the phone last sent for this track.
///
/// The phone attaches `art` only when the track changes (it pushes once a
/// second while playing, and a ~40 KB base64 image per tick would dwarf every
/// other message on the link). That's the right call on the wire, but it means
/// any Mac-side listener that starts *mid-track* — a webview reload, the
/// menu-bar panel opening, the status widget — never sees the artwork at all,
/// and the phone has no reason to re-send it until the next song.
///
/// So the art is cached here against its `trackKey` and re-attached to every
/// tick. Callers get a message that is always complete, and `media_state`
/// below hands the same thing to anything that mounts late.
pub fn on_media(app: &AppHandle, raw: &Value) -> Value {
    let state = app.state::<StatusState>();
    let mut merged = raw.clone();
    {
        let mut snap = state.0.lock().unwrap();
        let active = raw.get("active").and_then(Value::as_bool).unwrap_or(false);
        snap.playing = raw.get("playing").and_then(Value::as_bool).unwrap_or(false);
        snap.title = active
            .then(|| raw.get("title").and_then(Value::as_str).unwrap_or("").to_string())
            .filter(|t| !t.is_empty());
        snap.artist = active
            .then(|| raw.get("artist").and_then(Value::as_str).unwrap_or("").to_string())
            .filter(|t| !t.is_empty());

        let track = raw.get("trackKey").and_then(Value::as_str).unwrap_or("").to_string();
        match raw.get("art") {
            // Present and non-null: a new image for this track. `Value::Null` is
            // the phone explicitly saying "this track has none" — distinct from
            // absent, which means "unchanged, keep what you have".
            Some(Value::Null) => {
                snap.art = None;
                snap.art_track = track;
            }
            Some(art) => {
                snap.art = art.as_str().map(str::to_string);
                snap.art_track = track;
            }
            None => {
                // Re-attach the cached art, but only if it belongs to the track
                // now playing — otherwise a paused-then-new-song sequence would
                // show the previous album's cover.
                if snap.art_track == track {
                    if let Some(art) = &snap.art {
                        if let Some(obj) = merged.as_object_mut() {
                            obj.insert("art".into(), Value::String(art.clone()));
                        }
                    }
                }
            }
        }
        snap.media = Some(merged.clone());
    }
    refresh_title(app);
    merged
}

/// The last `media` push, artwork included — for anything that mounts after it
/// was emitted. Exactly the same fix `wifi_status` needed: an emit-only event
/// leaves every late subscriber showing "nothing playing" over a live session.
#[tauri::command]
pub fn media_state(app: AppHandle) -> Option<Value> {
    app.state::<StatusState>().0.lock().unwrap().media.clone()
}

/// The phone went away — clear the menu bar and re-arm the battery alert so the
/// next phone (or the next session) can trigger it again.
pub fn on_disconnect(app: &AppHandle) {
    {
        let state = app.state::<StatusState>();
        *state.0.lock().unwrap() = PhoneSnapshot::default();
    }
    refresh_title(app);
}

/// Whether to alert, and at what level.
///
/// Fires on a *downward crossing* only: below the threshold, not charging, and
/// not already alerted at or below this level. Recovering above the threshold
/// (or plugging in) re-arms it, so a phone hovering at the boundary produces one
/// banner per discharge, not one per push.
fn decide_battery_alert(snap: &mut PhoneSnapshot, cfg: &Config) -> Option<i64> {
    let level = snap.battery?;
    let threshold = i64::from(cfg.low_battery_pct);

    if snap.charging || level > threshold {
        snap.alerted_at = None; // re-arm
        return None;
    }
    if !cfg.low_battery_alert || cfg.is_paused() {
        return None;
    }
    match snap.alerted_at {
        // Already warned at this level or lower — don't repeat.
        Some(prev) if level >= prev => None,
        _ => {
            snap.alerted_at = Some(level);
            Some(level)
        }
    }
}

fn raise_low_battery(app: &AppHandle, pct: i64) {
    use tauri_plugin_notification::NotificationExt;
    let _ = app
        .notification()
        .builder()
        .title("Phone battery low")
        .body(format!("Your phone is at {pct}%."))
        .show();
}

/// Rebuild and apply the menu-bar title from the current snapshot + settings.
pub fn refresh_title(app: &AppHandle) {
    let cfg = config(app);
    let snap = app.state::<StatusState>().0.lock().unwrap().clone();
    let title = render_title(&snap, &cfg);
    crate::tray::set_title(app, title.as_deref());
}

/// Pure so it can be tested without a tray.
pub fn render_title(snap: &PhoneSnapshot, cfg: &Config) -> Option<String> {
    let base = render_base(snap, cfg);
    // The unread badge rides alongside whatever text mode is selected, and
    // shows on its own when the mode is "none" — an unseen count is worth a
    // glance even if you asked for a bare icon otherwise.
    match (base, snap.unread) {
        (b, 0) => b,
        (Some(b), n) => Some(format!("{b}  ●{n}")),
        (None, n) => Some(format!("●{n}")),
    }
}

fn render_base(snap: &PhoneSnapshot, cfg: &Config) -> Option<String> {
    let max = cfg.menubar_max_len.max(6) as usize;
    match cfg.menubar_text.as_str() {
        "battery" => snap.battery.map(|b| render_battery(b, snap.charging, &cfg.menubar_battery_style)),
        "media" => {
            let title = snap.title.as_deref()?;
            let text = match snap.artist.as_deref() {
                Some(artist) if !artist.is_empty() => format!("{title} — {artist}"),
                _ => title.to_string(),
            };
            Some(truncate(&text, max))
        }
        "device" => None, // filled in by the caller's device name; see tray.rs
        _ => None,
    }
}

fn render_battery(level: i64, charging: bool, style: &str) -> String {
    let bolt = if charging { "⚡︎" } else { "" };
    match style {
        // A 5-segment bar reads at a glance without parsing digits.
        "bar" => format!("{bolt}{}", bar(level)),
        "both" => format!("{bolt}{} {level}%", bar(level)),
        _ => format!("{bolt}{level}%"),
    }
}

fn bar(level: i64) -> String {
    let filled = ((level.clamp(0, 100) as f64) / 100.0 * 5.0).round() as usize;
    let filled = filled.min(5);
    format!("{}{}", "▮".repeat(filled), "▯".repeat(5 - filled))
}

/// Truncate on a character boundary — a byte slice would panic on the
/// multi-byte characters that show up constantly in track titles.
fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    let kept: String = s.chars().take(max.saturating_sub(1)).collect();
    format!("{}…", kept.trim_end())
}

fn config(app: &AppHandle) -> Config {
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> Config {
        Config::default()
    }

    fn snap(battery: Option<i64>, charging: bool) -> PhoneSnapshot {
        PhoneSnapshot { battery, charging, ..Default::default() }
    }

    #[test]
    fn battery_styles_render_distinctly() {
        let mut c = cfg();
        c.menubar_text = "battery".into();
        let s = snap(Some(64), false);

        c.menubar_battery_style = "percent".into();
        assert_eq!(render_title(&s, &c).unwrap(), "64%");
        c.menubar_battery_style = "bar".into();
        assert_eq!(render_title(&s, &c).unwrap(), "▮▮▮▯▯");
        c.menubar_battery_style = "both".into();
        assert_eq!(render_title(&s, &c).unwrap(), "▮▮▮▯▯ 64%");
    }

    #[test]
    fn charging_is_marked_and_bar_saturates() {
        let mut c = cfg();
        c.menubar_text = "battery".into();
        assert!(render_title(&snap(Some(50), true), &c).unwrap().starts_with('⚡'));
        c.menubar_battery_style = "bar".into();
        assert_eq!(render_title(&snap(Some(100), false), &c).unwrap(), "▮▮▮▮▮");
        assert_eq!(render_title(&snap(Some(0), false), &c).unwrap(), "▯▯▯▯▯");
    }

    #[test]
    fn media_text_truncates_on_char_boundaries() {
        let mut c = cfg();
        c.menubar_text = "media".into();
        c.menubar_max_len = 10;
        let s = PhoneSnapshot {
            // Multi-byte throughout: a byte-index truncation would panic here.
            title: Some("日本語のタイトルです".into()),
            artist: Some("アーティスト".into()),
            ..Default::default()
        };
        let out = render_title(&s, &c).unwrap();
        assert!(out.chars().count() <= 10, "got {out:?}");
        assert!(out.ends_with('…'));
    }

    #[test]
    fn nothing_to_show_yields_no_title() {
        let mut c = cfg();
        c.menubar_text = "battery".into();
        assert_eq!(render_title(&snap(None, false), &c), None);
        c.menubar_text = "media".into();
        assert_eq!(render_title(&snap(Some(50), false), &c), None);
        c.menubar_text = "none".into();
        assert_eq!(render_title(&snap(Some(50), false), &c), None);
    }

    #[test]
    fn unread_badge_rides_alongside_and_stands_alone() {
        let mut c = cfg();
        c.menubar_text = "battery".into();
        let mut s = snap(Some(80), false);

        assert_eq!(render_title(&s, &c).unwrap(), "80%", "no badge at zero");
        s.unread = 3;
        assert_eq!(render_title(&s, &c).unwrap(), "80%  ●3");

        // Even with the text mode off, an unseen count is worth showing.
        c.menubar_text = "none".into();
        assert_eq!(render_title(&s, &c).unwrap(), "●3");
        s.unread = 0;
        assert_eq!(render_title(&s, &c), None);
    }

    #[test]
    fn low_battery_alerts_once_per_discharge() {
        let c = cfg(); // threshold 20, alerts on
        let mut s = snap(Some(25), false);
        assert_eq!(decide_battery_alert(&mut s, &c), None, "above threshold");

        s.battery = Some(20);
        assert_eq!(decide_battery_alert(&mut s, &c), Some(20), "crossed");
        assert_eq!(decide_battery_alert(&mut s, &c), None, "same level must not repeat");

        // Still draining — a *lower* level is a fresh warning.
        s.battery = Some(12);
        assert_eq!(decide_battery_alert(&mut s, &c), Some(12));
        s.battery = Some(13); // small rebound, still low: no second banner
        assert_eq!(decide_battery_alert(&mut s, &c), None);
    }

    #[test]
    fn charging_or_recovery_rearms_the_alert() {
        let c = cfg();
        let mut s = snap(Some(15), false);
        assert_eq!(decide_battery_alert(&mut s, &c), Some(15));

        s.charging = true;
        assert_eq!(decide_battery_alert(&mut s, &c), None, "no alert while charging");

        // Unplugged again below the threshold → warn again.
        s.charging = false;
        assert_eq!(decide_battery_alert(&mut s, &c), Some(15));
    }

    #[test]
    fn the_toggle_and_the_global_pause_both_suppress_it() {
        let mut c = cfg();
        c.low_battery_alert = false;
        let mut s = snap(Some(5), false);
        assert_eq!(decide_battery_alert(&mut s, &c), None);

        c.low_battery_alert = true;
        c.paused_until = Some(i64::MAX);
        assert_eq!(decide_battery_alert(&mut s, &c), None, "pause means stop reacting");
    }
}
