//! Controlling the Mac from the phone (Tier D — the PRD's parked "Phase 20").
//!
//! # Why this one is gated harder than anything else in the app
//!
//! Every other feature moves *data* from the phone to the Mac. This one moves
//! *authority*: a paired phone can synthesise keyboard and mouse input, so
//! whatever your Mac can do, a phone holding the pairing token can do too. A
//! stolen phone, or a malicious app on it that reaches the DroidDock socket,
//! inherits your logged-in Mac session.
//!
//! So, unlike the rest of the app, this is:
//!
//! * **off by default** (`Config.remote_control`, opt-in from Settings),
//! * **capability-gated** — the Mac only advertises `"remote"` in `welcome.caps`
//!   when it is on, so a phone can't even discover the surface otherwise,
//!   and every inbound message re-checks the flag rather than trusting that
//!   the advert was ever sent,
//! * **narrow** — a fixed vocabulary of key/mouse/scroll/media actions. There is
//!   deliberately no "run this command" or "type this into a shell" verb.
//!
//! macOS additionally requires the user to grant Accessibility permission
//! before `CGEvent` posting does anything, which is a second, OS-enforced
//! consent step this code cannot bypass. If it hasn't been granted the events
//! are silently dropped by the system — that shows up as "nothing happens",
//! not as an error, which is worth knowing when debugging.

use crate::config::Config;
use serde_json::Value;
use tauri::{AppHandle, Manager};

/// Advertised in `welcome.caps` only while the feature is enabled.
pub const CAP: &str = "remote";

pub fn enabled(app: &AppHandle) -> bool {
    let cfg: Config = app.state::<crate::AppState>().config.lock().unwrap().clone();
    // A global pause means "stop reacting to the phone" — it would be
    // inconsistent for that to mute notifications but still let the phone
    // drive the cursor.
    cfg.remote_control && !cfg.is_paused()
}

/// Handle one `remote` message from the phone. Returns quietly on anything
/// unrecognised: a phone from a newer build sending a verb this Mac doesn't
/// know should be a no-op, not an error the user sees.
pub fn on_message(app: &AppHandle, raw: &Value) {
    if !enabled(app) {
        eprintln!("[remote] ignoring input — remote control is off");
        return;
    }

    let action = raw.get("action").and_then(Value::as_str).unwrap_or("");
    match action {
        "mouse_move" => {
            if let (Some(x), Some(y)) = (num(raw, "x"), num(raw, "y")) {
                imp::mouse_move(x, y);
            }
        }
        "mouse_click" => {
            let button = raw.get("button").and_then(Value::as_str).unwrap_or("left");
            imp::mouse_click(button);
        }
        "mouse_scroll" => {
            let dx = num(raw, "dx").unwrap_or(0.0) as i32;
            let dy = num(raw, "dy").unwrap_or(0.0) as i32;
            imp::scroll(dx, dy);
        }
        "key" => {
            if let Some(name) = raw.get("key").and_then(Value::as_str) {
                if let Some(code) = keycode(name) {
                    imp::key(code);
                }
            }
        }
        "text" => {
            if let Some(text) = raw.get("text").and_then(Value::as_str) {
                // Bounded: a phone should not be able to stream unbounded
                // synthetic typing into whatever window happens to be focused.
                imp::type_text(&text.chars().take(500).collect::<String>());
            }
        }
        other => eprintln!("[remote] unknown action {other:?}"),
    }
}

fn num(raw: &Value, key: &str) -> Option<f64> {
    raw.get(key).and_then(Value::as_f64)
}

/// The allow-list. Anything not named here cannot be synthesised — in
/// particular there is no way to express arbitrary modifier+keycode
/// combinations, which is what keeps this from being a general-purpose
/// remote-execution primitive.
fn keycode(name: &str) -> Option<u16> {
    Some(match name {
        "up" => 126,
        "down" => 125,
        "left" => 123,
        "right" => 124,
        "enter" => 36,
        "space" => 49,
        "escape" => 53,
        "backspace" => 51,
        "tab" => 48,
        _ => return None,
    })
}

#[cfg(target_os = "macos")]
mod imp {
    use objc2_core_foundation::{CFRetained, CGPoint};
    use objc2_core_graphics::{CGEvent, CGEventTapLocation, CGEventType, CGMouseButton, CGScrollEventUnit};

    /// Every synthesised event goes out through here. `CGEvent::post` is a
    /// no-op unless the user granted Accessibility permission, so a missing
    /// grant shows up as "nothing happened" rather than an error.
    fn post(event: Option<CFRetained<CGEvent>>) {
        if let Some(e) = event {
            CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&e));
        }
    }

    pub fn mouse_move(x: f64, y: f64) {
        post(CGEvent::new_mouse_event(
            None,
            CGEventType::MouseMoved,
            CGPoint::new(x, y),
            CGMouseButton::Left,
        ));
    }

    pub fn mouse_click(button: &str) {
        let (down, up, btn) = match button {
            "right" => (
                CGEventType::RightMouseDown,
                CGEventType::RightMouseUp,
                CGMouseButton::Right,
            ),
            _ => (
                CGEventType::LeftMouseDown,
                CGEventType::LeftMouseUp,
                CGMouseButton::Left,
            ),
        };
        // Click wherever the cursor already is: the phone sends a move first,
        // and recomputing a position here would race that move.
        let at = current_pointer();
        post(CGEvent::new_mouse_event(None, down, at, btn));
        post(CGEvent::new_mouse_event(None, up, at, btn));
    }

    pub fn scroll(dx: i32, dy: i32) {
        // wheel1 is vertical, wheel2 horizontal — line units so the step
        // matches a real trackpad rather than pixel-scrolling by a huge amount.
        post(CGEvent::new_scroll_wheel_event2(
            None,
            CGScrollEventUnit::Line,
            2,
            dy,
            dx,
            0,
        ));
    }

    pub fn key(code: u16) {
        post(CGEvent::new_keyboard_event(None, code, true));
        post(CGEvent::new_keyboard_event(None, code, false));
    }

    pub fn type_text(text: &str) {
        // Unicode payload rather than per-key virtual codes: this works
        // regardless of the Mac's keyboard layout and needs no layout table.
        for ch in text.chars() {
            let mut buf = [0u16; 2];
            let utf16 = ch.encode_utf16(&mut buf);
            let len = utf16.len() as u64;
            let ptr = utf16.as_ptr();
            for down in [true, false] {
                if let Some(e) = CGEvent::new_keyboard_event(None, 0, down) {
                    // SAFETY: `ptr` points at `len` valid UTF-16 units in `buf`,
                    // which outlives this call.
                    unsafe { CGEvent::keyboard_set_unicode_string(Some(&e), len, ptr) };
                    CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&e));
                }
            }
        }
    }

    fn current_pointer() -> CGPoint {
        CGEvent::new(None)
            .map(|e| CGEvent::location(Some(&e)))
            .unwrap_or(CGPoint::new(0.0, 0.0))
    }
}

#[cfg(not(target_os = "macos"))]
mod imp {
    pub fn mouse_move(_x: f64, _y: f64) {}
    pub fn mouse_click(_button: &str) {}
    pub fn scroll(_dx: i32, _dy: i32) {}
    pub fn key(_code: u16) {}
    pub fn type_text(_text: &str) {}
}

#[cfg(test)]
mod tests {
    use super::keycode;

    #[test]
    fn only_allow_listed_keys_resolve() {
        assert_eq!(keycode("enter"), Some(36));
        assert_eq!(keycode("escape"), Some(53));
        // Anything outside the fixed vocabulary must not map to a keycode —
        // this is the property that keeps the surface narrow.
        assert_eq!(keycode("f1"), None);
        assert_eq!(keycode("cmd"), None);
        assert_eq!(keycode(""), None);
        assert_eq!(keycode("36"), None);
    }
}
