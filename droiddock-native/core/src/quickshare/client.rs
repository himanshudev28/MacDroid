//! Quick Share **send**: the outbound client (Mac → a nearby Quick Share device).
//!
//! The mirror image of `connection.rs`: here we are the UKEY2 *client*. Written
//! from the protocol description (NearDrop's PROTOCOL.md, Unlicense) and the
//! existing server half in this crate — no code from rquickshare (GPL) or
//! AirSync (MPL).
//!
//! Sequence:
//!   1. ConnectionRequest (plain)       — who we are
//!   2. UKEY2 ClientInit  (plain)       — commitment to our key
//!   3. UKEY2 ServerInit  (plain)       — peer's P-256 key
//!   4. UKEY2 ClientFinish (plain)      — our key; ECDH; session keys (client view)
//!   5. ConnectionResponse both ways (plain)
//!   --- sealed from here ---
//!   6. PairedKeyEncryption / PairedKeyResult both ways (meaningless content)
//!   7. Introduction (file list) -> wait for Response ACCEPT/REJECT
//!   8. FILE payload chunks, then a final empty LAST_CHUNK per file
//!
//! Platform limit (see mod.rs): a phone only shows up here while its Quick Share
//! screen is open and visible to everyone — a Mac cannot wake it over BLE.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use p256::elliptic_curve::sec1::{FromSec1Point, ToSec1Point};
use prost::Message;
use sha2::{Digest, Sha512};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

use super::connection::{from_signed_be, offline, random_p256, sharing_frame, to_signed_be};
use super::crypto::{pin_code, SessionKeys};
use super::payload::{Completed, Reassembler};
use super::proto::location::nearby::connections as conn;
use super::proto::securegcm as gcm;
use super::proto::securemessage as sm;
use super::proto::sharing::nearby as sharing;
use super::secure::SecureChannel;
use super::wire;

const CHUNK: usize = 64 * 1024;
const KEEP_ALIVE: Duration = Duration::from_secs(10);
/// How long the person on the other device has to tap Accept.
const CONSENT_TIMEOUT: Duration = Duration::from_secs(90);
const STEP_TIMEOUT: Duration = Duration::from_secs(20);

/// One file to send.
#[derive(Clone, Debug)]
pub struct OutFile {
    pub path: PathBuf,
    pub name: String,
    pub size: i64,
    pub mime: String,
}

impl OutFile {
    pub fn from_path(path: PathBuf) -> std::io::Result<Self> {
        let meta = std::fs::metadata(&path)?;
        if !meta.is_file() {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "not a file"));
        }
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".into());
        let mime = mime_for(&name).to_string();
        Ok(Self { path, name, size: meta.len() as i64, mime })
    }
}

fn mime_for(name: &str) -> &'static str {
    match name.rsplit('.').next().map(str::to_ascii_lowercase).as_deref() {
        Some("jpg") | Some("jpeg") => "image/jpeg",
        Some("png") => "image/png",
        Some("gif") => "image/gif",
        Some("webp") => "image/webp",
        Some("heic") => "image/heic",
        Some("mp4") => "video/mp4",
        Some("mov") => "video/quicktime",
        Some("mp3") => "audio/mpeg",
        Some("m4a") => "audio/mp4",
        Some("pdf") => "application/pdf",
        Some("txt") => "text/plain",
        Some("zip") => "application/zip",
        _ => "application/octet-stream",
    }
}

fn file_kind(mime: &str) -> sharing::file_metadata::Type {
    use sharing::file_metadata::Type;
    if mime.starts_with("image/") {
        Type::Image
    } else if mime.starts_with("video/") {
        Type::Video
    } else if mime.starts_with("audio/") {
        Type::Audio
    } else if mime == "application/pdf" || mime.starts_with("text/") {
        Type::Document
    } else {
        Type::Unknown
    }
}

/// What a send reports outward.
#[derive(Debug)]
pub enum SendEvent {
    /// Handshake done; `pin` is what both screens show.
    Connected { peer: String, pin: String },
    /// Introduction sent; waiting for the other person to accept.
    WaitingForAccept,
    Progress { sent: i64, total: i64 },
    Done,
    Rejected,
    Failed { error: String },
}

type Res<T> = Result<T, String>;
type Shared = Arc<Mutex<SecureChannel>>;

enum Incoming {
    Sharing(Box<sharing::Frame>),
    Disconnected,
}

/// Run one outbound transfer on an already-connected stream.
pub async fn send<S>(
    stream: S,
    our_name: &str,
    files: Vec<OutFile>,
    events: mpsc::UnboundedSender<SendEvent>,
    cancel: Arc<AtomicBool>,
) -> Res<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let result = run(stream, our_name, files, &events, cancel).await;
    match &result {
        Ok(true) => {
            let _ = events.send(SendEvent::Done);
        }
        Ok(false) => {
            let _ = events.send(SendEvent::Rejected);
        }
        Err(e) => {
            let _ = events.send(SendEvent::Failed { error: e.clone() });
        }
    }
    result.map(|_| ())
}

async fn write_plain<W: AsyncWrite + Unpin>(w: &mut W, body: &[u8]) -> Res<()> {
    wire::write_frame(w, body).await.map_err(|e| e.to_string())?;
    w.flush().await.map_err(|e| e.to_string())
}

async fn read_plain<R: AsyncRead + Unpin>(r: &mut R) -> Res<Vec<u8>> {
    match tokio::time::timeout(STEP_TIMEOUT, wire::read_frame(r)).await {
        Ok(Ok(v)) => Ok(v),
        Ok(Err(e)) => Err(format!("connection closed during handshake: {e}")),
        Err(_) => Err("the other device did not answer".into()),
    }
}

fn seal_to(channel: &Shared, frame: &[u8]) -> Vec<u8> {
    channel.lock().unwrap().seal(frame)
}

async fn send_sharing<W: AsyncWrite + Unpin>(w: &mut W, channel: &Shared, frame: sharing::Frame) -> Res<()> {
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
    // Body, then an empty LAST_CHUNK — same two-frame shape the receive side uses.
    for (offset, chunk_body, last) in [(0i64, body.clone(), false), (body.len() as i64, Vec::new(), true)] {
        let sealed = seal_to(channel, &payload_frame(header.clone(), offset, chunk_body, last));
        wire::write_frame(w, &sealed).await.map_err(|e| e.to_string())?;
    }
    w.flush().await.map_err(|e| e.to_string())
}

fn payload_frame(
    header: conn::payload_transfer_frame::PayloadHeader,
    offset: i64,
    body: Vec<u8>,
    last: bool,
) -> Vec<u8> {
    offline(conn::V1Frame {
        r#type: Some(conn::v1_frame::FrameType::PayloadTransfer as i32),
        payload_transfer: Some(conn::PayloadTransferFrame {
            packet_type: Some(conn::payload_transfer_frame::PacketType::Data as i32),
            payload_header: Some(header),
            payload_chunk: Some(conn::payload_transfer_frame::PayloadChunk {
                flags: Some(if last {
                    conn::payload_transfer_frame::payload_chunk::Flags::LastChunk as i32
                } else {
                    0
                }),
                offset: Some(offset),
                body: Some(body),
                ..Default::default()
            }),
            ..Default::default()
        }),
        ..Default::default()
    })
}

/// Reader task: decrypt frames, reassemble control payloads, forward the
/// sharing frames. Ends (with `Disconnected`) on EOF, error or a Disconnection.
async fn read_loop<R: AsyncRead + Unpin>(mut r: R, channel: Shared, tx: mpsc::UnboundedSender<Incoming>) {
    let mut re = Reassembler::new(std::env::temp_dir());
    loop {
        let raw = match wire::read_frame(&mut r).await {
            Ok(v) => v,
            Err(_) => break,
        };
        let plain = match channel.lock().unwrap().open(&raw) {
            Ok(p) => p,
            Err(_) => break,
        };
        let Ok(frame) = conn::OfflineFrame::decode(plain.as_slice()) else { continue };
        let Some(v1) = frame.v1 else { continue };
        if v1.r#type == Some(conn::v1_frame::FrameType::Disconnection as i32) {
            break;
        }
        let Some(t) = v1.payload_transfer else { continue };
        let (Some(h), Some(c)) = (&t.payload_header, &t.payload_chunk) else { continue };
        let is_file = h.r#type
            == Some(conn::payload_transfer_frame::payload_header::PayloadType::File as i32);
        if is_file {
            continue;
        }
        let last = c.flags.unwrap_or(0)
            & conn::payload_transfer_frame::payload_chunk::Flags::LastChunk as i32
            != 0;
        if let Ok(Some(Completed::Bytes { data, .. })) = re.on_chunk(
            h.id.unwrap_or(0),
            false,
            h.total_size.unwrap_or(0),
            c.offset.unwrap_or(0),
            c.body.as_deref().unwrap_or(&[]),
            last,
        ) {
            if let Ok(f) = sharing::Frame::decode(data.as_slice()) {
                if tx.send(Incoming::Sharing(Box::new(f))).is_err() {
                    return;
                }
            }
        }
    }
    let _ = tx.send(Incoming::Disconnected);
}

fn is_type(f: &sharing::Frame, t: sharing::v1_frame::FrameType) -> bool {
    f.v1.as_ref().and_then(|v| v.r#type) == Some(t as i32)
}

/// `Ok(true)` = delivered, `Ok(false)` = declined by the other side.
async fn run<S>(
    stream: S,
    our_name: &str,
    files: Vec<OutFile>,
    events: &mpsc::UnboundedSender<SendEvent>,
    cancel: Arc<AtomicBool>,
) -> Res<bool>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    if files.is_empty() {
        return Err("nothing to send".into());
    }
    let (mut rd, mut wr) = tokio::io::split(stream);

    // ── 1. Connection request ────────────────────────────────────────────
    let endpoint_id = wire::EndpointId::random();
    let mut nonce = [0u8; 4];
    getrandom::fill(&mut nonce).expect("system randomness");
    write_plain(
        &mut wr,
        &offline(conn::V1Frame {
            r#type: Some(conn::v1_frame::FrameType::ConnectionRequest as i32),
            connection_request: Some(conn::ConnectionRequestFrame {
                endpoint_id: Some(endpoint_id.as_str().to_string()),
                endpoint_name: Some(our_name.to_string()),
                endpoint_info: Some(wire::endpoint_info(wire::DeviceType::Laptop, our_name)),
                nonce: Some(i32::from_be_bytes(nonce)),
                mediums: vec![conn::connection_request_frame::Medium::WifiLan as i32],
                keep_alive_interval_millis: Some(KEEP_ALIVE.as_millis() as i32),
                keep_alive_timeout_millis: Some(30_000),
                ..Default::default()
            }),
            ..Default::default()
        }),
    )
    .await?;

    // ── 2. UKEY2 ClientInit — commit to our key before seeing theirs ─────
    let secret = random_p256();
    let point = secret.public_key().to_sec1_point(false);
    let our_key = sm::GenericPublicKey {
        r#type: sm::PublicKeyType::EcP256 as i32,
        ec_p256_public_key: Some(sm::EcP256PublicKey {
            x: to_signed_be(point.x().ok_or("no x coordinate")?.as_slice()),
            y: to_signed_be(point.y().ok_or("no y coordinate")?.as_slice()),
        }),
        ..Default::default()
    };
    let client_finish_raw = gcm::Ukey2Message {
        message_type: Some(gcm::ukey2_message::Type::ClientFinish as i32),
        message_data: Some(
            gcm::Ukey2ClientFinished { public_key: Some(our_key.encode_to_vec()) }.encode_to_vec(),
        ),
    }
    .encode_to_vec();
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).expect("system randomness");
    let client_init_raw = gcm::Ukey2Message {
        message_type: Some(gcm::ukey2_message::Type::ClientInit as i32),
        message_data: Some(
            gcm::Ukey2ClientInit {
                version: Some(1),
                random: Some(random.to_vec()),
                cipher_commitments: vec![gcm::ukey2_client_init::CipherCommitment {
                    handshake_cipher: Some(gcm::Ukey2HandshakeCipher::P256Sha512 as i32),
                    commitment: Some(Sha512::digest(&client_finish_raw).to_vec()),
                }],
                next_protocol: Some("AES_256_CBC-HMAC_SHA256".to_string()),
            }
            .encode_to_vec(),
        ),
    }
    .encode_to_vec();
    write_plain(&mut wr, &client_init_raw).await?;

    // ── 3. ServerInit ────────────────────────────────────────────────────
    let server_init_raw = read_plain(&mut rd).await?;
    let msg = gcm::Ukey2Message::decode(server_init_raw.as_slice()).map_err(|_| "bad ukey2 server init")?;
    if msg.message_type != Some(gcm::ukey2_message::Type::ServerInit as i32) {
        return Err("the other device refused the secure handshake".into());
    }
    let server_init = gcm::Ukey2ServerInit::decode(msg.message_data.as_deref().ok_or("server init had no data")?)
        .map_err(|_| "bad ukey2 server init body")?;
    if server_init.handshake_cipher != Some(gcm::Ukey2HandshakeCipher::P256Sha512 as i32) {
        return Err("the other device chose a cipher we don't speak".into());
    }
    let peer_key = sm::GenericPublicKey::decode(server_init.public_key.as_deref().ok_or("server init had no key")?)
        .map_err(|_| "bad peer public key")?;
    let ec = peer_key.ec_p256_public_key.ok_or("peer key was not P-256")?;
    let px = from_signed_be(&ec.x).ok_or("bad peer x")?;
    let py = from_signed_be(&ec.y).ok_or("bad peer y")?;
    let encoded = p256::Sec1Point::from_affine_coordinates(&px.into(), &py.into(), false);
    let peer_public = Option::<p256::PublicKey>::from(p256::PublicKey::from_sec1_point(&encoded))
        .ok_or("peer public key is not on the curve")?;

    // ── 4. ClientFinish, then derive ─────────────────────────────────────
    write_plain(&mut wr, &client_finish_raw).await?;
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), peer_public.as_affine());
    let keys = SessionKeys::derive(shared.raw_secret_bytes().as_slice(), &client_init_raw, &server_init_raw)
        .as_client();
    let pin = pin_code(&keys.auth);
    let channel: Shared = Arc::new(Mutex::new(SecureChannel::new(keys)));

    // ── 5. Connection response, both ways ────────────────────────────────
    write_plain(
        &mut wr,
        &offline(conn::V1Frame {
            r#type: Some(conn::v1_frame::FrameType::ConnectionResponse as i32),
            connection_response: Some(conn::ConnectionResponseFrame {
                response: Some(conn::connection_response_frame::ResponseStatus::Accept as i32),
                ..Default::default()
            }),
            ..Default::default()
        }),
    )
    .await?;
    let resp = read_plain(&mut rd).await?;
    if let Ok(f) = conn::OfflineFrame::decode(resp.as_slice()) {
        let refused = f
            .v1
            .and_then(|v| v.connection_response)
            .and_then(|r| r.response)
            == Some(conn::connection_response_frame::ResponseStatus::Reject as i32);
        if refused {
            return Ok(false);
        }
    }
    let _ = events.send(SendEvent::Connected { peer: String::new(), pin });

    // From here on a reader task owns the read half.
    let (tx, mut incoming) = mpsc::unbounded_channel();
    let reader = tokio::spawn(read_loop(rd, channel.clone(), tx));
    let outcome = sealed_phase(&mut wr, &channel, &mut incoming, files, events, &cancel).await;
    if outcome.is_err() {
        // Best effort: tell the peer we are going away.
        let bye = seal_to(&channel, &offline(conn::V1Frame {
            r#type: Some(conn::v1_frame::FrameType::Disconnection as i32),
            ..Default::default()
        }));
        let _ = wire::write_frame(&mut wr, &bye).await;
    }
    let _ = wr.shutdown().await;
    reader.abort();
    outcome
}

async fn sealed_phase<W: AsyncWrite + Unpin>(
    wr: &mut W,
    channel: &Shared,
    incoming: &mut mpsc::UnboundedReceiver<Incoming>,
    files: Vec<OutFile>,
    events: &mpsc::UnboundedSender<SendEvent>,
    cancel: &AtomicBool,
) -> Res<bool> {
    // ── 6. Paired key frames (meaningless content, as on the receive side) ──
    let mut signed = [0u8; 72];
    let mut secret_id = [0u8; 6];
    getrandom::fill(&mut signed).expect("system randomness");
    getrandom::fill(&mut secret_id).expect("system randomness");
    send_sharing(wr, channel, sharing_frame(sharing::v1_frame::FrameType::PairedKeyEncryption, |v| {
        v.paired_key_encryption = Some(sharing::PairedKeyEncryptionFrame {
            signed_data: Some(signed.to_vec()),
            secret_id_hash: Some(secret_id.to_vec()),
            ..Default::default()
        });
    }))
    .await?;
    // Wait (softly) for the peer's key frame before answering with the result.
    wait_for(incoming, sharing::v1_frame::FrameType::PairedKeyEncryption, Duration::from_secs(10)).await?;
    send_sharing(wr, channel, sharing_frame(sharing::v1_frame::FrameType::PairedKeyResult, |v| {
        v.paired_key_result = Some(sharing::PairedKeyResultFrame {
            status: Some(sharing::paired_key_result_frame::Status::Unable as i32),
            ..Default::default()
        });
    }))
    .await?;
    wait_for(incoming, sharing::v1_frame::FrameType::PairedKeyResult, Duration::from_secs(10)).await?;

    // ── 7. Introduction ──────────────────────────────────────────────────
    let mut ids = Vec::new();
    let mut meta = Vec::new();
    for f in &files {
        let mut b = [0u8; 8];
        getrandom::fill(&mut b).expect("system randomness");
        let id = i64::from_be_bytes(b);
        ids.push(id);
        meta.push(sharing::FileMetadata {
            name: Some(f.name.clone()),
            r#type: Some(file_kind(&f.mime) as i32),
            payload_id: Some(id),
            size: Some(f.size),
            mime_type: Some(f.mime.clone()),
            id: Some(id),
            ..Default::default()
        });
    }
    send_sharing(wr, channel, sharing_frame(sharing::v1_frame::FrameType::Introduction, |v| {
        v.introduction = Some(sharing::IntroductionFrame {
            file_metadata: meta,
            ..Default::default()
        });
    }))
    .await?;
    let _ = events.send(SendEvent::WaitingForAccept);

    // Wait for ACCEPT / REJECT, keeping the link alive and honouring Cancel.
    let deadline = tokio::time::Instant::now() + CONSENT_TIMEOUT;
    let mut ticker = tokio::time::interval(KEEP_ALIVE);
    ticker.tick().await;
    let accepted = loop {
        if cancel.load(Ordering::Relaxed) {
            send_cancel(wr, channel).await;
            return Err("cancelled".into());
        }
        tokio::select! {
            _ = ticker.tick() => keep_alive(wr, channel).await?,
            _ = tokio::time::sleep(Duration::from_millis(500)) => {}
            _ = tokio::time::sleep_until(deadline) => return Err("the other device did not respond in time".into()),
            msg = incoming.recv() => match msg {
                Some(Incoming::Sharing(f)) => {
                    if is_type(&f, sharing::v1_frame::FrameType::Cancel) {
                        return Err("the other device cancelled".into());
                    }
                    if is_type(&f, sharing::v1_frame::FrameType::Response) {
                        let status = f.v1.and_then(|v| v.connection_response).and_then(|r| r.status);
                        break status == Some(sharing::connection_response_frame::Status::Accept as i32);
                    }
                }
                Some(Incoming::Disconnected) | None => return Err("the other device disconnected".into()),
            },
        }
    };
    if !accepted {
        return Ok(false);
    }

    // ── 8. Stream the files ──────────────────────────────────────────────
    let total: i64 = files.iter().map(|f| f.size).sum();
    let mut sent_total: i64 = 0;
    let mut last_keepalive = tokio::time::Instant::now();
    let mut buf = vec![0u8; CHUNK];
    for (file, id) in files.iter().zip(ids) {
        let mut fh = tokio::fs::File::open(&file.path).await.map_err(|e| format!("{}: {e}", file.name))?;
        let header = conn::payload_transfer_frame::PayloadHeader {
            id: Some(id),
            r#type: Some(conn::payload_transfer_frame::payload_header::PayloadType::File as i32),
            total_size: Some(file.size),
            file_name: Some(file.name.clone()),
            ..Default::default()
        };
        let mut offset: i64 = 0;
        loop {
            if cancel.load(Ordering::Relaxed) {
                send_cancel(wr, channel).await;
                return Err("cancelled".into());
            }
            // Pick up a cancel / disconnect from the peer without blocking.
            match incoming.try_recv() {
                Ok(Incoming::Sharing(f)) if is_type(&f, sharing::v1_frame::FrameType::Cancel) => {
                    return Err("the other device cancelled".into());
                }
                Ok(Incoming::Disconnected) => return Err("the other device disconnected".into()),
                _ => {}
            }
            let n = fh.read(&mut buf).await.map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            let sealed = seal_to(channel, &payload_frame(header.clone(), offset, buf[..n].to_vec(), false));
            wire::write_frame(wr, &sealed).await.map_err(|e| e.to_string())?;
            offset += n as i64;
            sent_total += n as i64;
            let _ = events.send(SendEvent::Progress { sent: sent_total, total });
            if last_keepalive.elapsed() >= KEEP_ALIVE {
                keep_alive(wr, channel).await?;
                last_keepalive = tokio::time::Instant::now();
            }
        }
        let sealed = seal_to(channel, &payload_frame(header, offset, Vec::new(), true));
        wire::write_frame(wr, &sealed).await.map_err(|e| e.to_string())?;
    }
    wr.flush().await.map_err(|e| e.to_string())?;

    // Give the receiver a moment to finish and hang up; either way we're done.
    let _ = tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(m) = incoming.recv().await {
            if matches!(m, Incoming::Disconnected) {
                break;
            }
        }
    })
    .await;
    Ok(true)
}

async fn keep_alive<W: AsyncWrite + Unpin>(wr: &mut W, channel: &Shared) -> Res<()> {
    let sealed = seal_to(channel, &offline(conn::V1Frame {
        r#type: Some(conn::v1_frame::FrameType::KeepAlive as i32),
        keep_alive: Some(conn::KeepAliveFrame { ack: Some(false), seq_num: Some(0) }),
        ..Default::default()
    }));
    wire::write_frame(wr, &sealed).await.map_err(|e| e.to_string())?;
    wr.flush().await.map_err(|e| e.to_string())
}

async fn send_cancel<W: AsyncWrite + Unpin>(wr: &mut W, channel: &Shared) {
    let _ = send_sharing(wr, channel, sharing_frame(sharing::v1_frame::FrameType::Cancel, |_| {})).await;
}

/// Wait up to `limit` for a sharing frame of `kind`; a timeout is not an error
/// (some receivers skip the paired-key dance), a disconnect or Cancel is.
async fn wait_for(
    incoming: &mut mpsc::UnboundedReceiver<Incoming>,
    kind: sharing::v1_frame::FrameType,
    limit: Duration,
) -> Res<()> {
    let end = tokio::time::Instant::now() + limit;
    loop {
        match tokio::time::timeout_at(end, incoming.recv()).await {
            Err(_) => return Ok(()),
            Ok(None) | Ok(Some(Incoming::Disconnected)) => {
                return Err("the other device disconnected".into());
            }
            Ok(Some(Incoming::Sharing(f))) => {
                if is_type(&f, sharing::v1_frame::FrameType::Cancel) {
                    return Err("the other device cancelled".into());
                }
                if is_type(&f, kind) {
                    return Ok(());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::connection::{self, Event, IncomingFile};
    use super::*;

    async fn loopback(contents: Vec<(&str, Vec<u8>)>, accept: bool, cancel_early: bool) -> (Vec<SendEvent>, Vec<Event>, PathBuf) {
        let dir = std::env::temp_dir().join(format!("dd-qs-test-{}", uuid::Uuid::new_v4()));
        let src = dir.join("src");
        let dst = dir.join("dst");
        std::fs::create_dir_all(&src).unwrap();
        std::fs::create_dir_all(&dst).unwrap();
        let mut files = Vec::new();
        for (name, data) in &contents {
            std::fs::write(src.join(name), data).unwrap();
            files.push(OutFile::from_path(src.join(name)).unwrap());
        }

        let (a, b) = tokio::io::duplex(1 << 20);
        let (stx, mut srx) = mpsc::unbounded_channel();
        let server = tokio::spawn(connection::serve(b, dst.clone(), stx));
        // Answer the server's consent prompt.
        let answer = tokio::spawn(async move {
            let mut seen = Vec::new();
            while let Some(ev) = srx.recv().await {
                match ev {
                    Event::Introduced { files, reply, peer, pin } => {
                        let _ = reply.send(accept);
                        seen.push(Event::Introduced { files, reply: tokio::sync::oneshot::channel().0, peer, pin });
                    }
                    other => seen.push(other),
                }
            }
            seen
        });

        let (ctx, mut crx) = mpsc::unbounded_channel();
        let cancel = Arc::new(AtomicBool::new(cancel_early));
        let _ = send(a, "Test Mac", files, ctx, cancel).await;
        let _ = server.await;
        let server_events = answer.await.unwrap();
        let mut client_events = Vec::new();
        while let Ok(e) = crx.try_recv() {
            client_events.push(e);
        }
        (client_events, server_events, dst)
    }

    #[tokio::test]
    async fn client_and_server_agree_and_files_arrive_intact() {
        let big: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let (c, s, dst) = loopback(vec![("a.txt", b"hello quick share".to_vec()), ("big.bin", big.clone())], true, false).await;

        // Both sides derived the same PIN.
        let client_pin = c.iter().find_map(|e| match e { SendEvent::Connected { pin, .. } => Some(pin.clone()), _ => None }).unwrap();
        let (server_pin, offered): (String, Vec<IncomingFile>) = s.iter().find_map(|e| match e {
            Event::Introduced { pin, files, .. } => Some((pin.clone(), files.clone())),
            _ => None,
        }).unwrap();
        assert_eq!(client_pin, server_pin, "UKEY2 handshake must agree");
        assert_eq!(offered.len(), 2);
        assert_eq!(offered[0].name, "a.txt");
        assert_eq!(offered[1].size, big.len() as i64);

        assert!(c.iter().any(|e| matches!(e, SendEvent::Done)), "client events: {c:?}");
        assert!(s.iter().any(|e| matches!(e, Event::Done { .. })));
        assert_eq!(std::fs::read(dst.join("a.txt")).unwrap(), b"hello quick share");
        assert_eq!(std::fs::read(dst.join("big.bin")).unwrap(), big);
        let last_progress = c.iter().rev().find_map(|e| match e { SendEvent::Progress { sent, total } => Some((*sent, *total)), _ => None }).unwrap();
        assert_eq!(last_progress.0, last_progress.1);
        let _ = std::fs::remove_dir_all(dst.parent().unwrap());
    }

    #[tokio::test]
    async fn a_declined_introduction_reports_rejected_and_sends_nothing() {
        let (c, _s, dst) = loopback(vec![("x.txt", b"secret".to_vec())], false, false).await;
        assert!(c.iter().any(|e| matches!(e, SendEvent::Rejected)), "{c:?}");
        assert!(!c.iter().any(|e| matches!(e, SendEvent::Progress { .. })));
        assert!(std::fs::read_dir(&dst).unwrap().next().is_none());
        let _ = std::fs::remove_dir_all(dst.parent().unwrap());
    }

    #[tokio::test]
    async fn cancel_stops_the_send_and_the_receiver_cleans_up() {
        let big = vec![7u8; 2_000_000];
        let (c, _s, dst) = loopback(vec![("big.bin", big)], true, true).await;
        assert!(c.iter().any(|e| matches!(e, SendEvent::Failed { error } if error == "cancelled")), "{c:?}");
        assert!(!c.iter().any(|e| matches!(e, SendEvent::Done)));
        let _ = std::fs::remove_dir_all(dst.parent().unwrap());
    }

    #[test]
    fn outfile_rejects_directories_and_guesses_mime() {
        assert!(OutFile::from_path(std::env::temp_dir()).is_err());
        assert_eq!(mime_for("a.JPG"), "image/jpeg");
        assert_eq!(mime_for("noext"), "application/octet-stream");
    }
}
