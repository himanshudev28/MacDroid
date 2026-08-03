use tauri::{AppHandle, Manager};
use tokio::net::UdpSocket;

/// UDP discovery on `port + 1`: phone broadcasts `DROIDDOCK:DISCOVER:<token>`
/// when it can't reach any known IP (e.g. both devices switched networks);
/// we reply `DROIDDOCK:HERE` so it learns our current IP without re-pairing.
/// Byte-for-byte match to wifi.js — see CLAUDE.md's compatibility mandate.
pub async fn run(app: AppHandle, discovery_port: u16) {
    let socket = match UdpSocket::bind(("0.0.0.0", discovery_port)).await {
        Ok(s) => s,
        Err(e) => {
            eprintln!("discovery: failed to bind 0.0.0.0:{discovery_port}: {e}");
            return;
        }
    };

    let mut buf = [0u8; 256];
    loop {
        let Ok((len, addr)) = socket.recv_from(&mut buf).await else {
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
