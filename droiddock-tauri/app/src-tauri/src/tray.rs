//! Tray menu completeness + Mac-initiated pause (Phase 14).
//!
//! The Electron reference has NO tray icon and NO launch-at-login at all
//! (confirmed: no `Tray`/`Menu`/`app.setLoginItemSettings` anywhere in
//! `index.js`) — this is net-new Mac polish, not a port. Phase 1 shipped a
//! Quit-only tray as a placeholder; this phase fills it out per the PRD:
//! status + Pause (1h/8h/indefinite) + Resume + Quit.
//!
//! Pause here is a Mac-local "quiet hours" concept: the Android app has no
//! receive-side handling for an inbound "pause"/"resume" message at all
//! (`ConnectionManager.kt` only ever SENDS `pause`, never handles receiving
//! one), so a Mac→phone pause command would just be silently ignored by the
//! frozen Android app. Pausing here instead mutes local reactions —
//! notification banners, clipboard sync, and the ADB reconnect scanner (see
//! `notifications.rs`/`clipboard.rs`/`adb.rs`'s `is_paused()` gates) — using
//! the same duration-based `pausedUntil` shape the Android app already uses
//! for its own (phone-initiated) pause, just applied Mac-side.

use crate::config;
use tauri::menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_autostart::ManagerExt;

const TRAY_ID: &str = "main-tray";
const HOUR_MS: i64 = 3_600_000;
/// Any `paused_until` further out than this reads as "indefinite" for
/// display. Deliberately NOT an exact-`i64::MAX` sentinel match — a
/// JS-originated indefinite pause (Settings' "∞" button) round-trips through
/// an f64-safe `Number.MAX_SAFE_INTEGER`, not `i64::MAX` itself, so both need
/// to land in the same "basically forever" bucket.
const TEN_YEARS_MS: i64 = 10 * 365 * 24 * 3600 * 1000;

pub fn build(app: &AppHandle) -> tauri::Result<()> {
    let menu = build_menu(app)?;
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(app.default_window_icon().unwrap().clone())
        .tooltip("DroidDock")
        .menu(&menu)
        .on_menu_event(on_menu_event)
        .build(app)?;
    Ok(())
}

fn build_menu(app: &AppHandle) -> tauri::Result<tauri::menu::Menu<tauri::Wry>> {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let paused = cfg.is_paused();
    let status_text = match cfg.paused_until {
        Some(u) if paused && u - config::now_ms() > TEN_YEARS_MS => "Paused indefinitely".to_string(),
        Some(u) if paused => format!("Paused until {}", fmt_clock(u)),
        _ => "Active".to_string(),
    };

    let status = MenuItemBuilder::with_id("status", format!("DroidDock — {status_text}"))
        .enabled(false)
        .build(app)?;
    let pause_1h = MenuItemBuilder::with_id("pause_1h", "Pause for 1 hour").enabled(!paused).build(app)?;
    let pause_8h = MenuItemBuilder::with_id("pause_8h", "Pause for 8 hours").enabled(!paused).build(app)?;
    let pause_indef = MenuItemBuilder::with_id("pause_indef", "Pause indefinitely").enabled(!paused).build(app)?;
    let resume = MenuItemBuilder::with_id("resume", "Resume").enabled(paused).build(app)?;
    let quit = PredefinedMenuItem::quit(app, Some("Quit DroidDock"))?;

    MenuBuilder::new(app)
        .item(&status)
        .separator()
        .item(&pause_1h)
        .item(&pause_8h)
        .item(&pause_indef)
        .item(&resume)
        .separator()
        .item(&quit)
        .build()
}

/// `HH:MM` in local time, no `chrono` dependency — just enough for a tray
/// label, not a general-purpose formatter.
fn fmt_clock(epoch_ms: i64) -> String {
    let secs_since_midnight_utc = (epoch_ms / 1000).rem_euclid(86400);
    let h = secs_since_midnight_utc / 3600;
    let m = (secs_since_midnight_utc % 3600) / 60;
    format!("{h:02}:{m:02}")
}

fn on_menu_event(app: &AppHandle, event: tauri::menu::MenuEvent) {
    match event.id().as_ref() {
        "pause_1h" => apply_pause(app, Some(config::now_ms() + HOUR_MS)),
        "pause_8h" => apply_pause(app, Some(config::now_ms() + 8 * HOUR_MS)),
        "pause_indef" => apply_pause(app, Some(i64::MAX)),
        "resume" => apply_pause(app, None),
        _ => {}
    }
}

/// Update `paused_until`, persist, notify the frontend (so Settings reflects
/// it live), and rebuild the tray menu's status/enabled-state.
pub fn apply_pause(app: &AppHandle, until: Option<i64>) {
    let updated = {
        let app_state = app.state::<crate::AppState>();
        let mut cfg = app_state.config.lock().unwrap();
        cfg.paused_until = until;
        config::save(app, &cfg);
        cfg.clone()
    };
    let _ = app.emit("config", &updated);
    refresh(app);
}

fn refresh(app: &AppHandle) {
    if let Ok(menu) = build_menu(app) {
        if let Some(tray) = app.tray_by_id(TRAY_ID) {
            let _ = tray.set_menu(Some(menu));
        }
    }
}

/// Auto-resume once a timed pause's deadline passes — checked every 30s
/// rather than a one-shot timer, so it survives an app restart mid-pause
/// (the deadline is just a persisted timestamp, not a live timer).
pub async fn expire_loop(app: AppHandle) {
    let mut ticker = tokio::time::interval(std::time::Duration::from_secs(30));
    loop {
        ticker.tick().await;
        let expired = {
            let app_state = app.state::<crate::AppState>();
            let cfg = app_state.config.lock().unwrap();
            cfg.paused_until.is_some() && !cfg.is_paused()
        };
        if expired {
            apply_pause(&app, None);
        }
    }
}

// ── Tauri commands ───────────────────────────────────────────────────────

#[tauri::command]
pub fn pause_set(app: AppHandle, until: Option<i64>) -> config::Config {
    apply_pause(&app, until);
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

#[tauri::command]
pub fn autostart_get(app: AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

#[tauri::command]
pub fn autostart_set(app: AppHandle, enabled: bool) -> Result<(), String> {
    let mgr = app.autolaunch();
    let result = if enabled { mgr.enable() } else { mgr.disable() };
    result.map_err(|e| e.to_string())
}
