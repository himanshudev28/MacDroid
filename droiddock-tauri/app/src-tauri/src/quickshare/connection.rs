//! One inbound Quick Share connection, start to finish.
//!
//! Written as a straight-line async function rather than an explicit state
//! enum: the protocol is strictly sequential, and `await`ing each step in order
//! makes the sequence readable against the spec instead of scattered across
//! callbacks.
//!
//! Sequence (we are always the server/receiver):
//!   1. ConnectionRequest            (plaintext, tells us who is calling)
//!   2. UKEY2 ClientInit             — remember the raw bytes, they key the session
//!   3. UKEY2 ServerInit             — our P-256 public key
//!   4. UKEY2 ClientFinish           — verify its commitment, then ECDH
//!   5. ConnectionResponse, both ways (still plaintext)
//!   --- everything below is sealed ---
//!   6. PairedKeyEncryption + PairedKeyResult, both ways (content is meaningless)
//!   7. Introduction                 — the file list; we show the user a PIN
//!   8. Response ACCEPT/REJECT
//!   9. Payload chunks               — the files

use p256::elliptic_curve::sec1::{FromSec1Point, ToSec1Point};
use prost::Message;
use sha2::{Digest, Sha512};
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{mpsc, oneshot};

use super::crypto::{pin_code, SessionKeys};
use super::payload::{Completed, Reassembler};
use super::proto::location::nearby::connections as conn;
use super::proto::securegcm as gcm;
use super::proto::securemessage as sm;
use super::proto::sharing::nearby as sharing;
use super::secure::SecureChannel;
use super::wire;

/// Android drops a connection that goes quiet, and TCP's own acknowledgements
/// do not count — the keep-alive has to be visible at this layer.
const KEEP_ALIVE: Duration = Duration::from_secs(10);

/// How long the user has to accept before we give up and close.
const CONSENT_TIMEOUT: Duration = Duration::from_secs(60);

/// Guard against a peer that completes the handshake and then says nothing.
const IDLE_TIMEOUT: Duration = Duration::from_secs(120);

#[derive(Clone, Debug, serde::Serialize)]
pub struct IncomingFile {
    pub name: String,
    pub size: i64,
    pub mime: String,
}

/// What the connection reports outward. The UI turns `Introduced` into a
/// prompt and answers through the enclosed channel.
pub enum Event {
    Introduced {
        peer: String,
        pin: String,
        files: Vec<IncomingFile>,
        reply: oneshot::Sender<bool>,
    },
    Progress {
        received: i64,
        total: i64,
    },
    Done {
        paths: Vec<PathBuf>,
    },
    Rejected,
    Failed {
        error: String,
    },
}

type Res<T> = Result<T, String>;

/// Encode a coordinate the way Java's `BigInteger.toByteArray()` would.
///
/// This is not cosmetic. The peer parses these as signed big integers, so a
/// 32-byte value whose top bit is set reads as *negative* and the ECDH result
/// silently differs from ours. Minimal two's-complement: strip leading zeroes,
/// then re-add one if the high bit would otherwise imply a negative number.
fn to_signed_be(v: &[u8]) -> Vec<u8> {
    let mut s = v;
    while s.len() > 1 && s[0] == 0 {
        s = &s[1..];
    }
    if s.first().is_some_and(|b| b & 0x80 != 0) {
        let mut out = Vec::with_capacity(s.len() + 1);
        out.push(0);
        out.extend_from_slice(s);
        out
    } else {
        s.to_vec()
    }
}

/// Inverse of [`to_signed_be`], to a fixed 32 bytes: drop a sign byte if present,
/// left-pad if the peer trimmed leading zeroes.
fn from_signed_be(v: &[u8]) -> Option<[u8; 32]> {
    if v.is_empty() || v.len() > 33 {
        return None;
    }
    let mut out = [0u8; 32];
    if v.len() >= 32 {
        out.copy_from_slice(&v[v.len() - 32..]);
    } else {
        out[32 - v.len()..].copy_from_slice(v);
    }
    Some(out)
}

fn random_p256() -> p256::SecretKey {
    loop {
        let mut b = [0u8; 32];
        getrandom::fill(&mut b).expect("system randomness");
        // Rejection sampling: a uniform 32-byte string is occasionally not a
        // valid scalar (>= n, or zero).
        if let Ok(sk) = p256::SecretKey::from_slice(&b) {
            return sk;
        }
    }
}

fn offline(v1: conn::V1Frame) -> Vec<u8> {
    conn::OfflineFrame {
        version: Some(conn::offline_frame::Version::V1 as i32),
        v1: Some(v1),
    }
    .encode_to_vec()
}

/// Serve one inbound connection to completion.
pub async fn serve<S>(
    mut stream: S,
    dest_dir: PathBuf,
    events: mpsc::UnboundedSender<Event>,
) -> Res<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut reassembler = Reassembler::new(dest_dir);
    let result = run(&mut stream, &mut reassembler, &events).await;
    if result.is_err() {
        // A half-written file that looks complete is worse than none.
        reassembler.abort();
    }
    if let Err(e) = &result {
        let _ = events.send(Event::Failed { error: e.clone() });
    }
    result
}

async fn run<S>(
    stream: &mut S,
    reassembler: &mut Reassembler,
    events: &mpsc::UnboundedSender<Event>,
) -> Res<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // ── 1. Connection request ────────────────────────────────────────────
    let raw = read(stream).await?;
    let frame = conn::OfflineFrame::decode(raw.as_slice()).map_err(|_| "bad connection request")?;
    let v1 = frame.v1.ok_or("connection request had no v1")?;
    let req = v1.connection_request.ok_or("not a connection request")?;
    let peer_name = req
        .endpoint_info
        .as_deref()
        .and_then(wire::parse_endpoint_info)
        .map(|p| p.name)
        .unwrap_or_else(|| "Unknown device".to_string());

    // ── 2. UKEY2 ClientInit ──────────────────────────────────────────────
    // These raw bytes are an input to the key derivation, so they must be kept
    // exactly as received — re-encoding the parsed message would not reproduce
    // them byte for byte.
    let client_init_raw = read(stream).await?;
    let msg = gcm::Ukey2Message::decode(client_init_raw.as_slice()).map_err(|_| "bad ukey2 client init")?;
    if msg.message_type != Some(gcm::ukey2_message::Type::ClientInit as i32) {
        return Err("expected UKEY2 client init".into());
    }
    let client_init = gcm::Ukey2ClientInit::decode(
        msg.message_data.as_deref().ok_or("client init had no data")?,
    )
    .map_err(|_| "bad ukey2 client init body")?;

    // We only speak P-256; a peer offering only Curve25519 is not an error we
    // can talk our way out of.
    let commitment = client_init
        .cipher_commitments
        .iter()
        .find(|c| c.handshake_cipher == Some(gcm::Ukey2HandshakeCipher::P256Sha512 as i32))
        .and_then(|c| c.commitment.clone())
        .ok_or("peer did not offer P256_SHA512")?;

    // ── 3. UKEY2 ServerInit ──────────────────────────────────────────────
    let secret = random_p256();
    let point = secret.public_key().to_sec1_point(false);
    let public_key = sm::GenericPublicKey {
        r#type: sm::PublicKeyType::EcP256 as i32,
        ec_p256_public_key: Some(sm::EcP256PublicKey {
            x: to_signed_be(point.x().ok_or("no x coordinate")?.as_slice()),
            y: to_signed_be(point.y().ok_or("no y coordinate")?.as_slice()),
        }),
        ..Default::default()
    };
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).expect("system randomness");
    let server_init = gcm::Ukey2ServerInit {
        version: Some(1),
        random: Some(random.to_vec()),
        handshake_cipher: Some(gcm::Ukey2HandshakeCipher::P256Sha512 as i32),
        public_key: Some(public_key.encode_to_vec()),
    };
    let server_init_raw = gcm::Ukey2Message {
        message_type: Some(gcm::ukey2_message::Type::ServerInit as i32),
        message_data: Some(server_init.encode_to_vec()),
    }
    .encode_to_vec();
    write(stream, &server_init_raw).await?;

    // ── 4. UKEY2 ClientFinish ────────────────────────────────────────────
    let client_finish_raw = read(stream).await?;
    // The commitment binds the key the client is about to reveal to the one it
    // committed to before seeing ours. Skipping this check would let a
    // man-in-the-middle pick its key after seeing our ServerInit.
    if Sha512::digest(&client_finish_raw).as_slice() != commitment.as_slice() {
        return Err("UKEY2 commitment mismatch".into());
    }
    let msg = gcm::Ukey2Message::decode(client_finish_raw.as_slice())
        .map_err(|_| "bad ukey2 client finish")?;
    if msg.message_type != Some(gcm::ukey2_message::Type::ClientFinish as i32) {
        return Err("expected UKEY2 client finish".into());
    }
    let finished = gcm::Ukey2ClientFinished::decode(
        msg.message_data.as_deref().ok_or("client finish had no data")?,
    )
    .map_err(|_| "bad ukey2 client finish body")?;
    let peer_key = sm::GenericPublicKey::decode(
        finished.public_key.as_deref().ok_or("client finish had no key")?,
    )
    .map_err(|_| "bad peer public key")?;
    let ec = peer_key.ec_p256_public_key.ok_or("peer key was not P-256")?;
    let px = from_signed_be(&ec.x).ok_or("bad peer x")?;
    let py = from_signed_be(&ec.y).ok_or("bad peer y")?;
    let encoded = p256::Sec1Point::from_affine_coordinates(&px.into(), &py.into(), false);
    let peer_public = Option::<p256::PublicKey>::from(p256::PublicKey::from_sec1_point(&encoded))
        .ok_or("peer public key is not on the curve")?;

    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), peer_public.as_affine());
    let keys = SessionKeys::derive(
        shared.raw_secret_bytes().as_slice(),
        &client_init_raw,
        &server_init_raw,
    );
    let pin = pin_code(&keys.auth);
    let mut channel = SecureChannel::new(keys);

    // ── 5. Connection response, both ways, still in the clear ────────────
    write(
        stream,
        &offline(conn::V1Frame {
            r#type: Some(conn::v1_frame::FrameType::ConnectionResponse as i32),
            connection_response: Some(conn::ConnectionResponseFrame {
                // `response`, not the deprecated `status`: newer peers read
                // this field and older ones tolerate its absence.
                response: Some(conn::connection_response_frame::ResponseStatus::Accept as i32),
                ..Default::default()
            }),
            ..Default::default()
        }),
    )
    .await?;
    let _ = read(stream).await?; // the peer's response; nothing in it we act on

    // ── 6. Paired key frames — content is deliberately meaningless ───────
    // Real values would require Google account state we have no way to obtain.
    // Random bytes of the right shape are what every third-party implementation
    // sends, and the peer accepts them.
    let mut signed = [0u8; 72];
    let mut secret_id = [0u8; 6];
    getrandom::fill(&mut signed).expect("system randomness");
    getrandom::fill(&mut secret_id).expect("system randomness");
    send_sharing(stream, &mut channel, sharing_frame(sharing::v1_frame::FrameType::PairedKeyEncryption, |v| {
        v.paired_key_encryption = Some(sharing::PairedKeyEncryptionFrame {
            signed_data: Some(signed.to_vec()),
            secret_id_hash: Some(secret_id.to_vec()),
            ..Default::default()
        });
    })).await?;
    send_sharing(stream, &mut channel, sharing_frame(sharing::v1_frame::FrameType::PairedKeyResult, |v| {
        v.paired_key_result = Some(sharing::PairedKeyResultFrame {
            status: Some(sharing::paired_key_result_frame::Status::Unable as i32),
            ..Default::default()
        });
    })).await?;

    // ── 7. Wait for the introduction ─────────────────────────────────────
    let introduction = loop {
        match next_sharing_frame(stream, &mut channel, reassembler).await? {
            Some(frame) => {
                let v1 = frame.v1.unwrap_or_default();
                if let Some(intro) = v1.introduction {
                    break intro;
                }
                // PairedKey* frames from the peer land here and are ignored,
                // which is exactly what they deserve.
            }
            None => continue,
        }
    };

    let files: Vec<IncomingFile> = introduction
        .file_metadata
        .iter()
        .map(|f| IncomingFile {
            name: super::payload::sanitize_file_name(f.name.as_deref().unwrap_or("")),
            size: f.size.unwrap_or(0),
            mime: f.mime_type.clone().unwrap_or_default(),
        })
        .collect();
    if files.is_empty() {
        return Err("the sender offered nothing this Mac can receive".into());
    }
    for f in &introduction.file_metadata {
        if let (Some(id), Some(name)) = (f.payload_id, f.name.as_deref()) {
            reassembler.expect_file(id, name);
        }
    }
    let total: i64 = files.iter().map(|f| f.size).sum();

    // ── 8. Ask the user ──────────────────────────────────────────────────
    let (tx, rx) = oneshot::channel();
    events
        .send(Event::Introduced { peer: peer_name, pin, files, reply: tx })
        .map_err(|_| "nothing is listening for transfer prompts")?;
    let accepted = match tokio::time::timeout(CONSENT_TIMEOUT, rx).await {
        Ok(Ok(v)) => v,
        // Timed out, or the UI went away: decline rather than accept by default.
        _ => false,
    };

    send_sharing(stream, &mut channel, sharing_frame(sharing::v1_frame::FrameType::Response, |v| {
        v.connection_response = Some(sharing::ConnectionResponseFrame {
            status: Some(if accepted {
                sharing::connection_response_frame::Status::Accept as i32
            } else {
                sharing::connection_response_frame::Status::Reject as i32
            }),
            ..Default::default()
        });
    })).await?;

    if !accepted {
        let _ = events.send(Event::Rejected);
        return Ok(());
    }

    // ── 9. Receive the files ─────────────────────────────────────────────
    let mut paths = Vec::new();
    let mut received: i64 = 0;
    let mut ticker = tokio::time::interval(KEEP_ALIVE);
    ticker.tick().await; // the first tick completes immediately

    loop {
        tokio::select! {
            _ = ticker.tick() => {
                write(stream, &offline(conn::V1Frame {
                    r#type: Some(conn::v1_frame::FrameType::KeepAlive as i32),
                    keep_alive: Some(conn::KeepAliveFrame { ack: Some(false), seq_num: Some(0) }),
                    ..Default::default()
                })).await?;
            }
            incoming = tokio::time::timeout(IDLE_TIMEOUT, read(stream)) => {
                let raw = incoming.map_err(|_| "the sender stopped responding".to_string())??;
                let plain = channel.open(&raw).map_err(|e| e.to_string())?;
                let frame = conn::OfflineFrame::decode(plain.as_slice())
                    .map_err(|_| "bad offline frame")?;
                let Some(v1) = frame.v1 else { continue };

                match v1.r#type {
                    Some(t) if t == conn::v1_frame::FrameType::KeepAlive as i32 => continue,
                    Some(t) if t == conn::v1_frame::FrameType::Disconnection as i32 => {
                        return Err("the sender disconnected".into());
                    }
                    _ => {}
                }
                let Some(transfer) = v1.payload_transfer else { continue };
                match handle_transfer(&transfer, reassembler)? {
                    Some(Completed::File { path, .. }) => {
                        paths.push(path);
                        if paths.len() == introduction.file_metadata.len() {
                            let _ = events.send(Event::Done { paths: paths.clone() });
                            return Ok(());
                        }
                    }
                    Some(Completed::Progress { .. }) => {
                        received += transfer
                            .payload_chunk
                            .as_ref()
                            .and_then(|c| c.body.as_ref())
                            .map(|b| b.len() as i64)
                            .unwrap_or(0);
                        let _ = events.send(Event::Progress { received, total });
                    }
                    // A BYTES payload after acceptance is a control frame such
                    // as a cancel; nothing to collect.
                    _ => {}
                }
            }
        }
    }
}

/// Route one payload-transfer frame into the reassembler.
fn handle_transfer(
    transfer: &conn::PayloadTransferFrame,
    reassembler: &mut Reassembler,
) -> Res<Option<Completed>> {
    let Some(header) = &transfer.payload_header else { return Ok(None) };
    let Some(chunk) = &transfer.payload_chunk else { return Ok(None) };
    let id = header.id.unwrap_or(0);
    let is_file = header.r#type
        == Some(conn::payload_transfer_frame::payload_header::PayloadType::File as i32);
    // A FILE payload we were never introduced to is dropped rather than failing
    // the whole connection: the rest of the transfer may still be legitimate.
    if is_file && !reassembler.is_expected_file(id) {
        return Ok(None);
    }
    let last = chunk.flags.unwrap_or(0)
        & conn::payload_transfer_frame::payload_chunk::Flags::LastChunk as i32
        != 0;
    reassembler
        .on_chunk(
            id,
            is_file,
            header.total_size.unwrap_or(0),
            chunk.offset.unwrap_or(0),
            chunk.body.as_deref().unwrap_or(&[]),
            last,
        )
        .map_err(|e| e.to_string())
}

fn sharing_frame(
    kind: sharing::v1_frame::FrameType,
    fill: impl FnOnce(&mut sharing::V1Frame),
) -> sharing::Frame {
    let mut v1 = sharing::V1Frame { r#type: Some(kind as i32), ..Default::default() };
    fill(&mut v1);
    sharing::Frame {
        version: Some(sharing::frame::Version::V1 as i32),
        v1: Some(v1),
    }
}

/// Send a sharing frame as a BYTES payload.
///
/// Two transfer frames, not one: the body, then an empty chunk carrying
/// LAST_CHUNK. That is what Android does, and NearDrop found it necessary to
/// match — a single frame with the flag set is not reliably accepted.
async fn send_sharing<S>(
    stream: &mut S,
    channel: &mut SecureChannel,
    frame: sharing::Frame,
) -> Res<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let body = frame.encode_to_vec();
    let mut id_bytes = [0u8; 8];
    getrandom::fill(&mut id_bytes).expect("system randomness");
    let id = i64::from_be_bytes(id_bytes);

    let header = conn::payload_transfer_frame::PayloadHeader {
        id: Some(id),
        r#type: Some(conn::payload_transfer_frame::payload_header::PayloadType::Bytes as i32),
        total_size: Some(body.len() as i64),
        ..Default::default()
    };

    for (offset, chunk_body, last) in [(0i64, body.clone(), false), (body.len() as i64, Vec::new(), true)] {
        let transfer = conn::PayloadTransferFrame {
            packet_type: Some(conn::payload_transfer_frame::PacketType::Data as i32),
            payload_header: Some(header.clone()),
            payload_chunk: Some(conn::payload_transfer_frame::PayloadChunk {
                flags: Some(if last {
                    conn::payload_transfer_frame::payload_chunk::Flags::LastChunk as i32
                } else {
                    0
                }),
                offset: Some(offset),
                body: Some(chunk_body),
                ..Default::default()
            }),
            ..Default::default()
        };
        let sealed = channel.seal(&offline(conn::V1Frame {
            r#type: Some(conn::v1_frame::FrameType::PayloadTransfer as i32),
            payload_transfer: Some(transfer),
            ..Default::default()
        }));
        write(stream, &sealed).await?;
    }
    Ok(())
}

/// Read until a complete BYTES payload arrives, and parse it as a sharing frame.
/// Returns `Ok(None)` for a frame that was consumed but produced nothing.
async fn next_sharing_frame<S>(
    stream: &mut S,
    channel: &mut SecureChannel,
    reassembler: &mut Reassembler,
) -> Res<Option<sharing::Frame>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let raw = tokio::time::timeout(IDLE_TIMEOUT, read(stream))
        .await
        .map_err(|_| "the sender stopped responding".to_string())??;
    let plain = channel.open(&raw).map_err(|e| e.to_string())?;
    let frame = conn::OfflineFrame::decode(plain.as_slice()).map_err(|_| "bad offline frame")?;
    let Some(v1) = frame.v1 else { return Ok(None) };
    let Some(transfer) = v1.payload_transfer else { return Ok(None) };
    match handle_transfer(&transfer, reassembler)? {
        Some(Completed::Bytes { data, .. }) => Ok(sharing::Frame::decode(data.as_slice()).ok()),
        _ => Ok(None),
    }
}

async fn read<S: AsyncRead + Unpin>(s: &mut S) -> Res<Vec<u8>> {
    wire::read_frame(s).await.map_err(|e| e.to_string())
}

async fn write<S: AsyncWrite + Unpin>(s: &mut S, body: &[u8]) -> Res<()> {
    wire::write_frame(s, body).await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signed_encoding_round_trips() {
        for probe in [[0u8; 32], [0xff; 32], [0x7f; 32], [0x80; 32]] {
            let encoded = to_signed_be(&probe);
            assert_eq!(from_signed_be(&encoded).unwrap(), probe, "{probe:?}");
        }
    }

    #[test]
    fn high_bit_gets_a_sign_byte() {
        // The interop trap: without the leading zero the peer's BigInteger
        // reads this as negative and derives a different shared secret.
        let mut v = [0u8; 32];
        v[0] = 0x80;
        let encoded = to_signed_be(&v);
        assert_eq!(encoded.len(), 33);
        assert_eq!(encoded[0], 0x00);

        v[0] = 0x7f;
        assert_eq!(to_signed_be(&v).len(), 32, "no sign byte when the top bit is clear");
    }

    #[test]
    fn leading_zeroes_are_trimmed_like_biginteger() {
        let mut v = [0u8; 32];
        v[31] = 5;
        assert_eq!(to_signed_be(&v), vec![5]);
        // …and restored on the way back in.
        assert_eq!(from_signed_be(&[5]).unwrap(), v);
    }

    #[test]
    fn from_signed_be_rejects_nonsense_lengths() {
        assert!(from_signed_be(&[]).is_none());
        assert!(from_signed_be(&[0u8; 34]).is_none());
        // A 33-byte sign-extended value is legitimate.
        assert!(from_signed_be(&[0u8; 33]).is_some());
    }

    #[test]
    fn generated_keys_are_valid_and_distinct() {
        let a = random_p256();
        let b = random_p256();
        assert_ne!(a.to_bytes(), b.to_bytes());
        // And a round-trip through the wire encoding lands on the same point.
        let pt = a.public_key().to_sec1_point(false);
        let x = from_signed_be(&to_signed_be(pt.x().unwrap().as_slice())).unwrap();
        let y = from_signed_be(&to_signed_be(pt.y().unwrap().as_slice())).unwrap();
        let re = p256::Sec1Point::from_affine_coordinates(&x.into(), &y.into(), false);
        assert_eq!(
            Option::<p256::PublicKey>::from(p256::PublicKey::from_sec1_point(&re)).unwrap(),
            a.public_key()
        );
    }

    #[test]
    fn ecdh_agrees_across_the_wire_encoding() {
        // The whole handshake rests on this: both sides must reach the same
        // secret after the coordinates have been through signed encoding.
        let server = random_p256();
        let client = random_p256();

        let cpt = client.public_key().to_sec1_point(false);
        let x = from_signed_be(&to_signed_be(cpt.x().unwrap().as_slice())).unwrap();
        let y = from_signed_be(&to_signed_be(cpt.y().unwrap().as_slice())).unwrap();
        let encoded = p256::Sec1Point::from_affine_coordinates(&x.into(), &y.into(), false);
        let client_pub =
            Option::<p256::PublicKey>::from(p256::PublicKey::from_sec1_point(&encoded)).unwrap();

        let a = p256::ecdh::diffie_hellman(server.to_nonzero_scalar(), client_pub.as_affine());
        let b = p256::ecdh::diffie_hellman(
            client.to_nonzero_scalar(),
            server.public_key().as_affine(),
        );
        assert_eq!(a.raw_secret_bytes(), b.raw_secret_bytes());
        assert_eq!(a.raw_secret_bytes().len(), 32, "fixed width, never trimmed");
    }
}
