//! Bonjour/mDNS service advertising (Tier C).
//!
//! A second discovery path alongside `discovery.rs`'s UDP broadcast, not a
//! replacement for it. The broadcast path works and stays exactly as it was —
//! but a lot of consumer routers drop directed broadcast or enable AP/client
//! isolation, and on those networks the phone can only ever reach a Mac whose
//! IP it already has. Multicast DNS usually survives where broadcast doesn't,
//! so publishing here gives the phone a way back after a network change that
//! the broadcast probe can't recover from.
//!
//! **No secret is published.** The TXT record carries the Mac's display name
//! and nothing else; the pairing token never leaves the WS handshake. Anyone on
//! the LAN can see that a DroidDock Mac exists — the same thing every Bonjour
//! service on the network already announces about itself — but discovering it
//! grants nothing, because `ws_server`'s hello still requires the token and
//! still times out in 5s without one.

use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::Duration;
use crate::host::{AppHandle, Manager};

/// The running advert, kept where quit cleanup can reach it: the daemon, and
/// the name currently registered (`None` while offline).
static ADVERT: Mutex<Option<(ServiceDaemon, Option<String>)>> = Mutex::new(None);

/// The one mDNS daemon for the whole process. `mdns-sd` runs a thread with its
/// own poll loop and multicast sockets per daemon, and each one wakes for every
/// mDNS packet on the LAN, so DroidDock's advert, the Quick Share advert and
/// Quick Share discovery all share this instead of starting three.
///
/// Only [`stop_blocking`] (quit) shuts it down; the other users just
/// unregister / stop browsing.
static SHARED: Mutex<Option<ServiceDaemon>> = Mutex::new(None);

pub(crate) fn shared_daemon() -> Result<ServiceDaemon, String> {
    let mut g = SHARED.lock().unwrap();
    if let Some(d) = g.as_ref() {
        return Ok(d.clone());
    }
    let d = ServiceDaemon::new().map_err(|e| e.to_string())?;
    *g = Some(d.clone());
    Ok(d)
}

/// Quit cleanup: withdraw the advert and stop the daemon.
///
/// Unregistering sends a "goodbye" packet, so phones drop this Mac at once
/// instead of listing it until the record expires. The packet is sent from the
/// daemon's own thread, so this waits (briefly) for it to go out before the
/// process ends.
pub fn stop_blocking() {
    if let Some((daemon, Some(name))) = ADVERT.lock().unwrap().take() {
        if let Ok(done) = daemon.unregister(&name) {
            let _ = done.recv_timeout(Duration::from_millis(500));
        }
    }
    // The shared daemon also serves Quick Share discovery, which may be
    // running even when no advert is registered; stop it either way.
    if let Some(d) = SHARED.lock().unwrap().take() {
        let _ = d.shutdown();
    }
}

pub const SERVICE_TYPE: &str = "_droiddock._tcp.local.";

/// How often to re-check the interface list. A Wi-Fi change swaps our IPs
/// without any signal we can await here, and an advert pointing at a dead
/// address is worse than none.
const REFRESH: Duration = Duration::from_secs(30);

/// Advertise for the app's lifetime, re-registering whenever our LAN addresses
/// change. Failures are non-fatal by design: mDNS is the *fallback* path, so if
/// the daemon can't start we log once and leave UDP broadcast to do its job.
pub async fn run(app: AppHandle, port: u16) {
    let daemon = match shared_daemon() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("[mdns] daemon unavailable, falling back to UDP broadcast only: {e}");
            return;
        }
    };

    let instance = instance_name(&app);
    let mut advertised: Vec<IpAddr> = Vec::new();
    let mut fullname: Option<String> = None;
    *ADVERT.lock().unwrap() = Some((daemon.clone(), None));

    loop {
        // Quit cleanup has taken the daemon and shut it down; stop here
        // rather than register again on a dead daemon.
        if ADVERT.lock().unwrap().is_none() {
            return;
        }
        let iface = app.state::<crate::AppState>().config.lock().unwrap().server_iface.clone();
        let ips = selected_ipv4s(&iface);

        if !same_set(&ips, &advertised) {
            // The LAN changed under us (not the first pass): any adb reconnect
            // back-off was earned on the old network.
            if !advertised.is_empty() {
                crate::pacing::poke_reconnect();
                // A new network may bring the phone back in range: scan continuously again.
                crate::ble_policy::kick_scan();
            }
            // Tell the UI so the pairing QR / address row follow the new LAN.
            let _ = app.emit("pairing-changed", serde_json::json!({}));
            if let Some(old) = fullname.take() {
                let _ = daemon.unregister(&old);
                if let Some(advert) = ADVERT.lock().unwrap().as_mut() {
                    advert.1 = None;
                }
            }

            if ips.is_empty() {
                // Offline: nothing to point at. Keep looping so we re-advertise
                // the moment an interface comes back.
                advertised.clear();
            } else {
                let mut txt = HashMap::new();
                txt.insert("name".to_string(), instance.clone());

                match ServiceInfo::new(
                    SERVICE_TYPE,
                    &instance,
                    // Own host record rather than the system's, so we never
                    // collide with whatever else claims `<host>.local.`
                    &format!("droiddock-{}.local.", sanitize_host(&instance)),
                    &ips[..],
                    port,
                    txt,
                ) {
                    Ok(info) => {
                        let name = info.get_fullname().to_string();
                        match daemon.register(info) {
                            Ok(()) => {
                                if let Some(advert) = ADVERT.lock().unwrap().as_mut() {
                                    advert.1 = Some(name.clone());
                                }
                                fullname = Some(name);
                                advertised = ips;
                            }
                            Err(e) => eprintln!("[mdns] register failed: {e}"),
                        }
                    }
                    Err(e) => eprintln!("[mdns] service info invalid: {e}"),
                }
            }
        }

        tokio::time::sleep(REFRESH).await;
    }
}

/// The name the phone shows for this Mac — the same one `get_pairing_info`
/// reports, so the two never disagree.
pub(crate) fn instance_name(app: &AppHandle) -> String {
    let configured = app
        .state::<crate::AppState>()
        .config
        .lock()
        .unwrap()
        .device_name
        .clone();

    configured
        .map(|n| n.trim().to_string())
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| {
            gethostname::gethostname()
                .to_string_lossy()
                .trim_end_matches(".local")
                .to_string()
        })
}

/// Unregister the DroidDock advert (restart / port change); the daemon stays.
pub fn withdraw() {
    if let Some(advert) = ADVERT.lock().unwrap().as_mut() {
        if let Some(name) = advert.1.take() {
            let _ = advert.0.unregister(&name);
        }
    }
}

/// Addresses to advertise: the chosen adapter's, or every LAN address (Auto,
/// or when the chosen adapter has gone away).
pub fn selected_ipv4s(iface: &str) -> Vec<IpAddr> {
    if !iface.is_empty() {
        let mut ips: Vec<IpAddr> = if_addrs::get_if_addrs()
            .unwrap_or_default()
            .into_iter()
            .filter(|i| !i.is_loopback() && i.name == iface && matches!(i.ip(), IpAddr::V4(_)))
            .map(|i| i.ip())
            .collect();
        ips.sort();
        ips.dedup();
        if !ips.is_empty() {
            return primary_first(ips);
        }
    }
    lan_ipv4s()
}

pub(crate) fn lan_ipv4s() -> Vec<IpAddr> {
    let mut ips: Vec<IpAddr> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        // IPv4 only: the phone dials `ws://<ip>:<port>` and its stored
        // pairing addresses are v4, so a v6 advert would just fail to connect.
        .filter(|i| matches!(i.ip(), IpAddr::V4(_)))
        .map(|i| i.ip())
        .collect();
    // Stable order so the change-detection compare is about the *set*, not the
    // order the OS happened to enumerate interfaces in this time.
    ips.sort();
    ips.dedup();
    primary_first(ips)
}

/// The source address the OS would use to reach the internet/default route.
/// A UDP connect sends nothing; it only picks a route.
fn primary_ipv4() -> Option<IpAddr> {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("203.0.113.1:9").ok()?;
    let ip = sock.local_addr().ok()?.ip();
    (!ip.is_unspecified() && !ip.is_loopback()).then_some(ip)
}

/// Move `primary` to the front; nothing is dropped. Fixes a stale address
/// (old DHCP lease still on the interface) being listed before the live one.
fn order_with_primary(mut ips: Vec<IpAddr>, primary: Option<IpAddr>) -> Vec<IpAddr> {
    if let Some(p) = primary {
        if let Some(pos) = ips.iter().position(|i| *i == p) {
            let ip = ips.remove(pos);
            ips.insert(0, ip);
        }
    }
    ips
}

fn primary_first(ips: Vec<IpAddr>) -> Vec<IpAddr> {
    order_with_primary(ips, primary_ipv4())
}

/// Compare as sets: order may change without the network changing.
fn same_set(a: &[IpAddr], b: &[IpAddr]) -> bool {
    let mut a = a.to_vec();
    let mut b = b.to_vec();
    a.sort();
    b.sort();
    a == b
}

/// DNS labels allow letters, digits and hyphens — a device name like
/// "Himanshu's MacBook Air" is not a legal hostname as-is.
fn sanitize_host(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' })
        .collect();
    let trimmed = cleaned.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "mac".to_string()
    } else {
        trimmed.chars().take(40).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::{order_with_primary, same_set, sanitize_host};
    use std::net::IpAddr;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn primary_goes_first_and_nothing_is_dropped() {
        let ips = vec![ip("10.131.164.254"), ip("192.168.31.50")];
        let out = order_with_primary(ips.clone(), Some(ip("192.168.31.50")));
        assert_eq!(out, vec![ip("192.168.31.50"), ip("10.131.164.254")]);
        assert_eq!(order_with_primary(ips.clone(), Some(ip("10.0.0.9"))), ips);
        assert_eq!(order_with_primary(ips.clone(), None), ips);
    }

    #[test]
    fn set_compare_ignores_order() {
        assert!(same_set(&[ip("1.1.1.1"), ip("2.2.2.2")], &[ip("2.2.2.2"), ip("1.1.1.1")]));
        assert!(!same_set(&[ip("1.1.1.1")], &[ip("2.2.2.2")]));
    }

    #[test]
    fn host_labels_are_dns_legal() {
        assert_eq!(sanitize_host("Himanshu's MacBook Air"), "himanshu-s-macbook-air");
        assert_eq!(sanitize_host("MacBookAir"), "macbookair");
        // Leading/trailing separators would produce an illegal label.
        assert_eq!(sanitize_host("  ...  "), "mac");
        assert_eq!(sanitize_host(""), "mac");
        // Long names are truncated rather than rejected (63 is the DNS label cap).
        assert!(sanitize_host(&"x".repeat(200)).len() <= 40);
    }
}
