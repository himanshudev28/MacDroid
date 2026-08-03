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

use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use tauri::{AppHandle, Emitter, Manager};
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
    next_mac_id: AtomicU32,
}

impl TransferRegistry {
    /// Allocate a new Mac-originated transferId from the shared `MAC_ID_BASE`
    /// range — reused as-is by `mac_fs::pull_to_phone` (Phase 19) so it never
    /// invents a second id space alongside `phone-push`'s.
    pub fn alloc_mac_id(&self) -> u32 {
        self.next_mac_id.fetch_add(1, Ordering::Relaxed) + MAC_ID_BASE
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
    let payload = buf[HEADER..].to_vec();
    if let Some(tx) = state.transfers.recv.lock().await.get(&transfer_id) {
        let _ = tx.send(RecvEvent::Chunk(payload));
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
        "photo-thumb-error" => {
            if let Some(req_id) = raw.get("reqId").and_then(Value::as_u64) {
                let err = raw
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or("thumbnail failed")
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
        if let Some(tx) = state.transfers.recv.lock().await.get(&tid) {
            let _ = tx.send(ev);
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
pub async fn push(
    app: AppHandle,
    state: SharedState,
    local_path: String,
    dest: String,
) -> Result<(), String> {
    let path = PathBuf::from(&local_path);
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .ok_or("invalid file name")?;
    let meta = tokio::fs::metadata(&path)
        .await
        .map_err(|e| format!("cannot read file: {e}"))?;
    let size = meta.len();

    let begin = ws_server::request_default(
        &state,
        json_map(json!({ "type": "fs-push-begin", "name": name, "size": size, "dest": dest })),
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

    let result = stream_file(&app, &state, &path, transfer_id, &name, size, &mut rx).await;
    state.transfers.send.lock().await.remove(&transfer_id);
    result
}

async fn stream_file(
    app: &AppHandle,
    state: &SharedState,
    path: &Path,
    transfer_id: u32,
    name: &str,
    size: u64,
    rx: &mut mpsc::UnboundedReceiver<SendEvent>,
) -> Result<(), String> {
    let mut file = File::open(path)
        .await
        .map_err(|e| format!("cannot open file: {e}"))?;
    let mut buf = vec![0u8; CHUNK];
    let mut seq: u32 = 0;
    let mut sent: u64 = 0;

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
        emit_progress(
            app,
            Progress { transfer_id, name: name.into(), sent, total: size, dir: "push", done: false, error: None },
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
    state.transfers.recv.lock().await.insert(transfer_id, tx);

    let result = receive_to_file(&app, &dest_file, transfer_id, &name, size, "pull", rx).await;
    state.transfers.recv.lock().await.remove(&transfer_id);
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

    let Some(dest) = download_dir(app).map(|d| d.join(sanitize(&name))) else {
        ws_server::push(state, json!({ "type": "phone-push-error", "reqId": req_id, "error": "no downloads dir" })).await;
        return;
    };

    // Ack so the phone starts streaming chunks against this transferId.
    ws_server::push(state, json!({ "type": "phone-push", "reqId": req_id, "transferId": transfer_id })).await;

    let (tx, rx) = mpsc::unbounded_channel::<RecvEvent>();
    state.transfers.recv.lock().await.insert(transfer_id, tx);

    let app = app.clone();
    let state2 = state.clone();
    tauri::async_runtime::spawn(async move {
        let ok = receive_to_file(&app, &dest, transfer_id, &name, size, "phone", rx)
            .await
            .is_ok();
        state2.transfers.recv.lock().await.remove(&transfer_id);
        ws_server::push(
            &state2,
            json!({ "type": "phone-push-result", "transferId": transfer_id, "ok": ok }),
        )
        .await;
    });
}

async fn receive_to_file(
    app: &AppHandle,
    dest: &Path,
    transfer_id: u32,
    name: &str,
    size: u64,
    dir: &'static str,
    mut rx: mpsc::UnboundedReceiver<RecvEvent>,
) -> Result<(), String> {
    if let Some(parent) = dest.parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }
    let part = with_part_ext(dest);
    let mut file = File::create(&part)
        .await
        .map_err(|e| format!("cannot create file: {e}"))?;
    let mut received: u64 = 0;

    let outcome = loop {
        match rx.recv().await {
            Some(RecvEvent::Chunk(bytes)) => {
                if file.write_all(&bytes).await.is_err() {
                    break Err("write error".to_string());
                }
                received += bytes.len() as u64;
                emit_progress(
                    app,
                    Progress { transfer_id, name: name.into(), sent: received, total: size, dir, done: false, error: None },
                );
            }
            Some(RecvEvent::Done) => break Ok(()),
            // Channel closed without a Done: the registry entry was dropped
            // (disconnect abort_all sends Cancel first, so this is a genuinely
            // abnormal end) — never rename a partial .part into place.
            None => break Err("transfer aborted".to_string()),
            Some(RecvEvent::Error(e)) => break Err(e),
            Some(RecvEvent::Cancel) => break Err("cancelled".to_string()),
        }
    };

    let _ = file.flush().await;
    drop(file);

    match outcome {
        Ok(()) => {
            let _ = tokio::fs::rename(&part, dest).await;
            emit_progress(
                app,
                Progress { transfer_id, name: name.into(), sent: received, total: size, dir, done: true, error: None },
            );
            Ok(())
        }
        Err(e) => {
            let _ = tokio::fs::remove_file(&part).await;
            emit_progress(
                app,
                Progress { transfer_id, name: name.into(), sent: received, total: size, dir, done: true, error: Some(e.clone()) },
            );
            Err(e)
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

fn sanitize(name: &str) -> String {
    name.replace(['/', '\\'], "_")
}

/// The user's Downloads dir (same landing spot as Electron's
/// `app.getPath('downloads')`), falling back to `~/Downloads`.
pub fn download_dir(app: &AppHandle) -> Option<PathBuf> {
    app.path()
        .download_dir()
        .ok()
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Downloads")))
}
