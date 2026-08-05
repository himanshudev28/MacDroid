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

/// Keep the main window on the Space it was opened on.
///
/// # Why this is needed at all
///
/// A window that follows you to every desktop is doing so because its
/// `collectionBehavior` carries `CanJoinAllSpaces` (it exists on all of them)
/// or `MoveToActiveSpace` (it is dragged to whichever one you switch to).
/// Neither is a behaviour this app asks for — only the status widget and the
/// menu-bar panel want it, and they set it on their own windows. The main
/// window inherits it from the window the toolkit hands us, and the symptom is
/// exactly what a user reports as "it appears on the new desktop for a moment
/// and then goes": the window is pulled across, then macOS re-resolves which
/// Space owns it.
///
/// `Managed` is the normal document-window behaviour — participates in
/// Exposé, belongs to one Space. `FullScreenPrimary` is kept so the green
/// button still offers full screen; dropping it silently disables that.
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
            "[window] collectionBehavior {:#x} → {:#x} (was following Spaces)",
            before.0, wanted.0
        );
        ns.setCollectionBehavior(wanted);
    }
}

#[cfg(not(target_os = "macos"))]
pub fn pin_to_own_space(_window: &tauri::WebviewWindow) {}
