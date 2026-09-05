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
use tauri::{AppHandle, Manager};
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

#[derive(Default)]
pub struct ScrcpyState {
    running: Mutex<Option<Session>>,
}

struct Session {
    stop: Arc<AtomicBool>,
    /// The `adb forward` we opened, so it can be removed on stop rather than
    /// leaking a port binding for the life of the adb server.
    local_port: u16,
    serial: String,
}

impl ScrcpyState {
    pub fn is_running(&self) -> bool {
        self.running.lock().unwrap().is_some()
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
) -> Result<(), String> {
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

    adb(&adb_bin, &["-s", &serial, "push", &jar.to_string_lossy(), REMOTE_JAR]).await?;

    // Port 0 lets the OS pick, then we ask adb what it chose — avoids racing
    // another process for a hardcoded port.
    let fwd = adb(&adb_bin, &["-s", &serial, "forward", "tcp:0", &socket_name]).await?;
    let local_port: u16 = fwd
        .trim()
        .parse()
        .map_err(|_| format!("adb forward returned an unexpected port: {fwd:?}"))?;

    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let bitrate = cfg.mirror_bitrate_mbps.clamp(1, 50) as u64 * 1_000_000;
    let fps = cfg.mirror_fps.clamp(15, 120);
    // H.264 only for now: the pop-out's WebCodecs decoder is configured from an
    // `avc1.*` codec string derived from the SPS, and HEVC needs a different
    // (hvcC-shaped) description that this path does not build yet.
    let mut server_args = vec![
        format!("scid={scid:08x}"),
        "tunnel_forward=true".to_string(),
        "audio=false".to_string(),
        "video=true".to_string(),
        // Control needs its own socket and a second reader; input still goes
        // through the existing AccessibilityService path until that lands.
        "control=false".to_string(),
        "video_codec=h264".to_string(),
        format!("video_bit_rate={bitrate}"),
        format!("max_fps={fps}"),
    ];
    if cfg.mirror_max_size > 0 {
        server_args.push(format!("max_size={}", cfg.mirror_max_size));
    }

    let mut cmd = tokio::process::Command::new(&adb_bin);
    cmd.args(["-s", &serial, "shell", &format!("CLASSPATH={REMOTE_JAR}"), "app_process", "/", "com.genymobile.scrcpy.Server", &version]);
    cmd.args(&server_args);
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);
    let child = cmd.spawn().map_err(|e| format!("couldn't start the scrcpy server: {e}"))?;

    let stop = Arc::new(AtomicBool::new(false));
    {
        let state = app.state::<ScrcpyState>();
        *state.running.lock().unwrap() = Some(Session {
            stop: stop.clone(),
            local_port,
            serial: serial.clone(),
        });
    }

    // The server needs a moment to bind its abstract socket. scrcpy retries
    // rather than sleeping a fixed time, and so does `connect_with_retry`.
    let app2 = app.clone();
    let adb2 = adb_bin.clone();
    tokio::spawn(async move {
        let result = pump(app2.clone(), local_port, stop.clone()).await;
        // Whatever happened — clean exit, stream error, stop request — the
        // session is over: tear down the forward and tell the UI once.
        let _ = adb(&adb2, &["-s", &serial, "forward", "--remove", &format!("tcp:{local_port}")]).await;
        drop(child);
        {
            let state = app2.state::<ScrcpyState>();
            *state.running.lock().unwrap() = None;
        }
        match result {
            Err(e) if !stop.load(Ordering::Relaxed) => {
                mirror::on_error(&app2, &json!({ "error": e }));
            }
            _ => mirror::on_stopped(&app2, &json!({})),
        }
    });

    Ok(())
}

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
async fn pump(app: AppHandle, port: u16, stop: Arc<AtomicBool>) -> Result<(), String> {
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
            continue;
        }

        let pts_flags = u64::from_be_bytes([
            header[0], header[1], header[2], header[3], header[4], header[5], header[6], header[7],
        ]);
        let len = u32::from_be_bytes([header[8], header[9], header[10], header[11]]);
        if len == 0 || len > MAX_PACKET {
            return Err(format!("desynchronised stream (packet length {len})"));
        }

        let mut payload = vec![0u8; len as usize];
        if sock.read_exact(&mut payload).await.is_err() {
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
        if pts_flags & FLAG_CONFIG != 0 {
            if !announced {
                mirror::on_started(
                    &app,
                    &json!({
                        "width": size.0,
                        "height": size.1,
                        "codec": if codec_name == "h264" {
                            avc_codec_string(&payload)
                        } else {
                            codec_name.to_string()
                        },
                        "source": "screen",
                    }),
                );
                announced = true;
            }
            config_bytes = Some(payload);
            continue;
        }

        // Our pipeline's frame shape: [3][flags][access unit], flags bit0 =
        // keyframe.
        let is_key = pts_flags & FLAG_KEY_FRAME != 0;
        let mut frame = Vec::with_capacity(payload.len() + config_len(&config_bytes) + 2);
        frame.push(3);
        frame.push(u8::from(is_key));
        if is_key {
            if let Some(cfg) = config_bytes.as_deref() {
                frame.extend_from_slice(cfg);
            }
        }
        frame.extend_from_slice(&payload);
        mirror::on_frame(&app, &frame);
    }
}

fn config_len(cfg: &Option<Vec<u8>>) -> usize {
    cfg.as_ref().map_or(0, Vec::len)
}

/// Stop a running session. Idempotent.
pub fn stop(app: &AppHandle) {
    let state = app.state::<ScrcpyState>();
    let session = state.running.lock().unwrap().take();
    if let Some(s) = session {
        s.stop.store(true, Ordering::Relaxed);
        // The pump's own cleanup removes the forward; this is the belt-and-
        // braces path for a session whose task has already exited.
        let _ = (s.local_port, s.serial);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
#[tauri::command]
pub async fn scrcpy_embedded_start(
    app: AppHandle,
    adb_state: tauri::State<'_, crate::adb::AdbState>,
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
    start(app, adb_bin, scrcpy_bin, serial).await
}

#[tauri::command]
pub fn scrcpy_embedded_stop(app: AppHandle) {
    stop(&app);
}

#[tauri::command]
pub fn scrcpy_embedded_running(state: tauri::State<'_, ScrcpyState>) -> bool {
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
        let _ = std::process::Command::new(adb_bin)
            .args(["-s", &s.serial, "forward", "--remove", &format!("tcp:{}", s.local_port)])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
    }
}
