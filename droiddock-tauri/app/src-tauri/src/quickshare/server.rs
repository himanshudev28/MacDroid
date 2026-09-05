//! Advertising this Mac as a Quick Share target, and accepting the transfers
//! that result.
//!
//! Off by default and explicitly opt-in: while it runs, this Mac is visible to
//! *everyone* on the local network — Quick Share's "contacts only" visibility
//! needs Google account state a third-party implementation cannot obtain, so
//! "visible to everyone" is the only mode available. That is a real privacy
//! decision and belongs to the user, not to a default.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use mdns_sd::{ServiceDaemon, ServiceInfo};
use tauri::{AppHandle, Emitter, Manager};
use tokio::net::TcpListener;
use tokio::sync::{mpsc, oneshot};

use super::connection::{self, Event, IncomingFile};
use super::wire::{self, DeviceType, EndpointId};

struct Running {
    task: tauri::async_runtime::JoinHandle<()>,
    daemon: ServiceDaemon,
    fullname: String,
}

#[derive(Default)]
pub struct QuickShareState {
    running: Mutex<Option<Running>>,
    /// Transfers waiting on the user's accept/reject.
    pending: Mutex<HashMap<u64, oneshot::Sender<bool>>>,
    next_id: AtomicU64,
}

#[derive(Clone, serde::Serialize)]
struct RequestPayload {
    id: u64,
    peer: String,
    pin: String,
    files: Vec<IncomingFile>,
}

/// Begin advertising and accepting. Idempotent: calling it while already
/// running is a no-op rather than a second advertisement.
pub async fn start(app: AppHandle) -> Result<u16, String> {
    if app.state::<QuickShareState>().running.lock().unwrap().is_some() {
        return Err("Quick Share is already running".into());
    }

    // Port 0: the protocol carries the port in the mDNS record, so there is no
    // reason to fight over a fixed one.
    let listener = TcpListener::bind(("0.0.0.0", 0))
        .await
        .map_err(|e| format!("could not listen for Quick Share: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| e.to_string())?
        .port();

    let endpoint_id = EndpointId::random();
    let device_name = crate::mdns::instance_name(&app);
    let instance = wire::mdns_instance_name(&endpoint_id);
    let info_blob = wire::endpoint_info(DeviceType::Laptop, &device_name);

    let ips = crate::mdns::lan_ipv4s();
    if ips.is_empty() {
        return Err("no network interface to advertise on".into());
    }

    let daemon = ServiceDaemon::new().map_err(|e| format!("mDNS unavailable: {e}"))?;
    let mut txt = HashMap::new();
    txt.insert(
        "n".to_string(),
        base64::Engine::encode(
            &base64::engine::general_purpose::URL_SAFE_NO_PAD,
            &info_blob,
        ),
    );
    let service = ServiceInfo::new(
        wire::SERVICE_TYPE,
        &instance,
        // Our own host record, for the same reason the DroidDock advert uses one:
        // never collide with whatever else claims `<host>.local.`
        &format!("droiddock-qs-{}.local.", endpoint_id.as_str()),
        &ips[..],
        port,
        txt,
    )
    .map_err(|e| format!("mDNS service info invalid: {e}"))?;
    let fullname = service.get_fullname().to_string();
    daemon
        .register(service)
        .map_err(|e| format!("mDNS register failed: {e}"))?;

    eprintln!("[quickshare] advertising as {device_name:?} ({instance}) on port {port}");

    let handle = app.clone();
    let task = tauri::async_runtime::spawn(async move {
        loop {
            let Ok((stream, peer)) = listener.accept().await else { continue };
            let app = handle.clone();
            let dest = match crate::transfer::download_dir(&app) {
                Some(d) => d,
                None => {
                    eprintln!("[quickshare] no Downloads directory; refusing {peer}");
                    continue;
                }
            };
            tauri::async_runtime::spawn(async move {
                let (tx, rx) = mpsc::unbounded_channel();
                let pump = tauri::async_runtime::spawn(forward_events(app.clone(), rx));
                if let Err(e) = connection::serve(stream, dest, tx).await {
                    eprintln!("[quickshare] {peer}: {e}");
                }
                let _ = pump.await;
            });
        }
    });

    *app.state::<QuickShareState>().running.lock().unwrap() =
        Some(Running { task, daemon, fullname });
    Ok(port)
}

/// Stop advertising and drop the listener. In-flight transfers are cut off —
/// the user asked to stop being visible, and finishing quietly in the
/// background is not what that means.
pub fn stop(app: &AppHandle) {
    let taken = app.state::<QuickShareState>().running.lock().unwrap().take();
    if let Some(running) = taken {
        let _ = running.daemon.unregister(&running.fullname);
        let _ = running.daemon.shutdown();
        running.task.abort();
        eprintln!("[quickshare] stopped advertising");
    }
    // Anything waiting on consent will never get an answer now; decline so the
    // peer is told rather than left hanging until its own timeout.
    for (_, tx) in app.state::<QuickShareState>().pending.lock().unwrap().drain() {
        let _ = tx.send(false);
    }
}

pub fn is_running(app: &AppHandle) -> bool {
    app.state::<QuickShareState>().running.lock().unwrap().is_some()
}

/// Translate one connection's events into Tauri events, holding the consent
/// channel until the UI answers.
async fn forward_events(app: AppHandle, mut rx: mpsc::UnboundedReceiver<Event>) {
    while let Some(event) = rx.recv().await {
        let state = app.state::<QuickShareState>();
        match event {
            Event::Introduced { peer, pin, files, reply } => {
                let id = state.next_id.fetch_add(1, Ordering::Relaxed);
                state.pending.lock().unwrap().insert(id, reply);
                eprintln!(
                    "[quickshare] {peer} offers {} file(s), PIN {pin}",
                    files.len()
                );
                let _ = app.emit("quickshare-request", RequestPayload { id, peer, pin, files });
            }
            Event::Progress { received, total } => {
                let _ = app.emit(
                    "quickshare-progress",
                    serde_json::json!({ "received": received, "total": total }),
                );
            }
            Event::Done { paths } => {
                eprintln!("[quickshare] received {} file(s)", paths.len());
                let _ = app.emit(
                    "quickshare-received",
                    serde_json::json!({
                        "paths": paths.iter().map(|p| p.to_string_lossy()).collect::<Vec<_>>()
                    }),
                );
            }
            Event::Rejected => {
                let _ = app.emit("quickshare-rejected", serde_json::json!({}));
            }
            Event::Failed { error } => {
                eprintln!("[quickshare] transfer failed: {error}");
                let _ = app.emit("quickshare-error", serde_json::json!({ "error": error }));
            }
        }
    }
}

// ── Tauri commands ───────────────────────────────────────────────────────

#[tauri::command]
pub async fn quickshare_set_enabled(app: AppHandle, on: bool) -> Result<bool, String> {
    if on {
        if is_running(&app) {
            return Ok(true);
        }
        start(app.clone()).await?;
        Ok(true)
    } else {
        stop(&app);
        Ok(false)
    }
}

#[tauri::command]
pub fn quickshare_status(app: AppHandle) -> bool {
    is_running(&app)
}

/// The user's answer to a transfer prompt.
#[tauri::command]
pub fn quickshare_respond(app: AppHandle, id: u64, accept: bool) -> Result<(), String> {
    let tx = app
        .state::<QuickShareState>()
        .pending
        .lock()
        .unwrap()
        .remove(&id);
    match tx {
        Some(tx) => {
            let _ = tx.send(accept);
            Ok(())
        }
        // Already answered, or the sender gave up first.
        None => Err("that transfer is no longer waiting".into()),
    }
}
