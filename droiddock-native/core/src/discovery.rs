use crate::host::{AppHandle, Manager};
use tokio::net::UdpSocket;

/// UDP discovery on `port + 1`: phone broadcasts `DROIDDOCK:DISCOVER2:<hmac>` (or the
/// legacy `DROIDDOCK:DISCOVER:<token>` from older phones)
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
        if probe_matches(text.trim(), &token, unix_now()) {
            let _ = socket.send_to(b"DROIDDOCK:HERE", addr).await;
        }
    }
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Lowercase hex of the first 16 bytes of HMAC-SHA256(key = token UTF-8,
/// msg = "droiddock-discover:" + w), `w` a decimal 5-minute window number.
pub fn discover2_tag(token: &str, w: u64) -> String {
    use hmac::{digest::KeyInit, Hmac, Mac};
    let mut m = Hmac::<sha2::Sha256>::new_from_slice(token.as_bytes()).expect("hmac accepts any key length");
    m.update(format!("droiddock-discover:{w}").as_bytes());
    let out = m.finalize().into_bytes();
    out[..16].iter().map(|b| format!("{b:02x}")).collect()
}

/// Accepts `DROIDDOCK:DISCOVER2:<hex>` for windows w-1..=w+1 (clock skew) and
/// the legacy `DROIDDOCK:DISCOVER:<token>` (older phones). Constant-time compares.
pub fn probe_matches(text: &str, token: &str, now_secs: u64) -> bool {
    use crate::crypto::ct_eq;
    if let Some(hex) = text.strip_prefix("DROIDDOCK:DISCOVER2:") {
        let w = now_secs / 300;
        let mut ok = false;
        for cand in [w.saturating_sub(1), w, w + 1] {
            ok |= ct_eq(hex.as_bytes(), discover2_tag(token, cand).as_bytes());
        }
        return ok;
    }
    if let Some(t) = text.strip_prefix("DROIDDOCK:DISCOVER:") {
        return ct_eq(t.as_bytes(), token.as_bytes());
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    // Vector for the phone side: token "acad86c1-6041-408c-bce7-2495dd338711",
    // now = 1_762_500_000 s -> w = 5_875_000.
    #[test]
    fn discover2_vector_and_window_skew() {
        let tok = "acad86c1-6041-408c-bce7-2495dd338711";
        let now = 1_762_500_000u64;
        let w = now / 300;
        let tag = discover2_tag(tok, w);
        assert_eq!(tag.len(), 32);
        assert_eq!(tag, VECTOR_W, "vector for w={w}");
        let probe = |w: u64| format!("DROIDDOCK:DISCOVER2:{}", discover2_tag(tok, w));
        assert!(probe_matches(&probe(w), tok, now));
        assert!(probe_matches(&probe(w - 1), tok, now));
        assert!(probe_matches(&probe(w + 1), tok, now));
        assert!(!probe_matches(&probe(w - 2), tok, now));
        assert!(!probe_matches(&probe(w + 2), tok, now));
        assert!(!probe_matches(&probe(w), "other-token", now));
        assert!(!probe_matches("DROIDDOCK:DISCOVER2:", tok, now));
    }

    #[test]
    fn legacy_probe_still_answers() {
        assert!(probe_matches("DROIDDOCK:DISCOVER:abc", "abc", 0));
        assert!(!probe_matches("DROIDDOCK:DISCOVER:abd", "abc", 0));
        assert!(!probe_matches("DROIDDOCK:DISCOVER:", "abc", 0));
    }

    const VECTOR_W: &str = "43a61c8666352254f2cd02f1979e0288";
}
