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
fn default_menubar_text() -> String { "battery".to_string() }
fn default_battery_style() -> String { "percent".to_string() }
fn default_menubar_max_len() -> u32 { 28 }
fn default_album_art() -> String { "thumb".to_string() }
fn default_low_battery_pct() -> u8 { 20 }
fn default_mirror_mode() -> String { "wifi".to_string() }
// The "best" end, deliberately. This is a LAN link, and the old hardcoded
// 6 Mbps / 30 fps was a conservative guess nobody ever revisited.
fn default_mirror_bitrate() -> u32 { 12 }
fn default_mirror_fps() -> u32 { 60 }

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
    /// Reverse file browsing (the phone's "Mac Files" tab). **Opt-in**, matching
    /// photo-sync's posture — it was previously on by default with no way to
    /// turn it off, which is the wrong default for a feature that widens what a
    /// paired phone can reach on this Mac.
    #[serde(default)]
    pub mac_fs_enabled: bool,
    #[serde(default = "default_mac_fs_roots")]
    pub mac_fs_roots: Vec<String>,
    /// Tier C: encrypt JSON control messages (AES-256-GCM keyed off the pairing
    /// token). Off by default and negotiated per connection — see
    /// `crate::crypto`. `#[serde(default)]` means an existing droiddock.json
    /// written before this field simply loads as `false`.
    #[serde(default)]
    pub encrypt_link: bool,
    /// Tier D: let the paired phone drive this Mac's keyboard and pointer.
    /// Off by default and re-checked on every inbound event — see
    /// `crate::mac_remote` for why this one is gated harder than anything else.
    #[serde(default)]
    pub remote_control: bool,
    /// Tell the paired phone this Mac's name and battery state (AirSync's
    /// `MacInfoSyncManager`). Unlike `remote_control` this only ever *sends*
    /// read-only status to a device that is already paired, so it defaults on —
    /// see `crate::mac_info`.
    #[serde(default = "default_true")]
    pub mac_info_sync: bool,
    /// Push what's playing on this Mac to the phone (`crate::mac_media`).
    /// Read-only status like `mac_info_sync`, so it defaults on.
    #[serde(default = "default_true")]
    pub mac_media_sync: bool,
    /// Also read the active browser tab's title when it's on a media site, so
    /// YouTube and friends show a track name instead of "Playing on your Mac".
    /// Scoped to an allow-list of media hosts — see `crate::mac_media`.
    #[serde(default = "default_true")]
    pub mac_media_browser: bool,
    // ── Menu bar (AirSync+ "MenuBar Customizations") ─────────────────────
    /// What the tray icon shows beside it: `none` | `battery` | `media` | `device`.
    #[serde(default = "default_menubar_text")]
    pub menubar_text: String,
    /// How a battery reading is rendered: `percent` | `bar` | `both`.
    #[serde(default = "default_battery_style")]
    pub menubar_battery_style: String,
    /// Truncation for menu-bar media text. macOS gives no API to set the status
    /// item's font size from Tauri, so length is the honest lever we do have —
    /// it controls how much menu bar the app occupies, which is what the font
    /// setting is really for.
    #[serde(default = "default_menubar_max_len")]
    pub menubar_max_len: u32,
    /// Album-art layout in the menu-bar panel: `none` | `thumb` | `background`.
    #[serde(default = "default_album_art")]
    pub menubar_album_art: String,

    // ── Low battery alerts ───────────────────────────────────────────────
    /// Raise a macOS banner when the phone's battery drops below the threshold.
    #[serde(default = "default_true")]
    pub low_battery_alert: bool,
    /// Percentage the phone has to fall *below* to trigger the alert.
    #[serde(default = "default_low_battery_pct")]
    pub low_battery_pct: u8,

    /// Virtual-display size for desktop mode, e.g. `"1920x1080"`. Empty means
    /// "let the device choose".
    #[serde(default)]
    pub desktop_display_size: String,
    /// Which mirror the Mirror tab's primary action starts: `wifi` | `adb` | `desktop`.
    #[serde(default = "default_mirror_mode")]
    pub default_mirror_mode: String,

    // ── Mirror quality (both transports) ─────────────────────────────────
    // One set of knobs for Wi-Fi and ADB, because "how good does the mirror
    // look" is one question to a user even though it reaches two encoders.
    // Defaults are the good end, not the safe end: this runs over a LAN, and
    // the previous hardcoded 6 Mbps / 30 fps was chosen for neither.
    /// Video bit rate in Mbps. Wi-Fi passes it to Android's MediaCodec;
    /// ADB passes it to scrcpy as `--video-bit-rate`.
    #[serde(default = "default_mirror_bitrate")]
    pub mirror_bitrate_mbps: u32,
    /// Frame-rate cap. 60 reads as smooth without doubling the bandwidth of 30.
    #[serde(default = "default_mirror_fps")]
    pub mirror_fps: u32,
    /// Longest-edge cap in pixels; `0` means the device's own resolution.
    /// Lowering this is the single biggest bandwidth lever there is.
    #[serde(default)]
    pub mirror_max_size: u32,

    /// Show the floating always-on-top status widget.
    #[serde(default)]
    pub widget_enabled: bool,

    /// Packages whose notifications never raise a macOS banner. The in-app
    /// list still shows them — this mutes the interruption, not the record,
    /// which is the distinction AirSync's per-app notification settings make.
    #[serde(default)]
    pub muted_apps: Vec<String>,

    /// Look for a new release shortly after launch. The check is throttled by
    /// `last_update_check` and never installs anything on its own — it only
    /// badges the Settings tab. Off means the only route is the button.
    #[serde(default = "default_true")]
    pub auto_check_updates: bool,
    /// Epoch ms of the last *completed* automatic check, so relaunching the
    /// app ten times in an afternoon doesn't hit GitHub ten times.
    #[serde(default)]
    pub last_update_check: i64,
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
            mac_fs_enabled: false,
            mac_fs_roots: default_mac_fs_roots(),
            encrypt_link: false,
            remote_control: false,
            mac_info_sync: true,
            mac_media_sync: true,
            mac_media_browser: true,
            muted_apps: Vec::new(),
            menubar_text: default_menubar_text(),
            menubar_battery_style: default_battery_style(),
            menubar_max_len: default_menubar_max_len(),
            menubar_album_art: default_album_art(),
            low_battery_alert: true,
            low_battery_pct: default_low_battery_pct(),
            desktop_display_size: String::new(),
            default_mirror_mode: default_mirror_mode(),
            mirror_bitrate_mbps: default_mirror_bitrate(),
            mirror_fps: default_mirror_fps(),
            mirror_max_size: 0,
            widget_enabled: false,
            auto_check_updates: true,
            last_update_check: 0,
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

#[cfg(test)]
mod tests {
    use super::*;

    /// `save()` writes every field; `load_or_create()` reads it back on the next
    /// launch. If that round-trip ever fails, the `if let Ok(config)` in
    /// `load_or_create` falls through to `Config::default()` — which mints a
    /// FRESH TOKEN and silently unpairs the phone, with the only symptom being
    /// "token mismatch" on every handshake. So the round-trip is worth a test:
    /// the settings on disk today predate most of these fields, meaning the
    /// first setting the user changes is the first time the full shape is ever
    /// written.
    #[test]
    fn a_saved_config_reloads_without_losing_the_token() {
        let mut cfg = Config::default();
        cfg.token = "acad86c1-6041-408c-bce7-2495dd338711".to_string();
        cfg.device_name = Some("MacBook Air M1".into());
        // Exercise the fields added after the on-disk file was last written,
        // including the numeric ones flatten has to buffer past.
        cfg.menubar_max_len = 41;
        cfg.low_battery_pct = 15;
        cfg.menubar_text = "media".into();
        cfg.muted_apps = vec!["com.whatsapp".into()];
        cfg.encrypt_link = true;
        cfg.widget_enabled = true;

        let raw = serde_json::to_string_pretty(&cfg).expect("config must serialize");
        let back: Config = serde_json::from_str(&raw).expect("a config we just wrote must parse");

        assert_eq!(back.token, cfg.token, "a lost token silently unpairs the phone");
        assert_eq!(back.menubar_max_len, 41);
        assert_eq!(back.low_battery_pct, 15);
        assert_eq!(back.menubar_text, "media");
        assert_eq!(back.muted_apps, vec!["com.whatsapp".to_string()]);
        assert!(back.encrypt_link && back.widget_enabled);
    }

    /// The file on disk right now predates every field added since. Loading it
    /// must keep the existing token and fill the rest from defaults rather than
    /// failing the parse.
    #[test]
    fn an_old_config_missing_the_new_fields_still_loads() {
        let legacy = r#"{
            "token": "acad86c1-6041-408c-bce7-2495dd338711",
            "port": 48484,
            "notifications": true,
            "nativeNotifs": true,
            "clipboardSync": true,
            "deviceName": "MacBook Air M1"
        }"#;
        let cfg: Config = serde_json::from_str(legacy).expect("legacy config must still parse");
        assert_eq!(cfg.token, "acad86c1-6041-408c-bce7-2495dd338711");
        assert_eq!(cfg.port, 48484);
        assert_eq!(cfg.menubar_text, "battery", "missing keys fall back to defaults");
        assert_eq!(cfg.low_battery_pct, 20);
        assert!(!cfg.encrypt_link);
    }
}
