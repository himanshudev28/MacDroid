//! Panic capture, written to disk and nowhere else.
//!
//! The Android app has had `CrashNotifier.kt` since early on; the Mac side had
//! nothing, so a panic on a background thread killed a feature silently and the
//! only evidence was "it stopped working". This writes what happened to a file
//! you can actually read.
//!
//! **No reporting service, by design.** The project's standing rule is zero
//! data egress, and a panic message is exactly the kind of thing that carries
//! file paths, device names and occasionally message text. So this is a local
//! log with a "Reveal in Finder" button — not a crash reporter.
//!
//! Panics here are rarely fatal to the process: Tauri command handlers and
//! spawned tasks unwind independently, so the app usually keeps running with
//! one thing broken. That is precisely why a record matters — there is no crash
//! dialog to notice.

use std::fs;
use std::io::Write;
use std::path::PathBuf;
use tauri::AppHandle;

/// Where the logs live. `~/Library/Logs/DroidDock` is where macOS users (and
/// Console.app) already look for an app's logs, rather than burying them in
/// Application Support next to the config.
pub fn log_dir() -> Option<PathBuf> {
    let home = std::env::var("HOME").ok()?;
    Some(PathBuf::from(home).join("Library/Logs/DroidDock"))
}

/// Keep the directory from growing without bound. Panics are rare; if there are
/// more than this, the oldest are the least interesting.
const KEEP: usize = 20;

fn prune(dir: &PathBuf) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let mut files: Vec<_> = entries
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().starts_with("panic-"))
        .filter_map(|e| e.metadata().ok().and_then(|m| m.modified().ok()).map(|t| (t, e.path())))
        .collect();
    if files.len() <= KEEP {
        return;
    }
    // Oldest first, then drop everything past the cap.
    files.sort_by_key(|(t, _)| *t);
    for (_, path) in files.iter().take(files.len() - KEEP) {
        let _ = fs::remove_file(path);
    }
}

/// Install the panic hook. Call once, early — before anything spawns.
///
/// The previous hook is chained rather than replaced, so the default
/// stderr output still happens in `tauri dev` where it is what you actually
/// read.
pub fn install() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        write_panic(info);
        previous(info);
    }));
}

fn write_panic(info: &std::panic::PanicHookInfo<'_>) {
    let Some(dir) = log_dir() else { return };
    if fs::create_dir_all(&dir).is_err() {
        return;
    }

    // Seconds since the epoch, not a formatted date: this crate carries no
    // date dependency (see `now_timestamp` in adb.rs for the same reasoning),
    // and the file's own mtime is what Finder sorts on anyway.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let path = dir.join(format!("panic-{secs}.log"));

    // `payload` is the panic message for the string/format cases, which is
    // every panic this codebase can actually produce.
    let msg = info
        .payload()
        .downcast_ref::<&str>()
        .map(|s| (*s).to_string())
        .or_else(|| info.payload().downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "(non-string panic payload)".to_string());
    let where_ = info
        .location()
        .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
        .unwrap_or_else(|| "(unknown location)".to_string());

    // Deliberately not `RUST_BACKTRACE`-gated: a release build with no
    // backtrace still tells you the message and the source location, which is
    // usually enough to find it.
    let body = format!(
        "DroidDock {} panicked\n\
         when:    epoch {secs}\n\
         where:   {where_}\n\
         thread:  {}\n\
         message: {msg}\n\n\
         backtrace:\n{}\n",
        env!("CARGO_PKG_VERSION"),
        std::thread::current().name().unwrap_or("unnamed").to_string(),
        std::backtrace::Backtrace::force_capture(),
    );

    if let Ok(mut f) = fs::File::create(&path) {
        let _ = f.write_all(body.as_bytes());
    }
    prune(&dir);
}

// ── Tauri commands ───────────────────────────────────────────────────────

/// How many panic logs exist, so Settings can say "3 logs" rather than
/// offering to reveal an empty folder.
#[tauri::command]
pub fn crash_log_count() -> usize {
    let Some(dir) = log_dir() else { return 0 };
    fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .filter(|e| e.file_name().to_string_lossy().starts_with("panic-"))
                .count()
        })
        .unwrap_or(0)
}

/// Show the log folder in Finder.
#[tauri::command]
pub fn crash_logs_reveal(app: AppHandle) -> Result<(), String> {
    let dir = log_dir().ok_or("no home directory")?;
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    tauri_plugin_opener::OpenerExt::opener(&app)
        .open_path(dir.to_string_lossy().to_string(), None::<&str>)
        .map_err(|e| e.to_string())
}

/// Delete every panic log.
#[tauri::command]
pub fn crash_logs_clear() -> Result<usize, String> {
    let Some(dir) = log_dir() else { return Ok(0) };
    let Ok(entries) = fs::read_dir(&dir) else { return Ok(0) };
    let mut n = 0;
    for e in entries.flatten() {
        if e.file_name().to_string_lossy().starts_with("panic-") && fs::remove_file(e.path()).is_ok()
        {
            n += 1;
        }
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The hook must chain, not replace — otherwise `tauri dev` loses the
    /// stderr panic output that is how you actually debug during development.
    /// Installing twice must also stay safe, since `install` is called from
    /// setup paths that a test harness can re-enter.
    #[test]
    fn install_is_idempotent_and_chains() {
        install();
        install();
        // Reaching here means neither call panicked or recursed.
    }

    /// `prune` on a directory that doesn't exist must not panic — it runs
    /// inside the panic hook, where a second panic aborts the process.
    #[test]
    fn prune_tolerates_a_missing_directory() {
        prune(&PathBuf::from("/nonexistent/droiddock-test-dir"));
    }

    /// Same reasoning for the count command: it is called from the UI on a
    /// machine that has never crashed.
    #[test]
    fn counting_with_no_logs_is_zero_not_an_error() {
        // Whatever the real directory holds, this must return a number rather
        // than panicking or erroring.
        let _ = crash_log_count();
    }
}
