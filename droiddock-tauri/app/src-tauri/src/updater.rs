//! In-app updates.
//!
//! DroidDock is distributed as an unsigned `.dmg` on GitHub Releases, outside
//! the App Store — so without this module a user stays on whichever version
//! they first downloaded, permanently. `tauri-plugin-updater` fetches
//! `latest.json` from the release, verifies its minisign signature against the
//! pubkey baked into `tauri.conf.json`, swaps the `.app` bundle and we restart.
//!
//! Two things this is *not*:
//!
//! * Apple notarization. The minisign signature is the updater's own scheme and
//!   proves the archive came from whoever holds the private key; Gatekeeper
//!   neither knows nor cares about it. First install is still right-click-open.
//! * Automatic. `spawn_startup_check` only ever *looks*, and only once a day —
//!   the download is always a deliberate click, because it ends in the app
//!   relaunching underneath a live phone link.
//!
//! The whole surface is two commands plus a background look-see, which keeps
//! the state machine (idle → checking → available → downloading → restart) on
//! the frontend where it can be rendered, rather than split across both sides.

use serde::Serialize;
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::{Update, UpdaterExt};

/// Don't hit GitHub more than once a day on launch. This is a personal LAN
/// utility that people leave running for weeks and relaunch in bursts when
/// they're fiddling with it; an unthrottled check would be all burst.
const CHECK_INTERVAL_MS: i64 = 24 * 60 * 60 * 1000;

/// Let the phone link, tray and window settle before spending bandwidth on
/// something nobody asked for yet.
const STARTUP_DELAY_SECS: u64 = 10;

/// The resolved-but-not-yet-downloaded update, parked between `update_check`
/// and `update_install` so the install doesn't re-query the endpoint (and can't
/// end up installing a *different* version than the one the user was shown).
#[derive(Default)]
pub struct UpdaterState {
    pending: Mutex<Option<Update>>,
}

impl UpdaterState {
    /// The version waiting to be installed, for the tray label.
    pub fn pending_version(&self) -> Option<String> {
        self.pending.lock().unwrap().as_ref().map(|u| u.version.clone())
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    /// The version on offer, e.g. `1.1.0` — no `v` prefix, that's the tag's.
    pub version: String,
    /// The release body, shown as-is. Empty rather than null when absent, so
    /// the frontend can test truthiness without a null dance.
    pub notes: String,
    /// What's running now, so the UI can say "1.0.0 → 1.1.0" without a second
    /// round-trip to `getVersion()`.
    pub current_version: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Progress {
    downloaded: u64,
    /// `None` when the server sends no `Content-Length`; the UI falls back to
    /// an indeterminate bar rather than inventing a denominator.
    total: Option<u64>,
}

impl From<&Update> for UpdateInfo {
    fn from(u: &Update) -> Self {
        Self {
            version: u.version.clone(),
            notes: u.body.clone().unwrap_or_default(),
            current_version: u.current_version.clone(),
        }
    }
}

/// Ask the endpoint whether anything newer exists. `Ok(None)` means up to date.
///
/// Errors come back as plain strings for the frontend to show verbatim — the
/// failure modes here (offline, endpoint 404 because the release is still a
/// draft) are all things the user can act on, so swallowing them would be
/// worse than surfacing the raw message.
#[tauri::command]
pub async fn update_check(app: AppHandle) -> Result<Option<UpdateInfo>, String> {
    // `tauri dev` runs an unbundled binary the updater can't replace, and the
    // plugin's own error for that is opaque. Say the useful thing instead.
    if tauri::is_dev() {
        return Err("Updates aren't available in a development build.".into());
    }

    let update = app
        .updater()
        .map_err(|e| e.to_string())?
        .check()
        .await
        .map_err(|e| e.to_string())?;

    let info = update.as_ref().map(UpdateInfo::from);
    *app.state::<UpdaterState>().pending.lock().unwrap() = update;
    Ok(info)
}

/// Download the update parked by `update_check`, install it, and relaunch.
///
/// On success this never returns — `restart()` diverges.
#[tauri::command]
pub async fn update_install(app: AppHandle) -> Result<(), String> {
    // Taken, not borrowed: a `MutexGuard` can't be held across an await, and
    // the `Update` is single-use anyway. Parked back on failure so the user can
    // retry a flaky download without re-checking.
    let update = app
        .state::<UpdaterState>()
        .pending
        .lock()
        .unwrap()
        .take()
        .ok_or("No update is pending — check for updates first.")?;

    let progress_app = app.clone();
    let mut downloaded: u64 = 0;
    let result = update
        .download_and_install(
            move |chunk, total| {
                downloaded += chunk as u64;
                let _ = progress_app.emit("update-progress", Progress { downloaded, total });
            },
            || {},
        )
        .await;

    match result {
        Ok(()) => {
            app.restart();
        }
        Err(e) => {
            *app.state::<UpdaterState>().pending.lock().unwrap() = Some(update);
            Err(e.to_string())
        }
    }
}

/// Background look-see, ~10s after launch.
///
/// Silent by design: on a hit it emits `update-available` (the Settings tab
/// grows a dot, the tray item relabels) and stops. Nothing downloads, nothing
/// interrupts. The throttle timestamp is written whether or not an update was
/// found — the point is to rate-limit the *request*.
pub fn spawn_startup_check(app: AppHandle) {
    if tauri::is_dev() {
        return;
    }
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(STARTUP_DELAY_SECS)).await;

        {
            let state = app.state::<crate::AppState>();
            let cfg = state.config.lock().unwrap();
            if !cfg.auto_check_updates
                || crate::config::now_ms() - cfg.last_update_check < CHECK_INTERVAL_MS
            {
                return;
            }
        }

        let found = match update_check(app.clone()).await {
            Ok(found) => found,
            // A failed check is not worth telling the user about — they didn't
            // ask, and the button in Settings reports properly when they do.
            Err(e) => {
                eprintln!("updater: background check failed ({e})");
                return;
            }
        };

        {
            let state = app.state::<crate::AppState>();
            let mut cfg = state.config.lock().unwrap();
            cfg.last_update_check = crate::config::now_ms();
            crate::config::save(&app, &cfg);
        }

        if let Some(info) = found {
            let _ = app.emit("update-available", info);
            crate::tray::refresh(&app);
        }
    });
}

/// The version this binary was built as — `tauri.conf.json`'s `version`,
/// which is also what the updater compares against.
#[tauri::command]
pub fn app_version(app: AppHandle) -> String {
    app.package_info().version.to_string()
}
