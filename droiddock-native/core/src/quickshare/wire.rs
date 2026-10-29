//! Framing and discovery encoding for Quick Share.
//!
//! Two things live here: the 4-byte length prefix every protobuf on the socket
//! carries, and the peculiar little binary blobs that go into mDNS so an
//! Android share sheet will list this Mac.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// The Nearby Sharing service. The hex is the first 3 bytes of
/// `SHA256("NearbySharing")` — `fc9f5ed42c8a…` — which is also the service ID
/// embedded in the instance name below.
pub const SERVICE_TYPE: &str = "_FC9F5ED42C8A._tcp.local.";

/// Ceiling on a single framed message.
///
/// The length prefix is attacker-controlled: without a cap, one peer sending
/// `0xFFFFFFFF` makes us try to allocate 4 GiB before reading a byte of it.
/// File data arrives in payload chunks of ~512 KiB, so nothing legitimate comes
/// close to this.
pub const MAX_FRAME: usize = 8 * 1024 * 1024;

/// Device type as Android reads it, purely to pick an icon in the share sheet.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceType {
    Unknown = 0,
    Phone = 1,
    Tablet = 2,
    Laptop = 3,
}

/// Four random alphanumeric characters identifying this endpoint for the life
/// of one advertisement. Android logs it verbatim, which makes it the fastest
/// way to correlate our side with logcat when something goes wrong.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EndpointId([u8; 4]);

impl EndpointId {
    pub fn random() -> Self {
        const ALPHABET: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        let mut raw = [0u8; 4];
        let mut bytes = [0u8; 4];
        getrandom::fill(&mut bytes).expect("system randomness");
        for (out, b) in raw.iter_mut().zip(bytes) {
            // Modulo bias across 62 of 256 values is irrelevant here: this is a
            // collision-avoidance nonce on a LAN, not a secret.
            *out = ALPHABET[(b as usize) % ALPHABET.len()];
        }
        Self(raw)
    }

    pub fn as_bytes(&self) -> &[u8; 4] {
        &self.0
    }

    pub fn as_str(&self) -> &str {
        // Every byte came from an ASCII alphabet.
        std::str::from_utf8(&self.0).expect("endpoint id is ASCII")
    }
}

/// The mDNS *instance name*: 10 bytes in URL-safe base64, unpadded.
///
/// Layout is fixed by the protocol: a `0x23` "PCP" marker, the 4-byte endpoint
/// id, the 3-byte service-ID hash, then two zero bytes whose purpose nobody has
/// identified. Padding must be stripped — Android matches the encoded string.
pub fn mdns_instance_name(id: &EndpointId) -> String {
    let mut raw = Vec::with_capacity(10);
    raw.push(0x23);
    raw.extend_from_slice(id.as_bytes());
    raw.extend_from_slice(&[0xFC, 0x9F, 0x5E]);
    raw.extend_from_slice(&[0, 0]);
    URL_SAFE_NO_PAD.encode(raw)
}

/// The "endpoint info" blob — the body of the `n` TXT record when advertising,
/// and the `endpoint_info` field of a ConnectionRequest when connecting.
///
/// `[flags][16 bytes][name len][name]`, where flags is
/// `version:3 | visibility:1 | device_type:3 | reserved:1`. We advertise
/// version 0 and visible (0), so the byte reduces to `device_type << 1`.
///
/// The 16 bytes identify the device to Google's servers and are meaningless
/// without them, so random is both correct and the most private option.
pub fn endpoint_info(device_type: DeviceType, name: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(1 + 16 + 1 + name.len());
    out.push((device_type as u8) << 1);

    let mut salt = [0u8; 16];
    getrandom::fill(&mut salt).expect("system randomness");
    out.extend_from_slice(&salt);

    // The length is one byte, so the name has to fit in 255 — and it must be
    // truncated on a char boundary or the peer gets invalid UTF-8.
    let mut name_bytes = name.as_bytes();
    if name_bytes.len() > 255 {
        let mut end = 255;
        while end > 0 && !name.is_char_boundary(end) {
            end -= 1;
        }
        name_bytes = &name.as_bytes()[..end];
    }
    out.push(name_bytes.len() as u8);
    out.extend_from_slice(name_bytes);
    out
}

/// What we can learn about the peer from its endpoint info: the name it shows
/// the user, and the icon class it claims.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PeerInfo {
    pub name: String,
    pub device_type: DeviceType,
}

/// Parse a peer's endpoint info. Returns `None` rather than a partial result:
/// this is remote input, and a name we cannot read is not a name worth showing
/// in a prompt asking the user to accept files.
pub fn parse_endpoint_info(raw: &[u8]) -> Option<PeerInfo> {
    if raw.len() < 18 {
        return None;
    }
    let device_type = match (raw[0] & 0b0000_1110) >> 1 {
        1 => DeviceType::Phone,
        2 => DeviceType::Tablet,
        3 => DeviceType::Laptop,
        _ => DeviceType::Unknown,
    };
    let name_len = raw[17] as usize;
    let name = raw.get(18..18 + name_len)?;
    Some(PeerInfo {
        name: String::from_utf8(name.to_vec()).ok()?,
        device_type,
    })
}

/// Read one length-prefixed message.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Vec<u8>> {
    let mut len_buf = [0u8; 4];
    r.read_exact(&mut len_buf).await?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > MAX_FRAME {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("frame of {len} bytes exceeds the {MAX_FRAME} cap"),
        ));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(buf)
}

/// Write one length-prefixed message.
///
/// A single `write_all` of prefix+body rather than two: two writes can be split
/// across TCP segments with a delay between them, and a peer that reads the
/// length then blocks is one Nagle interaction away from a stall.
pub async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, body: &[u8]) -> std::io::Result<()> {
    let mut out = Vec::with_capacity(4 + body.len());
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    out.extend_from_slice(body);
    w.write_all(&out).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn instance_name_has_the_fixed_layout() {
        let id = EndpointId([b'a', b'B', b'3', b'z']);
        let encoded = mdns_instance_name(&id);
        let raw = URL_SAFE_NO_PAD.decode(&encoded).unwrap();
        assert_eq!(raw.len(), 10);
        assert_eq!(raw[0], 0x23, "PCP marker");
        assert_eq!(&raw[1..5], b"aB3z", "endpoint id");
        assert_eq!(&raw[5..8], &[0xFC, 0x9F, 0x5E], "service id hash");
        assert_eq!(&raw[8..], &[0, 0]);
    }

    #[test]
    fn instance_name_is_unpadded_and_url_safe() {
        // 10 bytes would normally base64 to 16 chars with two '=' — Android
        // matches the encoded string, so padding must not appear.
        let name = mdns_instance_name(&EndpointId::random());
        assert!(!name.contains('='), "padding must be stripped: {name}");
        assert!(!name.contains('+') && !name.contains('/'), "must be URL-safe");
        assert_eq!(name.len(), 14);
    }

    #[test]
    fn service_id_is_the_prefix_of_sha256_nearbysharing() {
        use sha2::{Digest, Sha256};
        let h = Sha256::digest(b"NearbySharing");
        assert_eq!(&h[..3], &[0xFC, 0x9F, 0x5E], "service id derivation");
    }

    #[test]
    fn endpoint_id_is_four_alphanumerics() {
        for _ in 0..64 {
            let id = EndpointId::random();
            assert_eq!(id.as_str().len(), 4);
            assert!(id.as_str().chars().all(|c| c.is_ascii_alphanumeric()));
        }
    }

    #[test]
    fn endpoint_info_round_trips() {
        let raw = endpoint_info(DeviceType::Laptop, "Himanshu's MacBook Air");
        // Visible + version 0 + laptop => 3 << 1.
        assert_eq!(raw[0], 6);
        let parsed = parse_endpoint_info(&raw).unwrap();
        assert_eq!(parsed.name, "Himanshu's MacBook Air");
        assert_eq!(parsed.device_type, DeviceType::Laptop);
    }

    #[test]
    fn endpoint_info_truncates_long_names_on_a_char_boundary() {
        // A multi-byte char straddling byte 255 would otherwise be cut in half
        // and the peer would see invalid UTF-8.
        let name = "é".repeat(200); // 400 bytes
        let raw = endpoint_info(DeviceType::Laptop, &name);
        let parsed = parse_endpoint_info(&raw).expect("must still parse");
        assert!(parsed.name.len() <= 255);
        assert!(name.starts_with(&parsed.name));
    }

    #[test]
    fn parse_endpoint_info_rejects_hostile_input() {
        assert!(parse_endpoint_info(&[]).is_none());
        assert!(parse_endpoint_info(&[0u8; 17]).is_none(), "too short");
        // Declares a 200-byte name but supplies none: must not panic or read past.
        let mut lying = vec![6u8];
        lying.extend_from_slice(&[0u8; 16]);
        lying.push(200);
        assert!(parse_endpoint_info(&lying).is_none());
        // Invalid UTF-8 in the name.
        let mut bad_utf8 = vec![6u8];
        bad_utf8.extend_from_slice(&[0u8; 16]);
        bad_utf8.push(2);
        bad_utf8.extend_from_slice(&[0xff, 0xfe]);
        assert!(parse_endpoint_info(&bad_utf8).is_none());
    }

    #[tokio::test]
    async fn frames_round_trip() {
        let mut buf: Vec<u8> = Vec::new();
        write_frame(&mut buf, b"hello").await.unwrap();
        write_frame(&mut buf, b"").await.unwrap();
        assert_eq!(&buf[..4], &5u32.to_be_bytes(), "big-endian length prefix");

        let mut cursor = std::io::Cursor::new(buf);
        assert_eq!(read_frame(&mut cursor).await.unwrap(), b"hello");
        assert_eq!(read_frame(&mut cursor).await.unwrap(), b"");
    }

    #[tokio::test]
    async fn oversized_length_is_refused_before_allocating() {
        let mut framed = Vec::new();
        framed.extend_from_slice(&u32::MAX.to_be_bytes());
        let mut cursor = std::io::Cursor::new(framed);
        let err = read_frame(&mut cursor).await.unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    }
}
