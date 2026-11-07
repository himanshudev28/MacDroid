//! The Dock's per-app Space assignment ("Assign To → All Desktops").
//!
//! In the native app the window/theme/Space handling lives in Swift; what stays
//! here is the part that is invisible from a window: the Dock keeps the
//! assignment in the user's `com.apple.spaces` preferences, keyed by bundle id,
//! and stamps it onto every window the app opens. The host asks (and can undo
//! it) through `spaces_binding_active` / `spaces_binding_clear`. The
//! window-server check (`CGSCopySpacesForWindows`) now belongs to the host,
//! which owns the window.

use crate::host::AppHandle;

// ── The Dock's per-app Space assignment ──────────────────────────────────────

/// The pref domain the Dock keeps window/Space assignments in. `app-bindings`
/// inside it is a bundle-id → assignment dictionary, written when you pick
/// Dock icon → Options → Assign To.
#[cfg(target_os = "macos")]
const SPACES_DOMAIN: &str = "com.apple.spaces";
#[cfg(target_os = "macos")]
const BINDINGS_KEY: &str = "app-bindings";

/// What the Dock has this app assigned to, if anything.
///
/// # Why this outranks everything else in this module
///
/// `Assign To → All Desktops` is not a window property — it is an **app**
/// property, stored outside the app in `com.apple.spaces`, and the Dock stamps
/// it onto every window the app opens, after the app has opened it. So a
/// DroidDock with `app-bindings = { "com.droiddock.mac" = AllSpaces; }` shows
/// its main window on every desktop no matter what [`pin_to_own_space`] writes,
/// no matter how narrow the activation in [`activate_without_raising_all`] is,
/// and with no click or activation involved at all: plain Space switching is
/// enough, because the window genuinely lives on all of them.
///
/// It survives reinstalls and app rebuilds, because it is keyed on the bundle
/// id and lives in the user's preferences. That makes it invisible from inside
/// the app unless something goes looking — which is what this does.
///
/// Returns the assignment string the Dock recorded (`"AllSpaces"`, or a space
/// UUID for "this desktop"), or `None` when the app is unassigned, which is the
/// state a normal app is in.
#[cfg(target_os = "macos")]
pub fn dock_space_binding(bundle_id: &str) -> Option<String> {
    match read_bindings() {
        Bindings::Parsed(all) => all.into_iter().find(|(id, _)| id == bundle_id).map(|(_, value)| value),
        _ => None,
    }
}

#[cfg(not(target_os = "macos"))]
pub fn dock_space_binding(_bundle_id: &str) -> Option<String> {
    None
}

/// Undo the assignment above — the programmatic equivalent of Dock icon →
/// Options → Assign To → None.
///
/// Rewrites `app-bindings` without our entry (every *other* app's assignment is
/// preserved verbatim, including desktop-UUID ones) and restarts the Dock,
/// which is what makes it re-read the file and stop stamping the old answer
/// onto our windows. Nothing is lost by the restart: the Dock is stateless
/// across it.
///
/// Returns `false` if the assignment is still there afterwards, so the caller
/// can say "that didn't work, do it by hand" rather than claim a fix.
#[cfg(target_os = "macos")]
pub fn clear_dock_space_binding(bundle_id: &str) -> bool {
    use std::process::Command;

    let all = match read_bindings() {
        Bindings::Parsed(all) => all,
        // No assignments at all: nothing to clear.
        Bindings::Missing => return true,
        // Couldn't make sense of it: rewriting from a half-understood list would wipe every other
        // app's "Assign To", so touch nothing and let the caller say "do it by hand".
        Bindings::Unreadable => {
            eprintln!("[window] could not parse {SPACES_DOMAIN} {BINDINGS_KEY}; leaving it alone");
            return false;
        }
    };
    if !all.iter().any(|(id, _)| id == bundle_id) {
        return true;
    }
    let remaining: Vec<(String, String)> = all.into_iter().filter(|(id, _)| id != bundle_id).collect();

    let ok = if remaining.is_empty() {
        // The whole dictionary was ours, so the key itself goes. `defaults`
        // has no way to delete one entry of a dictionary, which is the only
        // reason this is written as a read-modify-write at all.
        Command::new("/usr/bin/defaults")
            .args(["delete", SPACES_DOMAIN, BINDINGS_KEY])
            .status()
            .is_ok()
    } else {
        let mut cmd = Command::new("/usr/bin/defaults");
        cmd.args(["write", SPACES_DOMAIN, BINDINGS_KEY, "-dict"]);
        for (id, value) in &remaining {
            cmd.arg(id).arg(value);
        }
        cmd.status().is_ok()
    };

    if !ok {
        eprintln!("[window] could not rewrite {SPACES_DOMAIN} {BINDINGS_KEY}");
        return false;
    }

    // SIGTERM, not SIGKILL: the Dock flushes cleanly and comes straight back.
    let _ = Command::new("/usr/bin/killall").arg("Dock").status();

    dock_space_binding(bundle_id).is_none()
}

#[cfg(not(target_os = "macos"))]
pub fn clear_dock_space_binding(_bundle_id: &str) -> bool {
    true
}

/// What `defaults read com.apple.spaces app-bindings` gave.
#[cfg(target_os = "macos")]
enum Bindings {
    Parsed(Vec<(String, String)>),
    /// The key doesn't exist (no app is assigned to a desktop).
    Missing,
    /// It exists but couldn't be run or fully parsed.
    Unreadable,
}

/// `defaults read com.apple.spaces app-bindings`, parsed.
///
/// The output is an old-style plist — `{ "com.foo.bar" = AllSpaces; }` — with
/// keys quoted only when they need to be. Anything that doesn't parse completely is
/// `Unreadable`, and [`clear_dock_space_binding`] then does nothing rather than write a
/// half-understood dictionary back over the user's.
#[cfg(target_os = "macos")]
fn read_bindings() -> Bindings {
    let Ok(out) = std::process::Command::new("/usr/bin/defaults")
        .args(["read", SPACES_DOMAIN, BINDINGS_KEY])
        .output()
    else {
        return Bindings::Unreadable;
    };
    if !out.status.success() {
        // The usual case: no app on this Mac is assigned to a desktop, so the
        // key doesn't exist and `defaults` exits non-zero with "does not exist".
        let err = String::from_utf8_lossy(&out.stderr);
        return if err.contains("does not exist") { Bindings::Missing } else { Bindings::Unreadable };
    }
    match parse_bindings(&String::from_utf8_lossy(&out.stdout)) {
        Some(v) => Bindings::Parsed(v),
        None => Bindings::Unreadable,
    }
}

/// `None` when any non-structural line isn't a `key = value;` pair.
#[cfg(target_os = "macos")]
fn parse_bindings(text: &str) -> Option<Vec<(String, String)>> {
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line == "{" || line == "}" || line == "};" {
            continue;
        }
        let (key, value) = line.trim_end_matches(';').split_once('=')?;
        let unquote = |s: &str| s.trim().trim_matches('"').to_string();
        let (key, value) = (unquote(key), unquote(value));
        if key.is_empty() || value.is_empty() || value == "{" || value == "(" {
            return None;
        }
        out.push((key, value));
    }
    Some(out)
}

// ── Surfaced to Settings ─────────────────────────────────────────────────────

/// `true` when the Dock has this app assigned to a desktop. See
/// [`dock_space_binding`]. (The window-server symptom check moved to the host.)
pub fn spaces_binding_active(app: AppHandle) -> bool {
    dock_space_binding(&app.config().identifier).is_some()
}

/// Clear that assignment. Reports whether it took, so Settings can fall back to
/// telling the user where the menu item is.
///
/// `async` only so the `defaults`/`killall` round-trip and the Dock's restart
/// don't block a runtime worker's caller.
pub async fn spaces_binding_clear(app: AppHandle) -> bool {
    clear_dock_space_binding(&app.config().identifier)
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::parse_bindings;

    #[test]
    fn reads_the_dock_assignment_dictionary() {
        let got = parse_bindings(
            "{\n    \"com.droiddock.mac\" = AllSpaces;\n    Finder = \"D5B6...\";\n}\n",
        )
        .unwrap();
        assert_eq!(
            got,
            vec![
                ("com.droiddock.mac".into(), "AllSpaces".into()),
                ("Finder".into(), "D5B6...".into()),
            ]
        );
    }

    #[test]
    fn empty_dictionary_parses_to_nothing() {
        assert_eq!(parse_bindings("{\n}\n").unwrap(), vec![]);
        assert_eq!(parse_bindings("").unwrap(), vec![]);
    }

    #[test]
    fn unparseable_output_is_none_never_an_empty_list() {
        // The guard that matters: an unparseable read must never look like "no other assignments".
        assert!(parse_bindings("does not exist").is_none());
        assert!(parse_bindings("garbage line").is_none());
        assert!(parse_bindings("{\n x = {\n y = 1;\n };\n}").is_none());
    }
}
