//! Absolute brightness of the Mac's built-in display, for the phone's slider.
//!
//! Apple Silicon (and recent Intel) route brightness through the private
//! `DisplayServices` framework. It is loaded with `dlopen`/`dlsym` so a macOS
//! release that drops or renames it degrades to "no slider" (the cap is not
//! advertised and the phone keeps its -/+ buttons) instead of failing to
//! launch. Older Intel Macs fall back to `IODisplayGetFloatParameter`. External
//! displays are not supported: DDC is a separate, flaky world.
//!
//! Wire: phone -> Mac `{"type":"mac-brightness-set","level":0.0..1.0}`; the
//! level rides back in `mac-info` as `brightness` (omitted/null when unknown).
//! Setting is gated exactly like `mac_remote` (`remote_control`, not paused).

use serde_json::Value;

/// Advertised in `welcome.caps` when remote control is on and the built-in
/// display's brightness can be read.
pub const CAP: &str = "macbrightness";

/// Pull the requested level out of a `mac-brightness-set` message, clamped to
/// 0..=1. `None` for a missing, non-numeric or non-finite level.
pub fn parse_set(raw: &Value) -> Option<f32> {
    let v = raw.get("level")?.as_f64()?;
    v.is_finite().then(|| v.clamp(0.0, 1.0) as f32)
}

/// Current built-in display brightness 0..1, or `None` (external only, or no
/// API worked).
pub fn get() -> Option<f32> {
    imp::get().filter(|v| v.is_finite()).map(|v| v.clamp(0.0, 1.0))
}

/// Set the built-in display brightness. Returns whether a backend accepted it.
pub fn set(level: f32) -> bool {
    imp::set(level.clamp(0.0, 1.0))
}

/// Handle one `mac-brightness-set`. Re-checks the remote-control gate itself
/// (like `mac_remote::on_message`), applies the level, shows the HUD, and
/// schedules one trailing `mac-info` push so a drag of N sets costs one echo.
pub fn on_message(app: &crate::host::AppHandle, state: &crate::ws_server::SharedState, raw: &Value) {
    use std::sync::atomic::{AtomicU64, Ordering};
    static GEN: AtomicU64 = AtomicU64::new(0);
    if !crate::mac_remote::enabled(app) {
        eprintln!("[brightness] ignoring set — remote control is off");
        return;
    }
    let Some(level) = parse_set(raw) else { return };
    if !set(level) {
        return;
    }
    let _ = app.emit("mac-hud", serde_json::json!({"kind": "brightness", "level": level, "muted": false}));
    let id = GEN.fetch_add(1, Ordering::SeqCst) + 1;
    let (app, state) = (app.clone(), state.clone());
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(400)).await;
        if GEN.load(Ordering::SeqCst) == id {
            crate::mac_info::push_now(&app, &state).await;
        }
    });
}

#[cfg(target_os = "macos")]
mod imp {
    use std::ffi::{c_void, CString};
    use std::sync::OnceLock;

    type GetFn = unsafe extern "C" fn(u32, *mut f32) -> i32;
    type SetFn = unsafe extern "C" fn(u32, f32) -> i32;

    #[link(name = "CoreGraphics", kind = "framework")]
    extern "C" {
        fn CGGetActiveDisplayList(max: u32, out: *mut u32, count: *mut u32) -> i32;
        fn CGDisplayIsBuiltin(display: u32) -> u32;
    }

    fn symbols() -> Option<(GetFn, SetFn)> {
        static SYMS: OnceLock<Option<(usize, usize)>> = OnceLock::new();
        let s = (*SYMS.get_or_init(|| unsafe {
            let path = CString::new(
                "/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices",
            )
            .ok()?;
            let h = libc::dlopen(path.as_ptr(), libc::RTLD_LAZY);
            if h.is_null() {
                return None;
            }
            let g = libc::dlsym(h, c"DisplayServicesGetBrightness".as_ptr());
            let s = libc::dlsym(h, c"DisplayServicesSetBrightness".as_ptr());
            (!g.is_null() && !s.is_null()).then(|| (g as usize, s as usize))
        }))?;
        // SAFETY: both addresses came from dlsym for functions with these C signatures.
        Some(unsafe { (std::mem::transmute::<usize, GetFn>(s.0), std::mem::transmute::<usize, SetFn>(s.1)) })
    }

    fn builtin_display() -> Option<u32> {
        let mut ids = [0u32; 16];
        let mut n = 0u32;
        // SAFETY: `ids` has room for the 16 entries we say it has.
        unsafe {
            if CGGetActiveDisplayList(16, ids.as_mut_ptr(), &mut n) != 0 {
                return None;
            }
            ids[..n as usize].iter().copied().find(|d| CGDisplayIsBuiltin(*d) != 0)
        }
    }

    pub fn get() -> Option<f32> {
        let d = builtin_display()?;
        if let Some((g, _)) = symbols() {
            let mut v = 0f32;
            // SAFETY: `v` is a writable f32.
            if unsafe { g(d, &mut v) } == 0 {
                return Some(v);
            }
        }
        io::get()
    }

    pub fn set(level: f32) -> bool {
        let Some(d) = builtin_display() else { return false };
        if let Some((_, s)) = symbols() {
            // SAFETY: plain value call.
            if unsafe { s(d, level) } == 0 {
                return true;
            }
        }
        io::set(level)
    }

    /// Intel fallback through IOKit's display parameters.
    mod io {
        use super::*;
        type Io = u32;
        #[link(name = "IOKit", kind = "framework")]
        extern "C" {
            fn IOServiceMatching(name: *const i8) -> *mut c_void;
            fn IOServiceGetMatchingServices(port: u32, matching: *mut c_void, it: *mut Io) -> i32;
            fn IOIteratorNext(it: Io) -> Io;
            fn IOObjectRelease(o: Io) -> i32;
            fn IODisplayGetFloatParameter(s: Io, opts: u32, name: *const c_void, out: *mut f32) -> i32;
            fn IODisplaySetFloatParameter(s: Io, opts: u32, name: *const c_void, v: f32) -> i32;
        }
        #[link(name = "CoreFoundation", kind = "framework")]
        extern "C" {
            fn CFStringCreateWithCString(a: *const c_void, s: *const i8, enc: u32) -> *const c_void;
            fn CFRelease(o: *const c_void);
        }

        fn with_service<R>(f: impl Fn(Io, *const c_void) -> Option<R>) -> Option<R> {
            // SAFETY: standard IOKit iterate/release; the CFString is released after use.
            unsafe {
                let key = CFStringCreateWithCString(std::ptr::null(), c"brightness".as_ptr(), 0x0800_0100);
                let mut it: Io = 0;
                if IOServiceGetMatchingServices(0, IOServiceMatching(c"IODisplayConnect".as_ptr()), &mut it) != 0 {
                    CFRelease(key);
                    return None;
                }
                let mut out = None;
                loop {
                    let s = IOIteratorNext(it);
                    if s == 0 {
                        break;
                    }
                    out = f(s, key);
                    IOObjectRelease(s);
                    if out.is_some() {
                        break;
                    }
                }
                IOObjectRelease(it);
                CFRelease(key);
                out
            }
        }

        pub fn get() -> Option<f32> {
            with_service(|s, k| {
                let mut v = 0f32;
                // SAFETY: `v` writable.
                (unsafe { IODisplayGetFloatParameter(s, 0, k, &mut v) } == 0).then_some(v)
            })
        }

        pub fn set(level: f32) -> bool {
            // SAFETY: plain value call.
            with_service(|s, k| (unsafe { IODisplaySetFloatParameter(s, 0, k, level) } == 0).then_some(())).is_some()
        }
    }
}

#[cfg(not(target_os = "macos"))]
mod imp {
    pub fn get() -> Option<f32> {
        None
    }
    pub fn set(_level: f32) -> bool {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::parse_set;
    use serde_json::json;

    #[test]
    fn level_is_clamped_to_unit_range() {
        assert_eq!(parse_set(&json!({"level": 0.5})), Some(0.5));
        assert_eq!(parse_set(&json!({"level": 7})), Some(1.0));
        assert_eq!(parse_set(&json!({"level": -3.2})), Some(0.0));
        assert_eq!(parse_set(&json!({"level": 1})), Some(1.0));
    }

    #[test]
    fn junk_levels_are_rejected() {
        assert_eq!(parse_set(&json!({})), None);
        assert_eq!(parse_set(&json!({"level": "0.5"})), None);
        assert_eq!(parse_set(&json!({"level": null})), None);
    }
}
