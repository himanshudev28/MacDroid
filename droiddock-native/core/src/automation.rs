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
//! droiddock://commandbar        open the command bar
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

use crate::host::{AppHandle, Emitter};

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
    // S10: any web page can fire these URLs. State-changing routes are
    // rate-limited per route (`open` and `commandbar` never are) and tagged
    // `confirm: true` so the UI can ask first.

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
            if action == "mirror-adb" && !rate_ok("mirror-adb", std::time::Instant::now()) {
                return;
            }
            let _ = app.emit(
                "automation",
                serde_json::json!({ "action": action, "confirm": action == "mirror-adb" }),
            );
        }
        "desktop" => {
            if !rate_ok("desktop", std::time::Instant::now()) {
                return;
            }
            focus_main(app);
            let _ = app.emit("automation", serde_json::json!({ "action": "desktop", "confirm": true }));
        }
        "app" => {
            // `droiddock://app/com.whatsapp`
            let Some(pkg) = parts.next().filter(|p| is_package_name(p)) else {
                return;
            };
            if !rate_ok("app", std::time::Instant::now()) {
                return;
            }
            focus_main(app);
            let _ = app.emit(
                "automation",
                serde_json::json!({ "action": "app", "pkg": pkg, "confirm": true }),
            );
        }
        "commandbar" => {
            // Opens the command bar only; it changes nothing by itself, so no confirmation.
            let _ = app.emit("automation", commandbar_event());
        }
        "clipboard" => {
            if parts.next() == Some("push") && rate_ok("clipboard-push", std::time::Instant::now()) {
                let _ = app.emit("automation", serde_json::json!({ "action": "clipboard-push", "confirm": true }));
            }
        }
        _ => {}
    }
}

/// The event for `droiddock://commandbar` (the Mac side shows the ⌥Space bar).
fn commandbar_event() -> serde_json::Value {
    serde_json::json!({ "action": "commandbar" })
}

static LAST_ROUTE: std::sync::Mutex<Vec<(&'static str, std::time::Instant)>> = std::sync::Mutex::new(Vec::new());
const MIN_GAP: std::time::Duration = std::time::Duration::from_millis(1500);

/// At most one call per `MIN_GAP` for each state-changing route.
fn rate_ok(route: &'static str, now: std::time::Instant) -> bool {
    let mut g = LAST_ROUTE.lock().unwrap_or_else(|e| e.into_inner());
    match g.iter_mut().find(|(r, _)| *r == route) {
        Some((_, t)) => {
            if now.saturating_duration_since(*t) < MIN_GAP {
                return false;
            }
            *t = now;
        }
        None => g.push((route, now)),
    }
    true
}

/// Bringing the main window forward is the host's job: it reacts to the
/// `automation` event (and to `droiddock://open`, which has no event of its own).
fn focus_main(app: &AppHandle) {
    let _ = app.emit("automation", serde_json::json!({ "action": "open" }));
}

/// Android package names: dot-separated segments of letters, digits and
/// underscores. Validated rather than trusted because this string reaches a
/// process argument — `--start-app=+<pkg>` — and a URL is attacker-supplied.
///
/// scrcpy takes the package as a single `execve` argument with no shell in
/// between, so this is defence in depth rather than the only thing standing
/// between a URL and a command injection. It also keeps a malformed URL from
/// spawning a scrcpy that can only fail.
pub(crate) fn is_package_name(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 255
        && s.split('.').all(|seg| {
            !seg.is_empty() && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
}

#[cfg(test)]
mod tests {
    use super::{commandbar_event, is_package_name, rate_ok, MIN_GAP};

    #[test]
    fn commandbar_route_emits_unconfirmed_action() {
        let e = commandbar_event();
        assert_eq!(e["action"], "commandbar");
        assert!(e.get("confirm").is_none());
    }

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

    #[test]
    fn rate_limit_is_per_route() {
        let t = std::time::Instant::now();
        assert!(rate_ok("t-desktop", t));
        assert!(!rate_ok("t-desktop", t));
        assert!(rate_ok("t-app", t), "another route is independent");
        assert!(rate_ok("t-desktop", t + MIN_GAP));
    }
}
