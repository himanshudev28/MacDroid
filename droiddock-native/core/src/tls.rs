//! TLS for the Mac <-> phone Wi-Fi link.
//!
//! One port (48484) carries both plain `ws://` and `wss://`: the first byte of a
//! connection tells them apart (`0x16` is a TLS ClientHello, an HTTP upgrade
//! starts with `G`). The Mac holds a self-signed ECDSA P-256 identity; phones pin
//! its SubjectPublicKeyInfo (`fp` = base64url(SHA-256(SPKI)), no padding), so
//! re-issuing the certificate never breaks a pin.
//!
//! Wire contract: see the TLS design doc. Pure std/tokio code, no `tokio::spawn`
//! (CLAUDE.md rule 6); BLE is untouched.

use crate::host::{AppHandle, Emitter, Manager};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64URL, Engine};
use hmac::{digest::KeyInit, Hmac, Mac};
use rcgen::PublicKeyData;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::io;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio_rustls::rustls::{self, ServerConfig};
use tokio_rustls::TlsAcceptor;

/// Capability the phone advertises in `hello.caps` (and the Mac echoes, informationally).
pub const CAP: &str = "tls";
/// A plain link from a phone that has already done TLS is refused (downgrade guard).
/// Later release: make this the default for every phone.
const MAX_DEVICES: usize = 16;
const PEEK_TIMEOUT: Duration = Duration::from_secs(5);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const PIN_CONTEXT: &str = "droiddock-tls-pin-v1|";
/// Re-issue the certificate (same key) when it is older than this.
const REISSUE_AFTER: Duration = Duration::from_secs(9 * 365 * 24 * 3600);

// ── Identity ─────────────────────────────────────────────────────────────

pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    pub fp: String,
}

pub fn fp_of_spki(spki: &[u8]) -> String {
    B64URL.encode(Sha256::digest(spki))
}

fn write_atomic(path: &Path, bytes: &[u8], mode: u32) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let tmp = path.with_extension("tmp");
    let _ = std::fs::remove_file(&tmp);
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(mode)
        .open(&tmp)
        .map_err(|e| e.to_string())?;
    f.write_all(bytes).map_err(|e| e.to_string())?;
    f.sync_all().map_err(|e| e.to_string())?;
    drop(f);
    std::fs::rename(&tmp, path).map_err(|e| e.to_string())
}

fn issue_cert(kp: &rcgen::KeyPair) -> Result<Vec<u8>, String> {
    let mut params =
        rcgen::CertificateParams::new(vec!["droiddock.local".to_string()]).map_err(|e| e.to_string())?;
    let mut dn = rcgen::DistinguishedName::new();
    dn.push(rcgen::DnType::CommonName, "DroidDock");
    params.distinguished_name = dn;
    let now = time::OffsetDateTime::now_utc();
    params.not_before = now - time::Duration::days(1);
    params.not_after = now + time::Duration::days(3650);
    let cert = params.self_signed(kp).map_err(|e| e.to_string())?;
    Ok(cert.der().to_vec())
}

fn contains(hay: &[u8], needle: &[u8]) -> bool {
    !needle.is_empty() && hay.windows(needle.len()).any(|w| w == needle)
}

/// Load `key.der` / `cert.der` from `dir`, creating or repairing them. The key is
/// regenerated only when missing or corrupt; the cert is re-issued with the same
/// key when missing, mismatched or about to expire.
pub fn load_or_create(dir: &Path) -> Result<Identity, String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let key_path = dir.join("key.der");
    let cert_path = dir.join("cert.der");

    let existing = std::fs::read(&key_path).ok().and_then(|k| {
        let der = PrivatePkcs8KeyDer::from(k.clone());
        rcgen::KeyPair::try_from(&der).ok().map(|kp| (k, kp))
    });
    let (key_der, kp) = match existing {
        Some(x) => x,
        None => {
            let kp = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(|e| e.to_string())?;
            let k = kp.serialize_der();
            write_atomic(&key_path, &k, 0o600)?;
            let _ = std::fs::remove_file(&cert_path);
            (k, kp)
        }
    };
    // Keep the mode right even for a key written by an older build.
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&key_path, std::fs::Permissions::from_mode(0o600));
    }
    let spki = kp.subject_public_key_info();
    let fresh_enough = std::fs::metadata(&cert_path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.elapsed().ok())
        .is_some_and(|age| age < REISSUE_AFTER);
    let cert_der = match std::fs::read(&cert_path) {
        Ok(c) if fresh_enough && contains(&c, &spki) => c,
        _ => {
            let c = issue_cert(&kp)?;
            // The key is what phones pin, so a cert we can't save is served from
            // memory with the PERSISTED key; the pin stays valid.
            if let Err(e) = write_atomic(&cert_path, &c, 0o644) {
                eprintln!("[tls] couldn't save cert.der ({e}); serving an in-memory certificate");
            }
            c
        }
    };
    Ok(Identity { cert_der, key_der, fp: fp_of_spki(&spki) })
}

fn tls_dir(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_data_dir().ok().map(|d| d.join("tls"))
}

struct Ready {
    identity: Arc<Identity>,
    acceptor: TlsAcceptor,
}

/// English text; also the i18n key Swift looks up via `tr`.
const IDENTITY_WARNING: &str = "Couldn't save this Mac's encryption identity, so the link stays unencrypted.";

struct Slot {
    ready: Option<Arc<Ready>>,
    failed_at: Option<Instant>,
}

static SLOT: Mutex<Slot> = Mutex::new(Slot { ready: None, failed_at: None });
/// After a failed setup, wait this long before trying the disk again.
const RETRY_AFTER: Duration = Duration::from_secs(30);

fn build(app: &AppHandle) -> Result<Ready, String> {
    let dir = tls_dir(app).ok_or("no app data directory")?;
    let id = load_or_create(&dir)?;
    let acceptor = acceptor(&id)?;
    Ok(Ready { identity: Arc::new(id), acceptor })
}

/// The lazily built identity + acceptor. A failure is not cached forever: it is
/// retried (at most every 30 s) on the next pairing-info request or connection.
/// While it fails there is NO identity: no `fp` in the QR, no `tls-pin`, plain
/// links exactly as before TLS existed. We never invent a throwaway key.
fn ready(app: &AppHandle) -> Option<Arc<Ready>> {
    let mut g = SLOT.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(r) = &g.ready {
        return Some(r.clone());
    }
    if g.failed_at.is_some_and(|t| t.elapsed() < RETRY_AFTER) {
        return None;
    }
    match build(app) {
        Ok(r) => {
            let r = Arc::new(r);
            g.ready = Some(r.clone());
            g.failed_at = None;
            Some(r)
        }
        Err(e) => {
            let first = g.failed_at.is_none();
            g.failed_at = Some(Instant::now());
            eprintln!("[tls] identity unavailable ({e}); links stay unencrypted");
            if first {
                let _ = app.emit(
                    "wifi-event",
                    json!({
                        "kind": "bad",
                        "key": IDENTITY_WARNING,
                        "params": {},
                        "text": IDENTITY_WARNING,
                    }),
                );
            }
            None
        }
    }
}

/// The Mac's identity (lazy, process-wide). `None` only if TLS cannot be set up at all.
pub fn identity(app: &AppHandle) -> Option<Arc<Identity>> {
    ready(app).map(|r| r.identity.clone())
}

pub fn acceptor(id: &Identity) -> Result<TlsAcceptor, String> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let cfg = ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(id.cert_der.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(id.key_der.clone())),
        )
        .map_err(|e| e.to_string())?;
    Ok(TlsAcceptor::from(Arc::new(cfg)))
}

// ── Stream wrapper ───────────────────────────────────────────────────────

pub enum LinkStream {
    Plain(TcpStream),
    Tls(Box<tokio_rustls::server::TlsStream<TcpStream>>),
}

impl LinkStream {
    pub fn is_tls(&self) -> bool {
        matches!(self, LinkStream::Tls(_))
    }
}

impl AsyncRead for LinkStream {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            LinkStream::Plain(s) => Pin::new(s).poll_read(cx, buf),
            LinkStream::Tls(s) => Pin::new(s.as_mut()).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for LinkStream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        match self.get_mut() {
            LinkStream::Plain(s) => Pin::new(s).poll_write(cx, buf),
            LinkStream::Tls(s) => Pin::new(s.as_mut()).poll_write(cx, buf),
        }
    }
    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            LinkStream::Plain(s) => Pin::new(s).poll_flush(cx),
            LinkStream::Tls(s) => Pin::new(s.as_mut()).poll_flush(cx),
        }
    }
    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            LinkStream::Plain(s) => Pin::new(s).poll_shutdown(cx),
            LinkStream::Tls(s) => Pin::new(s.as_mut()).poll_shutdown(cx),
        }
    }
    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        match self.get_mut() {
            LinkStream::Plain(s) => Pin::new(s).poll_write_vectored(cx, bufs),
            LinkStream::Tls(s) => Pin::new(s.as_mut()).poll_write_vectored(cx, bufs),
        }
    }
    fn is_write_vectored(&self) -> bool {
        match self {
            LinkStream::Plain(s) => s.is_write_vectored(),
            LinkStream::Tls(s) => s.is_write_vectored(),
        }
    }
}

static LAST_LOG: AtomicU64 = AtomicU64::new(0);

/// Connection-level noise (port scanners, stalled peers) is logged at most once a
/// minute, with no allocation per connection.
fn log_limited(args: std::fmt::Arguments<'_>) {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let last = LAST_LOG.load(Ordering::Relaxed);
    if now.saturating_sub(last) >= 60 && LAST_LOG.compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed).is_ok() {
        eprintln!("{args}");
    }
}

/// Sniff the first byte and, for a ClientHello, finish the TLS handshake.
/// `None` means "nothing to serve": a bare TCP probe (EOF, silent), a stalled
/// peer or a failed handshake.
pub async fn accept(app: &AppHandle, stream: TcpStream) -> Option<LinkStream> {
    match ready(app) {
        Some(r) => accept_with(stream, &r.acceptor, PEEK_TIMEOUT, HANDSHAKE_TIMEOUT).await,
        // TLS unavailable: behave exactly as before this feature.
        None => Some(LinkStream::Plain(stream)),
    }
}

pub async fn accept_with(
    stream: TcpStream,
    acceptor: &TlsAcceptor,
    peek_to: Duration,
    hs_to: Duration,
) -> Option<LinkStream> {
    let mut first = [0u8; 1];
    match tokio::time::timeout(peek_to, stream.peek(&mut first)).await {
        Err(_) => {
            log_limited(format_args!("[tls] peer sent nothing within {peek_to:?}; dropping"));
            None
        }
        // The phone's TCP reachability probe: connect, close. Not worth a log line.
        Ok(Ok(0)) | Ok(Err(_)) => None,
        Ok(Ok(_)) if first[0] == 0x16 => match tokio::time::timeout(hs_to, acceptor.accept(stream)).await {
            Ok(Ok(tls)) => Some(LinkStream::Tls(Box::new(tls))),
            Ok(Err(e)) => {
                log_limited(format_args!("[tls] handshake failed: {e}"));
                None
            }
            Err(_) => {
                log_limited(format_args!("[tls] handshake timed out"));
                None
            }
        },
        Ok(Ok(_)) => Some(LinkStream::Plain(stream)),
    }
}

// ── Pin message ──────────────────────────────────────────────────────────

pub fn pin_sig(token: &str, fp: &str) -> String {
    let mut m = Hmac::<Sha256>::new_from_slice(token.as_bytes()).expect("hmac accepts any key length");
    m.update(PIN_CONTEXT.as_bytes());
    m.update(fp.as_bytes());
    B64URL.encode(m.finalize().into_bytes())
}

pub fn pin_message(token: &str, fp: &str) -> Value {
    json!({ "type": "tls-pin", "fp": fp, "sig": pin_sig(token, fp) })
}

// ── Downgrade guard (tls/devices.json) ──────────────────────────────────

static DEVICES_LOCK: Mutex<()> = Mutex::new(());

fn read_devices(dir: &Path) -> Vec<String> {
    std::fs::read(dir.join("devices.json"))
        .ok()
        .and_then(|b| serde_json::from_slice::<Vec<String>>(&b).ok())
        .unwrap_or_default()
}

pub fn note_device_in(dir: &Path, device_id: Option<&str>) {
    let Some(id) = device_id.filter(|s| !s.is_empty()) else { return };
    let _g = DEVICES_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut list = read_devices(dir);
    if list.last().is_some_and(|l| l == id) {
        return;
    }
    list.retain(|d| d != id);
    list.push(id.to_string());
    while list.len() > MAX_DEVICES {
        list.remove(0);
    }
    if std::fs::create_dir_all(dir).is_ok() {
        if let Ok(b) = serde_json::to_vec(&list) {
            let _ = write_atomic(&dir.join("devices.json"), &b, 0o644);
        }
    }
}

pub fn blocked_in(dir: &Path, device_id: Option<&str>) -> bool {
    let Some(id) = device_id.filter(|s| !s.is_empty()) else { return false };
    let _g = DEVICES_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    read_devices(dir).iter().any(|d| d == id)
}

/// What the hello handler does with a hello.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum HelloAction {
    /// Wrong token: close quietly; nothing is ever sent (no pin, no event).
    Reject,
    /// Blocked plain link from a TLS-capable phone: send `tls-pin`, close 4003 "tls-required".
    PinAndClose4003,
    /// Blocked plain link, but no way to hand over a pin: event, then close 4003.
    BlockClose4003,
    /// Not blocked: welcome as usual (and `tls-pin` after it, if the phone has the cap).
    Normal,
}

/// Pure decision for the hello block. `blocked` = plain link from a device in `devices.json`.
pub fn hello_decision(token_ok: bool, blocked: bool, has_tls_cap: bool, has_identity: bool) -> HelloAction {
    if !token_ok {
        HelloAction::Reject
    } else if !blocked {
        HelloAction::Normal
    } else if has_tls_cap && has_identity {
        HelloAction::PinAndClose4003
    } else {
        HelloAction::BlockClose4003
    }
}

const PROMPT_EVERY: Duration = Duration::from_secs(10 * 60);
static PROMPTED: Mutex<Vec<(String, Instant)>> = Mutex::new(Vec::new());

/// Pure throttle: prompt unless this device was prompted less than `every` ago.
pub fn should_prompt(last: Option<Instant>, now: Instant, every: Duration) -> bool {
    last.is_none_or(|t| now.saturating_duration_since(t) >= every)
}

/// True at most once per device per 10 minutes (a reconnect loop must not spam the prompt).
pub fn prompt_due(device_id: &str) -> bool {
    let now = Instant::now();
    let mut g = PROMPTED.lock().unwrap_or_else(|e| e.into_inner());
    let idx = g.iter().position(|(d, _)| d == device_id);
    if !should_prompt(idx.map(|i| g[i].1), now, PROMPT_EVERY) {
        return false;
    }
    match idx {
        Some(i) => g[i].1 = now,
        None => {
            if g.len() >= MAX_DEVICES {
                g.remove(0);
            }
            g.push((device_id.to_string(), now));
        }
    }
    true
}

/// Remove a phone from the downgrade list (it was forgotten / re-paired).
pub fn forget_device_in(dir: &Path, device_id: Option<&str>) {
    let Some(id) = device_id.filter(|s| !s.is_empty()) else { return };
    let _g = DEVICES_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut list = read_devices(dir);
    let before = list.len();
    list.retain(|d| d != id);
    if list.len() != before {
        if let Ok(b) = serde_json::to_vec(&list) {
            let _ = write_atomic(&dir.join("devices.json"), &b, 0o644);
        }
    }
}

pub fn forget_device(app: &AppHandle, device_id: Option<&str>) {
    if let Some(d) = tls_dir(app) {
        forget_device_in(&d, device_id);
    }
}

/// Record that this phone completed a TLS session.
pub fn note_tls_device(app: &AppHandle, device_id: Option<&str>) {
    if let Some(d) = tls_dir(app) {
        note_device_in(&d, device_id);
    }
}

/// True when a plain-text hello from this phone must be refused.
pub fn plain_blocked(app: &AppHandle, device_id: Option<&str>) -> bool {
    tls_dir(app).is_some_and(|d| blocked_in(&d, device_id))
}

// ── Tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::{SinkExt, StreamExt};
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
    use rustls::pki_types::{ServerName, UnixTime};
    use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio_tungstenite::tungstenite::Message as WsMsg;

    const TOKEN: &str = "acad86c1-6041-408c-bce7-2495dd338711";
    const FP: &str = "7iF8Sz-mw4_5-QGyqC_ptXO0NB7toSxHHpHmz7EXwfs";
    const SIG: &str = "BR0ExWmPcRuTGOn21e-372ynCHAArP6SZdxUzbFbK4k";

    fn tmpdir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("dd-tls-{tag}-{}-{}", std::process::id(), uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// Pins the server key: accepts only a certificate that embeds `spki`.
    #[derive(Debug)]
    struct Pinned {
        spki: Vec<u8>,
        provider: Arc<rustls::crypto::CryptoProvider>,
    }
    impl ServerCertVerifier for Pinned {
        fn verify_server_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            _i: &[CertificateDer<'_>],
            _n: &ServerName<'_>,
            _o: &[u8],
            _t: UnixTime,
        ) -> Result<ServerCertVerified, rustls::Error> {
            if contains(end_entity.as_ref(), &self.spki) {
                Ok(ServerCertVerified::assertion())
            } else {
                Err(rustls::Error::General("pin mismatch".into()))
            }
        }
        fn verify_tls12_signature(
            &self,
            m: &[u8],
            c: &CertificateDer<'_>,
            d: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls12_signature(m, c, d, &self.provider.signature_verification_algorithms)
        }
        fn verify_tls13_signature(
            &self,
            m: &[u8],
            c: &CertificateDer<'_>,
            d: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls13_signature(m, c, d, &self.provider.signature_verification_algorithms)
        }
        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            self.provider.signature_verification_algorithms.supported_schemes()
        }
    }

    fn spki_of(dir: &Path) -> Vec<u8> {
        let k = std::fs::read(dir.join("key.der")).unwrap();
        rcgen::KeyPair::try_from(&PrivatePkcs8KeyDer::from(k)).unwrap().subject_public_key_info()
    }

    fn client(spki: Vec<u8>, tls12_only: bool) -> tokio_rustls::TlsConnector {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let versions: Vec<&'static rustls::SupportedProtocolVersion> =
            if tls12_only { vec![&rustls::version::TLS12] } else { vec![&rustls::version::TLS13, &rustls::version::TLS12] };
        let mut cfg = ClientConfig::builder_with_provider(provider.clone())
            .with_protocol_versions(&versions)
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(Pinned { spki, provider }))
            .with_no_client_auth();
        cfg.enable_sni = false;
        tokio_rustls::TlsConnector::from(Arc::new(cfg))
    }

    /// Echo server on 127.0.0.1:0 using the real accept path.
    async fn echo_server(dir: &Path) -> std::net::SocketAddr {
        let id = load_or_create(dir).unwrap();
        let acc = acceptor(&id).unwrap();
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let acc = acc.clone();
                tokio::spawn(async move {
                    let Some(ls) = accept_with(s, &acc, Duration::from_secs(2), Duration::from_secs(5)).await else { return };
                    let Ok(mut ws) = tokio_tungstenite::accept_async(ls).await else { return };
                    while let Some(Ok(m)) = ws.next().await {
                        if m.is_text() || m.is_binary() {
                            if ws.send(m).await.is_err() {
                                return;
                            }
                        }
                    }
                });
            }
        });
        addr
    }

    #[test]
    fn identity_persists_and_key_is_0600() {
        use std::os::unix::fs::PermissionsExt;
        let d = tmpdir("persist");
        let a = load_or_create(&d).unwrap();
        let b = load_or_create(&d).unwrap();
        assert_eq!(a.fp, b.fp);
        assert_eq!(a.cert_der, b.cert_der);
        assert_eq!(a.fp.len(), 43);
        let mode = std::fs::metadata(d.join("key.der")).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
        // A deleted cert is re-issued with the same key, so the pin survives.
        std::fs::remove_file(d.join("cert.der")).unwrap();
        assert_eq!(load_or_create(&d).unwrap().fp, a.fp);
        // A corrupt key is replaced.
        std::fs::write(d.join("key.der"), b"junk").unwrap();
        assert_ne!(load_or_create(&d).unwrap().fp, a.fp);
    }

    fn read_only(d: &Path, ro: bool) {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(d, std::fs::Permissions::from_mode(if ro { 0o555 } else { 0o755 })).unwrap();
    }

    #[test]
    fn unwritable_dir_gives_no_identity() {
        let d = tmpdir("ro");
        read_only(&d, true);
        let r = load_or_create(&d);
        read_only(&d, false);
        // (root can write anywhere; the check only means something for a normal user)
        if unsafe { libc::geteuid() } != 0 {
            assert!(r.is_err());
        }
    }

    #[test]
    fn cert_write_failure_keeps_the_persisted_key() {
        let d = tmpdir("certro");
        let a = load_or_create(&d).unwrap();
        std::fs::remove_file(d.join("cert.der")).unwrap();
        read_only(&d, true);
        let b = load_or_create(&d);
        read_only(&d, false);
        if unsafe { libc::geteuid() } != 0 {
            let b = b.unwrap();
            assert_eq!(a.fp, b.fp);
            assert!(contains(&b.cert_der, &spki_of(&d)));
            assert!(!d.join("cert.der").exists());
        }
    }

    #[test]
    fn spki_is_in_cert_and_fp_matches() {
        let d = tmpdir("spki");
        let id = load_or_create(&d).unwrap();
        let spki = spki_of(&d);
        assert!(contains(&id.cert_der, &spki));
        assert_eq!(id.fp, B64URL.encode(Sha256::digest(&spki)));
    }

    #[test]
    fn pin_sig_matches_vector() {
        assert_eq!(pin_sig(TOKEN, FP), SIG);
        let m = pin_message(TOKEN, FP);
        assert_eq!(m["type"], "tls-pin");
        assert_eq!(m["fp"], FP);
        assert_eq!(m["sig"], SIG);
    }

    #[tokio::test]
    async fn tls_and_plain_clients_share_one_listener() {
        let d = tmpdir("both");
        let addr = echo_server(&d).await;
        let spki = spki_of(&d);

        let tcp = TcpStream::connect(addr).await.unwrap();
        let name = ServerName::IpAddress(std::net::Ipv4Addr::LOCALHOST.into());
        let tls = client(spki, false).connect(name, tcp).await.unwrap();
        let (mut ws, _) = tokio_tungstenite::client_async("ws://127.0.0.1/", tls).await.unwrap();
        ws.send(WsMsg::text("hello")).await.unwrap();
        assert_eq!(ws.next().await.unwrap().unwrap().into_text().unwrap().as_str(), "hello");
        let big = vec![0xA5u8; 4 * 1024 * 1024];
        ws.send(WsMsg::binary(big.clone())).await.unwrap();
        let back = ws.next().await.unwrap().unwrap().into_data();
        assert_eq!(back.len(), big.len());
        assert!(back.iter().all(|b| *b == 0xA5));

        let (mut plain, _) = tokio_tungstenite::connect_async(format!("ws://{addr}/")).await.unwrap();
        plain.send(WsMsg::text("plain")).await.unwrap();
        assert_eq!(plain.next().await.unwrap().unwrap().into_text().unwrap().as_str(), "plain");
    }

    #[tokio::test]
    async fn tls12_only_client_connects() {
        let d = tmpdir("tls12");
        let addr = echo_server(&d).await;
        let tcp = TcpStream::connect(addr).await.unwrap();
        let name = ServerName::IpAddress(std::net::Ipv4Addr::LOCALHOST.into());
        let tls = client(spki_of(&d), true).connect(name, tcp).await.unwrap();
        assert_eq!(tls.get_ref().1.protocol_version(), Some(rustls::ProtocolVersion::TLSv1_2));
        let (mut ws, _) = tokio_tungstenite::client_async("ws://127.0.0.1/", tls).await.unwrap();
        ws.send(WsMsg::text("x")).await.unwrap();
        assert_eq!(ws.next().await.unwrap().unwrap().into_text().unwrap().as_str(), "x");
    }

    #[tokio::test]
    async fn wrong_pin_fails_handshake() {
        let d = tmpdir("pin-a");
        let other = tmpdir("pin-b");
        load_or_create(&other).unwrap();
        let addr = echo_server(&d).await;
        let tcp = TcpStream::connect(addr).await.unwrap();
        let name = ServerName::IpAddress(std::net::Ipv4Addr::LOCALHOST.into());
        assert!(client(spki_of(&other), false).connect(name, tcp).await.is_err());
    }

    #[tokio::test]
    async fn probe_returns_none_quickly() {
        let d = tmpdir("probe");
        let acc = acceptor(&load_or_create(&d).unwrap()).unwrap();
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let c = tokio::spawn(async move { drop(TcpStream::connect(addr).await.unwrap()) });
        let (s, _) = l.accept().await.unwrap();
        let t = std::time::Instant::now();
        assert!(accept_with(s, &acc, Duration::from_secs(5), Duration::from_secs(5)).await.is_none());
        assert!(t.elapsed() < Duration::from_secs(1));
        c.await.unwrap();
    }

    #[tokio::test]
    async fn silent_client_times_out() {
        let d = tmpdir("silent");
        let acc = acceptor(&load_or_create(&d).unwrap()).unwrap();
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let _keep = TcpStream::connect(addr).await.unwrap();
        let (s, _) = l.accept().await.unwrap();
        let t = std::time::Instant::now();
        assert!(accept_with(s, &acc, Duration::from_millis(200), Duration::from_secs(5)).await.is_none());
        assert!(t.elapsed() < Duration::from_secs(2));
    }

    #[tokio::test]
    async fn non_tls_non_http_goes_plain_and_fails_cleanly() {
        let d = tmpdir("zero");
        let acc = acceptor(&load_or_create(&d).unwrap()).unwrap();
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let mut c = TcpStream::connect(addr).await.unwrap();
        c.write_all(&[0x00, 0x01, 0x02, b'\r', b'\n', b'\r', b'\n']).await.unwrap();
        let (s, _) = l.accept().await.unwrap();
        let ls = accept_with(s, &acc, Duration::from_secs(2), Duration::from_secs(2)).await.unwrap();
        assert!(!ls.is_tls());
        assert!(tokio_tungstenite::accept_async(ls).await.is_err());
        let mut sink = Vec::new();
        let _ = tokio::time::timeout(Duration::from_secs(2), c.read_to_end(&mut sink)).await;
    }

    #[test]
    fn allow_plain_unblocks_the_device() {
        let d = tmpdir("allow");
        note_device_in(&d, Some("old-phone"));
        assert!(blocked_in(&d, Some("old-phone")));
        forget_device_in(&d, Some("old-phone")); // what `tls_allow_plain` does
        assert!(!blocked_in(&d, Some("old-phone")));
    }

    #[test]
    fn prompt_throttle() {
        let t0 = Instant::now();
        let ten = Duration::from_secs(600);
        assert!(should_prompt(None, t0, ten));
        assert!(!should_prompt(Some(t0), t0 + Duration::from_secs(5), ten));
        assert!(should_prompt(Some(t0), t0 + ten, ten));
        assert!(prompt_due("throttle-test-dev"));
        assert!(!prompt_due("throttle-test-dev"));
        assert!(prompt_due("throttle-test-other"));
    }

    #[test]
    fn forget_device_unblocks() {
        let d = tmpdir("forget");
        note_device_in(&d, Some("a"));
        note_device_in(&d, Some("b"));
        forget_device_in(&d, Some("a"));
        assert!(!blocked_in(&d, Some("a")));
        assert!(blocked_in(&d, Some("b")));
        forget_device_in(&d, None);
        forget_device_in(&d, Some("never-there"));
        assert!(blocked_in(&d, Some("b")));
    }

    #[test]
    fn hello_decision_covers_every_combination() {
        for blocked in [false, true] {
            for cap in [false, true] {
                for ident in [false, true] {
                    // A bad token is always a silent reject: never a pin.
                    assert_eq!(hello_decision(false, blocked, cap, ident), HelloAction::Reject);
                }
            }
        }
        assert_eq!(hello_decision(true, true, true, true), HelloAction::PinAndClose4003);
        assert_eq!(hello_decision(true, true, false, true), HelloAction::BlockClose4003);
        assert_eq!(hello_decision(true, true, true, false), HelloAction::BlockClose4003);
        assert_eq!(hello_decision(true, true, false, false), HelloAction::BlockClose4003);
        for cap in [false, true] {
            for ident in [false, true] {
                assert_eq!(hello_decision(true, false, cap, ident), HelloAction::Normal);
            }
        }
    }

    #[test]
    fn device_list_blocks_and_caps() {
        let d = tmpdir("devs");
        assert!(!blocked_in(&d, None));
        assert!(!blocked_in(&d, Some("a")));
        note_device_in(&d, None);
        note_device_in(&d, Some(""));
        assert!(!blocked_in(&d, None));
        note_device_in(&d, Some("a"));
        assert!(blocked_in(&d, Some("a")));
        assert!(!blocked_in(&d, None));
        for i in 0..20 {
            note_device_in(&d, Some(&format!("dev{i}")));
        }
        assert_eq!(read_devices(&d).len(), MAX_DEVICES);
        assert!(!blocked_in(&d, Some("a")));
        assert!(blocked_in(&d, Some("dev19")));
        assert!(!blocked_in(&d, Some("dev0")));
    }

    #[tokio::test]
    #[ignore = "throughput benchmark: run with --release -- --ignored --nocapture"]
    async fn loopback_throughput() {
        const TOTAL: usize = 200 * 1024 * 1024;
        let d = tmpdir("bench");
        let id = load_or_create(&d).unwrap();
        let acc = acceptor(&id).unwrap();
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let srv = tokio::spawn(async move {
            let (s, _) = l.accept().await.unwrap();
            let mut ls = accept_with(s, &acc, Duration::from_secs(5), Duration::from_secs(5)).await.unwrap();
            let mut buf = vec![0u8; 64 * 1024];
            let mut n = 0usize;
            while n < TOTAL {
                let r = ls.read(&mut buf).await.unwrap();
                if r == 0 {
                    break;
                }
                n += r;
            }
            n
        });
        let tcp = TcpStream::connect(addr).await.unwrap();
        let name = ServerName::IpAddress(std::net::Ipv4Addr::LOCALHOST.into());
        let mut tls = client(spki_of(&d), false).connect(name, tcp).await.unwrap();
        let chunk = vec![7u8; 64 * 1024];
        let t = std::time::Instant::now();
        for _ in 0..(TOTAL / chunk.len()) {
            tls.write_all(&chunk).await.unwrap();
        }
        tls.flush().await.unwrap();
        let got = srv.await.unwrap();
        let mbps = got as f64 / 1e6 / t.elapsed().as_secs_f64();
        println!("TLS loopback: {mbps:.0} MB/s");
        assert_eq!(got, TOTAL);
        assert!(mbps > 500.0, "only {mbps:.0} MB/s");
    }
}
