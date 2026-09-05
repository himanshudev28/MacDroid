//! Saving what the mirror is showing: a still, or a recording of it.
//!
//! # Why the frontend does the encoding
//!
//! The pop-out window already holds decoded frames on a canvas — that is the
//! whole mirror pipeline. `canvas.toBlob()` and `MediaRecorder` over
//! `captureStream()` therefore get a PNG and an MP4 for free, off work WebKit
//! is already doing. Re-encoding here would mean a second decoder, a muxer, and
//! a copy of every frame crossing the IPC boundary at 30fps to feed it — for a
//! button pressed a few times a day.
//!
//! So this module is deliberately small: it is the part the browser sandbox
//! cannot do, which is putting bytes somewhere the user can find them.
//!
//! # Why the bytes arrive raw
//!
//! A recording is tens of megabytes. Passed as a JSON number array — Tauri's
//! default for `Vec<u8>` — that is one JSON element per byte, which is roughly
//! a hundred megabytes of text to parse. [`tauri::ipc::Request`]'s raw body
//! passes the `ArrayBuffer` through as bytes instead, the same mechanism the
//! mirror already uses in the other direction for video frames.
//!
//! The command is therefore **synchronous**: a borrowed `Request` cannot cross
//! an await point. The only blocking work is one `fs::write` of an already
//! fully-buffered file, on a local disk, in response to a button press.
//!
//! # A recording has no sound, on purpose
//!
//! Phone audio arrives on its own binary frame kind and is played by the *main*
//! window's `AudioContext`, deliberately, so it outlives any pop-out. The
//! mirror window's canvas stream therefore has no audio track to record, and
//! wiring one across two windows to get it would be a real piece of work. The
//! button says "video only" rather than quietly producing silent files.

use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager};

/// Extensions this will write, and which folder each belongs in.
///
/// An allow-list rather than a sanitiser: the extension comes from the webview
/// and ends up in a filesystem path, and "reject anything unrecognised" is the
/// only version of that check with no clever bypass.
fn folder_for(ext: &str) -> Option<&'static str> {
    match ext {
        "png" | "jpg" => Some("Pictures"),
        "mp4" | "webm" => Some("Movies"),
        _ => None,
    }
}

/// `Phone 2026-09-06 at 14.32.05.png` — macOS's own screenshot naming, which
/// sorts chronologically and reads as a date rather than an epoch.
fn stamped_name(ext: &str) -> String {
    let secs = crate::config::now_ms().div_euclid(1000) as libc::time_t;
    // SAFETY: `localtime_r` writes into `tm` and reads `secs`; both live for the
    // call, and the `_r` form needs no global lock. Same pattern as `tray.rs`.
    let tm = unsafe {
        let mut tm: libc::tm = std::mem::zeroed();
        if libc::localtime_r(&secs, &mut tm).is_null() {
            return format!("Phone {secs}.{ext}");
        }
        tm
    };
    format!(
        "Phone {:04}-{:02}-{:02} at {:02}.{:02}.{:02}.{ext}",
        tm.tm_year + 1900,
        tm.tm_mon + 1,
        tm.tm_mday,
        tm.tm_hour,
        tm.tm_min,
        tm.tm_sec,
    )
}

/// `~/Pictures/DroidDock` or `~/Movies/DroidDock`, resolved through Tauri's
/// path API with the same `$HOME` fallback `photo_sync` uses — a Mac with the
/// folder relocated should still get the relocated one.
fn dir_for(app: &AppHandle, ext: &str) -> Result<PathBuf, String> {
    let kind = folder_for(ext).ok_or_else(|| format!("Refusing to save a .{ext} file"))?;
    let base = match kind {
        "Pictures" => app.path().picture_dir().ok(),
        _ => app.path().video_dir().ok(),
    }
    .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(kind)))
    .ok_or("Could not find your home folder")?;
    Ok(base.join("DroidDock"))
}

/// Write `bytes` as a timestamped file and return its full path.
pub fn save(app: &AppHandle, ext: &str, bytes: &[u8]) -> Result<String, String> {
    if bytes.is_empty() {
        // A zero-length recording means MediaRecorder produced nothing — an
        // empty file on disk would look like a successful save of a broken clip.
        return Err("Nothing was captured".into());
    }
    let dir = dir_for(app, ext)?;
    std::fs::create_dir_all(&dir).map_err(|e| format!("Could not create {}: {e}", dir.display()))?;
    let path = unique(&dir, &stamped_name(ext));
    std::fs::write(&path, bytes).map_err(|e| format!("Could not write {}: {e}", path.display()))?;
    Ok(path.to_string_lossy().into_owned())
}

/// Two captures inside the same second must not overwrite each other. Rare, but
/// the failure is silent data loss, and the fix is four lines.
fn unique(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = name.rsplit_once('.').unwrap_or((name, ""));
    for n in 2..1000 {
        let candidate = dir.join(format!("{stem} ({n}).{ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    first
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_extensions_are_writable() {
        assert_eq!(folder_for("png"), Some("Pictures"));
        assert_eq!(folder_for("mp4"), Some("Movies"));
        // The extension reaches a path. Anything unrecognised — including the
        // obvious traversal attempts — has to be refused, not cleaned up.
        assert_eq!(folder_for("sh"), None);
        assert_eq!(folder_for("../../etc/passwd"), None);
        assert_eq!(folder_for("png/../../x"), None);
        assert_eq!(folder_for(""), None);
    }

    #[test]
    fn stamped_name_carries_the_extension_and_no_separators() {
        let name = stamped_name("png");
        assert!(name.ends_with(".png"), "{name}");
        assert!(!name.contains('/'), "{name}");
        assert!(name.starts_with("Phone "), "{name}");
    }

    #[test]
    fn unique_avoids_an_existing_file() {
        let dir = std::env::temp_dir().join(format!("droiddock-capture-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let taken = dir.join("Phone x.png");
        std::fs::write(&taken, b"1").unwrap();

        let next = unique(&dir, "Phone x.png");
        assert_ne!(next, taken);
        assert_eq!(next.file_name().unwrap().to_str().unwrap(), "Phone x (2).png");

        std::fs::remove_dir_all(&dir).ok();
    }

}
