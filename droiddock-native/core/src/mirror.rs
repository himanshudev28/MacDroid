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
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Mutex;
use crate::host::ipc::Channel;
use crate::host::{AppHandle, Emitter, Manager};

/// Binary stream id for `dd_frame_cb` (see droiddock_core.h).
pub const STREAM_MIRROR: u32 = 1;

const LABEL: &str = "mirror";

/// Tracks the most recent `mirror-started` payload so a pop-out window that
/// hasn't finished loading yet (or is freshly created after the stream was
/// already announced) can replay it — mirrors `index.js`'s `lastMirrorStarted`
/// — plus the pop-out's registered frame channel (set by `mirror_attach` once
/// the window's JS is actually listening; raw video never rides plain events).
#[derive(Default)]
pub struct MirrorState {
    last_started: Mutex<Option<Value>>,
    frame_tx: Mutex<Option<Channel>>,
    /// Whether this WebView's `VideoDecoder` can actually decode HEVC, as
    /// reported by the frontend's `isConfigSupported` probe at startup.
    ///
    /// Defaults to false and is only ever raised by that probe, because the
    /// failure it guards is a silent one: asking the phone for H.265 that the
    /// decoder then refuses leaves a black pop-out with nothing in the log. It
    /// is far better to under-use HEVC than to negotiate a stream nothing here
    /// can play.
    hevc_supported: AtomicBool,
    /// Whether the host's mirror window is up and attached: set by
    /// `mirror_attach`, cleared by `mirror_closed`.
    window_open: AtomicBool,
    /// Mac-side hints for the next `mirror-start` (0 = none), set by the local
    /// pseudo-message `mirror-hints` (see `mirror_input`): the pop-out's pixel
    /// long edge, and the display's refresh rate. Used only when the user has
    /// not pinned an explicit value in settings.
    hint_max_size: AtomicU32,
    hint_max_fps: AtomicU32,
    /// The viewer is hidden: frames are dropped here, before they cross the FFI.
    paused: AtomicBool,
    /// `(serial, natural display size)` for `adb shell input` coordinate mapping.
    adb_screen: Mutex<Option<(String, (u32, u32))>>,
}

/// Phone capability: understands `mirror-pause` / `mirror-resume` (video only;
/// the encoder and projection stay alive, audio keeps playing).
pub const CAP_MIRROR_PAUSE: &str = "mirrorpause";

// ── Tauri commands (invoked from the frontend) ──────────────────────────

/// Ask the phone to start (screen or camera) and open/focus the pop-out
/// window. Mirrors `ipcMain.handle('mirror:popout', ...)`.
pub async fn mirror_popout(
    app: AppHandle,
    state: crate::host::State<'_, SharedState>,
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
        // Never ask for more frames than this Mac's display can show.
        "fps": match app.state::<MirrorState>().hint_max_fps.load(Ordering::Relaxed) {
            0 => cfg.mirror_fps.clamp(15, 120),
            cap => cfg.mirror_fps.clamp(15, 120).min(cap.max(15)),
        },
        // An explicit setting wins; otherwise size the stream to the window
        // (the phone reads this at encoder start only, so it is a start-time
        // decision). 0 = native, as before.
        "maxSize": if cfg.mirror_max_size > 0 {
            cfg.mirror_max_size
        } else if source == "camera" {
            0
        } else {
            app.state::<MirrorState>().hint_max_size.load(Ordering::Relaxed)
        },
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
    app.state::<MirrorState>().paused.store(false, Ordering::Relaxed);
    if !ws_server::push(&state, msg).await {
        return Err("Phone not linked over Wi-Fi".into());
    }
    open_mirror_window(&app)
}

/// Mirrors `ipcMain.handle('mirror:stop', ...)`: with the window open, asking
/// the host to close it is what actually ends the stream (the host calls
/// `mirror_closed` once it is gone); otherwise push `mirror-stop` directly.
pub async fn mirror_stop(app: AppHandle, state: crate::host::State<'_, SharedState>) -> Result<(), String> {
    if app.state::<MirrorState>().window_open.load(Ordering::Relaxed) {
        let _ = app.emit("mirror-window", json!({ "op": "close" }));
    } else {
        ws_server::push(&state, json!({ "type": "mirror-stop" })).await;
    }
    Ok(())
}

/// The host's mirror window was destroyed. This is the body of the old
/// `WindowEvent::Destroyed` handler: end the stream on the phone and reset the
/// replay state.
pub async fn mirror_closed(app: AppHandle) -> Result<(), String> {
    let st = app.state::<MirrorState>();
    st.window_open.store(false, Ordering::Relaxed);
    st.clear();
    let _ = app.emit("mirror-stopped", json!({}));
    // The same window serves both mirror sources, so closing it has to end
    // whichever one is running. `stop` is a no-op when the embedded ADB
    // session isn't the active one.
    crate::scrcpy_client::stop(&app);
    let state: SharedState = app.state::<SharedState>().inner().clone();
    ws_server::push(&state, json!({ "type": "mirror-stop" })).await;
    Ok(())
}

/// Tap / swipe / key / text / camera-flip from the Mac → pushed verbatim to
/// the phone. Mirrors `ipcMain.handle('mirror:input', (_e, msg) => wifi.push(msg))`
/// — the frontend sends the exact `{type, ...}` shape already.
pub async fn mirror_input(
    app: AppHandle,
    state: crate::host::State<'_, SharedState>,
    msg: Value,
) -> Result<(), String> {
    // Local pseudo-messages (never forwarded verbatim):
    //   `mirror-hints {maxSize, maxFps}`  hints for the next start.
    //   `mirror-pause` / `mirror-resume {source?}`  the viewer was hidden /
    //   shown. Frames are always dropped locally; the phone is told only if it
    //   advertised `mirrorpause`. An older phone keeps encoding and nothing is
    //   sent on resume. For embedded-scrcpy sessions only local frame-dropping
    //   happens (nothing is ever sent to the phone).
    match msg.get("type").and_then(Value::as_str) {
        Some("mirror-hints") => {
            let st = app.state::<MirrorState>();
            let n = |k: &str| msg.get(k).and_then(Value::as_u64).unwrap_or(0).min(8192) as u32;
            st.hint_max_size.store(n("maxSize"), Ordering::Relaxed);
            st.hint_max_fps.store(n("maxFps").min(240), Ordering::Relaxed);
            return Ok(());
        }
        Some("mirror-pause") => {
            // An embedded-scrcpy (ADB) session is only paused locally (frames are
            // dropped here); the Mac sends this only when the user opted in
            // (`droiddock.pauseHiddenAdbMirror`). The scrcpy server keeps
            // encoding and, with `control=false`, cannot be asked for an IDR.
            if app.state::<crate::scrcpy_client::ScrcpyState>().is_running() {
                app.state::<MirrorState>().paused.store(true, Ordering::Relaxed);
                return Ok(());
            }
            app.state::<MirrorState>().paused.store(true, Ordering::Relaxed);
            if ws_server::phone_has_cap(&state, CAP_MIRROR_PAUSE).await {
                ws_server::push(&state, json!({ "type": "mirror-pause" })).await;
            }
            return Ok(());
        }
        Some("mirror-resume") => {
            app.state::<MirrorState>().paused.store(false, Ordering::Relaxed);
            // Only a phone that advertised `mirrorpause` is told. For an older
            // phone nothing is sent: a bare `mirror-start` could launch the
            // phone's consent flow and clobber its stored quality settings, so
            // the decoder simply waits for the next natural keyframe.
            if !app.state::<crate::scrcpy_client::ScrcpyState>().is_running()
                && ws_server::phone_has_cap(&state, CAP_MIRROR_PAUSE).await
            {
                ws_server::push(&state, json!({ "type": "mirror-resume" })).await;
            }
            return Ok(());
        }
        _ => {}
    }
    // Screen control. The phone's accessibility service is the Wi-Fi route, but it is often
    // off, and the embedded scrcpy session never had one. When an adb device for this phone is
    // available, `adb shell input` does the same job with no phone-side setup.
    if matches!(
        msg.get("type").and_then(Value::as_str),
        Some("mirror-tap" | "mirror-swipe" | "mirror-key" | "mirror-text")
    ) {
        if let Some((adb_bin, serial)) = adb_target(&app) {
            match adb_input(&app, &adb_bin, &serial, &msg).await {
                Ok(()) => return Ok(()),
                Err(e) => eprintln!("[mirror] adb input failed: {e}"),
            }
        } else if !ws_server::push(&state, msg).await {
            return Err("Phone not linked".into());
        } else {
            return Ok(());
        }
    }
    ws_server::push(&state, msg).await;
    Ok(())
}

/// The adb binary and serial that control this phone, if any: the running embedded scrcpy
/// session's device, else the only online adb device (never guessed among several).
pub(crate) fn adb_target(app: &AppHandle) -> Option<(String, String)> {
    let st = app.state::<crate::adb::AdbState>();
    let bin = st.adb.lock().unwrap().clone()?;
    if let Some(s) = app.state::<crate::scrcpy_client::ScrcpyState>().serial() {
        return Some((bin, s));
    }
    let devs = st.devices.lock().unwrap();
    let mut online = devs.iter().filter(|d| d.state == "device");
    let first = online.next()?;
    if online.next().is_some() {
        return None;
    }
    Some((bin, first.serial.clone()))
}

/// Single-quote for the phone's shell (adb joins its arguments into one command line).
fn sh_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// Pixel size of the display as currently oriented, from the natural size and the stream's aspect.
fn oriented(natural: (u32, u32), stream: Option<(i64, i64)>) -> (f64, f64) {
    let (long, short) = (natural.0.max(natural.1) as f64, natural.0.min(natural.1) as f64);
    match stream {
        Some((w, h)) if w > h => (long, short),
        _ => (short, long),
    }
}

async fn adb_input(app: &AppHandle, adb_bin: &str, serial: &str, msg: &Value) -> Result<(), String> {
    let f = |k: &str| msg.get(k).and_then(Value::as_f64).unwrap_or(0.0).clamp(0.0, 1.0);
    match msg.get("type").and_then(Value::as_str) {
        Some("mirror-key") => {
            let code = match msg.get("key").and_then(Value::as_str) {
                Some("back") => "4",
                Some("home") => "3",
                Some("recents") => "187",
                Some("lock") => "223",
                other => return Err(format!("unsupported key {other:?}")),
            };
            crate::adb::shell_input(adb_bin, serial, &["input", "keyevent", code]).await.map(|_| ())
        }
        Some("mirror-text") => match msg.get("op").and_then(Value::as_str) {
            Some("backspace") => crate::adb::shell_input(adb_bin, serial, &["input", "keyevent", "67"]).await.map(|_| ()),
            Some("enter") => crate::adb::shell_input(adb_bin, serial, &["input", "keyevent", "66"]).await.map(|_| ()),
            _ => {
                let text = msg.get("text").and_then(Value::as_str).unwrap_or("");
                // `input text` takes ASCII only; `%s` is its space.
                if text.is_empty() || !text.is_ascii() {
                    return Err("this character can't be typed over adb".into());
                }
                let arg = sh_quote(&text.replace('%', "%25").replace(' ', "%s"));
                crate::adb::shell_input(adb_bin, serial, &["input", "text", &arg]).await.map(|_| ())
            }
        },
        Some(kind @ ("mirror-tap" | "mirror-swipe")) => {
            let st = app.state::<MirrorState>();
            let cached = st.adb_screen.lock().unwrap().clone().filter(|(s, _)| s == serial).map(|(_, d)| d);
            let natural = match cached {
                Some(d) => d,
                None => {
                    let d = crate::adb::display_size(adb_bin, serial).await.ok_or("couldn't read the phone's screen size")?;
                    *st.adb_screen.lock().unwrap() = Some((serial.to_string(), d));
                    d
                }
            };
            let stream = st.last_started.lock().unwrap().as_ref().map(|v| {
                (v.get("width").and_then(Value::as_i64).unwrap_or(0), v.get("height").and_then(Value::as_i64).unwrap_or(0))
            });
            let (dw, dh) = oriented(natural, stream);
            let px = |frac: f64, dim: f64| ((frac * dim).round().min(dim - 1.0).max(0.0) as i64).to_string();
            if kind == "mirror-tap" {
                let (x, y) = (px(f("x"), dw), px(f("y"), dh));
                crate::adb::shell_input(adb_bin, serial, &["input", "tap", &x, &y]).await.map(|_| ())
            } else {
                let dur = msg.get("dur").and_then(Value::as_i64).unwrap_or(120).clamp(30, 1500).to_string();
                let (x1, y1, x2, y2) = (px(f("x1"), dw), px(f("y1"), dh), px(f("x2"), dw), px(f("y2"), dh));
                crate::adb::shell_input(adb_bin, serial, &["input", "swipe", &x1, &y1, &x2, &y2, &dur]).await.map(|_| ())
            }
        }
        _ => Err("not an input message".into()),
    }
}

/// Called by the pop-out once its JS is mounted and listening: registers the
/// raw-frame delivery channel and hands back the pending `mirror-started`
/// payload if the phone announced the stream while the window was still
/// loading. This is the Tauri equivalent of `index.js`'s `did-finish-load`
/// replay, made deterministic — an event emitted at build time would be
/// dropped by the not-yet-loaded webview, so the frontend asks when it is
/// actually ready instead of the backend guessing from load timing.
pub fn mirror_attach(app: AppHandle, state: crate::host::State<'_, MirrorState>) -> Option<Value> {
    *state.frame_tx.lock().unwrap() = Some(Channel::new(&app, STREAM_MIRROR));
    state.window_open.store(true, Ordering::Relaxed);
    state.last_started.lock().unwrap().clone()
}

/// The frontend's one-time WebCodecs probe result. Called from the main
/// window at startup; until it arrives, H.265 is simply never requested.
pub fn mirror_set_hevc_supported(state: crate::host::State<'_, MirrorState>, supported: bool) {
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
    // The host opens (or focuses) the window, then calls `mirror_attach`.
    app.emit("mirror-window", json!({ "op": "open" })).map_err(|e| e.to_string())
}

impl MirrorState {
    pub(crate) fn clear(&self) {
        *self.last_started.lock().unwrap() = None;
        *self.frame_tx.lock().unwrap() = None;
        self.paused.store(false, Ordering::Relaxed);
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

/// The embedded scrcpy stream changed orientation: keep the input mapping honest.
pub fn note_size(app: &AppHandle, width: u32, height: u32) {
    if let Some(Value::Object(o)) = app.state::<MirrorState>().last_started.lock().unwrap().as_mut() {
        o.insert("width".into(), width.into());
        o.insert("height".into(), height.into());
    }
}

/// Whether a mirror stream is announced (between `mirror-started` and stop).
pub(crate) fn is_live(app: &AppHandle) -> bool {
    app.state::<MirrorState>().last_started.lock().unwrap().is_some()
}

/// A kind-3 binary frame arrived: `[3][flags][H.264 access unit]`, flags
/// bit0 = keyframe. Forwarded to the pop-out's attached channel as raw
/// `[flags][payload]` bytes — Tauri delivers ≥1 KiB raw channel payloads via
/// its fetch fast-path as an ArrayBuffer, ordered by a per-message index, so
/// no base64/JSON cost per frame at 30 fps. Dropped when no window is
/// attached (yet), same as `index.js` dropping `mirror-frame` with no
/// `mirrorWin`.
pub fn on_frame(app: &AppHandle, buf: &[u8]) {
    if buf.len() < 2 || app.state::<MirrorState>().paused.load(Ordering::Relaxed) {
        return;
    }
    // Borrowed straight through to the host callback: no per-frame `to_vec()`.
    if let Some(tx) = app.state::<MirrorState>().frame_tx.lock().unwrap().as_ref() {
        tx.send_slice(&buf[1..]);
    }
}

#[cfg(test)]
mod input_tests {
    use super::*;

    #[test]
    fn shell_quoting_survives_quotes() {
        assert_eq!(sh_quote("a b"), "'a b'");
        assert_eq!(sh_quote("it's"), "'it'\\''s'");
    }

    #[test]
    fn orientation_follows_the_stream_aspect() {
        assert_eq!(oriented((1080, 2340), Some((1080, 2340))), (1080.0, 2340.0));
        assert_eq!(oriented((1080, 2340), Some((2340, 1080))), (2340.0, 1080.0));
        assert_eq!(oriented((1080, 2340), None), (1080.0, 2340.0));
    }
}
