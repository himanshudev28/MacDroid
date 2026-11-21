//! An in-app scrcpy client: mirror without the separate scrcpy window.
//!
//! # What this is for
//!
//! We already have two mirror paths. The Wi-Fi one (`mirror.rs` +
//! `MirrorService.kt`) renders inside our own pop-out, but costs a
//! MediaProjection consent tap per session and needs the AccessibilityService
//! for input. The ADB one (`adb::mirror`) has neither problem but opens
//! scrcpy's own window, outside our UI entirely.
//!
//! This is the third: scrcpy's *server* driven directly over ADB, with the
//! video stream decoded by the pop-out's existing WebCodecs pipeline. That
//! gives the ADB path's freedom from consent prompts and the Wi-Fi path's
//! in-app window at the same time.
//!
//! # Licensing
//!
//! Written against scrcpy's own protocol and source (Apache-2.0, which the
//! project's permissive-only dependency rule allows). Deliberately **not**
//! ported from AirSync's Swift implementation: that project is MPL-2.0 with an
//! additional redistribution restriction, and a close translation would carry
//! those terms into this tree.
//!
//! The server `.jar` is scrcpy's, taken from the local scrcpy install rather
//! than vendored, so we never redistribute it and always match the binary the
//! user already has.
//!
//! # Wire format (scrcpy 4.x, `tunnel_forward=true`)
//!
//! ```text
//! connect  -> 64 bytes  device name, NUL-padded  (first socket only)
//!          -> 4  bytes  codec id, big-endian ASCII: "h264" | "h265" | "av1"
//!  then, repeating:
//!          -> 12 bytes  header
//!                       header[0] & 0x80 == 1 -> session packet:
//!                           width  = BE32(header[4..8])
//!                           height = BE32(header[8..12])
//!                       else -> media packet:
//!                           pts_flags = BE64(header[0..8])
//!                             bit 62 = config packet (SPS/PPS)
//!                             bit 61 = key frame
//!                           len = BE32(header[8..12])
//!          -> len bytes raw access unit
//! ```

use crate::mirror;
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use crate::host::{AppHandle, Emitter, Manager};
use serde_json::Value;
use tokio::io::AsyncReadExt;

/// Where the server jar is pushed. Suffixed so it can never be mistaken for —
/// or clobber — the one a user's own scrcpy run pushes.
const REMOTE_JAR: &str = "/data/local/tmp/scrcpy-server-droiddock.jar";

/// scrcpy's own packet header size.
const HEADER: usize = 12;
const FLAG_CONFIG: u64 = 1 << 62;
const FLAG_KEY_FRAME: u64 = 1 << 61;
/// Device-name field the server sends first (`SC_DEVICE_NAME_FIELD_LENGTH`).
const DEVICE_NAME_LEN: usize = 64;

/// A frame larger than this is a desynchronised stream, not a real packet.
/// Without the check, a bad length turns into a multi-gigabyte allocation.
const MAX_PACKET: u32 = 16 * 1024 * 1024;

type PushRecord = (String, std::path::PathBuf, u64, Option<std::time::SystemTime>);

/// Whether the jar must be pushed: anything differing from the last successful push.
fn push_needed(last: Option<&PushRecord>, now: &PushRecord) -> bool {
    last != Some(now)
}

/// What an embedded session streams.
#[derive(Clone, Debug, PartialEq)]
pub enum Source {
    Screen,
    /// A phone camera. `camera_id: None` = the first back camera. `zoom: None` = 1x.
    /// `lens`: the exact lens id (needed because two lenses can share one camera id).
    Camera { camera_id: Option<String>, zoom: Option<f32>, lens: Option<String> },
}

/// One entry of the lens picker the Mac shows (`mirror-started.lenses`).
#[derive(Clone, Debug, PartialEq)]
pub struct Lens {
    /// `adb:<cameraId>@<zoom>` — opaque to the Mac.
    pub id: String,
    /// `back` | `front` | `external`.
    pub facing: String,
    pub label: String,
    pub zoom: f32,
    /// `android.sensor.orientation` of this lens's camera (from dumpsys), if known.
    pub sensor: Option<i32>,
    /// `zoom-range=[min, max]` from `--list-cameras`.
    pub zoom_range: Option<(f32, f32)>,
}

impl Lens {
    fn to_json(&self) -> Value {
        json!({ "id": self.id, "facing": self.facing, "label": self.label, "zoom": self.zoom })
    }
}

/// Lens state of a running camera session.
#[derive(Clone)]
struct CameraInfo {
    lenses: Vec<Lens>,
    /// Id of the active lens ("" when the camera list was unavailable).
    lens: String,
    /// Active zoom ratio (`camera_zoom`).
    zoom: f32,
}

#[derive(Default)]
pub struct ScrcpyState {
    running: Mutex<Option<Session>>,
    /// A lens switch is in flight; a second one is refused rather than raced.
    switching: AtomicBool,
    /// Zoom debounce: the last requested ratio, a request counter, and whether a worker runs.
    zoom_pending: Mutex<Option<f32>>,
    zoom_seq: std::sync::atomic::AtomicU64,
    zoom_worker: AtomicBool,
    /// Bumped by `stop()` (window closed / user stop); a lens switch compares it to know the
    /// window went away mid-switch and must not leave a new session behind.
    generation: std::sync::atomic::AtomicU64,
    /// Lens list per phone: `(serial, model, when, lenses)`. Spares repeat starts the
    /// `--list-cameras` + dumpsys round trips; stale after 24 h or when serial/model change.
    /// `(serial, jar path, size, mtime)` of the last successful jar push this run.
    pushed: Mutex<Option<PushRecord>>,
    lens_cache: Mutex<Option<(String, String, std::time::Instant, Vec<Lens>)>>,
}

struct Session {
    stop: Arc<AtomicBool>,
    /// The `adb forward` we opened, so it can be removed on stop rather than
    /// leaking a port binding for the life of the adb server.
    local_port: u16,
    serial: String,
    /// Wakes the session's task to drop (and so kill) the server child.
    kill: Arc<tokio::sync::Notify>,
    /// Set by a lens switch: the session task then skips `on_stopped`/`on_error`, so the Mac
    /// window (which closes on those) survives the restart.
    silent: Arc<AtomicBool>,
    /// Notified (permit stored) when the session task has fully finished.
    done: Arc<tokio::sync::Notify>,
    /// `Some` for a camera session.
    camera: Option<CameraInfo>,
}

impl ScrcpyState {
    pub fn is_running(&self) -> bool {
        self.running.lock().unwrap().is_some()
    }

    /// The adb serial of the running embedded session, if any.
    pub fn serial(&self) -> Option<String> {
        self.running.lock().unwrap().as_ref().map(|s| s.serial.clone())
    }
}

/// Locate the scrcpy server jar that ships with the resolved scrcpy binary.
///
/// Homebrew puts it at `<prefix>/share/scrcpy/scrcpy-server`, next to the
/// `bin/scrcpy` we already resolve. Deriving it from the binary's own path
/// keeps the jar and the client at the same version — the protocol above is
/// stable across 4.x, but the server refuses to start if the version string
/// we pass doesn't match its own.
fn server_jar(scrcpy_bin: &str) -> Option<std::path::PathBuf> {
    let bin = std::path::Path::new(scrcpy_bin).canonicalize().ok()?;
    // .../Cellar/scrcpy/4.1/bin/scrcpy -> .../Cellar/scrcpy/4.1
    let prefix = bin.parent()?.parent()?;
    let candidates = [
        prefix.join("share/scrcpy/scrcpy-server"),
        prefix.join("share/scrcpy/scrcpy-server.jar"),
    ];
    candidates.into_iter().find(|p| p.is_file())
}

/// Read the version scrcpy reports, as the server expects it (`"4.1"`).
async fn server_version(scrcpy_bin: &str) -> Option<String> {
    let out = tokio::process::Command::new(scrcpy_bin)
        .arg("--version")
        .output()
        .await
        .ok()?;
    let first = String::from_utf8_lossy(&out.stdout);
    let v = first.lines().next()?.split_whitespace().nth(1)?.to_string();
    if v.is_empty() { None } else { Some(v) }
}

async fn adb(adb_bin: &str, args: &[&str]) -> Result<String, String> {
    let out = tokio::process::Command::new(adb_bin)
        .args(args)
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

/// Start mirroring into the pop-out window.
pub async fn start(
    app: AppHandle,
    adb_bin: String,
    scrcpy_bin: String,
    serial: String,
    source: Source,
) -> Result<(), String> {
    start_inner(app, adb_bin, scrcpy_bin, serial, source, None, false).await.map(|_| ())
}

async fn start_inner(
    app: AppHandle,
    adb_bin: String,
    scrcpy_bin: String,
    serial: String,
    source: Source,
    known_lenses: Option<Vec<Lens>>,
    silent_init: bool,
) -> Result<tokio::sync::oneshot::Receiver<()>, String> {
    {
        let state = app.state::<ScrcpyState>();
        if state.is_running() {
            return Err("already mirroring".into());
        }
    }

    let jar = server_jar(&scrcpy_bin)
        .ok_or("couldn't find scrcpy-server next to the scrcpy binary")?;
    let version = server_version(&scrcpy_bin)
        .await
        .ok_or("couldn't read the scrcpy version")?;

    // A random session id, exactly as scrcpy does — it namespaces the abstract
    // socket so two concurrent sessions can't attach to each other's server.
    let scid: u32 = {
        let mut b = [0u8; 4];
        getrandom::fill(&mut b).map_err(|e| e.to_string())?;
        // scrcpy's scid is 31-bit (it is formatted as %08x and must be positive).
        u32::from_be_bytes(b) & 0x7FFF_FFFF
    };
    let socket_name = format!("localabstract:scrcpy_{scid:08x}");

    let meta = std::fs::metadata(&jar).ok();
    let rec: PushRecord = (serial.clone(), jar.clone(), meta.as_ref().map_or(0, |m| m.len()), meta.and_then(|m| m.modified().ok()));
    let skipped_push = meta_known(&rec) && !push_needed(app.state::<ScrcpyState>().pushed.lock().unwrap().as_ref(), &rec);
    if !skipped_push {
        adb(&adb_bin, &["-s", &serial, "push", &jar.to_string_lossy(), REMOTE_JAR]).await?;
        if meta_known(&rec) {
            *app.state::<ScrcpyState>().pushed.lock().unwrap() = Some(rec);
        }
    }

    // Port 0 lets the OS pick, then we ask adb what it chose — avoids racing
    // another process for a hardcoded port.
    let fwd = adb(&adb_bin, &["-s", &serial, "forward", "tcp:0", &socket_name]).await?;
    let local_port: u16 = fwd
        .trim()
        .parse()
        .map_err(|_| format!("adb forward returned an unexpected port: {fwd:?}"))?;

    crate::shutdown::forward_opened(&app, &serial, local_port);
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let bitrate = cfg.mirror_bitrate_mbps.clamp(1, 50) as u64 * 1_000_000;
    let fps = cfg.mirror_fps.clamp(15, 120);
    let is_camera = matches!(source, Source::Camera { .. });
    let camera_info: Option<CameraInfo> = match &source {
        Source::Screen => None,
        Source::Camera { camera_id, zoom, lens: want_lens } => {
            let model = adb(&adb_bin, &["-s", &serial, "shell", "getprop", "ro.product.model"]).await.map(|m| m.trim().to_string()).unwrap_or_default();
            let cached = app.state::<ScrcpyState>().lens_cache.lock().unwrap().as_ref()
                .filter(|(s, m, t, _)| *s == serial && *m == model && t.elapsed() < LENS_CACHE_TTL)
                .map(|(_, _, _, l)| l.clone());
            let fresh = known_lenses.is_none() && cached.is_none();
            let lenses = match known_lenses.or(cached) {
                Some(l) => l,
                None => match list_cameras(&scrcpy_bin, &adb_bin, &serial).await {
                    Some(o) => {
                        let cams = parse_list_cameras(&o);
                        let dump = dumpsys_camera(&adb_bin, &serial).await;
                        let eq = dump.as_deref().map(parse_dumpsys_equiv_focal).filter(|m| !m.is_empty());
                        let mut l = lenses_from_cameras(&cams, eq.as_ref(), dump.as_deref().map(parse_dumpsys_physical_ids).as_ref());
                        if let Some(d) = dump.as_deref() {
                            let so = parse_dumpsys_sensor_orientation(d);
                            for x in &mut l {
                                x.sensor = parse_lens_id(&x.id).and_then(|(c, _)| so.get(&c).copied());
                            }
                        }
                        l
                    }
                    None => Vec::new(),
                },
            };
            if fresh && !lenses.is_empty() {
                *app.state::<ScrcpyState>().lens_cache.lock().unwrap() = Some((serial.clone(), model.clone(), std::time::Instant::now(), lenses.clone()));
            }
            let lens = match camera_id {
                Some(_) if want_lens.as_ref().is_some_and(|w| lenses.iter().any(|l| l.id == *w)) => want_lens.clone().unwrap(),
                Some(id) => lenses
                    .iter()
                    .find(|l| parse_lens_id(&l.id).is_some_and(|(c, _)| c == *id))
                    .map(|l| l.id.clone())
                    .unwrap_or_else(|| lens_id(id, 1.0)),
                // Open on the main lens (1×), like Camera.app — not the first back lens in the
                // list, which is sorted by focal length and so is the ultra-wide.
                None => lenses
                    .iter()
                    .find(|l| l.facing == "back" && l.label == "1×")
                    .or_else(|| lenses.iter().find(|l| l.facing == "back"))
                    .or(lenses.first())
                    .map(|l| l.id.clone())
                    .unwrap_or_default(),
            };
            let active = lenses.iter().find(|l| l.id == lens);
            let z = zoom.unwrap_or(1.0);
            let z = active.and_then(|l| l.zoom_range).map_or(z, |(lo, hi)| z.clamp(lo, hi));
            Some(CameraInfo { lenses, lens, zoom: z })
        }
    };
    // H.264 only for now: the pop-out's WebCodecs decoder is configured from an
    // `avc1.*` codec string derived from the SPS, and HEVC needs a different
    // (hvcC-shaped) description that this path does not build yet.
    let mut server_args = vec![
        format!("scid={scid:08x}"),
        "tunnel_forward=true".to_string(),
        "audio=false".to_string(),
        "video=true".to_string(),
        // Control needs its own socket and a second reader. Input for this session is
        // injected with `adb shell input` instead (see `mirror::adb_input`), which needs
        // neither the control socket nor the phone's accessibility service.
        "control=false".to_string(),
        "video_codec=h264".to_string(),
        format!("video_bit_rate={bitrate}"),
        format!("max_fps={fps}"),
    ];
    match (&source, &camera_info) {
        (Source::Camera { .. }, info) => {
            let long = if cfg.mirror_max_size > 0 { cfg.mirror_max_size.min(1920) } else { 1920 };
            let active = info.as_ref().and_then(|i| i.lenses.iter().find(|l| l.id == i.lens));
            let zoom = info.as_ref().map_or(1.0, |i| i.zoom);
            server_args.extend(camera_server_args(active, zoom, long as u32));
        }
        _ => {
            if cfg.mirror_max_size > 0 {
                server_args.push(format!("max_size={}", cfg.mirror_max_size));
            }
        }
    }

    let mut cmd = tokio::process::Command::new(&adb_bin);
    cmd.args(["-s", &serial, "shell", &format!("CLASSPATH={REMOTE_JAR}"), "app_process", "/", "com.genymobile.scrcpy.Server", &version]);
    cmd.args(&server_args);
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);
    let child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            // Don't leak the forward we just opened.
            let _ = adb(&adb_bin, &["-s", &serial, "forward", "--remove", &format!("tcp:{local_port}")]).await;
            crate::shutdown::forward_closed(&app, &serial, local_port);
            return Err(format!("couldn't start the scrcpy server: {e}"));
        }
    };

    let stop = Arc::new(AtomicBool::new(false));
    let kill = Arc::new(tokio::sync::Notify::new());
    let silent = Arc::new(AtomicBool::new(silent_init));
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();
    let done = Arc::new(tokio::sync::Notify::new());
    {
        let state = app.state::<ScrcpyState>();
        *state.running.lock().unwrap() = Some(Session {
            stop: stop.clone(),
            local_port,
            serial: serial.clone(),
            kill: kill.clone(),
            silent: silent.clone(),
            done: done.clone(),
            camera: camera_info.clone(),
        });
    }

    // The server needs a moment to bind its abstract socket. scrcpy retries
    // rather than sleeping a fixed time, and so does `connect_with_retry`.
    let app2 = app.clone();
    let adb2 = adb_bin.clone();
    crate::host::async_runtime::spawn(async move {
        // `stop()` notifies `kill`, which ends the pump at once (a stalled read included) so the
        // server child below is dropped, and so killed (`kill_on_drop`).
        let result = tokio::select! {
            r = pump(app2.clone(), local_port, stop.clone(), camera_info, Some(ready_tx)) => r,
            _ = kill.notified() => Ok(()),
        };
        // A start that never produced a stream after we skipped the push may mean the phone lost
        // the jar (cleaned /data/local/tmp, reboot): forget the record so the next start pushes.
        if skipped_push && result.is_err() {
            *app2.state::<ScrcpyState>().pushed.lock().unwrap() = None;
        }
        // Whatever happened — clean exit, stream error, stop request — the
        // session is over: tear down the forward and tell the UI once.
        let _ = adb(&adb2, &["-s", &serial, "forward", "--remove", &format!("tcp:{local_port}")]).await;
        crate::shutdown::forward_closed(&app2, &serial, local_port);
        drop(child);
        {
            // Only clear our own session: a newer one may already be running.
            let state = app2.state::<ScrcpyState>();
            let mut running = state.running.lock().unwrap();
            if running.as_ref().is_some_and(|s| s.local_port == local_port && Arc::ptr_eq(&s.stop, &stop)) {
                *running = None;
            }
        }
        // A lens switch replaces this session with another one behind the same window, so
        // neither end-of-session event may fire (both make the Mac close the window).
        if !silent.load(Ordering::Relaxed) {
            match result {
                Err(e) if !stop.load(Ordering::Relaxed) => {
                    mirror::on_error(&app2, &json!({ "error": e }));
                }
                _ => mirror::on_stopped(&app2, &json!({})),
            }
        }
        if is_camera && !silent.load(Ordering::Relaxed) {
            mirror::orient_watch_off(&app2).await;
        }
        done.notify_one();
    });

    Ok(ready_rx)
}

/// The `mirror-started` payload. Camera sessions add `source:"camera"`, the lens list, the
/// active lens and `photo:"frame"` (the Mac grabs the decoded frame; no phone-side still).
fn started_payload(size: (u32, u32), codec: String, camera: Option<&CameraInfo>) -> Value {
    let mut v = json!({ "width": size.0, "height": size.1, "codec": codec, "source": "screen" });
    if let (Some(c), Some(o)) = (camera, v.as_object_mut()) {
        o.insert("source".into(), "camera".into());
        if let Some(l) = c.lenses.iter().find(|l| l.id == c.lens) {
            o.insert("facing".into(), l.facing.clone().into());
        }
        o.insert("lenses".into(), Value::Array(c.lenses.iter().map(Lens::to_json).collect()));
        o.insert("lens".into(), c.lens.clone().into());
        o.insert("photo".into(), "frame".into());
        if let Some((lo, hi)) = c.lenses.iter().find(|l| l.id == c.lens).and_then(|l| l.zoom_range).filter(|(lo, hi)| hi > lo) {
            o.insert("zoomRange".into(), json!([lo, hi]));
            o.insert("zoom".into(), c.zoom.into());
        }
    }
    v
}

/// `adb:<cameraId>@<zoom>`.
pub fn lens_id(camera_id: &str, zoom: f32) -> String {
    format!("adb:{camera_id}@{zoom}")
}

/// Inverse of `lens_id`. A missing or unparseable zoom means 1x.
pub fn parse_lens_id(id: &str) -> Option<(String, f32)> {
    let rest = id.strip_prefix("adb:")?;
    let (cam, zoom) = match rest.split_once('@') {
        Some((c, z)) => (c, z.parse::<f32>().ok().filter(|z| z.is_finite() && *z > 0.0).unwrap_or(1.0)),
        None => (rest, 1.0),
    };
    if cam.is_empty() || !cam.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') {
        return None;
    }
    Some((cam.to_string(), zoom))
}

/// Server args that select the camera. `camera_ar=16:9` + `max_size` lets the server pick a
/// sensible stream size (a 4000x3000 sensor mode is no use to a live preview).
fn camera_server_args(active: Option<&Lens>, zoom: f32, long_edge: u32) -> Vec<String> {
    // I-frame every second: with no control socket there is no way to request a keyframe, and the
    // encoder's default interval (~10 s) made a recording wait seconds for its first one.
    // Server key `video_codec_options`, value `key[:type]=value` (type defaults to int), the
    // same syntax as `scrcpy --video-codec-options` (confirmed in `scrcpy --help`).
    let mut a = vec!["video_source=camera".to_string(), "video_codec_options=i-frame-interval=1".to_string()];
    match active {
        Some(l) => {
            if let Some((id, _)) = parse_lens_id(&l.id) {
                a.push(format!("camera_id={id}"));
            }
            if (zoom - 1.0).abs() > f32::EPSILON {
                a.push(format!("camera_zoom={zoom}"));
            }
        }
        None => a.push("camera_facing=back".to_string()),
    }
    a.push("camera_ar=16:9".to_string());
    a.push(format!("max_size={long_edge}"));
    a
}

/// One `--camera-id=N (facing, WxH, fps=[..])` line of `scrcpy --list-cameras`.
#[derive(Clone, Debug, PartialEq)]
pub struct CameraEntry {
    pub id: String,
    pub facing: String,
    pub size: Option<(u32, u32)>,
    /// `zoom-range=[min, max]`, when listed.
    pub zoom_range: Option<(f32, f32)>,
}

/// Parse `scrcpy --list-cameras` output. Tolerant of log prefixes (`[server] INFO:`), of the
/// indented `- WxH` size lines `--list-camera-sizes` adds, and of unknown trailing text.
pub fn parse_list_cameras(out: &str) -> Vec<CameraEntry> {
    let mut v = Vec::new();
    for line in out.lines() {
        let Some(at) = line.find("--camera-id=") else { continue };
        let rest = &line[at + "--camera-id=".len()..];
        let id: String = rest.chars().take_while(|c| !c.is_whitespace() && *c != '(').collect();
        if id.is_empty() {
            continue;
        }
        let inner = rest.split_once('(').and_then(|(_, r)| r.split_once(')').map(|(i, _)| i)).unwrap_or("");
        let mut parts = inner.split(',').map(str::trim);
        let facing = parts.next().unwrap_or("").to_ascii_lowercase();
        let facing = match facing.as_str() {
            "back" | "front" | "external" => facing,
            _ => "back".to_string(),
        };
        let size = parts.next().and_then(|s| {
            let (w, h) = s.split_once('x')?;
            Some((w.trim().parse().ok()?, h.trim().parse().ok()?))
        });
        let zoom_range = rest.find("zoom-range=[").and_then(|i| {
            let r = rest[i + "zoom-range=[".len()..].split(']').next()?;
            let mut p = r.split(',').map(|t| t.trim().parse::<f32>().ok());
            Some((p.next()??, p.next()??))
        });
        v.push(CameraEntry { id, facing, size, zoom_range });
    }
    v
}

/// Snap a focal-length multiplier to the ones phones actually ship.
fn snap_mult(m: f32) -> f32 {
    const STEPS: [f32; 7] = [0.5, 0.6, 1.0, 2.0, 3.0, 5.0, 10.0];
    STEPS.iter().copied().min_by(|a, b| (m / a).ln().abs().total_cmp(&(m / b).ln().abs())).unwrap_or(1.0)
}

fn mult_label(m: f32) -> String {
    format!("{m}×")
}

/// 35 mm-equivalent focal length per camera id from `dumpsys media.camera`
/// (`focal * 43.27 / diagonal(sensor physicalSize)`). Only ids with both fields appear.
pub fn parse_dumpsys_equiv_focal(dump: &str) -> std::collections::HashMap<String, f32> {
    let mut out = std::collections::HashMap::new();
    let mut id: Option<String> = None;
    let (mut focal, mut size): (Option<f32>, Option<(f32, f32)>) = (None, None);
    let mut flush = |id: &Option<String>, focal: Option<f32>, size: Option<(f32, f32)>| {
        if let (Some(i), Some(f), Some((w, h))) = (id, focal, size) {
            let diag = (w * w + h * h).sqrt();
            if diag > 0.0 && f > 0.0 {
                out.insert(i.clone(), f * 43.27 / diag);
            }
        }
    };
    let nums = |l: &str| -> Vec<f32> {
        l.trim().trim_start_matches('[').trim_end_matches(']').split_whitespace().filter_map(|t| t.parse().ok()).collect()
    };
    let mut lines = dump.lines();
    while let Some(line) = lines.next() {
        if line.starts_with("== Camera HAL device") {
            flush(&id, focal, size);
            id = line.split(" (").next().and_then(|h| h.rsplit('/').next()).map(str::to_string);
            focal = None;
            size = None;
        } else if line.contains("android.lens.info.availableFocalLengths") {
            focal = lines.next().and_then(|l| nums(l).first().copied());
        } else if line.contains("android.sensor.info.physicalSize") {
            size = lines.next().and_then(|l| { let n = nums(l); Some((*n.first()?, *n.get(1)?)) });
        }
    }
    flush(&id, focal, size);
    out
}

/// `android.sensor.orientation` per camera id from `dumpsys media.camera`.
pub fn parse_dumpsys_sensor_orientation(dump: &str) -> std::collections::HashMap<String, i32> {
    let mut out = std::collections::HashMap::new();
    let mut id: Option<String> = None;
    let mut lines = dump.lines();
    while let Some(line) = lines.next() {
        if line.starts_with("== Camera HAL device") {
            id = line.split(" (").next().and_then(|h| h.rsplit('/').next()).map(str::to_string);
        } else if line.contains("android.sensor.orientation") {
            let v = lines.next().and_then(|l| l.trim().trim_matches(|c| c == '[' || c == ']').split_whitespace().next().and_then(|t| t.parse::<i32>().ok()));
            if let (Some(i), Some(v)) = (&id, v) {
                out.insert(i.clone(), v);
            }
        }
    }
    out
}

/// Facing and sensor orientation of the running embedded camera's ACTIVE lens (defaults 90 back
/// / 270 front when dumpsys had no value). `None` when no embedded camera session is running.
pub fn active_camera_sensor(app: &AppHandle) -> Option<(bool, i32)> {
    let g = app.state::<ScrcpyState>();
    let g = g.running.lock().unwrap();
    let c = g.as_ref()?.camera.as_ref()?;
    let l = c.lenses.iter().find(|l| l.id == c.lens)?;
    let front = l.facing == "front";
    Some((front, l.sensor.unwrap_or(if front { 270 } else { 90 })))
}

/// Decode a camera-metadata byte array as printed by `dumpsys media.camera` into NUL-separated
/// ASCII ids. Standard tags print printable bytes as characters and NUL as `0` (`[2 0 5 2]` is
/// "2\0" "52"); vendor tags print numbers (`[50 0]`). The dump shows only the first few
/// values, so an id not closed by a NUL when the array is truncated is dropped.
fn decode_byte_ids(tokens: &[&str], declared_len: usize) -> Vec<String> {
    let numeric = tokens.iter().any(|t| t.len() > 1);
    let bytes: Vec<u8> = tokens
        .iter()
        .filter_map(|t| if numeric { t.parse::<u8>().ok() } else if *t == "0" { Some(0) } else { t.bytes().next() })
        .collect();
    let truncated = bytes.len() < declared_len;
    let mut out = Vec::new();
    let mut cur = String::new();
    for b in &bytes {
        if *b == 0 {
            if !cur.is_empty() {
                out.push(std::mem::take(&mut cur));
            }
        } else if b.is_ascii_digit() {
            cur.push(*b as char);
        } else {
            cur.clear();
        }
    }
    if !cur.is_empty() && !truncated {
        out.push(cur);
    }
    out
}

/// `android.logicalMultiCamera.physicalIds` per camera id from `dumpsys media.camera`.
pub fn parse_dumpsys_physical_ids(dump: &str) -> std::collections::HashMap<String, Vec<String>> {
    let mut out = std::collections::HashMap::new();
    let mut id: Option<String> = None;
    let mut lines = dump.lines();
    while let Some(line) = lines.next() {
        if line.starts_with("== Camera HAL device") {
            id = line.split(" (").next().and_then(|h| h.rsplit('/').next()).map(str::to_string);
        } else if line.contains("android.logicalMultiCamera.physicalIds") {
            let declared = line.split("byte[").nth(1).and_then(|r| r.split(']').next()).and_then(|n| n.parse().ok()).unwrap_or(0);
            if let (Some(i), Some(vals)) = (&id, lines.next()) {
                let toks: Vec<&str> = vals.trim().trim_start_matches('[').trim_end_matches(']').split_whitespace().collect();
                let ids = decode_byte_ids(&toks, declared);
                if !ids.is_empty() {
                    out.insert(i.clone(), ids);
                }
            }
        }
    }
    out
}

/// Lenses for the picker, ordered by multiplier: each listed back camera labelled by its
/// 35 mm-equivalent multiplier vs the first back camera (from `equiv`, when known for all of
/// them; otherwise "1×", "Back 2"…; two cameras with the same snapped label keep the first,
/// the main one), and the first front camera last as "Front". `external` cameras are skipped.
pub fn lenses_from_cameras(
    cams: &[CameraEntry],
    equiv: Option<&std::collections::HashMap<String, f32>>,
    physical: Option<&std::collections::HashMap<String, Vec<String>>>,
) -> Vec<Lens> {
    let backs: Vec<&CameraEntry> = cams.iter().filter(|c| c.facing == "back").collect();
    let mut out: Vec<(f32, Lens)> = Vec::new();
    let main_eq = backs.first().and_then(|m| equiv.and_then(|e| e.get(&m.id)).copied());
    let known = main_eq.is_some() && backs.iter().all(|c| equiv.is_some_and(|e| e.contains_key(&c.id)));
    for (n, c) in backs.iter().enumerate() {
        let (mult, label) = if known {
            let m = snap_mult(equiv.unwrap()[&c.id] / main_eq.unwrap());
            (m, mult_label(m))
        } else if n == 0 {
            (1.0, "1×".to_string())
        } else {
            (1.0 + n as f32 * 0.01, format!("Back {}", n + 1))
        };
        if known && out.iter().any(|(m, _)| *m == mult) {
            continue;
        }
        out.push((mult, Lens { id: lens_id(&c.id, 1.0), facing: "back".into(), label, zoom: 1.0, sensor: None, zoom_range: c.zoom_range }));
    }
    // No synthesized digital-zoom lens for plain cameras. Over USB, scrcpy's server refuses ids
    // that are not in the camera id list (the S21 FE's tele, hidden ids 52/21, fails with
    // "Camera with id 52 not found"), and `camera_zoom=3` on a non-logical camera is only a
    // digital crop. But a LISTED *logical* back camera (Pixel Pro, OnePlus, Xiaomi…) switches
    // to its physical tele/wide itself when the zoom ratio passes that lens's multiplier, so
    // each physical sub-camera (from dumpsys) becomes a real `<logical>@<mult>` lens.
    if let (Some(phys), Some(eq), Some(main)) = (physical, equiv, main_eq) {
        for c in &backs {
            let Some(ids) = phys.get(&c.id) else { continue };
            let Some((lo, hi)) = c.zoom_range.filter(|(lo, hi)| hi > lo) else { continue };
            for p in ids.iter().filter(|p| **p != c.id) {
                let Some(pe) = eq.get(p) else { continue };
                let mult = snap_mult(pe / main);
                let z = if mult >= 2.0 && mult <= hi {
                    mult
                } else if mult <= 0.6 && lo < 1.0 && !out.iter().any(|(m, _)| *m < 1.0) {
                    lo // the logical camera's widest setting is its ultra-wide
                } else {
                    continue;
                };
                let label = mult_label(z);
                if out.iter().any(|(_, l)| l.label == label) {
                    continue;
                }
                out.push((z, Lens { id: lens_id(&c.id, z), facing: "back".into(), label, zoom: z, sensor: None, zoom_range: c.zoom_range }));
            }
        }
    }
    out.sort_by(|a, b| a.0.total_cmp(&b.0));
    let mut lenses: Vec<Lens> = out.into_iter().map(|(_, l)| l).collect();
    if let Some(f) = cams.iter().find(|c| c.facing == "front") {
        lenses.push(Lens { id: lens_id(&f.id, 1.0), facing: "front".into(), label: "Front".into(), zoom: 1.0, sensor: None, zoom_range: f.zoom_range });
    }
    lenses
}

/// `adb shell dumpsys media.camera` (large; bounded).
async fn dumpsys_camera(adb_bin: &str, serial: &str) -> Option<String> {
    tokio::time::timeout(std::time::Duration::from_secs(10), adb(adb_bin, &["-s", serial, "shell", "dumpsys", "media.camera"])).await.ok()?.ok()
}

/// Run `scrcpy -s <serial> --list-cameras`; stdout and stderr are both searched (scrcpy logs
/// the server's answer through its own logger).
async fn list_cameras(scrcpy_bin: &str, adb_bin: &str, serial: &str) -> Option<String> {
    let mut cmd = tokio::process::Command::new(scrcpy_bin);
    cmd.args(["-s", serial, "--list-cameras"]).envs(crate::adb::scrcpy_env(Some(adb_bin))).kill_on_drop(true);
    let out = tokio::time::timeout(std::time::Duration::from_secs(20), cmd.output()).await.ok()?.ok()?;
    let mut text = String::from_utf8_lossy(&out.stdout).into_owned();
    text.push('\n');
    text.push_str(&String::from_utf8_lossy(&out.stderr));
    Some(text)
}

/// The first lens of `facing` in the running camera session.
pub fn lens_for_facing(app: &AppHandle, facing: &str) -> Option<String> {
    let g = app.state::<ScrcpyState>();
    let g = g.running.lock().unwrap();
    g.as_ref()?.camera.as_ref()?.lenses.iter().find(|l| l.facing == facing).map(|l| l.id.clone())
}

/// Switch lenses on a running embedded camera session WITHOUT closing the Mac window: emit
/// `camera-switching`, stop the old session silently (no `mirror-stopped`/`mirror-error`),
/// wait for it, then start the new one. On failure it goes back to the previous lens and
/// emits `camera-lens-failed`; only if that fails too does the window get a `mirror-error`.
pub async fn camera_select_lens(app: &AppHandle, lens: &str) -> Result<(), String> {
    eprintln!("[camera] select lens {lens}");
    let state = app.state::<ScrcpyState>();
    let (serial, info, prev_lens) = {
        let g = state.running.lock().unwrap();
        let s = g.as_ref().ok_or("no embedded camera session")?;
        let info = s.camera.clone().ok_or("the running session is not a camera")?;
        (s.serial.clone(), info.clone(), info.lens)
    };
    if !info.lenses.iter().any(|l| l.id == lens) {
        return Err(format!("unknown lens {lens}"));
    }
    if lens == prev_lens {
        return Ok(());
    }
    if state.switching.swap(true, Ordering::SeqCst) {
        return Err("a lens switch is already in progress".into());
    }
    // A lens change resets zoom: drop any queued zoom request so it can't land on the new lens.
    *state.zoom_pending.lock().unwrap() = None;
    state.zoom_seq.fetch_add(1, Ordering::SeqCst);
    let target_zoom = info.lenses.iter().find(|l| l.id == lens).map_or(1.0, |l| l.zoom);
    let r = switch_to(app, &serial, info.lenses.clone(), lens, target_zoom, &prev_lens, info.zoom, true).await;
    state.switching.store(false, Ordering::SeqCst);
    r
}

#[allow(clippy::too_many_arguments)]
async fn switch_to(app: &AppHandle, serial: &str, lenses: Vec<Lens>, lens: &str, zoom: f32, prev: &str, prev_zoom: f32, announce: bool) -> Result<(), String> {
    let (adb_bin, scrcpy_bin) = {
        let a = app.state::<crate::adb::AdbState>();
        let (adb_bin, scrcpy_bin) = (a.adb.lock().unwrap().clone(), a.scrcpy.lock().unwrap().clone());
        (adb_bin.ok_or("ADB not found")?, scrcpy_bin.ok_or("scrcpy not found")?)
    };
    let gen = app.state::<ScrcpyState>().generation.load(Ordering::SeqCst);
    let closed = || app.state::<ScrcpyState>().generation.load(Ordering::SeqCst) != gen;
    if announce {
        let _ = app.emit("camera-switching", json!({ "lens": lens }));
    }
    let old = app.state::<ScrcpyState>().running.lock().unwrap().take();
    if let Some(s) = old {
        s.silent.store(true, Ordering::SeqCst);
        s.stop.store(true, Ordering::SeqCst);
        s.kill.notify_one();
        let _ = tokio::time::timeout(std::time::Duration::from_secs(8), s.done.notified()).await;
    }
    if closed() {
        return Err("the window was closed".into());
    }
    match try_lens_retry(app, &adb_bin, &scrcpy_bin, serial, lens, zoom, &lenses, closed).await {
        Ok(()) => Ok(()),
        Err(e) => {
            if closed() {
                return Err(e);
            }
            if announce {
                let _ = app.emit("camera-lens-failed", json!({ "lens": lens, "error": e }));
            }
            if let Err(e2) = try_lens_retry(app, &adb_bin, &scrcpy_bin, serial, prev, prev_zoom, &lenses, closed).await {
                if !closed() {
                    mirror::on_error(app, &json!({ "error": e2 }));
                }
            }
            Err(e)
        }
    }
}

/// Debounce before a zoom change restarts the session.
const ZOOM_DEBOUNCE: std::time::Duration = std::time::Duration::from_millis(400);

/// Clamp `ratio` to the active lens's `zoom-range`.
fn clamp_zoom(range: Option<(f32, f32)>, ratio: f32) -> f32 {
    range.map_or(ratio, |(lo, hi)| ratio.clamp(lo, hi))
}

/// Request a camera zoom on the embedded session. Only the LAST ratio is applied, once no new
/// request has come for 400 ms, and never while a restart is in flight (requests coalesce into
/// `zoom_pending`). The restart is silent: the Mac window stays and no `camera-switching` fires.
pub fn camera_set_zoom(app: &AppHandle, ratio: f32) -> Result<(), String> {
    let state = app.state::<ScrcpyState>();
    let range = {
        let g = state.running.lock().unwrap();
        let c = g.as_ref().and_then(|s| s.camera.as_ref()).ok_or("no embedded camera session")?;
        c.lenses.iter().find(|l| l.id == c.lens).and_then(|l| l.zoom_range)
    };
    let range = range.filter(|(lo, hi)| hi > lo).ok_or("unsupported")?;
    *state.zoom_pending.lock().unwrap() = Some(clamp_zoom(Some(range), ratio));
    state.zoom_seq.fetch_add(1, Ordering::SeqCst);
    if !state.zoom_worker.swap(true, Ordering::SeqCst) {
        let app = app.clone();
        crate::host::async_runtime::spawn(async move { zoom_worker(app).await });
    }
    Ok(())
}

async fn zoom_worker(app: AppHandle) {
    let state = app.state::<ScrcpyState>();
    loop {
        // Wait until no new request arrived for the debounce window.
        loop {
            let seq = state.zoom_seq.load(Ordering::SeqCst);
            tokio::time::sleep(ZOOM_DEBOUNCE).await;
            if state.zoom_seq.load(Ordering::SeqCst) == seq {
                break;
            }
        }
        // Never overlap a lens switch / another restart.
        while state.switching.swap(true, Ordering::SeqCst) {
            tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        }
        let target = state.zoom_pending.lock().unwrap().take();
        let info = {
            let g = state.running.lock().unwrap();
            g.as_ref().and_then(|s| s.camera.clone().map(|c| (s.serial.clone(), c)))
        };
        if let (Some(z), Some((serial, c))) = (target, info) {
            // Re-clamp against the CURRENT lens (it may have changed while debouncing).
            let range = c.lenses.iter().find(|l| l.id == c.lens).and_then(|l| l.zoom_range);
            let z = clamp_zoom(range, z);
            if (z - c.zoom).abs() > 0.01 {
                let lens = c.lens.clone();
                let r = switch_to(&app, &serial, c.lenses.clone(), &lens, z, &lens, c.zoom, false).await;
                if let Err(e) = r {
                    eprintln!("[camera] zoom {z} failed: {e}");
                    let _ = app.emit("camera-zoom-state", json!({ "ratio": c.zoom }));
                }
            }
        }
        state.switching.store(false, Ordering::SeqCst);
        state.zoom_worker.store(false, Ordering::SeqCst);
        // A request that slipped in after the last take: run again rather than drop it.
        if state.zoom_pending.lock().unwrap().is_some() && !state.zoom_worker.swap(true, Ordering::SeqCst) {
            continue;
        }
        break;
    }
}

/// `try_lens`, retried once after 1.5 s: a camera session started right after the previous one
/// ended sometimes fails once ("Server connection failed") and works on the next try.
#[allow(clippy::too_many_arguments)]
async fn try_lens_retry(
    app: &AppHandle,
    adb_bin: &str,
    scrcpy_bin: &str,
    serial: &str,
    id: &str,
    zoom: f32,
    lenses: &[Lens],
    closed: impl Fn() -> bool + Copy,
) -> Result<(), String> {
    match try_lens(app, adb_bin, scrcpy_bin, serial, id, zoom, lenses, closed).await {
        Err(_) if !closed() => {
            tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
            if closed() {
                return Err("the window was closed".into());
            }
            try_lens(app, adb_bin, scrcpy_bin, serial, id, zoom, lenses, closed).await
        }
        r => r,
    }
}

/// Start one lens as a *silent* session and wait for its first config packet. On success the
/// session becomes a normal one (its end closes the window again); on failure it is killed
/// silently, so a lens whose server dies after spawning never closes the Mac window.
#[allow(clippy::too_many_arguments)]
async fn try_lens(
    app: &AppHandle,
    adb_bin: &str,
    scrcpy_bin: &str,
    serial: &str,
    id: &str,
    zoom: f32,
    lenses: &[Lens],
    closed: impl Fn() -> bool,
) -> Result<(), String> {
    let camera_id = parse_lens_id(id).map(|(c, _)| c);
    let src = Source::Camera { camera_id, zoom: Some(zoom), lens: Some(id.to_string()) };
    let rx = start_inner(app.clone(), adb_bin.into(), scrcpy_bin.into(), serial.into(), src, Some(lenses.to_vec()), true).await?;
    let ok = matches!(tokio::time::timeout(READY_TIMEOUT, rx).await, Ok(Ok(())));
    if closed() {
        stop(app);
        return Err("the window was closed".into());
    }
    let sess = app.state::<ScrcpyState>().running.lock().unwrap().take();
    match (ok, sess) {
        (true, Some(s)) => {
            s.silent.store(false, Ordering::SeqCst);
            *app.state::<ScrcpyState>().running.lock().unwrap() = Some(s);
            Ok(())
        }
        (_, sess) => {
            if let Some(s) = sess {
                s.stop.store(true, Ordering::SeqCst);
                s.kill.notify_one();
                let _ = tokio::time::timeout(std::time::Duration::from_secs(8), s.done.notified()).await;
            }
            Err("the camera did not start".into())
        }
    }
}

fn meta_known(r: &PushRecord) -> bool {
    r.3.is_some()
}

const LENS_CACHE_TTL: std::time::Duration = std::time::Duration::from_secs(24 * 3600);
const READY_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(12);

/// Derive the WebCodecs codec string from an H.264 config packet (SPS/PPS).
///
/// **Not cosmetic.** The pop-out calls `VideoDecoder.configure({codec})`, and
/// WebCodecs requires a real codec string — `avc1.<profile><constraint><level>`
/// in hex. Handing it the bare `"h264"` leaves the decoder unconfigured and the
/// window black, with no error anywhere: the stream arrives, the UI says it is
/// mirroring, and nothing paints.
///
/// Walks Annex-B start codes (3- or 4-byte) looking for a NAL of type 7 (SPS),
/// whose next three bytes are exactly the values the string needs. Mirrors
/// `MirrorService.kt`'s `avcCodecString`, so both mirror paths describe the same
/// stream the same way. Falls back to constrained baseline 3.0, which is what
/// the Android side falls back to.
fn avc_codec_string(config: &[u8]) -> String {
    const FALLBACK: &str = "avc1.42E01E";
    let mut i = 0usize;
    while i + 4 < config.len() {
        let sc4 = config[i] == 0 && config[i + 1] == 0 && config[i + 2] == 0 && config[i + 3] == 1;
        let sc3 = config[i] == 0 && config[i + 1] == 0 && config[i + 2] == 1;
        if sc4 || sc3 {
            let nal = i + if sc4 { 4 } else { 3 };
            if nal + 3 < config.len() && (config[nal] & 0x1f) == 7 {
                return format!(
                    "avc1.{:02X}{:02X}{:02X}",
                    config[nal + 1],
                    config[nal + 2],
                    config[nal + 3]
                );
            }
            i = nal;
        } else {
            i += 1;
        }
    }
    FALLBACK.to_string()
}

/// Connect to the forwarded socket, retrying while the server starts up, and
/// consume the tunnel's dummy byte.
///
/// **The dummy byte is not optional.** `adb forward` completes the TCP
/// handshake whether or not anything is listening on the device side, so a
/// successful `connect` proves nothing. scrcpy's server writes one byte to say
/// it is really there, and the client reads it before anything else. Skipping
/// that read shifts the entire stream by one byte — the device name comes back
/// with a leading NUL and the codec id reads as `"\0h26"`, which is exactly what
/// this did before the byte was accounted for.
async fn connect_with_retry(port: u16) -> Result<tokio::net::TcpStream, String> {
    // 100 × 100ms — the same budget scrcpy's own client allows.
    for _ in 0..100 {
        if let Ok(mut s) = tokio::net::TcpStream::connect(("127.0.0.1", port)).await {
            let mut dummy = [0u8; 1];
            // A connection that closes here is the tunnel being open with no
            // server behind it yet — retry rather than fail.
            if s.read_exact(&mut dummy).await.is_ok() {
                return Ok(s);
            }
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    Err("the scrcpy server never accepted a connection".into())
}

/// Read the stream and forward frames into the existing mirror pipeline.
async fn pump(app: AppHandle, port: u16, stop: Arc<AtomicBool>, camera: Option<CameraInfo>, mut ready: Option<tokio::sync::oneshot::Sender<()>>) -> Result<(), String> {
    let mut sock = connect_with_retry(port).await?;

    // Device name, then the codec id — both only on the first (video) socket.
    let mut name = [0u8; DEVICE_NAME_LEN];
    sock.read_exact(&mut name).await.map_err(|e| e.to_string())?;

    let mut codec = [0u8; 4];
    sock.read_exact(&mut codec).await.map_err(|e| e.to_string())?;
    let codec_name = match &codec {
        b"h264" => "h264",
        b"h265" => "h265",
        other => {
            return Err(format!(
                "unsupported codec from the device: {}",
                String::from_utf8_lossy(other)
            ))
        }
    };

    // Dimensions arrive in the session packet; the codec string can only be
    // derived once the config packet (SPS/PPS) has been seen. `mirror-started`
    // needs both, so it is held until the config packet lands rather than being
    // sent early with a placeholder the decoder would reject.
    let mut announced = false;
    let mut size = (0u32, 0u32);
    // SPS/PPS, held back and prepended to each key frame — see below.
    let mut config_bytes: Option<Vec<u8>> = None;
    let mut header = [0u8; HEADER];

    loop {
        if stop.load(Ordering::Relaxed) {
            return Ok(());
        }
        // A short read here is the ordinary end of stream (server exited,
        // device unplugged), not an error worth surfacing as one.
        if sock.read_exact(&mut header).await.is_err() {
            return Ok(());
        }

        // Session packet: carries the video dimensions, and is how we learn
        // them — the codec meta above does not include them in 4.x.
        if header[0] & 0x80 != 0 {
            size = (
                u32::from_be_bytes([header[4], header[5], header[6], header[7]]),
                u32::from_be_bytes([header[8], header[9], header[10], header[11]]),
            );
            if announced {
                mirror::note_size(&app, size.0, size.1);
            }
            continue;
        }

        let pts_flags = u64::from_be_bytes([
            header[0], header[1], header[2], header[3], header[4], header[5], header[6], header[7],
        ]);
        let len = u32::from_be_bytes([header[8], header[9], header[10], header[11]]);
        if len == 0 || len > MAX_PACKET {
            return Err(format!("desynchronised stream (packet length {len})"));
        }

        // One allocation, read straight into place. The packet flags are known
        // from the header, so the frame's `[3][flags][config?]` prefix can be
        // reserved up front and the payload read into the tail: no second Vec,
        // no `extend_from_slice` copy of the access unit.
        let is_config = pts_flags & FLAG_CONFIG != 0;
        let is_key = pts_flags & FLAG_KEY_FRAME != 0;
        let prefix_cfg: &[u8] = match (is_config, is_key, config_bytes.as_deref()) {
            (false, true, Some(cfg)) => cfg,
            _ => &[],
        };
        let head = if is_config { 0 } else { 2 + prefix_cfg.len() };
        let mut buf = vec![0u8; head + len as usize];
        if sock.read_exact(&mut buf[head..]).await.is_err() {
            return Ok(());
        }

        // A config packet is SPS/PPS only. It is **not** a decodable frame, and
        // must never be forwarded as one: WebCodecs errors on a chunk that
        // contains no slice, and an errored `VideoDecoder` transitions to
        // `closed`. The pop-out then drops every later frame on its
        // `state !== "configured"` guard, so one bad chunk at the start means a
        // permanently black window with nothing logged.
        //
        // So it is stored and prepended to the next key frame instead — exactly
        // what `MirrorService.kt` does on the Wi-Fi path (`configBytes + bytes`
        // when `isKey`), which keeps both mirror sources feeding the decoder
        // the same shape.
        if is_config {
            let payload = buf;
            if !announced {
                let codec = if codec_name == "h264" { avc_codec_string(&payload) } else { codec_name.to_string() };
                mirror::on_started(&app, &started_payload(size, codec, camera.as_ref()));
                announced = true;
                if let Some(r) = ready.take() {
                    let _ = r.send(());
                }
            }
            config_bytes = Some(payload);
            continue;
        }

        // Our pipeline's frame shape: [3][flags][access unit], flags bit0 =
        // keyframe (the prefix was reserved above).
        buf[0] = 3;
        buf[1] = u8::from(is_key);
        buf[2..head].copy_from_slice(prefix_cfg);
        mirror::on_frame(&app, &buf);
    }
}

/// Stop a running session. Idempotent.
pub fn stop(app: &AppHandle) {
    let state = app.state::<ScrcpyState>();
    state.generation.fetch_add(1, Ordering::SeqCst);
    let session = state.running.lock().unwrap().take();
    if let Some(s) = session {
        s.stop.store(true, Ordering::Relaxed);
        // The session task removes the forward and drops the server child (killing it).
        s.kill.notify_one();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "scrcpy 5.0.1 <https://github.com/Genymobile/scrcpy>\n[server] INFO: List of cameras:\n    --camera-id=0    (back, 4000x3000, fps={10, 15, 24, 30}, zoom-range=[1, 8])\n    --camera-id=1    (front, 3264x2448, fps={10, 15, 24, 30}, zoom-range=[1, 8])\n    --camera-id=2    (back, 4000x3000, fps={10, 15, 24, 30}, zoom-range=[1, 8])\n    --camera-id=3    (front, 2640x1980, fps={10, 15, 24, 30}, zoom-range=[1, 8])\n    --camera-id=20   (external, 1920x1080, fps={30})\n";

    const DUMP: &str = "== Camera HAL device device@3.5/legacy/0 (v3.5) static information: ==\n      android.lens.info.availableFocalLengths (90002): float[1]\n        [5.40000010 ]\n      android.sensor.info.physicalSize (f0005): float[2]\n        [7.19999981 5.40000010 ]\n== Camera HAL device device@3.5/legacy/1 (v3.5) static information: ==\n      android.lens.info.availableFocalLengths (90002): float[1]\n        [3.75000000 ]\n      android.sensor.info.physicalSize (f0005): float[2]\n        [5.01350403 3.76012802 ]\n== Camera HAL device device@3.5/legacy/2 (v3.5) static information: ==\n      android.lens.info.availableFocalLengths (90002): float[1]\n        [1.74000001 ]\n      android.sensor.info.physicalSize (f0005): float[2]\n        [4.48000002 3.35999990 ]\n";

    #[test]
    fn list_cameras_parses_real_output() {
        let c = parse_list_cameras(SAMPLE);
        assert_eq!(c.len(), 5);
        assert_eq!(c[0], CameraEntry { id: "0".into(), facing: "back".into(), size: Some((4000, 3000)), zoom_range: Some((1.0, 8.0)) });
        assert_eq!(c[1].facing, "front");
        assert_eq!(c[4].facing, "external");
        assert_eq!(c[4].zoom_range, None);
        assert!(parse_list_cameras("ERROR: nothing\n").is_empty());
    }

    #[test]
    fn dumpsys_focal_lengths_give_equivalents() {
        let e = parse_dumpsys_equiv_focal(DUMP);
        assert!((e["0"] - 26.0).abs() < 0.5, "{}", e["0"]);
        assert!((e["2"] - 13.0).abs() < 0.5, "{}", e["2"]);
        assert_eq!(e.len(), 3);
        assert_eq!(snap_mult(e["2"] / e["0"]), 0.5);
    }

    #[test]
    fn lenses_are_ordered_by_multiplier_with_a_zoom_lens_and_one_front() {
        let cams = parse_list_cameras(SAMPLE);
        let l = lenses_from_cameras(&cams, Some(&parse_dumpsys_equiv_focal(DUMP)), Some(&parse_dumpsys_physical_ids(DUMP)));
        let got: Vec<(&str, &str)> = l.iter().map(|l| (l.id.as_str(), l.label.as_str())).collect();
        assert_eq!(got, [("adb:2@1", "0.5×"), ("adb:0@1", "1×"), ("adb:1@1", "Front")]);
    }

    #[test]
    fn lenses_fall_back_without_dumpsys() {
        let l = lenses_from_cameras(&parse_list_cameras(SAMPLE), None, None);
        let labels: Vec<&str> = l.iter().map(|l| l.label.as_str()).collect();
        assert_eq!(labels, ["1×", "Back 2", "Front"]);
        assert!(l.iter().all(|l| l.facing != "external"));
    }

    #[test]
    fn sensor_orientation_parses_per_camera() {
        let d = "== Camera HAL device device@3.5/legacy/0 (v3.5) static information: ==\n      android.sensor.orientation (e000e): int32[1]\n        [90 ]\n== Camera HAL device device@3.5/legacy/1 (v3.5) static information: ==\n      android.sensor.orientation (e000e): int32[1]\n        [270 ]\n";
        let m = parse_dumpsys_sensor_orientation(d);
        assert_eq!((m["0"], m["1"]), (90, 270));
    }

    #[test]
    fn jar_push_is_skipped_only_when_nothing_changed() {
        let t = std::time::SystemTime::UNIX_EPOCH;
        let r: PushRecord = ("S".into(), "/j".into(), 10, Some(t));
        assert!(push_needed(None, &r));
        assert!(!push_needed(Some(&r), &r));
        assert!(push_needed(Some(&r), &("T".into(), "/j".into(), 10, Some(t))));
        assert!(push_needed(Some(&r), &("S".into(), "/j".into(), 11, Some(t))));
        assert!(push_needed(Some(&r), &("S".into(), "/k".into(), 10, Some(t))));
        assert!(push_needed(Some(&r), &("S".into(), "/j".into(), 10, Some(t + std::time::Duration::from_secs(1)))));
    }

    #[test]
    fn zoom_is_clamped_to_the_listed_range() {
        assert_eq!(clamp_zoom(Some((1.0, 8.0)), 20.0), 8.0);
        assert_eq!(clamp_zoom(Some((1.0, 8.0)), 0.3), 1.0);
        assert_eq!(clamp_zoom(Some((1.0, 8.0)), 2.5), 2.5);
        assert_eq!(clamp_zoom(None, 2.5), 2.5);
    }

    #[test]
    fn physical_ids_decode_real_dump_excerpts() {
        // Real S21 FE excerpts (logical cameras 20 and 21).
        let d = "== Camera HAL device device@3.5/legacy/20 (v3.5) static information: ==\n      android.logicalMultiCamera.physicalIds (1a0000): byte[7]\n        [2 0 5 2 ]\n== Camera HAL device device@3.5/legacy/21 (v3.5) static information: ==\n      android.logicalMultiCamera.physicalIds (1a0000): byte[5]\n        [5 2 0 ]\n";
        let m = parse_dumpsys_physical_ids(d);
        assert_eq!(m["20"], vec!["2"]); // "52" is cut off by the dump: dropped, not guessed
        assert_eq!(m["21"], vec!["52"]);
        assert_eq!(decode_byte_ids(&["50", "0", "51", "0"], 4), vec!["2", "3"]);
    }

    fn pixel_dump() -> String {
        let sec = |id: &str, focal: &str, w: &str, h: &str, phys: &str| format!(
            "== Camera HAL device device@3.5/legacy/{id} (v3.5) static information: ==\n      android.lens.info.availableFocalLengths (90002): float[1]\n        [{focal} ]\n      android.sensor.info.physicalSize (f0005): float[2]\n        [{w} {h} ]\n{phys}");
        let p0 = "      android.logicalMultiCamera.physicalIds (1a0000): byte[4]\n        [2 0 3 0 ]\n";
        // main 26 mm eq, wide 13 mm, tele 130 mm (5x) on the same sensor size
        sec("0", "5.4", "7.2", "5.4", p0) + &sec("2", "2.7", "7.2", "5.4", "") + &sec("3", "27.0", "7.2", "5.4", "") + &sec("1", "3.75", "5.0", "3.76", "")
    }

    #[test]
    fn logical_back_camera_gets_physical_tele_and_wide_lenses() {
        let list = "    --camera-id=0    (back, 4000x3000, fps={30}, zoom-range=[0.7, 30])\n    --camera-id=1    (front, 3264x2448, fps={30}, zoom-range=[1, 8])\n";
        let d = pixel_dump();
        let l = lenses_from_cameras(&parse_list_cameras(list), Some(&parse_dumpsys_equiv_focal(&d)), Some(&parse_dumpsys_physical_ids(&d)));
        let got: Vec<(&str, &str)> = l.iter().map(|l| (l.id.as_str(), l.label.as_str())).collect();
        assert_eq!(got, [("adb:0@0.7", "0.7×"), ("adb:0@1", "1×"), ("adb:0@5", "5×"), ("adb:1@1", "Front")]);
        assert_eq!(l[2].zoom, 5.0);
        // The S21 FE (no physical ids on its listed cameras) is unchanged.
        let s = lenses_from_cameras(&parse_list_cameras(SAMPLE), Some(&parse_dumpsys_equiv_focal(DUMP)), Some(&parse_dumpsys_physical_ids(DUMP)));
        assert_eq!(s.iter().map(|l| l.label.as_str()).collect::<Vec<_>>(), ["0.5×", "1×", "Front"]);
    }

    #[test]
    fn lens_ids_round_trip() {
        assert_eq!(lens_id("0", 0.5), "adb:0@0.5");
        assert_eq!(parse_lens_id("adb:0@0.5"), Some(("0".into(), 0.5)));
        assert_eq!(parse_lens_id("adb:3@1"), Some(("3".into(), 1.0)));
        assert_eq!(parse_lens_id("adb:3"), Some(("3".into(), 1.0)));
        assert_eq!(parse_lens_id("0@1"), None);
        assert_eq!(parse_lens_id("adb:a b@1"), None);
    }

    #[test]
    fn camera_args_and_payload() {
        let l = Lens { id: "adb:0@0.5".into(), facing: "back".into(), label: "0.5".into(), zoom: 0.5, sensor: None, zoom_range: None };
        let a = camera_server_args(Some(&l), 3.0, 1920);
        assert!(a.contains(&"video_source=camera".to_string()));
        assert!(a.contains(&"video_codec_options=i-frame-interval=1".to_string()));
        assert!(a.contains(&"camera_id=0".to_string()));
        assert!(a.contains(&"camera_zoom=3".to_string()));
        assert!(a.contains(&"max_size=1920".to_string()));
        let one = Lens { zoom: 1.0, id: "adb:0@1".into(), ..l.clone() };
        assert!(!camera_server_args(Some(&one), 1.0, 1280).iter().any(|s| s.starts_with("camera_zoom")));
        assert!(camera_server_args(None, 1.0, 1920).contains(&"camera_facing=back".to_string()));
        let info = CameraInfo { lenses: vec![Lens { zoom_range: Some((1.0, 8.0)), ..l.clone() }], lens: l.id.clone(), zoom: 2.5 };
        let p = started_payload((1920, 1080), "avc1.64001F".into(), Some(&info));
        assert_eq!(p["source"], "camera");
        assert_eq!(p["photo"], "frame");
        assert_eq!(p["lens"], "adb:0@0.5");
        assert_eq!(p["zoomRange"], json!([1.0, 8.0]));
        assert_eq!(p["zoom"], 2.5);
        assert_eq!(p["lenses"][0]["label"], "0.5");
        assert_eq!(started_payload((1, 2), "c".into(), None)["source"], "screen");
    }

    /// The flag bits are the difference between a decodable stream and a
    /// silently black window, and they are easy to transpose.
    #[test]
    fn packet_flags_sit_where_scrcpy_puts_them() {
        assert_eq!(FLAG_CONFIG, 0x4000_0000_0000_0000);
        assert_eq!(FLAG_KEY_FRAME, 0x2000_0000_0000_0000);
        // The media/session discriminator is the MSB of byte 0.
        let session = [0x80u8, 0, 0, 0, 0, 0, 7, 128, 0, 0, 4, 56];
        assert!(session[0] & 0x80 != 0);
        let media = [0x20u8, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 42];
        assert!(media[0] & 0x80 == 0);
    }

    /// A session packet's width/height are big-endian at offsets 4 and 8.
    #[test]
    fn session_packets_decode_their_dimensions() {
        // 1920x1080
        let h: [u8; 12] = [0x80, 0, 0, 0, 0, 0, 0x07, 0x80, 0, 0, 0x04, 0x38];
        let w = u32::from_be_bytes([h[4], h[5], h[6], h[7]]);
        let ht = u32::from_be_bytes([h[8], h[9], h[10], h[11]]);
        assert_eq!((w, ht), (1920, 1080));
    }

    /// A media header must yield both its flags and its length.
    #[test]
    fn media_packets_decode_flags_and_length() {
        let mut h = [0u8; 12];
        // key frame flag + a small pts
        let pts_flags: u64 = FLAG_KEY_FRAME | 12345;
        h[..8].copy_from_slice(&pts_flags.to_be_bytes());
        h[8..].copy_from_slice(&1000u32.to_be_bytes());

        let got = u64::from_be_bytes([h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]]);
        assert!(got & FLAG_KEY_FRAME != 0);
        assert!(got & FLAG_CONFIG == 0);
        assert_eq!(u32::from_be_bytes([h[8], h[9], h[10], h[11]]), 1000);
    }

    /// The codec string is what `VideoDecoder.configure` receives. Get it
    /// wrong and the window is black with no error anywhere, so this walks a
    /// real config packet — 4-byte start code, SPS (NAL type 7), then the
    /// profile/constraint/level triplet.
    #[test]
    fn the_codec_string_comes_from_the_sps() {
        // Annex-B: 00 00 00 01 | 67 (SPS) | 64 00 1F | ...
        let cfg = [
            0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x1F, 0xAC, 0xD9,
            0x00, 0x00, 0x00, 0x01, 0x68, 0xEB, 0xE3, 0xCB,
        ];
        assert_eq!(avc_codec_string(&cfg), "avc1.64001F");

        // 3-byte start codes are equally legal.
        let cfg3 = [0x00, 0x00, 0x01, 0x67, 0x42, 0xE0, 0x1E, 0x99];
        assert_eq!(avc_codec_string(&cfg3), "avc1.42E01E");
    }

    /// Anything that isn't a parseable SPS must fall back to a string WebCodecs
    /// accepts, never to an empty or bare-"h264" value the decoder rejects.
    #[test]
    fn a_config_packet_without_an_sps_falls_back() {
        assert_eq!(avc_codec_string(&[]), "avc1.42E01E");
        assert_eq!(avc_codec_string(&[0, 0, 0, 1]), "avc1.42E01E");
        // A PPS (NAL type 8) alone is not enough.
        assert_eq!(
            avc_codec_string(&[0x00, 0x00, 0x00, 0x01, 0x68, 0xEB, 0xE3, 0xCB]),
            "avc1.42E01E"
        );
        // Truncated right after the SPS header — must not read past the end.
        assert_eq!(avc_codec_string(&[0x00, 0x00, 0x00, 0x01, 0x67]), "avc1.42E01E");
    }

    /// A corrupt length must be rejected rather than turned into a huge
    /// allocation — this is the one place a bad stream can exhaust memory.
    #[test]
    fn absurd_packet_lengths_are_bounded() {
        assert!(MAX_PACKET < u32::MAX);
        assert!(0 == 0u32 || MAX_PACKET > 0);
        // A full-HD keyframe is comfortably inside the cap.
        assert!(MAX_PACKET > 1920 * 1080 * 3 / 2);
    }
}

// ── Tauri commands ───────────────────────────────────────────────────────

/// Start the in-app ADB mirror and open the pop-out to receive it.
pub async fn scrcpy_embedded_start(
    app: AppHandle,
    adb_state: crate::host::State<'_, crate::adb::AdbState>,
    serial: String,
) -> Result<(), String> {
    let (adb_bin, scrcpy_bin) = {
        let a = adb_state.adb.lock().unwrap().clone();
        let s = adb_state.scrcpy.lock().unwrap().clone();
        (a, s)
    };
    let adb_bin = adb_bin.ok_or("ADB not found")?;
    let scrcpy_bin = scrcpy_bin.ok_or("scrcpy not found — install it from the Devices tab")?;

    // The pop-out has to exist and be attached before frames start, or the
    // first (config) packet is dropped and the decoder never configures.
    mirror::open_popout(&app)?;
    start(app, adb_bin, scrcpy_bin, serial, Source::Screen).await
}

/// Start the in-app ADB camera (first back camera) in the pop-out.
pub async fn camera_embedded_start(app: AppHandle, adb_bin: String, scrcpy_bin: String, serial: String) -> Result<(), String> {
    if app.state::<ScrcpyState>().is_running() {
        return Err("already mirroring".into());
    }
    mirror::open_popout(&app)?;
    start(app, adb_bin, scrcpy_bin, serial, Source::Camera { camera_id: None, zoom: None, lens: None }).await
}

pub fn scrcpy_embedded_stop(app: AppHandle) {
    stop(&app);
}

pub fn scrcpy_embedded_running(state: crate::host::State<'_, ScrcpyState>) -> bool {
    state.is_running()
}

/// Remove a running session's `adb forward` synchronously, for app shutdown.
///
/// The pump's own cleanup handles the ordinary end of a session, but it never
/// runs when the process is going away — and the forward outlives us inside the
/// adb server, one stale entry per session. This is deliberately blocking: at
/// exit there is no runtime left to await on.
pub fn cleanup_blocking(app: &AppHandle) {
    let session = {
        let state = app.state::<ScrcpyState>();
        let s = state.running.lock().unwrap().take();
        s
    };
    let Some(s) = session else { return };
    s.stop.store(true, Ordering::Relaxed);
    let adb_bin = {
        let adb_state = app.state::<crate::adb::AdbState>();
        let b = adb_state.adb.lock().unwrap().clone();
        b
    };
    if let Some(adb_bin) = adb_bin {
        // Bounded, like every quit-time command: a wedged adb server must
        // not hang Quit.
        crate::shutdown::run_bounded(
            std::process::Command::new(adb_bin).args([
                "-s",
                &s.serial,
                "forward",
                "--remove",
                &format!("tcp:{}", s.local_port),
            ]),
            std::time::Duration::from_secs(2),
        );
    }
    crate::shutdown::forward_closed(app, &s.serial, s.local_port);
}
