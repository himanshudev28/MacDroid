//! One question, asked without spawning anything: *is sound actually coming out
//! of this Mac right now?*
//!
//! # Why this exists
//!
//! [`crate::mac_media`] can read a real play/pause state out of Music, Spotify
//! and the other scriptable players, but that covers a minority of what people
//! actually watch. A YouTube tab exposes its title and URL over AppleScript and
//! nothing else — there is no "is this video playing" property — so the phone's
//! transport button had no way to know it had been paused on the Mac, and said
//! "playing" forever.
//!
//! CoreAudio answers that for *every* app at once.
//! `kAudioDevicePropertyDeviceIsRunningSomewhere` on the default output device
//! is true while any process is feeding it samples, which is as close to "the
//! Mac is playing something" as the public APIs get.
//!
//! # Why it is read rather than subscribed to
//!
//! `AudioObjectAddPropertyListener` would push changes instead of being polled,
//! but this is a two-integer property read against an in-process CoreAudio
//! cache — microseconds, no fork, no IPC. The tick that calls it was already
//! running. A listener would add a C callback and its lifetime for no
//! measurable saving.
//!
//! What it *does* save is large: `mac_media` used to fork `osascript` twice
//! every three seconds for the whole time a phone was linked. Now that fork
//! only happens when this bit changes or a slow heartbeat comes due, so an idle
//! Mac does a few reads a minute instead of forty process launches.
//!
//! # What it cannot tell you
//!
//! Which app is making the sound. A notification chime, a game, or this app's
//! own phone-audio playback all read as "running" — see `mac_media`'s handling
//! for the one case that matters.

/// `Some(true)`/`Some(false)` when the default output device could be read,
/// `None` when it couldn't (no output device, or a CoreAudio error). `None`
/// means "don't know", and callers must not read it as "not playing".
pub fn output_active() -> Option<bool> {
    imp::output_active()
}

/// The default output device's main volume as 0-100 (what AppleScript's
/// `output volume of (get volume settings)` returns), read in-process.
/// `None` when the device has no software main volume (some HDMI/DisplayPort
/// outputs) — the caller falls back to AppleScript.
pub fn output_volume_percent() -> Option<u8> {
    imp::output_volume_percent()
}

/// Set the default output device's main volume (0-100) in-process. `false`
/// when the device has no settable software volume — the caller falls back to
/// AppleScript. Also clears mute for a non-zero level, like the volume keys.
pub fn set_output_volume_percent(level: u8) -> bool {
    imp::set_output_volume_percent(level)
}

/// Whether the default output is muted. `None` when unknown.
pub fn output_muted() -> Option<bool> {
    imp::output_muted()
}

#[cfg(target_os = "macos")]
mod imp {
    use std::ffi::c_void;

    /// `AudioObjectPropertyAddress` from `<CoreAudio/AudioHardware.h>`. Declared
    /// by hand rather than pulling in a CoreAudio binding crate for one
    /// property read — the same call this file's `mac_remote` neighbour makes
    /// for `AXIsProcessTrusted`.
    #[repr(C)]
    #[derive(Clone, Copy)]
    struct PropertyAddress {
        selector: u32,
        scope: u32,
        element: u32,
    }

    /// The four-character codes CoreAudio uses as selectors. `u32::from_be_bytes`
    /// spells them the way the headers do, so they can be checked by eye.
    const SYSTEM_OBJECT: u32 = 1; // kAudioObjectSystemObject
    const DEFAULT_OUTPUT: u32 = u32::from_be_bytes(*b"dOut"); // kAudioHardwarePropertyDefaultOutputDevice
    const RUNNING_SOMEWHERE: u32 = u32::from_be_bytes(*b"gone"); // kAudioDevicePropertyDeviceIsRunningSomewhere
    const SCOPE_GLOBAL: u32 = u32::from_be_bytes(*b"glob"); // kAudioObjectPropertyScopeGlobal
    const ELEMENT_MAIN: u32 = 0; // kAudioObjectPropertyElementMain

    #[link(name = "CoreAudio", kind = "framework")]
    extern "C" {
        fn AudioObjectSetPropertyData(
            object: u32,
            address: *const PropertyAddress,
            qualifier_size: u32,
            qualifier: *const c_void,
            size: u32,
            data: *const c_void,
        ) -> i32;
        fn AudioObjectGetPropertyData(
            object: u32,
            address: *const PropertyAddress,
            qualifier_size: u32,
            qualifier: *const c_void,
            io_size: *mut u32,
            out_data: *mut c_void,
        ) -> i32;
    }

    /// Read one fixed-size property. Returns false on any CoreAudio error, and
    /// also when the framework wrote a different number of bytes than `T` — at
    /// which point `out` holds nothing meaningful and must not be used.
    fn read<T>(object: u32, selector: u32, out: &mut T) -> bool {
        let address = PropertyAddress {
            selector,
            scope: SCOPE_GLOBAL,
            element: ELEMENT_MAIN,
        };
        let mut size = std::mem::size_of::<T>() as u32;
        // SAFETY: `address` and `size` outlive the call, and `out` is a valid,
        // writable `T` whose size is what we told CoreAudio the buffer is.
        let status = unsafe {
            AudioObjectGetPropertyData(
                object,
                &address,
                0,
                std::ptr::null(),
                &mut size,
                (out as *mut T).cast::<c_void>(),
            )
        };
        status == 0 && size as usize == std::mem::size_of::<T>()
    }

    const SCOPE_OUTPUT: u32 = u32::from_be_bytes(*b"outp"); // kAudioObjectPropertyScopeOutput
    const VIRTUAL_MAIN_VOLUME: u32 = u32::from_be_bytes(*b"vmvc"); // kAudioHardwareServiceDeviceProperty_VirtualMainVolume

    pub fn output_volume_percent() -> Option<u8> {
        let mut device: u32 = 0;
        if !read(SYSTEM_OBJECT, DEFAULT_OUTPUT, &mut device) || device == 0 {
            return None;
        }
        let address = PropertyAddress {
            selector: VIRTUAL_MAIN_VOLUME,
            scope: SCOPE_OUTPUT,
            element: ELEMENT_MAIN,
        };
        let mut vol: f32 = 0.0;
        let mut size = std::mem::size_of::<f32>() as u32;
        // SAFETY: as in `read`; `vol` is a writable f32 and `size` says so.
        let status = unsafe {
            AudioObjectGetPropertyData(
                device,
                &address,
                0,
                std::ptr::null(),
                &mut size,
                (&mut vol as *mut f32).cast::<c_void>(),
            )
        };
        if status != 0 || size as usize != std::mem::size_of::<f32>() || !vol.is_finite() {
            return None;
        }
        Some((vol.clamp(0.0, 1.0) * 100.0).round() as u8)
    }

    const MUTE: u32 = u32::from_be_bytes(*b"mute"); // kAudioDevicePropertyMute

    fn default_device() -> Option<u32> {
        let mut device: u32 = 0;
        (read(SYSTEM_OBJECT, DEFAULT_OUTPUT, &mut device) && device != 0).then_some(device)
    }

    fn set_prop<T>(device: u32, selector: u32, value: &T) -> bool {
        let address = PropertyAddress { selector, scope: SCOPE_OUTPUT, element: ELEMENT_MAIN };
        // SAFETY: `value` is a valid `T` and the size passed is its size.
        let status = unsafe {
            AudioObjectSetPropertyData(
                device,
                &address,
                0,
                std::ptr::null(),
                std::mem::size_of::<T>() as u32,
                (value as *const T).cast::<c_void>(),
            )
        };
        status == 0
    }

    pub fn set_output_volume_percent(level: u8) -> bool {
        let Some(device) = default_device() else { return false };
        let vol = (level.min(100) as f32) / 100.0;
        if !set_prop(device, VIRTUAL_MAIN_VOLUME, &vol) {
            return false;
        }
        if level > 0 {
            let _ = set_prop(device, MUTE, &0u32); // best effort: many devices have no mute
        }
        true
    }

    pub fn output_muted() -> Option<bool> {
        let device = default_device()?;
        let address = PropertyAddress { selector: MUTE, scope: SCOPE_OUTPUT, element: ELEMENT_MAIN };
        let mut m: u32 = 0;
        let mut size = 4u32;
        // SAFETY: `m` is a writable u32 and `size` says so.
        let status = unsafe {
            AudioObjectGetPropertyData(device, &address, 0, std::ptr::null(), &mut size, (&mut m as *mut u32).cast())
        };
        (status == 0 && size == 4).then_some(m != 0)
    }

    pub fn output_active() -> Option<bool> {
        let mut device: u32 = 0;
        // 0 is `kAudioObjectUnknown` — a Mac with no output device at all,
        // which is "don't know" rather than "silent".
        if !read(SYSTEM_OBJECT, DEFAULT_OUTPUT, &mut device) || device == 0 {
            return None;
        }
        // The property is a `UInt32` used as a boolean, not a C `bool`.
        let mut running: u32 = 0;
        if !read(device, RUNNING_SOMEWHERE, &mut running) {
            return None;
        }
        Some(running != 0)
    }
}

#[cfg(not(target_os = "macos"))]
mod imp {
    pub fn output_active() -> Option<bool> {
        None
    }
    pub fn output_volume_percent() -> Option<u8> {
        None
    }
    pub fn set_output_volume_percent(_level: u8) -> bool {
        false
    }
    pub fn output_muted() -> Option<bool> {
        None
    }
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    /// A real read against real CoreAudio. The failure this guards is a wrong
    /// four-character selector or a struct laid out differently from the
    /// header: both compile fine and both return an error status forever, which
    /// would silently pin [`super::output_active`] at `None` and take the
    /// play/pause state for browsers down with it.
    #[test]
    fn the_default_output_device_can_actually_be_read() {
        assert!(
            super::output_active().is_some(),
            "CoreAudio refused the property read — selector or struct layout is wrong"
        );
    }

    /// Must agree with AppleScript's `output volume`, which `mac_info` used to
    /// fork for every minute.
    #[test]
    fn volume_matches_applescript() {
        let Some(v) = super::output_volume_percent() else { return };
        let out = std::process::Command::new("/usr/bin/osascript")
            .args(["-e", "output volume of (get volume settings)"])
            .output()
            .unwrap();
        let want: i32 = String::from_utf8_lossy(&out.stdout).trim().parse().unwrap();
        assert!((v as i32 - want).abs() <= 1, "coreaudio {v} vs osascript {want}");
    }
}
