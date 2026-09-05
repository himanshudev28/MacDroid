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
use std::time::Duration;
use tauri::{AppHandle, Manager};

pub const SERVICE_TYPE: &str = "_droiddock._tcp.local.";

/// How often to re-check the interface list. A Wi-Fi change swaps our IPs
/// without any signal we can await here, and an advert pointing at a dead
/// address is worse than none.
const REFRESH: Duration = Duration::from_secs(30);

/// Advertise for the app's lifetime, re-registering whenever our LAN addresses
/// change. Failures are non-fatal by design: mDNS is the *fallback* path, so if
/// the daemon can't start we log once and leave UDP broadcast to do its job.
pub async fn run(app: AppHandle, port: u16) {
    let daemon = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("[mdns] daemon unavailable, falling back to UDP broadcast only: {e}");
            return;
        }
    };

    let instance = instance_name(&app);
    let mut advertised: Vec<IpAddr> = Vec::new();
    let mut fullname: Option<String> = None;

    loop {
        let ips = lan_ipv4s();

        if ips != advertised {
            if let Some(old) = fullname.take() {
                let _ = daemon.unregister(&old);
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
    ips
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
    use super::sanitize_host;

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
