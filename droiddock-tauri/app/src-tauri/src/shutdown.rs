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
use tauri::AppHandle;

static DONE: AtomicBool = AtomicBool::new(false);

/// Stop everything DroidDock started. Safe to call more than once — Tauri
/// delivers both `ExitRequested` and `Exit`, and the updater calls it before a
/// restart — only the first call does anything.
pub fn on_quit(app: &AppHandle) {
    if DONE.swap(true, Ordering::SeqCst) {
        return;
    }
    // The order matters. The Finder volume has to be unmounted while its
    // server still answers: unmounting from a server that is already gone
    // leaves Finder stuck on the volume until a reboot.
    crate::webdav::shutdown_blocking(app);
    // Withdraw the announcements, so phones drop the Mac now.
    crate::quickshare::server::stop(app);
    crate::mdns::stop_blocking();
    // adb goes last. The in-app mirror's port forward and the scrcpy windows
    // both use the adb server to clean up on the phone, so the server has to
    // stay up until they're done.
    crate::scrcpy_client::cleanup_blocking(app);
    crate::adb::shutdown_blocking(app);
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
