//! Optional AES-256-GCM encryption for the link's JSON control messages (Tier C).
//!
//! # What this covers, and what it deliberately does not
//!
//! **Covered:** every JSON control message after the handshake — clipboard text,
//! notifications and their replies, SMS bodies, contacts, call events, file
//! *listings*, media metadata. That is where the personal data on this link
//! actually lives.
//!
//! **Not covered:** binary frames — file-transfer chunks, photo thumbnails,
//! app icons, and mirror video. Those ride a separate framing path
//! (`[kind][id][seq]` + payload) on the hot transfer/mirror loops, and wrapping
//! each 256 KiB chunk and each 30fps video frame would mean surgery on the
//! highest-throughput, least-verified code in the app for the least privacy
//! gain per byte. **So this is not end-to-end encryption of everything**, and
//! the Settings copy says so rather than implying otherwise.
//!
//! # Key
//!
//! HKDF-SHA256 over the pairing token both sides already share — no new secret,
//! no key exchange, nothing extra to pair. The token is the only shared secret
//! in the system, so the link is exactly as strong as the token is: this defends
//! against a passive listener on your Wi-Fi, not against someone who already has
//! your pairing QR.
//!
//! # Negotiation
//!
//! Strictly opt-in and fail-safe: the phone advertises `"enc"` in `hello.caps`,
//! the Mac answers with `"enc"` in `welcome.caps` **only if** the user turned
//! `encryptLink` on. If either side stays quiet the link runs in plaintext
//! exactly as before, so enabling this can never orphan an older phone build —
//! it just doesn't engage.

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use hkdf::Hkdf;
use serde_json::{json, Value};
use sha2::Sha256;

/// Bumped if the derivation or envelope ever changes, so an old phone and a new
/// Mac can't silently derive different keys and fail with a decrypt error.
const INFO: &[u8] = b"droiddock-link-v1";
const SALT: &[u8] = b"droiddock-hkdf-salt-v1";
const NONCE_LEN: usize = 12;

/// The capability string both sides advertise.
pub const CAP: &str = "enc";

#[derive(Clone)]
pub struct LinkKey(Key<Aes256Gcm>);

/// Derive the session key from the pairing token. Deterministic on both ends —
/// same token in, same key out, no handshake round-trip.
pub fn derive(token: &str) -> LinkKey {
    let hk = Hkdf::<Sha256>::new(Some(SALT), token.as_bytes());
    let mut out = [0u8; 32];
    hk.expand(INFO, &mut out)
        .expect("32 bytes is a valid HKDF-SHA256 output length");
    LinkKey(Key::<Aes256Gcm>::from(out))
}

/// Wrap one plaintext JSON message in the `enc` envelope.
///
/// A fresh random nonce per message: GCM catastrophically loses confidentiality
/// *and* integrity on nonce reuse, so this never derives one from a counter that
/// could restart after a reconnect.
pub fn seal(key: &LinkKey, plaintext: &Value) -> Result<Value, String> {
    let cipher = Aes256Gcm::new(&key.0);

    // Straight from the OS CSPRNG (getrandom → macOS `getentropy`). GCM's
    // security collapses on a repeated nonce, so this must never come from a
    // counter that could restart across reconnects.
    let mut nonce_bytes = [0u8; NONCE_LEN];
    getrandom::fill(&mut nonce_bytes).map_err(|e| format!("no system randomness: {e}"))?;

    let body = serde_json::to_vec(plaintext).map_err(|e| e.to_string())?;
    let sealed = cipher
        .encrypt(&Nonce::from(nonce_bytes), body.as_ref())
        .map_err(|_| "encrypt failed".to_string())?;

    Ok(json!({
        "type": "enc",
        "n": crate::base64_encode(&nonce_bytes),
        "d": crate::base64_encode(&sealed),
    }))
}

/// Unwrap an `enc` envelope back into the message it carries.
///
/// Every failure — malformed base64, wrong length, bad tag — collapses to one
/// opaque error on purpose: distinguishing them for a caller who can't act on
/// the difference only helps someone probing the link.
pub fn open(key: &LinkKey, envelope: &Value) -> Result<Value, String> {
    let nonce_b64 = envelope.get("n").and_then(Value::as_str).ok_or("bad envelope")?;
    let data_b64 = envelope.get("d").and_then(Value::as_str).ok_or("bad envelope")?;

    let nonce = base64_decode(nonce_b64).ok_or("bad envelope")?;
    if nonce.len() != NONCE_LEN {
        return Err("bad envelope".into());
    }
    let sealed = base64_decode(data_b64).ok_or("bad envelope")?;

    // Length was checked above, so this conversion cannot fail.
    let nonce: [u8; NONCE_LEN] = nonce.try_into().map_err(|_| "bad envelope".to_string())?;
    let cipher = Aes256Gcm::new(&key.0);
    let plain = cipher
        .decrypt(&Nonce::from(nonce), sealed.as_ref())
        .map_err(|_| "bad envelope".to_string())?;

    serde_json::from_slice(&plain).map_err(|_| "bad envelope".into())
}

/// Standard alphabet, padding tolerated — the mirror of `crate::base64_encode`,
/// kept here rather than pulling a crate for two call sites.
fn base64_decode(s: &str) -> Option<Vec<u8>> {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut lookup = [255u8; 256];
    for (i, c) in TABLE.iter().enumerate() {
        lookup[*c as usize] = i as u8;
    }

    let mut acc: u32 = 0;
    let mut bits = 0;
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    for b in s.bytes() {
        if b == b'=' {
            break;
        }
        let v = lookup[b as usize];
        if v == 255 {
            return None; // whitespace and stray characters are not tolerated
        }
        acc = (acc << 6) | u32::from(v);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_a_message() {
        let key = derive("some-pairing-token");
        let msg = json!({ "type": "clipboard", "text": "hello ✅ world" });
        let sealed = seal(&key, &msg).unwrap();

        assert_eq!(sealed.get("type").unwrap(), "enc");
        // The plaintext must not be recoverable by eyeballing the envelope.
        assert!(!sealed.to_string().contains("clipboard"));

        assert_eq!(open(&key, &sealed).unwrap(), msg);
    }

    #[test]
    fn derivation_is_deterministic_and_token_bound() {
        let a = derive("token-a");
        let b = derive("token-a");
        let c = derive("token-b");
        let msg = json!({ "type": "ping" });

        // Same token → interoperable.
        assert_eq!(open(&b, &seal(&a, &msg).unwrap()).unwrap(), msg);
        // Different token → cannot read it. This is what makes re-pairing
        // actually rotate the link key.
        assert!(open(&c, &seal(&a, &msg).unwrap()).is_err());
    }

    #[test]
    fn nonces_do_not_repeat() {
        let key = derive("t");
        let msg = json!({ "type": "ping" });
        let a = seal(&key, &msg).unwrap();
        let b = seal(&key, &msg).unwrap();
        // Identical plaintext under a reused nonce would produce identical
        // ciphertext — the exact failure GCM must never have.
        assert_ne!(a.get("n"), b.get("n"));
        assert_ne!(a.get("d"), b.get("d"));
    }

    #[test]
    fn tampering_is_rejected() {
        let key = derive("t");
        let sealed = seal(&key, &json!({ "type": "dismiss", "key": "abc" })).unwrap();

        let mut flipped = sealed.clone();
        let d = sealed.get("d").unwrap().as_str().unwrap();
        // Flip one ciphertext character; GCM's tag must catch it.
        let mut chars: Vec<char> = d.chars().collect();
        chars[0] = if chars[0] == 'A' { 'B' } else { 'A' };
        flipped["d"] = Value::from(chars.into_iter().collect::<String>());
        assert!(open(&key, &flipped).is_err());

        // Truncation (dropping the tag) must fail too, not decrypt partially.
        let mut truncated = sealed.clone();
        truncated["d"] = Value::from(&d[..d.len() / 2]);
        assert!(open(&key, &truncated).is_err());
    }

    #[test]
    fn malformed_envelopes_are_rejected_not_panicked_on() {
        let key = derive("t");
        assert!(open(&key, &json!({ "type": "enc" })).is_err());
        assert!(open(&key, &json!({ "type": "enc", "n": "!!!", "d": "!!!" })).is_err());
        // Right shape, wrong nonce length.
        assert!(open(&key, &json!({ "type": "enc", "n": "AAAA", "d": "AAAA" })).is_err());
    }

    #[test]
    fn base64_decode_matches_encoder() {
        for bytes in [vec![], vec![0u8], vec![1, 2], vec![1, 2, 3], (0u8..=255).collect()] {
            let encoded = crate::base64_encode(&bytes);
            assert_eq!(base64_decode(&encoded).unwrap(), bytes);
        }
    }
}
