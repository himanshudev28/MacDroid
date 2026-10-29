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
use crate::host::{AppHandle, Manager};

/// Advertised in `welcome.caps` only while the feature is enabled.
pub const CAP: &str = "remote";

/// Advertised alongside `remote`: the Mac understands `remote-button`
/// (press-and-hold, so a phone-driven pointer can drag). Additive: a phone that
/// has never heard of it just never sends the message.
pub const CAP_DRAG: &str = "remotedrag";

/// A held mouse button with no phone activity for this long is released.
pub const HOLD_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Btn {
    Left,
    Right,
}

/// What the state machine decided to post. Kept separate from the CGEvent
/// calls so the logic is testable without touching the real pointer.
/// `None` positions mean "wherever the cursor is now".
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Posted {
    Move(f64, f64),
    Drag(Btn, f64, f64),
    Down(Btn, Option<(f64, f64)>),
    Up(Btn, Option<(f64, f64)>),
}

/// Held-button state. At most one button is held; while it is, pointer moves
/// become drag events (`mouseMoved` would not drag a Finder item or select text).
#[derive(Debug, Default)]
pub struct HoldMachine {
    held: Option<Btn>,
    pos: Option<(f64, f64)>,
    last_activity: Option<std::time::Instant>,
}

impl HoldMachine {
    pub fn held(&self) -> Option<Btn> {
        self.held
    }

    pub fn mouse_move(&mut self, x: f64, y: f64, now: std::time::Instant) -> Posted {
        self.pos = Some((x, y));
        match self.held {
            Some(b) => {
                self.last_activity = Some(now);
                Posted::Drag(b, x, y)
            }
            None => Posted::Move(x, y),
        }
    }

    /// `down` while already held only refreshes the timer (the phone's
    /// heartbeat during a drag lock); `up` with nothing held is a no-op.
    pub fn button(&mut self, b: Btn, down: bool, now: std::time::Instant) -> Option<Posted> {
        if down {
            if self.held.is_some() {
                self.last_activity = Some(now);
                return None;
            }
            self.held = Some(b);
            self.last_activity = Some(now);
            Some(Posted::Down(b, self.pos))
        } else {
            match self.held {
                Some(h) if h == b => {
                    self.held = None;
                    self.last_activity = None;
                    Some(Posted::Up(h, self.pos))
                }
                _ => None,
            }
        }
    }

    /// A plain click while a button is held would corrupt the drag.
    pub fn click_allowed(&self) -> bool {
        self.held.is_none()
    }

    /// Release because the link dropped, the app is quitting, or we timed out.
    pub fn release(&mut self) -> Option<Posted> {
        let h = self.held.take()?;
        self.last_activity = None;
        Some(Posted::Up(h, self.pos))
    }

    pub fn release_if_idle(&mut self, now: std::time::Instant) -> Option<Posted> {
        let last = self.last_activity?;
        if self.held.is_some() && now.duration_since(last) >= HOLD_TIMEOUT {
            return self.release();
        }
        None
    }
}

/// Parse `{"type":"remote-button","button":"left"|"right","state":"down"|"up"}`.
pub fn parse_button(raw: &Value) -> Option<(Btn, bool)> {
    let b = match raw.get("button").and_then(Value::as_str).unwrap_or("left") {
        "left" => Btn::Left,
        "right" => Btn::Right,
        _ => return None,
    };
    let down = match raw.get("state").and_then(Value::as_str)? {
        "down" => true,
        "up" => false,
        _ => return None,
    };
    Some((b, down))
}

static HOLD: std::sync::Mutex<Option<HoldMachine>> = std::sync::Mutex::new(None);
static WATCHDOG: std::sync::Once = std::sync::Once::new();

fn with_hold<R>(f: impl FnOnce(&mut HoldMachine) -> R) -> R {
    let mut g = HOLD.lock().unwrap_or_else(|e| e.into_inner());
    f(g.get_or_insert_with(HoldMachine::default))
}

/// Release any held button. Called on WS disconnect and app quit.
pub fn release_all() {
    // Post while holding the lock so a racing move can't interleave.
    let mut g = HOLD.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(p) = g.as_mut().and_then(|m| m.release()) {
        eprintln!("[remote] releasing held button");
        imp::post_ev(p);
    }
}

fn start_watchdog() {
    WATCHDOG.call_once(|| {
        // Plain thread, not tokio: it only ever sleeps and checks a timestamp.
        std::thread::spawn(|| loop {
            std::thread::sleep(std::time::Duration::from_millis(500));
            let mut g = HOLD.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(p) = g.as_mut().and_then(|m| m.release_if_idle(std::time::Instant::now())) {
                eprintln!("[remote] held button timed out, releasing");
                imp::post_ev(p);
            }
        });
    });
}

/// Handle one `remote-button` message. Same gating as `remote`.
pub fn on_button_message(app: &AppHandle, raw: &Value) {
    if !enabled(app) {
        eprintln!("[remote] ignoring button — remote control is off");
        return;
    }
    let Some((b, down)) = parse_button(raw) else { return };
    if down {
        start_watchdog();
    }
    let mut g = HOLD.lock().unwrap_or_else(|e| e.into_inner());
    let m = g.get_or_insert_with(HoldMachine::default);
    if let Some(p) = m.button(b, down, std::time::Instant::now()) {
        imp::post_ev(p);
    }
}

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
                // Held button → drag events; otherwise a plain move.
                let mut g = HOLD.lock().unwrap_or_else(|e| e.into_inner());
                let m = g.get_or_insert_with(HoldMachine::default);
                imp::post_ev(m.mouse_move(x, y, std::time::Instant::now()));
            }
        }
        "mouse_click" => {
            let button = raw.get("button").and_then(Value::as_str).unwrap_or("left");
            if with_hold(|m| m.click_allowed()) {
                imp::mouse_click(button);
            }
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
        "media" => {
            if let Some(name) = raw.get("key").and_then(Value::as_str) {
                if let Some(code) = media_key(name) {
                    imp::media_key(code);
                }
            }
        }
        // ── System actions (Phase 2) ────────────────────────────────────────
        // Each is a named verb with no parameters, deliberately: the phone can
        // ask for "lock", it cannot ask for "run this".
        "lock" => imp::lock_screen(),
        "screensaver" => imp::start_screensaver(),
        "brightness" => {
            // Same HID mechanism as the media keys, so it drives the display
            // the same way the keyboard's own brightness keys do. Relative
            // rather than absolute because setting an absolute level needs
            // private CoreDisplay calls.
            let up = raw.get("dir").and_then(Value::as_str).unwrap_or("up") == "up";
            let steps = num(raw, "steps").unwrap_or(1.0).clamp(1.0, 8.0) as u32;
            for _ in 0..steps {
                imp::media_key(if up { 2 } else { 3 }); // NX_KEYTYPE_BRIGHTNESS_UP / _DOWN
            }
        }
        "volume_set" => {
            if let Some(level) = num(raw, "level") {
                let pct = level.clamp(0.0, 100.0).round() as u8;
                imp::set_output_volume(pct);
                // `set volume` (and a CoreAudio write) never raises macOS's own
                // volume HUD, and a synthetic media key would change the level
                // by a step. So the Mac shell draws the HUD itself.
                let muted = pct == 0 || crate::mac_audio::output_muted().unwrap_or(false);
                let _ = app.emit("mac-hud", serde_json::json!({
                    "kind": "volume", "level": pct as f64 / 100.0, "muted": muted
                }));
            }
        }
        other => eprintln!("[remote] unknown action {other:?}"),
    }
}

/// The Mac's current output volume as 0–100, or `None` if it couldn't be read.
/// Used to seed the phone's slider so it doesn't open at a value the Mac isn't
/// actually at.
///
/// Async because its only caller is the `mac-info` push, which runs on the
/// async runtime — reading this with a blocking `Command::output()` would park
/// a runtime thread for the length of an `osascript` launch on every update.
pub async fn output_volume() -> Option<u8> {
    imp::output_volume().await
}

/// Whether macOS currently trusts this app to synthesise input.
///
/// Worth its own surface because the failure mode is invisible: without the
/// grant `CGEvent::post` succeeds and does nothing, so every remote action
/// silently no-ops and the app looks broken rather than un-permitted. The
/// Settings toggle used to claim macOS "will ask for Accessibility permission
/// the first time" — it never did, because nothing in the app had ever called
/// an API that prompts.
pub fn accessibility_trusted() -> bool {
    imp::accessibility_trusted()
}

/// Open System Settings straight to Privacy & Security → Accessibility.
///
/// Preferred over `AXIsProcessTrustedWithOptions`' built-in prompt: that dialog
/// only ever appears *once* per app (macOS remembers it was shown), so a user
/// who dismissed it can never get it back, and it can't be re-triggered from a
/// Settings row. Sending them to the exact pane works every time.
pub fn open_accessibility_settings() {
    imp::open_accessibility_settings();
}

/// Tear down the Accessibility grant and ask for it again from scratch.
///
/// # Why "already ticked" and "not trusted" are not a contradiction
///
/// TCC does not remember *"the user allowed DroidDock"*. It remembers *"the
/// user allowed the program that satisfies this code-signing requirement"*, and
/// for a bundle signed the way this one is — ad-hoc, no Team ID, no Developer
/// ID — that requirement degrades to an exact hash of the binary. Every rebuild
/// produces a different binary, so every release invalidates the grant while
/// leaving the row in place: System Settings still lists DroidDock, the switch
/// is still on, and `AXIsProcessTrusted` still says no. Unticking and re-ticking
/// often doesn't help either, because the stale row is what's being toggled.
///
/// `tccutil reset` deletes the row outright — including macOS's memory of
/// having already prompted for it — and the `AXIsProcessTrustedWithOptions`
/// call that follows re-registers *this* binary and puts the standard grant
/// alert back on screen. The user then flips one switch and the hash on file is
/// the hash that's running.
///
/// Not something to do quietly on the user's behalf: it revokes a permission
/// they granted. It is wired to a button they press.
pub async fn accessibility_reset(app: AppHandle) -> bool {
    let bundle_id = app.config().identifier.clone();
    imp::reset_accessibility(&bundle_id)
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

/// The media half of the allow-list — `NX_KEYTYPE_*` constants from
/// `IOKit/hidsystem/ev_keymap.h`. These are not virtual key codes and can't be
/// posted as ordinary key events; see `imp::media_key`.
///
/// This is what makes the Mac controllable from the phone's own media widget
/// (AirSync does it through a `media-control` CLI shell-out; posting the HID
/// key directly needs no extra binary and works with whatever app currently
/// holds the system's now-playing session).
fn media_key(name: &str) -> Option<i32> {
    Some(match name {
        "playpause" => 16, // NX_KEYTYPE_PLAY
        "next" => 17,      // NX_KEYTYPE_NEXT
        "prev" => 18,      // NX_KEYTYPE_PREVIOUS
        "volup" => 0,      // NX_KEYTYPE_SOUND_UP
        "voldown" => 1,    // NX_KEYTYPE_SOUND_DOWN
        "mute" => 7,       // NX_KEYTYPE_MUTE
        _ => return None,
    })
}

#[cfg(target_os = "macos")]
mod imp {
    use objc2_core_foundation::{CFRetained, CGPoint};
    use objc2_core_graphics::{
        CGEvent, CGEventFlags, CGEventTapLocation, CGEventType, CGMouseButton, CGScrollEventUnit,
    };

    /// Every synthesised event goes out through here. `CGEvent::post` is a
    /// no-op unless the user granted Accessibility permission, so a missing
    /// grant shows up as "nothing happened" rather than an error.
    fn post(event: Option<CFRetained<CGEvent>>) {
        if let Some(e) = event {
            if dry_run() {
                eprintln!("[remote-dry] {:?}", CGEvent::r#type(Some(&e)));
                return;
            }
            CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&e));
        }
    }

    /// `DD_REMOTE_DRYRUN=1`: log the events instead of posting, so tests never
    /// move the real pointer.
    pub fn dry_run() -> bool {
        static D: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
        *D.get_or_init(|| std::env::var("DD_REMOTE_DRYRUN").map(|v| v == "1").unwrap_or(false))
    }

    pub fn post_ev(p: super::Posted) {
        use super::{Btn, Posted};
        let (dn, up, dr, mb) = (
            |b| if b == Btn::Left { CGEventType::LeftMouseDown } else { CGEventType::RightMouseDown },
            |b| if b == Btn::Left { CGEventType::LeftMouseUp } else { CGEventType::RightMouseUp },
            |b| if b == Btn::Left { CGEventType::LeftMouseDragged } else { CGEventType::RightMouseDragged },
            |b| if b == Btn::Left { CGMouseButton::Left } else { CGMouseButton::Right },
        );
        let at = |p: Option<(f64, f64)>| p.map(|(x, y)| CGPoint::new(x, y)).unwrap_or_else(current_pointer);
        match p {
            Posted::Move(x, y) => post(CGEvent::new_mouse_event(None, CGEventType::MouseMoved, CGPoint::new(x, y), CGMouseButton::Left)),
            Posted::Drag(b, x, y) => post(CGEvent::new_mouse_event(None, dr(b), CGPoint::new(x, y), mb(b))),
            Posted::Down(b, pos) => post(CGEvent::new_mouse_event(None, dn(b), at(pos), mb(b))),
            Posted::Up(b, pos) => post(CGEvent::new_mouse_event(None, up(b), at(pos), mb(b))),
        }
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

    /// Media keys are not virtual key codes — they arrive from the keyboard as
    /// a `NSSystemDefined` event with subtype 8, and `CGEvent::new_keyboard_event`
    /// has no way to express one. The only supported route is to build the
    /// AppKit event and post its `CGEvent` backing, which is what every media
    /// remote on macOS does.
    ///
    /// `data1` packs the key code in the high half and the down/up state in the
    /// low half; `flags` repeats that state. Both halves must agree or the
    /// system ignores the event.
    pub fn media_key(code: i32) {
        use objc2_app_kit::{NSEvent, NSEventModifierFlags, NSEventType};

        for down in [true, false] {
            let state: isize = if down { 0xa } else { 0xb };
            let data1 = ((code as isize) << 16) | (state << 8);
            let event = NSEvent::otherEventWithType_location_modifierFlags_timestamp_windowNumber_context_subtype_data1_data2(
                NSEventType::SystemDefined,
                CGPoint::new(0.0, 0.0),
                NSEventModifierFlags::from_bits_retain((state << 8) as usize),
                0.0,
                0,
                None,
                8, // NX_SUBTYPE_AUX_CONTROL_BUTTONS
                data1,
                -1,
            );
            // Same Accessibility gate as every other synthesised event here:
            // without the grant this posts and nothing happens.
            if let Some(cg) = event.and_then(|e| e.CGEvent()) {
                CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&cg));
            }
        }
    }

    fn current_pointer() -> CGPoint {
        CGEvent::new(None)
            .map(|e| CGEvent::location(Some(&e)))
            .unwrap_or(CGPoint::new(0.0, 0.0))
    }

    /// Lock the screen with ⌘⌃Q — the same shortcut the menu bar offers.
    ///
    /// Chosen over `CGSession -suspend` (which is fast-user-switching, not
    /// locking, and behaves differently with FileVault) and over
    /// `pmset displaysleepnow` (which only locks if the "require password
    /// immediately" preference happens to be set). This is a plain key event
    /// through the path already built above, so it needs no new permission
    /// beyond the Accessibility grant remote control already requires.
    pub fn lock_screen() {
        const KEY_Q: u16 = 12;
        for down in [true, false] {
            if let Some(ev) = CGEvent::new_keyboard_event(None, KEY_Q, down) {
                CGEvent::set_flags(
                    Some(&ev),
                    CGEventFlags::MaskCommand | CGEventFlags::MaskControl,
                );
                post(Some(ev));
            }
        }
    }

    /// Start the screensaver. `ScreenSaverEngine` is a normal app bundle, so
    /// this is a plain launch rather than anything privileged.
    pub fn start_screensaver() {
        let _ = std::process::Command::new("/usr/bin/open")
            .args(["-a", "ScreenSaverEngine"])
            .spawn();
    }

    /// Absolute output volume, 0–100.
    ///
    /// AppleScript rather than CoreAudio: `set volume` drives the same system
    /// output the volume keys do, including the HUD, in one line and with no
    /// device-enumeration edge cases (which is where a CoreAudio version has to
    /// decide what "the" output device is when a headset is plugged in).
    pub fn set_output_volume(level: u8) {
        // In-process CoreAudio first: a slider drag is tens of sets a second and
        // must not fork. AppleScript only for devices with no software volume.
        if crate::mac_audio::set_output_volume_percent(level) {
            return;
        }
        let _ = std::process::Command::new("/usr/bin/osascript")
            .args(["-e", &format!("set volume output volume {level}")])
            .spawn();
    }

    // Declared by hand rather than pulling in objc2-application-services for
    // two predicates. `AXIsProcessTrusted` takes no arguments and never
    // prompts; the `WithOptions` form takes a CFDictionary, which an
    // NSDictionary is (toll-free bridged), so there is still nothing to build
    // by hand.
    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        fn AXIsProcessTrusted() -> bool;
        fn AXIsProcessTrustedWithOptions(options: *const std::ffi::c_void) -> bool;
    }

    pub fn accessibility_trusted() -> bool {
        unsafe { AXIsProcessTrusted() }
    }

    pub fn reset_accessibility(bundle_id: &str) -> bool {
        use objc2_foundation::{NSDictionary, NSNumber, NSString};

        // Blocking on purpose: prompting before the old row is gone would just
        // re-check the stale grant and show nothing.
        let reset = std::process::Command::new("/usr/bin/tccutil")
            .args(["reset", "Accessibility", bundle_id])
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if !reset {
            eprintln!("[remote] tccutil reset Accessibility {bundle_id} failed");
        }

        // The literal rather than the `kAXTrustedCheckOptionPrompt` global:
        // that constant *is* this string, and using it avoids linking a
        // framework global for one dictionary key.
        let key = NSString::from_str("AXTrustedCheckOptionPrompt");
        let options = NSDictionary::from_slices(&[&*key], &[&*NSNumber::new_bool(true)]);

        // SAFETY: NSDictionary is toll-free bridged to CFDictionaryRef, and
        // `options` outlives the call.
        unsafe {
            AXIsProcessTrustedWithOptions(
                objc2::rc::Retained::as_ptr(&options).cast::<std::ffi::c_void>(),
            )
        }
    }

    pub fn open_accessibility_settings() {
        let _ = std::process::Command::new("/usr/bin/open")
            .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
            .spawn();
    }

    pub async fn output_volume() -> Option<u8> {
        // In-process CoreAudio read first; only devices without a software
        // main volume fall through to the AppleScript fork.
        if let Some(v) = crate::mac_audio::output_volume_percent() {
            return Some(v);
        }
        let out = tokio::process::Command::new("/usr/bin/osascript")
            .args(["-e", "output volume of (get volume settings)"])
            .output()
            .await
            .ok()?;
        String::from_utf8_lossy(&out.stdout).trim().parse::<u8>().ok()
    }
}

#[cfg(not(target_os = "macos"))]
mod imp {
    pub fn post_ev(_p: super::Posted) {}
    pub fn mouse_click(_button: &str) {}
    pub fn scroll(_dx: i32, _dy: i32) {}
    pub fn key(_code: u16) {}
    pub fn type_text(_text: &str) {}
    pub fn media_key(_code: i32) {}
    pub fn lock_screen() {}
    pub fn start_screensaver() {}
    pub fn set_output_volume(_level: u8) {}
    pub async fn output_volume() -> Option<u8> { None }
    pub fn accessibility_trusted() -> bool { true }
    pub fn open_accessibility_settings() {}
    pub fn reset_accessibility(_bundle_id: &str) -> bool { true }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    #[test]
    fn parses_button_messages() {
        let j = |s: &str| serde_json::from_str::<Value>(s).unwrap();
        assert_eq!(parse_button(&j(r#"{"type":"remote-button","button":"left","state":"down"}"#)), Some((Btn::Left, true)));
        assert_eq!(parse_button(&j(r#"{"button":"right","state":"up"}"#)), Some((Btn::Right, false)));
        assert_eq!(parse_button(&j(r#"{"state":"down"}"#)), Some((Btn::Left, true)));
        assert_eq!(parse_button(&j(r#"{"button":"middle","state":"down"}"#)), None);
        assert_eq!(parse_button(&j(r#"{"button":"left","state":"sideways"}"#)), None);
        assert_eq!(parse_button(&j(r#"{"button":"left"}"#)), None);
    }

    #[test]
    fn down_drag_up() {
        let t = Instant::now();
        let mut m = HoldMachine::default();
        assert_eq!(m.mouse_move(1.0, 2.0, t), Posted::Move(1.0, 2.0));
        assert_eq!(m.button(Btn::Left, true, t), Some(Posted::Down(Btn::Left, Some((1.0, 2.0)))));
        assert_eq!(m.mouse_move(5.0, 6.0, t), Posted::Drag(Btn::Left, 5.0, 6.0));
        assert!(!m.click_allowed());
        assert_eq!(m.button(Btn::Left, false, t), Some(Posted::Up(Btn::Left, Some((5.0, 6.0)))));
        assert_eq!(m.mouse_move(7.0, 8.0, t), Posted::Move(7.0, 8.0));
        assert!(m.click_allowed());
    }

    #[test]
    fn repeated_and_stray_messages_are_harmless() {
        let t = Instant::now();
        let mut m = HoldMachine::default();
        assert_eq!(m.button(Btn::Left, false, t), None);
        assert!(m.button(Btn::Left, true, t).is_some());
        assert_eq!(m.button(Btn::Left, true, t), None); // heartbeat, no second down
        assert_eq!(m.button(Btn::Right, false, t), None); // wrong button
        assert_eq!(m.held(), Some(Btn::Left));
    }

    #[test]
    fn timeout_releases_and_heartbeat_defers_it() {
        let t = Instant::now();
        let mut m = HoldMachine::default();
        assert_eq!(m.release_if_idle(t + HOLD_TIMEOUT * 2), None); // nothing held
        m.button(Btn::Left, true, t);
        assert_eq!(m.release_if_idle(t + Duration::from_secs(29)), None);
        m.button(Btn::Left, true, t + Duration::from_secs(29)); // heartbeat
        assert_eq!(m.release_if_idle(t + Duration::from_secs(40)), None);
        assert_eq!(m.release_if_idle(t + Duration::from_secs(60)), Some(Posted::Up(Btn::Left, None)));
        assert_eq!(m.held(), None);
    }

    #[test]
    fn disconnect_release_is_once() {
        let t = Instant::now();
        let mut m = HoldMachine::default();
        m.button(Btn::Right, true, t);
        assert_eq!(m.release(), Some(Posted::Up(Btn::Right, None)));
        assert_eq!(m.release(), None);
    }

    #[test]
    fn only_allow_listed_media_keys_resolve() {
        assert_eq!(media_key("playpause"), Some(16));
        assert_eq!(media_key("mute"), Some(7));
        // The media vocabulary must stay as closed as the key one: an
        // arbitrary NX code from the phone would reach hardware functions
        // (eject, brightness, illumination) this feature never offered.
        assert_eq!(media_key("eject"), None);
        assert_eq!(media_key("16"), None);
        assert_eq!(media_key(""), None);
    }

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
