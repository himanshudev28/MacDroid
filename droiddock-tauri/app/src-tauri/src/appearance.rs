use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct SystemAppearance {
    pub accent_color: String,
    // WebKit has no CSS media query for this (confirmed: unsupported through
    // Safari 27 / iOS 26.5), unlike prefers-reduced-motion/prefers-color-scheme,
    // which are real, WebKit-supported CSS media queries and need no native read.
    pub reduce_transparency: bool,
}

/// Default used if the native read ever fails: macOS system Blue.
const FALLBACK_ACCENT: &str = "#0A84FF";

#[cfg(target_os = "macos")]
pub fn read() -> SystemAppearance {
    use objc2_app_kit::{NSColor, NSColorSpace, NSWorkspace};

    let accent_color = (|| {
        let color = NSColor::controlAccentColor();
        let srgb = color.colorUsingColorSpace(&NSColorSpace::sRGBColorSpace())?;
        let r = (srgb.redComponent() * 255.0).round() as u8;
        let g = (srgb.greenComponent() * 255.0).round() as u8;
        let b = (srgb.blueComponent() * 255.0).round() as u8;
        Some(format!("#{r:02X}{g:02X}{b:02X}"))
    })()
    .unwrap_or_else(|| FALLBACK_ACCENT.to_string());

    let reduce_transparency =
        NSWorkspace::sharedWorkspace().accessibilityDisplayShouldReduceTransparency();

    SystemAppearance {
        accent_color,
        reduce_transparency,
    }
}

#[cfg(not(target_os = "macos"))]
pub fn read() -> SystemAppearance {
    SystemAppearance {
        accent_color: FALLBACK_ACCENT.to_string(),
        reduce_transparency: false,
    }
}

/// State the main window's Space membership explicitly: it belongs to the one
/// desktop it was opened on.
///
/// Re-applied every time the window is shown or focused, not just at startup.
/// `collectionBehavior` is a property anything can overwrite after we set it —
/// the Dock re-stamps it on every window an app opens when that app carries a
/// Space assignment (see [`dock_space_binding`]) — so setting it once during
/// `setup` proves nothing about what the window carries an hour later.
///
/// `FullScreenPrimary` is kept so the green button still offers full screen;
/// dropping it silently disables that.
#[cfg(target_os = "macos")]
pub fn pin_to_own_space(window: &tauri::WebviewWindow) {
    use objc2_app_kit::{NSWindow, NSWindowCollectionBehavior};

    let Ok(ptr) = window.ns_window() else { return };
    if ptr.is_null() {
        return;
    }
    // SAFETY: `ns_window()` returns this window's live `NSWindow`, and Tauri
    // keeps it alive for the window's lifetime. AppKit requires the main
    // thread, which the caller guarantees (see the call site in `lib.rs`).
    let ns: &NSWindow = unsafe { &*(ptr as *const NSWindow) };

    let before = ns.collectionBehavior();
    let wanted = (before
        & !NSWindowCollectionBehavior::CanJoinAllSpaces
        & !NSWindowCollectionBehavior::MoveToActiveSpace
        & !NSWindowCollectionBehavior::Transient)
        | NSWindowCollectionBehavior::Managed
        | NSWindowCollectionBehavior::FullScreenPrimary;

    if before != wanted {
        eprintln!(
            "[window] main collectionBehavior {:#x} → {:#x}",
            before.0, wanted.0
        );
        ns.setCollectionBehavior(wanted);
    }
}

#[cfg(not(target_os = "macos"))]
pub fn pin_to_own_space(_window: &tauri::WebviewWindow) {}

/// Make this app frontmost **without dragging its other windows to the desktop
/// you happen to be on**.
///
/// # The bug this exists to fix
///
/// Tauri's `set_focus()` ends in `-[NSApplication activateIgnoringOtherApps:]`,
/// and AppKit is explicit about what that means: *all* of the app's windows are
/// brought forward. When the main window lives on desktop 3 and you click the
/// menu-bar icon from desktop 2, "brought forward" is resolved by yanking it
/// onto desktop 2 — macOS won't switch Spaces for you here, because the app
/// already owns a window on this one (the panel and the status widget both join
/// every Space by design). The window vanishing from the desktop you left it on
/// and reappearing over your current work is the whole reported symptom, and no
/// `collectionBehavior` value prevents it: this is window *ordering*, not Space
/// membership.
///
/// `NSRunningApplication`'s activation is the same thing minus that clause —
/// with no `ActivateAllWindows` option it brings forward only the main and key
/// windows, so the panel we just ordered front comes up and everything parked
/// on another desktop stays parked.
#[cfg(target_os = "macos")]
pub fn activate_without_raising_all() {
    use objc2_app_kit::{NSApplicationActivationOptions, NSRunningApplication};

    // Deliberately empty: `ActivateAllWindows` is precisely the flag that
    // reaches across Spaces, and `ActivateIgnoringOtherApps` is a no-op from
    // macOS 14 on.
    NSRunningApplication::currentApplication()
        .activateWithOptions(NSApplicationActivationOptions::empty());
}

#[cfg(not(target_os = "macos"))]
pub fn activate_without_raising_all() {}

/// Make the window's own material follow the app's theme instead of macOS's.
///
/// # The bug this fixes
///
/// `tauri.conf.json` asks for the `sidebar` vibrancy material, and an
/// `NSVisualEffectView` renders according to its window's *effective
/// appearance* — which, left alone, is whatever macOS is set to. Run the Mac in
/// light mode, set DroidDock to dark, push the glass slider up, and the two
/// disagree: every translucent surface is backed by a light material and washes
/// out to grey-white, while everything still painted opaque — cards, raised
/// panels — stays espresso brown. That is the "whitish, with brown patches"
/// report exactly, and no amount of CSS fixes it, because the offending colour
/// is behind the webview rather than in it.
///
/// `prefers-color-scheme` inside the webview follows this too, which is why
/// `system` deliberately clears the override rather than pinning a value: the
/// app resolves `system` *from* that media query, so forcing it would be a
/// feedback loop that locks the app to whatever it happened to boot as.
#[tauri::command]
pub fn window_theme_set(app: tauri::AppHandle, theme: String) {
    #[cfg(target_os = "macos")]
    {
        use tauri::Manager;

        for window in app.webview_windows().into_values() {
            let theme = theme.clone();
            let target = window.clone();
            let _ = window.run_on_main_thread(move || apply_ns_appearance(&target, &theme));
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (app, theme);
}

#[cfg(target_os = "macos")]
fn apply_ns_appearance(window: &tauri::WebviewWindow, theme: &str) {
    // `setAppearance` lives on the NSAppearanceCustomization protocol, which
    // NSWindow conforms to — the trait has to be in scope for the method.
    use objc2_app_kit::{
        NSAppearance, NSAppearanceCustomization, NSAppearanceNameAqua, NSAppearanceNameDarkAqua,
        NSWindow,
    };

    let Ok(ptr) = window.ns_window() else { return };
    if ptr.is_null() {
        return;
    }
    // SAFETY: as in `pin_to_own_space` — a live `NSWindow` Tauri owns, on the
    // main thread.
    let ns: &NSWindow = unsafe { &*(ptr as *const NSWindow) };

    let name = match theme {
        "dark" => unsafe { NSAppearanceNameDarkAqua },
        "light" => unsafe { NSAppearanceNameAqua },
        // Anything else means "follow the Mac", and the way to say that is to
        // have no appearance of our own.
        _ => {
            ns.setAppearance(None);
            return;
        }
    };
    ns.setAppearance(NSAppearance::appearanceNamed(name).as_deref());
}

/// Watch the main window's Space membership and shout when it changes.
///
/// Exists because "the window appears on every desktop" has several possible
/// causes that look identical from the outside — a Dock-level Space assignment,
/// a `collectionBehavior` flag, or the window being *ordered* across by an
/// activation — and no amount of squinting at screenshots tells them apart.
/// This does: it samples `collectionBehavior` and `isOnActiveSpace` and reports
/// every transition, so a plain desktop switch that ends with the window
/// following you is visible in the log as the window going back on-space with
/// nothing having asked it to.
///
/// Set `DROIDDOCK_DEBUG_SPACES=1` and run the binary from a terminal to see it.
#[cfg(target_os = "macos")]
pub fn spawn_space_probe(app: tauri::AppHandle) {
    use tauri::Manager;

    if std::env::var("DROIDDOCK_DEBUG_SPACES").as_deref() != Ok("1") {
        return;
    }
    eprintln!("[spaces] probe on — sampling the main window every 500ms");

    std::thread::spawn(move || {
        let mut last: Option<(bool, u64, bool)> = None;
        let mut n: u64 = 0;
        loop {
            std::thread::sleep(std::time::Duration::from_millis(500));
            n += 1;
            let Some(win) = app.get_webview_window("main") else {
                eprintln!("[spaces] #{n} no main window");
                continue;
            };
            let (tx, rx) = std::sync::mpsc::channel();
            let w = win.clone();
            // AppKit reads have to happen on the main thread.
            if let Err(e) = win.run_on_main_thread(move || {
                let _ = tx.send((
                    is_on_active_space(&w),
                    collection_behavior(&w),
                    w.is_visible().unwrap_or(false),
                ));
            }) {
                eprintln!("[spaces] #{n} main-thread hop failed: {e}");
                continue;
            }
            let now = match rx.recv_timeout(std::time::Duration::from_secs(2)) {
                Ok(v) => v,
                Err(e) => {
                    eprintln!("[spaces] #{n} sample timed out: {e}");
                    continue;
                }
            };
            // Heartbeat as well as transitions: "nothing changed" and "the
            // sampler died" produce identical logs otherwise, and telling them
            // apart is the entire point of running this.
            if last.as_ref() != Some(&now) || n % 20 == 0 {
                let (on_space, behavior, visible) = now;
                eprintln!(
                    "[spaces] #{n} on_active_space={on_space} \
                     collectionBehavior={behavior:#x} visible={visible}"
                );
                last = Some(now);
            }
        }
    });
}

#[cfg(not(target_os = "macos"))]
pub fn spawn_space_probe(_app: tauri::AppHandle) {}

/// The window's raw `collectionBehavior`, for the probe above.
#[cfg(target_os = "macos")]
fn collection_behavior(window: &tauri::WebviewWindow) -> u64 {
    use objc2_app_kit::NSWindow;

    let Ok(ptr) = window.ns_window() else { return 0 };
    if ptr.is_null() {
        return 0;
    }
    // SAFETY: as in `pin_to_own_space`.
    let ns: &NSWindow = unsafe { &*(ptr as *const NSWindow) };
    ns.collectionBehavior().0 as u64
}

/// Is this window on the desktop the user is currently looking at?
///
/// # Why anyone needs to ask
///
/// `orderFront:` — which is what `show()` becomes — does not mean "make
/// visible" for a window that is already visible on another Space. It means
/// "put this window at the front **of the desktop I am on now**", and AppKit
/// implements that by *moving the window here*. So the obvious implementation
/// of "bring my window back", `show()` then activate, quietly relocates the
/// window to whichever desktop the user happened to be on when they clicked —
/// and after a day of clicking the tray icon from different desktops, the app
/// appears to live on all of them.
///
/// Asking first is the whole fix: a window that is already up and simply
/// elsewhere must be reached by activating the app, which makes macOS switch
/// desktops the way it does for every other Mac app.
#[cfg(target_os = "macos")]
pub fn is_on_active_space(window: &tauri::WebviewWindow) -> bool {
    use objc2_app_kit::NSWindow;

    let Ok(ptr) = window.ns_window() else {
        return true;
    };
    if ptr.is_null() {
        return true;
    }
    // SAFETY: as in `pin_to_own_space` — a live `NSWindow` Tauri owns, read on
    // the main thread.
    let ns: &NSWindow = unsafe { &*(ptr as *const NSWindow) };
    ns.isOnActiveSpace()
}

#[cfg(not(target_os = "macos"))]
pub fn is_on_active_space(_window: &tauri::WebviewWindow) -> bool {
    true
}

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
    read_bindings()
        .into_iter()
        .find(|(id, _)| id == bundle_id)
        .map(|(_, value)| value)
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

    let remaining: Vec<(String, String)> = read_bindings()
        .into_iter()
        .filter(|(id, _)| id != bundle_id)
        .collect();

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

/// `defaults read com.apple.spaces app-bindings`, parsed.
///
/// The output is an old-style plist — `{ "com.foo.bar" = AllSpaces; }` — with
/// keys quoted only when they need to be. Anything that doesn't parse yields an
/// empty list, and an empty list means [`clear_dock_space_binding`] deletes the
/// key rather than writing a half-understood dictionary back over the user's.
#[cfg(target_os = "macos")]
fn read_bindings() -> Vec<(String, String)> {
    let Ok(out) = std::process::Command::new("/usr/bin/defaults")
        .args(["read", SPACES_DOMAIN, BINDINGS_KEY])
        .output()
    else {
        return Vec::new();
    };
    if !out.status.success() {
        // The usual case: no app on this Mac is assigned to a desktop, so the
        // key doesn't exist and `defaults` exits non-zero.
        return Vec::new();
    }
    parse_bindings(&String::from_utf8_lossy(&out.stdout))
}

#[cfg(target_os = "macos")]
fn parse_bindings(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|line| {
            let line = line.trim().trim_end_matches(';');
            let (key, value) = line.split_once('=')?;
            let unquote = |s: &str| s.trim().trim_matches('"').to_string();
            let (key, value) = (unquote(key), unquote(value));
            (!key.is_empty() && !value.is_empty()).then_some((key, value))
        })
        .collect()
}

// ── Surfaced to Settings ─────────────────────────────────────────────────────

/// `true` when the Dock has this app assigned to a desktop — the one cause of
/// "the window shows up on every Space" that the app cannot fix by writing to
/// its own windows.
#[tauri::command]
pub fn spaces_binding_active(app: tauri::AppHandle) -> bool {
    dock_space_binding(&app.config().identifier).is_some()
}

/// Clear that assignment and re-pin the window. Reports whether it took, so
/// Settings can fall back to telling the user where the menu item is.
///
/// `async` only so the `defaults`/`killall` round-trip and the Dock's restart
/// don't block the main thread — the AppKit half is handed back to it.
#[tauri::command]
pub async fn spaces_binding_clear(app: tauri::AppHandle) -> bool {
    use tauri::Manager;

    let cleared = clear_dock_space_binding(&app.config().identifier);

    if let Some(win) = app.get_webview_window("main") {
        let w = win.clone();
        let _ = win.run_on_main_thread(move || pin_to_own_space(&w));
    }
    cleared
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::parse_bindings;

    #[test]
    fn reads_the_dock_assignment_dictionary() {
        let got = parse_bindings(
            "{\n    \"com.droiddock.mac\" = AllSpaces;\n    Finder = \"D5B6...\";\n}\n",
        );
        assert_eq!(
            got,
            vec![
                ("com.droiddock.mac".into(), "AllSpaces".into()),
                // Unquoted keys and quoted values both occur; a desktop-level
                // assignment is a space UUID, and rewriting the dictionary has
                // to hand that back to `defaults` unchanged.
                ("Finder".into(), "D5B6...".into()),
            ]
        );
    }

    #[test]
    fn braces_and_junk_contribute_no_entries() {
        // The guard that matters: an unparseable read must look like "no
        // assignments", never like a dictionary worth writing back.
        assert!(parse_bindings("{\n}\n").is_empty());
        assert!(parse_bindings("").is_empty());
        assert!(parse_bindings("does not exist").is_empty());
    }
}
