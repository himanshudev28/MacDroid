#![allow(unused_imports)]
//! DroidDock core: the protocol / transfer / ADB / mirror / Quick Share engine,
//! driven by a native host (SwiftUI) over the C ABI in `include/droiddock_core.h`.

mod adb;
mod appearance;
mod audio;
pub mod ble_link;
mod ble_policy;
mod transport_class;
mod automation;
mod capture;
mod clipboard;
mod commands;
mod config;
mod crash;
mod crypto;
mod discovery;
mod edit_cache;
pub mod ffi;
mod health;
pub mod host;
mod link_quality;
mod mac_apps;
mod mac_audio;
mod mac_brightness;
mod mac_fs;
mod mac_info;
mod mac_media;
mod mac_remote;
mod mdns;
mod mirror;
mod notifications;
mod pacing;
mod photo_sync;
mod protocol;
mod quickshare;
mod scrcpy_client;
mod shutdown;
mod statusbar;
mod transfer;
mod transfer_log;
mod tray;
mod updater;
mod webdav;
mod ws_server;

pub mod dispatch;

pub(crate) use commands::{base64_encode, map_of};

use adb::AdbState;
use clipboard::ClipboardGuard;
use config::Config;
use host::AppHandle;
use mirror::MirrorState;
use notifications::NotifState;
use std::sync::Mutex;
use ws_server::SharedState;

static SERVER_RESTART: tokio::sync::Notify = tokio::sync::Notify::const_new();

/// Rebind the listener / discovery on the configured port and re-advertise mDNS.
pub fn request_server_restart() {
    SERVER_RESTART.notify_one();
}

pub struct AppState {
    pub config: Mutex<Config>,
}

/// Everything the old Tauri `.setup` closure did, minus tray / windows /
/// deep-link (those belong to the native host). Called once from
/// `dd_core_start` after the [`AppHandle`] exists.
pub fn setup(app: &AppHandle) {
    let config = config::load_or_create(app);
    let port = config.port;
    app.manage(AppState { config: Mutex::new(config) });

    // Cross-cutting managed state for the feature modules.
    app.manage(ClipboardGuard::default());
    app.manage(NotifState::default());
    app.manage(MirrorState::default());
    app.manage(audio::AudioState::default());
    app.manage(quickshare::server::QuickShareState::default());
    app.manage(quickshare::sender::QuickShareSendState::default());
    app.manage(link_quality::LinkQuality::default());
    app.manage(ble_policy::BleHub::default());
    app.manage(ble_policy::LinkFlags::default());
    app.manage(statusbar::StatusState::default());
    app.manage(ws_server::LastPhoneName::default());
    app.manage(AdbState::default());
    app.manage(updater::UpdaterState::default());
    app.manage(webdav::WebdavState::default());
    app.manage(scrcpy_client::ScrcpyState::default());
    app.manage(transfer_log::TransferLog::default());

    // Tidy up after a last run that ended without its quit cleanup.
    shutdown::on_launch();

    // Phase 17: clears stale edit-cache sessions (keeping any still-pending
    // unsynced edit) and starts a fresh session dir for this launch. Managed
    // as an Option: if the data dir is ever unavailable, open-in-place just
    // disables itself (`fs_open_in_place` returns a clean error).
    let edit_cache: Option<edit_cache::EditCache> = app
        .path()
        .app_data_dir()
        .map(|dir| edit_cache::init(dir.join("edit-cache")))
        .map_err(|e| eprintln!("edit_cache: app data dir unavailable ({e}) — open-in-place disabled"))
        .ok();
    app.manage(edit_cache);

    // Phase 18: same graceful-degradation shape as `edit_cache` above.
    let photo_sync: Option<photo_sync::PhotoSync> = app
        .path()
        .app_data_dir()
        .map(photo_sync::init)
        .map_err(|e| eprintln!("photo_sync: app data dir unavailable ({e}) — photo sync disabled"))
        .ok();
    app.manage(photo_sync);

    let ws_state: SharedState = SharedState::default();
    app.manage(ws_state.clone());
    let app_handle: AppHandle = app.clone();

    // Every long-lived networking task runs on a runtime this thread owns and
    // blocks on for the app's lifetime, isolating the link from whatever else
    // the shared runtime is doing. (Kept verbatim from the Tauri build.)
    {
        let h = app_handle.clone();
        let ws = ws_state.clone();
        std::thread::Builder::new()
            .name("droiddock-net".into())
            .spawn(move || {
                let rt = tokio::runtime::Builder::new_multi_thread()
                    // Two workers: this runtime is a handful of mostly parked
                    // tasks plus the WS read/write loops; the default (one per
                    // core) only adds idle threads.
                    .worker_threads(2)
                    .max_blocking_threads(8)
                    .thread_keep_alive(std::time::Duration::from_secs(2))
                    .enable_all()
                    .thread_name("droiddock-net")
                    .build()
                    .expect("failed to build the networking runtime");
                rt.block_on(async move {
                    // Quick Share advertises only when switched on; `start` is
                    // a no-op otherwise.
                    {
                        let h = h.clone();
                        tokio::spawn(async move {
                            let on = h.state::<AppState>().config.lock().unwrap().quickshare_enabled;
                            if on {
                                if let Err(e) = quickshare::server::start(h).await {
                                    eprintln!("[quickshare] could not start: {e}");
                                }
                            }
                        });
                    }
                    tokio::spawn(link_quality::run(h.clone(), ws.clone()));
                    // BLE fallback policy: event-driven, never wakes while connectionMode is "network".
                    tokio::spawn(ble_policy::run(h.clone(), ws.clone()));
                    // Phase 3: 1s clipboard watcher (outbound Mac→phone).
                    tokio::spawn(clipboard::run(h.clone(), ws.clone()));
                    // Mac→phone status (name + battery), 60s tick.
                    tokio::spawn(mac_info::run(h.clone(), ws.clone()));
                    tokio::spawn(mac_media::run(h.clone(), ws.clone()));
                    // Listener, UDP discovery and the mDNS advert are the
                    // restartable part (port / adapter change from Settings).
                    // This thread parks here for the app's life.
                    let mut port = port;
                    loop {
                        let d = tokio::spawn(discovery::run(h.clone(), port + 1));
                        // Second discovery path: works on networks that drop
                        // directed broadcast. Additive.
                        let m = tokio::spawn(mdns::run(h.clone(), port));
                        let w = tokio::spawn(ws_server::run(h.clone(), ws.clone(), port));
                        SERVER_RESTART.notified().await;
                        d.abort();
                        m.abort();
                        w.abort();
                        mdns::withdraw();
                        ws_server::set_listening(false);
                        // Let the aborted tasks drop their sockets.
                        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
                        port = h.state::<AppState>().config.lock().unwrap().port;
                    }
                });
            })
            .expect("failed to start the networking thread");
    }

    // Phase 13: resolve/auto-download adb + scrcpy in the background — Wi-Fi
    // features work with none of this installed.
    if let Ok(data_dir) = app_handle.path().app_data_dir() {
        host::async_runtime::spawn(adb::init(app_handle.clone(), data_dir.join("tools")));
    }

    // Phase 14: auto-resume a timed Mac-initiated pause once it expires, and
    // paint the menu-bar title from the persisted settings before any phone
    // connects.
    tray::apply_widget(&app_handle);
    statusbar::refresh_title(&app_handle);
    host::async_runtime::spawn(tray::expire_loop(app_handle.clone()));

    // The Dock's per-app Space assignment is invisible from a window; log it.
    if let Some(binding) = appearance::dock_space_binding(&app.config().identifier) {
        eprintln!(
            "[window] the Dock has this app assigned to {binding:?} \
             (Dock icon → Options → Assign To) — every window it opens \
             will follow the user across Spaces until that is cleared"
        );
    }

    // Last, and on a delay of its own — an update check is the least urgent
    // thing this app does, and must never be in the way of the link coming up.
    updater::spawn_startup_check(app_handle);
}
