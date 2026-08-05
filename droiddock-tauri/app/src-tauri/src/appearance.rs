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
/// This is belt-and-braces, not the cure for a window that follows you around.
/// The window the toolkit hands us starts at `collectionBehavior 0x0`
/// (`Default`), which already means "managed, one Space" — verified by logging
/// the value at startup. Writing `Managed` down makes the intent explicit and
/// guards against a future toolkit change, but a window that *does* follow you
/// is almost never doing it through this property. See
/// [`activate_without_raising_all`] for what actually drags a window across
/// desktops.
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
