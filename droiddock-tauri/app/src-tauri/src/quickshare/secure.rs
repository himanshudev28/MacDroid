//! The SecureMessage envelope: everything after the UKEY2 handshake travels
//! inside one of these.
//!
//! Shape, outermost first:
//! `SecureMessage{ header_and_body, signature }` where `header_and_body` is a
//! serialized `HeaderAndBody{ header{scheme, iv, public_metadata}, body }` and
//! `body` is AES-256-CBC over a serialized
//! `DeviceToDeviceMessage{ sequence_number, message }`. `message` is the actual
//! `OfflineFrame`.
//!
//! The signature is an HMAC over the *serialized* `header_and_body` bytes, so
//! on receive it must be checked against the bytes exactly as they arrived —
//! decoding and re-encoding a protobuf is not guaranteed to reproduce them, and
//! a re-serialized check would reject valid messages while quietly accepting
//! whatever the re-encoding normalised away.

use prost::Message;

use super::crypto::{self, SessionKeys};
use super::proto::securegcm::{DeviceToDeviceMessage, GcmMetadata, Type as GcmType};
use super::proto::securemessage::{EncScheme, Header, HeaderAndBody, SecureMessage, SigScheme};

#[derive(Debug, PartialEq, Eq)]
pub enum ChannelError {
    /// Not a well-formed SecureMessage / HeaderAndBody / D2D message.
    Malformed(&'static str),
    /// HMAC did not verify. Deliberately indistinguishable from a padding
    /// failure to the caller, so neither becomes an oracle.
    BadSignature,
    /// The peer's sequence number was not the next one expected — a replayed,
    /// reordered or dropped frame.
    Sequence { expected: i32, got: i32 },
}

impl std::fmt::Display for ChannelError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Malformed(what) => write!(f, "malformed secure message ({what})"),
            Self::BadSignature => write!(f, "secure message failed authentication"),
            Self::Sequence { expected, got } => {
                write!(f, "out-of-order secure message: expected {expected}, got {got}")
            }
        }
    }
}

/// An established, authenticated channel. Owns the two independent sequence
/// counters — ours and the peer's — which is why sealing and opening both take
/// `&mut self`.
pub struct SecureChannel {
    keys: SessionKeys,
    send_seq: i32,
    recv_seq: i32,
}

impl SecureChannel {
    pub fn new(keys: SessionKeys) -> Self {
        Self { keys, send_seq: 0, recv_seq: 0 }
    }

    /// The UKEY2 auth string, for deriving the PIN both devices display.
    pub fn auth(&self) -> &[u8; 32] {
        &self.keys.auth
    }

    /// Wrap a serialized `OfflineFrame` for sending.
    pub fn seal(&mut self, frame: &[u8]) -> Vec<u8> {
        self.send_seq += 1;
        let d2d = DeviceToDeviceMessage {
            sequence_number: Some(self.send_seq),
            message: Some(frame.to_vec()),
        };

        let mut iv = [0u8; 16];
        getrandom::fill(&mut iv).expect("system randomness");
        let body = crypto::encrypt(&self.keys.encrypt, &iv, &d2d.encode_to_vec());

        let metadata = GcmMetadata {
            r#type: GcmType::DeviceToDeviceMessage as i32,
            version: Some(1),
        };
        let hb = HeaderAndBody {
            header: Header {
                signature_scheme: SigScheme::HmacSha256 as i32,
                encryption_scheme: EncScheme::Aes256Cbc as i32,
                iv: Some(iv.to_vec()),
                public_metadata: Some(metadata.encode_to_vec()),
                ..Default::default()
            },
            body,
        };

        let header_and_body = hb.encode_to_vec();
        let signature = crypto::hmac_sha256(&self.keys.send_hmac, &header_and_body).to_vec();
        SecureMessage { header_and_body, signature }.encode_to_vec()
    }

    /// Unwrap a received SecureMessage, returning the inner `OfflineFrame` bytes.
    pub fn open(&mut self, raw: &[u8]) -> Result<Vec<u8>, ChannelError> {
        let msg = SecureMessage::decode(raw).map_err(|_| ChannelError::Malformed("SecureMessage"))?;

        // Authenticate before decrypting. Doing it the other way round runs the
        // CBC unpadding over attacker-chosen ciphertext, which is the classic
        // padding-oracle setup.
        if !crypto::verify_hmac(&self.keys.recv_hmac, &msg.header_and_body, &msg.signature) {
            return Err(ChannelError::BadSignature);
        }

        let hb = HeaderAndBody::decode(msg.header_and_body.as_slice())
            .map_err(|_| ChannelError::Malformed("HeaderAndBody"))?;
        if hb.header.encryption_scheme != EncScheme::Aes256Cbc as i32 {
            return Err(ChannelError::Malformed("encryption scheme"));
        }
        if hb.header.signature_scheme != SigScheme::HmacSha256 as i32 {
            return Err(ChannelError::Malformed("signature scheme"));
        }
        let iv: [u8; 16] = hb
            .header
            .iv
            .as_deref()
            .and_then(|v| v.try_into().ok())
            .ok_or(ChannelError::Malformed("iv"))?;

        // Same error as a bad MAC on purpose: distinguishing "padding was wrong"
        // from "signature was wrong" is exactly what a padding oracle needs.
        let plain = crypto::decrypt(&self.keys.decrypt, &iv, &hb.body)
            .ok_or(ChannelError::BadSignature)?;

        let d2d = DeviceToDeviceMessage::decode(plain.as_slice())
            .map_err(|_| ChannelError::Malformed("DeviceToDeviceMessage"))?;
        let seq = d2d.sequence_number.ok_or(ChannelError::Malformed("sequence_number"))?;

        self.recv_seq += 1;
        if seq != self.recv_seq {
            // Leave the counter advanced: the channel is finished either way,
            // and the caller drops the connection on this error.
            return Err(ChannelError::Sequence { expected: self.recv_seq, got: seq });
        }
        d2d.message.ok_or(ChannelError::Malformed("message"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A pair of channels sharing one derivation, wired as server and client.
    fn pair() -> (SecureChannel, SecureChannel) {
        let server_keys = SessionKeys::derive(&[42u8; 32], b"client-init", b"server-init");
        let client_keys = server_keys.clone().as_client();
        (SecureChannel::new(server_keys), SecureChannel::new(client_keys))
    }

    #[test]
    fn round_trips_between_the_two_directions() {
        let (mut server, mut client) = pair();
        let sealed = server.seal(b"offline-frame-bytes");
        assert_eq!(client.open(&sealed).unwrap(), b"offline-frame-bytes");

        let back = client.seal(b"reply");
        assert_eq!(server.open(&back).unwrap(), b"reply");
    }

    #[test]
    fn sequence_numbers_advance_independently_per_direction() {
        let (mut server, mut client) = pair();
        for i in 0..5 {
            let msg = format!("s{i}");
            assert_eq!(client.open(&server.seal(msg.as_bytes())).unwrap(), msg.as_bytes());
        }
        // The client's own send counter is untouched by all that receiving.
        for i in 0..5 {
            let msg = format!("c{i}");
            assert_eq!(server.open(&client.seal(msg.as_bytes())).unwrap(), msg.as_bytes());
        }
    }

    #[test]
    fn replayed_frame_is_rejected() {
        let (mut server, mut client) = pair();
        let first = server.seal(b"one");
        let _ = server.seal(b"two");
        assert!(client.open(&first).is_ok());
        // Re-delivering the same bytes: authentic, but the sequence is stale.
        assert!(matches!(client.open(&first), Err(ChannelError::Sequence { .. })));
    }

    #[test]
    fn reordered_frame_is_rejected() {
        let (mut server, mut client) = pair();
        let first = server.seal(b"one");
        let second = server.seal(b"two");
        // Deliver out of order.
        assert_eq!(
            client.open(&second),
            Err(ChannelError::Sequence { expected: 1, got: 2 })
        );
        let _ = first;
    }

    #[test]
    fn tampering_is_caught_and_reported_uniformly() {
        let (mut server, mut client) = pair();
        let mut sealed = server.seal(b"payload");
        let n = sealed.len();
        sealed[n - 1] ^= 0x01; // corrupt the signature
        assert_eq!(client.open(&sealed), Err(ChannelError::BadSignature));
    }

    #[test]
    fn a_wrong_key_cannot_open_the_channel() {
        let (mut server, _) = pair();
        let other = SessionKeys::derive(&[7u8; 32], b"x", b"y").as_client();
        let mut stranger = SecureChannel::new(other);
        assert_eq!(stranger.open(&server.seal(b"secret")), Err(ChannelError::BadSignature));
    }

    #[test]
    fn garbage_input_never_panics() {
        let (_, mut client) = pair();
        for junk in [&b""[..], &[0xff; 8][..], &[0x08, 0x96, 0x01][..]] {
            let _ = client.open(junk);
        }
    }

    #[test]
    fn body_is_actually_encrypted() {
        // A regression guard: if the cipher were ever bypassed, the plaintext
        // would be sitting in the frame and nothing else in the suite would notice.
        let (mut server, _) = pair();
        let sealed = server.seal(b"TOP-SECRET-MARKER");
        assert!(
            !sealed.windows(17).any(|w| w == b"TOP-SECRET-MARKER"),
            "plaintext leaked into the sealed frame"
        );
    }
}
