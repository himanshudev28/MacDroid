//! Screen mirroring + phone-camera streaming (Phase 11), Wi-Fi/app-link path
//! only — decode per Spike A's verdict (WKWebView WebCodecs, no native
//! VideoToolbox bridge needed).
//!
//! Wire protocol verified against `wifi.js` + `MirrorService.kt` +
//! `AccessibilityControl.kt` + `ConnectionManager.kt`:
//!   Mac→phone: `mirror-start` (resumes a live screen session or launches
//!   consent), `mirror-stop`/`camera-stop` (always fully stops, clearing the
//!   phone's foreground-service indicator), `camera-start{facing}`,
//!   `camera-flip{facing}`, `mirror-tap{x,y}` (0..1 fractions),
//!   `mirror-swipe{x1,y1,x2,y2,dur}`, `mirror-key{key}` (back/home/recents),
//!   `mirror-text{text}` or `{op:"backspace"|"enter"}`.
//!   Phone→Mac: `mirror-started{width,height,codec,source,facing}`,
//!   `mirror-stopped`, `mirror-error{error}`, plus binary kind-3 frames
//!   `[3][flags][H.264 access unit]` (flags bit0 = keyframe).
//!
//! Pop-out window behaviour ported from `index.js`'s `openMirrorWindow` /
//! `fitMirrorWindow` / the `mirrorWin.on('closed', ...)` handler: a single
//! on-demand, frameless, aspect-locked, phone-shaped window labeled "mirror",
//! loading the same frontend bundle at the `#mirror` hash route.

use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};

const LABEL: &str = "mirror";

/// Tracks the most recent `mirror-started` payload so a pop-out window that
/// hasn't finished loading yet (or is freshly created after the stream was
/// already announced) can replay it — mirrors `index.js`'s `lastMirrorStarted`
/// — plus the pop-out's registered frame channel (set by `mirror_attach` once
/// the window's JS is actually listening; raw video never rides plain events).
#[derive(Default)]
pub struct MirrorState {
    last_started: Mutex<Option<Value>>,
    frame_tx: Mutex<Option<Channel<InvokeResponseBody>>>,
    /// Whether this WebView's `VideoDecoder` can actually decode HEVC, as
    /// reported by the frontend's `isConfigSupported` probe at startup.
    ///
    /// Defaults to false and is only ever raised by that probe, because the
    /// failure it guards is a silent one: asking the phone for H.265 that the
    /// decoder then refuses leaves a black pop-out with nothing in the log. It
    /// is far better to under-use HEVC than to negotiate a stream nothing here
    /// can play.
    hevc_supported: AtomicBool,
}

// ── Tauri commands (invoked from the frontend) ──────────────────────────

/// Ask the phone to start (screen or camera) and open/focus the pop-out
/// window. Mirrors `ipcMain.handle('mirror:popout', ...)`.
#[tauri::command]
pub async fn mirror_popout(
    app: AppHandle,
    state: tauri::State<'_, SharedState>,
    source: String,
) -> Result<(), String> {
    // Quality rides along on the start message. The phone's encoder was fixed
    // at 6 Mbps / 30 fps with no way to say otherwise; these are additive
    // fields, so a phone build that predates them ignores them and keeps its
    // own defaults rather than failing to start.
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    //
    // `codec` and `audio` ride along the same way. H.265 is requested only when
    // the user asked for it *and* this WebView proved it can decode it; the
    // phone independently falls back to H.264 if it has no HEVC encoder, so
    // both ends have to agree before the stream is anything but H.264.
    let want_hevc = cfg.mirror_codec == "h265"
        && app.state::<MirrorState>().hevc_supported.load(Ordering::Relaxed);
    let quality = json!({
        "bitrate": cfg.mirror_bitrate_mbps.clamp(1, 50) * 1_000_000,
        "fps": cfg.mirror_fps.clamp(15, 120),
        "maxSize": cfg.mirror_max_size,
        "codec": if want_hevc { "h265" } else { "h264" },
        "audio": cfg.mirror_audio && source != "camera",
    });
    let mut msg = if source == "camera" {
        json!({ "type": "camera-start", "facing": "back" })
    } else {
        json!({ "type": "mirror-start" })
    };
    if let (Some(obj), Some(q)) = (msg.as_object_mut(), quality.as_object()) {
        obj.extend(q.clone());
    }
    eprintln!(
        "[mirror] start source={source} codec={} audio={}",
        if want_hevc { "h265" } else { "h264" },
        cfg.mirror_audio && source != "camera"
    );
    if !ws_server::push(&state, msg).await {
        return Err("Phone not linked over Wi-Fi".into());
    }
    open_mirror_window(&app)
}

/// Mirrors `ipcMain.handle('mirror:stop', ...)`: closing the window (if open)
/// is what actually pushes `mirror-stop` (via the `Destroyed` handler below),
/// exactly like Electron's `mirrorWin.close()` path.
#[tauri::command]
pub async fn mirror_stop(app: AppHandle, state: tauri::State<'_, SharedState>) -> Result<(), String> {
    if let Some(w) = app.get_webview_window(LABEL) {
        let _ = w.close();
    } else {
        ws_server::push(&state, json!({ "type": "mirror-stop" })).await;
    }
    Ok(())
}

#[tauri::command]
pub fn mirror_focus(app: AppHandle) -> Result<(), String> {
    if let Some(w) = app.get_webview_window(LABEL) {
        let _ = w.set_focus();
    }
    Ok(())
}

#[tauri::command]
pub fn mirror_set_on_top(app: AppHandle, on: bool) -> Result<(), String> {
    if let Some(w) = app.get_webview_window(LABEL) {
        let _ = w.set_always_on_top(on);
    }
    Ok(())
}

/// Tap / swipe / key / text / camera-flip from the Mac → pushed verbatim to
/// the phone. Mirrors `ipcMain.handle('mirror:input', (_e, msg) => wifi.push(msg))`
/// — the frontend sends the exact `{type, ...}` shape already.
#[tauri::command]
pub async fn mirror_input(state: tauri::State<'_, SharedState>, msg: Value) -> Result<(), String> {
    ws_server::push(&state, msg).await;
    Ok(())
}

/// Called by the pop-out once its JS is mounted and listening: registers the
/// raw-frame delivery channel and hands back the pending `mirror-started`
/// payload if the phone announced the stream while the window was still
/// loading. This is the Tauri equivalent of `index.js`'s `did-finish-load`
/// replay, made deterministic — an event emitted at build time would be
/// dropped by the not-yet-loaded webview, so the frontend asks when it is
/// actually ready instead of the backend guessing from load timing.
#[tauri::command]
pub fn mirror_attach(
    window: tauri::WebviewWindow,
    state: tauri::State<'_, MirrorState>,
    channel: Channel<InvokeResponseBody>,
) -> Option<Value> {
    if window.label() != LABEL {
        return None;
    }
    *state.frame_tx.lock().unwrap() = Some(channel);
    let started = state.last_started.lock().unwrap().clone();
    if let Some(s) = &started {
        apply_aspect(&window, s);
    }
    started
}

/// The frontend's one-time WebCodecs probe result. Called from the main
/// window at startup; until it arrives, H.265 is simply never requested.
#[tauri::command]
pub fn mirror_set_hevc_supported(state: tauri::State<'_, MirrorState>, supported: bool) {
    eprintln!("[mirror] webview HEVC decode support: {supported}");
    state.hevc_supported.store(supported, Ordering::Relaxed);
}

// ── Window lifecycle ─────────────────────────────────────────────────────

/// Open (or focus) the pop-out. Public so the embedded ADB mirror can bring
/// the window up before frames start — the decoder is configured from the
/// stream's first config packet, and a window that isn't attached yet drops it.
pub fn open_popout(app: &AppHandle) -> Result<(), String> {
    open_mirror_window(app)
}

fn open_mirror_window(app: &AppHandle) -> Result<(), String> {
    if let Some(w) = app.get_webview_window(LABEL) {
        let _ = w.set_focus();
        return Ok(());
    }

    let window = WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("index.html#mirror".into()))
        .title("DroidDock — Mirror")
        .inner_size(360.0, 760.0)
        .min_inner_size(200.0, 240.0)
        .decorations(false)
        .resizable(true)
        .build()
        .map_err(|e| {
            eprintln!("mirror: failed to create pop-out window: {e}");
            format!("Couldn't open the mirror window: {e}")
        })?;

    // Closing the pop-out ends the stream: tell the phone to stop (always
    // fully, so its foreground-service/cast indicator clears — Auto mode
    // re-shows the system consent dialog directly on the next mirror-start,
    // no lingering notification needed) and reset the replay state. Mirrors
    // `mirrorWin.on('closed', ...)` — `Destroyed` fires after the window is
    // actually gone, same timing.
    let app_for_close = app.clone();
    window.on_window_event(move |event| {
        if let WindowEvent::Destroyed = event {
            app_for_close.state::<MirrorState>().clear();
            // Electron also tells the MAIN window's SCREEN tab launcher
            // directly from this same handler (not via the phone's own
            // mirror-stopped forward, which only ever reaches the pop-out) —
            // reproduced exactly, quirk included (see compatibility report).
            let _ = app_for_close.emit_to("main", "mirror-stopped", json!({}));
            // The same window serves both mirror sources, so closing it has to
            // end whichever one is running. `stop` is a no-op when the embedded
            // ADB session isn't the active one.
            crate::scrcpy_client::stop(&app_for_close);
            let app = app_for_close.clone();
            tauri::async_runtime::spawn(async move {
                let state: SharedState = app.state::<SharedState>().inner().clone();
                ws_server::push(&state, json!({ "type": "mirror-stop" })).await;
            });
        }
    });

    // Replay of an already-announced stream is NOT done here: the fresh
    // webview hasn't loaded its JS yet, so an emit at this point would be
    // silently dropped. The window calls `mirror_attach` once it's listening
    // and picks the pending `mirror-started` up from there.

    Ok(())
}

impl MirrorState {
    fn clear(&self) {
        *self.last_started.lock().unwrap() = None;
        *self.frame_tx.lock().unwrap() = None;
    }
}

// ── Inbound forwarding (called from ws_server::route_text / route_binary) ──

/// A `mirror-started` message arrived. Record it for replay, resize the
/// pop-out to the phone's aspect ratio, and forward to the pop-out only —
/// mirrors `index.js`'s forwardCb, which never sends these three to the main
/// window (only the pop-out's own `closed` handler does, above).
pub fn on_started(app: &AppHandle, payload: &Value) {
    // The codec string the phone actually derived. Logged because a wrong one
    // is invisible: the decoder errors, closes, and every later frame is
    // dropped on a state guard, leaving a black window and no message.
    eprintln!(
        "[mirror] started {}x{} codec={} source={}",
        payload.get("width").and_then(Value::as_i64).unwrap_or(0),
        payload.get("height").and_then(Value::as_i64).unwrap_or(0),
        payload.get("codec").and_then(Value::as_str).unwrap_or("?"),
        payload.get("source").and_then(Value::as_str).unwrap_or("?")
    );
    *app.state::<MirrorState>().last_started.lock().unwrap() = Some(payload.clone());
    if let Some(w) = app.get_webview_window(LABEL) {
        apply_aspect(&w, payload);
    }
    let _ = app.emit_to(LABEL, "mirror-started", payload);
}

pub fn on_stopped(app: &AppHandle, payload: &Value) {
    app.state::<MirrorState>().clear();
    let _ = app.emit_to(LABEL, "mirror-stopped", payload);
}

pub fn on_error(app: &AppHandle, payload: &Value) {
    app.state::<MirrorState>().clear();
    let _ = app.emit_to(LABEL, "mirror-error", payload);
}

/// A kind-3 binary frame arrived: `[3][flags][H.264 access unit]`, flags
/// bit0 = keyframe. Forwarded to the pop-out's attached channel as raw
/// `[flags][payload]` bytes — Tauri delivers ≥1 KiB raw channel payloads via
/// its fetch fast-path as an ArrayBuffer, ordered by a per-message index, so
/// no base64/JSON cost per frame at 30 fps. Dropped when no window is
/// attached (yet), same as `index.js` dropping `mirror-frame` with no
/// `mirrorWin`.
pub fn on_frame(app: &AppHandle, buf: &[u8]) {
    if buf.len() < 2 {
        return;
    }
    let tx = app.state::<MirrorState>().frame_tx.lock().unwrap().clone();
    if let Some(tx) = tx {
        let _ = tx.send(InvokeResponseBody::Raw(buf[1..].to_vec()));
    }
}

/// Lock the pop-out's aspect ratio to the phone's reported width/height and
/// size it to match, accounting for the 36px control bar — mirrors
/// `fitMirrorWindow`. Uses the raw `NSWindow` (via objc2-app-kit, same
/// pattern as `appearance.rs`/`clipboard.rs`) since neither tao nor tauri
/// expose aspect-ratio locking in their cross-platform window API.
fn apply_aspect(window: &tauri::WebviewWindow, started: &Value) {
    let w = started.get("width").and_then(Value::as_f64).unwrap_or(0.0);
    let h = started.get("height").and_then(Value::as_f64).unwrap_or(0.0);
    if w <= 0.0 || h <= 0.0 {
        return;
    }
    const BAR: f64 = 36.0;
    const PREFERRED_H: f64 = 760.0;

    // How much of this monitor we're willing to take. Height is the tighter
    // budget of the two because of the menu bar and Dock.
    let (max_w, max_h) = window
        .current_monitor()
        .ok()
        .flatten()
        .map(|m| {
            let scale = m.scale_factor();
            let size = m.size();
            (size.width as f64 / scale * 0.92, size.height as f64 / scale * 0.86)
        })
        .unwrap_or((1200.0, 800.0));

    // Start from the phone-shaped default, then shrink on whichever axis binds
    // first. Without the width clamp a landscape source — which is what the
    // camera sends — solved to ~1290px wide from the fixed 760 height alone,
    // i.e. wider than the screen on any laptop display.
    let mut video_h = (PREFERRED_H - BAR).min(max_h - BAR);
    let mut width = video_h * w / h;
    if width > max_w {
        width = max_w;
        video_h = width * h / w;
    }
    let width = width.max(200.0).round();
    let height = (video_h + BAR).round();
    let _ = window.set_size(tauri::LogicalSize::new(width, height));

    // AppKit is main-thread-only; `on_started` runs on a tokio worker, so the
    // NSWindow call must be dispatched (set_size above proxies internally).
    //
    // Lock the ratio of the *content* box, bar included. Locking it to the bare
    // video ratio (what this did before) is off by the bar's 36px, so dragging
    // the window walked the video out of the shape it was given and
    // `object-contain` letterboxed a little more with every pixel of resize.
    let win = window.clone();
    let _ = window.run_on_main_thread(move || lock_aspect_native(&win, width, height));
}

#[cfg(target_os = "macos")]
fn lock_aspect_native(window: &tauri::WebviewWindow, w: f64, h: f64) {
    use objc2_app_kit::NSWindow;
    use objc2_foundation::NSSize;

    let Ok(ptr) = window.ns_window() else { return };
    // SAFETY: `ns_window()` returns a valid, retained `NSWindow*` for the
    // lifetime of the window; called on the main thread (see apply_aspect).
    unsafe {
        let ns_window: &NSWindow = &*(ptr as *const NSWindow);
        ns_window.setContentAspectRatio(NSSize { width: w, height: h });
    }
}

#[cfg(not(target_os = "macos"))]
fn lock_aspect_native(_window: &tauri::WebviewWindow, _w: f64, _h: f64) {}
