//! The command functions that used to live in `lib.rs` (moved verbatim apart
//! from Tauri-specific plumbing). `dispatch.rs` routes host invokes to them.

use crate::adb::{self, AdbState};
use crate::config::{self, Config};
use crate::host::{AppHandle, Manager, State};
use crate::ws_server::{self, SharedState};
use crate::{capture, edit_cache, health, photo_sync, statusbar, transfer, transfer_log, AppState};
use serde::Serialize;
use serde_json::{json, Map, Value};
use std::net::IpAddr;
use std::time::Duration;

/// 15s timeout for the two operations the phone can be slow to satisfy —
/// contacts (up to 3000 rows) and sending an SMS (SmsManager round-trip) —
/// matching index.js's raised timeouts for `contacts:list` and `sms:send`.
const SLOW_TIMEOUT: Duration = Duration::from_secs(15);

/// A string setting, falling back when the frontend sends something unexpected.
fn str_setting(v: &Value, fallback: &str) -> String {
    v.as_str().filter(|s| !s.is_empty()).unwrap_or(fallback).to_string()
}

pub(crate) fn map_of(v: Value) -> Map<String, Value> {
    match v {
        Value::Object(m) => m,
        _ => Map::new(),
    }
}

// ── Config / settings ────────────────────────────────────────────────────

pub fn get_config(state: State<AppState>) -> Config {
    state.config.lock().unwrap().clone()
}

/// Update one whitelisted setting, persist to `droiddock.json`, return the new
/// config. Mirrors Electron's `settings:set` with its `allowed` key whitelist
/// (deviceName / clipboardSync / notifications / nativeNotifs).
pub fn set_setting(app: AppHandle, state: State<AppState>, key: String, value: Value) -> Result<Config, String> {
    // Wake any loop parked on a toggle (mac_media, adb scanner after
    // autoReconnect flips on).
    crate::pacing::poke_settings();
    if key == "autoReconnect" {
        crate::pacing::poke_reconnect();
    }
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
        "quickShareEnabled" => cfg.quickshare_enabled = value.as_bool().unwrap_or(false),
        "macFsRoots" => {
            cfg.mac_fs_roots = value
                .as_array()
                .map(|arr| arr.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                .unwrap_or_default()
        }
        "macInfoSync" => cfg.mac_info_sync = value.as_bool().unwrap_or(true),
        "macMediaSync" => cfg.mac_media_sync = value.as_bool().unwrap_or(true),
        "macMediaBrowser" => cfg.mac_media_browser = value.as_bool().unwrap_or(true),
        // Read once at mount time (webdav.rs), so it applies on the next mount.
        "webdavWritable" => cfg.webdav_writable = value.as_bool().unwrap_or(false),
        "encryptLink" => cfg.encrypt_link = value.as_bool().unwrap_or(false),
        "remoteControl" => cfg.remote_control = value.as_bool().unwrap_or(false),
        "menubarText" => cfg.menubar_text = str_setting(&value, "battery"),
        "menubarBatteryStyle" => cfg.menubar_battery_style = str_setting(&value, "percent"),
        "menubarMaxLen" => cfg.menubar_max_len = value.as_u64().unwrap_or(28).clamp(6, 60) as u32,
        "menubarAlbumArt" => cfg.menubar_album_art = str_setting(&value, "thumb"),
        "lowBatteryAlert" => cfg.low_battery_alert = value.as_bool().unwrap_or(true),
        "lowBatteryPct" => cfg.low_battery_pct = value.as_u64().unwrap_or(20).clamp(5, 50) as u8,
        "desktopDisplaySize" => cfg.desktop_display_size = value.as_str().unwrap_or("").trim().to_string(),
        // Virtual-display behaviour. `desktopUiMode` is the one that decides
        // whether an app opens as a desktop window or a magnified phone.
        "desktopUiMode" => cfg.desktop_ui_mode = str_setting(&value, "desktop"),
        "desktopFlex" => cfg.desktop_flex = value.as_bool().unwrap_or(true),
        "appWindowChrome" => cfg.app_window_chrome = value.as_bool().unwrap_or(false),
        "appWindowKeepAlive" => cfg.app_window_keep_alive = value.as_bool().unwrap_or(true),
        "mirrorCodec" => cfg.mirror_codec = str_setting(&value, "h264"),
        "mirrorAudio" => cfg.mirror_audio = value.as_bool().unwrap_or(true),
        "scrcpyUhid" => cfg.scrcpy_uhid = value.as_bool().unwrap_or(false),
        "scrcpyStayAwake" => cfg.scrcpy_stay_awake = value.as_bool().unwrap_or(false),
        "scrcpyTurnScreenOff" => cfg.scrcpy_turn_screen_off = value.as_bool().unwrap_or(false),
        "scrcpyAlwaysOnTop" => cfg.scrcpy_always_on_top = value.as_bool().unwrap_or(false),
        "pinnedApps" => {
            cfg.pinned_apps = value
                .as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                .unwrap_or_default()
        }
        "openAppsOnMac" => cfg.open_apps_on_mac = value.as_bool().unwrap_or(false),
        // Mirror quality. Clamped here as well as at each use site — this is
        // the boundary the frontend can write through, so it's the one place
        // that has to hold.
        "mirrorBitrateMbps" => {
            cfg.mirror_bitrate_mbps = value.as_u64().unwrap_or(12).clamp(1, 50) as u32
        }
        "mirrorFps" => cfg.mirror_fps = value.as_u64().unwrap_or(60).clamp(15, 120) as u32,
        "mirrorMaxSize" => cfg.mirror_max_size = value.as_u64().unwrap_or(0).min(4096) as u32,
        "defaultMirrorMode" => cfg.default_mirror_mode = str_setting(&value, "wifi"),
        "autoCheckUpdates" => cfg.auto_check_updates = value.as_bool().unwrap_or(true),
        "mutedApps" => {
            cfg.muted_apps = value
                .as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                .unwrap_or_default();
            // Keep tiers in step: the list is authoritative for "muted".
            let list = cfg.muted_apps.clone();
            cfg.notif_tiers.retain(|k, t| t != "muted" || list.contains(k));
            for p in list {
                cfg.notif_tiers.insert(p, "muted".into());
            }
        }
        // `{pkg: "alert"|"silent"|"muted"}`. `muted_apps` is kept in step (it is
        // the pre-tiers list); unknown tier values are dropped.
        "notifTiers" => {
            cfg.notif_tiers = value
                .as_object()
                .map(|o| {
                    o.iter()
                        .filter_map(|(k, v)| {
                            let t = v.as_str()?;
                            matches!(t, "alert" | "silent" | "muted")
                                .then(|| (k.clone(), t.to_string()))
                        })
                        .collect()
                })
                .unwrap_or_default();
            let tiers = cfg.notif_tiers.clone();
            cfg.muted_apps.retain(|p| !tiers.contains_key(p));
            let mut muted: Vec<String> =
                tiers.iter().filter(|(_, t)| *t == "muted").map(|(k, _)| k.clone()).collect();
            muted.sort();
            cfg.muted_apps.extend(muted);
        }
        "syncNotifDismissals" => cfg.sync_notif_dismissals = value.as_bool().unwrap_or(true),
        // BLE fallback (docs/BLE-NEARBY-DESIGN.md §8): stamped so the newest change wins when synced.
        "connectionMode" => {
            cfg.connection_mode = if value.as_str() == Some("fallback") { "fallback" } else { "network" }.to_string();
            cfg.connection_mode_at = config::now_ms();
        }
        "bleAutoReturn" => {
            cfg.ble_auto_return = value.as_bool().unwrap_or(true);
            cfg.connection_mode_at = config::now_ms();
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
    if key == "connectionMode" || key == "bleAutoReturn" {
        crate::ble_policy::mode_changed_locally(&app);
    }
    // Several settings here decide what the Mac *advertises* it can do, and the
    // phone only ever heard that list once, in `welcome`. So turning "Mac
    // files" (or remote control, or Mac apps) on while the phone was already
    // linked left the phone's UI unchanged — the Mac Files tab is gated on the
    // `macfs` cap and simply never appeared, which read as "I granted the
    // folders and the phone still can't see them".
    //
    // Rather than maintain a list of which keys matter, re-advertise after
    // every save and let `push_caps` stay silent when the list is unchanged.
    // It is a single small JSON message on a user-driven action.
    {
        let app2 = app.clone();
        crate::host::async_runtime::spawn(async move {
            let ws = app2.state::<ws_server::SharedState>().inner().clone();
            ws_server::push_caps(&app2, &ws).await;
        });
    }
    Ok(updated)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingInfo {
    host: String,
    port: u16,
    token: String,
    ips: Vec<String>,
    /// TLS identity fingerprint the QR carries (empty if TLS could not start).
    fp: String,
}

/// Same fields wifi.js's `status()`/`lanIPs()` expose — the frontend builds
/// the `droiddock://pair?...` URL itself (see lib/pairing.ts) so the
/// percent-encoding stays visibly identical to wifi.js's `pairingPayload()`.
pub fn get_pairing_info(app: &AppHandle, state: State<AppState>) -> PairingInfo {
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
    let ips = crate::mdns::selected_ipv4s(&config.server_iface).into_iter().map(|ip| ip.to_string()).collect();
    let fp = crate::tls::identity(app).map(|i| i.fp.clone()).unwrap_or_default();
    PairingInfo { host, port: config.port, token: config.token, ips, fp }
}

/// `tls_status`: how the live phone link is protected, plus the Mac's TLS fingerprint.
pub async fn tls_status(app: &AppHandle, state: &SharedState) -> Value {
    let fp = crate::tls::identity(app).map(|i| i.fp.clone()).unwrap_or_default();
    json!({ "linkSecurity": ws_server::link_security(state).await, "fp": fp })
}

// ── Phase 4: notifications ───────────────────────────────────────────────

pub async fn notif_reply(state: State<'_, SharedState>, key: String, text: String) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "reply", "key": key, "text": text })).await;
    Ok(())
}

/// The current link state, for views that mount after the phone connected —
/// `wifi-status` only fires on a *change*, so subscribing is not enough.
pub async fn wifi_status(state: State<'_, SharedState>) -> Result<ws_server::WifiStatus, String> {
    Ok(ws_server::status(state.inner()).await)
}

/// Fire the Nth action button of a notification back on the phone. Index-based
/// because the Mac only ever received labels, and two buttons can share one.
pub async fn notif_action(state: State<'_, SharedState>, key: String, index: u32) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "notif-action", "key": key, "index": index })).await;
    Ok(())
}

/// Open a notification's own tap action on the phone (the exact chat). Gated on the phone's
/// "notifopen" cap by the Mac UI. Returns the phone's `{ok, locked?}` answer.
pub async fn notif_open(state: State<'_, SharedState>, key: String, display_id: Option<i64>) -> Result<Value, String> {
    let mut msg = json!({ "type": "notif-open", "key": key });
    // Optional, gated on the phone's "notifopendisplay" cap by the Mac UI: fire the tap action on that display.
    if let Some(d) = display_id.filter(|d| *d > 0) {
        msg["displayId"] = json!(d);
    }
    let reply = ws_server::request_default(&state, map_of(msg)).await?;
    Ok(json!({
        "ok": reply.get("ok").and_then(Value::as_bool).unwrap_or(false),
        "locked": reply.get("locked").and_then(Value::as_bool).unwrap_or(false),
    }))
}

pub async fn notif_dismiss(state: State<'_, SharedState>, key: String) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "dismiss", "key": key })).await;
    Ok(())
}

// ── Phase 5: files ───────────────────────────────────────────────────────

pub async fn fs_list(state: State<'_, SharedState>, path: String) -> Result<Value, String> {
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "fs-list", "path": path }))).await?;
    Ok(reply.get("entries").cloned().unwrap_or_else(|| json!([])))
}

pub async fn fs_delete(state: State<'_, SharedState>, path: String) -> Result<(), String> {
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "fs-delete", "path": path }))).await?;
    match reply.get("error").and_then(Value::as_str) {
        Some(e) => Err(e.to_string()),
        None => Ok(()),
    }
}

/// Create folder `name` inside the phone directory `path`. Returns the new
/// folder's path. Needs a phone that advertises `fsmkdir`.
pub async fn fs_mkdir(state: State<'_, SharedState>, path: String, name: String) -> Result<String, String> {
    if !ws_server::phone_has_cap(&state, "fsmkdir").await {
        return Err("This phone's DroidDock is too old to create folders. Update the Android app.".into());
    }
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "fs-mkdir", "path": path, "name": name }))).await?;
    match reply.get("error").and_then(Value::as_str) {
        Some(e) => Err(e.to_string()),
        None => Ok(reply.get("path").and_then(Value::as_str).unwrap_or("").to_string()),
    }
}

pub async fn fs_rename(state: State<'_, SharedState>, path: String, new_name: String) -> Result<Value, String> {
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
pub async fn fs_pull(app: AppHandle, state: State<'_, SharedState>, path: String, name: String) -> Result<String, String> {
    let dir = transfer::download_dir(&app).ok_or("no Downloads directory")?;
    let dest = dir.join(sanitize(&name));
    let saved = transfer::pull(app.clone(), state.inner().clone(), path, dest, Some(transfer_log::Source::Files)).await?;
    Ok(saved.to_string_lossy().to_string())
}

/// Upload a local file to the phone under `dest` (a phone directory path).
pub async fn fs_push(app: AppHandle, state: State<'_, SharedState>, local_path: String, dest: String) -> Result<(), String> {
    // Not an overwrite: a user dropping a file into a folder expects the phone's
    // existing same-named file to survive, which is what uniqueDest() gives.
    transfer::push(app.clone(), state.inner().clone(), local_path, dest, false, transfer_log::Source::Files).await
}

pub async fn fs_cancel(app: AppHandle, state: State<'_, SharedState>, transfer_id: u32) -> Result<(), String> {
    // Recorded first, so the Transfers page shows "Cancelled", not a lost
    // connection, when the transfer ends.
    transfer_log::note_cancel(&app, transfer_id, transfer_log::CancelledBy::Mac);
    transfer::cancel(&state, transfer_id).await;
    Ok(())
}

// ── Phase 6: photos ──────────────────────────────────────────────────────

pub async fn photos_list(state: State<'_, SharedState>, offset: u32, limit: u32) -> Result<Value, String> {
    let reply = ws_server::request_default(
        &state,
        map_of(json!({ "type": "photos-list", "offset": offset, "limit": limit })),
    )
    .await?;
    Ok(reply.get("items").cloned().unwrap_or_else(|| json!([])))
}

/// Fetch a thumbnail and return it as a `data:image/jpeg;base64,...` URL, so the
/// frontend `<img>` can use it directly (same shape index.js hands the renderer).
pub async fn photo_thumb(state: State<'_, SharedState>, id: i64, kind: String) -> Result<String, String> {
    let bytes = ws_server::request_thumb(&state, id, &kind).await?;
    Ok(format!("data:image/jpeg;base64,{}", base64_encode(&bytes)))
}

/// Pull a full-res photo/video to a temp dir and open it in the OS default app
/// (Preview / QuickTime), matching Electron's `photos:open` → `shell.openPath`.
pub async fn photo_open(app: AppHandle, state: State<'_, SharedState>, path: String, name: String) -> Result<(), String> {
    let dir = std::env::temp_dir().join("DroidDock");
    let dest = dir.join(sanitize(&name));
    eprintln!("[photo_open] pulling name={name:?} phone_path={path:?} -> {dest:?}");
    let saved = transfer::pull(app.clone(), state.inner().clone(), path, dest, Some(transfer_log::Source::Files))
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

pub async fn fs_open_in_place(
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
pub async fn fs_pending_syncs(cache: State<'_, Option<edit_cache::EditCache>>) -> Result<Vec<String>, String> {
    match cache.inner() {
        Some(cache) => Ok(cache.pending_phone_paths().await),
        None => Ok(vec![]),
    }
}

// ── Phase 7: messages (SMS) ──────────────────────────────────────────────

pub async fn sms_threads(state: State<'_, SharedState>) -> Result<Value, String> {
    let reply = ws_server::request_default(&state, map_of(json!({ "type": "sms-threads" }))).await?;
    Ok(reply.get("threads").cloned().unwrap_or_else(|| json!([])))
}

pub async fn sms_messages(state: State<'_, SharedState>, thread_id: i64) -> Result<Value, String> {
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

pub async fn sms_send(state: State<'_, SharedState>, address: String, text: String) -> Result<(), String> {
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
pub async fn photo_sync_backfill(
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

pub async fn contacts_list(state: State<'_, SharedState>) -> Result<Value, String> {
    let reply = ws_server::request(&state, map_of(json!({ "type": "contacts" })), SLOW_TIMEOUT).await?;
    Ok(reply.get("contacts").cloned().unwrap_or_else(|| json!([])))
}

// ── Phase 9: calls ───────────────────────────────────────────────────────

/// Dial a number. Mirrors `contact:call`: prefer a live ADB device (direct
/// `am start -a android.intent.action.CALL` + starts call-state polling for
/// the CallOverlay's live controls, Phase 13); fall back to the Wi-Fi
/// `action-call` push (fire-and-forget, no reply, no remote hang-up/mute)
/// when no ADB device is connected.
pub async fn action_call(app: AppHandle, state: State<'_, SharedState>, adb_state: State<'_, AdbState>, number: String) -> Result<(), String> {
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

/// Answer, hang up, mute or change the route on the call the phone is holding,
/// over the Wi-Fi link.
///
/// This is the Wi-Fi counterpart to `adb::adb_call_*`, and the reason the
/// incoming-call overlay stopped being view-only: an alert you could not
/// decline was half a feature. The ADB commands stay as they are — they drive
/// the dialer's UI with `input keyevent` and can do DTMF, which no public
/// framework API exposes to a non-dialer app.
///
/// `on` is the absolute state wanted for the two toggles, never a flip. The
/// phone reads the audio state back after writing it and returns what the
/// device *actually* settled on, because the dialer that owns the call can and
/// does override the route — so the caller should render from the returned
/// `muted`/`speaker`, not from what it asked for.
pub async fn call_action(
    state: State<'_, SharedState>,
    action: String,
    on: Option<bool>,
) -> Result<Value, String> {
    // Closed allow-list, matching `mac_remote`'s posture: the action name
    // reaches a `when` on the phone, so the set of things it can name is
    // decided here rather than by whatever string a caller passes.
    if !matches!(action.as_str(), "answer" | "end" | "mute" | "speaker") {
        return Err(format!("Unknown call action: {action}"));
    }
    let mut body = serde_json::Map::new();
    body.insert("type".into(), Value::from("call-action"));
    body.insert("action".into(), Value::from(action));
    if let Some(on) = on {
        body.insert("on".into(), Value::from(on));
    }
    let reply = ws_server::request_default(&state, body).await?;
    if reply.get("ok").and_then(Value::as_bool) == Some(true) {
        return Ok(reply);
    }
    // The phone distinguishes "you lack the permission", "there was no call to
    // end" and "the dialer put the route back". Passing its wording through
    // means the toast names the actual fix instead of a generic failure.
    Err(reply
        .get("error")
        .and_then(Value::as_str)
        .unwrap_or("The phone could not do that")
        .to_string())
}

// ── Saving what the mirror shows ─────────────────────────────────────────

/// Save a still or a recording of the mirror, and return where it landed.
///
/// The host sends the raw bytes with `dd_core_invoke_bytes` and the format as
/// `{ext}` in the args JSON; see `capture.rs`.
pub fn mirror_save_capture(app: AppHandle, ext: &str, bytes: &[u8]) -> Result<String, String> {
    capture::save(&app, ext, bytes)
}

// ── Find my phone ────────────────────────────────────────────────────────

/// Make the phone ring, or stop it.
///
/// Returns whether it is ringing *now*, read from the phone rather than assumed
/// from the request — the phone stops itself after a minute and from its own
/// notification, so the Mac's button would otherwise sit lit next to a silent
/// phone. See `Ringer.kt` for why the alarm stream is the one that survives
/// silent mode.
pub async fn ring_phone(state: State<'_, SharedState>, on: bool) -> Result<bool, String> {
    let mut body = serde_json::Map::new();
    body.insert("type".into(), Value::from("ring"));
    body.insert("on".into(), Value::from(on));
    let reply = ws_server::request_default(&state, body).await?;
    if let Some(err) = reply.get("error").and_then(Value::as_str) {
        return Err(err.to_string());
    }
    Ok(reply.get("ringing").and_then(Value::as_bool).unwrap_or(on))
}

// ── Permission health ────────────────────────────────────────────────────

/// Probe every grant both devices need and report what's missing.
///
/// Called when the health panel opens and on a slow poll while it stays open.
/// Not on a background timer: the phone half is a round trip that wakes several
/// binder calls over there, and this app has spent real effort removing exactly
/// that kind of idle cost.
pub async fn health_check(
    app: AppHandle,
    state: State<'_, SharedState>,
) -> Result<Vec<health::HealthItem>, String> {
    Ok(health::check(&app, &state).await)
}

/// Take the user to the screen that fixes one row, on whichever device owns it.
///
/// Returns a sentence to show, rather than `()`, because the outcomes are
/// genuinely different: a Mac pane opens in front of you, a phone screen may
/// open on a device in your pocket, and on a phone that cannot start activities
/// from the background all that happens is a notification appears. Collapsing
/// those into "done" would leave someone waiting for a screen that never came.
pub async fn health_fix(state: State<'_, SharedState>, id: String) -> Result<String, String> {
    health::fix(&state, &id).await
}

// ── Phase 10: media remote ───────────────────────────────────────────────

/// Outbound transport/volume command. `value` is an integer (ms for `seek`,
/// a volume step 0..volMax for `setvol`, ignored for play/pause/next/prev) —
/// ConnectionManager.kt reads it via `msg.optInt("value")`.
pub async fn media_cmd(state: State<'_, SharedState>, cmd: String, value: i64) -> Result<(), String> {
    ws_server::push(&state, json!({ "type": "media-cmd", "cmd": cmd, "value": value })).await;
    Ok(())
}

// ── Tier B: wallpaper + apps ─────────────────────────────────────────────

/// The phone's wallpaper as a data URL, for the phone card's backdrop.
/// Requested once per connection by the frontend and cached there — the image
/// changes rarely and costs ~100 KB, so it is deliberately not pushed.
pub async fn wallpaper_get(state: State<'_, SharedState>) -> Result<String, String> {
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
pub struct PhoneApp {
    pkg: String,
    label: String,
}

/// Every launchable app on the phone, sorted by label (the phone sorts; this
/// just forwards). Backs the Apps grid.
pub async fn apps_list(state: State<'_, SharedState>) -> Result<Vec<PhoneApp>, String> {
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
pub async fn app_icon(state: State<'_, SharedState>, pkg: String) -> Result<String, String> {
    let bytes = ws_server::request_app_icon(&state, &pkg).await?;
    Ok(format!("data:image/png;base64,{}", base64_encode(&bytes)))
}

/// Open an app on the phone. Fire-and-forget — the phone has no reply for this,
/// and the observable result is the phone's own screen.
pub async fn app_launch(state: State<'_, SharedState>, pkg: String) -> Result<(), String> {
    if !ws_server::push(&state, json!({ "type": "app-launch", "pkg": pkg })).await {
        return Err("Phone not connected over Wi-Fi".into());
    }
    Ok(())
}

// ── Wi-Fi screenshot (#13) ───────────────────────────────────────────────

/// Cap the phone advertises when it can capture its own screen (accessibility
/// `takeScreenshot`, API 30+, service enabled).
pub const SCREENSHOT_CAP: &str = "screenshot";

/// Where a Wi-Fi screenshot lands: Downloads, `droiddock-<ts>.png` — the same
/// place and name pattern `adb_screenshot` uses.
fn screenshot_dest(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = transfer::download_dir(app).ok_or("no Downloads directory")?;
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let mut dest = dir.join(format!("droiddock-{secs}.png"));
    let mut n = 1;
    while dest.exists() {
        dest = dir.join(format!("droiddock-{secs}-{n}.png"));
        n += 1;
    }
    Ok(dest)
}

/// Pull the PNG out of a phone `screenshot` reply (`{data: base64}`).
pub(crate) fn screenshot_reply_bytes(reply: &Value) -> Result<Vec<u8>, String> {
    if let Some(err) = reply.get("error").and_then(Value::as_str) {
        return Err(err.to_string());
    }
    let data = reply.get("data").and_then(Value::as_str).ok_or("Phone sent no screenshot")?;
    let bytes = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, data)
        .map_err(|e| format!("bad screenshot data: {e}"))?;
    if !bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
        return Err("Phone sent an unreadable screenshot".into());
    }
    Ok(bytes)
}

/// Capture the phone's screen. Over Wi-Fi when the phone has the `screenshot`
/// cap (no ADB, no prompt); otherwise falls back to `adb screencap` if a device
/// is connected. Returns the saved path and emits `phone-screenshot
/// {path, via:"wifi"|"adb"}`.
pub async fn phone_screenshot(app: AppHandle, state: State<'_, SharedState>) -> Result<String, String> {
    let mut wifi_err: Option<String> = None;
    if ws_server::phone_has_cap(&state, SCREENSHOT_CAP).await {
        let reply = ws_server::request(
            &state,
            json_map(json!({ "type": "screenshot" })),
            Duration::from_secs(20),
        )
        .await;
        match reply.and_then(|r| screenshot_reply_bytes(&r)) {
            Ok(bytes) => {
                let dest = screenshot_dest(&app)?;
                tokio::fs::write(&dest, &bytes).await.map_err(|e| e.to_string())?;
                let path = dest.to_string_lossy().to_string();
                let _ = app.emit("phone-screenshot", json!({ "path": path, "via": "wifi" }));
                return Ok(path);
            }
            Err(e) => wifi_err = Some(e),
        }
    }
    let adb_state = app.state::<AdbState>();
    match adb::first_connected_serial(&adb_state) {
        Some(serial) => {
            let path = adb::adb_screenshot(app.clone(), adb_state, serial).await?;
            let _ = app.emit("phone-screenshot", json!({ "path": path, "via": "adb" }));
            Ok(path)
        }
        None => Err(wifi_err.unwrap_or_else(|| {
            "This phone can't take screenshots over Wi-Fi yet; connect it with ADB or update the DroidDock app".into()
        })),
    }
}

// ── helpers ──────────────────────────────────────────────────────────────

/// `json!({...})` → the owned field map `ws_server::request` takes.
pub fn json_map(value: Value) -> serde_json::Map<String, Value> {
    match value {
        Value::Object(map) => map,
        _ => serde_json::Map::new(),
    }
}

pub fn sanitize(name: &str) -> String {
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

#[cfg(test)]
mod screenshot_tests {
    use super::*;

    #[test]
    fn reply_decodes_png_and_rejects_junk() {
        let png = [0x89u8, b'P', b'N', b'G', 1, 2, 3];
        let b64 = base64_encode(&png);
        assert_eq!(screenshot_reply_bytes(&json!({ "data": b64 })).unwrap(), png);
        assert!(screenshot_reply_bytes(&json!({ "data": base64_encode(b"nope") })).is_err());
        assert_eq!(
            screenshot_reply_bytes(&json!({ "error": "Turn on accessibility" })).unwrap_err(),
            "Turn on accessibility"
        );
        assert!(screenshot_reply_bytes(&json!({})).is_err());
    }
}

// ── Storage: cache_info / cache_clear ───────────────────────────────────────

fn dir_bytes(dir: &std::path::Path) -> u64 {
    let Ok(rd) = std::fs::read_dir(dir) else { return 0 };
    rd.flatten()
        .map(|e| match e.metadata() {
            Ok(m) if m.is_dir() => dir_bytes(&e.path()),
            Ok(m) => m.len(),
            Err(_) => 0,
        })
        .sum()
}

pub async fn cache_info(app: AppHandle) -> Result<Value, String> {
    let data = app.path().app_data_dir().map_err(|e| e.to_string())?;
    let (edit_bytes, edit_files, pending) = match app.try_state::<Option<edit_cache::EditCache>>().and_then(|s| s.inner().clone()) {
        Some(c) => c.usage().await,
        None => (0, 0, 0),
    };
    let tools = dir_bytes(&data.join("tools"));
    let logs = crate::crash::log_dir().map(|d| dir_bytes(&d)).unwrap_or(0);
    Ok(json!({
        "editCacheBytes": edit_bytes, "editCacheFiles": edit_files, "pendingSyncs": pending,
        "toolsBytes": tools, "logsBytes": logs,
    }))
}

/// `kinds`: "editCache" | "tools". Returns `{freedBytes, keptPending}`; `tools` is refused while
/// adb is in use (a phone is connected over ADB, or a mirror session runs).
pub async fn cache_clear(app: AppHandle, kinds: Vec<String>) -> Result<Value, String> {
    let data = app.path().app_data_dir().map_err(|e| e.to_string())?;
    let (mut freed, mut kept) = (0u64, 0usize);
    if kinds.iter().any(|k| k == "tools") {
        let tools_dir = data.join("tools");
        let adb = app.state::<crate::adb::AdbState>();
        let in_tools = adb.adb.lock().unwrap().as_deref().map(|p| std::path::Path::new(p).starts_with(&tools_dir)).unwrap_or(false);
        let phone_on_adb = adb.devices.lock().unwrap().iter().any(|d| d.state == "device");
        let mirroring = app.state::<crate::scrcpy_client::ScrcpyState>().is_running();
        if mirroring || (in_tools && phone_on_adb) {
            return Err("ADB is in use right now. Disconnect the phone from ADB and stop mirroring, then try again.".into());
        }
        freed += dir_bytes(&tools_dir);
        std::fs::remove_dir_all(&tools_dir).or_else(|e| if e.kind() == std::io::ErrorKind::NotFound { Ok(()) } else { Err(e) }).map_err(|e| e.to_string())?;
        if in_tools {
            *adb.adb.lock().unwrap() = None;
        }
    }
    if kinds.iter().any(|k| k == "editCache") {
        if let Some(c) = app.try_state::<Option<edit_cache::EditCache>>().and_then(|s| s.inner().clone()) {
            let o = c.clear().await;
            freed += o.freed_bytes;
            kept += o.kept_pending;
        }
    }
    Ok(json!({ "freedBytes": freed, "keptPending": kept }))
}

// ── Per-app notification options ─────────────────────────────────────────

/// `{pkg: AppNotif}` for every app with explicit options.
pub fn get_notif_app_settings(state: State<AppState>) -> std::collections::HashMap<String, config::AppNotif> {
    state.config.lock().unwrap().notif_app_settings.clone()
}

/// Replace one app's options (`settings` null removes the entry, back to the
/// defaults). Returns the new config.
pub fn set_notif_app_settings(
    app: AppHandle,
    state: State<AppState>,
    pkg: String,
    settings: Option<config::AppNotif>,
) -> Result<Config, String> {
    if pkg.trim().is_empty() {
        return Err("empty package".into());
    }
    let mut cfg = state.config.lock().unwrap();
    match settings {
        Some(s) => {
            let s = s.sanitized();
            if s == config::AppNotif::default() {
                cfg.notif_app_settings.remove(&pkg);
            } else {
                cfg.notif_app_settings.insert(pkg, s);
            }
        }
        None => {
            cfg.notif_app_settings.remove(&pkg);
        }
    }
    config::save(&app, &cfg);
    Ok(cfg.clone())
}

// ── Server (WebSocket + discovery) ───────────────────────────────────────

/// Validate and apply a new port and/or advertised adapter, then restart the
/// listener, UDP discovery and the mDNS advert. `iface` empty = Auto.
pub fn server_restart(app: AppHandle, state: State<AppState>, port: Option<u32>, iface: Option<String>) -> Result<Value, String> {
    let (old_port, old_iface) = {
        let c = state.config.lock().unwrap();
        (c.port as u32, c.server_iface.clone())
    };
    let port = port.unwrap_or(old_port);
    if !(1024..=65535).contains(&port) {
        return Err("The port must be between 1024 and 65535.".into());
    }
    if port + 1 > 65535 {
        return Err("The port must be 65534 or lower (discovery uses the next port).".into());
    }
    let iface = iface.map(|s| s.trim().to_string()).unwrap_or(old_iface);
    if port != old_port {
        // Fail fast with a clear message instead of a silent bind loop.
        if std::net::TcpListener::bind(("0.0.0.0", port as u16)).is_err() {
            return Err(format!("Port {port} is already in use. Pick another port."));
        }
        // UDP discovery listens on the next port.
        if std::net::UdpSocket::bind(("0.0.0.0", port as u16 + 1)).is_err() {
            return Err(format!("Port {} (used for discovery) is already in use. Pick another port.", port + 1));
        }
    }
    let (cfg, ips) = {
        let mut c = state.config.lock().unwrap();
        c.port = port as u16;
        c.server_iface = iface;
        config::save(&app, &c);
        (c.clone(), crate::mdns::selected_ipv4s(&c.server_iface))
    };
    crate::request_server_restart();
    Ok(json!({
        "port": cfg.port,
        "iface": cfg.server_iface,
        "ips": ips.iter().map(|i| i.to_string()).collect::<Vec<_>>(),
    }))
}
