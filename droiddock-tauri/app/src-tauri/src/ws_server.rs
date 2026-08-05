use crate::config::now_ms;
use crate::protocol::Message;
use crate::transfer::{self, TransferRegistry};
use futures_util::{SinkExt, StreamExt};
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot, Mutex, Semaphore};
use tokio_tungstenite::tungstenite::Message as WsMessage;

const AUTH_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(8);
/// Thumbnail replies come back as a single binary frame, but the phone has to
/// decode+downscale the source image first — wifi.js/transfer.js allow 12s.
const THUMB_TIMEOUT: Duration = Duration::from_secs(12);
/// Bounded outbox depth. This IS the file-transfer backpressure mechanism:
/// tokio-tungstenite exposes no `bufferedAmount`, so capping the outbound queue
/// at `OUTBOX_CAP × CHUNK (256 KiB) ≈ 4 MiB` reproduces `transfer.js`'s
/// `MAX_INFLIGHT` window — a full queue back-pressures `send_binary(...).await`.
const OUTBOX_CAP: usize = 16;
/// Phase 19: caps concurrent `mac-fs-pull` transfers a paired phone can have
/// in flight at once — unlike the existing Mac-initiated fs-pull/fs-push
/// paths (one at a time, from a user action), a phone can spam this request,
/// so it needs its own bound rather than trusting the caller's restraint.
const MAX_CONCURRENT_MAC_FS_PULLS: usize = 4;

struct PhoneHandle {
    /// Tier C: present only when both sides negotiated `"enc"` — see
    /// `crate::crypto`. `None` means this session runs in plaintext, which is
    /// the default and the behaviour every build before Tier C had.
    key: Option<crate::crypto::LinkKey>,
    /// The phone's own `hello.name` — display only ("MANUFACTURER MODEL").
    name: String,
    /// A stable per-install identifier the phone generates once and persists.
    /// This, not `name`, keys the photo-sync ledger: two phones of the same
    /// model report an identical `name`, so keying on that made each of them
    /// skip the other's photos as "already synced". Absent on phone builds
    /// older than this fix, where we fall back to `name` and keep the old
    /// (imperfect but unchanged) behaviour.
    device_id: Option<String>,
    caps: Vec<String>,
    outbox: mpsc::Sender<WsMessage>,
    kill: oneshot::Sender<()>,
}

/// The connected phone's display name, read synchronously.
///
/// The async accessors below can't be used from the tray's title setter (not an
/// async context), so this mirrors the last name onto a plain mutex that
/// `emit_status` keeps current.
pub fn last_phone_name(app: &AppHandle) -> Option<String> {
    app.state::<LastPhoneName>().0.lock().unwrap().clone()
}

/// Sync mirror of the connected phone's name — see `last_phone_name`.
#[derive(Default)]
pub struct LastPhoneName(pub std::sync::Mutex<Option<String>>);

/// `(ledger key, legacy name key)` — the pair `photo_sync` needs to migrate a
/// ledger written before the key changed from display name to device id.
/// `None` for the legacy half when the phone never sent a device id, since then
/// the two keys are already the same thing.
pub async fn current_phone_keys(state: &SharedState) -> Option<(String, Option<String>)> {
    let phone = state.phone.lock().await;
    let p = phone.as_ref()?;
    match &p.device_id {
        Some(id) => Some((id.clone(), Some(p.name.clone()))),
        None => Some((p.name.clone(), None)),
    }
}

/// Whether a phone is linked right now — the `if (!phone) return` guard some
/// wifi.js paths (e.g. the clipboard watcher) check before doing any work.
pub async fn is_connected(state: &SharedState) -> bool {
    state.phone.lock().await.is_some()
}

/// Did the connected phone advertise `cap` in its `hello`?
///
/// Every Mac→phone feature added after the protocol froze has to ask this
/// before sending, or an older phone build gets messages it will log as
/// unknown. False when nothing is connected.
pub async fn phone_has_cap(state: &SharedState, cap: &str) -> bool {
    state
        .phone
        .lock()
        .await
        .as_ref()
        .is_some_and(|p| p.caps.iter().any(|c| c == cap))
}

/// The link state as it stands right now.
///
/// `wifi-status` is only *emitted* on a change, so anything that mounts after
/// the phone connected — the Dashboard on its second visit, the menu-bar panel,
/// the status widget — starts at "not linked" and stays there until the link
/// next changes. They call this on mount to get the truth up front.
pub async fn status(state: &SharedState) -> WifiStatus {
    match state.phone.lock().await.as_ref() {
        Some(p) => WifiStatus {
            connected: true,
            phone_name: Some(p.name.clone()),
            caps: p.caps.clone(),
        },
        None => WifiStatus { connected: false, phone_name: None, caps: Vec::new() },
    }
}

pub struct ServerState {
    phone: Mutex<Option<PhoneHandle>>,
    /// reqId → (expected reply family, waiter) for JSON request/response
    /// (sms, contacts, fs-list, …). The family is carried so a reply can only
    /// resolve the request it actually belongs to — see `reply_matches`.
    pending: Mutex<HashMap<u64, (String, oneshot::Sender<Value>)>>,
    req_seq: AtomicU64,
    /// Numeric thumb reqId → waiter, resolved by the KIND_THUMB binary frame.
    pending_thumb: Mutex<HashMap<u32, oneshot::Sender<Result<Vec<u8>, String>>>>,
    thumb_seq: AtomicU32,
    /// transferId → in-flight file transfer, for binary chunk routing (Phase 5).
    pub transfers: TransferRegistry,
    /// Bounds concurrent `mac-fs-pull` transfers (Phase 19) — see
    /// `MAX_CONCURRENT_MAC_FS_PULLS`.
    mac_fs_pull_limit: Semaphore,
    /// reqId → waiter for the phone's `mac-fs-pull-ready` handshake, so chunks
    /// never start before the phone's receiver exists.
    mac_fs_ready: Mutex<HashMap<String, oneshot::Sender<()>>>,
}

impl Default for ServerState {
    fn default() -> Self {
        Self {
            phone: Mutex::default(),
            pending: Mutex::default(),
            req_seq: AtomicU64::default(),
            pending_thumb: Mutex::default(),
            thumb_seq: AtomicU32::default(),
            transfers: TransferRegistry::default(),
            mac_fs_pull_limit: Semaphore::new(MAX_CONCURRENT_MAC_FS_PULLS),
            mac_fs_ready: Mutex::default(),
        }
    }
}

pub type SharedState = Arc<ServerState>;

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct WifiStatus {
    pub connected: bool,
    pub phone_name: Option<String>,
    /// What the connected phone said it can do (`hello.caps`). The frontend
    /// uses this to hide controls an older phone build would silently ignore —
    /// a button that does nothing is worse than no button.
    #[serde(default)]
    pub caps: Vec<String>,
}

/// Fire-and-forget send of a JSON control message (clipboard, reply, dismiss,
/// action-call, media-cmd, …). Mirrors wifi.js's `push()` / `send()`: returns
/// whether the socket was open, never waits for a reply.
pub async fn push(state: &SharedState, value: Value) -> bool {
    send_json(state, value).await
}

/// Send a raw binary frame (file-transfer chunks). Returns false if no phone.
/// Awaits the bounded outbox, so a saturated socket back-pressures the caller.
pub async fn send_binary(state: &SharedState, bytes: Vec<u8>) -> bool {
    let tx = state.phone.lock().await.as_ref().map(|p| p.outbox.clone());
    match tx {
        Some(tx) => tx.send(WsMessage::binary(bytes)).await.is_ok(),
        None => false,
    }
}

/// Ask the phone something and await its `reqId`-tagged reply. Mirrors
/// wifi.js's `request()`. Injects an incrementing `reqId`, resolves when the
/// phone echoes it, rejects on timeout.
pub async fn request(
    state: &SharedState,
    mut body: serde_json::Map<String, Value>,
    timeout: Duration,
) -> Result<Value, String> {
    let req_id = state.req_seq.fetch_add(1, Ordering::Relaxed) + 1;
    let family = reply_family(
        body.get("type").and_then(Value::as_str).unwrap_or_default(),
    );
    body.insert("reqId".into(), Value::from(req_id));

    let (tx, rx) = oneshot::channel();
    state.pending.lock().await.insert(req_id, (family, tx));

    if !send_json(state, Value::Object(body)).await {
        state.pending.lock().await.remove(&req_id);
        return Err("Phone not connected over Wi-Fi".into());
    }

    match tokio::time::timeout(timeout, rx).await {
        Ok(Ok(value)) => Ok(value),
        _ => {
            state.pending.lock().await.remove(&req_id);
            Err("Phone did not respond".into())
        }
    }
}

/// Default-timeout convenience wrapper around [`request`].
pub async fn request_default(
    state: &SharedState,
    body: serde_json::Map<String, Value>,
) -> Result<Value, String> {
    request(state, body, REQUEST_TIMEOUT).await
}

/// Request a photo/video thumbnail. Sends `{type:"photo-thumb", reqId, id, kind}`
/// and awaits the matching KIND_THUMB binary frame (raw JPEG bytes). The `reqId`
/// here lives in its own numeric namespace (`thumb_seq`), exactly like
/// transfer.js's `thumbSeq++ & 0x7fffffff`, and is carried in the 4-byte
/// transferId slot of the binary header — see [`crate::transfer`] for the layout.
pub async fn request_thumb(state: &SharedState, id: i64, kind: &str) -> Result<Vec<u8>, String> {
    // Thumb reqIds live in a disjoint HIGH range (bit30 set, bit31 clear so it
    // stays a positive 32-bit int like transfer.js's `& 0x7fffffff`). This
    // keeps them from ever colliding with `request()`'s low-range reqIds — a
    // `photo-thumb-error` carries a thumb reqId, and without disjoint ranges
    // route_text's JSON `pending` fast-path could misroute it to a real request.
    request_binary_thumb(state, |req_id| {
        json!({ "type": "photo-thumb", "reqId": req_id, "id": id, "kind": kind })
    })
    .await
}

/// Tier B: an app icon, over the same KIND_THUMB binary frame photo thumbnails
/// use. Sharing the transport means app icons inherit the disjoint thumb-reqId
/// namespace, the timeout, and the `*-error` failure path for free.
pub async fn request_app_icon(state: &SharedState, pkg: &str) -> Result<Vec<u8>, String> {
    request_binary_thumb(state, |req_id| {
        json!({ "type": "app-icon", "reqId": req_id, "pkg": pkg })
    })
    .await
}

/// Shared core: allocate a thumb-namespace reqId, register the waiter, send the
/// caller's message, and await the binary frame that answers it.
async fn request_binary_thumb(
    state: &SharedState,
    build: impl FnOnce(u32) -> Value,
) -> Result<Vec<u8>, String> {
    let req_id = (state.thumb_seq.fetch_add(1, Ordering::Relaxed) & 0x3fff_ffff) | 0x4000_0000;
    let (tx, rx) = oneshot::channel();
    state.pending_thumb.lock().await.insert(req_id, tx);

    if !send_json(state, build(req_id)).await {
        state.pending_thumb.lock().await.remove(&req_id);
        return Err("Phone not connected over Wi-Fi".into());
    }

    match tokio::time::timeout(THUMB_TIMEOUT, rx).await {
        Ok(Ok(result)) => result,
        _ => {
            state.pending_thumb.lock().await.remove(&req_id);
            Err("Thumbnail request timed out".into())
        }
    }
}

async fn send_json(state: &SharedState, value: Value) -> bool {
    // Clone the sender AND the session key out from under the lock so we never
    // hold the phone mutex across the (potentially back-pressured) await.
    let session = state
        .phone
        .lock()
        .await
        .as_ref()
        .map(|p| (p.outbox.clone(), p.key.clone()));
    let Some((tx, key)) = session else { return false };

    // Tier C: seal when this session negotiated encryption. A seal failure is
    // treated as a send failure rather than silently falling back to plaintext —
    // quietly downgrading is exactly the bug that makes optional crypto useless.
    let payload = match &key {
        None => value,
        Some(k) => match crate::crypto::seal(k, &value) {
            Ok(envelope) => envelope,
            Err(e) => {
                eprintln!("[ws] encrypt failed, dropping message: {e}");
                return false;
            }
        },
    };
    tx.send(WsMessage::text(payload.to_string())).await.is_ok()
}

fn emit_status(app: &AppHandle, connected: bool, phone_name: Option<String>, caps: Vec<String>) {
    let state = app.state::<LastPhoneName>();
    let mut guard = state.0.lock().unwrap();
    if !connected && guard.is_none() {
        return;
    }
    if connected && *guard == phone_name {
        return;
    }
    *guard = if connected { phone_name.clone() } else { None };
    drop(guard);

    if !connected {
        // Clears the menu-bar title and re-arms the low-battery alert.
        crate::statusbar::on_disconnect(app);
    } else {
        crate::statusbar::refresh_title(app);
    }
    let _ = app.emit("wifi-status", WifiStatus { connected, phone_name, caps });
}

/// Forward a feature message to the frontend as a Tauri event, mirroring
/// wifi.js's `onForward(channel, payload)`.
fn emit_event(app: &AppHandle, channel: &str, payload: &Value) {
    let _ = app.emit(channel, payload.clone());
}

pub async fn run(app: AppHandle, state: SharedState, port: u16) {
    let mut listener = None;
    for _ in 0..10 {
        match TcpListener::bind(("0.0.0.0", port)).await {
            Ok(l) => {
                listener = Some(l);
                break;
            }
            Err(_) => tokio::time::sleep(Duration::from_millis(500)).await,
        }
    }
    let listener = match listener {
        Some(l) => l,
        None => {
            // Losing the port is almost always a second copy of DroidDock still
            // running — /Applications and a dev build, say. Returning quietly
            // left an app that looked completely healthy but could never accept
            // a phone, with the only clue on a stderr nobody reads.
            eprintln!("ws_server: failed to bind 0.0.0.0:{port} after 10 retries");
            emit_event(
                &app,
                "wifi-event",
                &json!({
                    "kind": "bad",
                    "text": format!(
                        "Port {port} is already in use — another copy of DroidDock is probably \
                         running. Quit it and restart, or change the port in Settings."
                    ),
                }),
            );
            return;
        }
    };
    eprintln!("[ws] listening on 0.0.0.0:{port}");
    loop {
        match listener.accept().await {
            Ok((stream, _peer)) => {
                tokio::spawn(handle_connection(app.clone(), state.clone(), stream));
            }
            Err(e) => {
                eprintln!("[ws] accept error: {e}");
                continue;
            }
        }
    }
}

async fn handle_connection(app: AppHandle, state: SharedState, stream: TcpStream) {
    let ws_stream = match tokio_tungstenite::accept_async(stream).await {
        Ok(s) => s,
        Err(e) => {
            // Was a silent `else { return }`. A failed upgrade is the one error
            // that makes the whole app look dead — the TCP connect succeeds, the
            // phone waits for a welcome that never comes, and nothing is logged.
            eprintln!("[ws] WebSocket upgrade failed: {e}");
            return;
        }
    };
    let (mut write, mut read) = ws_stream.split();
    let (outbox_tx, mut outbox_rx) = mpsc::channel::<WsMessage>(OUTBOX_CAP);
    let (kill_tx, mut kill_rx) = oneshot::channel::<()>();
    let mut kill_tx = Some(kill_tx);

    // Funnel every outbound send (welcome, pushes, replies, binary chunks)
    // through one writer task so nothing races on the socket's write half.
    let writer = tokio::spawn(async move {
        while let Some(msg) = outbox_rx.recv().await {
            if write.send(msg).await.is_err() {
                break;
            }
        }
    });

    let mut authed = false;
    let auth_deadline = tokio::time::sleep(AUTH_TIMEOUT);
    tokio::pin!(auth_deadline);

    loop {
        tokio::select! {
            _ = &mut kill_rx => break,
            () = &mut auth_deadline, if !authed => break,
            incoming = read.next() => {
                let Some(Ok(ws_msg)) = incoming else { break };

                // Binary frames: only meaningful post-auth (mirror + transfer).
                if ws_msg.is_binary() {
                    if authed {
                        route_binary(&app, &state, ws_msg.into_data().to_vec()).await;
                    }
                    continue;
                }
                if !ws_msg.is_text() {
                    continue;
                }
                let Ok(text) = ws_msg.into_text() else { continue };
                let Ok(raw): Result<Value, _> = serde_json::from_str(&text) else { continue };

                if !authed {
                    let parsed = serde_json::from_value::<Message>(raw.clone());
                    let Ok(Message::Hello { token, name: hello_name, caps, device_id }) = parsed
                    else {
                        eprintln!("[ws] HANDSHAKE REJECT: first message was not a valid hello: {raw}");
                        tokio::time::sleep(Duration::from_millis(1000)).await;
                        break; // not a valid hello — close, matching wifi.js
                    };
                    if token != live_config(&app).token {
                        eprintln!("[ws] HANDSHAKE REJECT: token mismatch from {hello_name:?}");
                        tokio::time::sleep(Duration::from_millis(1000)).await;
                        break;
                    }
                    eprintln!("[ws] HANDSHAKE OK: {hello_name:?} caps={caps:?} deviceId={device_id:?}");
                    authed = true;

                    // Tier C: encryption engages only when the phone asked for
                    // it AND the user enabled it here. Either side silent →
                    // plaintext, exactly as before, so turning this on can
                    // never strand an older phone build.
                    let cfg = live_config(&app);
                    let want_enc =
                        cfg.encrypt_link && caps.iter().any(|c| c == crate::crypto::CAP);
                    let session_key =
                        want_enc.then(|| crate::crypto::derive(&cfg.token));

                    // Single phone: a new valid hello closes the previous phone's
                    // socket. take+insert under ONE lock acquisition so two
                    // near-simultaneous hellos can't both see "no previous" and
                    // leave a ghost connection whose kill switch is never fired.
                    // Kept for `emit_status` below — `caps` is moved into the
                    // handle, and the frontend needs the same list to decide
                    // which phone-dependent controls to render.
                    let caps_for_status = caps.clone();
                    let prev = std::mem::replace(
                        &mut *state.phone.lock().await,
                        Some(PhoneHandle {
                            name: hello_name.clone(),
                            device_id: device_id.clone(),
                            key: session_key,
                            caps,
                            outbox: outbox_tx.clone(),
                            kill: kill_tx.take().expect("hello only authenticates once per connection"),
                        }),
                    );
                    if let Some(prev) = prev {
                        let _ = prev.kill.send(());
                        // The evicted connection's own cleanup skips abort_all
                        // (its handle is no longer the registered one), so any
                        // transfer still waiting on chunks from the dead socket
                        // must be failed HERE — otherwise a reconnect mid-pull
                        // leaves that pull's command hanging until app restart.
                        // The new connection has no transfers yet, so this only
                        // ever kills the old socket's orphans.
                        state.transfers.abort_all().await;
                    }

                    // Phase 19: unconditional capability advertisement — the
                    // Mac always sends "macfs" once this feature exists in the
                    // binary (no feature flag / half-built gating). Additive
                    // field: an older phone build ignores it safely.
                    let mut welcome_caps: Vec<String> = Vec::new();
                    // Reverse file browsing is now opt-in, matching photo-sync's
                    // posture. Not advertising the cap makes the phone's tab
                    // disappear entirely rather than fail on use.
                    if cfg.mac_fs_enabled {
                        welcome_caps.push("macfs".to_string());
                    }
                    if want_enc {
                        welcome_caps.push(crate::crypto::CAP.to_string());
                    }
                    // Tier D: the phone can't even see the remote-control
                    // surface unless the user switched it on here.
                    if crate::mac_remote::enabled(&app) {
                        welcome_caps.push(crate::mac_remote::CAP.to_string());
                    }
                    // Mac → phone status. Read-only and on by default, so the
                    // cap is really just "this Mac build can do it" — the phone
                    // uses it to decide whether to render the row at all.
                    if crate::mac_info::enabled(&app) {
                        welcome_caps.push(crate::mac_info::CAP.to_string());
                    }
                    // Phase 3: what's playing on this Mac. Same shape as the
                    // row above — the phone hides the player entirely rather
                    // than showing an empty one when this is absent.
                    if crate::mac_media::enabled(&app) {
                        welcome_caps.push(crate::mac_media::CAP.to_string());
                    }
                    // Deliberately sent unsealed: it is the message that tells
                    // the phone encryption is on, so it cannot itself be
                    // encrypted. Everything after it is sealed.
                    let welcome =
                        json!({ "type": "welcome", "name": mac_name(&app), "caps": welcome_caps });
                    if outbox_tx.send(WsMessage::text(welcome.to_string())).await.is_err() {
                        break;
                    }
                    emit_status(&app, true, Some(hello_name.clone()), caps_for_status.clone());
                    // Phone reconnected after a pause → clear the pause and
                    // resume ADB reconnect scanning, matching wifi.js's
                    // `statusCb` clearing `appLinkPaused` on `s.connected`.
                    app.state::<crate::adb::AdbState>().paused.store(false, std::sync::atomic::Ordering::Relaxed);
                    // The phone's Home screen should show the Mac's status and
                    // what it's playing the moment it links, not up to a tick
                    // later. Both are cheap no-ops when the phone didn't
                    // advertise the matching cap, and `mac_media::push_now`
                    // additionally skips the osascript read in that case.
                    {
                        let app2 = app.clone();
                        let state2 = state.clone();
                        tokio::spawn(async move {
                            crate::mac_info::push_now(&app2, &state2).await;
                            crate::mac_media::push_now(&app2, &state2).await;
                        });
                    }
                    // Phase 17: a (re)connect is the retry signal for any edit
                    // that failed its writeback while the phone was away.
                    if let Some(cache) = app.state::<Option<crate::edit_cache::EditCache>>().inner().clone() {
                        let app2 = app.clone();
                        let state2 = state.clone();
                        tokio::spawn(crate::edit_cache::retry_pending(app2, state2, cache));
                    }
                    // Phase 18: a (re)connect is also the "went offline, took
                    // photos, came back" backfill signal — caps-gated so an
                    // un-updated phone (no "photosync" in its hello.caps) is
                    // left completely alone even with the feature enabled.
                    let has_photosync = state
                        .phone
                        .lock()
                        .await
                        .as_ref()
                        .is_some_and(|p| p.caps.iter().any(|c| c == "photosync"));
                    if has_photosync {
                        let cfg = live_config(&app);
                        if cfg.photo_sync_enabled {
                            if let Some(photo) = app.state::<Option<crate::photo_sync::PhotoSync>>().inner().clone() {
                                let app2 = app.clone();
                                let state2 = state.clone();
                                // Was `hello_name` — that's the *display* name,
                                // which is what the ledger used to be keyed on
                                // and is exactly the collision this fixed.
                                let (device_key, legacy) = match &device_id {
                                    Some(id) => (id.clone(), Some(hello_name.clone())),
                                    None => (hello_name.clone(), None),
                                };
                                tokio::spawn(crate::photo_sync::check(
                                    app2, state2, photo, cfg, device_key, legacy,
                                ));
                            }
                        }
                    }
                    continue;
                }

                route_text(&app, &state, raw).await;
            }
        }
    }

    writer.abort();
    let mut phone = state.phone.lock().await;
    if phone.as_ref().is_some_and(|p| p.outbox.same_channel(&outbox_tx)) {
        *phone = None;
        drop(phone);
        // Fail any in-flight file transfers so their commands don't hang.
        state.transfers.abort_all().await;
        emit_status(&app, false, None, Vec::new());
    }
}

/// Dispatch one authenticated JSON message. Mirrors wifi.js's post-auth
/// `ws.on('message')` switch: first the reqId reply table, then per-type
/// handling / forwarding to the frontend.
async fn route_text(app: &AppHandle, state: &SharedState, raw: Value) {
    // Tier C: unwrap first, so every downstream branch keeps seeing plaintext
    // and no feature had to learn about encryption. An envelope that fails to
    // open is dropped rather than routed — a bad tag means tampering or a key
    // mismatch, and neither is something to act on.
    let raw = if raw.get("type").and_then(Value::as_str) == Some("enc") {
        let key = state.phone.lock().await.as_ref().and_then(|p| p.key.clone());
        match key {
            Some(k) => match crate::crypto::open(&k, &raw) {
                Ok(inner) => inner,
                Err(e) => {
                    eprintln!("[ws] dropping undecryptable message: {e}");
                    return;
                }
            },
            None => {
                eprintln!("[ws] dropping encrypted message on a plaintext session");
                return;
            }
        }
    } else {
        raw
    };

    // 1. request/response replies (wifi.js: msg.reqId && pending.has(...)).
    //    NOTE: TransferManager.kt reads reqId via `optString` and echoes it back
    //    as a STRING (e.g. "5"), whereas ConnectionManager.respond() echoes it
    //    numeric. So fs-pull-begin / fs-push replies arrive with a string reqId
    //    and must be parsed back — otherwise transfers never match their
    //    pending request. (Phone-initiated `phone-push` uses a non-numeric
    //    reqId like "pp1"; that fails the parse and correctly falls through to
    //    type routing → transfer::on_control.)
    if let Some(req_id) = raw.get("reqId").and_then(reqid_key) {
        let ty = raw.get("type").and_then(Value::as_str).unwrap_or("");
        let mut pending = state.pending.lock().await;
        // Only resolve when the reply belongs to the request family that
        // allocated this reqId. Without that check, a phone could craft a
        // message with a small numeric reqId and have it delivered as the
        // answer to an unrelated in-flight request (contacts, sms, fs-list…),
        // which the whole feature layer above would then trust.
        let matches = pending
            .get(&req_id)
            .is_some_and(|(family, _)| reply_matches(family, ty));
        if matches {
            if let Some((_, tx)) = pending.remove(&req_id) {
                drop(pending);
                let _ = tx.send(raw);
                return;
            }
        }
        drop(pending);
    }

    let msg_type = raw.get("type").and_then(Value::as_str).unwrap_or("");
    match msg_type {
        // Clipboard: write to the Mac pasteboard through the echo-loop guard.
        "clipboard" => crate::clipboard::on_incoming(app, &raw),

        // Notifications: native banner (+ dedupe) and in-app panel list.
        "notification" => {
            // wifi.js: onForward('notification', { ...msg, time: msg.when || Date.now() }).
            let mut m = raw.clone();
            if m.get("time").is_none() {
                let when = m
                    .get("when")
                    .and_then(Value::as_i64)
                    .unwrap_or_else(now_ms);
                if let Value::Object(map) = &mut m {
                    map.insert("time".into(), Value::from(when));
                }
            }
            crate::notifications::on_notification(app, &m);
            // Backfill is a replay of what's already on the phone, not new
            // arrivals — counting it would badge the menu bar on every connect.
            if m.get("backfill").and_then(Value::as_bool) != Some(true) {
                crate::statusbar::on_notification(app);
            }
            emit_event(app, "notification", &m);
        }
        "notification-removed" => {
            let key = raw.get("key").cloned().unwrap_or(Value::Null);
            crate::notifications::on_removed(app, key.as_str());
            emit_event(app, "notification-removed", &json!({ "key": key }));
        }
        // The phone got a tap/swipe/nav press it cannot perform. Mirror video
        // keeps flowing in that state, so without this the Mac looks like it
        // simply isn't sending — the phone throttles these to one per 5s.
        //
        // Two causes, opposite fixes, so `reason` picks the wording: the
        // accessibility service is off (system Settings) or screen control is
        // switched off while the service still runs for auto-clipboard
        // (DroidDock's own Settings). Absent `reason` means an older phone
        // build — fall back to naming both places rather than guessing.
        "control-unavailable" => {
            let text = match raw.get("reason").and_then(Value::as_str) {
                Some("disabled") => "Phone screen control is switched off — turn it back on in DroidDock on your phone (Settings › Permissions › Mac screen control)",
                Some("service") => "Phone screen control needs Accessibility — enable DroidDock in your phone's Settings › Accessibility",
                // Lock has a second route that needs no accessibility service
                // at all, which is the answer for anyone keeping it off.
                Some("lock-needs-admin") => "Can't lock the phone — grant \"Lock Without Accessibility\" in DroidDock on your phone (Settings › Permissions), or turn its Accessibility service back on",
                _ => "Phone screen control is off — check DroidDock on your phone (Settings › Permissions)",
            };
            emit_event(app, "wifi-event", &json!({ "kind": "bad", "text": text }));
        }

        // wifi.js turns reply-result into a wifi-event toast (there is no
        // renderer 'reply-result' channel): ok → "Reply sent from Mac".
        "reply-result" => {
            let ok = raw.get("ok").and_then(Value::as_bool).unwrap_or(false);
            let ev = if ok {
                json!({ "kind": "ok", "text": "Reply sent from Mac" })
            } else {
                json!({ "kind": "bad", "text": "Reply failed on phone" })
            };
            emit_event(app, "wifi-event", &ev);
        }

        // Incoming call: only `ringing` surfaces (wifi.js showCall early-returns
        // otherwise), with a Mac-side key/time stamp added like wifi.js does
        // (`key: call-${Date.now()}` — unique per ring, so two calls make two
        // NOTIFS-panel cards instead of collapsing into one).
        "call" => {
            if raw.get("state").and_then(Value::as_str) == Some("ringing") {
                let mut m = raw.clone();
                if let Value::Object(map) = &mut m {
                    let ts = now_ms();
                    map.insert("key".into(), Value::from(format!("call-{ts}")));
                    map.insert("time".into(), Value::from(ts));
                }
                crate::notifications::on_call(app, &m);
                emit_event(app, "call", &m);
            } else {
                // idle/ended: clear any live call alert + tell the UI to hide.
                crate::notifications::on_call_cleared(app);
                emit_event(app, "call", &raw);
            }
        }

        // Media now-playing metadata push.
        "media" => {
            // Emit the *merged* message, not the raw one: `on_media` re-attaches
            // the artwork the phone only sends on a track change, so every
            // listener gets a complete picture rather than one that depends on
            // when it happened to subscribe.
            let merged = crate::statusbar::on_media(app, &raw);
            emit_event(app, "media", &merged)
        }

        // Device info (battery/model/etc) — forwarded verbatim.
        "device-info" => {
            // Drives the menu-bar title and the low-battery alert.
            crate::statusbar::on_device_info(app, &raw);
            emit_event(app, "device-info", &raw)
        }

        // SMS content changed on the phone — bare ping, UI refetches.
        "sms-changed" => emit_event(app, "sms-changed", &json!({})),

        // Phase 18: MediaStore changed on the phone. No item IDs on the wire
        // by design (see BridgeService.kt) — the Mac re-diffs its own ledger
        // against a fresh photos-list on every fire. Always safe to receive
        // even if the feature is off; just a no-op then.
        "photos-changed" => {
            let cfg = live_config(app);
            if cfg.photo_sync_enabled {
                if let (Some(photo), Some((device_key, legacy))) = (
                    app.state::<Option<crate::photo_sync::PhotoSync>>().inner().clone(),
                    current_phone_keys(state).await,
                ) {
                    let app2 = app.clone();
                    let state2 = state.clone();
                    tokio::spawn(crate::photo_sync::check(
                        app2, state2, photo, cfg, device_key, legacy,
                    ));
                }
            }
        }

        // Phase 19: reverse file browsing — phone-originated request/reply,
        // NOT routed through the `pending`/`request()` table above: that
        // mechanism is for Mac-initiated requests awaiting a phone reply, but
        // here the PHONE initiated, so its `reqId` is just an opaque string we
        // echo back verbatim. It lives in its own namespace, disjoint from
        // both `req_seq`'s numeric reqIds and the `phone-push*` string reqIds
        // — never look it up in `state.pending`.
        "mac-fs-list" => {
            let Some(req_id) = raw.get("reqId").and_then(Value::as_str).map(str::to_string) else {
                return;
            };
            let cfg = live_config(app);
            if !cfg.mac_fs_enabled || cfg.is_paused() {
                let _ = send_json(
                    state,
                    json!({ "type": "mac-fs-list-error", "reqId": req_id,
                            "error": "Mac file browsing is turned off" }),
                )
                .await;
                return;
            }
            let path = raw.get("path").and_then(Value::as_str).unwrap_or("").to_string();
            let roots = cfg.mac_fs_roots;
            // Spawned, not awaited inline: a slow or hung root would otherwise
            // stall the whole inbound read loop for every other feature.
            let state2 = state.clone();
            tokio::spawn(async move {
            match if path.is_empty() {
                Ok(crate::mac_fs::list_roots(&roots))
            } else {
                crate::mac_fs::list(&roots, &path).await
            } {
                Ok(entries) => {
                    let _ = send_json(
                        &state2,
                        json!({ "type": "mac-fs-list-result", "reqId": req_id, "entries": entries }),
                    )
                    .await;
                }
                Err(error) => {
                    let _ = send_json(
                        &state2,
                        json!({ "type": "mac-fs-list-error", "reqId": req_id, "error": error }),
                    )
                    .await;
                }
            }
            });
        }
        "mac-fs-pull" => {
            let Some(req_id) = raw.get("reqId").and_then(Value::as_str).map(str::to_string) else {
                return;
            };
            let cfg = live_config(app);
            if !cfg.mac_fs_enabled || cfg.is_paused() {
                let _ = send_json(
                    state,
                    json!({ "type": "mac-fs-pull-error", "reqId": req_id,
                            "error": "Mac file browsing is turned off" }),
                )
                .await;
                return;
            }
            let path = raw.get("path").and_then(Value::as_str).unwrap_or("").to_string();
            let roots = cfg.mac_fs_roots;
            let app2 = app.clone();
            let state2 = state.clone();
            tokio::spawn(async move {
                // A phone can spam this request unlike the Mac-initiated
                // fs-pull/fs-push paths (one at a time, from a user click) —
                // reject outright over the cap instead of queueing unbounded
                // waiters, so a malicious/broken client can't grow memory
                // just by firing requests faster than they're served.
                let permit = match state2.mac_fs_pull_limit.try_acquire() {
                    Ok(p) => p,
                    Err(_) => {
                        let _ = send_json(
                            &state2,
                            json!({ "type": "mac-fs-pull-error", "reqId": req_id, "error": "Too many concurrent Mac file transfers — try again shortly" }),
                        )
                        .await;
                        return;
                    }
                };
                let req_id2 = req_id.clone();
                if let Err(error) =
                    crate::mac_fs::pull_to_phone(app2, state2.clone(), &roots, path, req_id2).await
                {
                    let _ = send_json(
                        &state2,
                        json!({ "type": "mac-fs-pull-error", "reqId": req_id, "error": error }),
                    )
                    .await;
                }
                drop(permit);
            });
        }

        // Transfer control that is keyed by transferId (not reqId): done/error
        // for pulls, push acks, phone-initiated pushes, cancels, thumb errors.
        other
            if other.starts_with("fs-")
                || other.starts_with("phone-push")
                || other == "photo-thumb-error"
                // Tier B app icons share the thumb transport, so they share its
                // failure path too.
                || other == "app-icon-error" =>
        {
            transfer::on_control(app, state, &raw).await;
        }

        // JSON ping/pong kept for parity with wifi.js (the live Android app
        // uses OkHttp protocol-level pings instead, so this is never observed).
        "ping" => {
            let _ = send_json(state, json!({ "type": "pong" })).await;
        }
        // Tier C: the echo of our own link-quality probe. `t` is the timestamp
        // we sent, so the phone needs no synchronised clock to answer usefully.
        "pong" => app.state::<crate::link_quality::LinkQuality>().on_pong(&raw),
        // Tier D: phone → Mac input. `mac_remote` re-checks the enable flag
        // itself rather than trusting that the caps advert was ever sent.
        "remote" => crate::mac_remote::on_message(app, &raw),
        // The phone's receiver for a `mac-fs-pull` is now registered; releasing
        // this lets `mac_fs::pull_to_phone` start streaming chunks.
        "mac-fs-pull-ready" => {
            if let Some(req_id) = raw.get("reqId").and_then(Value::as_str) {
                if let Some(tx) = state.mac_fs_ready.lock().await.remove(req_id) {
                    let _ = tx.send(());
                }
            }
        }

        // Screen mirroring / camera streaming (Phase 11): forwarded to the
        // pop-out mirror window only, mirroring index.js's forwardCb (which
        // never sends these three to the main window either).
        "mirror-started" => crate::mirror::on_started(app, &raw),
        "mirror-stopped" => crate::mirror::on_stopped(app, &raw),
        "mirror-error" => crate::mirror::on_error(app, &raw),

        // Phone-initiated pause (Phase 14): the phone closes its own socket
        // right after sending this, so its ONLY practical Mac-side effect is
        // stopping the ADB mDNS/tcp reconnect scan — mirrors wifi.js's
        // `forwardCb('pause', ...)` (which also ignores `until`; the Mac just
        // reacts to the next successful hello, above, rather than doing
        // timestamp math). "resume" is ported for fidelity but is dead code
        // in the shipped Android app — `ConnectionManager.resume()` sends no
        // message at all, only stops pausing internally.
        "pause" => {
            app.state::<crate::adb::AdbState>().paused.store(true, std::sync::atomic::Ordering::Relaxed);
            emit_event(app, "wifi-event", &json!({ "kind": "info", "text": "Phone paused the link" }));
        }
        "resume" => {
            app.state::<crate::adb::AdbState>().paused.store(false, std::sync::atomic::Ordering::Relaxed);
        }

        // Nothing else is wired. Silently ignored, exactly like wifi.js's
        // final no-op else branch.
        _ => {}
    }
}

/// Route an inbound binary frame by its leading kind byte. Layout (see
/// [`crate::transfer`]): `[1B kind][4B transferId/reqId BE][4B seq BE][payload]`.
/// Kind 3 is the one exception — a fixed 2-byte `[3][flags]` mirror-frame
/// header (see `ConnectionManager.kt::sendVideo`), not the 9-byte transfer
/// header, so it's peeled off before the transfer-shaped frames below.
async fn route_binary(app: &AppHandle, state: &SharedState, buf: Vec<u8>) {
    if buf.first() == Some(&3) {
        crate::mirror::on_frame(app, &buf);
        return;
    }
    if buf.len() < transfer::HEADER {
        return;
    }
    let kind = buf[0];
    let id = u32::from_be_bytes([buf[1], buf[2], buf[3], buf[4]]);

    match kind {
        transfer::KIND_THUMB => {
            // The transferId slot IS the thumb reqId for KIND_THUMB frames.
            if let Some(tx) = state.pending_thumb.lock().await.remove(&id) {
                let _ = tx.send(Ok(buf[transfer::HEADER..].to_vec()));
            }
        }
        transfer::KIND_DATA => transfer::on_chunk(state, id, &buf).await,
        _ => {}
    }
}

/// Resolve a pending thumb request with an error (called from transfer control
/// on `photo-thumb-error`).
pub async fn fail_thumb(state: &SharedState, req_id: u32, error: String) {
    if let Some(tx) = state.pending_thumb.lock().await.remove(&req_id) {
        let _ = tx.send(Err(error));
    }
}

/// Extract a pending-table reqId from a reply, accepting either a JSON number
/// or a numeric JSON string (the transfer path echoes reqId as a string).
/// How long to wait for the phone's `mac-fs-pull-ready`. Generous, because it
/// only has to cover the phone registering a receiver — and on expiry we stream
/// anyway, so an older phone build that never sends it still works exactly as
/// it did before, race and all, rather than hanging forever.
const MAC_FS_READY_TIMEOUT: Duration = Duration::from_secs(5);

/// Register interest in a `mac-fs-pull-ready` before announcing the transfer.
/// The returned future resolves when the phone acks, or on timeout.
pub async fn arm_mac_fs_ready(
    state: &SharedState,
    req_id: &str,
) -> impl std::future::Future<Output = ()> {
    let (tx, rx) = oneshot::channel();
    state.mac_fs_ready.lock().await.insert(req_id.to_string(), tx);
    let state = state.clone();
    let req_id = req_id.to_string();
    async move {
        let _ = tokio::time::timeout(MAC_FS_READY_TIMEOUT, rx).await;
        state.mac_fs_ready.lock().await.remove(&req_id);
    }
}

/// The reply family a request type expects back. `fs-push-begin` is answered
/// with `fs-push`/`fs-push-error`, so the trailing `-begin` is stripped;
/// everything else is answered with its own type or a `<type>-…` variant.
fn reply_family(request_type: &str) -> String {
    request_type
        .strip_suffix("-begin")
        .unwrap_or(request_type)
        .to_string()
}

/// Whether `reply_type` is a legitimate answer for a request of `family`.
fn reply_matches(family: &str, reply_type: &str) -> bool {
    if family.is_empty() {
        return false;
    }
    reply_type == family
        || (reply_type.len() > family.len()
            && reply_type.starts_with(family)
            && reply_type.as_bytes()[family.len()] == b'-')
}

fn reqid_key(v: &Value) -> Option<u64> {
    v.as_u64().or_else(|| v.as_str().and_then(|s| s.parse::<u64>().ok()))
}

fn live_config(app: &AppHandle) -> crate::config::Config {
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

pub fn mac_name(app: &AppHandle) -> String {
    live_config(app)
        .device_name
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| {
            gethostname::gethostname()
                .to_string_lossy()
                .trim_end_matches(".local")
                .to_string()
        })
}

#[cfg(test)]
mod tests {
    use super::{reply_family, reply_matches};

    #[test]
    fn push_begin_is_answered_by_the_push_family() {
        // `fs-push-begin` is answered with `fs-push` / `fs-push-error`, so the
        // trailing `-begin` has to be stripped or those replies never match.
        assert_eq!(reply_family("fs-push-begin"), "fs-push");
        assert!(reply_matches("fs-push", "fs-push"));
        assert!(reply_matches("fs-push", "fs-push-error"));
    }

    #[test]
    fn pull_is_answered_by_its_own_variants() {
        assert_eq!(reply_family("fs-pull"), "fs-pull");
        assert!(reply_matches("fs-pull", "fs-pull-begin"));
        assert!(reply_matches("fs-pull", "fs-pull-error"));
    }

    #[test]
    fn plain_request_types_match_themselves() {
        for ty in ["contacts", "sms-threads", "photos-list", "wallpaper", "apps-list", "fs-list"] {
            assert!(reply_matches(&reply_family(ty), ty), "{ty} should match itself");
        }
    }

    #[test]
    fn a_crafted_reply_cannot_hijack_an_unrelated_request() {
        // The pre-existing hole: any message carrying a small numeric reqId
        // used to resolve whatever request happened to hold that id.
        assert!(!reply_matches("contacts", "clipboard"));
        assert!(!reply_matches("contacts", "notification"));
        assert!(!reply_matches("fs-list", "fs-delete"));
        // Prefix-but-not-a-family-member must not match either: `fs-pull` is
        // not a reply to `fs-push`, and `contacts-x` is a different type from
        // a shared-prefix string with no separator.
        assert!(!reply_matches("fs-push", "fs-pull"));
        assert!(!reply_matches("sms", "smsthreads"));
    }

    #[test]
    fn an_untyped_request_can_never_be_resolved() {
        // No type on the way out means nothing can be trusted on the way back.
        assert!(!reply_matches("", "anything"));
        assert!(!reply_matches("", ""));
    }
}
