use crate::host::{AppHandle, Manager};
use tokio::net::UdpSocket;

/// UDP discovery on `port + 1`: phone broadcasts `DROIDDOCK:DISCOVER:<token>`
/// when it can't reach any known IP (e.g. both devices switched networks);
/// we reply `DROIDDOCK:HERE` so it learns our current IP without re-pairing.
/// Byte-for-byte match to wifi.js — see CLAUDE.md's compatibility mandate.
pub async fn run(app: AppHandle, discovery_port: u16) {
    // Keep retrying forever (backoff up to 30 s): an old process may still
    // hold the port after a relaunch. Log once.
    let mut delay = std::time::Duration::from_millis(500);
    let mut logged = false;
    let socket = loop {
        match UdpSocket::bind(("0.0.0.0", discovery_port)).await {
            Ok(s) => break s,
            Err(_) => {
                if !logged {
                    eprintln!("discovery: 0.0.0.0:{discovery_port} busy, retrying in the background");
                    logged = true;
                }
                tokio::time::sleep(delay).await;
                delay = (delay * 2).min(std::time::Duration::from_secs(30));
            }
        }
    };

    let mut buf = [0u8; 256];
    loop {
        let Ok((len, addr)) = socket.recv_from(&mut buf).await else {
            // Don't spin hot if the socket is persistently erroring.
            tokio::time::sleep(std::time::Duration::from_millis(200)).await;
            continue;
        };
        let Ok(text) = std::str::from_utf8(&buf[..len]) else {
            continue;
        };
        // Compare against the LIVE in-memory token (like wifi.js's closure over
        // `config`), never a per-packet disk read: `load_or_create` regenerates
        // a fresh-token default on any unreadable read, so calling it here
        // would let one racing/corrupt read silently unpair the phone — and a
        // token regenerated on this path would disagree with the one ws-auth
        // still holds in memory.
        let token = app
            .state::<crate::AppState>()
            .config
            .lock()
            .unwrap()
            .token
            .clone();
        let expected = format!("DROIDDOCK:DISCOVER:{token}");
        if text.trim() == expected {
            let _ = socket.send_to(b"DROIDDOCK:HERE", addr).await;
        }
    }
}
