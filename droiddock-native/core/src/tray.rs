//! Mac-initiated pause and the status-widget flag (Phase 14), minus the tray UI.
//!
//! The tray, menu, panel and widget windows live in the SwiftUI host now. What
//! stays here is the state: the `pausedUntil` timestamp (and its auto-expiry)
//! and the persisted `widget_enabled` flag. Pausing is a Mac-local "quiet
//! hours" concept - the Android app has no receive-side handling for an inbound
//! pause, so it mutes local reactions only (notification banners, clipboard
//! sync, the ADB reconnect scanner).

use crate::config;
use crate::host::{AppHandle, Emitter, Manager};

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
    // Wake `expire_loop` so it re-aims at the new deadline, and let the adb
    // scanner retry now if the pause was just lifted.
    crate::pacing::poke_pause();
    if until.is_none() {
        crate::pacing::poke_reconnect();
    }
}

/// Longest single sleep. Tokio's clock does not advance while the Mac sleeps,
/// so one long sleep to a wall-clock deadline would overshoot by the whole
/// time the lid was closed. Sleeping in chunks and re-checking the wall clock
/// bounds the lateness to this value of awake time after a wake.
const MAX_CHUNK_MS: u64 = 30_000;

/// How long to sleep before re-checking a deadline `until` (wall-clock ms).
/// +50 ms past the deadline so `is_paused` (now < until) is definitely false.
fn next_wait_ms(until: i64, now: i64) -> u64 {
    ((until - now).max(0) as u64 + 50).min(MAX_CHUNK_MS)
}

/// Auto-resume once a timed pause's deadline passes. The deadline is a
/// persisted timestamp (so it survives an app restart mid-pause); this task
/// sleeps exactly until it, and parks entirely when no timed pause is set.
pub async fn expire_loop(app: AppHandle) {
    loop {
        let until = app.state::<crate::AppState>().config.lock().unwrap().paused_until;
        match until {
            None => crate::pacing::pause_changed().await,
            Some(until) => {
                let wait_ms = next_wait_ms(until, config::now_ms());
                tokio::select! {
                    _ = tokio::time::sleep(std::time::Duration::from_millis(wait_ms)) => {}
                    _ = crate::pacing::pause_changed() => continue,
                }
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
    }
}

pub fn pause_set(app: AppHandle, until: Option<i64>) -> config::Config {
    apply_pause(&app, until);
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

/// Persist the widget flag and tell the host (which owns the window) via `config`.
pub fn widget_set(app: AppHandle, show: bool) -> Result<(), String> {
    let updated = {
        let state = app.state::<crate::AppState>();
        let mut cfg = state.config.lock().unwrap();
        cfg.widget_enabled = show;
        config::save(&app, &cfg);
        cfg.clone()
    };
    let _ = app.emit("config", &updated);
    Ok(())
}

/// Kept for the old startup call-site: the host reads `widget_enabled` from
/// `get_config` and opens the widget itself.
pub fn apply_widget(_app: &AppHandle) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn waits_are_chunked_and_follow_the_wall_clock() {
        // Far deadline: never sleep longer than one chunk.
        assert_eq!(next_wait_ms(3_600_000, 0), MAX_CHUNK_MS);
        // After an 8 h sleep the deadline is long past: re-check immediately.
        assert_eq!(next_wait_ms(1_000, 8 * 3_600_000), 50);
        // Near deadline: sleep exactly to it (+50 ms).
        assert_eq!(next_wait_ms(5_000, 1_000), 4_050);
    }
}
