//! Update check.
//!
//! DroidDock ships outside the App Store, so without this a user stays on
//! whichever version they first downloaded. In the native app the check is
//! deliberately small: GET the GitHub "latest release", compare its tag against
//! this build's version. Installing is a separate, explicit step: if the release
//! carries `DroidDock-<v>-mac.zip` plus its `.sig`, `update_install` downloads
//! it, checks an Ed25519 signature (the public key is baked in below), unpacks
//! it, validates the bundle, swaps it in for the running one and relaunches.
//! Anything missing or wrong falls back to the release page. (Sparkle was
//! rejected: +3-4 MB of framework and no benefit while the app is ad-hoc signed.)
//!
//! `spawn_startup_check` only ever *looks*, and only once a day.

use base64::{engine::general_purpose::STANDARD as B64, Engine};
use ed25519_dalek::{Signature, VerifyingKey};
use serde::Serialize;
use serde_json::Value;
use std::path::{Path, PathBuf};
use crate::host::{AppHandle, Emitter, Manager};

/// Ed25519 public key (base64) every update zip must be signed against. The
/// matching seed lives in ~/.droiddock-release/update-signing-key and in the
/// DD_UPDATE_SIGNING_KEY secret; see core/examples/sign_update.rs.
const UPDATE_PUBLIC_KEY: &str = "LExqspJc75FGYZrMHwX8bjDgt13Cw2KjGZbGd2vSZ4I=";

const BUNDLE_ID: &str = "com.droiddock.mac";

/// Hard cap on the downloaded zip.
const MAX_ZIP_BYTES: u64 = 200 * 1024 * 1024;
/// A detached signature is ~88 base64 chars; anything big is not one.
const MAX_SIG_BYTES: u64 = 4096;

/// Releases repo, from the previous updater endpoint
/// (`github.com/himanshudev28/MacDroid/releases/latest/download/latest.json`).
const RELEASES_API: &str = "https://api.github.com/repos/himanshudev28/MacDroid/releases/latest";

/// Don't hit GitHub more than once a day on launch.
const CHECK_INTERVAL_MS: i64 = 24 * 60 * 60 * 1000;

/// Let the phone link settle before spending bandwidth on something nobody
/// asked for yet.
const STARTUP_DELAY_SECS: u64 = 10;

/// The newest release found by `update_check`, parked so `update_install` can
/// hand back the page of the exact version the user was shown.
#[derive(Default)]
pub struct UpdaterState {
    pending: std::sync::Mutex<Option<Pending>>,
    /// An install is running; a second `update_install` is refused, not queued.
    installing: std::sync::atomic::AtomicBool,
}

/// Clears `installing` on every exit path, including `?` and panics.
struct InstallGuard<'a>(&'a std::sync::atomic::AtomicBool);
impl Drop for InstallGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, std::sync::atomic::Ordering::SeqCst);
    }
}

#[derive(Clone)]
struct Pending {
    html_url: String,
    version: String,
    /// `Some` only when the release has both the zip and its `.sig`.
    assets: Option<Assets>,
}

#[derive(Clone, Debug, PartialEq)]
struct Assets {
    zip_url: String,
    sig_url: String,
    /// Size GitHub reports for the zip; drives the progress percentage.
    zip_size: u64,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    /// The version on offer, e.g. `1.1.0` - no `v` prefix, that's the tag's.
    pub version: String,
    /// The release body, shown as-is. Empty rather than null when absent.
    pub notes: String,
    /// What's running now.
    pub current_version: String,
    /// The release page, for the "Open release page" fallback.
    pub html_url: String,
    /// True when the release carries a signed zip, so the app can install it
    /// itself (the bundle's location still has to be writable; that is only
    /// known at install time).
    pub install_available: bool,
}

/// What `update_install` did. `open` means "show this page" (today's
/// behaviour); `restart` means the new bundle is in place and the host should
/// quit now - a relaunch helper is already waiting for this process to exit.
#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct InstallOutcome {
    pub action: &'static str,
    pub url: Option<String>,
}

/// Parse `1.2.3` / `v1.2.3` (ignoring any `-suffix`) into comparable parts.
fn parse_version(v: &str) -> Vec<u64> {
    v.trim()
        .trim_start_matches(['v', 'V'])
        .split(['-', '+'])
        .next()
        .unwrap_or("")
        .split('.')
        .map(|p| p.parse::<u64>().unwrap_or(0))
        .collect()
}

/// True when `candidate` is strictly newer than `current`.
fn is_newer(candidate: &str, current: &str) -> bool {
    let (mut a, mut b) = (parse_version(candidate), parse_version(current));
    let n = a.len().max(b.len());
    a.resize(n, 0);
    b.resize(n, 0);
    a > b
}

/// Ask GitHub whether anything newer exists. `Ok(None)` means up to date.
pub async fn update_check(app: AppHandle) -> Result<Option<UpdateInfo>, String> {
    let current = app.package_info().version.clone();
    let feed = dev_feed();
    let feed_url = feed.as_deref().unwrap_or(RELEASES_API);
    // System curl instead of an in-process TLS stack: this runs once a day.
    let out = tokio::process::Command::new(CURL)
        .args(curl_flags(feed.is_some()))
        .args([
            "--connect-timeout", "10", "--max-time", "15",
            "--user-agent", &format!("DroidDock/{current}"),
            "--header", "Accept: application/vnd.github+json",
            feed_url,
        ])
        .kill_on_drop(true)
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
        return Err(format!("GitHub request failed: {}", if err.is_empty() { "curl error" } else { &err }));
    }
    let bytes = out.stdout;
    let body: Value = serde_json::from_slice(&bytes).map_err(|e| e.to_string())?;
    let tag = body.get("tag_name").and_then(Value::as_str).ok_or("The release has no tag")?;
    let version = tag.trim_start_matches(['v', 'V']).to_string();
    let state = app.state::<UpdaterState>();
    if !is_newer(&version, &current) {
        *state.pending.lock().unwrap() = None;
        return Ok(None);
    }
    let html_url = body
        .get("html_url")
        .and_then(Value::as_str)
        .unwrap_or("https://github.com/himanshudev28/MacDroid/releases/latest")
        .to_string();
    let notes = body.get("body").and_then(Value::as_str).unwrap_or_default().to_string();
    let assets = pick_assets(&body, &version, feed.is_some());
    *state.pending.lock().unwrap() = Some(Pending {
        html_url: html_url.clone(),
        version: version.clone(),
        assets: assets.clone(),
    });
    Ok(Some(UpdateInfo {
        version,
        notes,
        current_version: current,
        html_url,
        install_available: assets.is_some(),
    }))
}

const CURL: &str = if cfg!(target_os = "macos") { "/usr/bin/curl" } else { "curl" };

/// The hardened curl flags used for every request. `dev_http` (debug builds
/// with DD_UPDATE_FEED only) additionally allows plain http, for a loopback
/// server in tests.
fn curl_flags(dev_http: bool) -> Vec<&'static str> {
    let proto = if dev_http { "=https,http" } else { "=https" };
    vec![
        "-q", "--fail", "--silent", "--show-error", "--location",
        "--proto", proto, "--proto-redir", proto, "--tlsv1.2",
    ]
}

/// Dev/test only: a local release JSON (file path or http://127.0.0.1 URL).
/// Compiled to nothing in release builds, so a shipped app cannot be redirected.
fn dev_feed() -> Option<String> {
    if !cfg!(debug_assertions) {
        return None;
    }
    std::env::var("DD_UPDATE_FEED").ok().filter(|s| !s.is_empty())
}

/// Dev/test only: replace the baked-in public key (base64).
fn public_key_b64() -> String {
    if cfg!(debug_assertions) {
        if let Ok(k) = std::env::var("DD_UPDATE_PUBKEY") {
            if !k.is_empty() {
                return k;
            }
        }
    }
    UPDATE_PUBLIC_KEY.to_string()
}

/// Find `DroidDock-<version>-mac.zip` and `.sig` among the release assets.
/// Both must exist and be https (loopback http only in dev mode).
fn pick_assets(release: &Value, version: &str, dev: bool) -> Option<Assets> {
    let list = release.get("assets")?.as_array()?;
    let zip_name = format!("DroidDock-{version}-mac.zip");
    let sig_name = format!("{zip_name}.sig");
    let find = |name: &str| -> Option<(String, u64)> {
        let a = list.iter().find(|a| a.get("name").and_then(Value::as_str) == Some(name))?;
        let url = a.get("browser_download_url")?.as_str()?.to_string();
        if !url_allowed(&url, dev) {
            return None;
        }
        Some((url, a.get("size").and_then(Value::as_u64).unwrap_or(0)))
    };
    let (zip_url, zip_size) = find(&zip_name)?;
    let (sig_url, _) = find(&sig_name)?;
    Some(Assets { zip_url, sig_url, zip_size })
}

fn url_allowed(url: &str, dev: bool) -> bool {
    // Exactly the loopback host (port or path may follow); never `127.0.0.1.evil.test`.
    url.starts_with("https://")
        || (dev && (url.starts_with("http://127.0.0.1:") || url.starts_with("http://127.0.0.1/")))
}

/// Install the pending update in place, or hand back the release page.
///
/// The page is returned (as `InstallOutcome::open`) when the release has no
/// signed zip or the running bundle can't be replaced (not a .app, or its
/// folder isn't writable). A failure after that point is an `Err` the UI shows,
/// next to an "Open release page" button; the running app is never left broken,
/// because the swap rolls back.
pub async fn update_install(app: AppHandle) -> Result<InstallOutcome, String> {
    let pending = app
        .state::<UpdaterState>()
        .pending
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "No update is pending - check for updates first.".to_string())?;
    let state = app.state::<UpdaterState>();
    if state.installing.swap(true, std::sync::atomic::Ordering::SeqCst) {
        return Err("An update is already being installed.".to_string());
    }
    let _guard = InstallGuard(&state.installing);
    let open = |p: &Pending| InstallOutcome { action: "open", url: Some(p.html_url.clone()) };
    let Some(assets) = pending.assets.clone() else { return Ok(open(&pending)) };
    let Some(target) = bundle_path().filter(|t| bundle_writable(t)) else { return Ok(open(&pending)) };

    let work_root = app
        .path()
        .home_dir()
        .map_err(|e| e.to_string())?
        .join("Library/Caches/DroidDock/update");
    let emit = |phase: &str, percent: u8, downloaded: u64, total: u64| {
        let _ = app.emit(
            "update-progress",
            serde_json::json!({ "phase": phase, "percent": percent, "downloaded": downloaded, "total": total }),
        );
    };
    let dev = dev_feed().is_some();
    install_package(&assets, &pending.version, &target, &work_root, dev, &emit).await?;

    // The swap is done; a relaunch hiccup must not read as a failed update.
    emit("restarting", 100, 0, 0);
    let action = match spawn_relaunch(&target, std::process::id()) {
        Ok(()) => "restart",
        Err(e) => {
            eprintln!("updater: installed, but the relaunch helper didn't start ({e})");
            "restart-manually"
        }
    };
    Ok(InstallOutcome { action, url: None })
}

/// Everything up to and including the swap; no relaunch. Split out so tests
/// can run it against a fake bundle in a temp dir.
async fn install_package(
    assets: &Assets,
    version: &str,
    target: &Path,
    work_root: &Path,
    dev: bool,
    emit: &(dyn Fn(&str, u8, u64, u64) + Sync),
) -> Result<(), String> {
    let _ = std::fs::remove_dir_all(work_root);
    let work = work_root.join(format!("{}-{version}", std::process::id()));
    std::fs::create_dir_all(&work).map_err(|e| format!("Couldn't create a work folder: {e}"))?;
    let result = install_in(assets, version, target, &work, dev, emit).await;
    let _ = std::fs::remove_dir_all(work_root);
    result
}

async fn install_in(
    assets: &Assets,
    version: &str,
    target: &Path,
    work: &Path,
    dev: bool,
    emit: &(dyn Fn(&str, u8, u64, u64) + Sync),
) -> Result<(), String> {
    let zip = work.join("update.zip");
    let sig = work.join("update.zip.sig");
    emit("downloading", 0, 0, assets.zip_size);
    download(&assets.sig_url, &sig, MAX_SIG_BYTES, 0, dev, &|_, _| {}).await?;
    download(&assets.zip_url, &zip, MAX_ZIP_BYTES, assets.zip_size, dev, &|d, t| {
        let pct = if t > 0 { ((d * 100) / t).min(99) as u8 } else { 0 };
        emit("downloading", pct, d, t);
    })
    .await?;

    emit("verifying", 100, 0, 0);
    let zip_bytes = std::fs::read(&zip).map_err(|e| e.to_string())?;
    let sig_text = std::fs::read_to_string(&sig).map_err(|e| e.to_string())?;
    verify_signature(&zip_bytes, &sig_text, &public_key_b64())?;

    // Extract the bytes that were verified, not the downloaded path: write them
    // to a fresh 0600 file in a fresh 0700 dir so nothing can swap the file
    // between the check and the unpack.
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
    let private = work.join("verified");
    std::fs::DirBuilder::new().mode(0o700).create(&private).map_err(|e| e.to_string())?;
    let checked = private.join("update.zip");
    {
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new()
            .write(true).create_new(true).mode(0o600).open(&checked)
            .map_err(|e| e.to_string())?;
        f.write_all(&zip_bytes).map_err(|e| e.to_string())?;
    }
    drop(zip_bytes);
    let _ = std::fs::remove_file(&zip);

    let out = private.join("x");
    std::fs::create_dir(&out).map_err(|e| e.to_string())?;
    run(DITTO, &["-x", "-k", path_str(&checked)?, path_str(&out)?]).await.map_err(|e| format!("Couldn't unpack the update: {e}"))?;
    let new_app = out.join("DroidDock.app");
    validate_bundle(&new_app, version).await?;
    check_signer(&new_app, installed_requirement(target).await.as_deref()).await?;

    emit("installing", 100, 0, 0);
    swap_bundle(&new_app, target)
}

const DITTO: &str = "/usr/bin/ditto";

fn path_str(p: &Path) -> Result<&str, String> {
    p.to_str().ok_or_else(|| "Path is not valid UTF-8".to_string())
}

/// Run a tool, Ok(stdout) on exit 0, Err(stderr) otherwise.
async fn run(bin: &str, args: &[&str]) -> Result<String, String> {
    let out = tokio::process::Command::new(bin)
        .args(args)
        .kill_on_drop(true)
        .output()
        .await
        .map_err(|e| format!("{bin}: {e}"))?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        let e = String::from_utf8_lossy(&out.stderr).trim().to_string();
        Err(if e.is_empty() { format!("{bin} failed") } else { e })
    }
}

/// curl to a file, size-capped, reporting (downloaded, total) as it grows.
async fn download(
    url: &str,
    dest: &Path,
    cap: u64,
    expected: u64,
    dev: bool,
    progress: &(dyn Fn(u64, u64) + Sync),
) -> Result<(), String> {
    if !url_allowed(url, dev) {
        return Err("The update isn't served over https".into());
    }
    let mut child = tokio::process::Command::new(CURL)
        .args(curl_flags(dev))
        .args([
            "--connect-timeout", "15", "--max-time", "900",
            "--max-filesize", &cap.to_string(),
            "--user-agent", "DroidDock-updater",
            "-o", path_str(dest)?, url,
        ])
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| format!("Couldn't start the download: {e}"))?;
    let status = loop {
        match tokio::time::timeout(std::time::Duration::from_millis(250), child.wait()).await {
            Ok(r) => break r.map_err(|e| e.to_string())?,
            Err(_) => {
                let got = std::fs::metadata(dest).map(|m| m.len()).unwrap_or(0);
                if got > cap {
                    let _ = child.kill().await;
                    return Err("The update is larger than expected".into());
                }
                progress(got, expected);
            }
        }
    };
    if !status.success() {
        let mut err = String::new();
        if let Some(mut se) = child.stderr.take() {
            let _ = tokio::io::AsyncReadExt::read_to_string(&mut se, &mut err).await;
        }
        let err = err.trim();
        return Err(format!("Download failed: {}", if err.is_empty() { "curl error" } else { err }));
    }
    let got = std::fs::metadata(dest).map_err(|e| e.to_string())?.len();
    if got == 0 || got > cap {
        return Err("The downloaded update has an unexpected size".into());
    }
    progress(got, got);
    Ok(())
}

/// Ed25519 over the exact zip bytes. `sig_b64` is the `.sig` file's text.
fn verify_signature(data: &[u8], sig_b64: &str, pubkey_b64: &str) -> Result<(), String> {
    let bad = || "The update's signature isn't valid - not installing it".to_string();
    let key: [u8; 32] = B64
        .decode(pubkey_b64.trim())
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or_else(|| "The built-in update key is malformed".to_string())?;
    let key = VerifyingKey::from_bytes(&key).map_err(|_| bad())?;
    let sig: [u8; 64] = B64
        .decode(sig_b64.trim())
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or_else(bad)?;
    key.verify_strict(data, &Signature::from_bytes(&sig)).map_err(|_| bad())
}

async fn plist_string(app: &Path, key: &str) -> Result<String, String> {
    let plist = app.join("Contents/Info.plist");
    run("/usr/bin/plutil", &["-extract", key, "raw", "-o", "-", path_str(&plist)?])
        .await
        .map_err(|_| format!("The update has no {key}"))
}

/// The unpacked bundle must be DroidDock, at the offered version, with an
/// intact code signature (ad-hoc is fine; tampering or truncation is not).
async fn validate_bundle(app: &Path, version: &str) -> Result<(), String> {
    if !app.is_dir() {
        return Err("The update doesn't contain DroidDock.app".into());
    }
    let id = plist_string(app, "CFBundleIdentifier").await?;
    if id != BUNDLE_ID {
        return Err(format!("The update is for a different app ({id})"));
    }
    let v = plist_string(app, "CFBundleShortVersionString").await?;
    if v != version {
        return Err(format!("The update is version {v}, not the {version} that was offered"));
    }
    run("/usr/bin/codesign", &["--verify", "--strict", path_str(app)?])
        .await
        .map_err(|e| format!("The update's code signature is broken: {e}"))?;
    // Only clear quarantine when it is there; curl doesn't set it, a browser might.
    if run("/usr/bin/xattr", &["-p", "com.apple.quarantine", path_str(app)?]).await.is_ok() {
        let _ = run("/usr/bin/xattr", &["-dr", "com.apple.quarantine", path_str(app)?]).await;
    }
    Ok(())
}

/// Shown when an update is signed differently from the installed app. The UI
/// puts an "Open release page" button next to it, so the user is never stuck.
const SIGNER_MISMATCH: &str = "This update isn't signed with the same certificate as the installed DroidDock, so it wasn't installed. Download it from the release page instead.";

/// The installed bundle's designated requirement, as `codesign -d -r-` prints
/// it (commented out, `# designated => cdhash …`, for an implicit ad-hoc one).
/// `None` when it can't be read; then nothing extra is enforced.
async fn installed_requirement(app: &Path) -> Option<String> {
    let out = run("/usr/bin/codesign", &["-d", "-r-", path_str(app).ok()?]).await.ok()?;
    out.lines()
        .find_map(|l| l.trim_start_matches('#').trim_start().strip_prefix("designated => "))
        .map(|s| s.trim().to_string())
}

/// A certificate requirement pins DroidDock's own signer. An ad-hoc one is only
/// the `cdhash` of that exact build and says nothing about the next one.
fn pins_a_certificate(req: &str) -> bool {
    req.contains("certificate") && !req.trim_start().starts_with("cdhash")
}

/// Once the installed app is certificate-signed, an update must satisfy the same
/// requirement: macOS keys the Accessibility grant to it, and a different signer
/// means a build that didn't come out of DroidDock's release pipeline. An ad-hoc
/// install accepts any intact update, which is how the first certificate-signed
/// release gets in.
async fn check_signer(new_app: &Path, installed_req: Option<&str>) -> Result<(), String> {
    let Some(req) = installed_req.filter(|r| pins_a_certificate(r)) else { return Ok(()) };
    run("/usr/bin/codesign", &["--verify", &format!("-R={req}"), path_str(new_app)?])
        .await
        .map(|_| ())
        .map_err(|_| SIGNER_MISMATCH.to_string())
}

/// `<target>.update-backup`, next to the bundle (same volume, so it's a rename).
fn backup_path(target: &Path) -> PathBuf {
    let mut n = target.file_name().unwrap_or_default().to_os_string();
    n.push(".update-backup");
    target.with_file_name(n)
}

fn staging_path(target: &Path) -> PathBuf {
    let mut n = std::ffi::OsString::from(".");
    n.push(target.file_name().unwrap_or_default());
    n.push(".update-new");
    target.with_file_name(n)
}

/// Replace `target` with `new_app`, keeping the old one as `backup_path`.
/// Any failure puts everything back as it was.
fn swap_bundle(new_app: &Path, target: &Path) -> Result<(), String> {
    swap_with(new_app, target, &|a, b| std::fs::rename(a, b))
}

/// `swap_bundle` with the rename injectable, so tests can fail the second move.
fn swap_with(
    new_app: &Path,
    target: &Path,
    rename: &dyn Fn(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), String> {
    let backup = backup_path(target);
    let staging = staging_path(target);
    let _ = std::fs::remove_dir_all(&backup);
    let _ = std::fs::remove_dir_all(&staging);
    // Same-volume rename when possible; otherwise a copy (ditto keeps everything).
    if rename(new_app, &staging).is_err() {
        let st = std::process::Command::new(DITTO)
            .args([new_app.as_os_str(), staging.as_os_str()])
            .status()
            .map_err(|e| e.to_string())?;
        if !st.success() {
            let _ = std::fs::remove_dir_all(&staging);
            return Err("Couldn't copy the update next to the app".into());
        }
    }
    if let Err(e) = rename(target, &backup) {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Couldn't move the current app aside: {e}"));
    }
    if let Err(e) = rename(&staging, target) {
        let rolled = std::fs::rename(&backup, target).is_ok();
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!(
            "Couldn't put the new app in place: {e}{}",
            if rolled { "" } else { " (and the old app could not be restored - it is at the .update-backup path)" }
        ));
    }
    Ok(())
}

/// The running .app, if this process is inside one. `DD_UPDATE_TARGET` (debug
/// builds) points the swap at a fake bundle.
fn bundle_path() -> Option<PathBuf> {
    if cfg!(debug_assertions) {
        if let Some(p) = std::env::var_os("DD_UPDATE_TARGET") {
            return Some(PathBuf::from(p));
        }
    }
    let exe = std::env::current_exe().ok()?.canonicalize().ok()?;
    exe.ancestors()
        .find(|a| a.extension().is_some_and(|e| e == "app") && a.join("Contents/Info.plist").exists())
        .map(Path::to_path_buf)
}

/// Can we rename the bundle and drop a sibling next to it?
fn bundle_writable(target: &Path) -> bool {
    let Some(parent) = target.parent() else { return false };
    let w = |p: &Path| {
        std::ffi::CString::new(p.as_os_str().as_encoded_bytes())
            .map(|c| unsafe { libc::access(c.as_ptr(), libc::W_OK) == 0 })
            .unwrap_or(false)
    };
    target.is_dir() && w(parent) && w(target)
}

/// A detached shell that waits for this process to exit, then opens the
/// bundle. In its own process group so quitting the app doesn't take it along.
fn spawn_relaunch(target: &Path, pid: u32) -> Result<(), String> {
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};
    if cfg!(debug_assertions) && std::env::var_os("DD_UPDATE_NO_RELAUNCH").is_some() {
        return Ok(());
    }
    // Give up waiting after ~2 minutes rather than leaking a shell forever.
    let script = r#"i=0; while kill -0 "$1" 2>/dev/null && [ $i -lt 600 ]; do sleep 0.2; i=$((i+1)); done; /usr/bin/open "$2""#;
    Command::new("/bin/sh")
        .args(["-c", script, "sh", &pid.to_string(), path_str(target)?])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .process_group(0)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("Couldn't schedule the relaunch: {e}"))
}

/// After an update the old bundle is kept as `.update-backup` until the new
/// version has actually launched. Once we're running (a few seconds in) and the
/// backup is a different version, it has done its job.
fn remove_stale_backup(running_version: &str) {
    let Some(target) = bundle_path() else { return };
    let backup = backup_path(&target);
    if !backup.exists() {
        return;
    }
    let old = std::process::Command::new("/usr/bin/plutil")
        .args(["-extract", "CFBundleShortVersionString", "raw", "-o", "-"])
        .arg(backup.join("Contents/Info.plist"))
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string());
    if old.as_deref() != Some(running_version) {
        let _ = std::fs::remove_dir_all(&backup);
    }
    let _ = std::fs::remove_dir_all(staging_path(&target));
}

/// Background look-see, ~10s after launch. Silent on a miss; on a hit it emits
/// `update-available`. The throttle timestamp is written whether or not an
/// update was found - the point is to rate-limit the *request*.
pub fn spawn_startup_check(app: AppHandle) {
    if crate::host::is_dev() {
        return;
    }
    crate::host::async_runtime::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(STARTUP_DELAY_SECS)).await;

        // Survived the first seconds as the new version: the update backup can go.
        remove_stale_backup(&app.package_info().version);

        {
            let state = app.state::<crate::AppState>();
            let cfg = state.config.lock().unwrap();
            if !cfg.auto_check_updates
                || crate::config::now_ms() - cfg.last_update_check < CHECK_INTERVAL_MS
            {
                return;
            }
        }

        let found = match update_check(app.clone()).await {
            Ok(found) => found,
            Err(e) => {
                eprintln!("updater: background check failed ({e})");
                return;
            }
        };

        {
            let state = app.state::<crate::AppState>();
            let mut cfg = state.config.lock().unwrap();
            cfg.last_update_check = crate::config::now_ms();
            crate::config::save(&app, &cfg);
        }

        if let Some(info) = found {
            let _ = app.emit("update-available", info);
        }
    });
}

/// The version this binary was built as.
pub fn app_version(app: AppHandle) -> String {
    app.package_info().version.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    #[test]
    fn compares_versions_numerically() {
        assert!(is_newer("2.3.1", "2.3.0"));
        assert!(is_newer("v2.10.0", "2.9.9"));
        assert!(is_newer("3", "2.9.9"));
        assert!(!is_newer("2.3.0", "2.3.0"));
        assert!(!is_newer("2.2.9", "2.3.0"));
    }

    fn test_key() -> (SigningKey, String) {
        let k = SigningKey::from_bytes(&[7u8; 32]);
        let pk = B64.encode(k.verifying_key().to_bytes());
        (k, pk)
    }

    #[test]
    fn baked_in_public_key_is_a_valid_point() {
        let k: [u8; 32] = B64.decode(UPDATE_PUBLIC_KEY).unwrap().try_into().unwrap();
        VerifyingKey::from_bytes(&k).unwrap();
    }

    #[test]
    fn signature_good_bad_and_missing() {
        let (k, pk) = test_key();
        let data = b"zip bytes";
        let sig = B64.encode(k.sign(data).to_bytes());
        assert!(verify_signature(data, &sig, &pk).is_ok());
        assert!(verify_signature(data, &format!("{sig}\n"), &pk).is_ok(), "trailing newline is fine");
        // tampered payload
        assert!(verify_signature(b"zip bytez", &sig, &pk).is_err());
        // signed by someone else
        let other = SigningKey::from_bytes(&[9u8; 32]);
        assert!(verify_signature(data, &B64.encode(other.sign(data).to_bytes()), &pk).is_err());
        // missing / empty / garbage / short signature
        assert!(verify_signature(data, "", &pk).is_err());
        assert!(verify_signature(data, "not base64!!", &pk).is_err());
        assert!(verify_signature(data, &B64.encode([1u8; 10]), &pk).is_err());
        // malformed key
        assert!(verify_signature(data, &sig, "AAAA").is_err());
    }

    fn release(zip: bool, sig: bool, url: &str) -> Value {
        let mut a = vec![];
        if zip { a.push(serde_json::json!({"name":"DroidDock-3.0.1-mac.zip","browser_download_url":format!("{url}/z"),"size":123})); }
        if sig { a.push(serde_json::json!({"name":"DroidDock-3.0.1-mac.zip.sig","browser_download_url":format!("{url}/s"),"size":88})); }
        a.push(serde_json::json!({"name":"DroidDock-3.0.1.dmg","browser_download_url":format!("{url}/d")}));
        serde_json::json!({ "assets": a })
    }

    #[test]
    fn selects_assets() {
        let a = pick_assets(&release(true, true, "https://x.test"), "3.0.1", false).unwrap();
        assert_eq!(a.zip_url, "https://x.test/z");
        assert_eq!(a.sig_url, "https://x.test/s");
        assert_eq!(a.zip_size, 123);
        assert!(pick_assets(&release(true, false, "https://x.test"), "3.0.1", false).is_none(), "no sig");
        assert!(pick_assets(&release(false, true, "https://x.test"), "3.0.1", false).is_none(), "no zip");
        assert!(pick_assets(&release(true, true, "https://x.test"), "3.0.2", false).is_none(), "other version");
        assert!(pick_assets(&serde_json::json!({}), "3.0.1", false).is_none(), "no assets array");
        assert!(pick_assets(&release(true, true, "http://x.test"), "3.0.1", false).is_none(), "http refused");
        assert!(pick_assets(&release(true, true, "http://127.0.0.1:9"), "3.0.1", false).is_none(), "loopback http refused outside dev");
        assert!(pick_assets(&release(true, true, "http://127.0.0.1:9"), "3.0.1", true).is_some(), "loopback http ok in dev");
        assert!(!url_allowed("http://127.0.0.1.evil.test/z", true), "lookalike host");
        assert!(!url_allowed("http://127.0.0.10/z", true), "lookalike host");
        assert!(url_allowed("http://127.0.0.1/z", true));
    }

    /// A minimal, ad-hoc signed bundle.
    fn fake_bundle(dir: &Path, id: &str, version: &str, marker: &str) -> PathBuf {
        let app = dir.join("DroidDock.app");
        std::fs::create_dir_all(app.join("Contents/MacOS")).unwrap();
        std::fs::write(
            app.join("Contents/Info.plist"),
            format!(
                r#"<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>{id}</string><key>CFBundleShortVersionString</key><string>{version}</string><key>CFBundleExecutable</key><string>app</string></dict></plist>"#
            ),
        ).unwrap();
        std::fs::write(app.join("Contents/MacOS/app"), format!("#!/bin/sh\n# {marker}\n")).unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(app.join("Contents/MacOS/app"), std::fs::Permissions::from_mode(0o755)).unwrap();
        let st = std::process::Command::new("/usr/bin/codesign").args(["-s", "-", "--force"]).arg(&app)
            .output().unwrap();
        assert!(st.status.success(), "{}", String::from_utf8_lossy(&st.stderr));
        app
    }

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("dd-upd-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn rt() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap()
    }

    #[test]
    fn validates_bundle_identity_and_version() {
        let d = tmp("validate");
        let ok = fake_bundle(&d.join("a"), BUNDLE_ID, "3.0.1", "x");
        rt().block_on(async {
            assert!(validate_bundle(&ok, "3.0.1").await.is_ok());
            assert!(validate_bundle(&ok, "3.0.2").await.unwrap_err().contains("not the 3.0.2"));
            assert!(validate_bundle(&d.join("nope.app"), "3.0.1").await.is_err());
        });
        let wrong = fake_bundle(&d.join("b"), "com.evil.app", "3.0.1", "x");
        rt().block_on(async {
            assert!(validate_bundle(&wrong, "3.0.1").await.unwrap_err().contains("different app"));
        });
        // tampered after signing -> codesign --verify --strict fails
        std::fs::write(ok.join("Contents/MacOS/app"), "#!/bin/sh\n# changed\n").unwrap();
        rt().block_on(async {
            assert!(validate_bundle(&ok, "3.0.1").await.unwrap_err().contains("signature"));
        });
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn certificate_signed_install_refuses_a_different_signer() {
        assert!(!pins_a_certificate(r#"cdhash H"3e24f2184b0598efac18239e71b0b5f26a8ad0e9""#));
        assert!(pins_a_certificate(r#"identifier "com.droiddock.mac" and certificate root = H"ec684d33""#));
        let d = tmp("signer");
        let new_app = fake_bundle(&d.join("n"), BUNDLE_ID, "3.0.1", "x");
        let pinned = format!(r#"identifier "{BUNDLE_ID}" and certificate root = H"{}""#, "0".repeat(40));
        rt().block_on(async {
            // An ad-hoc (or unreadable) install takes any intact update.
            assert!(check_signer(&new_app, None).await.is_ok());
            let own = installed_requirement(&new_app).await.unwrap();
            assert!(own.starts_with("cdhash"), "{own}");
            assert!(check_signer(&new_app, Some(&own)).await.is_ok());
            // A certificate-pinned install refuses an ad-hoc update and points at the release page.
            let e = check_signer(&new_app, Some(&pinned)).await.unwrap_err();
            assert!(e.contains("release page"), "{e}");
        });
        let _ = std::fs::remove_dir_all(&d);
    }

    fn marker(app: &Path) -> String {
        std::fs::read_to_string(app.join("Contents/MacOS/app")).unwrap()
    }

    #[test]
    fn swap_replaces_and_keeps_backup() {
        let d = tmp("swap");
        let target = fake_bundle(&d.join("apps"), BUNDLE_ID, "3.0.0", "old");
        let new = fake_bundle(&d.join("new"), BUNDLE_ID, "3.0.1", "new");
        swap_bundle(&new, &target).unwrap();
        assert!(marker(&target).contains("new"));
        assert!(marker(&backup_path(&target)).contains("old"));
        assert!(!staging_path(&target).exists());
        // A second update replaces the stale backup.
        let newer = fake_bundle(&d.join("newer"), BUNDLE_ID, "3.0.2", "newer");
        swap_bundle(&newer, &target).unwrap();
        assert!(marker(&target).contains("newer"));
        assert!(marker(&backup_path(&target)).contains("new"));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn swap_rolls_back_when_the_second_move_fails() {
        let d = tmp("rollback");
        let target = fake_bundle(&d.join("apps"), BUNDLE_ID, "3.0.0", "old");
        let new = fake_bundle(&d.join("new"), BUNDLE_ID, "3.0.1", "new");
        let t2 = target.clone();
        // Fail only the staging -> target move.
        let err = swap_with(&new, &target, &move |a, b| {
            if b == t2 { Err(std::io::Error::other("boom")) } else { std::fs::rename(a, b) }
        })
        .unwrap_err();
        assert!(err.contains("boom"));
        assert!(marker(&target).contains("old"), "original is back");
        assert!(!backup_path(&target).exists());
        assert!(!staging_path(&target).exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn swap_leaves_the_app_alone_when_it_cannot_start() {
        let d = tmp("nostart");
        let target = fake_bundle(&d.join("apps"), BUNDLE_ID, "3.0.0", "old");
        let new = fake_bundle(&d.join("new"), BUNDLE_ID, "3.0.1", "new");
        let t2 = target.clone();
        let err = swap_with(&new, &target, &move |a, b| {
            if a == t2 { Err(std::io::Error::other("busy")) } else { std::fs::rename(a, b) }
        })
        .unwrap_err();
        assert!(err.contains("aside"));
        assert!(marker(&target).contains("old"));
        assert!(!staging_path(&target).exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn writable_check() {
        let d = tmp("writable");
        let app = fake_bundle(&d, BUNDLE_ID, "3.0.0", "x");
        assert!(bundle_writable(&app));
        assert!(!bundle_writable(&d.join("missing.app")));
        let _ = std::fs::remove_dir_all(&d);
    }

    /// End to end against a local server; see the e2e notes in the report.
    ///   DD_E2E_BASE=http://127.0.0.1:PORT DD_E2E_TARGET=<copy of a bundle> DD_E2E_VERSION=x.y.z
    ///   DD_UPDATE_PUBKEY=<test pubkey> cargo test e2e_install -- --ignored --nocapture
    /// serving DroidDock-<v>-mac.zip and .sig at BASE. Never relaunches.
    #[test]
    #[ignore]
    fn e2e_install() {
        let base = std::env::var("DD_E2E_BASE").expect("DD_E2E_BASE");
        let target = PathBuf::from(std::env::var("DD_E2E_TARGET").expect("DD_E2E_TARGET"));
        let version = std::env::var("DD_E2E_VERSION").expect("DD_E2E_VERSION");
        let work = tmp("e2e-work");
        let assets = Assets {
            zip_url: format!("{base}/DroidDock-{version}-mac.zip"),
            sig_url: format!("{base}/DroidDock-{version}-mac.zip.sig"),
            zip_size: std::env::var("DD_E2E_SIZE").ok().and_then(|s| s.parse().ok()).unwrap_or(0),
        };
        let phases = std::sync::Mutex::new(Vec::<String>::new());
        let r = rt().block_on(install_package(&assets, &version, &target, &work, true, &|ph, pct, d, t| {
            println!("progress {ph} {pct}% {d}/{t}");
            let mut p = phases.lock().unwrap();
            if p.last().map(String::as_str) != Some(ph) { p.push(ph.to_string()); }
        }));
        if std::env::var("DD_E2E_EXPECT_FAIL").is_ok() {
            let e = r.unwrap_err();
            println!("expected failure: {e}");
            return;
        }
        r.unwrap();
        println!("phases: {:?}", phases.lock().unwrap());
        assert!(backup_path(&target).exists());
        let _ = std::fs::remove_dir_all(&work);
    }
}
