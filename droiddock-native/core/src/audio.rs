//! Phone → Mac audio over the app link (Wi-Fi path), the counterpart to
//! `AudioCapture.kt`.
//!
//! Wire protocol:
//!   Phone→Mac: `audio-started{sampleRate,channels,format}`, `audio-stopped`,
//!   `audio-error{error}`, plus binary kind-4 frames `[4][flags][PCM]` where
//!   flags bit0 marks the first chunk after a silent gap.
//!
//! Samples are raw interleaved s16le, not a compressed codec. That costs about
//! 192 KB/s next to a multi-megabit video stream on a LAN we are not short of,
//! and buys the absence of an entire failure class: no codec string to derive,
//! no decoder that can refuse to configure, nothing that can go silently wrong
//! the way a wrong `hvc1.*` string blanks the video path.
//!
//! Frames go to the **main** window rather than the mirror pop-out. Audio and
//! video are separate streams with separate lifetimes — the pop-out can be
//! closed, reopened, or never opened at all — and hanging playback off a window
//! that comes and goes would cut the sound every time.

use serde_json::Value;
use std::sync::Mutex;
use crate::host::ipc::{Channel, InvokeResponseBody};

/// Binary stream id for `dd_frame_cb` (see droiddock_core.h).
pub const STREAM_AUDIO: u32 = 2;
use crate::host::{AppHandle, Emitter, Manager};

const MAIN: &str = "main";

/// The main window's registered PCM channel, plus the last `audio-started` so a
/// window that attaches after the stream began still learns its format. Without
/// the replay, audio that starts during app launch would arrive as samples the
/// player has no sample rate for, and play at the wrong speed or not at all.
#[derive(Default)]
pub struct AudioState {
    frame_tx: Mutex<Option<Channel>>,
    last_started: Mutex<Option<Value>>,
}

impl AudioState {
    fn clear(&self) {
        *self.last_started.lock().unwrap() = None;
    }
}

/// Registers the main window's PCM delivery channel and replays a pending
/// `audio-started`. Same contract as `mirror::mirror_attach`: the frontend asks
/// when it is genuinely listening, rather than the backend guessing from load
/// timing and emitting into a webview that drops it.
pub fn audio_attach(app: AppHandle, state: crate::host::State<'_, AudioState>) -> Option<Value> {
    *state.frame_tx.lock().unwrap() = Some(Channel::new(&app, STREAM_AUDIO));
    state.last_started.lock().unwrap().clone()
}

/// True while the phone's audio is being played out of this Mac's speakers.
///
/// Read by [`crate::mac_media`], which infers "something is playing on the Mac"
/// from CoreAudio's output-is-running bit. That bit cannot tell one process
/// from another, so while this is true the Mac is making noise *because of the
/// phone* and the inference has to be abandoned rather than reported back as
/// the phone's own Now Playing state.
pub fn streaming(app: &AppHandle) -> bool {
    app.state::<AudioState>().last_started.lock().unwrap().is_some()
}

// ── Inbound forwarding (called from ws_server) ───────────────────────────

pub fn on_started(app: &AppHandle, payload: &Value) {
    eprintln!(
        "[audio] started {} Hz, {} ch, {}",
        payload.get("sampleRate").and_then(Value::as_i64).unwrap_or(0),
        payload.get("channels").and_then(Value::as_i64).unwrap_or(0),
        payload.get("format").and_then(Value::as_str).unwrap_or("?")
    );
    *app.state::<AudioState>().last_started.lock().unwrap() = Some(payload.clone());
    let _ = app.emit_to(MAIN, "audio-started", payload);
}

pub fn on_stopped(app: &AppHandle, payload: &Value) {
    app.state::<AudioState>().clear();
    let _ = app.emit_to(MAIN, "audio-stopped", payload);
}

pub fn on_error(app: &AppHandle, payload: &Value) {
    eprintln!(
        "[audio] error: {}",
        payload.get("error").and_then(Value::as_str).unwrap_or("?")
    );
    app.state::<AudioState>().clear();
    let _ = app.emit_to(MAIN, "audio-error", payload);
}

/// A kind-4 frame arrived: `[4][flags][PCM]`. Forwarded as raw `[flags][PCM]`,
/// matching `mirror::on_frame`'s layout so the frontend peels one header byte
/// in both cases. Dropped when nothing is attached — silence is the right
/// outcome for audio nobody is playing.
pub fn on_frame(app: &AppHandle, buf: &[u8]) {
    if buf.len() < 2 {
        return;
    }
    // The phone announces audio once per capture, but a live service that is resumed
    // (`mirror-start` onto a running screen session) or whose link dropped and came back
    // keeps capturing without a new `audio-started`: the Mac cleared its state on the drop and
    // the stream would play on with no format and no mute button. Audio is always the fixed
    // 48 kHz stereo s16le the phone documents (`AudioCapture.SAMPLE_RATE`), so announce it
    // locally, but only while a mirror is live so a straggler frame can't revive a closed one.
    if app.state::<AudioState>().last_started.lock().unwrap().is_none() && crate::mirror::is_live(app) {
        eprintln!("[audio] frames arrived without an audio-started; assuming 48 kHz stereo s16le");
        on_started(app, &serde_json::json!({ "sampleRate": 48000, "channels": 2, "format": "pcm_s16le" }));
    }
    let tx = app.state::<AudioState>().frame_tx.lock().unwrap().clone();
    if let Some(tx) = tx {
        tx.send_slice(&buf[1..]);
    }
}

/// Forget any live stream. Called when the link drops: the phone will send a
/// fresh `audio-started` on the next session, and replaying a stale one to a
/// window that attaches in between would start a player for a stream that no
/// longer exists.
pub fn on_disconnect(app: &AppHandle) {
    app.state::<AudioState>().clear();
    let _ = app.emit_to(MAIN, "audio-stopped", Value::Null);
}
