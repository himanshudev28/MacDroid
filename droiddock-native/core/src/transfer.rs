//! File transfer + binary framing (Phase 5).
//!
//! Wire layout (verified against `transfer.js` + `TransferManager.kt`):
//! every binary frame is `[1B kind][4B transferId BE][4B seq BE][payload]`.
//!   - `KIND_DATA (1)`  — a file-transfer chunk.
//!   - `KIND_THUMB (2)` — a single-frame photo/video thumbnail (Phase 6); the
//!     4-byte transferId slot carries the thumb *reqId* instead, and is routed
//!     in `ws_server::route_binary`, not here.
//!   - kind `3` — H.264 mirror frame (Phase 11), handled in ws_server.
//!
//! Chunk size and the in-flight cap match the reference constants: `CHUNK`
//! 256 KiB, `MAX_INFLIGHT` 4 MiB. There is no `bufferedAmount` equivalent in
//! tokio-tungstenite, so the 4 MiB window is reproduced structurally by the
//! bounded outbox channel in `ws_server` (`OUTBOX_CAP` slots × `CHUNK`), which
//! back-pressures the file-reader on `send_binary(...).await` exactly when the
//! socket can't drain fast enough — same external behaviour, no byte counting.

use crate::transfer_log::{self, Dir, NewTransfer, Source, Unit};
use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant};
use crate::host::{AppHandle, Emitter, Manager};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, Mutex};

pub const KIND_DATA: u8 = 1;
pub const KIND_THUMB: u8 = 2;
pub const HEADER: usize = 9;
/// Chunk size for outbound binary frames — also reused by `mac_fs::pull_to_phone`
/// (Phase 19) so a Mac→phone file send frames identically regardless of which
/// direction/feature originated it.
pub const CHUNK: usize = 256 * 1024;
/// Mac-allocated transferIds (for phone-initiated `phone-push` receives, and
/// Phase 19's Mac-initiated `mac-fs-pull` sends) start here so they can never
/// collide with the small phone-allocated ids used by `fs-pull`/`fs-push`,
/// which share the same `recv` map.
const MAC_ID_BASE: u32 = 0x4000_0000;

/// Early-arrival buffer (D2): the phone starts streaming right after it
/// replies to `fs-pull`, and those chunks can reach the socket reader before
/// the Mac-side task has registered its receiver. They are held briefly,
/// bounded in size, count and age, and replayed on registration.
const EARLY_MAX_BYTES: usize = 8 * 1024 * 1024;
const EARLY_MAX_IDS: usize = 16;
const EARLY_TTL: Duration = Duration::from_secs(15);
/// A receive that hears nothing for this long is dead (dropped `Done`, wedged phone).
const RECV_IDLE: Duration = Duration::from_secs(60);

struct Early {
    at: Instant,
    /// Something was dropped (over the cap): the stream has a hole, so the
    /// receiver must be told to fail rather than get a short "complete" file.
    poisoned: bool,
    bytes: usize,
    events: Vec<RecvEvent>,
}

/// Events delivered to a Mac-*receiving* transfer (pull / phone-push).
enum RecvEvent {
    Chunk(Vec<u8>),
    Done,
    Error(String),
    Cancel,
}

/// Events delivered to a Mac-*sending* transfer (push).
enum SendEvent {
    Result { ok: bool, error: Option<String> },
    Cancel,
}

/// Two independent maps, mirroring transfer.js's separate `recv`/`out` tables:
/// a pull and a push can legitimately share a transferId without colliding
/// because they live in different maps.
#[derive(Default)]
pub struct TransferRegistry {
    recv: Mutex<HashMap<u32, mpsc::UnboundedSender<RecvEvent>>>,
    send: Mutex<HashMap<u32, mpsc::UnboundedSender<SendEvent>>>,
    early: std::sync::Mutex<HashMap<u32, Early>>,
    next_mac_id: AtomicU32,
}

impl TransferRegistry {
    /// Allocate a new Mac-originated transferId from the shared `MAC_ID_BASE`
    /// range — reused as-is by `mac_fs::pull_to_phone` (Phase 19) so it never
    /// invents a second id space alongside `phone-push`'s.
    pub fn alloc_mac_id(&self) -> u32 {
        self.next_mac_id.fetch_add(1, Ordering::Relaxed) + MAC_ID_BASE
    }

    /// Register a receiver and replay anything that arrived before it existed.
    /// Holds the `recv` lock throughout so a chunk can't slip between the drain
    /// and the insert (`buffer_early` is called under the same lock).
    async fn register_recv(&self, id: u32, tx: mpsc::UnboundedSender<RecvEvent>) {
        let mut recv = self.recv.lock().await;
        let early = self.early.lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
        if let Some(e) = early {
            if e.poisoned || e.at.elapsed() >= EARLY_TTL {
                let _ = tx.send(RecvEvent::Error("early data was lost".into()));
            } else {
                for ev in e.events {
                    let _ = tx.send(ev);
                }
            }
        }
        recv.insert(id, tx);
    }

    /// Hold an event for a receiver that isn't registered yet. Caller holds the `recv` lock.
    fn buffer_early(&self, id: u32, ev: RecvEvent) {
        let mut g = self.early.lock().unwrap_or_else(|e| e.into_inner());
        g.retain(|_, e| e.at.elapsed() < EARLY_TTL);
        let is_chunk = matches!(ev, RecvEvent::Chunk(_));
        // A new id past the id cap is refused; if it was data, remember that the
        // stream has a hole (small marker entry) so registration fails it.
        let full = !g.contains_key(&id) && g.len() >= EARLY_MAX_IDS;
        if full && g.len() >= EARLY_MAX_IDS * 4 {
            return; // even the small "hole" markers are bounded
        }
        let entry = g
            .entry(id)
            .or_insert_with(|| Early { at: Instant::now(), poisoned: false, bytes: 0, events: Vec::new() });
        if entry.poisoned {
            return;
        }
        let add = if let RecvEvent::Chunk(b) = &ev { b.len() } else { 0 };
        if full || entry.bytes + add > EARLY_MAX_BYTES {
            if is_chunk {
                entry.poisoned = true;
                entry.events.clear();
                entry.bytes = 0;
            } else if full {
                g.remove(&id);
            }
            return;
        }
        entry.bytes += add;
        entry.events.push(ev);
    }

    /// Cancel every in-flight transfer — called when the phone disconnects, so
    /// a pull/push waiting on chunks that will never arrive fails fast instead
    /// of hanging on `rx.recv()` (JSON requests self-clean via their timeout;
    /// binary transfers have no such timeout, hence this).
    pub async fn abort_all(&self) {
        for (_, tx) in self.recv.lock().await.drain() {
            let _ = tx.send(RecvEvent::Cancel);
        }
        for (_, tx) in self.send.lock().await.drain() {
            let _ = tx.send(SendEvent::Cancel);
        }
        self.early.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
}

#[derive(Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct Progress {
    transfer_id: u32,
    name: String,
    sent: u64,
    total: u64,
    /// `"push"` (Mac→phone), `"pull"` (Mac←phone download), `"phone"` (phone-initiated push to Mac).
    dir: &'static str,
    done: bool,
    error: Option<String>,
}

fn emit_progress(app: &AppHandle, p: Progress) {
    let _ = app.emit("transfer-progress", p);
}

/// Floor on the gap between two *interim* progress events.
///
/// One event per 256 KiB chunk is roughly 80 a second on a fast LAN. Each is a
/// Tauri event broadcast to every window, and each lands in a `setState` that
/// re-renders the whole file list — for a bar no eye can read redrawing at
/// 80 Hz. Ten a second looks identical and costs an eighth as much.
const PROGRESS_EVERY: Duration = Duration::from_millis(100);

/// [`emit_progress`], rate-limited. Only for the in-flight updates: terminal
/// ones (`done: true`, with or without an error) go through `emit_progress`
/// directly so the UI always settles on the truth, however soon after the
/// previous update they land.
fn emit_progress_throttled(app: &AppHandle, p: Progress, last: &mut Option<Instant>) {
    if last.is_some_and(|t| t.elapsed() < PROGRESS_EVERY) {
        return;
    }
    *last = Some(Instant::now());
    emit_progress(app, p);
}

/// Build one binary frame — `pub` so `mac_fs::pull_to_phone` (Phase 19) can
/// reuse the exact same wire layout for its Mac→phone file sends.
pub fn frame(kind: u8, transfer_id: u32, seq: u32, payload: &[u8]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(HEADER + payload.len());
    buf.push(kind);
    buf.extend_from_slice(&transfer_id.to_be_bytes());
    buf.extend_from_slice(&seq.to_be_bytes());
    buf.extend_from_slice(payload);
    buf
}

// ── Inbound routing (called from ws_server) ──────────────────────────────

/// A KIND_DATA binary chunk arrived for `transfer_id`.
pub async fn on_chunk(state: &SharedState, transfer_id: u32, buf: &[u8]) {
    if buf.len() < HEADER {
        return;
    }
    let payload = buf[HEADER..].to_vec();
    let recv = state.transfers.recv.lock().await;
    if let Some(tx) = recv.get(&transfer_id) {
        let _ = tx.send(RecvEvent::Chunk(payload));
    } else {
        state.transfers.buffer_early(transfer_id, RecvEvent::Chunk(payload));
    }
}

/// A transfer-related control message arrived (`fs-*`, `phone-push*`,
/// `photo-thumb-error`). Routed by `transferId` (not reqId — those are already
/// resolved by the pending table in ws_server before reaching here).
pub async fn on_control(app: &AppHandle, state: &SharedState, raw: &Value) {
    let ty = raw.get("type").and_then(Value::as_str).unwrap_or("");
    let tid = raw
        .get("transferId")
        .and_then(Value::as_u64)
        .map(|v| v as u32);

    match ty {
        // Both answer over the KIND_THUMB frame and so share its reqId
        // namespace and this failure path.
        "photo-thumb-error" | "app-icon-error" => {
            if let Some(req_id) = raw.get("reqId").and_then(Value::as_u64) {
                let fallback = if ty == "app-icon-error" { "icon failed" } else { "thumbnail failed" };
                let err = raw
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or(fallback)
                    .to_string();
                ws_server::fail_thumb(state, req_id as u32, err).await;
            }
        }
        "fs-pull-done" => send_recv(state, tid, RecvEvent::Done).await,
        "fs-pull-error" => {
            let err = err_str(raw);
            send_recv(state, tid, RecvEvent::Error(err)).await;
        }
        "phone-push-done" => send_recv(state, tid, RecvEvent::Done).await,
        "phone-push-begin" => start_phone_push(app, state, raw).await,
        "fs-push-result" => {
            let ok = raw.get("ok").and_then(Value::as_bool).unwrap_or(false);
            let error = raw
                .get("error")
                .and_then(Value::as_str)
                .map(str::to_string);
            send_send(state, tid, SendEvent::Result { ok, error }).await;
        }
        "fs-push-error" => {
            send_send(
                state,
                tid,
                SendEvent::Result { ok: false, error: Some(err_str(raw)) },
            )
            .await;
        }
        "fs-cancel" => {
            // The phone cancelled; could be either direction — signal both maps.
            if let Some(tid) = tid {
                transfer_log::note_cancel(app, tid, transfer_log::CancelledBy::Phone);
            }
            send_recv(state, tid, RecvEvent::Cancel).await;
            send_send(state, tid, SendEvent::Cancel).await;
        }
        _ => {}
    }
}

fn err_str(raw: &Value) -> String {
    raw.get("error")
        .and_then(Value::as_str)
        .unwrap_or("transfer failed")
        .to_string()
}

async fn send_recv(state: &SharedState, tid: Option<u32>, ev: RecvEvent) {
    if let Some(tid) = tid {
        let recv = state.transfers.recv.lock().await;
        if let Some(tx) = recv.get(&tid) {
            let _ = tx.send(ev);
        } else if matches!(ev, RecvEvent::Done | RecvEvent::Error(_)) {
            // `fs-pull-done` can beat the receiver's registration too.
            state.transfers.buffer_early(tid, ev);
        }
    }
}

async fn send_send(state: &SharedState, tid: Option<u32>, ev: SendEvent) {
    if let Some(tid) = tid {
        if let Some(tx) = state.transfers.send.lock().await.get(&tid) {
            let _ = tx.send(ev);
        }
    }
}

// ── Mac→phone push (upload) ──────────────────────────────────────────────

/// Upload a local file to the phone under `dest` (a directory path on the
/// phone). Emits `transfer-progress` and resolves when the phone acks
/// `fs-push-result`. Mirrors transfer.js `push()`.
/// Send a local file to the phone.
///
/// `overwrite` is what the edit-in-place writeback path needs: the phone's
/// `FileRepo.uniqueDest` never overwrites, so without it every save of an
/// opened file landed as `name (2).ext`, `name (3).ext`, … while the Mac
/// cheerfully reported "synced" and the file the user was editing was never
/// actually updated. Normal user-initiated pushes keep the non-overwriting
/// behaviour, which is the right default for dropping files into a folder.
pub async fn push(
    app: AppHandle,
    state: SharedState,
    local_path: String,
    dest: String,
    overwrite: bool,
    source: Source,
) -> Result<(), String> {
    push_as(app, state, local_path, None, dest, overwrite, source).await
}

/// [`push`], with the name the phone should create. `None` uses the local file's
/// own name. A caller whose local file is a cache copy (the Finder mount names
/// them by hash) must pass the real leaf name, or the phone gets a hex-named file.
pub async fn push_as(
    app: AppHandle,
    state: SharedState,
    local_path: String,
    remote_name: Option<String>,
    dest: String,
    overwrite: bool,
    source: Source,
) -> Result<(), String> {
    let path = PathBuf::from(&local_path);
    let name = pick_remote_name(remote_name, &path).ok_or("invalid file name")?;
    let meta = tokio::fs::metadata(&path)
        .await
        .map_err(|e| format!("cannot read file: {e}"))?;
    let size = meta.len();

    let begin = ws_server::request_default(
        &state,
        json_map(json!({ "type": "fs-push-begin", "name": name, "size": size, "dest": dest, "overwrite": overwrite })),
    )
    .await?;
    if let Some(err) = begin.get("error").and_then(Value::as_str) {
        return Err(err.to_string());
    }
    let transfer_id = begin
        .get("transferId")
        .and_then(Value::as_u64)
        .ok_or("phone did not return a transferId")? as u32;

    let (tx, mut rx) = mpsc::unbounded_channel::<SendEvent>();
    state.transfers.send.lock().await.insert(transfer_id, tx);

    let tracked = transfer_log::start(
        &app,
        NewTransfer {
            name: name.clone(),
            dir: Dir::ToPhone,
            source,
            unit: Unit::Bytes,
            total: size,
            cancel_id: Some(transfer_id),
        },
    );
    let result = stream_file(&app, &state, &path, transfer_id, &name, size, tracked.key(), &mut rx).await;
    state.transfers.send.lock().await.remove(&transfer_id);
    tracked.finish(result.clone().map(|()| None).into());
    result
}

/// The name the phone should create: the caller's override when it is a plain
/// leaf, else the local file's own name.
pub(crate) fn pick_remote_name(remote_name: Option<String>, local: &Path) -> Option<String> {
    remote_name
        .filter(|n| !n.is_empty() && !n.contains('/') && n != "." && n != "..")
        .or_else(|| local.file_name().map(|n| n.to_string_lossy().to_string()))
}

async fn stream_file(
    app: &AppHandle,
    state: &SharedState,
    path: &Path,
    transfer_id: u32,
    name: &str,
    size: u64,
    log_key: u64,
    rx: &mut mpsc::UnboundedReceiver<SendEvent>,
) -> Result<(), String> {
    let mut file = File::open(path)
        .await
        .map_err(|e| format!("cannot open file: {e}"))?;
    let mut buf = vec![0u8; CHUNK];
    let mut seq: u32 = 0;
    let mut sent: u64 = 0;

    let mut last_progress: Option<Instant> = None;
    loop {
        // Non-blocking control check between chunks. Any event here must end
        // the stream — matching only Cancel would silently consume an early
        // fs-push-error/-result, and the post-loop `rx.recv()` would then wait
        // forever on a reply the phone already sent.
        match rx.try_recv() {
            Ok(SendEvent::Cancel) => return Err("cancelled".into()),
            Ok(SendEvent::Result { ok: true, .. }) => {
                emit_progress(
                    app,
                    Progress { transfer_id, name: name.into(), sent, total: size, dir: "push", done: true, error: None },
                );
                return Ok(());
            }
            Ok(SendEvent::Result { ok: false, error }) => {
                return Err(error.unwrap_or_else(|| "phone rejected the file".into()));
            }
            Err(_) => {}
        }
        let n = file
            .read(&mut buf)
            .await
            .map_err(|e| format!("read error: {e}"))?;
        if n == 0 {
            break;
        }
        // send_binary awaits the bounded outbox → this is the 4 MiB backpressure.
        if !ws_server::send_binary(state, frame(KIND_DATA, transfer_id, seq, &buf[..n])).await {
            return Err("Phone disconnected mid-transfer".into());
        }
        seq += 1;
        sent += n as u64;
        transfer_log::progress(app, log_key, sent);
        emit_progress_throttled(
            app,
            Progress { transfer_id, name: name.into(), sent, total: size, dir: "push", done: false, error: None },
            &mut last_progress,
        );
    }

    ws_server::push(state, json!({ "type": "fs-push-done", "transferId": transfer_id, "size": sent }))
        .await;

    // Await the phone's fs-push-result / fs-push-error (or a cancel).
    match rx.recv().await {
        Some(SendEvent::Result { ok: true, .. }) => {
            emit_progress(
                app,
                Progress { transfer_id, name: name.into(), sent, total: size, dir: "push", done: true, error: None },
            );
            Ok(())
        }
        Some(SendEvent::Result { ok: false, error }) => {
            Err(error.unwrap_or_else(|| "phone rejected the file".into()))
        }
        Some(SendEvent::Cancel) => Err("cancelled".into()),
        None => Err("transfer aborted".into()),
    }
}

// ── Mac←phone pull (download) ────────────────────────────────────────────

/// Download `path` from the phone into `dest_file` on the Mac. Mirrors
/// transfer.js `pull()`: request → `fs-pull-begin` → KIND_DATA chunks →
/// `fs-pull-done`. Writes to a `.part` file then renames on success.
pub async fn pull(
    app: AppHandle,
    state: SharedState,
    path: String,
    dest_file: PathBuf,
    // `None` for callers that report the transfer themselves (photo sync
    // shows one entry for the whole batch, not one per photo).
    track: Option<Source>,
) -> Result<PathBuf, String> {
    let name = dest_file
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "download".into());

    let begin = ws_server::request_default(
        &state,
        json_map(json!({ "type": "fs-pull", "path": path })),
    )
    .await?;
    if let Some(err) = begin.get("error").and_then(Value::as_str) {
        return Err(err.to_string());
    }
    let transfer_id = begin
        .get("transferId")
        .and_then(Value::as_u64)
        .ok_or("phone did not return a transferId")? as u32;
    let size = begin.get("size").and_then(Value::as_u64).unwrap_or(0);

    let (tx, rx) = mpsc::unbounded_channel::<RecvEvent>();
    state.transfers.register_recv(transfer_id, tx).await;

    let tracked = track.map(|source| {
        transfer_log::start(
            &app,
            NewTransfer {
                name: name.clone(),
                dir: Dir::ToMac,
                source,
                unit: Unit::Bytes,
                total: size,
                cancel_id: Some(transfer_id),
            },
        )
    });
    let key = tracked.as_ref().map(|t| t.key());
    let result = receive_to_file(true, &app, &dest_file, transfer_id, &name, size, "pull", key, rx).await;
    state.transfers.recv.lock().await.remove(&transfer_id);
    if let Some(t) = tracked {
        t.finish(result.clone().map(|()| Some(dest_file.clone())).into());
    }
    result.map(|_| dest_file)
}

/// Phone-initiated push (Android share sheet → DroidDock): the phone sends
/// `phone-push-begin`; we allocate a transferId, ack with `phone-push`, and
/// receive into the Downloads folder. Mirrors transfer.js's `phone-push` path.
async fn start_phone_push(app: &AppHandle, state: &SharedState, raw: &Value) {
    // The phone's reqId here is a STRING like "pp1" (TransferManager.pushToMac),
    // and it's the key the phone looks our `phone-push` ack up by — so echo it
    // back VERBATIM as the same JSON value, never coerced to a number.
    let Some(req_id) = raw.get("reqId").cloned().filter(|v| !v.is_null()) else {
        return;
    };
    let name = raw
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("download")
        .to_string();
    let size = raw.get("size").and_then(Value::as_u64).unwrap_or(0);
    let transfer_id = state
        .transfers
        .next_mac_id
        .fetch_add(1, Ordering::Relaxed)
        + MAC_ID_BASE;

    let Some(dest) = download_dir(app).map(|d| unique_dest(&d, &sanitize(&name))) else {
        ws_server::push(state, json!({ "type": "phone-push-error", "reqId": req_id, "error": "no downloads dir" })).await;
        return;
    };

    // Ack so the phone starts streaming chunks against this transferId.
    ws_server::push(state, json!({ "type": "phone-push", "reqId": req_id, "transferId": transfer_id })).await;

    let (tx, rx) = mpsc::unbounded_channel::<RecvEvent>();
    state.transfers.register_recv(transfer_id, tx).await;

    let tracked = transfer_log::start(
        app,
        NewTransfer {
            name: name.clone(),
            dir: Dir::ToMac,
            source: Source::Share,
            unit: Unit::Bytes,
            total: size,
            cancel_id: Some(transfer_id),
        },
    );
    let app = app.clone();
    let state2 = state.clone();
    tokio::spawn(async move {
        let result = receive_to_file(false, &app, &dest, transfer_id, &name, size, "phone", Some(tracked.key()), rx).await;
        tracked.finish(result.clone().map(|()| Some(dest.clone())).into());
        let ok = result.is_ok();
        if !ok {
            // Drop the empty placeholder `unique_dest` reserved.
            let _ = tokio::fs::remove_file(&dest).await;
        }
        state2.transfers.recv.lock().await.remove(&transfer_id);
        ws_server::push(
            &state2,
            json!({ "type": "phone-push-result", "transferId": transfer_id, "ok": ok }),
        )
        .await;
    });
}

async fn receive_to_file(
    strict_size: bool,
    app: &AppHandle,
    dest: &Path,
    transfer_id: u32,
    name: &str,
    size: u64,
    dir: &'static str,
    log_key: Option<u64>,
    rx: mpsc::UnboundedReceiver<RecvEvent>,
) -> Result<(), String> {
    let mut last_progress: Option<Instant> = None;
    let outcome = receive_core(dest, size, strict_size, RECV_IDLE, rx, |received| {
        if let Some(key) = log_key {
            transfer_log::progress(app, key, received);
        }
        emit_progress_throttled(
            app,
            Progress { transfer_id, name: name.into(), sent: received, total: size, dir, done: false, error: None },
            &mut last_progress,
        );
    })
    .await;
    match outcome {
        Ok(received) => {
            emit_progress(
                app,
                Progress { transfer_id, name: name.into(), sent: received, total: size, dir, done: true, error: None },
            );
            Ok(())
        }
        Err((received, e)) => {
            emit_progress(
                app,
                Progress { transfer_id, name: name.into(), sent: received, total: size, dir, done: true, error: Some(e.clone()) },
            );
            Err(e)
        }
    }
}

/// The receive loop, free of app state so it can be tested. Writes `dest.part`
/// and renames it into place only when the transfer finished cleanly AND the
/// byte count matches the announced `size` (when one was announced); otherwise
/// the `.part` is deleted. Gives up after `idle` without any event.
async fn receive_core(
    dest: &Path,
    size: u64,
    strict_size: bool,
    idle: Duration,
    mut rx: mpsc::UnboundedReceiver<RecvEvent>,
    mut on_progress: impl FnMut(u64),
) -> Result<u64, (u64, String)> {
    if let Some(parent) = dest.parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }
    let part = with_part_ext(dest);
    let mut file = File::create(&part)
        .await
        .map_err(|e| (0, format!("cannot create file: {e}")))?;
    let mut received: u64 = 0;

    let outcome = loop {
        match tokio::time::timeout(idle, rx.recv()).await {
            Err(_) => break Err("transfer stalled".to_string()),
            Ok(Some(RecvEvent::Chunk(bytes))) => {
                if file.write_all(&bytes).await.is_err() {
                    break Err("write error".to_string());
                }
                received += bytes.len() as u64;
                on_progress(received);
            }
            Ok(Some(RecvEvent::Done)) => break Ok(()),
            // Channel closed without a Done: the registry entry was dropped
            // (disconnect abort_all sends Cancel first, so this is a genuinely
            // abnormal end) — never rename a partial .part into place.
            Ok(None) => break Err("transfer aborted".to_string()),
            Ok(Some(RecvEvent::Error(e))) => break Err(e),
            Ok(Some(RecvEvent::Cancel)) => break Err("cancelled".to_string()),
        }
    };

    let flushed = file.flush().await;
    drop(file);

    let outcome = outcome.and_then(|()| {
        // size == 0 may just mean "the phone didn't say", so only a declared
        // non-zero size is enforced; that is what catches a truncated file.
        if size > 0 && received != size {
            if strict_size {
                Err(format!("incomplete transfer: got {received} of {size} bytes"))
            } else {
                // A phone-initiated share: content providers (Drive/Docs virtual
                // files) report a wrong OpenableColumns.SIZE, so the bytes we
                // got are accepted; the mismatch is only logged.
                eprintln!("[transfer] phone push size mismatch: got {received}, announced {size}; accepting");
                flushed.map_err(|e| format!("write error: {e}"))
            }
        } else {
            flushed.map_err(|e| format!("write error: {e}"))
        }
    });
    match outcome {
        Ok(()) => match tokio::fs::rename(&part, dest).await {
            Ok(()) => Ok(received),
            Err(e) => {
                let _ = tokio::fs::remove_file(&part).await;
                Err((received, format!("cannot save file: {e}")))
            }
        },
        Err(e) => {
            let _ = tokio::fs::remove_file(&part).await;
            Err((received, e))
        }
    }
}

/// Cancel an in-flight transfer: tell the phone (`fs-cancel`) and interrupt the
/// local task in whichever map owns the id.
pub async fn cancel(state: &SharedState, transfer_id: u32) {
    ws_server::push(state, json!({ "type": "fs-cancel", "transferId": transfer_id })).await;
    send_recv(state, Some(transfer_id), RecvEvent::Cancel).await;
    send_send(state, Some(transfer_id), SendEvent::Cancel).await;
}

// ── helpers ──────────────────────────────────────────────────────────────

fn json_map(v: Value) -> serde_json::Map<String, Value> {
    match v {
        Value::Object(m) => m,
        _ => serde_json::Map::new(),
    }
}

fn with_part_ext(dest: &Path) -> PathBuf {
    let mut s = dest.as_os_str().to_os_string();
    s.push(".part");
    PathBuf::from(s)
}

pub(crate) fn sanitize(name: &str) -> String {
    let n: String = name.replace(['/', '\\'], "_").chars().filter(|c| !c.is_control()).collect();
    match n.trim() {
        "" | "." | ".." => "download".to_string(),
        t => t.to_string(),
    }
}

/// `dir/name`, or `dir/name (2).ext`, `(3)`, … when that already exists (D5:
/// a phone share must never overwrite a file in Downloads). Also steps over a
/// leftover `.part`, so two shares of one name can't write the same temp file.
fn unique_dest(dir: &Path, name: &str) -> PathBuf {
    // Reserve with an exclusive create so two concurrent shares can't pick the
    // same name (the empty placeholder is replaced by the final rename).
    let reserve = |p: &Path| {
        !with_part_ext(p).exists()
            && std::fs::OpenOptions::new().write(true).create_new(true).open(p).is_ok()
    };
    let first = dir.join(name);
    if reserve(&first) {
        return first;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    (2..10_000)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|p| reserve(p))
        .unwrap_or(first)
}

/// The user's Downloads dir (same landing spot as Electron's
/// `app.getPath('downloads')`), falling back to `~/Downloads`.
pub fn download_dir(app: &AppHandle) -> Option<PathBuf> {
    app.path()
        .download_dir()
        .ok()
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Downloads")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("dd-xfer-{tag}-{}-{}", std::process::id(), uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    async fn run(dest: &Path, size: u64, idle: Duration, evs: Vec<RecvEvent>, keep_open: bool) -> Result<u64, (u64, String)> {
        let (tx, rx) = mpsc::unbounded_channel();
        for e in evs {
            tx.send(e).unwrap();
        }
        let r = receive_core(dest, size, true, idle, rx, |_| {}).await;
        drop(tx);
        let _ = keep_open;
        r
    }

    #[tokio::test]
    async fn complete_transfer_is_renamed_into_place() {
        let d = tmp("ok");
        let dest = d.join("a.bin");
        let r = run(&dest, 5, Duration::from_secs(5), vec![RecvEvent::Chunk(vec![1, 2, 3]), RecvEvent::Chunk(vec![4, 5]), RecvEvent::Done], false).await;
        assert_eq!(r.unwrap(), 5);
        assert_eq!(std::fs::read(&dest).unwrap(), vec![1, 2, 3, 4, 5]);
        assert!(!with_part_ext(&dest).exists());
    }

    #[tokio::test]
    async fn truncated_transfer_is_an_error_and_leaves_nothing() {
        let d = tmp("short");
        let dest = d.join("a.bin");
        let r = run(&dest, 10, Duration::from_secs(5), vec![RecvEvent::Chunk(vec![1, 2, 3]), RecvEvent::Done], false).await;
        assert!(r.unwrap_err().1.contains("incomplete"));
        assert!(!dest.exists() && !with_part_ext(&dest).exists());
        // An "empty but complete" file with a declared size is the D2 symptom.
        let r = run(&dest, 10, Duration::from_secs(5), vec![RecvEvent::Done], false).await;
        assert!(r.is_err() && !dest.exists());
    }

    #[tokio::test]
    async fn unknown_size_is_not_enforced_and_a_rename_failure_is_reported() {
        let d = tmp("unk");
        let dest = d.join("a.bin");
        assert!(run(&dest, 0, Duration::from_secs(5), vec![RecvEvent::Chunk(vec![9]), RecvEvent::Done], false).await.is_ok());
        // Rename onto a non-empty directory fails: must be an Err, not a silent success.
        let blocked = d.join("dir");
        std::fs::create_dir_all(blocked.join("x")).unwrap();
        let r = run(&blocked, 1, Duration::from_secs(5), vec![RecvEvent::Chunk(vec![9]), RecvEvent::Done], false).await;
        assert!(r.unwrap_err().1.contains("cannot save"));
        assert!(!with_part_ext(&blocked).exists());
    }

    #[tokio::test]
    async fn a_silent_phone_times_out_instead_of_hanging() {
        let d = tmp("idle");
        let dest = d.join("a.bin");
        let (tx, rx) = mpsc::unbounded_channel::<RecvEvent>();
        tx.send(RecvEvent::Chunk(vec![1])).unwrap();
        let r = receive_core(&dest, 5, true, Duration::from_millis(80), rx, |_| {}).await;
        assert!(r.unwrap_err().1.contains("stalled"));
        assert!(!with_part_ext(&dest).exists());
        drop(tx);
    }

    #[tokio::test]
    async fn early_chunks_are_replayed_and_bounded() {
        let reg = TransferRegistry::default();
        {
            let _g = reg.recv.lock().await;
            reg.buffer_early(7, RecvEvent::Chunk(vec![1, 2]));
            reg.buffer_early(7, RecvEvent::Chunk(vec![3]));
            reg.buffer_early(7, RecvEvent::Done);
            // Over the byte cap: dropped, not stored.
            reg.buffer_early(8, RecvEvent::Chunk(vec![0; EARLY_MAX_BYTES]));
            reg.buffer_early(8, RecvEvent::Chunk(vec![0; 1]));
        }
        let (tx, mut rx) = mpsc::unbounded_channel();
        reg.register_recv(7, tx).await;
        let mut bytes = Vec::new();
        let mut done = false;
        while let Ok(ev) = rx.try_recv() {
            match ev {
                RecvEvent::Chunk(b) => bytes.extend(b),
                RecvEvent::Done => done = true,
                _ => {}
            }
        }
        assert_eq!(bytes, vec![1, 2, 3]);
        assert!(done);
        // id cap
        {
            let _g = reg.recv.lock().await;
            for id in 100..(100 + EARLY_MAX_IDS as u32 + 5) {
                reg.buffer_early(id, RecvEvent::Chunk(vec![1]));
            }
        }
        assert!(reg.early.lock().unwrap().len() <= EARLY_MAX_IDS * 4);
    }

    #[tokio::test]
    async fn a_dropped_early_chunk_fails_the_receiver_instead_of_leaving_a_hole() {
        let reg = TransferRegistry::default();
        {
            let _g = reg.recv.lock().await;
            reg.buffer_early(9, RecvEvent::Chunk(vec![0; EARLY_MAX_BYTES]));
            reg.buffer_early(9, RecvEvent::Chunk(vec![1])); // over the cap -> poisoned
            reg.buffer_early(9, RecvEvent::Done); // ignored once poisoned
        }
        let (tx, mut rx) = mpsc::unbounded_channel();
        reg.register_recv(9, tx).await;
        assert!(matches!(rx.try_recv(), Ok(RecvEvent::Error(_))));
        assert!(rx.try_recv().is_err(), "no data or Done is replayed after a hole");
    }

    #[tokio::test]
    async fn phone_push_accepts_a_wrong_announced_size_but_pull_does_not() {
        let d = tmp("lenient");
        let dest = d.join("p.bin");
        let (tx, rx) = mpsc::unbounded_channel();
        tx.send(RecvEvent::Chunk(vec![1, 2, 3])).unwrap();
        tx.send(RecvEvent::Done).unwrap();
        let r = receive_core(&dest, 99, false, Duration::from_secs(5), rx, |_| {}).await;
        assert_eq!(r.unwrap(), 3);
        assert_eq!(std::fs::read(&dest).unwrap(), vec![1, 2, 3]);
        let dest2 = d.join("q.bin");
        let (tx, rx) = mpsc::unbounded_channel();
        tx.send(RecvEvent::Chunk(vec![1, 2, 3])).unwrap();
        tx.send(RecvEvent::Done).unwrap();
        assert!(receive_core(&dest2, 99, true, Duration::from_secs(5), rx, |_| {}).await.is_err());
    }

    #[test]
    fn share_names_never_overwrite_or_escape() {
        let d = tmp("uniq");
        assert_eq!(sanitize("../x"), ".._x");
        assert_eq!(sanitize(".."), "download");
        let a = unique_dest(&d, "photo.jpg");
        assert_eq!(a, d.join("photo.jpg"));
        std::fs::write(&a, b"1").unwrap();
        let b = unique_dest(&d, "photo.jpg");
        assert_eq!(b, d.join("photo (2).jpg"));
        std::fs::write(&b, b"2").unwrap();
        assert_eq!(unique_dest(&d, "photo.jpg"), d.join("photo (3).jpg"));
        assert_eq!(unique_dest(&d, "noext"), d.join("noext"));
    }
}
