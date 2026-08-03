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
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};
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

async fn download_file(url: &str, dest: &Path) -> Result<(), String> {
    use futures_util::StreamExt;
    let resp = reqwest::get(url).await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("download failed (HTTP {})", resp.status()));
    }
    let mut file = tokio::fs::File::create(dest).await.map_err(|e| e.to_string())?;
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| e.to_string())?;
        file.write_all(&chunk).await.map_err(|e| e.to_string())?;
    }
    Ok(())
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
    if cfg!(windows) {
        run("tar", &["-xf", &zip_s, "-C", &dir_s], Duration::from_secs(60)).await?;
    } else {
        run("/usr/bin/unzip", &["-o", "-q", &zip_s, "-d", &dir_s], Duration::from_secs(60)).await?;
    }
    let _ = tokio::fs::remove_file(&zip).await;
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
    let fut = Command::new(bin).args(args).env("PATH", augmented_path()).output();
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

/// POSIX single-quote escaping for paths sent through `adb shell` (mirrors `shq`).
#[allow(dead_code)] // ported from adb.js for fidelity; not yet wired to a UI path (see module doc)
fn shq(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

pub async fn start_server(adb: &str) {
    let _ = run(adb, &["start-server"], DEFAULT_TIMEOUT).await;
}

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
}

fn tools_status(state: &AdbState) -> ToolsStatus {
    ToolsStatus {
        adb: state.adb.lock().unwrap().is_some(),
        scrcpy: state.scrcpy.lock().unwrap().is_some(),
        brew: state.brew.lock().unwrap().is_some(),
        adb_path: state.adb.lock().unwrap().clone(),
        scrcpy_path: state.scrcpy.lock().unwrap().clone(),
    }
}

fn emit_toast(app: &AppHandle, kind: &str, text: &str) {
    let _ = app.emit("wifi-event", serde_json::json!({ "kind": kind, "text": text }));
}

/// Spawn `adb track-devices -l` and parse its 4-hex-length-prefixed stream,
/// auto-respawning on exit (2s backoff) — mirrors `trackDevices`.
fn spawn_tracker(app: AppHandle, adb: String, stop: Arc<AtomicBool>) {
    tauri::async_runtime::spawn(async move {
        loop {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            let child = Command::new(&adb)
                .args(["track-devices", "-l"])
                .env("PATH", augmented_path())
                .stdout(std::process::Stdio::piped())
                .spawn();
            match child {
                Ok(mut child) => {
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
                }
                Err(_) => {}
            }
            if stop.load(Ordering::Relaxed) {
                return;
            }
            tokio::time::sleep(Duration::from_secs(2)).await;
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
pub async fn init(app: AppHandle, base_dir: PathBuf) {
    let adb = resolve_tool("adb").await;
    let scrcpy = resolve_tool("scrcpy").await;
    let brew = resolve_tool("brew").await;
    {
        let state = app.state::<AdbState>();
        *state.adb.lock().unwrap() = adb;
        *state.scrcpy.lock().unwrap() = scrcpy;
        *state.brew.lock().unwrap() = brew;
    }
    ensure_adb(app.clone(), base_dir).await;
    start_reconnect_scheduler(app);
}

// ── Background mDNS/tcp reconnect scheduler (mirrors scheduleMdns/runMdnsScan) ──

const MDNS_MIN: Duration = Duration::from_secs(10);
const MDNS_MAX: Duration = Duration::from_secs(30);

fn start_reconnect_scheduler(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let mut interval = MDNS_MIN;
        loop {
            tokio::time::sleep(interval).await;
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
                interval = MDNS_MIN;
                continue;
            }
            let adb = state.adb.lock().unwrap().clone();
            let Some(adb) = adb else { continue };
            let idle_elapsed = state.idle_since.lock().unwrap().elapsed();
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
            interval = if idle_elapsed > Duration::from_secs(300) { MDNS_MAX } else { MDNS_MIN };
        }
    });
}

// ── Wireless pairing / mDNS / reconnect (mirrors the corresponding adb.js fns) ──

/// Android 11+ Wireless Debugging pairing. Returns the device guid.
pub async fn pair_wireless(adb: &str, host_port: &str, code: &str) -> Result<String, String> {
    let out = run(adb, &["pair", host_port, code], Duration::from_secs(15)).await?;
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
            if let Some(port) = bytes[i + 1..].iter().find(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())) {
                return Some(format!("{tok}:{port}"));
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
pub fn camera(scrcpy: &str, serial: &str, adb: Option<&str>) -> Result<(), String> {
    std::process::Command::new(scrcpy)
        .args(["-s", serial, "--video-source=camera", "--camera-facing=back", "--no-audio", "--window-title", "DroidDock — Camera"])
        .envs(scrcpy_env(adb))
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Spawn scrcpy in mirror mode, detached — mirrors `mirror`.
pub fn mirror(scrcpy: &str, serial: &str, adb: Option<&str>) -> Result<(), String> {
    std::process::Command::new(scrcpy)
        .args(["-s", serial, "--window-title", "DroidDock — Mirror"])
        .envs(scrcpy_env(adb))
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
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
    let model = prop("ro.product.model").await;
    let android = prop("ro.build.version.release").await;
    let sdk = prop("ro.build.version.sdk").await;
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
    // "...is 7 (min: 0, max: 15)"
    let after_is = out.split("is ").nth(1)?;
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
    tauri::async_runtime::spawn(async move {
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

#[tauri::command]
pub fn adb_tools(state: tauri::State<AdbState>) -> ToolsStatus {
    tools_status(&state)
}

#[tauri::command]
pub async fn adb_scrcpy_install(app: AppHandle, state: tauri::State<'_, AdbState>) -> Result<(), String> {
    let brew = state.brew.lock().unwrap().clone();
    let Some(brew) = brew else {
        return Err("Homebrew not found — install it from brew.sh, then retry".into());
    };
    brew_install(&brew, "scrcpy").await?;
    let scrcpy = resolve_tool("scrcpy").await;
    let found = scrcpy.is_some();
    *state.scrcpy.lock().unwrap() = scrcpy;
    let _ = app.emit("tools", tools_status(&state));
    if found {
        Ok(())
    } else {
        Err("scrcpy installed but could not be located".into())
    }
}

#[tauri::command]
pub fn adb_devices(state: tauri::State<AdbState>) -> Vec<Device> {
    state.devices.lock().unwrap().clone()
}

#[tauri::command]
pub async fn adb_device_info(state: tauri::State<'_, AdbState>, serial: String) -> Result<DeviceInfo, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    device_info(&adb, &serial).await
}

#[tauri::command]
pub async fn adb_go_wireless(app: AppHandle, state: tauri::State<'_, AdbState>, serial: String) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let addr = go_wireless(&adb, &serial).await?;
    let app_state = app.state::<crate::AppState>();
    let mut cfg = app_state.config.lock().unwrap();
    cfg.tcp_addr = Some(addr.clone());
    config::save(&app, &cfg);
    Ok(addr)
}

use crate::config;

#[tauri::command]
pub async fn adb_pair_wireless(app: AppHandle, state: tauri::State<'_, AdbState>, host_port: String, code: String) -> Result<Value, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let guid = pair_wireless(&adb, &host_port, &code).await?;
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

#[tauri::command]
pub async fn adb_unpair(app: AppHandle, state: tauri::State<'_, AdbState>) -> Result<(), String> {
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

#[tauri::command]
pub fn adb_paired_info(app: AppHandle) -> Value {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    serde_json::json!({ "guid": cfg.device_guid })
}

#[tauri::command]
pub async fn adb_reconnect_now(app: AppHandle, state: tauri::State<'_, AdbState>) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let app_state = app.state::<crate::AppState>();
    let (guid, tcp_addr) = {
        let cfg = app_state.config.lock().unwrap();
        (cfg.device_guid.clone(), cfg.tcp_addr.clone())
    };
    *state.idle_since.lock().unwrap() = Instant::now();
    if let Some(guid) = guid {
        return connect_by_guid(&adb, &guid).await;
    }
    if let Some(addr) = tcp_addr {
        connect_tcp(&adb, &addr).await;
        return Ok(addr);
    }
    Err("No paired phone yet — pair ADB first".into())
}

#[tauri::command]
pub async fn adb_qr_pair_start(app: AppHandle, state: tauri::State<'_, AdbState>, service_name: String, password: String) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let token = state.qr_token.fetch_add(1, Ordering::SeqCst) + 1;
    let emit = |state_str: &str, text: &str, addr: Option<&str>| {
        let _ = app.emit("adb-qr-status", serde_json::json!({ "state": state_str, "text": text, "addr": addr }));
    };
    emit("waiting", "Waiting for scan…", None);
    let app2 = app.clone();
    tauri::async_runtime::spawn(async move {
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

#[tauri::command]
pub fn adb_qr_pair_cancel(state: tauri::State<AdbState>) {
    state.qr_token.fetch_add(1, Ordering::SeqCst);
}

#[tauri::command]
pub async fn adb_camera(state: tauri::State<'_, AdbState>, serial: String) -> Result<(), String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone();
    camera(&scrcpy, &serial, adb.as_deref())
}

#[tauri::command]
pub async fn adb_mirror(state: tauri::State<'_, AdbState>, serial: String) -> Result<(), String> {
    let scrcpy = state.scrcpy.lock().unwrap().clone().ok_or("scrcpy not found — brew install scrcpy")?;
    let adb = state.adb.lock().unwrap().clone();
    mirror(&scrcpy, &serial, adb.as_deref())
}

#[tauri::command]
pub async fn adb_screenshot(app: AppHandle, state: tauri::State<'_, AdbState>, serial: String) -> Result<String, String> {
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

#[tauri::command]
pub async fn adb_volume_get(state: tauri::State<'_, AdbState>) -> Result<Volume, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    Ok(get_volume(&adb, &serial).await)
}

#[tauri::command]
pub async fn adb_volume_set(state: tauri::State<'_, AdbState>, level: i64, current_level: i64) -> Result<String, String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("ADB not found")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    Ok(set_volume(&adb, &serial, level, current_level).await?.to_string())
}

fn first_device_serial(state: &AdbState) -> Option<String> {
    state.devices.lock().unwrap().iter().find(|d| d.state == "device").map(|d| d.serial.clone())
}

#[tauri::command]
pub async fn adb_call_end(state: tauri::State<'_, AdbState>) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_end(&adb, &serial).await
}

#[tauri::command]
pub async fn adb_call_speaker(state: tauri::State<'_, AdbState>) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_speaker(&adb, &serial).await
}

#[tauri::command]
pub async fn adb_call_mute(state: tauri::State<'_, AdbState>) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_mute(&adb, &serial).await
}

#[tauri::command]
pub async fn adb_call_dtmf(state: tauri::State<'_, AdbState>, digit: String) -> Result<(), String> {
    let adb = state.adb.lock().unwrap().clone().ok_or("No ADB device")?;
    let serial = first_device_serial(&state).ok_or("No ADB device")?;
    call_dtmf(&adb, &serial, &digit).await
}

#[tauri::command]
pub fn adb_call_start_polling(app: AppHandle, state: tauri::State<AdbState>, serial: Option<String>) {
    let serial = serial.or_else(|| first_device_serial(&state));
    if let (Some(serial), true) = (serial, state.adb.lock().unwrap().is_some()) {
        start_call_polling(app, serial);
    }
}
