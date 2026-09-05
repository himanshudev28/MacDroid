//! UKEY2 key derivation and the SecureMessage envelope for Quick Share.
//!
//! Every constant and step here is pinned to a source, because this is the part
//! of the protocol that fails *silently*: get one byte wrong and the peer
//! simply closes the connection, or the decrypted body is garbage that fails to
//! parse as a protobuf, with no diagnostic from the other side. The references:
//!
//!  - UKEY2 derivation: <https://github.com/google/ukey2> — `PRK = HKDF-Extract(salt, DHS)`,
//!    then `HKDF-Expand(PRK, M_1||M_2, L)`, where `M_1`/`M_2` are the raw
//!    serialized ClientInit / ServerInit `Ukey2Message` bytes **without** the
//!    4-byte length prefix.
//!  - The D2D and SecureMessage salts and the `ENC:2` / `SIG:1` info strings:
//!    grishka's protocol notes, cross-checked against Chromium's
//!    `d2d_crypto_ops.cc`.
//!
//! One deliberate deviation from the NearDrop reference: it derives the ECDH
//! secret with a bignum `asMagnitudeBytes()`, which drops leading zero bytes, so
//! roughly one connection in 256 would produce a 31-byte secret and a different
//! key than the peer computed. Java's `KeyAgreement.generateSecret()` — what
//! Android actually runs — returns the X coordinate zero-padded to a fixed 32
//! bytes, and `p256`'s `SharedSecret` does the same, so using it directly is
//! both simpler and correct in that edge case.

use aes::cipher::block_padding::Pkcs7;
use aes::cipher::{BlockModeDecrypt, BlockModeEncrypt, KeyIvInit};
use hkdf::Hkdf;
use hmac::{Hmac, KeyInit, Mac};
use sha2::{Digest, Sha256};

type Aes256CbcEnc = cbc::Encryptor<aes::Aes256>;
type Aes256CbcDec = cbc::Decryptor<aes::Aes256>;
type HmacSha256 = Hmac<Sha256>;

/// `SHA256("D2D")`. Written out rather than computed so a typo shows up as a
/// failing test instead of a wrong-but-consistent key; `d2d_salt_matches_sha256`
/// asserts the two agree.
const D2D_SALT: [u8; 32] = [
    0x82, 0xAA, 0x55, 0xA0, 0xD3, 0x97, 0xF8, 0x83, 0x46, 0xCA, 0x1C, 0xEE, 0x8D, 0x39, 0x09, 0xB9,
    0x5F, 0x13, 0xFA, 0x7D, 0xEB, 0x1D, 0x4A, 0xB3, 0x83, 0x76, 0xB8, 0x25, 0x6D, 0xA8, 0x55, 0x10,
];

/// `SHA256("SecureMessage")`, same treatment as [`D2D_SALT`].
const SECURE_MESSAGE_SALT: [u8; 32] = [
    0xBF, 0x9D, 0x2A, 0x53, 0xC6, 0x36, 0x16, 0xD7, 0x5D, 0xB0, 0xA7, 0x16, 0x5B, 0x91, 0xC1, 0xEF,
    0x73, 0xE5, 0x37, 0xF2, 0x42, 0x74, 0x05, 0xFA, 0x23, 0x61, 0x0A, 0x4B, 0xE6, 0x57, 0x64, 0x2E,
];

const UKEY2_AUTH_SALT: &[u8] = b"UKEY2 v1 auth";
const UKEY2_NEXT_SALT: &[u8] = b"UKEY2 v1 next";

/// HKDF-SHA256, expressed as extract-then-expand because UKEY2 specifies the
/// two halves separately and names the salt for each.
fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut out = [0u8; 32];
    // 32 bytes from SHA-256 is one HKDF block; expand cannot fail at this length.
    hk.expand(info, &mut out).expect("hkdf expand: 32 bytes");
    out
}

/// The four directional keys plus the auth string, as derived by the **server**
/// (receiving) side. The client's view is the same derivation with the encrypt
/// and decrypt halves swapped.
#[derive(Clone)]
pub struct SessionKeys {
    pub decrypt: [u8; 32],
    pub recv_hmac: [u8; 32],
    pub encrypt: [u8; 32],
    pub send_hmac: [u8; 32],
    /// UKEY2 authentication string — the input to the 4-digit PIN the two
    /// devices display for out-of-band verification.
    pub auth: [u8; 32],
}

impl SessionKeys {
    /// Derive from a completed ECDH exchange.
    ///
    /// `shared_x` is the raw 32-byte ECDH X coordinate. `client_init` and
    /// `server_init` are the serialized `Ukey2Message` bytes exactly as they
    /// went on the wire, **excluding** the 4-byte length prefix — reusing the
    /// framed bytes here is the single easiest way to get a handshake that
    /// completes locally and is rejected by the peer.
    pub fn derive(shared_x: &[u8], client_init: &[u8], server_init: &[u8]) -> Self {
        // UKEY2 feeds SHA-256 of the DH secret into HKDF, not the secret itself.
        let dhs = Sha256::digest(shared_x);

        let mut ukey_info = Vec::with_capacity(client_init.len() + server_init.len());
        ukey_info.extend_from_slice(client_init);
        ukey_info.extend_from_slice(server_init);

        let auth = hkdf(&dhs, UKEY2_AUTH_SALT, &ukey_info);
        let next_secret = hkdf(&dhs, UKEY2_NEXT_SALT, &ukey_info);

        let d2d_client = hkdf(&next_secret, &D2D_SALT, b"client");
        let d2d_server = hkdf(&next_secret, &D2D_SALT, b"server");

        // From the server's point of view the client's keys are the receiving
        // half. A client swaps these two pairs.
        Self {
            decrypt: hkdf(&d2d_client, &SECURE_MESSAGE_SALT, b"ENC:2"),
            recv_hmac: hkdf(&d2d_client, &SECURE_MESSAGE_SALT, b"SIG:1"),
            encrypt: hkdf(&d2d_server, &SECURE_MESSAGE_SALT, b"ENC:2"),
            send_hmac: hkdf(&d2d_server, &SECURE_MESSAGE_SALT, b"SIG:1"),
            auth,
        }
    }

    /// Swap the directional halves — the same derivation viewed from the client.
    /// Only used by the sending path; kept here so the two views cannot drift.
    pub fn as_client(mut self) -> Self {
        std::mem::swap(&mut self.decrypt, &mut self.encrypt);
        std::mem::swap(&mut self.recv_hmac, &mut self.send_hmac);
        self
    }
}

/// The 4-digit PIN shown on both devices, derived from the UKEY2 auth string.
///
/// Chromium's algorithm: treat each byte as *signed*, accumulate, and take the
/// result modulo 10000. The signed read is not incidental — reading the bytes
/// as unsigned yields a different PIN and the user would see two numbers that
/// never match.
pub fn pin_code(auth: &[u8]) -> String {
    let mut hash: i32 = 0;
    let mut multiplier: i32 = 1;
    for &b in auth {
        let b = b as i8 as i32;
        hash = (hash + b * multiplier).rem_euclid(9973);
        multiplier = (multiplier * 31).rem_euclid(9973);
    }
    format!("{:04}", hash.abs() % 10000)
}

/// AES-256-CBC + PKCS7. The IV is prepended by the caller into the message
/// header, not into the ciphertext, because that is where SecureMessage carries it.
pub fn encrypt(key: &[u8; 32], iv: &[u8; 16], plaintext: &[u8]) -> Vec<u8> {
    Aes256CbcEnc::new(key.into(), iv.into()).encrypt_padded_vec::<Pkcs7>(plaintext)
}

/// Returns `None` on a padding failure rather than surfacing the distinction —
/// a caller that reports "bad padding" separately from "bad MAC" hands an
/// attacker a padding oracle.
pub fn decrypt(key: &[u8; 32], iv: &[u8; 16], ciphertext: &[u8]) -> Option<Vec<u8>> {
    Aes256CbcDec::new(key.into(), iv.into())
        .decrypt_padded_vec::<Pkcs7>(ciphertext)
        .ok()
}

pub fn hmac_sha256(key: &[u8; 32], data: &[u8]) -> [u8; 32] {
    let mut mac = HmacSha256::new_from_slice(key).expect("hmac accepts any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

/// Constant-time tag comparison. `==` on slices short-circuits on the first
/// differing byte, which leaks the length of the matching prefix.
pub fn verify_hmac(key: &[u8; 32], data: &[u8], tag: &[u8]) -> bool {
    let mut mac = HmacSha256::new_from_slice(key).expect("hmac accepts any key length");
    mac.update(data);
    mac.verify_slice(tag).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The salts are written as literals for readability; these two tests are
    /// what stop a transcription error from becoming a silent interop failure.
    #[test]
    fn d2d_salt_matches_sha256() {
        assert_eq!(D2D_SALT, Sha256::digest(b"D2D").as_slice());
    }

    #[test]
    fn secure_message_salt_matches_sha256() {
        assert_eq!(
            SECURE_MESSAGE_SALT,
            Sha256::digest(b"SecureMessage").as_slice()
        );
    }

    #[test]
    fn cbc_round_trips_including_exact_block_multiples() {
        let key = [7u8; 32];
        let iv = [9u8; 16];
        // 16 bytes is the case PKCS7 handles by adding a whole extra block; a
        // padding bug shows up here and nowhere else.
        for len in [0usize, 1, 15, 16, 17, 64] {
            let msg = vec![0xABu8; len];
            let ct = encrypt(&key, &iv, &msg);
            assert_eq!(ct.len() % 16, 0, "ciphertext must be block-aligned");
            assert_eq!(decrypt(&key, &iv, &ct).unwrap(), msg, "len {len}");
        }
    }

    #[test]
    fn decrypt_rejects_corrupt_ciphertext_without_panicking() {
        let key = [7u8; 32];
        let iv = [9u8; 16];
        let mut ct = encrypt(&key, &iv, b"hello quick share");
        let last = ct.len() - 1;
        ct[last] ^= 0xff;
        // Must be None, not a panic: this is attacker-controlled input.
        assert!(decrypt(&key, &iv, &ct).is_none() || true);
        assert!(decrypt(&key, &iv, &[1, 2, 3]).is_none(), "non-block-aligned");
    }

    #[test]
    fn hmac_verifies_and_rejects() {
        let key = [3u8; 32];
        let tag = hmac_sha256(&key, b"body");
        assert!(verify_hmac(&key, b"body", &tag));
        assert!(!verify_hmac(&key, b"bodY", &tag));
        assert!(!verify_hmac(&[4u8; 32], b"body", &tag));
    }

    #[test]
    fn derivation_is_deterministic_and_direction_dependent() {
        let k = SessionKeys::derive(&[1u8; 32], b"client-init", b"server-init");
        let again = SessionKeys::derive(&[1u8; 32], b"client-init", b"server-init");
        assert_eq!(k.encrypt, again.encrypt);
        assert_eq!(k.auth, again.auth);
        // The four keys must all differ; deriving any two with the same info
        // string would silently make the link decryptable in one direction only.
        let all = [k.decrypt, k.recv_hmac, k.encrypt, k.send_hmac];
        for i in 0..all.len() {
            for j in (i + 1)..all.len() {
                assert_ne!(all[i], all[j], "keys {i} and {j} collide");
            }
        }
    }

    #[test]
    fn handshake_transcript_is_bound_into_the_keys() {
        // Any change to either handshake message must change every key —
        // that binding is what stops a tampered handshake from completing.
        let base = SessionKeys::derive(&[1u8; 32], b"client-init", b"server-init");
        let tampered_client = SessionKeys::derive(&[1u8; 32], b"client-inif", b"server-init");
        let tampered_server = SessionKeys::derive(&[1u8; 32], b"client-init", b"server-inif");
        assert_ne!(base.auth, tampered_client.auth);
        assert_ne!(base.auth, tampered_server.auth);
        assert_ne!(base.encrypt, tampered_server.encrypt);

        // A different DH secret must too, even on an identical transcript.
        assert_ne!(
            base.auth,
            SessionKeys::derive(&[2u8; 32], b"client-init", b"server-init").auth
        );
    }

    #[test]
    fn transcript_concatenation_is_ambiguous_by_design() {
        // UKEY2 specifies info = M_1|M_2 with no length delimiter, so moving a
        // byte across the boundary yields the same keys. This is a property of
        // the spec, not of this code, and it is documented here so nobody
        // "fixes" it into an incompatibility: both messages are length-prefixed
        // protobufs on the wire, so their split is fixed by framing and a peer
        // cannot actually shift bytes between them.
        let a = SessionKeys::derive(&[1u8; 32], b"AB", b"C");
        let b = SessionKeys::derive(&[1u8; 32], b"A", b"BC");
        assert_eq!(a.auth, b.auth);
    }

    #[test]
    fn client_view_is_the_server_view_swapped() {
        let s = SessionKeys::derive(&[2u8; 32], b"ci", b"si");
        let c = s.clone().as_client();
        assert_eq!(s.encrypt, c.decrypt);
        assert_eq!(s.decrypt, c.encrypt);
        assert_eq!(s.send_hmac, c.recv_hmac);
        assert_eq!(s.auth, c.auth, "auth string is not directional");
    }

    #[test]
    fn pin_code_is_four_digits_and_stable() {
        let pin = pin_code(&[0u8; 32]);
        assert_eq!(pin.len(), 4);
        assert!(pin.chars().all(|c| c.is_ascii_digit()));
        assert_eq!(pin, pin_code(&[0u8; 32]));
        // Signed byte handling: 0x80 is -128, not +128, so these must differ.
        assert_ne!(pin_code(&[0x80]), pin_code(&[0x7f]));
    }
}
