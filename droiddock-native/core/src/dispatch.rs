//! Command dispatch: routes a host `invoke(cmd, args)` to the Rust command fns.
//!
//! GENERATED mechanically from the old `generate_handler![...]` list (see
//! `droiddock-native/ARCHITECTURE.md` section D). Args are JSON keyed by the
//! lowerCamelCase of the Rust parameter name (Tauri's default). `State<T>` and
//! `AppHandle` params are filled from `app`; `Option<T>` params accept
//! missing/null.

use crate::host::{AppHandle, Manager};
use serde::Serialize;
use serde_json::Value;

macro_rules! arg {
    ($a:expr, $k:literal) => {
        serde_json::from_value($a.get($k).cloned().unwrap_or(Value::Null))
            .map_err(|e| format!("bad arg `{}`: {e}", $k))?
    };
}

fn val<T: Serialize>(v: T) -> Result<Value, String> {
    serde_json::to_value(v).map_err(|e| e.to_string())
}

fn res<T: Serialize>(r: Result<T, String>) -> Result<Value, String> {
    r.and_then(val)
}

/// Every command this core answers.
pub const COMMANDS: &[&str] = &[
    "quickshare_discover_start",
    "quickshare_discover_stop",
    "quickshare_devices",
    "quickshare_send",
    "quickshare_send_cancel",
    "phone_screenshot",
    "fs_mkdir",
    "get_notif_app_settings",
    "set_notif_app_settings",
    "server_restart",
    "get_config",
    "get_pairing_info",
    "set_setting",
    "notif_reply",
    "notif_dismiss",
    "notif_action",
    "notif_open",
    "wifi_status",
    "fs_list",
    "fs_delete",
    "fs_rename",
    "fs_pull",
    "fs_push",
    "fs_cancel",
    "photos_list",
    "photo_thumb",
    "photo_open",
    "fs_open_in_place",
    "fs_pending_syncs",
    "photo_sync_backfill",
    "sms_threads",
    "sms_messages",
    "sms_send",
    "contacts_list",
    "action_call",
    "call_action",
    "mirror_save_capture",
    "ring_phone",
    "health_check",
    "health_fix",
    "media_cmd",
    "clipboard_push_now",
    "wallpaper_get",
    "apps_list",
    "app_icon",
    "app_launch",
    "mirror_popout",
    "mirror_stop",
    "mirror_input",
    "mirror_attach",
    "mirror_set_hevc_supported",
    "audio_attach",
    "quickshare_set_enabled",
    "quickshare_status",
    "quickshare_respond",
    "scrcpy_embedded_start",
    "scrcpy_embedded_stop",
    "scrcpy_embedded_running",
    "adb_tools",
    "adb_scrcpy_install",
    "adb_devices",
    "adb_device_info",
    "adb_go_wireless",
    "adb_pair_wireless",
    "adb_unpair",
    "adb_paired_info",
    "adb_reconnect_now",
    "adb_qr_pair_start",
    "adb_qr_pair_cancel",
    "adb_camera",
    "adb_mirror",
    "adb_desktop",
    "adb_mirror_app",
    "adb_app_window_open",
    "adb_app_window_place",
    "adb_second_screen",
    "adb_vd_key",
    "adb_vd_tasks",
    "adb_vd_switch",
    "adb_projection_status",
    "adb_projection_grant",
    "adb_freeform_status",
    "adb_freeform_enable",
    "adb_freeform_revert",
    "adb_screenshot",
    "adb_volume_get",
    "adb_volume_set",
    "adb_call_end",
    "adb_call_speaker",
    "adb_call_mute",
    "adb_call_dtmf",
    "adb_call_start_polling",
    "accessibility_trusted",
    "open_accessibility_settings",
    "accessibility_reset",
    "spaces_binding_active",
    "spaces_binding_clear",
    "pause_set",
    "widget_set",
    "media_state",
    "update_check",
    "update_install",
    "app_version",
    "transfers_list",
    "transfers_clear",
    "transfer_cancel",
    "transfer_reveal",
    "transfer_open",
    "webdav_start",
    "webdav_stop",
    "webdav_status",
    "crash_log_count",
    "crash_logs_reveal",
    "crash_logs_clear",
    "mirror_closed",
    "menubar_opened",
    "app_set_active",
    "cache_info",
    "cache_clear",
    "core_ping",
    "automation_open_url",
    "ble_status",
    "ble_radio",
    "ble_connected",
    "ble_rx",
    "ble_tx_done",
    "ble_closed",
    "ble_rssi",
    "link_prefer",
    "tls_status",
    "tls_allow_plain",
];

/// The old Tauri `generate_handler![...]` list, verbatim (last path segment).
#[cfg(test)]
const TAURI_ORIGINAL: &[&str] = &[
    "get_config",
    "get_appearance",
    "get_pairing_info",
    "set_setting",
    "notif_reply",
    "notif_dismiss",
    "notif_action",
    "wifi_status",
    "fs_list",
    "fs_delete",
    "fs_rename",
    "fs_pull",
    "fs_push",
    "fs_cancel",
    "photos_list",
    "photo_thumb",
    "photo_open",
    "fs_open_in_place",
    "fs_pending_syncs",
    "photo_sync_backfill",
    "sms_threads",
    "sms_messages",
    "sms_send",
    "contacts_list",
    "action_call",
    "call_action",
    "mirror_save_capture",
    "ring_phone",
    "health_check",
    "health_fix",
    "media_cmd",
    "clipboard_push_now",
    "wallpaper_get",
    "apps_list",
    "app_icon",
    "app_launch",
    "mirror_popout",
    "mirror_stop",
    "mirror_focus",
    "mirror_set_on_top",
    "mirror_input",
    "mirror_attach",
    "mirror_set_hevc_supported",
    "audio_attach",
    "quickshare_set_enabled",
    "quickshare_status",
    "quickshare_respond",
    "scrcpy_embedded_start",
    "scrcpy_embedded_stop",
    "scrcpy_embedded_running",
    "adb_tools",
    "adb_scrcpy_install",
    "adb_devices",
    "adb_device_info",
    "adb_go_wireless",
    "adb_pair_wireless",
    "adb_unpair",
    "adb_paired_info",
    "adb_reconnect_now",
    "adb_qr_pair_start",
    "adb_qr_pair_cancel",
    "adb_camera",
    "adb_mirror",
    "adb_desktop",
    "adb_mirror_app",
    "adb_app_window_open",
    "adb_app_window_place",
    "adb_second_screen",
    "adb_vd_key",
    "adb_vd_tasks",
    "adb_vd_switch",
    "adb_projection_status",
    "adb_projection_grant",
    "adb_freeform_status",
    "adb_freeform_enable",
    "adb_freeform_revert",
    "adb_screenshot",
    "adb_volume_get",
    "adb_volume_set",
    "adb_call_end",
    "adb_call_speaker",
    "adb_call_mute",
    "adb_call_dtmf",
    "adb_call_start_polling",
    "accessibility_trusted",
    "open_accessibility_settings",
    "accessibility_reset",
    "spaces_binding_active",
    "spaces_binding_clear",
    "window_theme_set",
    "pause_set",
    "autostart_get",
    "autostart_set",
    "menubar_hide",
    "open_main_window",
    "widget_set",
    "media_state",
    "update_check",
    "update_install",
    "app_version",
    "transfers_list",
    "transfers_clear",
    "transfer_cancel",
    "transfer_reveal",
    "transfer_open",
    "webdav_start",
    "webdav_stop",
    "webdav_status",
    "crash_log_count",
    "crash_logs_reveal",
    "crash_logs_clear",
];
#[cfg(test)]
const SWIFT_ONLY: &[&str] = &[
    "get_appearance",
    "window_theme_set",
    "mirror_focus",
    "mirror_set_on_top",
    "menubar_hide",
    "open_main_window",
    "autostart_get",
    "autostart_set",
];
#[cfg(test)]
const NEW_COMMANDS: &[&str] = &[
    "notif_open",
    "quickshare_discover_start",
    "quickshare_discover_stop",
    "quickshare_devices",
    "quickshare_send",
    "quickshare_send_cancel",
    "phone_screenshot",
    "fs_mkdir",
    "get_notif_app_settings",
    "set_notif_app_settings",
    "server_restart",
    "core_ping",
    "mirror_closed",
    "automation_open_url",
    "menubar_opened",
    "app_set_active",
    "cache_info",
    "cache_clear",
    "ble_status",
    "ble_radio",
    "ble_connected",
    "ble_rx",
    "ble_tx_done",
    "ble_closed",
    "ble_rssi",
    "link_prefer",
    "tls_status",
    "tls_allow_plain",
];

/// Run one command. `body` is the raw payload of `dd_core_invoke_bytes`.
#[allow(unused_variables)]
pub async fn dispatch(
    app: &AppHandle,
    cmd: &str,
    args: Value,
    body: Option<Vec<u8>>,
) -> Result<Value, String> {
    match cmd {
        // ── BLE fallback (docs/BLE-NEARBY-DESIGN.md §8) ──
        "ble_status" => crate::ble_policy::cmd_status(app),
        "ble_radio" => crate::ble_policy::cmd_radio(app, &args),
        "ble_connected" => crate::ble_policy::cmd_connected(app, &args),
        "ble_rx" => crate::ble_policy::cmd_rx(app, &args, body).await,
        "ble_tx_done" => crate::ble_policy::cmd_tx_done(app, &args),
        "ble_closed" => crate::ble_policy::cmd_closed(app, &args).await,
        "ble_rssi" => crate::ble_policy::cmd_rssi(app, &args),
        "link_prefer" => crate::ble_policy::cmd_link_prefer(app, &args).await,
        "tls_allow_plain" => {
            crate::tls::forget_device(app, args.get("deviceId").and_then(|v| v.as_str()));
            Ok(Value::Null)
        }
        "tls_status" => {
            let st = app.state::<crate::ws_server::SharedState>();
            Ok(crate::commands::tls_status(app, st.inner()).await)
        }
        "get_config" => {
            let s0 = app.state::<crate::AppState>();
            val(crate::commands::get_config(s0))
        }
        "get_pairing_info" => {
            let s0 = app.state::<crate::AppState>();
            val(crate::commands::get_pairing_info(app, s0))
        }
        "get_notif_app_settings" => {
            val(crate::commands::get_notif_app_settings(app.state::<crate::AppState>()))
        }
        "set_notif_app_settings" => {
            let s1 = app.state::<crate::AppState>();
            res(crate::commands::set_notif_app_settings(app.clone(), s1, arg!(args, "pkg"), arg!(args, "settings")))
        }
        "server_restart" => {
            let s1 = app.state::<crate::AppState>();
            res(crate::commands::server_restart(app.clone(), s1, arg!(args, "port"), arg!(args, "iface")))
        }
        "set_setting" => {
            let s1 = app.state::<crate::AppState>();
            res(crate::commands::set_setting(app.clone(), s1, arg!(args, "key"), arg!(args, "value")))
        }
        "notif_reply" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::notif_reply(s0, arg!(args, "key"), arg!(args, "text")).await)
        }
        "notif_open" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::notif_open(s0, arg!(args, "key"), arg!(args, "displayId")).await)
        }
        "notif_dismiss" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::notif_dismiss(s0, arg!(args, "key")).await)
        }
        "notif_action" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::notif_action(s0, arg!(args, "key"), arg!(args, "index")).await)
        }
        "wifi_status" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::wifi_status(s0).await)
        }
        "fs_list" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_list(s0, arg!(args, "path")).await)
        }
        "fs_delete" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_delete(s0, arg!(args, "path")).await)
        }
        "fs_mkdir" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_mkdir(s0, arg!(args, "path"), arg!(args, "name")).await)
        }
        "fs_rename" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_rename(s0, arg!(args, "path"), arg!(args, "newName")).await)
        }
        "fs_pull" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_pull(app.clone(), s1, arg!(args, "path"), arg!(args, "name")).await)
        }
        "fs_push" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_push(app.clone(), s1, arg!(args, "localPath"), arg!(args, "dest")).await)
        }
        "fs_cancel" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::fs_cancel(app.clone(), s1, arg!(args, "transferId")).await)
        }
        "photos_list" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::photos_list(s0, arg!(args, "offset"), arg!(args, "limit")).await)
        }
        "photo_thumb" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::photo_thumb(s0, arg!(args, "id"), arg!(args, "kind")).await)
        }
        "photo_open" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::photo_open(app.clone(), s1, arg!(args, "path"), arg!(args, "name")).await)
        }
        "fs_open_in_place" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            let s2 = app.state::<Option<crate::edit_cache::EditCache>>();
            res(crate::commands::fs_open_in_place(app.clone(), s1, s2, arg!(args, "path")).await)
        }
        "fs_pending_syncs" => {
            let s0 = app.state::<Option<crate::edit_cache::EditCache>>();
            res(crate::commands::fs_pending_syncs(s0).await)
        }
        "photo_sync_backfill" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            let s2 = app.state::<Option<crate::photo_sync::PhotoSync>>();
            let s3 = app.state::<crate::AppState>();
            res(crate::commands::photo_sync_backfill(app.clone(), s1, s2, s3).await)
        }
        "sms_threads" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::sms_threads(s0).await)
        }
        "sms_messages" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::sms_messages(s0, arg!(args, "threadId")).await)
        }
        "sms_send" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::sms_send(s0, arg!(args, "address"), arg!(args, "text")).await)
        }
        "contacts_list" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::contacts_list(s0).await)
        }
        "action_call" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            let s2 = app.state::<crate::adb::AdbState>();
            res(crate::commands::action_call(app.clone(), s1, s2, arg!(args, "number")).await)
        }
        "call_action" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::call_action(s0, arg!(args, "action"), arg!(args, "on")).await)
        }
        "mirror_save_capture" => {
            // Raw body rides in `body`; the format is `ext` in the args.
            let ext: String = arg!(args, "ext");
            let bytes = body.ok_or("The capture did not arrive as raw bytes")?;
            res(crate::commands::mirror_save_capture(app.clone(), &ext, &bytes))
        }
        "ring_phone" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::ring_phone(s0, arg!(args, "on")).await)
        }
        "health_check" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::health_check(app.clone(), s1).await)
        }
        "health_fix" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::health_fix(s0, arg!(args, "id")).await)
        }
        "media_cmd" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::media_cmd(s0, arg!(args, "cmd"), arg!(args, "value")).await)
        }
        "clipboard_push_now" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::clipboard::clipboard_push_now(app.clone(), s1).await)
        }
        "wallpaper_get" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::wallpaper_get(s0).await)
        }
        "apps_list" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::apps_list(s0).await)
        }
        "app_icon" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::app_icon(s0, arg!(args, "pkg")).await)
        }
        "app_launch" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::app_launch(s0, arg!(args, "pkg")).await)
        }
        "mirror_popout" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::mirror::mirror_popout(app.clone(), s1, arg!(args, "source")).await)
        }
        "mirror_stop" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::mirror::mirror_stop(app.clone(), s1).await)
        }
        "mirror_input" => {
            let s0 = app.state::<crate::ws_server::SharedState>();
            res(crate::mirror::mirror_input(app.clone(), s0, arg!(args, "msg")).await)
        }
        "mirror_attach" => {
            let s1 = app.state::<crate::mirror::MirrorState>();
            val(crate::mirror::mirror_attach(app.clone(), s1))
        }
        "mirror_set_hevc_supported" => {
            let s0 = app.state::<crate::mirror::MirrorState>();
            crate::mirror::mirror_set_hevc_supported(s0, arg!(args, "supported"));
            Ok(Value::Null)
        }
        "audio_attach" => {
            let s1 = app.state::<crate::audio::AudioState>();
            val(crate::audio::audio_attach(app.clone(), s1))
        }
        "quickshare_set_enabled" => {
            res(crate::quickshare::server::quickshare_set_enabled(app.clone(), arg!(args, "on")).await)
        }
        "quickshare_status" => {
            val(crate::quickshare::server::quickshare_status(app.clone()))
        }
        "quickshare_respond" => {
            res(crate::quickshare::server::quickshare_respond(app.clone(), arg!(args, "id"), arg!(args, "accept")))
        }
        "scrcpy_embedded_start" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::scrcpy_client::scrcpy_embedded_start(app.clone(), s1, arg!(args, "serial")).await)
        }
        "scrcpy_embedded_stop" => {
            crate::scrcpy_client::scrcpy_embedded_stop(app.clone());
            Ok(Value::Null)
        }
        "scrcpy_embedded_running" => {
            let s0 = app.state::<crate::scrcpy_client::ScrcpyState>();
            val(crate::scrcpy_client::scrcpy_embedded_running(s0))
        }
        "adb_tools" => {
            let s0 = app.state::<crate::adb::AdbState>();
            val(crate::adb::adb_tools(s0))
        }
        "adb_scrcpy_install" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_scrcpy_install(app.clone(), s1).await)
        }
        "adb_devices" => {
            let s0 = app.state::<crate::adb::AdbState>();
            val(crate::adb::adb_devices(s0))
        }
        "adb_device_info" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_device_info(s0, arg!(args, "serial")).await)
        }
        "adb_go_wireless" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_go_wireless(app.clone(), s1, arg!(args, "serial")).await)
        }
        "adb_pair_wireless" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_pair_wireless(app.clone(), s1, arg!(args, "hostPort"), arg!(args, "code")).await)
        }
        "adb_unpair" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_unpair(app.clone(), s1).await)
        }
        "adb_paired_info" => {
            val(crate::adb::adb_paired_info(app.clone()))
        }
        "adb_reconnect_now" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_reconnect_now(app.clone(), s1).await)
        }
        "adb_qr_pair_start" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_qr_pair_start(app.clone(), s1, arg!(args, "serviceName"), arg!(args, "password")).await)
        }
        "adb_qr_pair_cancel" => {
            let s0 = app.state::<crate::adb::AdbState>();
            crate::adb::adb_qr_pair_cancel(s0);
            Ok(Value::Null)
        }
        "adb_camera" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_camera(app.clone(), s1, arg!(args, "serial")).await)
        }
        "adb_mirror" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_mirror(app.clone(), s1, arg!(args, "serial")).await)
        }
        "adb_desktop" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_desktop(app.clone(), s1, arg!(args, "serial"), arg!(args, "size")).await)
        }
        "adb_mirror_app" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_mirror_app(app.clone(), s1, arg!(args, "serial"), arg!(args, "package"), arg!(args, "newDisplay")).await)
        }
        "adb_app_window_open" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_app_window_open(app.clone(), s1, arg!(args, "serial"), arg!(args, "package"), arg!(args, "title")).await)
        }
        "adb_app_window_place" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_app_window_place(s1, arg!(args, "serial"), arg!(args, "package"), arg!(args, "displayId"), arg!(args, "fired")).await)
        }
        "adb_second_screen" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_second_screen(app.clone(), s1, arg!(args, "serial")).await)
        }
        "adb_vd_key" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_vd_key(s1, arg!(args, "serial"), arg!(args, "displayId"), arg!(args, "key"), arg!(args, "package")).await)
        }
        "adb_vd_tasks" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_vd_tasks(s1, arg!(args, "serial"), arg!(args, "displayId")).await)
        }
        "adb_vd_switch" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_vd_switch(s1, arg!(args, "serial"), arg!(args, "displayId"), arg!(args, "package")).await)
        }
        "adb_projection_status" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_projection_status(s1).await)
        }
        "adb_projection_grant" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_projection_grant(s1, arg!(args, "allow")).await)
        }
        "adb_freeform_status" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_freeform_status(s0).await)
        }
        "adb_freeform_enable" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_freeform_enable(app.clone(), s1).await)
        }
        "adb_freeform_revert" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_freeform_revert(app.clone(), s1).await)
        }
        "adb_screenshot" => {
            let s1 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_screenshot(app.clone(), s1, arg!(args, "serial")).await)
        }
        "adb_volume_get" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_volume_get(s0).await)
        }
        "adb_volume_set" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_volume_set(s0, arg!(args, "level"), arg!(args, "currentLevel")).await)
        }
        "adb_call_end" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_call_end(s0).await)
        }
        "adb_call_speaker" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_call_speaker(s0).await)
        }
        "adb_call_mute" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_call_mute(s0).await)
        }
        "adb_call_dtmf" => {
            let s0 = app.state::<crate::adb::AdbState>();
            res(crate::adb::adb_call_dtmf(s0, arg!(args, "digit")).await)
        }
        "adb_call_start_polling" => {
            let s1 = app.state::<crate::adb::AdbState>();
            crate::adb::adb_call_start_polling(app.clone(), s1, arg!(args, "serial"));
            Ok(Value::Null)
        }
        "accessibility_trusted" => {
            val(crate::mac_remote::accessibility_trusted())
        }
        "open_accessibility_settings" => {
            crate::mac_remote::open_accessibility_settings();
            Ok(Value::Null)
        }
        "accessibility_reset" => {
            val(crate::mac_remote::accessibility_reset(app.clone()).await)
        }
        "spaces_binding_active" => {
            val(crate::appearance::spaces_binding_active(app.clone()))
        }
        "spaces_binding_clear" => {
            val(crate::appearance::spaces_binding_clear(app.clone()).await)
        }
        "pause_set" => {
            val(crate::tray::pause_set(app.clone(), arg!(args, "until")))
        }
        "widget_set" => {
            res(crate::tray::widget_set(app.clone(), arg!(args, "show")))
        }
        "media_state" => {
            val(crate::statusbar::media_state(app.clone()))
        }
        "update_check" => {
            res(crate::updater::update_check(app.clone()).await)
        }
        "update_install" => {
            res(crate::updater::update_install(app.clone()).await)
        }
        "app_version" => {
            val(crate::updater::app_version(app.clone()))
        }
        "transfers_list" => {
            val(crate::transfer_log::transfers_list(app.clone()))
        }
        "transfers_clear" => {
            crate::transfer_log::transfers_clear(app.clone());
            Ok(Value::Null)
        }
        "transfer_cancel" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::transfer_log::transfer_cancel(app.clone(), s1, arg!(args, "key")).await)
        }
        "transfer_reveal" => {
            res(crate::transfer_log::transfer_reveal(app.clone(), arg!(args, "key")).await)
        }
        "transfer_open" => {
            res(crate::transfer_log::transfer_open(app.clone(), arg!(args, "key")).await)
        }
        "webdav_start" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            let s2 = app.state::<crate::webdav::WebdavState>();
            res(crate::webdav::webdav_start(app.clone(), s1, s2).await)
        }
        "webdav_stop" => {
            let s0 = app.state::<crate::webdav::WebdavState>();
            res(crate::webdav::webdav_stop(s0).await)
        }
        "webdav_status" => {
            let s0 = app.state::<crate::webdav::WebdavState>();
            res(crate::webdav::webdav_status(s0))
        }
        "crash_log_count" => {
            val(crate::crash::crash_log_count())
        }
        "crash_logs_reveal" => {
            res(crate::crash::crash_logs_reveal(app.clone()))
        }
        "crash_logs_clear" => {
            res(crate::crash::crash_logs_clear())
        }
        "mirror_closed" => {
            res(crate::mirror::mirror_closed(app.clone()).await)
        }
        // Mac-local pacing hint from the host: is any window / the menu-bar
        // panel / the widget visible? Not forwarded to the phone.
        "app_set_active" => {
            let active: bool = arg!(args, "active");
            crate::pacing::set_active(active);
            Ok(Value::Null)
        }
        "cache_info" => res(crate::commands::cache_info(app.clone()).await),
        "cache_clear" => res(crate::commands::cache_clear(app.clone(), arg!(args, "kinds")).await),
        "menubar_opened" => {
            crate::statusbar::menubar_opened(app.clone());
            Ok(Value::Null)
        }
        "quickshare_discover_start" => {
            res(crate::quickshare::sender::quickshare_discover_start(app.clone()).await)
        }
        "quickshare_discover_stop" => {
            crate::quickshare::sender::quickshare_discover_stop(app.clone());
            Ok(Value::Null)
        }
        "quickshare_devices" => val(crate::quickshare::sender::quickshare_devices(app.clone())),
        "quickshare_send" => {
            res(crate::quickshare::sender::quickshare_send(app.clone(), arg!(args, "deviceId"), arg!(args, "paths")).await)
        }
        "quickshare_send_cancel" => {
            res(crate::quickshare::sender::quickshare_send_cancel(app.clone(), arg!(args, "transferId")))
        }
        "core_ping" => Ok(Value::from("pong")),
        "phone_screenshot" => {
            let s1 = app.state::<crate::ws_server::SharedState>();
            res(crate::commands::phone_screenshot(app.clone(), s1).await)
        }
        "automation_open_url" => {
            let url: String = arg!(args, "url");
            crate::automation::handle_url(app, &url);
            Ok(Value::Null)
        }
        other => Err(format!("unknown command: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// dispatch name list == old lib.rs list - Swift-only + new.
    #[test]
    fn command_list_matches_the_tauri_handler_list() {
        let mut want: Vec<&str> = TAURI_ORIGINAL
            .iter()
            .copied()
            .filter(|n| !SWIFT_ONLY.contains(n))
            .chain(NEW_COMMANDS.iter().copied())
            .collect();
        want.sort_unstable();
        want.dedup();
        let mut got: Vec<&str> = COMMANDS.to_vec();
        got.sort_unstable();
        assert_eq!(got, want);
    }

    /// Every name in COMMANDS has a match arm (and no arm is undeclared).
    #[test]
    fn every_command_has_an_arm() {
        let src = include_str!("dispatch.rs");
        let body = &src[src.find("match cmd {").unwrap()..src.find("other => Err").unwrap()];
        let arms: std::collections::BTreeSet<&str> = body
            .lines()
            .filter_map(|l| l.strip_prefix("        \""))
            .filter_map(|l| l.split('"').next())
            .collect();
        let declared: std::collections::BTreeSet<&str> = COMMANDS.iter().copied().collect();
        assert_eq!(arms, declared);
    }

    #[tokio::test]
    async fn unknown_commands_are_errors() {
        let app = AppHandle::new(
            crate::ffi::DdCallbacks::null(),
            std::env::temp_dir(),
            "test".into(),
            "0".into(),
        );
        let r = dispatch(&app, "nope", Value::Null, None).await;
        assert_eq!(r.unwrap_err(), "unknown command: nope");
        let r = dispatch(&app, "core_ping", Value::Null, None).await;
        assert_eq!(r.unwrap(), Value::from("pong"));
    }
}
