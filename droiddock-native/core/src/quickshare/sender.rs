//! Quick Share send: discovery of nearby devices and the commands that drive
//! `client::send`. Emits `quickshare-devices`, `quickshare-send-*` events.
//!
//! Discovery only finds devices whose Quick Share screen is open and visible to
//! everyone (they advertise `_FC9F5ED42C8A._tcp` only then) — a Mac cannot wake
//! a phone over BLE, so that is a platform limit, not a gap here.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use base64::Engine;
use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use tokio::sync::mpsc;

use super::client::{self, OutFile, SendEvent};
use super::wire::{self, DeviceType};
use crate::host::{AppHandle, Manager};

#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NearbyDevice {
    /// mDNS instance name; pass back to `quickshare_send`.
    pub id: String,
    pub name: String,
    /// `phone` | `tablet` | `laptop` | `unknown`
    #[serde(rename = "type")]
    pub kind: String,
    pub host: String,
    pub port: u16,
}

struct Browsing {
    daemon: ServiceDaemon,
    task: crate::host::async_runtime::JoinHandle<()>,
}

#[derive(Default)]
pub struct QuickShareSendState {
    browsing: Mutex<Option<Browsing>>,
    devices: Mutex<HashMap<String, NearbyDevice>>,
    cancels: Mutex<HashMap<u64, Arc<AtomicBool>>>,
    next_id: AtomicU64,
}

fn kind_str(t: DeviceType) -> &'static str {
    match t {
        DeviceType::Phone => "phone",
        DeviceType::Tablet => "tablet",
        DeviceType::Laptop => "laptop",
        DeviceType::Unknown => "unknown",
    }
}

/// Turn a resolved mDNS record into a device. `None` for records that are not
/// well-formed Quick Share adverts (or are our own receive advert).
pub(crate) fn parse_device(fullname: &str, txt_n: Option<&str>, addrs: &[IpAddr], port: u16) -> Option<NearbyDevice> {
    let instance = fullname.split('.').next()?;
    // Instance: 0x23 + 4-byte endpoint id + service-id hash + 2 zero bytes.
    let raw = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(instance).ok()?;
    if raw.len() < 10 || raw[0] != 0x23 {
        return None;
    }
    let info = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(txt_n?).ok()?;
    let peer = wire::parse_endpoint_info(&info)?;
    // Prefer IPv4 (link-local v6 needs a scope id we don't track).
    let ip = addrs.iter().find(|a| a.is_ipv4() && !a.is_loopback())?;
    Some(NearbyDevice {
        id: instance.to_string(),
        name: peer.name,
        kind: kind_str(peer.device_type).to_string(),
        host: ip.to_string(),
        port,
    })
}

fn publish(app: &AppHandle) {
    let mut list: Vec<NearbyDevice> =
        app.state::<QuickShareSendState>().devices.lock().unwrap().values().cloned().collect();
    list.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()).then(a.id.cmp(&b.id)));
    let _ = app.emit("quickshare-devices", list);
}

/// Start browsing. Idempotent. Emits the (initially empty) list at once.
pub async fn quickshare_discover_start(app: AppHandle) -> Result<(), String> {
    let state = app.state::<QuickShareSendState>();
    // Held across check-and-create (no await in between), so two racing starts
    // can never build two daemons.
    let mut browsing = state.browsing.lock().unwrap();
    if browsing.is_some() {
        drop(browsing);
        publish(&app);
        return Ok(());
    }
    let daemon = crate::mdns::shared_daemon().map_err(|e| format!("mDNS unavailable: {e}"))?;
    let rx = daemon.browse(wire::SERVICE_TYPE).map_err(|e| format!("could not browse: {e}"))?;
    state.devices.lock().unwrap().clear();
    let handle = app.clone();
    let task = crate::host::async_runtime::spawn(async move {
        while let Ok(event) = rx.recv_async().await {
            let st = handle.state::<QuickShareSendState>();
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let addrs: Vec<IpAddr> = info.get_addresses().iter().map(|a| a.to_ip_addr()).collect();
                    if let Some(d) = parse_device(info.get_fullname(), info.get_property_val_str("n"), &addrs, info.get_port()) {
                        // Never list ourselves (our own receive advert).
                        if info.get_hostname().starts_with("droiddock-qs-") {
                            continue;
                        }
                        st.devices.lock().unwrap().insert(d.id.clone(), d);
                        publish(&handle);
                    }
                }
                ServiceEvent::ServiceRemoved(_, fullname) => {
                    let id = fullname.split('.').next().unwrap_or("").to_string();
                    if st.devices.lock().unwrap().remove(&id).is_some() {
                        publish(&handle);
                    }
                }
                _ => {}
            }
        }
    });
    *browsing = Some(Browsing { daemon, task });
    drop(browsing);
    publish(&app);
    Ok(())
}

pub fn quickshare_discover_stop(app: AppHandle) {
    let state = app.state::<QuickShareSendState>();
    if let Some(b) = state.browsing.lock().unwrap().take() {
        let _ = b.daemon.stop_browse(wire::SERVICE_TYPE);
        b.task.abort();
    }
    state.devices.lock().unwrap().clear();
    publish(&app);
}

/// Current device list (also pushed as `quickshare-devices`).
pub fn quickshare_devices(app: AppHandle) -> Vec<NearbyDevice> {
    let mut v: Vec<NearbyDevice> =
        app.state::<QuickShareSendState>().devices.lock().unwrap().values().cloned().collect();
    v.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    v
}

/// Send files to a discovered device. Returns the transfer id; progress and
/// outcome arrive as events:
///   `quickshare-send-pin {transferId, peer, pin}`     handshake done; both screens show `pin`
///   `quickshare-send-waiting {transferId}`            waiting for the other person to accept
///   `quickshare-send-progress {transferId, sent, total}`
///   `quickshare-send-done {transferId}`
///   `quickshare-send-rejected {transferId}`
///   `quickshare-send-error {transferId, error}`
pub async fn quickshare_send(app: AppHandle, device_id: String, paths: Vec<String>) -> Result<u64, String> {
    let state = app.state::<QuickShareSendState>();
    let device = state
        .devices
        .lock()
        .unwrap()
        .get(&device_id)
        .cloned()
        .ok_or("That device is no longer visible. Open Quick Share on it and try again.")?;
    if paths.is_empty() {
        return Err("Choose at least one file".into());
    }
    let mut files = Vec::new();
    for p in &paths {
        files.push(OutFile::from_path(p.into()).map_err(|e| format!("{p}: {e}"))?);
    }
    let ip: IpAddr = device.host.parse().map_err(|_| "bad device address".to_string())?;
    let stream = tokio::time::timeout(
        std::time::Duration::from_secs(8),
        tokio::net::TcpStream::connect(SocketAddr::new(ip, device.port)),
    )
    .await
    .map_err(|_| "Could not reach the device".to_string())?
    .map_err(|e| format!("Could not reach the device: {e}"))?;

    let id = state.next_id.fetch_add(1, Ordering::Relaxed) + 1;
    let cancel = Arc::new(AtomicBool::new(false));
    state.cancels.lock().unwrap().insert(id, cancel.clone());
    let name = crate::mdns::instance_name(&app);
    let total: i64 = files.iter().map(|f| f.size).sum();
    let label = match files.as_slice() {
        [one] => one.name.clone(),
        [first, rest @ ..] => format!("{} + {} more", first.name, rest.len()),
        [] => String::new(),
    };

    let handle = app.clone();
    crate::host::async_runtime::spawn(async move {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let pump_app = handle.clone();
        let peer_name = device.name.clone();
        let pump = tokio::spawn(async move {
            let mut tracked: Option<crate::transfer_log::Tracked> = None;
            // The sender reports once per 64 KiB chunk; the UI needs ~10 updates a second, not thousands.
            let mut last_emit: Option<std::time::Instant> = None;
            while let Some(ev) = rx.recv().await {
                match ev {
                    SendEvent::Connected { pin, .. } => {
                        let _ = pump_app.emit("quickshare-send-pin", serde_json::json!({ "transferId": id, "peer": peer_name, "pin": pin }));
                    }
                    SendEvent::WaitingForAccept => {
                        let _ = pump_app.emit("quickshare-send-waiting", serde_json::json!({ "transferId": id }));
                    }
                    SendEvent::Progress { sent, total } => {
                        let key = tracked
                            .get_or_insert_with(|| {
                                crate::transfer_log::start(
                                    &pump_app,
                                    crate::transfer_log::NewTransfer {
                                        name: label.clone(),
                                        dir: crate::transfer_log::Dir::ToPhone,
                                        source: crate::transfer_log::Source::QuickShare,
                                        unit: crate::transfer_log::Unit::Bytes,
                                        total: total.max(0) as u64,
                                        cancel_id: None,
                                    },
                                )
                            })
                            .key();
                        crate::transfer_log::progress(&pump_app, key, sent.max(0) as u64);
                        let due = last_emit.map_or(true, |t| t.elapsed() >= std::time::Duration::from_millis(100)) || sent >= total;
                        if due {
                            last_emit = Some(std::time::Instant::now());
                            let _ = pump_app.emit("quickshare-send-progress", serde_json::json!({ "transferId": id, "sent": sent, "total": total }));
                        }
                    }
                    SendEvent::Done => {
                        if let Some(t) = tracked.take() {
                            t.finish(crate::transfer_log::Outcome::Done { path: None });
                        }
                        let _ = pump_app.emit("quickshare-send-done", serde_json::json!({ "transferId": id }));
                    }
                    SendEvent::Rejected => {
                        let _ = pump_app.emit("quickshare-send-rejected", serde_json::json!({ "transferId": id }));
                    }
                    SendEvent::Failed { error } => {
                        if let Some(t) = tracked.take() {
                            t.finish(crate::transfer_log::Outcome::Failed(error.clone()));
                        }
                        let _ = pump_app.emit("quickshare-send-error", serde_json::json!({ "transferId": id, "error": error }));
                    }
                }
            }
        });
        let _ = total;
        let _ = client::send(stream, &name, files, tx, cancel).await;
        let _ = pump.await;
        handle.state::<QuickShareSendState>().cancels.lock().unwrap().remove(&id);
    });
    Ok(id)
}

/// Cancel an in-flight send.
pub fn quickshare_send_cancel(app: AppHandle, transfer_id: u64) -> Result<(), String> {
    match app.state::<QuickShareSendState>().cancels.lock().unwrap().get(&transfer_id) {
        Some(flag) => {
            flag.store(true, Ordering::Relaxed);
            Ok(())
        }
        None => Err("that transfer has already finished".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn advert(id: &[u8; 4], name: &str, kind: DeviceType) -> (String, String) {
        let inst = wire::mdns_instance_name(&wire::EndpointId::random());
        let _ = id;
        let info = wire::endpoint_info(kind, name);
        let n = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(info);
        (format!("{inst}._FC9F5ED42C8A._tcp.local."), n)
    }

    #[test]
    fn a_phone_advert_decodes_to_a_device() {
        let (full, n) = advert(b"abcd", "Pixel 9", DeviceType::Phone);
        let ips = ["fe80::1".parse().unwrap(), "192.168.1.20".parse().unwrap()];
        let d = parse_device(&full, Some(&n), &ips, 41234).unwrap();
        assert_eq!((d.name.as_str(), d.kind.as_str(), d.host.as_str(), d.port), ("Pixel 9", "phone", "192.168.1.20", 41234));
    }

    #[test]
    fn garbage_adverts_are_ignored() {
        let (full, n) = advert(b"abcd", "X", DeviceType::Phone);
        let ip = ["10.0.0.2".parse().unwrap()];
        assert!(parse_device(&full, None, &ip, 1).is_none(), "no TXT");
        assert!(parse_device("not-base64!._x._tcp.local.", Some(&n), &ip, 1).is_none());
        assert!(parse_device(&full, Some("AAAA"), &ip, 1).is_none(), "short endpoint info");
        assert!(parse_device(&full, Some(&n), &[], 1).is_none(), "no address");
    }
}
