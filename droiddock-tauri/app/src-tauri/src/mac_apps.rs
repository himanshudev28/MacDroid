//! This Mac's applications, listable and launchable from the phone.
//!
//! The mirror image of `AppsRepo.kt`: that lets the Mac open a phone app, this
//! lets the phone open a Mac one. Same shape on the wire (`pkg`/`label`), where
//! `pkg` is a bundle identifier rather than an Android package name.
//!
//! # Why this is gated harder than the rest of the link
//!
//! Everything else the phone can ask for is *data* — a listing, a thumbnail, a
//! message. This starts a process on the Mac. That is the same class of power
//! as `mac_remote`'s pointer and keyboard control, so it sits behind the same
//! switch (`remote_control`, off by default) and re-checks it on every inbound
//! message rather than trusting a capability advertised at handshake time.
//!
//! # What it will not launch
//!
//! Only `.app` bundles found in the three standard application directories,
//! resolved by bundle identifier through `NSWorkspace`. A phone cannot name an
//! arbitrary path, a binary, or a script — the request carries a bundle id,
//! that id is looked up in the scanned set, and anything not in it is refused.
//! This is what keeps "open an app" from becoming "run anything on my Mac".

use serde::Serialize;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use tauri::AppHandle;

/// Where macOS keeps applications. Deliberately not user-configurable: the
/// point of the allowlist is that it is small and predictable.
fn app_dirs() -> Vec<PathBuf> {
    let mut v = vec![
        PathBuf::from("/Applications"),
        PathBuf::from("/System/Applications"),
    ];
    if let Ok(home) = std::env::var("HOME") {
        v.push(PathBuf::from(home).join("Applications"));
    }
    v
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct MacApp {
    /// Bundle identifier — the wire's `pkg`, and the only thing the phone can
    /// name when asking to launch something.
    pub pkg: String,
    pub label: String,
}

/// Read `CFBundleIdentifier` and a display name out of an `.app`'s Info.plist.
///
/// Uses `defaults read`, which handles both the binary and XML plist formats
/// without adding a plist parser dependency for one field.
fn read_bundle(app_path: &Path) -> Option<MacApp> {
    let plist = app_path.join("Contents/Info");
    let read = |key: &str| -> Option<String> {
        let out = std::process::Command::new("/usr/bin/defaults")
            .arg("read")
            .arg(&plist)
            .arg(key)
            .output()
            .ok()?;
        if !out.status.success() {
            return None;
        }
        let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if s.is_empty() { None } else { Some(s) }
    };

    let pkg = read("CFBundleIdentifier")?;
    if !is_bundle_id(&pkg) {
        return None;
    }
    // Fall back through the names macOS itself uses, then the folder name —
    // some bundles set only one of these.
    let label = read("CFBundleDisplayName")
        .or_else(|| read("CFBundleName"))
        .unwrap_or_else(|| {
            app_path
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| pkg.clone())
        });
    Some(MacApp { pkg, label })
}

/// Bundle identifiers are reverse-DNS: dot-separated alphanumeric segments,
/// with `-` and `_` allowed inside a segment. Validated because this string is
/// compared against a launch request and would otherwise be a free-form key.
///
/// Every segment must *start* with an alphanumeric. That rules out a leading
/// `-`, which `open` would read as the start of a flag rather than an operand.
/// The membership check in `launch` would reject such a string anyway — no
/// installed app is called `-b` — but a validator that admits an argument
/// shaped like a flag is one refactor away from being a hole.
fn is_bundle_id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 255
        && s.split('.').all(|seg| {
            seg.starts_with(|c: char| c.is_ascii_alphanumeric())
                && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        })
}

/// Every launchable app on this Mac, sorted by name.
///
/// One level deep only: `/Applications/Foo.app` and `/Applications/Utilities/
/// Foo.app` are both found, but this does not walk the whole disk.
pub fn list() -> Vec<MacApp> {
    let mut out: Vec<MacApp> = Vec::new();
    let mut seen = std::collections::HashSet::new();

    let consider = |path: &Path, out: &mut Vec<MacApp>, seen: &mut std::collections::HashSet<String>| {
        if path.extension().and_then(|e| e.to_str()) != Some("app") {
            return;
        }
        if let Some(app) = read_bundle(path) {
            if seen.insert(app.pkg.clone()) {
                out.push(app);
            }
        }
    };

    for dir in app_dirs() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) == Some("app") {
                consider(&p, &mut out, &mut seen);
            } else if p.is_dir() {
                // One nested level — this is where Utilities lives.
                let Ok(inner) = std::fs::read_dir(&p) else { continue };
                for e2 in inner.flatten() {
                    consider(&e2.path(), &mut out, &mut seen);
                }
            }
        }
    }

    out.sort_by_key(|a| a.label.to_lowercase());
    out
}

/// Launch one app by bundle id, but only if it is in the scanned set.
///
/// The membership check is the security boundary: `open -b` would otherwise
/// happily resolve a bundle id registered anywhere on the system, including
/// somewhere a downloaded file put it.
pub fn launch(pkg: &str) -> Result<(), String> {
    if !is_bundle_id(pkg) {
        return Err("not a bundle identifier".into());
    }
    if !list().iter().any(|a| a.pkg == pkg) {
        return Err("no such application".into());
    }
    let status = std::process::Command::new("/usr/bin/open")
        .args(["-b", pkg])
        .status()
        .map_err(|e| e.to_string())?;
    if status.success() {
        Ok(())
    } else {
        Err("macOS refused to open it".into())
    }
}

/// Whether the phone is allowed to see or launch Mac apps at all.
fn enabled(app: &AppHandle) -> bool {
    use tauri::Manager;
    app.state::<crate::AppState>().config.lock().unwrap().remote_control
}

/// The capability advertised in `welcome.caps` while the feature is on.
pub const CAP: &str = "macapps";

/// Handle `mac-apps-list` / `mac-app-launch`. Returns the reply to send, or
/// `None` if this wasn't one of ours.
///
/// Re-reads the setting on every message: a user who turns remote control off
/// mid-session must not keep a still-open phone screen working.
pub fn on_message(app: &AppHandle, msg: &Value) -> Option<Value> {
    let ty = msg.get("type")?.as_str()?;
    let req_id = msg.get("reqId").cloned().unwrap_or(Value::Null);
    match ty {
        "mac-apps-list" => {
            if !enabled(app) {
                return Some(json!({
                    "type": "mac-apps-list", "reqId": req_id,
                    "error": "Turn on “Let the phone control this Mac” in DroidDock’s settings"
                }));
            }
            Some(json!({ "type": "mac-apps-list", "reqId": req_id, "apps": list() }))
        }
        "mac-app-launch" => {
            if !enabled(app) {
                return Some(json!({
                    "type": "mac-app-launch", "reqId": req_id,
                    "error": "Turn on “Let the phone control this Mac” in DroidDock’s settings"
                }));
            }
            let pkg = msg.get("pkg").and_then(Value::as_str).unwrap_or_default();
            Some(match launch(pkg) {
                Ok(()) => json!({ "type": "mac-app-launch", "reqId": req_id, "ok": true }),
                Err(e) => json!({ "type": "mac-app-launch", "reqId": req_id, "error": e }),
            })
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The membership check in `launch` is the security boundary, and it rests
    /// on this. Anything that isn't reverse-DNS must be rejected before it can
    /// reach `open -b`.
    #[test]
    fn only_reverse_dns_identifiers_are_accepted() {
        assert!(is_bundle_id("com.apple.Safari"));
        assert!(is_bundle_id("com.microsoft.VSCode"));
        assert!(is_bundle_id("org.mozilla.firefox"));
        assert!(is_bundle_id("com.a-b_c.App1"));

        assert!(!is_bundle_id(""));
        assert!(!is_bundle_id("com..apple"));
        assert!(!is_bundle_id(".com.apple"));
        assert!(!is_bundle_id("com.apple."));
        assert!(!is_bundle_id("com.apple Safari"));
        assert!(!is_bundle_id("/Applications/Evil.app"));
        assert!(!is_bundle_id("../../bin/sh"));
        assert!(!is_bundle_id("com.apple;rm -rf /"));
        assert!(!is_bundle_id("-b"));
        assert!(!is_bundle_id(&"a".repeat(300)));
    }

    /// A bundle id that is well-formed but not installed must still be refused
    /// — validity is not membership.
    #[test]
    fn a_wellformed_but_unknown_identifier_is_refused() {
        let err = launch("com.example.definitely.not.installed.xyz").unwrap_err();
        assert_eq!(err, "no such application");
    }

    /// The scan must find real applications on any Mac this runs on, and every
    /// entry it returns must be launchable-shaped.
    #[test]
    fn scanning_finds_system_applications() {
        let apps = list();
        assert!(!apps.is_empty(), "a Mac always has /System/Applications");
        for a in &apps {
            assert!(is_bundle_id(&a.pkg), "{} is not a bundle id", a.pkg);
            assert!(!a.label.is_empty());
        }
        // Bundle ids are the launch key, so duplicates would make the choice
        // ambiguous.
        let mut ids: Vec<&str> = apps.iter().map(|a| a.pkg.as_str()).collect();
        ids.sort_unstable();
        let before = ids.len();
        ids.dedup();
        assert_eq!(before, ids.len(), "bundle ids must be unique");
    }
}
