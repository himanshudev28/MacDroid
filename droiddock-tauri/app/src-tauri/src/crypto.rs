//! Optional AES-256-GCM encryption for the link's JSON control messages (Tier C).
//!
//! # What this covers, and what it deliberately does not
//!
//! **Covered:** every JSON control message after the handshake — clipboard text,
//! notifications and their replies, SMS bodies, contacts, call events, file
//! *listings*, media metadata. That is where the personal data on this link
//! actually lives.
//!
//! **Also covered, as of `enc2`:** binary frames — file-transfer chunks, photo
//! thumbnails, app icons and mirror video. These ride a separate framing path
//! (`[kind][id][seq]` + payload) and are sealed whole, header included; see
//! `seal_frame` below. Negotiated as its own capability so a phone that
//! understands sealed JSON but not sealed frames keeps working.
//!
//! With both engaged, everything after the handshake is encrypted. The
//! handshake itself is not, and cannot be: `hello` carries the token that
//! derives the key, and `welcome` is the message that announces encryption is
//! on. What that leaks is the phone's name and the fact of a connection.
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

// ── Binary frames (`enc2`) ───────────────────────────────────────────────
//
// The JSON envelope above covers control messages. This covers the other half
// of the link: file chunks, thumbnails, app icons and mirror video, which ride
// the `[kind][id][seq]` framing in `crate::transfer` rather than the JSON path.
//
// **Negotiated separately from `enc`**, as its own `enc2` capability. A phone
// that understands sealed JSON but not sealed frames is a real combination —
// this shipped later — and conflating them would break that phone's transfers
// the moment the user turned encryption on.

/// Frame kind marking a sealed frame. Distinct from every kind in
/// `crate::transfer`, so an unsealed receiver hits its `_ => {}` default and
/// drops the frame rather than misreading a nonce as a header.
pub const KIND_SEALED: u8 = 0x80;

/// The capability advertised for sealed binary frames.
pub const CAP_FRAMES: &str = "enc2";

/// Seal a complete binary frame — header included.
///
/// The whole frame is the plaintext, not just the payload: the header carries
/// the transfer id and sequence number, and leaving those in the clear would
/// leak the shape of every transfer (how many files, how large, in what order)
/// to exactly the passive listener this is defending against.
///
/// Layout out: `[KIND_SEALED][12B nonce][AES-256-GCM(frame)]`, so the overhead
/// is a fixed 29 bytes — negligible against a 256 KiB chunk and acceptable on a
/// video frame.
pub fn seal_frame(key: &LinkKey, frame: &[u8]) -> Result<Vec<u8>, String> {
    let cipher = Aes256Gcm::new(&key.0);
    let mut nonce_bytes = [0u8; NONCE_LEN];
    // Random per frame, for the same reason `seal` uses random: a counter that
    // restarts on reconnect would repeat a nonce, and GCM does not survive that.
    // 96 random bits over the number of frames a mirror session can produce
    // leaves collision probability far below anything that matters here.
    getrandom::fill(&mut nonce_bytes).map_err(|e| format!("no system randomness: {e}"))?;

    let sealed = cipher
        .encrypt(&Nonce::from(nonce_bytes), frame)
        .map_err(|_| "encrypt failed".to_string())?;

    let mut out = Vec::with_capacity(1 + NONCE_LEN + sealed.len());
    out.push(KIND_SEALED);
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&sealed);
    Ok(out)
}

/// Unwrap a sealed frame back into the original one.
///
/// Returns `None` on anything malformed or unauthentic — same opaque-failure
/// posture as `open`. The caller drops the frame; there is nothing useful a
/// transfer can do with a frame that failed authentication.
pub fn open_frame(key: &LinkKey, buf: &[u8]) -> Option<Vec<u8>> {
    if buf.first() != Some(&KIND_SEALED) || buf.len() < 1 + NONCE_LEN {
        return None;
    }
    let nonce: [u8; NONCE_LEN] = buf[1..1 + NONCE_LEN].try_into().ok()?;
    let cipher = Aes256Gcm::new(&key.0);
    cipher
        .decrypt(&Nonce::from(nonce), &buf[1 + NONCE_LEN..])
        .ok()
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

    /// The round trip, and that the header is genuinely inside the ciphertext —
    /// a transfer id visible in the clear would leak the shape of every
    /// transfer, which is the thing sealing frames is for.
    #[test]
    fn a_sealed_frame_round_trips_and_hides_its_header() {
        let key = derive("pairing-token");
        // [KIND_DATA][transferId=0x01020304][seq=5] + payload
        let frame = vec![1, 1, 2, 3, 4, 0, 0, 0, 5, 0xAA, 0xBB, 0xCC];
        let sealed = seal_frame(&key, &frame).expect("seals");

        assert_eq!(sealed[0], KIND_SEALED);
        assert_eq!(open_frame(&key, &sealed).as_deref(), Some(frame.as_slice()));
        // Neither the header bytes nor the payload appear verbatim.
        assert!(!sealed.windows(frame.len()).any(|w| w == frame.as_slice()));
        assert!(!sealed[1..].windows(4).any(|w| w == [1, 2, 3, 4]));
    }

    /// Nonces must never repeat — GCM loses confidentiality *and* integrity on
    /// reuse, so two seals of identical plaintext must differ.
    #[test]
    fn identical_frames_seal_differently() {
        let key = derive("pairing-token");
        let frame = vec![1, 0, 0, 0, 1, 0, 0, 0, 1, 42];
        let a = seal_frame(&key, &frame).unwrap();
        let b = seal_frame(&key, &frame).unwrap();
        assert_ne!(a, b, "a repeated nonce would be a total break");
        // Both still open to the same plaintext.
        assert_eq!(open_frame(&key, &a).unwrap(), frame);
        assert_eq!(open_frame(&key, &b).unwrap(), frame);
    }

    /// Authentication has to actually reject. A flipped bit anywhere in the
    /// ciphertext, a wrong key, and a truncated frame must all fail closed.
    #[test]
    fn tampering_and_wrong_keys_are_rejected() {
        let key = derive("pairing-token");
        let other = derive("a-different-token");
        let frame = vec![1, 0, 0, 0, 7, 0, 0, 0, 2, 9, 9, 9, 9];
        let sealed = seal_frame(&key, &frame).unwrap();

        assert!(open_frame(&other, &sealed).is_none(), "wrong key must fail");

        for i in [0usize, 1, 13, sealed.len() - 1] {
            let mut bad = sealed.clone();
            bad[i] ^= 0x01;
            assert!(open_frame(&key, &bad).is_none(), "flipped bit at {i} must fail");
        }

        // Truncations, including shorter than the nonce.
        for cut in [1usize, 5, 13, sealed.len() - 1] {
            assert!(open_frame(&key, &sealed[..cut]).is_none(), "truncation to {cut} must fail");
        }
        assert!(open_frame(&key, &[]).is_none());
        // A plaintext frame handed to open_frame is not sealed, so it must not
        // be mistaken for one.
        assert!(open_frame(&key, &frame).is_none());
    }

    /// The sealed marker must not collide with any real frame kind, or an
    /// unsealed peer would try to parse a nonce as a header.
    #[test]
    fn the_sealed_kind_is_distinct_from_every_real_frame_kind() {
        assert_ne!(KIND_SEALED, crate::transfer::KIND_DATA);
        assert_ne!(KIND_SEALED, crate::transfer::KIND_THUMB);
        // Kind 3 is the mirror frame (see transfer.rs module docs).
        assert_ne!(KIND_SEALED, 3);
    }
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
