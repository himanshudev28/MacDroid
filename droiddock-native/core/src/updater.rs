//! Update check.
//!
//! DroidDock ships outside the App Store, so without this a user stays on
//! whichever version they first downloaded. In the native app the check is
//! deliberately small: GET the GitHub "latest release", compare its tag against
//! this build's version, and let the host open the release page (no
//! auto-install until Sparkle lands).
//!
//! `spawn_startup_check` only ever *looks*, and only once a day.

use serde::Serialize;
use serde_json::Value;
use crate::host::{AppHandle, Emitter, Manager};

/// Releases repo, from the previous updater endpoint
/// (`github.com/himanshudev28/MacDroid/releases/latest/download/latest.json`).
const RELEASES_API: &str = "https://api.github.com/repos/himanshudev28/MacDroid/releases/latest";

/// Don't hit GitHub more than once a day on launch.
const CHECK_INTERVAL_MS: i64 = 24 * 60 * 60 * 1000;

/// Let the phone link settle before spending bandwidth on something nobody
/// asked for yet.
const STARTUP_DELAY_SECS: u64 = 10;

/// The newest release found by `update_check`, parked so `update_install` can
/// hand back the page of the exact version the user was shown.
#[derive(Default)]
pub struct UpdaterState {
    pending: std::sync::Mutex<Option<Pending>>,
}

#[derive(Clone)]
struct Pending {
    html_url: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    /// The version on offer, e.g. `1.1.0` - no `v` prefix, that's the tag's.
    pub version: String,
    /// The release body, shown as-is. Empty rather than null when absent.
    pub notes: String,
    /// What's running now.
    pub current_version: String,
}

/// Parse `1.2.3` / `v1.2.3` (ignoring any `-suffix`) into comparable parts.
fn parse_version(v: &str) -> Vec<u64> {
    v.trim()
        .trim_start_matches(['v', 'V'])
        .split(['-', '+'])
        .next()
        .unwrap_or("")
        .split('.')
        .map(|p| p.parse::<u64>().unwrap_or(0))
        .collect()
}

/// True when `candidate` is strictly newer than `current`.
fn is_newer(candidate: &str, current: &str) -> bool {
    let (mut a, mut b) = (parse_version(candidate), parse_version(current));
    let n = a.len().max(b.len());
    a.resize(n, 0);
    b.resize(n, 0);
    a > b
}

/// Ask GitHub whether anything newer exists. `Ok(None)` means up to date.
pub async fn update_check(app: AppHandle) -> Result<Option<UpdateInfo>, String> {
    let current = app.package_info().version.clone();
    // System curl instead of an in-process TLS stack: this runs once a day.
    let curl = if cfg!(target_os = "macos") { "/usr/bin/curl" } else { "curl" };
    let out = tokio::process::Command::new(curl)
        .args([
            "-q", "--fail", "--silent", "--show-error", "--location",
            "--proto", "=https", "--proto-redir", "=https", "--tlsv1.2",
            "--connect-timeout", "10", "--max-time", "15",
            "--user-agent", &format!("DroidDock/{current}"),
            "--header", "Accept: application/vnd.github+json",
            RELEASES_API,
        ])
        .kill_on_drop(true)
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
        return Err(format!("GitHub request failed: {}", if err.is_empty() { "curl error" } else { &err }));
    }
    let bytes = out.stdout;
    let body: Value = serde_json::from_slice(&bytes).map_err(|e| e.to_string())?;
    let tag = body.get("tag_name").and_then(Value::as_str).ok_or("The release has no tag")?;
    let version = tag.trim_start_matches(['v', 'V']).to_string();
    let state = app.state::<UpdaterState>();
    if !is_newer(&version, &current) {
        *state.pending.lock().unwrap() = None;
        return Ok(None);
    }
    let html_url = body
        .get("html_url")
        .and_then(Value::as_str)
        .unwrap_or("https://github.com/himanshudev28/MacDroid/releases/latest")
        .to_string();
    let notes = body.get("body").and_then(Value::as_str).unwrap_or_default().to_string();
    *state.pending.lock().unwrap() = Some(Pending { html_url });
    Ok(Some(UpdateInfo { version, notes, current_version: current }))
}

/// Returns the release page URL for the pending update; the host opens it.
pub async fn update_install(app: AppHandle) -> Result<String, String> {
    app.state::<UpdaterState>()
        .pending
        .lock()
        .unwrap()
        .clone()
        .map(|p| p.html_url)
        .ok_or_else(|| "No update is pending - check for updates first.".to_string())
}

/// Background look-see, ~10s after launch. Silent on a miss; on a hit it emits
/// `update-available`. The throttle timestamp is written whether or not an
/// update was found - the point is to rate-limit the *request*.
pub fn spawn_startup_check(app: AppHandle) {
    if crate::host::is_dev() {
        return;
    }
    crate::host::async_runtime::spawn(async move {
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
        }
    });
}

/// The version this binary was built as.
pub fn app_version(app: AppHandle) -> String {
    app.package_info().version.to_string()
}

#[cfg(test)]
mod tests {
    use super::is_newer;

    #[test]
    fn compares_versions_numerically() {
        assert!(is_newer("2.3.1", "2.3.0"));
        assert!(is_newer("v2.10.0", "2.9.9"));
        assert!(is_newer("3", "2.9.9"));
        assert!(!is_newer("2.3.0", "2.3.0"));
        assert!(!is_newer("2.2.9", "2.3.0"));
    }
}
