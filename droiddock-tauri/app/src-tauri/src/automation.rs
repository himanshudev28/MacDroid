//! The `droiddock://` URL scheme — the app's automation surface.
//!
//! # Why a URL scheme and not AppleScript
//!
//! AirSync gets a 1,072-line scripting surface almost for free by being a
//! native AppKit app: macOS reads its `.sdef` and the whole Apple Events
//! machinery applies. Tauri is not an AppKit app in that sense, so matching it
//! would mean hand-building a scriptability dictionary and an Apple Event
//! handler for a result that Raycast, Shortcuts, Alfred and `open(1)` can all
//! reach through a URL anyway. This is the 90% at a fraction of the surface.
//!
//! # Routes
//!
//! ```text
//! droiddock://mirror              open the Mirror tab (Wi-Fi needs a phone tap)
//! droiddock://mirror/adb          start the in-app ADB mirror — no phone tap
//! droiddock://desktop             start desktop mode (needs ADB + scrcpy)
//! droiddock://app/<package>       open one Android app in its own Mac window
//! droiddock://clipboard/push      send the Mac clipboard to the phone now
//! droiddock://open                just focus the main window
//! ```
//!
//! # What this deliberately cannot do
//!
//! Anything here is reachable by any process on this Mac that can call `open`,
//! and by a web page the user clicks a link on. So the routes are limited to
//! things the user could already do from the UI in one click, against the phone
//! that is *already paired*. Nothing here pairs a device, changes a setting,
//! reads data back to the caller, or touches the filesystem — a URL scheme is
//! an unauthenticated entry point and is scoped accordingly.

use tauri::{AppHandle, Emitter};

/// Parse and run one `droiddock://…` URL.
///
/// Unknown routes are ignored rather than surfaced as errors: the scheme is a
/// public entry point, and a typo'd or hostile URL should be a no-op, not a
/// toast that tells the caller what does exist.
pub fn handle_url(app: &AppHandle, url: &str) {
    let Some(rest) = url.strip_prefix("droiddock://") else {
        return;
    };
    // Strip any query/fragment — no route takes parameters that way, and
    // accepting them silently would invite a route that does.
    let path = rest.split(['?', '#']).next().unwrap_or("").trim_matches('/');
    let mut parts = path.split('/');
    let head = parts.next().unwrap_or("");

    match head {
        "open" | "" => focus_main(app),
        "mirror" => {
            focus_main(app);
            // `mirror/adb` starts the in-app ADB mirror, which needs no consent
            // tap on the phone and so is safe to start outright. Bare `mirror`
            // only navigates: the Wi-Fi route needs the user to approve capture
            // on the phone, and a pop-out that appears with no visible cause is
            // worse than landing them on the tab that explains it.
            let action = if parts.next() == Some("adb") { "mirror-adb" } else { "mirror" };
            let _ = app.emit("automation", serde_json::json!({ "action": action }));
        }
        "desktop" => {
            focus_main(app);
            let _ = app.emit("automation", serde_json::json!({ "action": "desktop" }));
        }
        "app" => {
            // `droiddock://app/com.whatsapp`
            let Some(pkg) = parts.next().filter(|p| is_package_name(p)) else {
                return;
            };
            focus_main(app);
            let _ = app.emit(
                "automation",
                serde_json::json!({ "action": "app", "pkg": pkg }),
            );
        }
        "clipboard" => {
            if parts.next() == Some("push") {
                let _ = app.emit("automation", serde_json::json!({ "action": "clipboard-push" }));
            }
        }
        _ => {}
    }
}

/// Show and focus the main window, creating nothing — if the window has been
/// closed to the tray it still exists, which is the whole point of the
/// close-means-hide behaviour in `lib.rs`.
///
/// Delegates to [`crate::tray::raise_main`] rather than doing it by hand. The
/// hand-rolled version was `show()` + `set_focus()`, which is precisely the
/// pair the rest of this codebase goes out of its way not to use:
///
/// * `show()` is `-[NSWindow orderFront:]`, and ordering a window that lives on
///   another desktop *moves it to the one you are on*.
/// * `set_focus()` ends in `-[NSApplication activateIgnoringOtherApps:]`, which
///   brings every window the app owns forward — see
///   [`crate::appearance::activate_without_raising_all`].
///
/// So every `droiddock://` URL — a Raycast script, a Shortcut, a plain `open` —
/// yanked the main window onto the current Space, whichever desktop the user
/// had left it on. `raise_main` checks `isOnActiveSpace` first and activates
/// without ordering when the window is already open elsewhere.
fn focus_main(app: &AppHandle) {
    crate::tray::raise_main(app);
}

/// Android package names: dot-separated segments of letters, digits and
/// underscores. Validated rather than trusted because this string reaches a
/// process argument — `--start-app=+<pkg>` — and a URL is attacker-supplied.
///
/// scrcpy takes the package as a single `execve` argument with no shell in
/// between, so this is defence in depth rather than the only thing standing
/// between a URL and a command injection. It also keeps a malformed URL from
/// spawning a scrcpy that can only fail.
fn is_package_name(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 255
        && s.split('.').all(|seg| {
            !seg.is_empty() && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
}

#[cfg(test)]
mod tests {
    use super::is_package_name;

    #[test]
    fn accepts_real_package_names() {
        assert!(is_package_name("com.whatsapp"));
        assert!(is_package_name("com.google.android.apps.maps"));
        assert!(is_package_name("org.fossify.home"));
        assert!(is_package_name("app_1.test2"));
    }

    /// Everything here reaches a process argument. Shell metacharacters, path
    /// traversal and flag-injection attempts must all be rejected outright —
    /// a leading `-` is the interesting one, since it could otherwise be read
    /// as another scrcpy option.
    #[test]
    fn rejects_anything_that_is_not_a_package_name() {
        assert!(!is_package_name(""));
        assert!(!is_package_name("com..whatsapp"));
        assert!(!is_package_name(".com.whatsapp"));
        assert!(!is_package_name("com.whatsapp."));
        assert!(!is_package_name("com.whatsapp; rm -rf /"));
        assert!(!is_package_name("com whatsapp"));
        assert!(!is_package_name("--video-codec=h265"));
        assert!(!is_package_name("../../etc/passwd"));
        assert!(!is_package_name("com.what$app"));
        assert!(!is_package_name(&"a".repeat(300)));
    }
}
