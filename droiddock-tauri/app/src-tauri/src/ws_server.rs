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
    /// The phone's own `hello.name` — used as the photo-sync ledger's device
    /// key (see `photo_sync.rs`) since it's the only per-phone identifier
    /// available on every connection, ADB-paired or not.
    name: String,
    caps: Vec<String>,
    outbox: mpsc::Sender<WsMessage>,
    kill: oneshot::Sender<()>,
}

/// The currently connected phone's `hello.name`, or `None` if nothing's
/// linked right now. Used to key the photo-sync ledger by device.
pub async fn current_phone_name(state: &SharedState) -> Option<String> {
    state.phone.lock().await.as_ref().map(|p| p.name.clone())
}

/// Whether a phone is linked right now — the `if (!phone) return` guard some
/// wifi.js paths (e.g. the clipboard watcher) check before doing any work.
pub async fn is_connected(state: &SharedState) -> bool {
    state.phone.lock().await.is_some()
}

pub struct ServerState {
    phone: Mutex<Option<PhoneHandle>>,
    /// reqId → waiter, for JSON request/response (sms, contacts, fs-list, …).
    pending: Mutex<HashMap<u64, oneshot::Sender<Value>>>,
    req_seq: AtomicU64,
    /// Numeric thumb reqId → waiter, resolved by the KIND_THUMB binary frame.
    pending_thumb: Mutex<HashMap<u32, oneshot::Sender<Result<Vec<u8>, String>>>>,
    thumb_seq: AtomicU32,
    /// transferId → in-flight file transfer, for binary chunk routing (Phase 5).
    pub transfers: TransferRegistry,
    /// Bounds concurrent `mac-fs-pull` transfers (Phase 19) — see
    /// `MAX_CONCURRENT_MAC_FS_PULLS`.
    mac_fs_pull_limit: Semaphore,
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
        }
    }
}

pub type SharedState = Arc<ServerState>;

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct WifiStatus {
    pub connected: bool,
    pub phone_name: Option<String>,
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
    body.insert("reqId".into(), Value::from(req_id));

    let (tx, rx) = oneshot::channel();
    state.pending.lock().await.insert(req_id, tx);

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
    let req_id = (state.thumb_seq.fetch_add(1, Ordering::Relaxed) & 0x3fff_ffff) | 0x4000_0000;
    let (tx, rx) = oneshot::channel();
    state.pending_thumb.lock().await.insert(req_id, tx);

    let msg = json!({ "type": "photo-thumb", "reqId": req_id, "id": id, "kind": kind });
    if !send_json(state, msg).await {
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
    // Clone the sender out from under the lock so we never hold the phone mutex
    // across the (potentially back-pressured) bounded-channel await.
    let tx = state.phone.lock().await.as_ref().map(|p| p.outbox.clone());
    match tx {
        Some(tx) => tx.send(WsMessage::text(value.to_string())).await.is_ok(),
        None => false,
    }
}

fn emit_status(app: &AppHandle, connected: bool, phone_name: Option<String>) {
    let _ = app.emit("wifi-status", WifiStatus { connected, phone_name });
}

/// Forward a feature message to the frontend as a Tauri event, mirroring
/// wifi.js's `onForward(channel, payload)`.
fn emit_event(app: &AppHandle, channel: &str, payload: &Value) {
    let _ = app.emit(channel, payload.clone());
}

pub async fn run(app: AppHandle, state: SharedState, port: u16) {
    let listener = match TcpListener::bind(("0.0.0.0", port)).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("ws_server: failed to bind 0.0.0.0:{port}: {e}");
            return;
        }
    };
    loop {
        let Ok((stream, _)) = listener.accept().await else {
            continue;
        };
        tauri::async_runtime::spawn(handle_connection(app.clone(), state.clone(), stream));
    }
}

async fn handle_connection(app: AppHandle, state: SharedState, stream: TcpStream) {
    let Ok(ws_stream) = tokio_tungstenite::accept_async(stream).await else {
        return;
    };
    let (mut write, mut read) = ws_stream.split();
    let (outbox_tx, mut outbox_rx) = mpsc::channel::<WsMessage>(OUTBOX_CAP);
    let (kill_tx, mut kill_rx) = oneshot::channel::<()>();
    let mut kill_tx = Some(kill_tx);

    // Funnel every outbound send (welcome, pushes, replies, binary chunks)
    // through one writer task so nothing races on the socket's write half.
    let writer = tauri::async_runtime::spawn(async move {
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
                    let Ok(Message::Hello { token, name: hello_name, caps }) =
                        serde_json::from_value::<Message>(raw)
                    else {
                        break; // not a valid hello — close, matching wifi.js
                    };
                    if token != live_config(&app).token {
                        break;
                    }
                    authed = true;

                    // Single phone: a new valid hello closes the previous phone's
                    // socket. take+insert under ONE lock acquisition so two
                    // near-simultaneous hellos can't both see "no previous" and
                    // leave a ghost connection whose kill switch is never fired.
                    let prev = std::mem::replace(
                        &mut *state.phone.lock().await,
                        Some(PhoneHandle {
                            name: hello_name.clone(),
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
                    let welcome = json!({ "type": "welcome", "name": mac_name(&app), "caps": ["macfs"] });
                    if outbox_tx.send(WsMessage::text(welcome.to_string())).await.is_err() {
                        break;
                    }
                    emit_status(&app, true, Some(hello_name.clone()));
                    // Phone reconnected after a pause → clear the pause and
                    // resume ADB reconnect scanning, matching wifi.js's
                    // `statusCb` clearing `appLinkPaused` on `s.connected`.
                    app.state::<crate::adb::AdbState>().paused.store(false, std::sync::atomic::Ordering::Relaxed);
                    // Phase 17: a (re)connect is the retry signal for any edit
                    // that failed its writeback while the phone was away.
                    if let Some(cache) = app.state::<Option<crate::edit_cache::EditCache>>().inner().clone() {
                        let app2 = app.clone();
                        let state2 = state.clone();
                        tauri::async_runtime::spawn(crate::edit_cache::retry_pending(app2, state2, cache));
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
                                let device_key = hello_name.clone();
                                tauri::async_runtime::spawn(crate::photo_sync::check(app2, state2, photo, cfg, device_key));
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
        emit_status(&app, false, None);
    }
}

/// Dispatch one authenticated JSON message. Mirrors wifi.js's post-auth
/// `ws.on('message')` switch: first the reqId reply table, then per-type
/// handling / forwarding to the frontend.
async fn route_text(app: &AppHandle, state: &SharedState, raw: Value) {
    // 1. request/response replies (wifi.js: msg.reqId && pending.has(...)).
    //    NOTE: TransferManager.kt reads reqId via `optString` and echoes it back
    //    as a STRING (e.g. "5"), whereas ConnectionManager.respond() echoes it
    //    numeric. So fs-pull-begin / fs-push replies arrive with a string reqId
    //    and must be parsed back — otherwise transfers never match their
    //    pending request. (Phone-initiated `phone-push` uses a non-numeric
    //    reqId like "pp1"; that fails the parse and correctly falls through to
    //    type routing → transfer::on_control.)
    if let Some(req_id) = raw.get("reqId").and_then(reqid_key) {
        if let Some(tx) = state.pending.lock().await.remove(&req_id) {
            let _ = tx.send(raw);
            return;
        }
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
            emit_event(app, "notification", &m);
        }
        "notification-removed" => {
            let key = raw.get("key").cloned().unwrap_or(Value::Null);
            crate::notifications::on_removed(app, key.as_str());
            emit_event(app, "notification-removed", &json!({ "key": key }));
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
        "media" => emit_event(app, "media", &raw),

        // Device info (battery/model/etc) — forwarded verbatim.
        "device-info" => emit_event(app, "device-info", &raw),

        // SMS content changed on the phone — bare ping, UI refetches.
        "sms-changed" => emit_event(app, "sms-changed", &json!({})),

        // Phase 18: MediaStore changed on the phone. No item IDs on the wire
        // by design (see BridgeService.kt) — the Mac re-diffs its own ledger
        // against a fresh photos-list on every fire. Always safe to receive
        // even if the feature is off; just a no-op then.
        "photos-changed" => {
            let cfg = live_config(app);
            if cfg.photo_sync_enabled {
                if let (Some(photo), Some(device_key)) = (
                    app.state::<Option<crate::photo_sync::PhotoSync>>().inner().clone(),
                    current_phone_name(state).await,
                ) {
                    let app2 = app.clone();
                    let state2 = state.clone();
                    tauri::async_runtime::spawn(crate::photo_sync::check(app2, state2, photo, cfg, device_key));
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
            let path = raw.get("path").and_then(Value::as_str).unwrap_or("").to_string();
            let roots = live_config(app).mac_fs_roots;
            match crate::mac_fs::list(&roots, &path).await {
                Ok(entries) => {
                    let _ = send_json(
                        state,
                        json!({ "type": "mac-fs-list-result", "reqId": req_id, "entries": entries }),
                    )
                    .await;
                }
                Err(error) => {
                    let _ = send_json(
                        state,
                        json!({ "type": "mac-fs-list-error", "reqId": req_id, "error": error }),
                    )
                    .await;
                }
            }
        }
        "mac-fs-pull" => {
            let Some(req_id) = raw.get("reqId").and_then(Value::as_str).map(str::to_string) else {
                return;
            };
            let path = raw.get("path").and_then(Value::as_str).unwrap_or("").to_string();
            let roots = live_config(app).mac_fs_roots;
            let app2 = app.clone();
            let state2 = state.clone();
            tauri::async_runtime::spawn(async move {
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
                || other == "photo-thumb-error" =>
        {
            transfer::on_control(app, state, &raw).await;
        }

        // JSON ping/pong kept for parity with wifi.js (the live Android app
        // uses OkHttp protocol-level pings instead, so this is never observed).
        "ping" => {
            let _ = send_json(state, json!({ "type": "pong" })).await;
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
fn reqid_key(v: &Value) -> Option<u64> {
    v.as_u64().or_else(|| v.as_str().and_then(|s| s.parse::<u64>().ok()))
}

fn live_config(app: &AppHandle) -> crate::config::Config {
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

fn mac_name(app: &AppHandle) -> String {
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
