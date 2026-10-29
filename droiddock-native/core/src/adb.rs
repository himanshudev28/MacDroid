//! ADB/scrcpy fallback (power-user path), Phase 13 — ported from `adb.js`.
//!
//! Everything here is optional: the Wi-Fi app-link (Phases 2–12) works with
//! zero ADB tooling installed. This module brings up `adb`/`scrcpy` in the
//! background (resolve on PATH → cached download → auto-download), tracks
//! USB/wireless devices, and exposes the handful of ADB-only extras the
//! reference app gates behind a live device: wireless pairing (code + QR),
//! screenshot, media volume, live call control (end/mute/speaker/DTMF), and
//! scrcpy-based mirror/camera.
//!
//! Deliberately NOT ported: `adb.js`'s `fsTransport()` dual-transport
//! fallback that lets Files/Photos silently prefer ADB pull/push over the
//! Wi-Fi link when a device is plugged in. That would mean reworking
//! `FilesView`/`PhotosView`'s single Wi-Fi transport into a transport-aware
//! one — a larger change than "matches current SetupModal/DevicesView flow"
//! calls for. The underlying primitives (`pull`/`push_paths`/`list_dir`/…)
//! are still ported below for fidelity to `adb.js`, just not wired to a UI
//! path yet. Flagged in the checkpoint as a known, explicit gap.

use serde::Serialize;
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use crate::host::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;

// ── PATH augmentation (mirrors `sdkPlatformToolDirs`/`toolDirs`/`augmentedPath`) ──

fn home_dir() -> Option<PathBuf> {
    std::env::var_os("HOME").map(PathBuf::from)
}

fn sdk_platform_tool_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    for root in [std::env::var_os("ANDROID_HOME"), std::env::var_os("ANDROID_SDK_ROOT")] {
        if let Some(root) = root {
            dirs.push(PathBuf::from(root).join("platform-tools"));
        }
    }
    if let Some(home) = home_dir() {
        dirs.push(home.join("Library/Android/sdk/platform-tools"));
        dirs.push(home.join("Android/Sdk/platform-tools"));
    }
    dirs
}

fn tool_dirs() -> Vec<PathBuf> {
    let mut dirs = vec![
        PathBuf::from("/opt/homebrew/bin"),
        PathBuf::from("/usr/local/bin"),
        PathBuf::from("/opt/local/bin"),
        PathBuf::from("/usr/bin"),
        PathBuf::from("/bin"),
    ];
    dirs.extend(sdk_platform_tool_dirs());
    dirs
}

fn augmented_path() -> String {
    let current: Vec<String> = std::env::var("PATH")
        .unwrap_or_default()
        .split(':')
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .collect();
    let mut seen: Vec<String> = current;
    for dir in tool_dirs() {
        let s = dir.to_string_lossy().to_string();
        if !seen.contains(&s) {
            seen.push(s);
        }
    }
    seen.join(":")
}

fn candidates(name: &str) -> Vec<PathBuf> {
    match name {
        "adb" => {
            let mut c: Vec<PathBuf> = sdk_platform_tool_dirs().into_iter().map(|d| d.join("adb")).collect();
            c.push(PathBuf::from("/opt/homebrew/bin/adb"));
            c.push(PathBuf::from("/usr/local/bin/adb"));
            c.push(PathBuf::from("/opt/local/bin/adb"));
            c
        }
        "scrcpy" => vec![
            PathBuf::from("/opt/homebrew/bin/scrcpy"),
            PathBuf::from("/usr/local/bin/scrcpy"),
            PathBuf::from("/opt/local/bin/scrcpy"),
        ],
        "brew" => vec![PathBuf::from("/opt/homebrew/bin/brew"), PathBuf::from("/usr/local/bin/brew")],
        _ => vec![],
    }
}

async fn which(cmd: &str) -> Option<String> {
    let out = Command::new("/usr/bin/which")
        .arg(cmd)
        .env("PATH", augmented_path())
        .output()
        .await
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let p = String::from_utf8_lossy(&out.stdout).lines().next().unwrap_or("").trim().to_string();
    if p.is_empty() {
        None
    } else {
        Some(p)
    }
}

/// Mirrors `resolveTool`: PATH (augmented) first, then the hardcoded
/// candidate list, else `None` (not found — Wi-Fi features still work).
pub async fn resolve_tool(name: &str) -> Option<String> {
    // `adb` is resolved candidates-first, and the candidate list starts with the
    // Android SDK's platform-tools. That ordering is load-bearing, not cosmetic.
    //
    // adb refuses to share its port-5037 server between client versions: a
    // client that finds a server of a different version **kills it** and starts
    // its own. So on a machine with both an SDK adb and a Homebrew adb, every
    // Android Studio or Gradle invocation restarts the server underneath us, and
    // whatever we had in flight dies with
    //
    //     protocol fault (couldn't read status message): Undefined error: 0
    //
    // which is what wireless QR pairing was failing with. Preferring the SDK's
    // copy puts us on the same binary as the rest of the Android toolchain, so
    // there is nothing to fight over. PATH remains the fallback for anyone
    // without an SDK install.
    //
    // Only `adb` cares: `scrcpy` and `brew` have no shared daemon, so they keep
    // resolving from PATH first, where a user's own build should win.
    if name == "adb" {
        for p in candidates(name) {
            if p.is_file() {
                return Some(p.to_string_lossy().to_string());
            }
        }
    }
    if let Some(found) = which(name).await {
        return Some(found);
    }
    for p in candidates(name) {
        if p.exists() {
            return Some(p.to_string_lossy().to_string());
        }
    }
    None
}

// ── Auto-provision adb (download-on-first-use) ──────────────────────────

/// Path where a previously auto-downloaded adb would live, or `None`.
pub fn bundled_adb(base_dir: &Path) -> Option<String> {
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    let p = base_dir.join("platform-tools").join(exe);
    p.exists().then(|| p.to_string_lossy().to_string())
}

fn platform_tools_url() -> Result<&'static str, String> {
    Ok(match std::env::consts::OS {
        "macos" => "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
        "linux" => "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
        "windows" => "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
        other => return Err(format!("no platform-tools build for {other}")),
    })
}

/// Fetch `url` to `dest` with the system `curl` (always present on macOS).
/// Shelling out replaces an in-process TLS stack (reqwest + rustls) that was
/// linked for this one rare download.
///
/// HTTPS only (including after redirects), TLS 1.2+, fail on HTTP errors, and
/// bounded in both connect and total time. The partial file is removed on
/// failure.
async fn download_file(url: &str, dest: &Path) -> Result<(), String> {
    let curl = if cfg!(target_os = "macos") { "/usr/bin/curl" } else { "curl" };
    let dest_s = dest.to_string_lossy().to_string();
    let res = run(
        curl,
        &[
            // `-q` first: ignore ~/.curlrc (insecure / proxy / alternate CA).
            "-q", "--fail", "--silent", "--show-error", "--location",
            "--proto", "=https", "--proto-redir", "=https", "--tlsv1.2",
            "--connect-timeout", "15", "--max-time", "600",
            "--output", &dest_s, url,
        ],
        Duration::from_secs(620),
    )
    .await;
    match res {
        Ok(_) => Ok(()),
        Err(e) => {
            let _ = tokio::fs::remove_file(dest).await;
            Err(format!("download failed ({e})"))
        }
    }
}

/// Download + extract platform-tools into `base_dir`; resolves to the adb path.
/// Mirrors `downloadAdb` — unzip is shelled out to (`/usr/bin/unzip` / `tar`)
/// rather than pulling in an archive crate, same as the reference.
pub async fn download_adb(base_dir: &Path) -> Result<String, String> {
    let url = platform_tools_url()?;
    tokio::fs::create_dir_all(base_dir).await.map_err(|e| e.to_string())?;
    let zip = base_dir.join("platform-tools.zip");
    download_file(url, &zip).await?;
    let zip_s = zip.to_string_lossy().to_string();
    let dir_s = base_dir.to_string_lossy().to_string();
    let extracted = if cfg!(windows) {
        run("tar", &["-xf", &zip_s, "-C", &dir_s], Duration::from_secs(60)).await
    } else {
        run("/usr/bin/unzip", &["-o", "-q", &zip_s, "-d", &dir_s], Duration::from_secs(60)).await
    };
    // Remove the zip whether or not extraction worked.
    let _ = tokio::fs::remove_file(&zip).await;
    extracted?;
    let adb_path = bundled_adb(base_dir).ok_or("platform-tools extracted but adb is missing")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = tokio::fs::set_permissions(&adb_path, std::fs::Permissions::from_mode(0o755)).await;
    }
    Ok(adb_path)
}

/// Install a Homebrew formula (e.g. scrcpy). Mirrors `brewInstall`.
pub async fn brew_install(brew: &str, formula: &str) -> Result<(), String> {
    let output = Command::new(brew)
        .args(["install", formula])
        .env("PATH", augmented_path())
        .env("HOMEBREW_NO_AUTO_UPDATE", "1")
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if output.status.success() {
        Ok(())
    } else {
        let err = String::from_utf8_lossy(&output.stderr);
        let tail: Vec<&str> = err.trim().lines().collect();
        let tail = tail.iter().rev().take(2).rev().cloned().collect::<Vec<_>>().join(" ");
        Err(if tail.is_empty() { format!("brew exited {:?}", output.status.code()) } else { tail })
    }
}

// ── Generic process runner (mirrors `run`) ──────────────────────────────

async fn run(bin: &str, args: &[&str], timeout: Duration) -> Result<String, String> {
    // kill_on_drop: on timeout the future below is dropped, and without this
    // the adb child keeps running unsupervised. The 1s call-state poller made
    // that a steady leak — dozens of orphaned processes over a long call.
    let fut = Command::new(bin)
        .args(args)
        .env("PATH", augmented_path())
        .kill_on_drop(true)
        .output();
    let output = tokio::time::timeout(timeout, fut)
        .await
        .map_err(|_| "command timed out".to_string())?
        .map_err(|e| e.to_string())?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        Err(if stderr.is_empty() { "command failed".to_string() } else { stderr })
    }
}

const DEFAULT_TIMEOUT: Duration = Duration::from_secs(20);

/// Detach a spawned GUI child (scrcpy) and report it if it dies on the spot.
///
/// We deliberately don't block on it — it owns its own window and outlives the
/// call — but the handle must not simply be dropped either, or it lingers as a
/// zombie until the app exits.
///
/// The reporting matters because `spawn()` succeeding proves almost nothing: it
/// only means the binary was found and executable. A scrcpy whose ffmpeg can't
/// resolve a dylib spawns fine and dies milliseconds later at dynamic-link
/// time. With stderr going to `/dev/null` that produced *no* signal anywhere —
/// the button did nothing, forever, and the actual message ("Library not
/// loaded: …libbluray.2.dylib") was thrown away. So stderr is captured, and an
/// exit inside `IMMEDIATE` is surfaced as a toast. A longer-lived process is a
/// real session the user closed, and stays silent.
fn reap(app: AppHandle, what: &'static str, child: std::process::Child) {
    const IMMEDIATE: Duration = Duration::from_secs(3);
    let started = Instant::now();
    let pid = child.id();
    OPEN_WINDOWS.lock().unwrap().push(pid);
    // Launched while quitting, after `close_windows` took its list: close it
    // now, or it would be the one window left running after Quit.
    if crate::shutdown::quitting() {
        send_signal(pid, Signal::Term);
    }
    std::thread::spawn(move || {
        let mut child = child;
        // Drained to EOF (not a bounded read) so a chatty session can never
        // fill the pipe and block scrcpy; only the tail is kept.
        let mut tail = String::new();
        if let Some(mut err) = child.stderr.take() {
            use std::io::Read;
            let mut buf = Vec::new();
            let _ = err.read_to_end(&mut buf);
            if buf.len() > 4096 {
                buf = buf[buf.len() - 4096..].to_vec();
            }
            tail = String::from_utf8_lossy(&buf).to_string();
        }
        let ok = child.wait().map(|s| s.success()).unwrap_or(false);
        OPEN_WINDOWS.lock().unwrap().retain(|&p| p != pid);
        if ok || started.elapsed() >= IMMEDIATE {
            return;
        }
        let reason = tail
            .lines()
            .map(str::trim)
            .rfind(|l| !l.is_empty())
            .unwrap_or("scrcpy exited immediately");
        emit_toast(&app, "bad", &format!("{what} failed — {reason}"));
    });
}

/// POSIX single-quote escaping for paths sent through `adb shell` (mirrors `shq`).
#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
fn shq(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

pub async fn start_server(adb: &str) {
    let _ = run(adb, &["start-server"], DEFAULT_TIMEOUT).await;
}

// ── Quit cleanup ─────────────────────────────────────────────────────────

/// PID of the running `adb track-devices` child (0 when none). Quit must end it
/// itself: it blocks reading the adb server, so the `stop` flag is only seen on
/// the next device event, and if the server is left running (or a Force Quit
/// skips cleanup) it would otherwise outlive the app as an orphan.
static TRACKER_PID: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// PIDs of the scrcpy windows (mirror, camera, desktop, app window) that are
/// still open. Each one is a separate program, so it outlives DroidDock unless
/// it is closed on the way out. `reap` adds a PID on spawn and removes it once
/// the process has been waited on.
static OPEN_WINDOWS: Mutex<Vec<u32>> = Mutex::new(Vec::new());

/// Whether an adb server was already listening when DroidDock launched.
static ADB_SERVER_PREEXISTED: AtomicBool = AtomicBool::new(false);

/// The adb server's port: `ANDROID_ADB_SERVER_PORT` if set, else 5037.
fn adb_server_port() -> u16 {
    std::env::var("ANDROID_ADB_SERVER_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(5037)
}

/// A TCP connect to the server port — the only check that can't itself start a server.
fn adb_server_listening() -> bool {
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], adb_server_port()));
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(200)).is_ok()
}

/// Stop everything this module started: the background pollers, every open
/// scrcpy window, then the adb server itself. `ensure_adb` starts the server
/// again on the next launch.
///
/// The adb server is stopped only if DroidDock started it: if one was already
/// running at launch (Android Studio, a terminal, wireless debugging), it belongs
/// to someone else and is left alone.
pub fn shutdown_blocking(app: &AppHandle) {
    let state = app.state::<AdbState>();
    let stops = [
        state.tracker_stop.lock().unwrap().take(),
        state.call_poll_stop.lock().unwrap().take(),
    ];
    for stop in stops.into_iter().flatten() {
        stop.store(true, Ordering::Relaxed);
    }

    close_windows();

    let tracker = TRACKER_PID.swap(0, Ordering::SeqCst);
    if tracker != 0 {
        send_signal(tracker, Signal::Term);
    }
    crate::shutdown::tracker_pid_forget(app);

    // Last, because closing scrcpy uses the adb server to clean up on the
    // phone. Stopping the server also ends any adb processes still attached
    // to it: the device tracker, and the in-app mirror's phone-side server.
    let adb = state.adb.lock().unwrap().clone();
    // Dev-only escape hatch so tests of the quit path (run with a throwaway
    // `DD_DATA_DIR`) don't take down an adb server other tools are using.
    let keep = (std::env::var_os("DD_DATA_DIR").is_some() && std::env::var_os("DD_KEEP_ADB_SERVER").is_some())
        || ADB_SERVER_PREEXISTED.load(Ordering::SeqCst);
    if ADB_SERVER_PREEXISTED.load(Ordering::SeqCst) {
        eprintln!("[shutdown] adb server was already running before DroidDock — leaving it");
    }
    if let (Some(adb), false) = (adb, keep) {
        crate::shutdown::run_bounded(
            std::process::Command::new(adb)
                .arg("kill-server")
                .env("PATH", augmented_path()),
            Duration::from_secs(3),
        );
    }
}

/// Ask every open scrcpy window to close, then force any that ignore it.
///
/// SIGTERM first: scrcpy treats it as an ordinary window close, so it removes
/// its own adb tunnels and stops its phone-side server (and a desktop-mode
/// virtual display) itself. SIGKILL is only for one still running after 3s.
/// The PID list can't point at a reused PID: an entry is only removed after
/// the process is waited on, and until then the kernel keeps the PID reserved.
fn close_windows() {
    let pids = OPEN_WINDOWS.lock().unwrap().clone();
    if pids.is_empty() {
        return;
    }
    for &pid in &pids {
        send_signal(pid, Signal::Term);
    }
    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline && !OPEN_WINDOWS.lock().unwrap().is_empty() {
        std::thread::sleep(Duration::from_millis(50));
    }
    for &pid in OPEN_WINDOWS.lock().unwrap().iter() {
        send_signal(pid, Signal::Kill);
    }
}

enum Signal {
    Term,
    Kill,
}

#[cfg(target_os = "macos")]
fn send_signal(pid: u32, signal: Signal) {
    let sig = match signal {
        Signal::Term => libc::SIGTERM,
        Signal::Kill => libc::SIGKILL,
    };
    // SAFETY: `kill` only reads its two integer arguments.
    unsafe {
        libc::kill(pid as libc::pid_t, sig);
    }
}

#[cfg(not(target_os = "macos"))]
fn send_signal(_pid: u32, _signal: Signal) {}

// ── Device parsing + tracking ────────────────────────────────────────────

struct RawDevice {
    serial: String,
    state: String,
    model: String,
    wireless: bool,
}

fn parse_device_line(line: &str) -> RawDevice {
    let cols: Vec<&str> = line.split_whitespace().collect();
    let serial = cols.first().copied().unwrap_or("").to_string();
    let state = cols.get(1).copied().unwrap_or("unknown").to_string();
    let model = line
        .split_whitespace()
        .find_map(|tok| tok.strip_prefix("model:"))
        .map(|m| m.replace('_', " "))
        .unwrap_or_else(|| serial.clone());
    let wireless = serial.rsplit_once(':').map(|(_, p)| p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty()).unwrap_or(false)
        || serial.contains("_adb-tls")
        || serial.contains("._tcp");
    RawDevice { serial, state, model, wireless }
}

fn parse_device_block(text: &str) -> Vec<RawDevice> {
    text.lines().map(str::trim).filter(|l| !l.is_empty()).map(parse_device_line).collect()
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub id: String,
    pub serial: String,
    pub model: String,
    pub state: String,
    pub transport: String,
    pub usb_serial: Option<String>,
    pub wifi_serial: Option<String>,
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn list_devices(adb: &str) -> Result<Vec<Device>, String> {
    let out = run(adb, &["devices", "-l"], DEFAULT_TIMEOUT).await?;
    let rest: String = out.lines().skip(1).collect::<Vec<_>>().join("\n");
    let raw = parse_device_block(&rest);
    Ok(group_devices(raw, &mut HashMap::new()))
}

/// Stable hardware id for grouping USB+wireless views of the same phone;
/// falls back to the adb serial if `ro.serialno` can't be read (offline).
async fn group_id(adb: &str, d: &RawDevice, cache: &mut HashMap<String, String>) -> String {
    if d.state != "device" {
        return d.serial.clone();
    }
    if let Some(id) = cache.get(&d.serial) {
        return id.clone();
    }
    let id = run(adb, &["-s", &d.serial, "shell", "getprop", "ro.serialno"], Duration::from_secs(5))
        .await
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|_| d.serial.clone());
    cache.insert(d.serial.clone(), id.clone());
    id
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
fn group_devices(raw: Vec<RawDevice>, cache: &mut HashMap<String, String>) -> Vec<Device> {
    // Synchronous grouping variant used only by the one-shot `list_devices` (no
    // network round-trip needed there since ids are cached ahead of time by the
    // tracker in the common path). Falls back to serial-as-id, same as the
    // uncached branch of `groupId`.
    let mut groups: Vec<(String, Vec<RawDevice>)> = Vec::new();
    for d in raw {
        let id = cache.get(&d.serial).cloned().unwrap_or_else(|| d.serial.clone());
        if let Some(entry) = groups.iter_mut().find(|(gid, _)| gid == &id) {
            entry.1.push(d);
        } else {
            groups.push((id, vec![d]));
        }
    }
    groups
        .into_iter()
        .map(|(id, g)| {
            let usb = g.iter().find(|d| !d.wireless);
            let wl = g
                .iter()
                .find(|d| d.wireless && d.serial.rsplit_once(':').map(|(_, p)| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())).unwrap_or(false))
                .or_else(|| g.iter().find(|d| d.wireless));
            let active = usb.or(wl).unwrap();
            Device {
                id,
                serial: active.serial.clone(),
                model: active.model.clone(),
                state: active.state.clone(),
                transport: if active.wireless { "wifi".into() } else { "usb".into() },
                usb_serial: usb.map(|d| d.serial.clone()),
                wifi_serial: wl.map(|d| d.serial.clone()),
            }
        })
        .collect()
}

/// Managed state: resolved tool paths, live device list, and background-task
/// handles (tracker / reconnect scheduler / call-state poller / QR-pair token).
pub struct AdbState {
    pub adb: Mutex<Option<String>>,
    pub scrcpy: Mutex<Option<String>>,
    /// Parsed `(major, minor)` of the resolved scrcpy, probed once at `init`.
    ///
    /// Load-bearing, not cosmetic: the virtual-display flags this module emits
    /// arrived across four different scrcpy releases, and passing one to an
    /// older binary is a hard failure at spawn, not a degraded mirror. Probing
    /// once here is what lets `desktop`/`mirror_app` drop the flags the local
    /// scrcpy can't parse instead of handing the user a dead window.
    /// `None` means "couldn't tell" — treated as the oldest supported build.
    pub scrcpy_ver: Mutex<Option<(u32, u32)>>,
    pub brew: Mutex<Option<String>>,
    pub devices: Mutex<Vec<Device>>,
    serial_id_cache: Mutex<HashMap<String, String>>,
    tracker_stop: Mutex<Option<Arc<AtomicBool>>>,
    call_poll_stop: Mutex<Option<Arc<AtomicBool>>>,
    qr_token: AtomicU64,
    /// Mirrors `appLinkPaused` — set/cleared by Phase 14's pause/resume; gates
    /// the background reconnect scheduler below, same as `scheduleMdns`'s check.
    pub paused: AtomicBool,
    idle_since: Mutex<Instant>,
    prev_has_adb: Mutex<Option<bool>>,
}

impl Default for AdbState {
    fn default() -> Self {
        Self {
            adb: Mutex::new(None),
            scrcpy: Mutex::new(None),
            scrcpy_ver: Mutex::new(None),
            brew: Mutex::new(None),
            devices: Mutex::new(Vec::new()),
            serial_id_cache: Mutex::new(HashMap::new()),
            tracker_stop: Mutex::new(None),
            call_poll_stop: Mutex::new(None),
            qr_token: AtomicU64::new(0),
            paused: AtomicBool::new(false),
            idle_since: Mutex::new(Instant::now()),
            prev_has_adb: Mutex::new(None),
        }
    }
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ToolsStatus {
    pub adb: bool,
    pub scrcpy: bool,
    pub brew: bool,
    pub adb_path: Option<String>,
    pub scrcpy_path: Option<String>,
    /// `"4.1"` etc., or `None` when scrcpy is missing or didn't answer.
    pub scrcpy_version: Option<String>,
    /// Which optional virtual-display features the resolved scrcpy supports.
    /// The UI reads these to disable actions with a reason rather than letting
    /// the spawn fail — see `ScrcpyCaps`.
    pub caps: ScrcpyCaps,
}

/// Feature gates derived from the probed scrcpy version.
///
/// Each flag below arrived in a specific release, and an older binary rejects
/// the whole command line rather than ignoring the unknown option:
///
/// | flag                          | since |
/// |-------------------------------|-------|
/// | `--new-display`               | 3.0   |
/// | `--start-app`                 | 3.0   |
/// | `--no-vd-system-decorations`  | 3.0   |
/// | `--no-vd-destroy-content`     | 3.1   |
/// | `--display-ime-policy`        | 3.2   |
/// | `--flex-display` / `-x`       | 4.0   |
/// | `--keyboard=uhid`             | 2.4   |
#[derive(Serialize, Clone, Copy, Default, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ScrcpyCaps {
    /// Virtual displays at all — desktop mode and per-app windows need this.
    pub virtual_display: bool,
    /// `--no-vd-destroy-content`: leave the app running on the phone at close.
    pub keep_content: bool,
    /// `--flex-display`: resize the Android display with the Mac window.
    pub flex_display: bool,
    /// `--keyboard=uhid`: low-level key injection.
    pub uhid: bool,
    /// `--display-ime-policy=local`: put the Android keyboard in the mirrored
    /// window instead of on the phone's own screen.
    pub ime_policy: bool,
}

impl ScrcpyCaps {
    fn from_version(v: Option<(u32, u32)>) -> Self {
        let Some((maj, min)) = v else {
            return Self::default();
        };
        let at_least = |a: u32, b: u32| maj > a || (maj == a && min >= b);
        Self {
            virtual_display: at_least(3, 0),
            keep_content: at_least(3, 1),
            ime_policy: at_least(3, 2),
            flex_display: at_least(4, 0),
            uhid: at_least(2, 4),
        }
    }
}

/// Ask a resolved scrcpy for its version. Output looks like
/// `scrcpy 4.1 <https://github.com/Genymobile/scrcpy>` on the first line.
///
/// Short timeout and a swallowed error on purpose: a scrcpy that can't answer
/// this is one we treat as ancient, which is the conservative direction — the
/// optional flags stay off and mirroring still works exactly as it did.
async fn probe_scrcpy_version(path: &str) -> Option<(u32, u32)> {
    let out = run(path, &["--version"], Duration::from_secs(5)).await.ok()?;
    let first = out.lines().next()?;
    let num = first.split_whitespace().nth(1)?;
    // Tolerate "4.1", "4.1.0" and "3.0-rc1" alike — only the first two
    // components decide any gate above.
    let mut parts = num.split(['.', '-']);
    let maj = parts.next()?.parse().ok()?;
    let min = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    Some((maj, min))
}

/// The caps of the currently resolved scrcpy.
pub fn scrcpy_caps(state: &AdbState) -> ScrcpyCaps {
    ScrcpyCaps::from_version(*state.scrcpy_ver.lock().unwrap())
}

fn tools_status(state: &AdbState) -> ToolsStatus {
    // Each mutex is locked exactly once, into a local, BEFORE the struct is
    // built. Locking `state.adb` twice inside the struct literal deadlocks:
    // temporaries in a struct expression live until the whole expression is
    // finished, so the guard taken for `adb:` is still held when `adb_path:`
    // asks for it — and `std::sync::Mutex` is not reentrant. Since `adb_tools`
    // is a sync command, the first caller was usually the *main thread*, which
    // then hung holding `AdbState.adb` for the life of the process: frozen UI,
    // and every async worker that later touched the tray blocked behind it
    // until the networking runtime had no thread left to poll its IO driver.
    let adb_path = state.adb.lock().unwrap().clone();
    let scrcpy_path = state.scrcpy.lock().unwrap().clone();
    let brew = state.brew.lock().unwrap().is_some();
    // Same one-lock-per-field discipline as above: `scrcpy_caps` takes the
    // `scrcpy_ver` lock itself, so it is called into a local first.
    let ver = *state.scrcpy_ver.lock().unwrap();
    let caps = ScrcpyCaps::from_version(ver);
    ToolsStatus {
        adb: adb_path.is_some(),
        scrcpy: scrcpy_path.is_some(),
        brew,
        adb_path,
        scrcpy_path,
        scrcpy_version: ver.map(|(a, b)| format!("{a}.{b}")),
        caps,
    }
}

fn emit_toast(app: &AppHandle, kind: &str, text: &str) {
    let _ = app.emit("wifi-event", serde_json::json!({ "kind": kind, "text": text }));
}

/// Spawn `adb track-devices -l` and parse its 4-hex-length-prefixed stream,
/// auto-respawning on exit (2s backoff) — mirrors `trackDevices`.
fn spawn_tracker(app: AppHandle, adb: String, stop: Arc<AtomicBool>) {
    crate::host::async_runtime::spawn(async move {
        // 2 s → 60 s: a missing/broken adb must not be re-spawned twice a
        // second forever. A tracker that ran for a while counts as healthy.
        let mut backoff = crate::pacing::Backoff::new(Duration::from_secs(2), Duration::from_secs(60));
        loop {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            let started = Instant::now();
            let child = Command::new(&adb)
                .args(["track-devices", "-l"])
                .env("PATH", augmented_path())
                .stdout(std::process::Stdio::piped())
                .spawn();
            match child {
                Ok(mut child) => {
                    if let Some(pid) = child.id() {
                        TRACKER_PID.store(pid, Ordering::SeqCst);
                        crate::shutdown::tracker_pid_record(&app, pid);
                    }
                    if let Some(mut stdout) = child.stdout.take() {
                        let mut buf: Vec<u8> = Vec::new();
                        let mut chunk = [0u8; 4096];
                        loop {
                            if stop.load(Ordering::Relaxed) {
                                let _ = child.kill().await;
                                return;
                            }
                            let n = match stdout.read(&mut chunk).await {
                                Ok(0) | Err(_) => break,
                                Ok(n) => n,
                            };
                            buf.extend_from_slice(&chunk[..n]);
                            loop {
                                if buf.len() < 4 {
                                    break;
                                }
                                let len = match std::str::from_utf8(&buf[0..4]).ok().and_then(|s| u32::from_str_radix(s, 16).ok()) {
                                    Some(l) => l as usize,
                                    None => {
                                        buf.clear();
                                        break;
                                    }
                                };
                                if buf.len() < 4 + len {
                                    break;
                                }
                                let payload = String::from_utf8_lossy(&buf[4..4 + len]).to_string();
                                buf.drain(0..4 + len);
                                on_device_list(&app, parse_device_block(&payload)).await;
                            }
                        }
                    }
                    let _ = child.wait().await;
                    TRACKER_PID.store(0, Ordering::SeqCst);
                }
                Err(_) => {}
            }
            if stop.load(Ordering::Relaxed) {
                return;
            }
            if started.elapsed() > Duration::from_secs(30) {
                backoff.reset();
            }
            let wait = backoff.delay();
            backoff.fail();
            tokio::time::sleep(wait).await;
        }
    });
}

/// Handle a device-list snapshot: drop offline entries, group by stable
/// hardware id, diff against the previous list, emit `devices` on change, and
/// toast once when ADB availability flips — mirrors `onDeviceList`.
async fn on_device_list(app: &AppHandle, raw0: Vec<RawDevice>) {
    let state = app.state::<AdbState>();
    let adb = state.adb.lock().unwrap().clone();
    let Some(adb) = adb else { return };

    let raw: Vec<RawDevice> = raw0.into_iter().filter(|d| d.state != "offline").collect();

    {
        let mut cache = state.serial_id_cache.lock().unwrap();
        let live: Vec<&String> = raw.iter().map(|d| &d.serial).collect();
        cache.retain(|s, _| live.contains(&s));
    }

    let mut cache_snapshot = state.serial_id_cache.lock().unwrap().clone();
    let mut with_ids: Vec<(String, RawDevice)> = Vec::with_capacity(raw.len());
    for d in raw {
        let id = group_id(&adb, &d, &mut cache_snapshot).await;
        with_ids.push((id, d));
    }
    *state.serial_id_cache.lock().unwrap() = cache_snapshot;

    let mut groups: Vec<(String, Vec<RawDevice>)> = Vec::new();
    for (id, d) in with_ids {
        if let Some(entry) = groups.iter_mut().find(|(gid, _)| gid == &id) {
            entry.1.push(d);
        } else {
            groups.push((id, vec![d]));
        }
    }
    let next: Vec<Device> = groups
        .into_iter()
        .map(|(id, g)| {
            let usb = g.iter().find(|d| !d.wireless);
            let wl = g
                .iter()
                .find(|d| d.wireless && d.serial.rsplit_once(':').map(|(_, p)| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())).unwrap_or(false))
                .or_else(|| g.iter().find(|d| d.wireless));
            let active = usb.or(wl).unwrap();
            Device {
                id,
                serial: active.serial.clone(),
                model: active.model.clone(),
                state: active.state.clone(),
                transport: if active.wireless { "wifi".into() } else { "usb".into() },
                usb_serial: usb.map(|d| d.serial.clone()),
                wifi_serial: wl.map(|d| d.serial.clone()),
            }
        })
        .collect();

    let changed = *state.devices.lock().unwrap() != next;
    if changed {
        *state.devices.lock().unwrap() = next.clone();
        let _ = app.emit("devices", next.clone());
    }

    let adb_now = next.iter().any(|d| d.state == "device");
    let prev = *state.prev_has_adb.lock().unwrap();
    if let Some(prev) = prev {
        if prev != adb_now {
            emit_toast(
                app,
                if adb_now { "ok" } else { "info" },
                if adb_now { "ADB connected — full features enabled" } else { "ADB disconnected — running over app link" },
            );
        }
    }
    *state.prev_has_adb.lock().unwrap() = Some(adb_now);
    *state.idle_since.lock().unwrap() = Instant::now();
}

/// Bring adb online: start its server + device tracker. Mirrors `activateAdb`.
async fn activate_adb(app: &AppHandle) {
    let state = app.state::<AdbState>();
    let adb = state.adb.lock().unwrap().clone();
    let Some(adb) = adb else { return };
    start_server(&adb).await;
    let already_running = state.tracker_stop.lock().unwrap().is_some();
    if !already_running {
        let stop = Arc::new(AtomicBool::new(false));
        *state.tracker_stop.lock().unwrap() = Some(stop.clone());
        spawn_tracker(app.clone(), adb, stop);
    }
    let _ = app.emit("tools", tools_status(&state));
}

/// Resolve adb, falling back to a cached/auto-downloaded copy so the user
/// never has to install platform-tools by hand — mirrors `ensureAdb`. Runs in
/// the background at startup; non-blocking (Wi-Fi features work regardless).
pub async fn ensure_adb(app: AppHandle, base_dir: PathBuf) {
    {
        let state = app.state::<AdbState>();
        if state.adb.lock().unwrap().is_some() {
            return activate_adb(&app).await;
        }
        if let Some(cached) = bundled_adb(&base_dir) {
            *state.adb.lock().unwrap() = Some(cached);
            return activate_adb(&app).await;
        }
    }
    emit_toast(&app, "info", "Setting up adb (one-time, ~5 MB)…");
    match download_adb(&base_dir).await {
        Ok(path) => {
            *app.state::<AdbState>().adb.lock().unwrap() = Some(path);
            emit_toast(&app, "ok", "adb ready — full features enabled");
            activate_adb(&app).await;
        }
        Err(e) => {
            emit_toast(&app, "bad", &format!("Couldn't auto-install adb: {e}. Wi-Fi features still work."));
        }
    }
}

/// Resolve adb/scrcpy/brew on PATH, then kick off `ensure_adb` in the
/// background — mirrors the `app.whenReady()` sequence in `index.js`.
fn state_adb(app: &AppHandle) -> Option<String> {
    app.state::<AdbState>().adb.lock().unwrap().clone()
}

pub async fn init(app: AppHandle, base_dir: PathBuf) {
    // Before ANY adb command (every adb command auto-starts the server): was a server
    // already running — Android Studio, a terminal, wireless debugging? Quit only stops
    // a server DroidDock itself started (user decision, 2026-10-29).
    ADB_SERVER_PREEXISTED.store(adb_server_listening(), Ordering::SeqCst);
    let adb = resolve_tool("adb").await;
    let scrcpy = resolve_tool("scrcpy").await;
    let brew = resolve_tool("brew").await;
    // Probed before the state lock, not inside it: this shells out, and the
    // guards in this block are `std::sync::Mutex` held across no await point
    // by design.
    let scrcpy_ver = match scrcpy.as_deref() {
        Some(p) => probe_scrcpy_version(p).await,
        None => None,
    };
    {
        let state = app.state::<AdbState>();
        *state.adb.lock().unwrap() = adb;
        *state.scrcpy.lock().unwrap() = scrcpy;
        *state.scrcpy_ver.lock().unwrap() = scrcpy_ver;
        *state.brew.lock().unwrap() = brew;
    }
    crate::shutdown::reap_orphan_tracker(&app);
    // Forwards a crashed run left in a still-running adb server (ours only).
    if let Some(adb) = state_adb(&app) {
        let a = app.clone();
        let _ = tokio::task::spawn_blocking(move || {
            crate::shutdown::remove_stale_forwards(&a, &adb, &augmented_path())
        })
        .await;
    }
    ensure_adb(app.clone(), base_dir).await;
    start_reconnect_scheduler(app);
}

// ── Background mDNS/tcp reconnect scheduler (mirrors scheduleMdns/runMdnsScan) ──

const MDNS_MIN: Duration = Duration::from_secs(10);
/// Back-off ceiling: a phone that has been away this long is probed every 5
/// minutes instead of every 10-30 s (each probe is two forks and a SYN on the
/// Wi-Fi radio).
const MDNS_MAX: Duration = Duration::from_secs(300);

/// Exponential back-off (10 s, 20 s, 40 s … 5 min) while no adb device is
/// live. Reset by a live device, a network change, a WS connect, the host
/// becoming active again, or any user-initiated adb action
/// ([`crate::pacing::poke_reconnect`]).
fn start_reconnect_scheduler(app: AppHandle) {
    crate::host::async_runtime::spawn(async move {
        let mut backoff = crate::pacing::Backoff::new(MDNS_MIN, MDNS_MAX);
        loop {
            // A poke resets the back-off and retries after a short settle
            // delay (the network is usually still coming up).
            let poked = tokio::select! {
                _ = tokio::time::sleep(backoff.delay()) => false,
                _ = crate::pacing::reconnect_poked() => true,
            };
            if poked {
                backoff.reset();
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
            let state = app.state::<AdbState>();
            // Gate on either a phone-initiated pause (AdbState.paused) or the
            // Mac-initiated tray pause (Config.paused_until) — either source
            // pausing stops the scan.
            let mac_paused = app.state::<crate::AppState>().config.lock().unwrap().is_paused();
            if state.paused.load(Ordering::Relaxed) || mac_paused {
                continue;
            }
            let has_live = state.devices.lock().unwrap().iter().any(|d| d.state == "device");
            if has_live {
                backoff.reset();
                continue;
            }
            let adb = state.adb.lock().unwrap().clone();
            let Some(adb) = adb else { continue };
            drop(state);

            let cfg_state = app.state::<crate::AppState>();
            let (guid, tcp_addr, auto_reconnect) = {
                let c = cfg_state.config.lock().unwrap();
                (c.device_guid.clone(), c.tcp_addr.clone(), c.auto_reconnect)
            };
            drop(cfg_state);

            if auto_reconnect {
                if let Some(guid) = guid {
                    let _ = connect_by_guid(&adb, &guid).await;
                } else if let Some(addr) = tcp_addr {
                    connect_tcp(&adb, &addr).await;
                }
            }
            // Whatever the attempt did, the next one waits longer; a live
            // device (seen at the top of the loop) resets this.
            backoff.fail();
        }
    });
}

// ── Wireless pairing / mDNS / reconnect (mirrors the corresponding adb.js fns) ──

/// Android 11+ Wireless Debugging pairing. Returns the device guid.
/// Whether an `adb` failure is the transport giving out rather than the command
/// being answered "no".
///
/// The distinction is what makes retrying safe. These messages all mean the
/// request never reached the phone — the adb *server* went away underneath the
/// client, most often because another adb of a different version restarted it
/// (see `resolve_tool`). Retrying re-sends a request that was never delivered.
///
/// A wrong pairing code, by contrast, comes back as a normal unsuccessful
/// result, and retrying that would burn the phone's one-shot pairing session
/// for nothing.
fn is_transient_adb_error(e: &str) -> bool {
    let e = e.to_lowercase();
    [
        "protocol fault",
        "couldn't read status message",
        "connection reset",
        "device offline",
        "device still connecting",
        "closed",
        "cannot connect to daemon",
        "server is out of date",
    ]
    .iter()
    .any(|needle| e.contains(needle))
}

pub async fn pair_wireless(adb: &str, host_port: &str, code: &str) -> Result<String, String> {
    // Three attempts, because the failure this guards against is a server
    // restart: the first command dies, the client transparently starts a new
    // server, and the next attempt succeeds. This is what QR pairing was
    // failing on — a single `protocol fault (couldn't read status message)`
    // aborted the whole flow with the phone still waiting on its scan screen.
    let mut last = String::new();
    for attempt in 0..3 {
        match run(adb, &["pair", host_port, code], Duration::from_secs(15)).await {
            Ok(out) => return parse_pair_output(&out),
            Err(e) if is_transient_adb_error(&e) && attempt < 2 => {
                last = e;
                // Long enough for a restarting adb server to finish binding.
                tokio::time::sleep(Duration::from_millis(1200)).await;
            }
            Err(e) => return Err(e),
        }
    }
    Err(if last.is_empty() { "Pairing failed".into() } else { last })
}

/// Pull the device guid out of a successful `adb pair` result.
fn parse_pair_output(out: &str) -> Result<String, String> {
    if !out.to_lowercase().contains("successfully paired") {
        return Err(if out.trim().is_empty() { "Pairing failed".into() } else { out.trim().to_string() });
    }
    out.split("guid=")
        .nth(1)
        .map(|s| s.split(|c: char| c.is_whitespace() || c == ']').next().unwrap_or("").to_string())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "Pairing failed".to_string())
}

struct MdnsService {
    name: String,
    addr: String,
}

async fn mdns_services(adb: &str, kind: &str) -> Vec<MdnsService> {
    let out = run(adb, &["mdns", "services"], Duration::from_secs(6)).await.unwrap_or_default();
    let mut res = Vec::new();
    for line in out.lines().skip(1) {
        if !line.contains(kind) {
            continue;
        }
        let name = line.trim().split_whitespace().next().unwrap_or("").to_string();
        // crude `ip:port` extraction, mirrors the JS regex `(\d+\.\d+\.\d+\.\d+)[\s:](\d+)`
        if let Some(addr) = extract_ip_port(line) {
            if !name.is_empty() {
                res.push(MdnsService { name, addr });
            }
        }
    }
    res
}

fn extract_ip_port(line: &str) -> Option<String> {
    let bytes: Vec<&str> = line.split(|c: char| c.is_whitespace() || c == ':').collect();
    for (i, tok) in bytes.iter().enumerate() {
        let parts: Vec<&str> = tok.split('.').collect();
        if parts.len() == 4 && parts.iter().all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())) {
            // Must be the IMMEDIATELY following token. Scanning forward for
            // "the next number anywhere" could pair an IP with an unrelated
            // figure later in the line; the JS regex being ported requires
            // `ip:port` adjacency.
            if let Some(port) = bytes.get(i + 1) {
                if !port.is_empty() && port.chars().all(|c| c.is_ascii_digit()) {
                    return Some(format!("{tok}:{port}"));
                }
            }
        }
    }
    None
}

pub async fn mdns_connect_services(adb: &str) -> Vec<(String, String)> {
    mdns_services(adb, "_adb-tls-connect").await.into_iter().map(|s| (s.name, s.addr)).collect()
}

pub async fn mdns_pairing_services(adb: &str) -> Vec<(String, String)> {
    mdns_services(adb, "_adb-tls-pairing").await.into_iter().map(|s| (s.name, s.addr)).collect()
}

/// Reconnect a previously paired phone by discovering its current `ip:port`
/// over mDNS and matching the stored guid.
pub async fn connect_by_guid(adb: &str, guid: &str) -> Result<String, String> {
    let services = mdns_connect_services(adb).await;
    let addr = services
        .into_iter()
        .find(|(name, _)| name.starts_with(guid))
        .map(|(_, addr)| addr)
        .ok_or("Phone not announcing on the network yet")?;
    let out = run(adb, &["connect", &addr], Duration::from_secs(5)).await?;
    if !out.to_lowercase().contains("connected") {
        return Err(if out.trim().is_empty() { "connect failed".into() } else { out.trim().to_string() });
    }
    Ok(addr)
}

/// Drop all wireless adb connections (Unpair / forget).
pub async fn disconnect_all(adb: &str) {
    let _ = run(adb, &["disconnect"], DEFAULT_TIMEOUT).await;
}

/// LEGACY (<Android 11): flip adbd to TCP over an existing USB link. Returns `ip:5555`.
pub async fn go_wireless(adb: &str, serial: &str) -> Result<String, String> {
    let route = run(adb, &["-s", serial, "shell", "ip route"], DEFAULT_TIMEOUT).await?;
    let ip = route
        .split("src ")
        .nth(1)
        .and_then(|s| s.split_whitespace().next())
        .filter(|s| s.split('.').count() == 4)
        .ok_or("Couldn't read the phone's Wi-Fi IP — is the phone on Wi-Fi?")?;
    let addr = format!("{ip}:5555");
    run(adb, &["-s", serial, "tcpip", "5555"], DEFAULT_TIMEOUT).await?;
    for _ in 0..5 {
        tokio::time::sleep(Duration::from_millis(1200)).await;
        if let Ok(out) = run(adb, &["connect", &addr], DEFAULT_TIMEOUT).await {
            if out.contains("connected") {
                return Ok(addr);
            }
        }
    }
    Err(format!("Couldn't reach {addr} — phone and Mac on the same network?"))
}

/// Quiet reconnect attempt for a previously used wireless address.
pub async fn connect_tcp(adb: &str, addr: &str) {
    let _ = run(adb, &["connect", addr], Duration::from_secs(4)).await;
}

// ── scrcpy (mirror / camera) ─────────────────────────────────────────────

fn scrcpy_env(adb: Option<&str>) -> HashMap<String, String> {
    let mut env: HashMap<String, String> = std::env::vars().collect();
    env.insert("PATH".into(), augmented_path());
    if let Some(adb) = adb {
        env.insert("ADB".into(), adb.into());
    }
    env
}

/// Spawn scrcpy in camera mode, detached (its own OS window) — mirrors `camera`.
pub fn camera(app: AppHandle, scrcpy: &str, serial: &str, adb: Option<&str>) -> Result<(), String> {
    std::process::Command::new(scrcpy)
        .args(["-s", serial, "--video-source=camera", "--camera-facing=back", "--no-audio", "--window-title", "DroidDock — Camera"])
        .envs(scrcpy_env(adb))
        // Detached with stdio ignored, matching the Electron reference's
        // `spawn(..., {detached:true, stdio:'ignore'})`. Without this each
        // session left a zombie behind and scrcpy's chatter went to our stdio.
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        // Piped, not nulled: `reap` needs scrcpy's own message to explain an
        // immediate failure. It drains the pipe, so nothing can block.
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map(|c| reap(app, "ADB camera", c))
        .map_err(|e| e.to_string())
}

/// Spawn scrcpy in mirror mode, detached — mirrors `mirror`.
/// The quality flags every scrcpy launch shares, from `Config`.
///
/// scrcpy's own defaults (8 Mbps, uncapped fps, native resolution) are tuned
/// for a phone-shaped window on a USB cable. Returned as owned Strings because
/// they carry runtime values; the caller borrows them into its arg list.
pub fn scrcpy_quality_args(app: &AppHandle) -> Vec<String> {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let mut v = vec![
        format!("--video-bit-rate={}M", cfg.mirror_bitrate_mbps.clamp(1, 50)),
        format!("--max-fps={}", cfg.mirror_fps.clamp(15, 120)),
    ];
    // 0 means "the device's own resolution" — scrcpy expresses that by the
    // flag being absent, not by a zero.
    if cfg.mirror_max_size > 0 {
        v.push(format!("--max-size={}", cfg.mirror_max_size));
    }
    v
}

pub fn mirror(app: AppHandle, scrcpy: &str, serial: &str, caps: ScrcpyCaps, adb: Option<&str>) -> Result<(), String> {
    let quality = scrcpy_quality_args(&app);
    let prefs = mirror_prefs(&app);
    std::process::Command::new(scrcpy)
        .args(["-s", serial, "--window-title", "DroidDock — Mirror"])
        .args(&quality)
        .args(scrcpy_option_args(&prefs, caps))
        .envs(scrcpy_env(adb))
        // Detached with stdio ignored, matching the Electron reference's
        // `spawn(..., {detached:true, stdio:'ignore'})`. Without this each
        // session left a zombie behind and scrcpy's chatter went to our stdio.
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        // Piped, not nulled: `reap` needs scrcpy's own message to explain an
        // immediate failure. It drains the pipe, so nothing can block.
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map(|c| reap(app, "ADB mirror", c))
        .map_err(|e| e.to_string())
}

/// Tier D — desktop mode ("wireless DeX for everyone", as AirSync puts it).
///
/// `--new-display` asks Android to create a *secondary virtual display* and
/// mirror that instead of the phone's own screen, so the phone stays usable and
/// the Mac window behaves like a real second desktop with its own launcher and
/// freeform windows. It needs Android 11+ on the phone and scrcpy 2.5+ on the
/// Mac; older combinations fail at spawn with scrcpy's own message rather than
/// silently falling back to plain mirroring, which would be indistinguishable
/// from the feature not working.
///
/// `--new-display=` with no value lets scrcpy pick the resolution/density from
/// the device default — matching what AirSync's desktop launcher does.
/// A landscape virtual-display size for desktop mode's "Auto" setting.
///
/// Derived from this Mac's primary display so the Android desktop lands at a
/// resolution that suits the screen it will be shown on, then clamped: below
/// 1280×800 Android's desktop UI starts overlapping itself, and above
/// 1920×1080 the phone is encoding far more pixels than a window on a laptop
/// screen can show. Falls back to 1920×1080 if the monitor can't be read.
#[cfg(target_os = "macos")]
fn main_display_points() -> Option<(u32, u32)> {
    #[link(name = "CoreGraphics", kind = "framework")]
    extern "C" {
        fn CGMainDisplayID() -> u32;
        fn CGDisplayPixelsWide(display: u32) -> usize;
        fn CGDisplayPixelsHigh(display: u32) -> usize;
    }
    // SAFETY: plain value-returning CoreGraphics queries, valid from any thread.
    let (w, h) = unsafe {
        let id = CGMainDisplayID();
        (CGDisplayPixelsWide(id), CGDisplayPixelsHigh(id))
    };
    (w > 0 && h > 0).then_some((w as u32, h as u32))
}

#[cfg(not(target_os = "macos"))]
fn main_display_points() -> Option<(u32, u32)> {
    None
}

fn auto_desktop_size(app: &AppHandle) -> String {
    // The host has no webview to ask, so read the main display's size (in
    // points) straight from CoreGraphics.
    let _ = app;
    let (mut w, mut h) = main_display_points().unwrap_or((1920, 1080));

    // A portrait or square monitor would otherwise hand the same portrait
    // geometry back to the phone — the exact bug this is fixing.
    if h > w {
        std::mem::swap(&mut w, &mut h);
    }
    // Leave room for the menu bar, the window's title bar and the Dock: a canvas
    // the size of the whole screen opens a window that runs off the bottom and
    // right edges (seen on a 1440×900 MacBook Air). Keep the screen's shape.
    let (aw, ah) = usable_points(w, h);
    let (w, h) = ((aw as f64 * 0.94) as u32, (ah as f64 * 0.94) as u32);
    let w = w.clamp(960, 1920);
    let h = h.clamp(600, 1080);
    // Odd dimensions make H.264 encoders unhappy; both axes must be even.
    format!("{}x{}", w & !1, h & !1)
}

/// Room a window can use on a `w`×`h`-point screen: minus the menu bar, the
/// window's own title bar and a typical Dock.
fn usable_points(w: u32, h: u32) -> (u32, u32) {
    (w.saturating_sub(16), h.saturating_sub(25 + 28 + 70))
}

/// The window size (points) a `WxH` canvas gets on this screen: scaled down,
/// aspect kept, so the whole window fits the usable area.
fn fitted_window(geometry: &str) -> Option<(u32, u32, u32, u32)> {
    let (sw, sh) = main_display_points()?;
    let mut it = geometry.split(['x', '/']).filter_map(|n| n.trim().parse::<f64>().ok());
    let (dw, dh) = (it.next()?, it.next()?);
    if dw <= 0.0 || dh <= 0.0 {
        return None;
    }
    let (aw, ah) = usable_points(sw, sh);
    let k = (aw as f64 / dw).min(ah as f64 / dh).min(1.0);
    let (ww, wh) = ((dw * k).round() as u32 & !1, (dh * k).round() as u32 & !1);
    Some((ww, wh, sw.saturating_sub(ww) / 2, 25 + 28 + ah.saturating_sub(wh) / 2))
}

/// `--window-*` arguments that open the desktop window fully on screen and centred.
/// scrcpy refuses `--window-width/--window-height` with `--flex-display` (the window
/// *is* the display there), so flex gets only the position; its canvas is fitted instead.
fn desktop_window_args(geometry: &str, flex: bool) -> Vec<String> {
    let Some((ww, wh, x, y)) = fitted_window(geometry) else { return vec![] };
    let mut v = vec![format!("--window-x={x}"), format!("--window-y={y}")];
    if !flex {
        v.push(format!("--window-width={ww}"));
        v.push(format!("--window-height={wh}"));
    }
    v
}

/// The density a virtual display is created at, and therefore whether Android
/// serves its phone layout or its desktop one.
///
/// This is the whole mechanism behind "why does the app open stretched".
/// Android picks a layout from `smallestScreenWidthDp = px ÷ (dpi ÷ 160)`, so
/// the same 1920×1080 surface is:
///
/// - ~731 dp at a phone's native ~420 dpi → the phone layout, magnified
/// - 1920 dp at 160 dpi → the `sw720dp` bucket → tablet/desktop layouts
///
/// Passing a size with no `/dpi` inherits the phone's own density, which is
/// exactly what the old code did and why desktop mode looked like a blown-up
/// phone. Expressed as a user choice rather than a constant because "which
/// layout do I want" is a preference, not a correctness question — some apps
/// genuinely behave better in their phone layout.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UiMode {
    /// ~160 dpi. Tablet/desktop layouts, freeform windows.
    Desktop,
    /// ~240 dpi. Larger touch targets, still the large-screen layouts.
    Tablet,
    /// No `/dpi` at all — the device's own density, i.e. the phone layout.
    /// This reproduces the pre-existing behaviour exactly.
    Phone,
}

impl UiMode {
    /// Parses the config string. Anything unrecognised falls back to `Desktop`,
    /// which is the mode the feature exists to provide.
    pub fn parse(s: &str) -> Self {
        match s {
            "phone" => Self::Phone,
            "tablet" => Self::Tablet,
            _ => Self::Desktop,
        }
    }

    /// The `/dpi` suffix for a `--new-display` value, empty for `Phone`.
    fn dpi_suffix(self) -> &'static str {
        match self {
            Self::Desktop => "/160",
            Self::Tablet => "/240",
            Self::Phone => "",
        }
    }
}

/// Read the mirror-related settings once, so a single spawn can't observe two
/// different configs.
struct MirrorPrefs {
    ui_mode: UiMode,
    flex: bool,
    app_window_chrome: bool,
    app_window_keep_alive: bool,
    codec: String,
    audio: bool,
    uhid: bool,
    stay_awake: bool,
    turn_screen_off: bool,
    always_on_top: bool,
}

fn mirror_prefs(app: &AppHandle) -> MirrorPrefs {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    MirrorPrefs {
        ui_mode: UiMode::parse(&cfg.desktop_ui_mode),
        flex: cfg.desktop_flex,
        app_window_chrome: cfg.app_window_chrome,
        app_window_keep_alive: cfg.app_window_keep_alive,
        codec: cfg.mirror_codec,
        audio: cfg.mirror_audio,
        uhid: cfg.scrcpy_uhid,
        stay_awake: cfg.scrcpy_stay_awake,
        turn_screen_off: cfg.scrcpy_turn_screen_off,
        always_on_top: cfg.scrcpy_always_on_top,
    }
}

/// Flags shared by every scrcpy launch that the user can opt into.
///
/// All default to off/absent, so with a freshly-defaulted config this returns
/// only what scrcpy would have done anyway — the existing mirror behaviour is
/// unchanged unless the user turns something on.
fn scrcpy_option_args(p: &MirrorPrefs, caps: ScrcpyCaps) -> Vec<String> {
    let mut v = Vec::new();
    // "h264" is the default and means "say nothing" — scrcpy's own default is
    // H.264, and naming a codec the device can't encode is a hard failure.
    if p.codec == "h265" {
        v.push("--video-codec=h265".to_string());
    }
    // scrcpy forwards audio by default on Android 11+; this flag is only ever
    // the opt-*out*, which is why `audio: true` adds nothing.
    if !p.audio {
        v.push("--no-audio".to_string());
    }
    if p.uhid && caps.uhid {
        v.push("--keyboard=uhid".to_string());
    }
    if p.stay_awake {
        v.push("--stay-awake".to_string());
    }
    if p.turn_screen_off {
        v.push("--turn-screen-off".to_string());
    }
    if p.always_on_top {
        v.push("--always-on-top".to_string());
    }
    v
}

pub fn desktop(
    app: AppHandle,
    scrcpy: &str,
    serial: &str,
    size: Option<&str>,
    caps: ScrcpyCaps,
    adb: Option<&str>,
) -> Result<(), String> {
    if !caps.virtual_display {
        return Err(SCRCPY_TOO_OLD.into());
    }
    let prefs = mirror_prefs(&app);

    // `--new-display=<WxH>/<dpi>`. The size decides the canvas; the density
    // decides which Android layout fills it. "Auto" derives a landscape size
    // from this Mac's screen, because a phone left to choose picks its own
    // portrait geometry — the one shape desktop mode exists to avoid.
    let flex = prefs.flex && caps.flex_display;
    let mut geometry = match size {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => auto_desktop_size(&app),
    };
    // With flex the window is the display, so a canvas bigger than the screen
    // would open a window that runs off it: shrink the canvas itself to fit.
    if flex {
        if let Some((ww, wh, _, _)) = fitted_window(&geometry) {
            geometry = format!("{ww}x{wh}");
        }
    }
    let display_arg = format!("--new-display={geometry}{}", prefs.ui_mode.dpi_suffix());

    let mut cmd = std::process::Command::new(scrcpy);
    cmd.args(["-s", serial, &display_arg, "--window-title", "DroidDock — Desktop"]);
    // Open the window fully on screen (the canvas can be larger than the Mac's usable area).
    cmd.args(desktop_window_args(&geometry, flex));
    // Flex display: resizing the Mac window resizes the Android display itself
    // rather than scaling a fixed one.
    if flex {
        cmd.arg("--flex-display");
    }
    // Without this the Android keyboard opens on the *phone's* screen while you
    // type into the Mac window — which defeats the point of a desktop you can
    // work in. Android's default IME policy sends it to the default display.
    if caps.ime_policy {
        cmd.arg("--display-ime-policy=local");
    }
    // Deliberately NOT `scrcpy_quality_args`: that carries `--max-size`, which
    // re-caps the very display this function just sized. Bit rate and fps still
    // apply — desktop mode drives ~3× the pixels of a portrait phone view.
    cmd.args(desktop_quality_args(&app));
    cmd.args(scrcpy_option_args(&prefs, caps));
    cmd.envs(scrcpy_env(adb))
        // Detached with stdio ignored, matching the Electron reference's
        // `spawn(..., {detached:true, stdio:'ignore'})`. Without this each
        // session left a zombie behind and scrcpy's chatter went to our stdio.
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        // Piped, not nulled: `reap` needs scrcpy's own message to explain an
        // immediate failure. It drains the pipe, so nothing can block.
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map(|c| reap(app, "Desktop mode", c))
        .map_err(|e| e.to_string())
}

/// What the UI shows when the local scrcpy predates virtual displays.
const SCRCPY_TOO_OLD: &str =
    "Desktop mode needs scrcpy 3.0 or newer — run `brew upgrade scrcpy`, or use Install scrcpy in Settings";

/// Bit rate and frame rate without `--max-size`.
///
/// Split out from `scrcpy_quality_args` because the two virtual-display modes
/// size their display explicitly; adding a longest-edge cap on top silently
/// undoes that, which is why desktop mode looked soft at every setting.
fn desktop_quality_args(app: &AppHandle) -> Vec<String> {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    vec![
        format!("--video-bit-rate={}M", cfg.mirror_bitrate_mbps.clamp(1, 50)),
        format!("--max-fps={}", cfg.mirror_fps.clamp(15, 120)),
    ]
}

/// Mirror with one app started on it. On a new virtual display this is how you
/// get "that Android app, in its own Mac window" rather than a mirror of the
/// whole phone — the Apps grid's "open on Mac" action.
pub fn mirror_app(
    app: AppHandle,
    scrcpy: &str,
    serial: &str,
    package: &str,
    new_display: bool,
    caps: ScrcpyCaps,
    adb: Option<&str>,
) -> Result<(), String> {
    if new_display && !caps.virtual_display {
        return Err(SCRCPY_TOO_OLD.into());
    }
    let prefs = mirror_prefs(&app);
    let mut cmd = std::process::Command::new(scrcpy);
    cmd.args(["-s", serial]);
    if new_display {
        // Size deliberately omitted — with flex display the *window* drives the
        // display size, so only the density needs stating. The old bare
        // `--new-display` let the phone choose both, and a phone chooses its
        // own portrait geometry at its own dpi: the tall, stretched window this
        // is fixing.
        cmd.arg(format!("--new-display={}", prefs.ui_mode.dpi_suffix()));
        if prefs.flex && caps.flex_display {
            cmd.arg("--flex-display");
        }
        // No launcher, status bar or nav bar around the app — this is what
        // makes it read as a Mac window of that app rather than a phone screen
        // that happens to have the app open.
        if !prefs.app_window_chrome {
            cmd.arg("--no-vd-system-decorations");
        }
        // On close, hand the app back to the phone instead of killing it.
        if prefs.app_window_keep_alive && caps.keep_content {
            cmd.arg("--no-vd-destroy-content");
        }
        // Keyboard in this window, not on the phone — see `desktop`.
        if caps.ime_policy {
            cmd.arg("--display-ime-policy=local");
        }
    }
    // scrcpy takes `--start-app=+pkg` to force-stop first, so re-launching an
    // app that is already running on the phone still lands on the new display
    // instead of silently resuming on the built-in one.
    cmd.args([
        format!("--start-app=+{package}"),
        "--window-title".to_string(),
        format!("DroidDock — {package}"),
    ]);
    // Bit rate and fps, but no `--max-size` on a virtual display — see
    // `desktop_quality_args`. Without a new display this is an ordinary mirror
    // and keeps the resolution cap it always had.
    if new_display {
        cmd.args(desktop_quality_args(&app));
    } else {
        cmd.args(scrcpy_quality_args(&app));
    }
    cmd.args(scrcpy_option_args(&prefs, caps));
    cmd.envs(scrcpy_env(adb))
        // Detached with stdio ignored, matching the Electron reference's
        // `spawn(..., {detached:true, stdio:'ignore'})`. Without this each
        // session left a zombie behind and scrcpy's chatter went to our stdio.
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        // Piped, not nulled: `reap` needs scrcpy's own message to explain an
        // immediate failure. It drains the pipe, so nothing can block.
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map(|c| reap(app, "App window", c))
        .map_err(|e| e.to_string())
}

// ── Android freeform / desktop-windowing settings (opt-in) ───────────────
//
// A low-density virtual display is enough to get large-screen *layouts*. The
// full freeform desktop — draggable, resizable app windows on that display —
// additionally needs three secure system settings on the phone.
//
// These are the user's device, not ours: they persist after DroidDock is
// uninstalled, so nothing here is applied as a side effect of starting a
// mirror. The UI asks, this applies, and `desktop_settings_revert` puts back
// the values that were there before rather than blindly writing 0.

/// The globals that control freeform windowing, in the order the UI shows them.
const FREEFORM_KEYS: [&str; 3] = [
    "enable_freeform_support",
    "force_desktop_mode_on_external_displays",
    "enable_non_resizable_multi_window",
];

/// Android 15 is where desktop windowing on a secondary display became real.
const FREEFORM_MIN_SDK: u32 = 35;

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FreeformStatus {
    /// The phone's API level, if it could be read.
    pub sdk: Option<u32>,
    /// Whether this phone is new enough for the settings to mean anything.
    pub supported: bool,
    /// Current value of each key, in `FREEFORM_KEYS` order. `None` = unset.
    pub values: Vec<Option<String>>,
    /// True when all three read as `1`.
    pub enabled: bool,
}

async fn read_setting(adb: &str, serial: &str, key: &str) -> Option<String> {
    let out = run(adb, &["-s", serial, "shell", "settings", "get", "global", key], DEFAULT_TIMEOUT)
        .await
        .ok()?;
    let v = out.trim().to_string();
    // `settings get` prints the literal string "null" for an unset key.
    if v.is_empty() || v == "null" {
        None
    } else {
        Some(v)
    }
}

/// Read the phone's current freeform settings without changing anything.
pub async fn freeform_status(adb: &str, serial: &str) -> FreeformStatus {
    let sdk = run(adb, &["-s", serial, "shell", "getprop", "ro.build.version.sdk"], DEFAULT_TIMEOUT)
        .await
        .ok()
        .and_then(|s| s.trim().parse::<u32>().ok());
    let mut values = Vec::with_capacity(FREEFORM_KEYS.len());
    for key in FREEFORM_KEYS {
        values.push(read_setting(adb, serial, key).await);
    }
    let enabled = values.iter().all(|v| v.as_deref() == Some("1"));
    FreeformStatus {
        sdk,
        supported: sdk.is_some_and(|s| s >= FREEFORM_MIN_SDK),
        values,
        enabled,
    }
}

/// Turn freeform windowing on, capturing the prior values first so
/// `desktop_settings_revert` can restore exactly what was there.
///
/// Returns the captured prior state for the caller to persist.
pub async fn freeform_enable(adb: &str, serial: &str) -> Result<Map<String, Value>, String> {
    let mut prev = Map::new();
    for key in FREEFORM_KEYS {
        let before = read_setting(adb, serial, key).await;
        prev.insert(
            key.to_string(),
            before.map(Value::String).unwrap_or(Value::Null),
        );
        run(adb, &["-s", serial, "shell", "settings", "put", "global", key, "1"], DEFAULT_TIMEOUT)
            .await
            .map_err(|e| format!("couldn't set {key}: {e}"))?;
    }
    Ok(prev)
}

/// Put the three settings back the way `freeform_enable` found them.
///
/// A key that was unset before is deleted rather than set to 0 — those are
/// different states to Android, and writing 0 would leave a trace of us behind
/// on a device we were asked to leave alone.
pub async fn freeform_revert(adb: &str, serial: &str, prev: &Map<String, Value>) -> Result<(), String> {
    for key in FREEFORM_KEYS {
        match prev.get(key).and_then(Value::as_str) {
            Some(v) => {
                run(adb, &["-s", serial, "shell", "settings", "put", "global", key, v], DEFAULT_TIMEOUT)
                    .await
                    .map_err(|e| format!("couldn't restore {key}: {e}"))?;
            }
            // Either explicitly captured as null, or we have no record at all —
            // both mean "there was nothing here", so remove ours.
            None => {
                run(adb, &["-s", serial, "shell", "settings", "delete", "global", key], DEFAULT_TIMEOUT)
                    .await
                    .map_err(|e| format!("couldn't clear {key}: {e}"))?;
            }
        }
    }
    Ok(())
}

// ── Device info / filesystem / media (ported for fidelity; see module doc) ──

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub model: String,
    pub android: String,
    pub sdk: String,
    pub battery: Option<i64>,
    pub charging: bool,
}

pub async fn device_info(adb: &str, serial: &str) -> Result<DeviceInfo, String> {
    let prop = |k: &'static str| {
        let adb = adb.to_string();
        let serial = serial.to_string();
        async move { run(&adb, &["-s", &serial, "shell", &format!("getprop {k}")], DEFAULT_TIMEOUT).await.unwrap_or_default().trim().to_string() }
    };
    // Concurrent, matching the reference's Promise.all — these are four
    // independent shell round-trips and running them in series made the
    // Devices tab visibly slow to populate.
    let (model, android, sdk) = tokio::join!(
        prop("ro.product.model"),
        prop("ro.build.version.release"),
        prop("ro.build.version.sdk"),
    );
    let battery_raw = run(adb, &["-s", serial, "shell", "dumpsys battery"], DEFAULT_TIMEOUT).await.unwrap_or_default();
    let battery = battery_raw
        .lines()
        .find_map(|l| l.trim().strip_prefix("level:"))
        .and_then(|s| s.trim().parse::<i64>().ok());
    let charging = ["AC", "USB", "Wireless"].iter().any(|src| battery_raw.contains(&format!("{src} powered: true")));
    Ok(DeviceInfo { model: if model.is_empty() { serial.to_string() } else { model }, android, sdk, battery, charging })
}

#[derive(Serialize)]
#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub struct Entry {
    pub name: String,
    pub dir: bool,
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn list_dir(adb: &str, serial: &str, dir_path: &str) -> Result<Vec<Entry>, String> {
    let out = run(adb, &["-s", serial, "shell", &format!("ls -1p {}", shq(dir_path))], DEFAULT_TIMEOUT).await?;
    let mut entries: Vec<Entry> = out
        .lines()
        .map(|l| l.trim_end_matches('\r'))
        .filter(|l| !l.is_empty())
        .map(|name| match name.strip_suffix('/') {
            Some(stem) => Entry { name: stem.to_string(), dir: true },
            None => Entry { name: name.to_string(), dir: false },
        })
        .collect();
    entries.sort_by(|a, b| match (a.dir, b.dir) {
        (x, y) if x == y => a.name.cmp(&b.name),
        (true, false) => std::cmp::Ordering::Less,
        (false, true) => std::cmp::Ordering::Greater,
        _ => std::cmp::Ordering::Equal,
    });
    Ok(entries)
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn rm(adb: &str, serial: &str, remote_path: &str) -> Result<(), String> {
    let out = run(adb, &["-s", serial, "shell", &format!("rm -rf {} && echo OK", shq(remote_path))], DEFAULT_TIMEOUT).await?;
    if out.contains("OK") {
        Ok(())
    } else {
        Err(if out.trim().is_empty() { "delete failed".into() } else { out.trim().to_string() })
    }
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn rename(adb: &str, serial: &str, remote_path: &str, new_name: &str) -> Result<String, String> {
    let clean = new_name.trim();
    if clean.is_empty() || clean.contains('/') || clean == "." || clean == ".." {
        return Err("Invalid name".into());
    }
    let dir = remote_path.rsplit_once('/').map(|(d, _)| d).unwrap_or("");
    let dest = format!("{dir}/{clean}");
    let out = run(adb, &["-s", serial, "shell", &format!("mv -n {} {} && echo OK", shq(remote_path), shq(&dest))], DEFAULT_TIMEOUT).await?;
    if out.contains("OK") {
        Ok(dest)
    } else {
        Err(if out.trim().is_empty() { "rename failed".into() } else { out.trim().to_string() })
    }
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
fn unique_dest(dest_dir: &Path, file_name: &str) -> PathBuf {
    let path = Path::new(file_name);
    let ext = path.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    let stem = path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| file_name.to_string());
    let mut candidate = dest_dir.join(file_name);
    let mut i = 2;
    while candidate.exists() {
        candidate = dest_dir.join(format!("{stem} ({i}){ext}"));
        i += 1;
    }
    candidate
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn pull(adb: &str, serial: &str, remote_path: &str, dest_dir: &Path) -> Result<String, String> {
    let file_name = Path::new(remote_path).file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
    let dest = unique_dest(dest_dir, &file_name);
    let dest_s = dest.to_string_lossy().to_string();
    let output = Command::new(adb)
        .args(["-s", serial, "pull", remote_path, &dest_s])
        .env("PATH", augmented_path())
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if output.status.success() {
        Ok(dest_s)
    } else {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
        Err(if err.is_empty() { format!("pull failed ({:?})", output.status.code()) } else { err })
    }
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn push_paths(adb: &str, serial: &str, local_paths: &[String], remote_dir: &str) -> Result<usize, String> {
    for p in local_paths {
        let output = Command::new(adb)
            .args(["-s", serial, "push", p, remote_dir])
            .env("PATH", augmented_path())
            .output()
            .await
            .map_err(|e| e.to_string())?;
        if !output.status.success() {
            let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
            return Err(if err.is_empty() { format!("push failed ({:?})", output.status.code()) } else { err });
        }
    }
    Ok(local_paths.len())
}

pub async fn screenshot(adb: &str, serial: &str, dest_dir: &Path, timestamp: &str) -> Result<String, String> {
    let dest = dest_dir.join(format!("droiddock-{timestamp}.png"));
    let output = Command::new(adb)
        .args(["-s", serial, "exec-out", "screencap", "-p"])
        .env("PATH", augmented_path())
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if !output.status.success() {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if err.is_empty() { "screencap failed".into() } else { err });
    }
    tokio::fs::write(&dest, &output.stdout).await.map_err(|e| e.to_string())?;
    Ok(dest.to_string_lossy().to_string())
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn file_bytes(adb: &str, serial: &str, remote_path: &str) -> Result<Vec<u8>, String> {
    let output = Command::new(adb)
        .args(["-s", serial, "exec-out", "cat", remote_path])
        .env("PATH", augmented_path())
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if output.status.success() {
        Ok(output.stdout)
    } else {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
        Err(if err.is_empty() { "cat failed".into() } else { err })
    }
}

/// Run `adb -s <serial> shell <args…>` for input injection (`input tap`, `input keyevent`…).
/// The caller builds each argument; adb joins them into one remote command line, so anything
/// user-controlled must be single-quoted by the caller (see `mirror::sh_quote`).
pub async fn shell_input(adb: &str, serial: &str, args: &[&str]) -> Result<String, String> {
    let mut v: Vec<&str> = vec!["-s", serial, "shell"];
    v.extend_from_slice(args);
    run(adb, &v, Duration::from_secs(6)).await
}

/// The phone's natural (portrait-up) display size in pixels, from `wm size`
/// ("Physical size: 1080x2400", with an "Override size:" line taking precedence).
pub async fn display_size(adb: &str, serial: &str) -> Option<(u32, u32)> {
    let out = run(adb, &["-s", serial, "shell", "wm", "size"], Duration::from_secs(5)).await.ok()?;
    let mut found = None;
    for line in out.lines() {
        let Some((label, dims)) = line.split_once(':') else { continue };
        let Some((w, h)) = dims.trim().split_once('x') else { continue };
        let (Ok(w), Ok(h)) = (w.trim().parse::<u32>(), h.trim().parse::<u32>()) else { continue };
        if label.contains("Override") {
            return Some((w, h));
        }
        found = Some((w, h));
    }
    found
}

pub async fn phone_call(adb: &str, serial: &str, number: &str) -> Result<(), String> {
    let tel = format!("tel:{}", urlencode(number));
    run(adb, &["-s", serial, "shell", "am", "start", "-a", "android.intent.action.CALL", "-d", &tel], Duration::from_secs(8)).await?;
    Ok(())
}

#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
pub async fn phone_sms(adb: &str, serial: &str, number: &str) -> Result<(), String> {
    let sms = format!("sms:{}", urlencode(number));
    run(adb, &["-s", serial, "shell", "am", "start", "-a", "android.intent.action.SENDTO", "-d", &sms], Duration::from_secs(8)).await?;
    Ok(())
}

fn urlencode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

// ── Volume ────────────────────────────────────────────────────────────────

#[derive(Serialize, Clone, Copy)]
pub struct Volume {
    pub level: i64,
    pub max: i64,
}

pub async fn get_volume(adb: &str, serial: &str) -> Volume {
    if let Ok(out) = run(adb, &["-s", serial, "shell", "media", "volume", "--stream", "3", "--get"], Duration::from_secs(4)).await {
        if let Some((level, max)) = parse_volume_get(&out) {
            return Volume { level, max };
        }
    }
    if let Ok(raw) = run(adb, &["-s", serial, "shell", "settings", "get", "system", "volume_music"], Duration::from_secs(3)).await {
        if let Ok(level) = raw.trim().parse::<i64>() {
            return Volume { level, max: 15 };
        }
    }
    Volume { level: 8, max: 15 }
}

fn parse_volume_get(out: &str) -> Option<(i64, i64)> {
    // "...volume is 7 (min: 0, max: 15)". Anchored on the LAST "is " rather
    // than the first: an unanchored split lands on any earlier "is " in the
    // line (a device name like "Louis Phone" was enough) and then parses the
    // wrong token as the level.
    let after_is = out.rsplit("is ").next().filter(|t| *t != out)?;
    let level: i64 = after_is.split_whitespace().next()?.parse().ok()?;
    let max: i64 = after_is.split("max: ").nth(1)?.trim_end_matches(')').trim().parse().ok()?;
    Some((level, max))
}

/// Set media stream volume — tries `media volume`, then `cmd media_session`,
/// then repeated volume keyevents. Returns which method worked.
pub async fn set_volume(adb: &str, serial: &str, level: i64, current_level: i64) -> Result<&'static str, String> {
    if let Ok(out) = run(adb, &["-s", serial, "shell", "media", "volume", "--stream", "3", "--set", &level.to_string(), "--show"], Duration::from_secs(4)).await {
        if !out.to_lowercase().contains("error") {
            return Ok("media");
        }
    }
    if run(adb, &["-s", serial, "shell", "cmd", "media_session", "volume", "--set", &level.to_string(), "--stream", "3"], Duration::from_secs(3)).await.is_ok() {
        return Ok("cmd");
    }
    let delta = level - current_level;
    if delta == 0 {
        return Ok("noop");
    }
    let keycode = if delta > 0 { "24" } else { "25" };
    let times = delta.unsigned_abs().min(20);
    for _ in 0..times {
        run(adb, &["-s", serial, "shell", "input", "keyevent", keycode], Duration::from_secs(2)).await?;
    }
    Ok("keyevents")
}

// ── Live call control (ADB keycodes) ────────────────────────────────────

fn keycode_for(digit: &str) -> Option<&'static str> {
    Some(match digit {
        "0" => "7", "1" => "8", "2" => "9", "3" => "10",
        "4" => "11", "5" => "12", "6" => "13", "7" => "14",
        "8" => "15", "9" => "16", "*" => "17", "#" => "18",
        _ => return None,
    })
}

const KEYCODE_ENDCALL: &str = "6";
const KEYCODE_SPEAKERPHONE: &str = "168";
const KEYCODE_MUTE: &str = "91";

async fn keyevent(adb: &str, serial: &str, code: &str) -> Result<(), String> {
    run(adb, &["-s", serial, "shell", "input", "keyevent", code], Duration::from_secs(5)).await?;
    Ok(())
}

/// Returns "IDLE" | "RINGING" | "ACTIVE".
pub async fn get_call_state(adb: &str, serial: &str) -> String {
    let Ok(out) = run(adb, &["-s", serial, "shell", "getprop", "gsm.call.state"], Duration::from_secs(3)).await else {
        return "IDLE".into();
    };
    let out = out.trim().to_uppercase();
    if out == "IDLE" || out == "RINGING" || out == "ACTIVE" {
        return out;
    }
    let Ok(dump) = run(adb, &["-s", serial, "shell", "dumpsys", "telephony.registry"], Duration::from_secs(4)).await else {
        return "IDLE".into();
    };
    match dump.split("mCallState=").nth(1).and_then(|s| s.chars().next()) {
        Some('0') => "IDLE".into(),
        Some('1') => "RINGING".into(),
        Some(_) => "ACTIVE".into(),
        None => "IDLE".into(),
    }
}

pub async fn call_end(adb: &str, serial: &str) -> Result<(), String> {
    keyevent(adb, serial, KEYCODE_ENDCALL).await
}

/// Wake screen + bring in-call UI to front, then toggle speakerphone.
pub async fn call_speaker(adb: &str, serial: &str) -> Result<(), String> {
    let _ = run(adb, &["-s", serial, "shell", "input", "keyevent", "224"], Duration::from_secs(2)).await;
    let _ = run(adb, &["-s", serial, "shell", "am", "start", "-a", "android.intent.action.CALL_BUTTON"], Duration::from_secs(3)).await;
    tokio::time::sleep(Duration::from_millis(350)).await;
    keyevent(adb, serial, KEYCODE_SPEAKERPHONE).await
}

/// Wake screen + bring in-call UI to front, then toggle mic mute.
pub async fn call_mute(adb: &str, serial: &str) -> Result<(), String> {
    let _ = run(adb, &["-s", serial, "shell", "input", "keyevent", "224"], Duration::from_secs(2)).await;
    let _ = run(adb, &["-s", serial, "shell", "am", "start", "-a", "android.intent.action.CALL_BUTTON"], Duration::from_secs(3)).await;
    tokio::time::sleep(Duration::from_millis(350)).await;
    keyevent(adb, serial, KEYCODE_MUTE).await
}

pub async fn call_dtmf(adb: &str, serial: &str, digit: &str) -> Result<(), String> {
    let code = keycode_for(digit).ok_or_else(|| format!("Unknown DTMF digit: {digit}"))?;
    keyevent(adb, serial, code).await
}

/// Poll call state every second until IDLE (after an initial 1.5s settle
/// delay), emitting `call-state` — mirrors `startCallPolling`.
pub fn start_call_polling(app: AppHandle, serial: String) {
    let state = app.state::<AdbState>();
    if let Some(prev) = state.call_poll_stop.lock().unwrap().take() {
        prev.store(true, Ordering::Relaxed);
    }
    let stop = Arc::new(AtomicBool::new(false));
    *state.call_poll_stop.lock().unwrap() = Some(stop.clone());
    crate::host::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_millis(1500)).await;
        loop {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            let adb = app.state::<AdbState>().adb.lock().unwrap().clone();
            let Some(adb) = adb else { return };
            let call_state = get_call_state(&adb, &serial).await;
            let _ = app.emit("call-state", serde_json::json!({ "state": call_state, "serial": serial }));
            if call_state == "IDLE" {
                return;
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    });
}

// ── Tauri commands ───────────────────────────────────────────────────────

pub fn adb_tools(state: crate::host::State<AdbState>) -> ToolsStatus {
    tools_status(&state)
}

pub async fn adb_scrcpy_install(app: AppHandle, state: crate::host::State<'_, AdbState>) -> Result<(), String> {
    let brew = state.brew.lock().unwrap().clone();
    let Some(brew) = brew else {
        return Err("Homebrew not found — install it from brew.sh, then retry".into());
    };
    brew_install(&brew, "scrcpy").await?;
    let scrcpy = resolve_tool("scrcpy").await;
    let found = scrcpy.is_some();
    // Re-probe, or a scrcpy installed in this very call would keep the caps
    // that were computed when it didn't exist — every virtual-display action
    // would stay disabled until the next app launch.
    let ver = match scrcpy.as_deref() {
        Some(p) => probe_scrcpy_version(p).await,
        None => None,
    };
    *state.scrcpy.lock().unwrap() = scrcpy;
    *state.scrcpy_ver.lock().unwrap() = ver;
    let _ = app.emit("tools", tools_status(&state));
    if found {
        Ok(())
    } else {
        Err("scrcpy installed but could not be located".into())
    }
}

pub fn adb_devices(state: crate::host::State<AdbState>) -> Vec<Device> {
    state.devices.lock().unwrap().clone()
}

pub async fn adb_device_info(state: crate::host::State<'_, AdbState>, serial: String) -> Result<DeviceInfo, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    device_info(&adb, &serial).await
}

pub async fn adb_go_wireless(app: AppHandle, state: crate::host::State<'_, AdbState>, serial: String) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let addr = go_wireless(&adb, &serial).await?;
    let app_state = app.state::<crate::AppState>();
    let mut cfg = app_state.config.lock().unwrap();
    cfg.tcp_addr = Some(addr.clone());
    config::save(&app, &cfg);
    Ok(addr)
}

use crate::config;

pub async fn adb_pair_wireless(app: AppHandle, state: crate::host::State<'_, AdbState>, host_port: String, code: String) -> Result<Value, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let guid = pair_wireless(&adb, &host_port, &code).await?;
    crate::pacing::poke_reconnect();
    {
        let app_state = app.state::<crate::AppState>();
        let mut cfg = app_state.config.lock().unwrap();
        cfg.device_guid = Some(guid.clone());
        config::save(&app, &cfg);
    }
    let mut addr = None;
    for _ in 0..6 {
        if let Ok(a) = connect_by_guid(&adb, &guid).await {
            addr = Some(a);
            break;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Ok(serde_json::json!({ "guid": guid, "addr": addr }))
}

pub async fn adb_unpair(app: AppHandle, state: crate::host::State<'_, AdbState>) -> Result<(), String> {
    {
        let app_state = app.state::<crate::AppState>();
        let mut cfg = app_state.config.lock().unwrap();
        cfg.device_guid = None;
        cfg.tcp_addr = None;
        config::save(&app, &cfg);
    }
    let adb_opt = state.adb.lock().unwrap().clone();
    if let Some(adb) = adb_opt {
        disconnect_all(&adb).await;
    }
    state.serial_id_cache.lock().unwrap().clear();
    Ok(())
}

pub fn adb_paired_info(app: AppHandle) -> Value {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    serde_json::json!({ "guid": cfg.device_guid })
}

pub async fn adb_reconnect_now(app: AppHandle, state: crate::host::State<'_, AdbState>) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let app_state = app.state::<crate::AppState>();
    let (guid, tcp_addr) = {
        let cfg = app_state.config.lock().unwrap();
        (cfg.device_guid.clone(), cfg.tcp_addr.clone())
    };
    *state.idle_since.lock().unwrap() = Instant::now();
    crate::pacing::poke_reconnect();
    if let Some(guid) = guid {
        return connect_by_guid(&adb, &guid).await;
    }
    if let Some(addr) = tcp_addr {
        connect_tcp(&adb, &addr).await;
        return Ok(addr);
    }
    Err("No paired phone yet — pair ADB first".into())
}

pub async fn adb_qr_pair_start(app: AppHandle, state: crate::host::State<'_, AdbState>, service_name: String, password: String) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let token = state.qr_token.fetch_add(1, Ordering::SeqCst) + 1;
    let emit = |state_str: &str, text: &str, addr: Option<&str>| {
        let _ = app.emit("adb-qr-status", serde_json::json!({ "state": state_str, "text": text, "addr": addr }));
    };
    emit("waiting", "Waiting for scan…", None);
    let app2 = app.clone();
    crate::host::async_runtime::spawn(async move {
        let cur_token = |app: &AppHandle| app.state::<AdbState>().qr_token.load(Ordering::SeqCst);
        let deadline = Instant::now() + Duration::from_secs(120);
        // 1. Wait for the phone's pairing service to appear.
        let mut pair_addr = None;
        while Instant::now() < deadline && cur_token(&app2) == token {
            let svcs = mdns_pairing_services(&adb).await;
            let m = svcs.iter().find(|s| s.0.starts_with(&service_name)).or_else(|| svcs.first());
            if let Some((_, addr)) = m {
                pair_addr = Some(addr.clone());
                break;
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        if cur_token(&app2) != token {
            return;
        }
        let Some(pair_addr) = pair_addr else {
            emit_qr(&app2, "error", "Timed out — phone never reached the scan screen", None);
            return;
        };

        // 2. Pair using the QR password as the pairing code.
        let guid = match pair_wireless(&adb, &pair_addr, &password).await {
            Ok(g) => g,
            Err(e) => {
                if cur_token(&app2) == token {
                    emit_qr(&app2, "error", &e, None);
                }
                return;
            }
        };
        if cur_token(&app2) != token {
            return;
        }
        {
            let app_state = app2.state::<crate::AppState>();
            let mut cfg = app_state.config.lock().unwrap();
            cfg.device_guid = Some(guid.clone());
            config::save(&app2, &cfg);
        }

        // 3. Discover the connect endpoint and attach.
        emit_qr(&app2, "connecting", "Paired — keep the Wireless debugging screen open…", None);
        let mut addr = None;
        for _ in 0..20 {
            if cur_token(&app2) != token {
                return;
            }
            if let Ok(a) = connect_by_guid(&adb, &guid).await {
                addr = Some(a);
                break;
            }
            tokio::time::sleep(Duration::from_millis(1500)).await;
        }
        if cur_token(&app2) != token {
            return;
        }
        match addr {
            Some(addr) => emit_qr(&app2, "connected", "Connected", Some(&addr)),
            None => emit_qr(&app2, "error", "Paired ✓ — open the Wireless debugging screen to finish connecting", None),
        }
    });
    Ok(())
}

fn emit_qr(app: &AppHandle, state_str: &str, text: &str, addr: Option<&str>) {
    let _ = app.emit("adb-qr-status", serde_json::json!({ "state": state_str, "text": text, "addr": addr }));
}

pub fn adb_qr_pair_cancel(state: crate::host::State<AdbState>) {
    state.qr_token.fetch_add(1, Ordering::SeqCst);
}

pub async fn adb_camera(app: AppHandle, state: crate::host::State<'_, AdbState>, serial: String) -> Result<(), String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone();
    camera(app, &scrcpy, &serial, adb.as_deref())
}

pub async fn adb_mirror(app: AppHandle, state: crate::host::State<'_, AdbState>, serial: String) -> Result<(), String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone();
    let caps = scrcpy_caps(&state);
    mirror(app, &scrcpy, &serial, caps, adb.as_deref())
}

/// Tier D: mirror a *new virtual display* rather than the phone's own screen.
pub async fn adb_desktop(
    app: AppHandle,
    state: crate::host::State<'_, AdbState>,
    serial: String,
    size: Option<String>,
) -> Result<(), String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone();
    let caps = scrcpy_caps(&state);
    desktop(app, &scrcpy, &serial, size.as_deref(), caps, adb.as_deref())
}

/// Tier D: open one Android app in its own Mac window. With `new_display` the
/// app gets a virtual display to itself and the phone screen is left alone;
/// without it, the app is launched on the phone and the phone is mirrored.
pub async fn adb_mirror_app(
    app: AppHandle,
    state: crate::host::State<'_, AdbState>,
    serial: String,
    package: String,
    new_display: bool,
) -> Result<(), String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone();
    let caps = scrcpy_caps(&state);
    mirror_app(app, &scrcpy, &serial, &package, new_display, caps, adb.as_deref())
}

/// Read the phone's freeform/desktop-windowing settings. Pure read — nothing
/// on the device changes, so the UI can call this whenever it likes.
pub async fn adb_freeform_status(state: crate::host::State<'_, AdbState>) -> Result<FreeformStatus, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    Ok(freeform_status(&adb, &serial).await)
}

/// Turn freeform windowing on, remembering the prior values for Revert.
///
/// Only ever reached from an explicit button — never as a side effect of
/// starting a mirror. These settings outlive DroidDock's own install.
pub async fn adb_freeform_enable(
    app: AppHandle,
    state: crate::host::State<'_, AdbState>,
) -> Result<FreeformStatus, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    let status = freeform_status(&adb, &serial).await;
    if !status.supported {
        return Err(format!(
            "Needs Android 15 or newer — this phone reports API {}",
            status.sdk.map(|s| s.to_string()).unwrap_or_else(|| "unknown".into())
        ));
    }
    let prev = freeform_enable(&adb, &serial).await?;
    {
        let app_state = app.state::<crate::AppState>();
        let mut cfg = app_state.config.lock().unwrap();
        // Only record the *first* capture. Enabling twice must not overwrite
        // the original values with our own 1s — that would make Revert a no-op
        // and strand the phone in a state we can no longer undo.
        if cfg.freeform_prev.is_none() {
            cfg.freeform_prev = Some(prev);
        }
        crate::config::save(&app, &cfg);
    }
    Ok(freeform_status(&adb, &serial).await)
}

/// Put the phone's freeform settings back the way we found them.
pub async fn adb_freeform_revert(
    app: AppHandle,
    state: crate::host::State<'_, AdbState>,
) -> Result<FreeformStatus, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    let prev = {
        let app_state = app.state::<crate::AppState>();
        let cfg = app_state.config.lock().unwrap();
        cfg.freeform_prev.clone()
    };
    // No record means we never enabled it — clearing the keys is still the
    // right restore, since "we didn't set them" and "they were unset" are the
    // same end state from the phone's point of view.
    freeform_revert(&adb, &serial, &prev.unwrap_or_default()).await?;
    {
        let app_state = app.state::<crate::AppState>();
        let mut cfg = app_state.config.lock().unwrap();
        cfg.freeform_prev = None;
        crate::config::save(&app, &cfg);
    }
    Ok(freeform_status(&adb, &serial).await)
}

pub async fn adb_screenshot(app: AppHandle, state: crate::host::State<'_, AdbState>, serial: String) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let dir = crate::transfer::download_dir(&app).ok_or("no Downloads directory")?;
    let ts = now_timestamp();
    screenshot(&adb, &serial, &dir, &ts).await
}

fn now_timestamp() -> String {
    // No `chrono` dependency — reuse the same coarse `date`-shell-out pattern
    // as nowhere else in this codebase needs wall-clock formatting; here a
    // monotonically-increasing counter is enough to keep filenames unique
    // (uniqueness against existing files is already handled by callers that
    // need it — screenshots just need *a* distinct name per call).
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    format!("{secs}-{n}")
}

pub async fn adb_volume_get(state: crate::host::State<'_, AdbState>) -> Result<Volume, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    Ok(get_volume(&adb, &serial).await)
}

pub async fn adb_volume_set(state: crate::host::State<'_, AdbState>, level: i64, current_level: i64) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    Ok(set_volume(&adb, &serial, level, current_level).await?.to_string())
}

pub fn first_connected_serial(state: &AdbState) -> Option<String> {
    first_device_serial(state)
}

fn first_device_serial(state: &AdbState) -> Option<String> {
    state.devices.lock().unwrap().iter().find(|d| d.state == "device").map(|d| d.serial.clone())
}

pub async fn adb_call_end(state: crate::host::State<'_, AdbState>) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_end(&adb, &serial).await
}

pub async fn adb_call_speaker(state: crate::host::State<'_, AdbState>) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_speaker(&adb, &serial).await
}

pub async fn adb_call_mute(state: crate::host::State<'_, AdbState>) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_mute(&adb, &serial).await
}

pub async fn adb_call_dtmf(state: crate::host::State<'_, AdbState>, digit: String) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_dtmf(&adb, &serial, &digit).await
}

pub fn adb_call_start_polling(app: AppHandle, state: crate::host::State<AdbState>, serial: Option<String>) {
    let serial = serial.or_else(|| first_device_serial(&state));
    if let (Some(serial), true) = (serial, state.adb.lock().unwrap().is_some()) {
        start_call_polling(app, serial);
    }
}

// ── Per-app windows on a virtual display ────────────────────────────────
//
// A notification click on the Mac opens that app on its own virtual display
// ("second screen") and shows it as a Mac window, so the phone's own screen
// stays free. Swift orchestrates; the pieces live here:
//
//   1. `adb_app_window_open`  — spawn `scrcpy --new-display=WxH/dpi`, learn the display id from
//                               the server's "New display: … (id=N)" line. One window per app.
//   2. (Swift) `notif_open {key, displayId}` — tier 1: the phone fires the contentIntent there.
//   3. `adb_app_window_place` — verify with `dumpsys activity activities` where the app landed;
//                               tier 2 moves its task (`am display move-stack`, verified working
//                               on Android 16); tier 3 starts its launcher activity there.

/// A running app window, keyed `serial|package`.
#[derive(Clone, Copy)]
struct AppWin {
    display_id: u32,
    pid: u32,
}

static APP_WINDOWS: std::sync::LazyLock<Mutex<HashMap<String, AppWin>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashMap::new()));

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppWindowOpened {
    pub display_id: u32,
    /// True when this app already had a window and it was reused.
    pub reused: bool,
}

/// `New display: 540x1170/210 (id=26)` → 26.
fn parse_new_display_id(line: &str) -> Option<u32> {
    let rest = line.split("New display:").nth(1)?;
    let digits: String = rest.split("(id=").nth(1)?.chars().take_while(char::is_ascii_digit).collect();
    digits.parse().ok()
}

/// `wm density` → ("Physical density: 480" / "Override density: 420"), override wins.
async fn display_density(adb: &str, serial: &str) -> Option<u32> {
    let out = run(adb, &["-s", serial, "shell", "wm", "density"], Duration::from_secs(5)).await.ok()?;
    let mut found = None;
    for line in out.lines() {
        let Some((label, v)) = line.split_once(':') else { continue };
        let Ok(d) = v.trim().parse::<u32>() else { continue };
        if label.contains("Override") {
            return Some(d);
        }
        found = Some(d);
    }
    found
}

/// A phone-shaped virtual display for this Mac: the phone's own aspect and dp layout, rendered at up
/// to 2x the window that will show it (never above the phone's native size).
/// Returns `(geometry "WxH/dpi" , "WxH")`.
fn app_window_geometry(phone: (u32, u32), density: u32) -> (String, String) {
    let (pw, ph) = (phone.0.max(2), phone.1.max(2));
    let (ww, _, _, _) = fitted_window(&format!("{pw}x{ph}")).unwrap_or((pw / 3, ph / 3, 0, 0));
    let w = ((ww * 2).min(pw).max(360)) & !1;
    let h = ((w as f64 * ph as f64 / pw as f64).round() as u32) & !1;
    let dpi = ((density as f64 * w as f64 / pw as f64).round() as u32).clamp(120, 640);
    (format!("{w}x{h}/{dpi}"), format!("{w}x{h}"))
}

pub async fn adb_app_window_open(
    app: AppHandle,
    state: crate::host::State<'_, AdbState>,
    serial: String,
    package: String,
    title: String,
) -> Result<AppWindowOpened, String> {
    let prefs = mirror_prefs(&app);
    let key = format!("{serial}|{package}");
    open_vd_window(
        app,
        &state,
        VdWindow {
            serial,
            key,
            package: Some(package),
            kind: "app",
            title: format!("{title} — DroidDock"),
            decorations: prefs.app_window_chrome,
            keep_alive: prefs.app_window_keep_alive,
        },
    )
    .await
}

/// "Second screen": a phone-shaped virtual display with its own home screen, status bar and taskbar,
/// shown in a Mac window. Apps opened there never touch the phone's own screen, and closing the window
/// closes them (they never jump back onto the phone).
pub async fn adb_second_screen(
    app: AppHandle,
    state: crate::host::State<'_, AdbState>,
    serial: String,
) -> Result<AppWindowOpened, String> {
    let key = format!("{serial}|#second-screen");
    open_vd_window(
        app,
        &state,
        VdWindow {
            serial,
            key,
            package: None,
            kind: "second",
            title: "Second screen — DroidDock".into(),
            decorations: true,
            keep_alive: false,
        },
    )
    .await
}

/// One scrcpy window showing its own virtual display.
struct VdWindow {
    serial: String,
    /// Reuse key (`serial|package`, `serial|#second-screen`): one window each.
    key: String,
    /// The app a notification opened (its window's Home goes back to that app's start screen).
    package: Option<String>,
    /// "app" | "second": tells the Mac which controls to put under the window.
    kind: &'static str,
    title: String,
    /// Android's own home screen, status bar and taskbar on the display.
    decorations: bool,
    /// On close, move what was on the display to the phone instead of closing it.
    keep_alive: bool,
}

/// Spawn `scrcpy --new-display` (phone-shaped), learn the display id from the server's
/// "New display: … (id=N)" line, and tell the Mac (`vd-window` / `vd-window-closed`) so it can
/// attach Back / Home / Recent controls to the window.
async fn open_vd_window(app: AppHandle, state: &AdbState, w: VdWindow) -> Result<AppWindowOpened, String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let caps = scrcpy_caps(state);
    if !caps.virtual_display {
        return Err(SCRCPY_TOO_OLD.into());
    }
    let VdWindow { serial, key, package, kind, title, decorations, keep_alive } = w;
    if let Some(w) = APP_WINDOWS.lock().unwrap().get(&key).copied() {
        if kind == "second" {
            emit_toast(&app, "info", "The second screen is already open");
        }
        return Ok(AppWindowOpened { display_id: w.display_id, reused: true });
    }
    let phone = display_size(&adb, &serial).await.unwrap_or((1080, 2340));
    let density = display_density(&adb, &serial).await.unwrap_or(420);
    let (geometry, canvas) = app_window_geometry(phone, density);
    let prefs = mirror_prefs(&app);

    let mut cmd = std::process::Command::new(&scrcpy);
    cmd.args(["-s", &serial, &format!("--new-display={geometry}"), "--window-title", &title]);
    cmd.args(desktop_window_args(&canvas, false));
    if !decorations {
        cmd.arg("--no-vd-system-decorations");
    }
    if keep_alive && caps.keep_content {
        cmd.arg("--no-vd-destroy-content");
    }
    if caps.ime_policy {
        cmd.arg("--display-ime-policy=local");
    }
    cmd.args(desktop_quality_args(&app));
    // The phone's own screen must stay usable: never blank it for this window.
    cmd.args(scrcpy_option_args(&prefs, caps).into_iter().filter(|a| a != "--turn-screen-off"));
    cmd.envs(scrcpy_env(Some(&adb)))
        .stdin(std::process::Stdio::null())
        // scrcpy 5.x logs its INFO lines (including the server's "New display: … (id=N)") to stdout,
        // older versions to stderr: read both.
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
    let pid = child.id();
    OPEN_WINDOWS.lock().unwrap().push(pid);
    if crate::shutdown::quitting() {
        send_signal(pid, Signal::Term);
    }
    let (tx, rx) = std::sync::mpsc::channel::<Result<u32, String>>();
    let announced_flag = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    // First sighting of the display id (stdout or stderr reader): record it and tell the Mac.
    let announce = {
        let (key, serial, app, package) = (key.clone(), serial.clone(), app.clone(), package.clone());
        move |id: u32| {
            APP_WINDOWS.lock().unwrap().insert(key.clone(), AppWin { display_id: id, pid });
            let _ = app.emit(
                "vd-window",
                serde_json::json!({ "pid": pid, "displayId": id, "serial": serial, "kind": kind, "decorations": decorations, "package": package }),
            );
        }
    };
    if let Some(out) = child.stdout.take() {
        let (tx2, flag, announce) = (tx.clone(), announced_flag.clone(), announce.clone());
        std::thread::spawn(move || {
            use std::io::{BufRead, BufReader};
            for line in BufReader::new(out).lines().map_while(Result::ok) {
                if let Some(id) = parse_new_display_id(&line) {
                    if !flag.swap(true, std::sync::atomic::Ordering::SeqCst) {
                        announce(id);
                        let _ = tx2.send(Ok(id));
                    }
                }
            }
        });
    }
    let (wkey, wapp) = (key.clone(), app.clone());
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader};
        let started = Instant::now();
        let mut tail = String::new();
        if let Some(err) = child.stderr.take() {
            for line in BufReader::new(err).lines().map_while(Result::ok) {
                if let Some(id) = parse_new_display_id(&line) {
                    if !announced_flag.swap(true, std::sync::atomic::Ordering::SeqCst) {
                        announce(id);
                        let _ = tx.send(Ok(id));
                    }
                }
                if !line.trim().is_empty() {
                    tail = line;
                }
            }
        }
        let ok = child.wait().map(|s| s.success()).unwrap_or(false);
        OPEN_WINDOWS.lock().unwrap().retain(|&p| p != pid);
        let mut map = APP_WINDOWS.lock().unwrap();
        if map.get(&wkey).is_some_and(|w| w.pid == pid) {
            map.remove(&wkey);
        }
        drop(map);
        let _ = wapp.emit("vd-window-closed", serde_json::json!({ "pid": pid }));
        if !announced_flag.load(std::sync::atomic::Ordering::SeqCst) {
            let _ = tx.send(Err(if tail.is_empty() { "scrcpy exited before creating the display".into() } else { tail.clone() }));
        }
        if !ok && started.elapsed() < Duration::from_secs(3) {
            emit_toast(&wapp, "bad", &format!("App window failed — {tail}"));
        }
    });
    // Wait (without blocking the runtime) for the server to name the display.
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        match rx.try_recv() {
            Ok(Ok(id)) => return Ok(AppWindowOpened { display_id: id, reused: false }),
            Ok(Err(e)) => return Err(e),
            Err(std::sync::mpsc::TryRecvError::Disconnected) => return Err("scrcpy exited".into()),
            Err(std::sync::mpsc::TryRecvError::Empty) => {}
        }
        if Instant::now() > deadline {
            send_signal(pid, Signal::Term);
            // scrcpy's SDL loop may sleep through SIGTERM until another event arrives: don't leave an orphan window.
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_secs(2));
                if OPEN_WINDOWS.lock().unwrap().contains(&pid) {
                    send_signal(pid, Signal::Kill);
                }
            });
            return Err("Timed out waiting for the virtual display".into());
        }
        tokio::time::sleep(Duration::from_millis(120)).await;
    }
}

/// Back / Home for one virtual display (`input -d`). Recent is deliberately absent: Android routes
/// KEYCODE_APP_SWITCH to the phone's own screen whatever display is named, so the Mac builds its
/// own list from `adb_vd_tasks` instead. An app window has no home screen of its own, so its Home
/// (`package` set) brings that app back to its start screen.
pub async fn adb_vd_key(
    state: crate::host::State<'_, AdbState>,
    serial: String,
    display_id: u32,
    key: String,
    package: Option<String>,
) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    if let (Some(pkg), "home") = (package.as_deref(), key.as_str()) {
        let comp = launcher_activity(&adb, &serial, pkg).await.ok_or("This app has no launcher activity")?;
        let d = display_id.to_string();
        return run(&adb, &["-s", &serial, "shell", "am", "start", "--display", &d, "--activity-clear-top", "-n", &comp], Duration::from_secs(8))
            .await
            .map(|_| ());
    }
    let code = match key.as_str() {
        "back" => "KEYCODE_BACK",
        "home" => "KEYCODE_HOME",
        _ => return Err(format!("unknown key {key}")),
    };
    let d = display_id.to_string();
    run(&adb, &["-s", &serial, "shell", "input", "-d", &d, "keyevent", code], Duration::from_secs(5)).await.map(|_| ())
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VdTask {
    pub task_id: u32,
    pub package: String,
    pub visible: bool,
}

/// Apps open on one virtual display, front-most first (the Mac's "Recent" menu).
pub async fn adb_vd_tasks(
    state: crate::host::State<'_, AdbState>,
    serial: String,
    display_id: u32,
) -> Result<Vec<VdTask>, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    Ok(dump_tasks(&adb, &serial)
        .await
        .into_iter()
        .filter(|t| t.display == display_id)
        .filter_map(|t| t.packages.first().cloned().map(|package| VdTask { task_id: t.id, package, visible: t.visible }))
        .collect())
}

/// Bring an app already open on the display to the front (its launcher intent resumes the task).
pub async fn adb_vd_switch(
    state: crate::host::State<'_, AdbState>,
    serial: String,
    display_id: u32,
    package: String,
) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let comp = launcher_activity(&adb, &serial, &package).await.ok_or("This app has no launcher activity")?;
    let d = display_id.to_string();
    run(&adb, &["-s", &serial, "shell", "am", "start", "--display", &d, "-n", &comp], Duration::from_secs(8)).await.map(|_| ())
}

/// One task from `dumpsys activity activities`.
#[derive(Debug, Clone, PartialEq)]
struct TaskInfo {
    id: u32,
    display: u32,
    visible: bool,
    packages: Vec<String>,
}

/// Parse the per-display task lists (`Display #N` → `* Task{… #id … visible=…}` → `Hist … u0 pkg/.Cls`).
/// The first sighting of a task id wins: later lines in the dump (display-area trees) repeat tasks.
fn parse_tasks(dump: &str) -> Vec<TaskInfo> {
    let mut tasks: Vec<TaskInfo> = Vec::new();
    let mut display = 0u32;
    let mut cur: Option<usize> = None;
    for line in dump.lines() {
        if let Some(rest) = line.strip_prefix("Display #") {
            display = rest.chars().take_while(char::is_ascii_digit).collect::<String>().parse().unwrap_or(0);
            cur = None;
            continue;
        }
        let t = line.trim_start();
        if let Some(rest) = t.strip_prefix("* Task{") {
            let id = rest.split(" #").nth(1).and_then(|r| r.split(|c: char| !c.is_ascii_digit()).next()).and_then(|n| n.parse().ok());
            let visible = rest.contains("visible=true");
            cur = match id {
                Some(id) if rest.contains("type=standard") && !tasks.iter().any(|x| x.id == id) => {
                    tasks.push(TaskInfo { id, display, visible, packages: vec![] });
                    Some(tasks.len() - 1)
                }
                _ => None,
            };
        } else if let (Some(i), Some(rest)) = (cur, t.strip_prefix("* Hist")) {
            if let Some(pkg) = rest.split(" u0 ").nth(1).and_then(|r| r.split('/').next()) {
                if !tasks[i].packages.iter().any(|p| p == pkg) {
                    tasks[i].packages.push(pkg.to_string());
                }
            }
        }
    }
    tasks
}

/// The visible task showing `package`, preferring one already on `display`.
fn find_app_task(tasks: &[TaskInfo], package: &str, display: u32) -> Option<TaskInfo> {
    let mine = || tasks.iter().filter(|t| t.visible && t.packages.iter().any(|p| p == package));
    mine().find(|t| t.display == display).or_else(|| mine().next()).cloned()
}

async fn dump_tasks(adb: &str, serial: &str) -> Vec<TaskInfo> {
    run(adb, &["-s", serial, "shell", "dumpsys", "activity", "activities"], Duration::from_secs(10))
        .await
        .map(|d| parse_tasks(&d))
        .unwrap_or_default()
}

/// The package's launcher activity (`pkg/.Cls`).
async fn launcher_activity(adb: &str, serial: &str, package: &str) -> Option<String> {
    let out = run(
        adb,
        &["-s", serial, "shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", package],
        Duration::from_secs(6),
    )
    .await
    .ok()?;
    out.lines().map(str::trim).rfind(|l| l.contains('/') && l.starts_with(package)).map(str::to_string)
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppWindowPlaced {
    /// "notif" (opened there directly) | "move" (task moved) | "launch" (launcher started there).
    pub tier: &'static str,
}

/// Get `package`'s activity onto `display_id`, detecting which tier worked.
/// `fired`: the phone already fired the notification's tap action (tier 1 attempt or plain open).
pub async fn adb_app_window_place(
    state: crate::host::State<'_, AdbState>,
    serial: String,
    package: String,
    display_id: u32,
    fired: bool,
) -> Result<AppWindowPlaced, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    if fired {
        // The start is asynchronous: poll until the app has a visible task somewhere.
        for _ in 0..12 {
            if let Some(t) = find_app_task(&dump_tasks(&adb, &serial).await, &package, display_id) {
                if t.display == display_id {
                    return Ok(AppWindowPlaced { tier: "notif" });
                }
                // Tier 2: opened on another display; move its task over.
                let (task, disp) = (t.id.to_string(), display_id.to_string());
                for cmd in [["am", "display", "move-stack"], ["cmd", "activity", "display"]] {
                    let mut args = vec!["-s", serial.as_str(), "shell"];
                    args.extend(cmd);
                    if cmd[2] == "display" {
                        args.push("move-stack");
                    }
                    args.extend([task.as_str(), disp.as_str()]);
                    if run(&adb, &args, Duration::from_secs(6)).await.is_err() {
                        continue;
                    }
                    for _ in 0..6 {
                        tokio::time::sleep(Duration::from_millis(250)).await;
                        if find_app_task(&dump_tasks(&adb, &serial).await, &package, display_id)
                            .is_some_and(|t| t.display == display_id)
                        {
                            return Ok(AppWindowPlaced { tier: "move" });
                        }
                    }
                }
                break;
            }
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
    }
    // Tier 3: the app's launcher activity, started on the virtual display.
    let comp = launcher_activity(&adb, &serial, &package).await.ok_or("This app has no launcher activity")?;
    run(&adb, &["-s", &serial, "shell", "am", "start", "--display", &display_id.to_string(), "-n", &comp], Duration::from_secs(8)).await?;
    Ok(AppWindowPlaced { tier: "launch" })
}

// ── Screen-share prompt (MediaProjection consent) ──────────────────────

const DROIDDOCK_PKG: &str = "com.droiddock.app";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectionStatus {
    /// "allow" | "default" | "ignore" | "deny" | …
    pub mode: String,
    pub allowed: bool,
}

/// `PROJECT_MEDIA: allow; rejectTime=…` → "allow".
fn parse_appop_mode(out: &str) -> String {
    out.lines()
        .find_map(|l| l.split_once(':').filter(|(k, _)| k.trim() == "PROJECT_MEDIA").map(|(_, v)| v))
        .and_then(|v| v.split(';').next())
        .map(|m| m.trim().to_string())
        .filter(|m| !m.is_empty())
        .unwrap_or_else(|| "default".into())
}

pub async fn adb_projection_status(state: crate::host::State<'_, AdbState>) -> Result<ProjectionStatus, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    let out = run(&adb, &["-s", &serial, "shell", "appops", "get", DROIDDOCK_PKG, "PROJECT_MEDIA"], Duration::from_secs(6)).await?;
    let mode = parse_appop_mode(&out);
    Ok(ProjectionStatus { allowed: mode == "allow", mode })
}

pub async fn adb_projection_grant(state: crate::host::State<'_, AdbState>, allow: bool) -> Result<ProjectionStatus, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    let mode = if allow { "allow" } else { "default" };
    run(&adb, &["-s", &serial, "shell", "appops", "set", DROIDDOCK_PKG, "PROJECT_MEDIA", mode], Duration::from_secs(6)).await?;
    adb_projection_status(state).await
}


#[cfg(test)]
mod tests {
    use super::{app_window_geometry, find_app_task, is_transient_adb_error, parse_appop_mode, parse_new_display_id, parse_pair_output, parse_tasks, ScrcpyCaps, UiMode};

    /// The exact string wireless QR pairing died on must be recognised as
    /// transient, or the retry that fixes it never runs.
    #[test]
    fn the_pairing_failure_we_actually_hit_is_treated_as_transient() {
        assert!(is_transient_adb_error(
            "protocol fault (couldn't read status message): Undefined error: 0"
        ));
        assert!(is_transient_adb_error("adb: device offline"));
        assert!(is_transient_adb_error("error: closed"));
        assert!(is_transient_adb_error("Connection reset by peer"));
        assert!(is_transient_adb_error("adb server is out of date. killing..."));
    }

    /// A rejected pairing must NOT be retried: the phone's pairing session is
    /// single-use, so a second attempt with a bad code burns it and the user
    /// has to restart the whole flow on the phone.
    #[test]
    fn a_rejected_pairing_is_never_retried() {
        assert!(!is_transient_adb_error("failed to pair: wrong code"));
        assert!(!is_transient_adb_error("adb: failed to pair to 192.168.1.5:37000"));
        assert!(!is_transient_adb_error(""));
        assert!(!is_transient_adb_error("Pairing failed"));
    }

    /// The guid is what every later reconnect keys on, so parsing it out of
    /// adb's chatty success line is load-bearing.
    #[test]
    fn a_successful_pair_yields_its_guid() {
        let ok = "Successfully paired to 192.168.1.5:37000 [guid=adb-RZ8N70ABCDE-Xy9Qk2]";
        assert_eq!(parse_pair_output(ok).unwrap(), "adb-RZ8N70ABCDE-Xy9Qk2");

        // Success without a guid, and outright failure, are both errors rather
        // than an empty guid that would poison the reconnect path.
        assert!(parse_pair_output("Successfully paired to 1.2.3.4:5").is_err());
        assert!(parse_pair_output("failed to pair").is_err());
        assert!(parse_pair_output("").is_err());
    }

    /// The density suffix is the whole mechanism behind desktop-vs-phone
    /// layout. `Phone` must emit *nothing* — a `/0` or a `/420` would both be
    /// wrong, the first invalid and the second a guess at the device's density.
    #[test]
    fn ui_mode_dpi_suffixes() {
        assert_eq!(UiMode::Desktop.dpi_suffix(), "/160");
        assert_eq!(UiMode::Tablet.dpi_suffix(), "/240");
        assert_eq!(UiMode::Phone.dpi_suffix(), "");
    }

    /// An unrecognised value must land on `Desktop`, not panic and not silently
    /// pick `Phone` — a config written by a newer build should degrade to the
    /// mode the feature exists to provide.
    #[test]
    fn ui_mode_parse_is_total() {
        assert_eq!(UiMode::parse("desktop"), UiMode::Desktop);
        assert_eq!(UiMode::parse("tablet"), UiMode::Tablet);
        assert_eq!(UiMode::parse("phone"), UiMode::Phone);
        assert_eq!(UiMode::parse(""), UiMode::Desktop);
        assert_eq!(UiMode::parse("something-newer"), UiMode::Desktop);
    }

    /// The gates that stop us handing a flag to a scrcpy that can't parse it.
    /// Getting these wrong is a hard spawn failure, not a degraded mirror.
    #[test]
    fn scrcpy_caps_track_the_release_each_flag_landed_in() {
        // Unknown version: assume the oldest, enable nothing optional.
        let unknown = ScrcpyCaps::from_version(None);
        assert!(!unknown.virtual_display);
        assert!(!unknown.flex_display);
        assert!(!unknown.keep_content);
        assert!(!unknown.uhid);

        // 2.4 brought uhid and nothing else here.
        let v24 = ScrcpyCaps::from_version(Some((2, 4)));
        assert!(v24.uhid);
        assert!(!v24.virtual_display);

        // 3.0 is where --new-display arrived. The UI claimed 2.5 for a long
        // time; this is the assertion that keeps that claim from coming back.
        assert!(!ScrcpyCaps::from_version(Some((2, 5))).virtual_display);
        let v30 = ScrcpyCaps::from_version(Some((3, 0)));
        assert!(v30.virtual_display);
        assert!(!v30.keep_content);
        assert!(!v30.flex_display);

        // 3.1 adds --no-vd-destroy-content, 3.2 --display-ime-policy,
        // 4.0 --flex-display.
        assert!(ScrcpyCaps::from_version(Some((3, 1))).keep_content);
        assert!(!ScrcpyCaps::from_version(Some((3, 1))).ime_policy);
        assert!(ScrcpyCaps::from_version(Some((3, 2))).ime_policy);
        assert!(!ScrcpyCaps::from_version(Some((3, 9))).flex_display);
        let v41 = ScrcpyCaps::from_version(Some((4, 1)));
        assert!(v41.flex_display && v41.keep_content && v41.virtual_display && v41.uhid);
        assert!(v41.ime_policy);
    }

    /// `tools_status` must never take the same lock twice in one expression.
    ///
    /// It used to, and because a struct literal keeps its temporaries alive
    /// until the whole struct is built, the second `state.adb.lock()` waited on
    /// a guard the same thread was still holding. That deadlocked the first
    /// caller — normally the main thread, via the `adb_tools` command — and
    /// took the UI, the tray and eventually the WebSocket accept loop with it.
    /// Run on a worker thread with a deadline so a regression fails the suite
    /// instead of hanging it.
    #[test]
    fn tools_status_does_not_deadlock_on_itself() {
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let state = super::AdbState::default();
            let _ = tx.send(super::tools_status(&state).adb);
        });
        assert!(
            rx.recv_timeout(std::time::Duration::from_secs(5)).is_ok(),
            "tools_status deadlocked — it is locking the same mutex twice in one expression"
        );
    }

    #[test]
    fn volume_parse_is_anchored_to_the_last_is() {
        assert_eq!(super::parse_volume_get("volume is 7 (min: 0, max: 15)"), Some((7, 15)));
        // An earlier "is " in the line used to hijack the split and parse the
        // wrong token as the level.
        assert_eq!(
            super::parse_volume_get("Louis Phone: volume is 9 (min: 0, max: 15)"),
            Some((9, 15))
        );
        // No "is " at all must not silently parse the whole string.
        assert_eq!(super::parse_volume_get("no volume here"), None);
        assert_eq!(super::parse_volume_get(""), None);
    }

    #[test]
    fn ip_port_requires_adjacency() {
        assert_eq!(
            super::extract_ip_port("connected to 192.168.1.5:5555"),
            Some("192.168.1.5:5555".to_string())
        );
        // The port must immediately follow the IP. Scanning ahead for "the
        // next number anywhere" paired the IP with an unrelated figure.
        assert_eq!(super::extract_ip_port("192.168.1.5 device product:foo 1234"), None);
        assert_eq!(super::extract_ip_port("no address here"), None);
    }

    #[test]
    fn display_id_comes_from_the_scrcpy_server_line() {
        assert_eq!(parse_new_display_id("[server] INFO: New display: 540x1170/210 (id=26)"), Some(26));
        assert_eq!(parse_new_display_id("[server] INFO: Device: x"), None);
    }

    #[test]
    fn dumpsys_tasks_are_attributed_to_their_display() {
        let dump = "Display #26 (activities from top to bottom):\n  * Task{18ab #24727 type=standard A=10321:com.calc U=0 visible=true mode=fullscreen}\n    * Hist  #0: ActivityRecord{1 u0 com.calc/.Calculator t24727}\nDisplay #0 (activities from top to bottom):\n  * Task{aa #5 type=home U=0 visible=true}\n  * Task{bb #6 type=standard A=1:com.x U=0 visible=false}\n    * Hist  #0: ActivityRecord{2 u0 com.x/.Main t6}\n  * Task{18ab #24727 type=standard A=10321:com.calc U=0 visible=true}\n";
        let t = parse_tasks(dump);
        assert_eq!(t.len(), 2);
        assert_eq!(find_app_task(&t, "com.calc", 26).map(|t| (t.id, t.display)), Some((24727, 26)));
        assert!(find_app_task(&t, "com.x", 26).is_none()); // not visible
    }

    #[test]
    fn app_window_display_keeps_the_phones_shape_and_dp() {
        let (g, c) = app_window_geometry((1080, 2340), 480);
        let (w, h) = c.split_once('x').unwrap();
        let (w, h): (u32, u32) = (w.parse().unwrap(), h.parse().unwrap());
        assert!(w % 2 == 0 && h % 2 == 0 && w <= 1080 && h > w);
        assert!(g.ends_with(&format!("/{}", (480.0 * w as f64 / 1080.0).round() as u32)));
    }

    #[test]
    fn appops_mode_is_parsed() {
        assert_eq!(parse_appop_mode("PROJECT_MEDIA: allow; rejectTime=+54m ago"), "allow");
        assert_eq!(parse_appop_mode("PROJECT_MEDIA: default"), "default");
    }
}
