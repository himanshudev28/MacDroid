mod adb;
mod appearance;
mod clipboard;
mod config;
mod crypto;
mod discovery;
mod edit_cache;
mod link_quality;
mod mac_fs;
mod mac_remote;
mod mdns;
mod mirror;
mod notifications;
mod photo_sync;
mod protocol;
mod statusbar;
mod transfer;
mod tray;
mod ws_server;

use adb::AdbState;
use appearance::SystemAppearance;
use clipboard::ClipboardGuard;
use config::Config;
use mirror::MirrorState;
use notifications::NotifState;
use serde::Serialize;
use serde_json::{json, Map, Value};
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Manager, State};
use ws_server::SharedState;

pub struct AppState {
    pub config: Mutex<Config>,
}

/// 15s timeout for the two operations the phone can be slow to satisfy —
/// contacts (up to 3000 rows) and sending an SMS (SmsManager round-trip) —
/// matching index.js's raised timeouts for `contacts:list` and `sms:send`.
const SLOW_TIMEOUT: Duration = Duration::from_secs(15);

/// A string setting, falling back when the frontend sends something unexpected.
fn str_setting(v: &Value, fallback: &str) -> String {
    v.as_str().filter(|s| !s.is_empty()).unwrap_or(fallback).to_string()
}

fn map_of(v: Value) -> Map<String, Value> {
    match v {
        Value::Object(m) => m,
        _ => Map::new(),
    }
}

// ── Config / settings ────────────────────────────────────────────────────

#[tauri::command]
fn get_config(state: State<AppState>) -> Config {
    state.config.lock().unwrap().clone()
}

#[tauri::command]
fn get_appearance(state: State<Mutex<SystemAppearance>>) -> SystemAppearance {
    state.lock().unwrap().clone()
}

/// Update one whitelisted setting, persist to `droiddock.json`, return the new
/// config. Mirrors Electron's `settings:set` with its `allowed` key whitelist
/// (deviceName / clipboardSync / notifications / nativeNotifs).
#[tauri::command]
fn set_setting(app: AppHandle, state: State<AppState>, key: String, value: Value) -> Result<Config, String> {
    let mut cfg = state.config.lock().unwrap();
    match key.as_str() {
        "clipboardSync" => cfg.clipboard_sync = value.as_bool().unwrap_or(true),
        "notifications" => cfg.notifications = value.as_bool().unwrap_or(true),
        "nativeNotifs" => cfg.native_notifs = value.as_bool().unwrap_or(true),
        "autoReconnect" => cfg.auto_reconnect = value.as_bool().unwrap_or(true),
        "deviceName" => {
            cfg.device_name = value
                .as_str()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
        }
        "photoSyncEnabled" => cfg.photo_sync_enabled = value.as_bool().unwrap_or(false),
        "photoSyncDest" => {
            cfg.photo_sync_dest = value
                .as_str()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
        }
        // Phase 19: the reverse-file-browsing root allowlist — a plain string
        // list edited wholesale from Settings (add/remove folder), same as
        // any other setting here, just array-shaped instead of scalar.
        "macFsEnabled" => cfg.mac_fs_enabled = value.as_bool().unwrap_or(false),
        "macFsRoots" => {
            cfg.mac_fs_roots = value
                .as_array()
                .map(|arr| arr.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                .unwrap_or_default()
        }
        "encryptLink" => cfg.encrypt_link = value.as_bool().unwrap_or(false),
        "remoteControl" => cfg.remote_control = value.as_bool().unwrap_or(false),
        "menubarText" => cfg.menubar_text = str_setting(&value, "battery"),
        "menubarBatteryStyle" => cfg.menubar_battery_style = str_setting(&value, "percent"),
        "menubarMaxLen" => cfg.menubar_max_len = value.as_u64().unwrap_or(28).clamp(6, 60) as u32,
        "menubarAlbumArt" => cfg.menubar_album_art = str_setting(&value, "thumb"),
        "lowBatteryAlert" => cfg.low_battery_alert = value.as_bool().unwrap_or(true),
        "lowBatteryPct" => cfg.low_battery_pct = value.as_u64().unwrap_or(20).clamp(5, 50) as u8,
        "desktopDisplaySize" => cfg.desktop_display_size = value.as_str().unwrap_or("").trim().to_string(),
        "defaultMirrorMode" => cfg.default_mirror_mode = str_setting(&value, "wifi"),
        "mutedApps" => {
            cfg.muted_apps = value
                .as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                .unwrap_or_default()
        }
        other => return Err(format!("unknown setting: {other}")),
    }
    config::save(&app, &cfg);
    let updated = cfg.clone();
    // Drop the lock before repainting: statusbar::refresh_title re-reads config.
    drop(cfg);
    if key.starts_with("menubar") {
        statusbar::refresh_title(&app);
    }
    Ok(updated)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairingInfo {
    host: String,
    port: u16,
    token: String,
    ips: Vec<String>,
}

/// Same fields wifi.js's `status()`/`lanIPs()` expose — the frontend builds
/// the `droiddock://pair?...` URL itself (see lib/pairing.ts) so the
/// percent-encoding stays visibly identical to wifi.js's `pairingPayload()`.
#[tauri::command]
fn get_pairing_info(state: State<AppState>) -> PairingInfo {
    let config = state.config.lock().unwrap().clone();
    let host = config
        .device_name
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| {
            gethostname::gethostname()
                .to_string_lossy()
                .trim_end_matches(".local")
                .to_string()
        });
    let ips = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|iface| !iface.is_loopback() && matches!(iface.ip(), IpAddr::V4(_)))
        .map(|iface| iface.ip().to_string())
        .collect();
    PairingInfo { host, port: config.port, token: config.token, ips }
}

// ── Phase 4: notifications ───────────────────────────────────────────────

#[tauri::command]
async fn notif_reply(state: State<'_, SharedState>, key: String, text: String) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "reply", "key": key, "text": text })).await;
    Ok(())
}

/// The current link state, for views that mount after the phone connected —
/// `wifi-status` only fires on a *change*, so subscribing is not enough.
#[tauri::command]
async fn wifi_status(state: State<'_, SharedState>) -> Result<ws_server::WifiStatus, String> {
    Ok(ws_server::status(state.inner()).await)
}

/// Fire the Nth action button of a notification back on the phone. Index-based
/// because the Mac only ever received labels, and two buttons can share one.
#[tauri::command]
async fn notif_action(state: State<'_, SharedState>, key: String, index: u32) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "notif-action", "key": key, "index": index })).await;
    Ok(())
}

#[tauri::command]
async fn notif_dismiss(state: State<'_, SharedState>, key: String) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "dismiss", "key": key })).await;
    Ok(())
}

// ── Phase 5: files ───────────────────────────────────────────────────────

#[tauri::command]
async fn fs_list(state: State<'_, SharedState>, path: String) -> Result<Value, String> {
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "fs-list", "path": path }))).await?;
    Ok(reply.get("entries").cloned().unwrap_or_else(|| json!([])))
}

#[tauri::command]
async fn fs_delete(state: State<'_, SharedState>, path: String) -> Result<(), String> {
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "fs-delete", "path": path }))).await?;
    match reply.get("error").and_then(Value::as_str) {
        Some(e) => Err(e.to_string()),
        None => Ok(()),
    }
}

#[tauri::command]
async fn fs_rename(state: State<'_, SharedState>, path: String, new_name: String) -> Result<Value, String> {
    let reply = ws_server::request_default(
        &state,
        map_of(json!({ "type": "fs-rename", "path": path, "newName": new_name })),
    )
    .await?;
    match reply.get("error").and_then(Value::as_str) {
        Some(e) => Err(e.to_string()),
        None => Ok(reply),
    }
}

/// Download a phone file into the Mac Downloads folder. Returns the saved path.
#[tauri::command]
async fn fs_pull(app: AppHandle, state: State<'_, SharedState>, path: String, name: String) -> Result<String, String> {
    let dir = transfer::download_dir(&app).ok_or("no Downloads directory")?;
    let dest = dir.join(sanitize(&name));
    let saved = transfer::pull(app.clone(), state.inner().clone(), path, dest).await?;
    Ok(saved.to_string_lossy().to_string())
}

/// Upload a local file to the phone under `dest` (a phone directory path).
#[tauri::command]
async fn fs_push(app: AppHandle, state: State<'_, SharedState>, local_path: String, dest: String) -> Result<(), String> {
    // Not an overwrite: a user dropping a file into a folder expects the phone's
    // existing same-named file to survive, which is what uniqueDest() gives.
    transfer::push(app.clone(), state.inner().clone(), local_path, dest, false).await
}

#[tauri::command]
async fn fs_cancel(state: State<'_, SharedState>, transfer_id: u32) -> Result<(), String> {
    transfer::cancel(&state, transfer_id).await;
    Ok(())
}

// ── Phase 6: photos ──────────────────────────────────────────────────────

#[tauri::command]
async fn photos_list(state: State<'_, SharedState>, offset: u32, limit: u32) -> Result<Value, String> {
    let reply = ws_server::request_default(
        &state,
        map_of(json!({ "type": "photos-list", "offset": offset, "limit": limit })),
    )
    .await?;
    Ok(reply.get("items").cloned().unwrap_or_else(|| json!([])))
}

/// Fetch a thumbnail and return it as a `data:image/jpeg;base64,...` URL, so the
/// frontend `<img>` can use it directly (same shape index.js hands the renderer).
#[tauri::command]
async fn photo_thumb(state: State<'_, SharedState>, id: i64, kind: String) -> Result<String, String> {
    let bytes = ws_server::request_thumb(&state, id, &kind).await?;
    Ok(format!("data:image/jpeg;base64,{}", base64_encode(&bytes)))
}

/// Pull a full-res photo/video to a temp dir and open it in the OS default app
/// (Preview / QuickTime), matching Electron's `photos:open` → `shell.openPath`.
#[tauri::command]
async fn photo_open(app: AppHandle, state: State<'_, SharedState>, path: String, name: String) -> Result<(), String> {
    let dir = std::env::temp_dir().join("DroidDock");
    let dest = dir.join(sanitize(&name));
    eprintln!("[photo_open] pulling name={name:?} phone_path={path:?} -> {dest:?}");
    let saved = transfer::pull(app.clone(), state.inner().clone(), path, dest)
        .await
        .map_err(|e| {
            eprintln!("[photo_open] pull error: {e}");
            format!("download failed: {e}")
        })?;
    eprintln!("[photo_open] pulled to {saved:?}");

    // Make sure the pull actually produced a real file before handing it to the
    // OS — an empty/missing file would "open" into nothing and look like a no-op.
    match std::fs::metadata(&saved) {
        Ok(m) if m.len() > 0 => {}
        Ok(_) => return Err("downloaded file is empty".into()),
        Err(e) => return Err(format!("downloaded file missing: {e}")),
    }

    // Open in the OS default app (Preview / QuickTime) via `open`, exactly like
    // Electron's `shell.openPath`. tokio::process keeps it off the async thread.
    let saved_str = saved.to_string_lossy().to_string();
    eprintln!("[photo_open] running: open {saved_str:?}");
    let status = tokio::process::Command::new("open")
        .arg(&saved_str)
        .status()
        .await
        .map_err(|e| format!("could not launch `open`: {e}"))?;
    eprintln!("[photo_open] open exited: {status}");
    if !status.success() {
        return Err(format!("`open` could not display {name}"));
    }
    Ok(())
}

// ── Phase 17: open-in-place with edit-writeback ─────────────────────────────

#[tauri::command]
async fn fs_open_in_place(
    app: AppHandle,
    state: State<'_, SharedState>,
    cache: State<'_, Option<edit_cache::EditCache>>,
    path: String,
) -> Result<(), String> {
    let cache = cache.inner().clone().ok_or("Edit cache unavailable")?;
    edit_cache::open_in_place(app, state.inner().clone(), cache, path).await
}

/// Phone paths with an edit-in-place save that hasn't synced back yet, so the
/// frontend can hydrate its pending-sync badge on mount instead of only
/// picking up pending state from a future live `edit-sync` event.
#[tauri::command]
async fn fs_pending_syncs(cache: State<'_, Option<edit_cache::EditCache>>) -> Result<Vec<String>, String> {
    match cache.inner() {
        Some(cache) => Ok(cache.pending_phone_paths().await),
        None => Ok(vec![]),
    }
}

// ── Phase 7: messages (SMS) ──────────────────────────────────────────────

#[tauri::command]
async fn sms_threads(state: State<'_, SharedState>) -> Result<Value, String> {
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "sms-threads" }))).await?;
    Ok(reply.get("threads").cloned().unwrap_or_else(|| json!([])))
}

#[tauri::command]
async fn sms_messages(state: State<'_, SharedState>, thread_id: i64) -> Result<Value, String> {
    let reply = ws_server::request_default(
        &state,
        map_of(json!({ "type": "sms-messages", "threadId": thread_id })),
    )
    .await?;
    Ok(json!({
        "messages": reply.get("messages").cloned().unwrap_or_else(|| json!([])),
        "address": reply.get("address").cloned().unwrap_or_else(|| json!("")),
    }))
}

#[tauri::command]
async fn sms_send(state: State<'_, SharedState>, address: String, text: String) -> Result<(), String> {
    let reply = ws_server::request(
        &state,
        map_of(json!({ "type": "sms-send", "address": address, "text": text })),
        SLOW_TIMEOUT,
    )
    .await?;
    match reply.get("error").and_then(Value::as_str) {
        Some(e) => Err(e.to_string()),
        None => Ok(()),
    }
}

// ── Phase 18: photo auto-sync ────────────────────────────────────────────

/// Manual "back-fill existing library" action — runs the same diff-and-pull
/// as the automatic path but ignores `photo_sync_enabled`/caps (an explicit
/// one-off the user asked for from Settings), still honors global Pause.
#[tauri::command]
async fn photo_sync_backfill(
    app: AppHandle,
    state: State<'_, SharedState>,
    photo: State<'_, Option<photo_sync::PhotoSync>>,
    app_state: State<'_, AppState>,
) -> Result<(), String> {
    let photo = photo.inner().clone().ok_or("Photo sync unavailable")?;
    let cfg = app_state.config.lock().unwrap().clone();
    let (device_key, legacy) = ws_server::current_phone_keys(state.inner())
        .await
        .ok_or("No phone connected — pair a phone before backfilling")?;
    photo_sync::backfill(app, state.inner().clone(), photo, cfg, device_key, legacy).await
}

// ── Phase 8: contacts ────────────────────────────────────────────────────

#[tauri::command]
async fn contacts_list(state: State<'_, SharedState>) -> Result<Value, String> {
    let reply = ws_server::request(&state, map_of(json!({ "type": "contacts" })), SLOW_TIMEOUT).await?;
    Ok(reply.get("contacts").cloned().unwrap_or_else(|| json!([])))
}

// ── Phase 9: calls ───────────────────────────────────────────────────────

/// Dial a number. Mirrors `contact:call`: prefer a live ADB device (direct
/// `am start -a android.intent.action.CALL` + starts call-state polling for
/// the CallOverlay's live controls, Phase 13); fall back to the Wi-Fi
/// `action-call` push (fire-and-forget, no reply, no remote hang-up/mute)
/// when no ADB device is connected.
#[tauri::command]
async fn action_call(app: AppHandle, state: State<'_, SharedState>, adb_state: State<'_, AdbState>, number: String) -> Result<(), String> {
    let adb_serial = {
        let adb = adb_state.adb.lock().unwrap().clone();
        let serial = adb_state.devices.lock().unwrap().iter().find(|d| d.state == "device").map(|d| d.serial.clone());
        adb.zip(serial)
    };
    if let Some((adb, serial)) = adb_serial {
        adb::phone_call(&adb, &serial, &number).await?;
        adb::start_call_polling(app, serial);
        return Ok(());
    }
    if ws_server::push(&state, json!({ "type": "action-call", "number": number })).await {
        Ok(())
    } else {
        Err("No connection — connect via ADB or link the phone app first".into())
    }
}

// ── Phase 10: media remote ───────────────────────────────────────────────

/// Outbound transport/volume command. `value` is an integer (ms for `seek`,
/// a volume step 0..volMax for `setvol`, ignored for play/pause/next/prev) —
/// ConnectionManager.kt reads it via `msg.optInt("value")`.
#[tauri::command]
async fn media_cmd(state: State<'_, SharedState>, cmd: String, value: i64) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "media-cmd", "cmd": cmd, "value": value })).await;
    Ok(())
}

// ── Tier B: wallpaper + apps ─────────────────────────────────────────────

/// The phone's wallpaper as a data URL, for the phone card's backdrop.
/// Requested once per connection by the frontend and cached there — the image
/// changes rarely and costs ~100 KB, so it is deliberately not pushed.
#[tauri::command]
async fn wallpaper_get(state: State<'_, SharedState>) -> Result<String, String> {
    let reply = ws_server::request_default(&state, json_map(json!({ "type": "wallpaper" }))).await?;
    if let Some(err) = reply.get("error").and_then(Value::as_str) {
        return Err(err.to_string());
    }
    let data = reply
        .get("data")
        .and_then(Value::as_str)
        .ok_or("Phone sent no wallpaper")?;
    Ok(format!("data:image/jpeg;base64,{data}"))
}

#[derive(serde::Serialize)]
struct PhoneApp {
    pkg: String,
    label: String,
}

/// Every launchable app on the phone, sorted by label (the phone sorts; this
/// just forwards). Backs the Apps grid.
#[tauri::command]
async fn apps_list(state: State<'_, SharedState>) -> Result<Vec<PhoneApp>, String> {
    let reply = ws_server::request_default(&state, json_map(json!({ "type": "apps-list" }))).await?;
    if let Some(err) = reply.get("error").and_then(Value::as_str) {
        return Err(err.to_string());
    }
    let apps = reply
        .get("apps")
        .and_then(Value::as_array)
        .ok_or("Phone sent no app list")?;
    Ok(apps
        .iter()
        .filter_map(|a| {
            Some(PhoneApp {
                pkg: a.get("pkg")?.as_str()?.to_string(),
                label: a.get("label").and_then(Value::as_str).unwrap_or_default().to_string(),
            })
        })
        .collect())
}

/// One app's icon as a PNG data URL. PNG, not JPEG: adaptive icons are
/// transparent outside the mask and a JPEG matte would render as a box.
#[tauri::command]
async fn app_icon(state: State<'_, SharedState>, pkg: String) -> Result<String, String> {
    let bytes = ws_server::request_app_icon(&state, &pkg).await?;
    Ok(format!("data:image/png;base64,{}", base64_encode(&bytes)))
}

/// Open an app on the phone. Fire-and-forget — the phone has no reply for this,
/// and the observable result is the phone's own screen.
#[tauri::command]
async fn app_launch(state: State<'_, SharedState>, pkg: String) -> Result<(), String> {
    if !ws_server::push(&state, json!({ "type": "app-launch", "pkg": pkg })).await {
        return Err("Phone not connected over Wi-Fi".into());
    }
    Ok(())
}

// ── helpers ──────────────────────────────────────────────────────────────

/// `json!({...})` → the owned field map `ws_server::request` takes.
fn json_map(value: Value) -> serde_json::Map<String, Value> {
    match value {
        Value::Object(map) => map,
        _ => serde_json::Map::new(),
    }
}

fn sanitize(name: &str) -> String {
    name.replace(['/', '\\'], "_")
}

/// Minimal base64 (standard alphabet, padded) for thumbnail data URLs — avoids
/// pulling in a crate for a handful of KiB of JPEG per thumb. Also reused by
/// `mirror::on_frame` for the same reason (base64-in-event mirror frames).
pub(crate) fn base64_encode(bytes: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((bytes.len() + 2) / 3 * 4);
    for chunk in bytes.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = (b[0] as u32) << 16 | (b[1] as u32) << 8 | b[2] as u32;
        out.push(T[(n >> 18 & 63) as usize] as char);
        out.push(T[(n >> 12 & 63) as usize] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6 & 63) as usize] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[(n & 63) as usize] as char } else { '=' });
    }
    out
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            let config = config::load_or_create(app.handle());
            let port = config.port;
            app.manage(AppState {
                config: Mutex::new(config),
            });

            // Cross-cutting managed state for the feature modules.
            app.manage(ClipboardGuard::default());
            app.manage(NotifState::default());
            app.manage(MirrorState::default());
            app.manage(link_quality::LinkQuality::default());
            app.manage(statusbar::StatusState::default());
            app.manage(ws_server::LastPhoneName::default());
            app.manage(AdbState::default());

            // Phase 17: clears stale edit-cache sessions (keeping any still-pending
            // unsynced edit) and starts a fresh session dir for this launch. Managed
            // as an Option, same graceful-degradation spirit as `adb::init` below —
            // if the data dir is ever unavailable, open-in-place just disables
            // itself (`fs_open_in_place` returns a clean error) instead of a panic.
            let edit_cache: Option<edit_cache::EditCache> = app
                .path()
                .app_data_dir()
                .map(|dir| edit_cache::init(dir.join("edit-cache")))
                .map_err(|e| eprintln!("edit_cache: app data dir unavailable ({e}) — open-in-place disabled"))
                .ok();
            app.manage(edit_cache);

            // Phase 18: same graceful-degradation shape as `edit_cache` above —
            // if the app data dir is unavailable the ledger just can't persist,
            // so photo sync disables itself instead of panicking.
            let photo_sync: Option<photo_sync::PhotoSync> = app
                .path()
                .app_data_dir()
                .map(photo_sync::init)
                .map_err(|e| eprintln!("photo_sync: app data dir unavailable ({e}) — photo sync disabled"))
                .ok();
            app.manage(photo_sync);

            let ws_state: SharedState = SharedState::default();
            app.manage(ws_state.clone());
            let app_handle: AppHandle = app.handle().clone();
            // Every long-lived networking task runs on a runtime this thread
            // owns and blocks on for the app's lifetime, isolating the link from
            // whatever else the app's shared runtime is doing.
            //
            // Historical note, because the comment here used to claim otherwise:
            // the "`TcpListener::accept()` is entered and never wakes" symptom
            // that motivated this thread was NOT a Tauri runtime defect. Its
            // cause was `adb::tools_status` locking one `std::sync::Mutex` twice
            // inside a single struct literal (see `adb.rs`) — that deadlocked the
            // main thread, and every async worker that subsequently called into
            // the tray blocked behind it, until no thread was left to poll the
            // runtime's IO driver and `accept()` stopped waking. The lock bug is
            // fixed; this thread is kept for the isolation, not as a workaround.
            {
                let h = app_handle.clone();
                let ws = ws_state.clone();
                std::thread::Builder::new()
                    .name("droiddock-net".into())
                    .spawn(move || {
                        let rt = tokio::runtime::Builder::new_multi_thread()
                            .enable_all()
                            .thread_name("droiddock-net")
                            .build()
                            .expect("failed to build the networking runtime");
                        rt.block_on(async move {
                            tokio::spawn(discovery::run(h.clone(), port + 1));
                            // Second discovery path: works on networks that drop
                            // directed broadcast. Additive — the UDP responder
                            // above is unchanged.
                            tokio::spawn(mdns::run(h.clone(), port));
                            tokio::spawn(link_quality::run(h.clone(), ws.clone()));
                            // Phase 3: 1s clipboard watcher (outbound Mac→phone).
                            tokio::spawn(clipboard::run(h.clone(), ws.clone()));
                            // Awaited directly (not spawned) so this thread
                            // drives the accept loop itself for the app's life.
                            ws_server::run(h, ws, port).await;
                        });
                    })
                    .expect("failed to start the networking thread");
            }

            // Phase 13: resolve/auto-download adb + scrcpy in the background —
            // Wi-Fi features (everything through Phase 12) work with none of
            // this installed; ADB just unlocks the power-user extras.
            if let Ok(data_dir) = app_handle.path().app_data_dir() {
                // Same runtime as the networking thread: adb shells out to
                // child processes, which need a working I/O driver too.
                tauri::async_runtime::spawn(adb::init(app_handle.clone(), data_dir.join("tools")));
            }

            // Phase 14: auto-resume a timed Mac-initiated pause once it expires.
            // Reopen the widget where it was, and paint the menu-bar title
            // from the persisted settings before any phone connects.
            tray::apply_widget(&app_handle);
            statusbar::refresh_title(&app_handle);
            tauri::async_runtime::spawn(tray::expire_loop(app_handle.clone()));

            let system_appearance = appearance::read();
            // Respect the user's actual "reduce transparency" setting: WebKit has no
            // CSS media query for it, so this is the only place it can be honored —
            // clearing effects here means the window falls back to a solid background.
            if let Some(window) = app.get_webview_window("main") {
                if system_appearance.reduce_transparency {
                    let _ = window.set_effects(None);
                }
            }
            app.manage(Mutex::new(system_appearance));

            tray::build(app.handle())?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_config,
            get_appearance,
            get_pairing_info,
            set_setting,
            notif_reply,
            notif_dismiss,
            notif_action,
            wifi_status,
            fs_list,
            fs_delete,
            fs_rename,
            fs_pull,
            fs_push,
            fs_cancel,
            photos_list,
            photo_thumb,
            photo_open,
            fs_open_in_place,
            fs_pending_syncs,
            photo_sync_backfill,
            sms_threads,
            sms_messages,
            sms_send,
            contacts_list,
            action_call,
            media_cmd,
            clipboard::clipboard_push_now,
            wallpaper_get,
            apps_list,
            app_icon,
            app_launch,
            mirror::mirror_popout,
            mirror::mirror_stop,
            mirror::mirror_focus,
            mirror::mirror_set_on_top,
            mirror::mirror_input,
            mirror::mirror_attach,
            adb::adb_tools,
            adb::adb_scrcpy_install,
            adb::adb_devices,
            adb::adb_device_info,
            adb::adb_go_wireless,
            adb::adb_pair_wireless,
            adb::adb_unpair,
            adb::adb_paired_info,
            adb::adb_reconnect_now,
            adb::adb_qr_pair_start,
            adb::adb_qr_pair_cancel,
            adb::adb_camera,
            adb::adb_mirror,
            adb::adb_desktop,
            adb::adb_mirror_app,
            adb::adb_screenshot,
            adb::adb_volume_get,
            adb::adb_volume_set,
            adb::adb_call_end,
            adb::adb_call_speaker,
            adb::adb_call_mute,
            adb::adb_call_dtmf,
            adb::adb_call_start_polling,
            tray::pause_set,
            tray::autostart_get,
            tray::autostart_set,
            tray::menubar_hide,
            tray::open_main_window,
            tray::widget_set,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
