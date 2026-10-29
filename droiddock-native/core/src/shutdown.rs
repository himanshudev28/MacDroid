//! Quitting DroidDock stops everything DroidDock started.
//!
//! Several things the app starts are not part of its own process, so they do
//! not end with it: scrcpy windows (separate programs), the adb server (a
//! daemon by design), and the Finder volume (a kernel mount pointing at our
//! WebDAV server). Before this module they were all left running after Quit.
//! Network announcements live in-process but have to be withdrawn, or phones
//! keep listing the Mac until the records expire.
//!
//! Everything here is blocking on purpose: the process is about to end, and
//! work handed to the async runtime at this point may never run.

use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use crate::host::AppHandle;

static DONE: AtomicBool = AtomicBool::new(false);

/// Stop everything DroidDock started. Safe to call more than once — Tauri
/// delivers both `ExitRequested` and `Exit`, and the updater calls it before a
/// restart — only the first call does anything.
pub fn on_quit(app: &AppHandle) {
    if DONE.swap(true, Ordering::SeqCst) {
        return;
    }
    let began = Instant::now();
    eprintln!("[shutdown] stopping everything DroidDock started");
    // First: never leave the Mac with a mouse button held down by a phone drag.
    crate::mac_remote::release_all();
    // The order matters. The Finder volume has to be unmounted while its
    // server still answers: unmounting from a server that is already gone
    // leaves Finder stuck on the volume until a reboot.
    // Its own sub-budget: a stuck unmount (up to 10 s of umount + umount -f) must not eat the time the
    // scrcpy/adb cleanup below needs, so it runs on a thread we only wait ~3 s for.
    {
        let app = app.clone();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            crate::webdav::shutdown_blocking(&app);
            let _ = tx.send(());
        });
        let _ = rx.recv_timeout(Duration::from_secs(3));
    }
    // Withdraw the announcements, so phones drop the Mac now.
    crate::quickshare::server::stop(app);
    crate::mdns::stop_blocking();
    // adb goes last. The in-app mirror's port forward and the scrcpy windows
    // both use the adb server to clean up on the phone, so the server has to
    // stay up until they're done.
    crate::scrcpy_client::cleanup_blocking(app);
    crate::adb::shutdown_blocking(app);
    eprintln!("[shutdown] done in {} ms", began.elapsed().as_millis());
}

/// Whether quit cleanup has started. Something launched after that point
/// (a mirror window that was mid-launch when Quit was chosen) has to stop
/// itself, because the cleanup has already passed it by.
pub(crate) fn quitting() -> bool {
    DONE.load(Ordering::SeqCst)
}

/// Clean up after a previous run that ended without `on_quit`: Force Quit, a
/// crash, or a power cut. Runs in the background, so launch isn't delayed.
pub fn on_launch() {
    crate::webdav::unmount_stale();
}

// ── adb forward ledger ───────────────────────────────────────────────────
//
// An `adb forward` lives inside the adb server, which survives a crash or
// Force Quit of DroidDock. Sweeping `adb forward --list` by pattern could hit
// another tool's forward (scrcpy uses the same socket names), so instead we
// record exactly the forwards *we* created, in our own data dir, and remove
// only those on the next launch.

fn ledger_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    use crate::host::Manager;
    app.path().app_data_dir().ok().map(|d| d.join("adb-forwards.txt"))
}

fn ledger_read(app: &AppHandle) -> Vec<(String, u16)> {
    let Some(p) = ledger_path(app) else { return vec![] };
    let Ok(text) = std::fs::read_to_string(p) else { return vec![] };
    text.lines()
        .filter_map(|l| {
            let (serial, port) = l.rsplit_once('\t')?;
            Some((serial.to_string(), port.parse().ok()?))
        })
        .collect()
}

fn ledger_write(app: &AppHandle, entries: &[(String, u16)]) {
    let Some(p) = ledger_path(app) else { return };
    if entries.is_empty() {
        let _ = std::fs::remove_file(p);
        return;
    }
    let text: String = entries.iter().map(|(s, port)| format!("{s}\t{port}\n")).collect();
    let _ = std::fs::write(p, text);
}

static LEDGER_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Record a forward we just created.
pub(crate) fn forward_opened(app: &AppHandle, serial: &str, port: u16) {
    let _g = LEDGER_LOCK.lock().unwrap();
    let mut e = ledger_read(app);
    e.push((serial.to_string(), port));
    ledger_write(app, &e);
}

/// Forget a forward that has been removed.
pub(crate) fn forward_closed(app: &AppHandle, serial: &str, port: u16) {
    let _g = LEDGER_LOCK.lock().unwrap();
    let mut e = ledger_read(app);
    e.retain(|(s, p)| !(s == serial && *p == port));
    ledger_write(app, &e);
}

/// Launch cleanup: remove forwards a previous run left in the adb server.
/// Blocking; call from a blocking context. Only our own recorded forwards.
pub(crate) fn remove_stale_forwards(app: &AppHandle, adb: &str, path_env: &str) {
    let entries = {
        let _g = LEDGER_LOCK.lock().unwrap();
        let e = ledger_read(app);
        ledger_write(app, &[]);
        e
    };
    for (serial, port) in entries {
        eprintln!("[shutdown] removing stale adb forward tcp:{port} for {serial}");
        run_bounded(
            Command::new(adb)
                .env("PATH", path_env)
                .args(["-s", &serial, "forward", "--remove", &format!("tcp:{port}")]),
            Duration::from_secs(5),
        );
    }
}

/// Run a command to completion, giving up (and killing it) after `timeout`.
/// Returns whether it ran and exited successfully. Output is discarded.
///
/// A quit that hangs is worse than one that leaves something behind, so every
/// command run during shutdown goes through this.
pub(crate) fn run_bounded(cmd: &mut Command, timeout: Duration) -> bool {
    let Ok(mut child) = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return false;
    };
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return status.success(),
            Err(_) => return false,
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                // Not an unbounded `wait()`: a process stuck inside a kernel
                // call (an unmount waiting on a stalled server) can't die
                // until that call returns. Give it a moment, then move on.
                let grace = Instant::now() + Duration::from_millis(200);
                while Instant::now() < grace {
                    if !matches!(child.try_wait(), Ok(None)) {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(20));
                }
                return false;
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(25)),
        }
    }
}

// ── orphaned `adb track-devices` ─────────────────────────────────────────

fn tracker_pid_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    use crate::host::Manager;
    app.path().app_data_dir().ok().map(|d| d.join("adb-tracker.pid"))
}

pub(crate) fn tracker_pid_record(app: &AppHandle, pid: u32) {
    if let Some(p) = tracker_pid_path(app) {
        let _ = std::fs::write(p, pid.to_string());
    }
}

pub(crate) fn tracker_pid_forget(app: &AppHandle) {
    if let Some(p) = tracker_pid_path(app) {
        let _ = std::fs::remove_file(p);
    }
}

/// Launch cleanup: a Force Quit leaves our `adb track-devices` child running,
/// re-parented to launchd. Only the PID we recorded is considered, and only if
/// it is still an orphaned `adb track-devices` (so a reused PID is left alone).
#[cfg(target_os = "macos")]
pub(crate) fn reap_orphan_tracker(app: &AppHandle) {
    let Some(p) = tracker_pid_path(app) else { return };
    let Ok(text) = std::fs::read_to_string(&p) else { return };
    let _ = std::fs::remove_file(&p);
    let Ok(pid) = text.trim().parse::<u32>() else { return };
    let Ok(out) = Command::new("/bin/ps").args(["-o", "ppid=,command=", "-p", &pid.to_string()]).output() else { return };
    let line = String::from_utf8_lossy(&out.stdout);
    let line = line.trim();
    if line.starts_with("1 ") && line.contains("adb") && line.contains("track-devices") {
        eprintln!("[shutdown] ending orphaned adb track-devices (pid {pid})");
        // SAFETY: `kill` only reads its two integer arguments.
        unsafe { libc::kill(pid as libc::pid_t, libc::SIGTERM) };
    }
}

#[cfg(not(target_os = "macos"))]
pub(crate) fn reap_orphan_tracker(_app: &AppHandle) {}
