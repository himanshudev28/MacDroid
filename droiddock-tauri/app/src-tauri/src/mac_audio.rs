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
}
