use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use tauri::{AppHandle, Manager};
use uuid::Uuid;

const DEFAULT_PORT: u16 = 48484;

/// `clipboardSync` / `notifications` / `nativeNotifs` all default to `true`
/// when absent on disk — the Electron app treats a missing key as enabled
/// (`config?.clipboardSync !== false`), so `#[serde(default = "default_true")]`
/// reproduces that exactly for older/partial config files.
fn default_true() -> bool {
    true
}

/// Phase 19: default Mac-filesystem allowlist for reverse file browsing —
/// Desktop/Documents/Downloads, each expanded to a real absolute path from
/// `$HOME` right here (never stored as the literal `~`), so `check_root`
/// (see `mac_fs.rs`) always has concrete paths to canonicalize against.
fn default_mac_fs_roots() -> Vec<String> {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    vec![
        format!("{home}/Desktop"),
        format!("{home}/Documents"),
        format!("{home}/Downloads"),
    ]
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub token: String,
    pub port: u16,
    #[serde(default = "default_true")]
    pub notifications: bool,
    #[serde(default = "default_true")]
    pub native_notifs: bool,
    /// Two-way clipboard sync master switch. Gates BOTH inbound writes and
    /// outbound sends, exactly like wifi.js's `clipboardEnabled()`. Stored on
    /// disk as `clipboardSync` (the same key the Electron app uses).
    #[serde(default = "default_true")]
    pub clipboard_sync: bool,
    /// User-configurable override for the name the phone sees; falls back to
    /// the Mac's hostname when unset — same semantics as wifi.js's `macName()`.
    #[serde(default)]
    pub device_name: Option<String>,
    /// Phase 13: the paired phone's stable ADB pairing guid (wireless
    /// debugging) — `wifi.setCfg('deviceGuid', guid)`'s equivalent. Used to
    /// rediscover the phone's rotating ip:port over mDNS on reconnect.
    #[serde(default)]
    pub device_guid: Option<String>,
    /// Phase 13: last-known wireless-adb `ip:port` from the legacy (<Android
    /// 11) `adb tcpip` flow — `wifi.setCfg('tcpAddr', addr)`'s equivalent.
    #[serde(default)]
    pub tcp_addr: Option<String>,
    /// Phase 13: whether the background mDNS/tcp reconnect scan runs at all —
    /// mirrors `wifi.getCfg('autoReconnect') !== false`, so absent = enabled.
    #[serde(default = "default_true")]
    pub auto_reconnect: bool,
    /// Phase 14: Mac-initiated "quiet hours", tray-driven — epoch millis until
    /// which notification/clipboard forwarding is suppressed locally; `None` =
    /// active, `Some(i64::MAX)` = paused indefinitely. Same duration-based
    /// shape as the Android app's own `Prefs.pausedUntil`/`ConnectionManager
    /// .pause()`, just applied Mac-side — the reference has no protocol
    /// message for a Mac→phone pause (`ConnectionManager` never handles an
    /// inbound "pause"/"resume"), so this does not touch the wire.
    #[serde(default)]
    pub paused_until: Option<i64>,
    /// Phase 18: photo auto-sync master switch — opt-in (default off), unlike
    /// the other `default_true` toggles above, since it starts pulling files
    /// onto disk the moment a capable phone connects.
    #[serde(default)]
    pub photo_sync_enabled: bool,
    /// Phase 18: destination folder override. `None` = not yet configured —
    /// resolved lazily to `~/Pictures/DroidDock` at point-of-use (see
    /// `photo_sync::resolve_dest`) rather than eagerly written here, so
    /// changing the setting later takes effect on the very next sync without
    /// a config migration.
    #[serde(default)]
    pub photo_sync_dest: Option<String>,
    /// Phase 19: allowed Mac directories the paired phone may browse/pull from
    /// (reverse file browsing) — the hard security boundary enforced by
    /// `mac_fs::check_root`. User-configurable in Settings; never implicitly
    /// widened beyond what's listed here.
    #[serde(default = "default_mac_fs_roots")]
    pub mac_fs_roots: Vec<String>,
    /// Keys this build doesn't know about (a newer build's settings, or
    /// hand-added ones) — round-tripped instead of silently deleted on the
    /// next save, matching the Electron config's free-form object semantics.
    #[serde(flatten)]
    pub extra: serde_json::Map<String, serde_json::Value>,
}

/// Current time in epoch milliseconds.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

impl Config {
    /// Whether the Mac-initiated pause is currently in effect.
    pub fn is_paused(&self) -> bool {
        self.paused_until.is_some_and(|until| now_ms() < until)
    }
}

impl Default for Config {
    fn default() -> Self {
        Self {
            token: Uuid::new_v4().to_string(),
            port: DEFAULT_PORT,
            notifications: true,
            native_notifs: true,
            clipboard_sync: true,
            device_name: None,
            device_guid: None,
            tcp_addr: None,
            auto_reconnect: true,
            paused_until: None,
            photo_sync_enabled: false,
            photo_sync_dest: None,
            mac_fs_roots: default_mac_fs_roots(),
            extra: serde_json::Map::new(),
        }
    }
}

/// Persist a config back to `droiddock.json` (pretty-printed, same as
/// first-run creation). Used by the Settings-toggle commands.
///
/// Write-to-temp + rename, not an in-place truncate: a crash mid-write must
/// never leave a corrupt `droiddock.json`, because `load_or_create` reacts to
/// a failed parse by regenerating a default config with a FRESH TOKEN —
/// silently unpairing the phone.
pub fn save(app: &AppHandle, config: &Config) {
    let path = config_path(app);
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let tmp = path.with_extension("json.tmp");
    if fs::write(&tmp, serde_json::to_string_pretty(config).unwrap()).is_ok() {
        let _ = fs::rename(&tmp, &path);
    }
}

fn config_path(app: &AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("app data dir unavailable")
        .join("droiddock.json")
}

/// Load `droiddock.json` from the app data dir, creating it with a fresh
/// token on first run — same semantics as the Electron app's `loadConfig()`.
pub fn load_or_create(app: &AppHandle) -> Config {
    let path = config_path(app);

    if let Ok(raw) = fs::read_to_string(&path) {
        if let Ok(config) = serde_json::from_str::<Config>(&raw) {
            return config;
        }
    }

    let config = Config::default();
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let _ = fs::write(&path, serde_json::to_string_pretty(&config).unwrap());
    config
}
