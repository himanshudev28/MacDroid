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
