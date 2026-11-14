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
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

use mdns_sd::{ServiceDaemon, ServiceInfo};
use crate::host::{AppHandle, Emitter, Manager};
use tokio::net::TcpListener;
use tokio::sync::{mpsc, oneshot};

use super::connection::{self, Event, IncomingFile};
use super::wire::{self, DeviceType, EndpointId};

/// Inbound connections at once, and per peer address. Nearby phones need the
/// listener open on every interface, so abuse is bounded here instead.
const MAX_CONNECTIONS: usize = 16;
const MAX_PER_IP: usize = 3;
/// Consent prompts that may be on screen at once; more are refused, not queued.
const MAX_PENDING_PROMPTS: usize = 3;

#[derive(Default)]
struct ConnTable {
    total: usize,
    per_ip: HashMap<IpAddr, usize>,
}

impl ConnTable {
    fn try_take(&mut self, ip: IpAddr, max_total: usize, max_ip: usize) -> bool {
        let n = self.per_ip.get(&ip).copied().unwrap_or(0);
        if self.total >= max_total || n >= max_ip {
            return false;
        }
        self.total += 1;
        self.per_ip.insert(ip, n + 1);
        true
    }
    fn release(&mut self, ip: IpAddr) {
        self.total = self.total.saturating_sub(1);
        match self.per_ip.get_mut(&ip) {
            Some(n) if *n > 1 => *n -= 1,
            _ => {
                self.per_ip.remove(&ip);
            }
        }
    }
}

/// Holds one connection slot; dropping it frees the slot however the task ends.
struct ConnSlot(Arc<Mutex<ConnTable>>, IpAddr);
impl Drop for ConnSlot {
    fn drop(&mut self) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).release(self.1);
    }
}

struct Running {
    task: crate::host::async_runtime::JoinHandle<()>,
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

    let daemon = crate::mdns::shared_daemon().map_err(|e| format!("mDNS unavailable: {e}"))?;
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
    let conns = Arc::new(Mutex::new(ConnTable::default()));
    let task = crate::host::async_runtime::spawn(async move {
        loop {
            let Ok((stream, peer)) = listener.accept().await else { continue };
            let ip = peer.ip();
            if !conns.lock().unwrap_or_else(|e| e.into_inner()).try_take(ip, MAX_CONNECTIONS, MAX_PER_IP) {
                eprintln!("[quickshare] too many connections; dropping {peer}");
                continue; // `stream` drops here, closing the socket
            }
            let slot = ConnSlot(conns.clone(), ip);
            let app = handle.clone();
            let dest = match crate::transfer::download_dir(&app) {
                Some(d) => d,
                None => {
                    eprintln!("[quickshare] no Downloads directory; refusing {peer}");
                    continue;
                }
            };
            crate::host::async_runtime::spawn(async move {
                let _slot = slot;
                let (tx, rx) = mpsc::unbounded_channel();
                let pump = crate::host::async_runtime::spawn(forward_events(app.clone(), rx));
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
        // Wait (briefly) for the goodbye packet to go out. At quit, this is
        // the last chance; without it the process can end first and phones
        // keep offering this Mac as a Quick Share target.
        if let Ok(done) = running.daemon.unregister(&running.fullname) {
            let _ = done.recv_timeout(std::time::Duration::from_millis(500));
        }
        // The daemon is shared (see `mdns::shared_daemon`); only quit stops it.
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

/// What the Transfers page calls a share: the file's name, or the first name
/// and how many more.
fn share_name(files: &[IncomingFile]) -> String {
    match files {
        [] => "Quick Share".into(),
        [one] => one.name.clone(),
        [first, rest @ ..] => format!("{} + {} more", first.name, rest.len()),
    }
}

/// Translate one connection's events into Tauri events, holding the consent
/// channel until the UI answers.
async fn forward_events(app: AppHandle, mut rx: mpsc::UnboundedReceiver<Event>) {
    // One Transfers entry per share, opened by its first progress (so a
    // declined share never shows up) and closed by however it ends.
    let mut offered: Vec<IncomingFile> = Vec::new();
    let mut tracked: Option<crate::transfer_log::Tracked> = None;
    while let Some(event) = rx.recv().await {
        let state = app.state::<QuickShareState>();
        match event {
            Event::Introduced { peer, pin, files, reply } => {
                let mut pending = state.pending.lock().unwrap_or_else(|e| e.into_inner());
                // Prompts whose connection already gave up are not pending.
                pending.retain(|_, tx| !tx.is_closed());
                if pending.len() >= MAX_PENDING_PROMPTS {
                    // Refuse rather than queue: dropping `reply` makes the
                    // connection decline and tell the peer.
                    eprintln!("[quickshare] too many pending prompts; refusing {peer}");
                    continue;
                }
                offered = files.clone();
                let id = state.next_id.fetch_add(1, Ordering::Relaxed);
                pending.insert(id, reply);
                drop(pending);
                eprintln!("[quickshare] {peer} offers {} file(s), PIN {pin}", files.len());
                let _ = app.emit("quickshare-request", RequestPayload { id, peer, pin, files });
            }
            Event::Progress { received, total } => {
                let key = tracked.get_or_insert_with(|| {
                    crate::transfer_log::start(
                        &app,
                        crate::transfer_log::NewTransfer {
                            name: share_name(&offered),
                            dir: crate::transfer_log::Dir::ToMac,
                            source: crate::transfer_log::Source::QuickShare,
                            unit: crate::transfer_log::Unit::Bytes,
                            total: total.max(0) as u64,
                            cancel_id: None,
                        },
                    )
                })
                .key();
                crate::transfer_log::progress(&app, key, received.max(0) as u64);
                let _ = app.emit(
                    "quickshare-progress",
                    serde_json::json!({ "received": received, "total": total }),
                );
            }
            Event::Done { paths } => {
                eprintln!("[quickshare] received {} file(s)", paths.len());
                if let Some(t) = tracked.take() {
                    // One file: that file. Several: the folder they landed in.
                    let path = match paths.as_slice() {
                        [one] => Some(one.clone()),
                        [first, ..] => first.parent().map(|p| p.to_path_buf()),
                        [] => None,
                    };
                    t.finish(crate::transfer_log::Outcome::Done { path });
                }
                let _ = app.emit(
                    "quickshare-received",
                    serde_json::json!({
                        "paths": paths.iter().map(|p| p.to_string_lossy()).collect::<Vec<_>>()
                    }),
                );
            }
            // Declined on this Mac before any bytes moved, so there's no
            // Transfers row to close.
            Event::Rejected => {
                let _ = app.emit("quickshare-rejected", serde_json::json!({}));
            }
            Event::Failed { error } => {
                eprintln!("[quickshare] transfer failed: {error}");
                if let Some(t) = tracked.take() {
                    t.finish(crate::transfer_log::Outcome::Failed(error.clone()));
                }
                let _ = app.emit("quickshare-error", serde_json::json!({ "error": error }));
            }
        }
    }
    // The connection closed without saying how the share ended.
    if let Some(t) = tracked {
        t.finish(crate::transfer_log::Outcome::Failed("Connection to the sender was lost".into()));
    }
}

// ── Tauri commands ───────────────────────────────────────────────────────

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

pub fn quickshare_status(app: AppHandle) -> bool {
    is_running(&app)
}

/// The user's answer to a transfer prompt.
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_caps_apply_globally_and_per_address() {
        let a: IpAddr = "10.0.0.1".parse().unwrap();
        let b: IpAddr = "10.0.0.2".parse().unwrap();
        let mut t = ConnTable::default();
        for _ in 0..MAX_PER_IP {
            assert!(t.try_take(a, MAX_CONNECTIONS, MAX_PER_IP));
        }
        assert!(!t.try_take(a, MAX_CONNECTIONS, MAX_PER_IP), "one peer is capped");
        assert!(t.try_take(b, MAX_CONNECTIONS, MAX_PER_IP), "another peer still gets in");
        t.release(a);
        assert!(t.try_take(a, MAX_CONNECTIONS, MAX_PER_IP));
        let mut g = ConnTable::default();
        assert!(g.try_take(a, 1, 3));
        assert!(!g.try_take(b, 1, 3), "global cap");
        g.release(a);
        assert!(g.per_ip.is_empty() && g.total == 0);
    }
}
