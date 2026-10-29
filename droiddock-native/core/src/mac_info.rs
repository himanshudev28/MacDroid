//! Mac → phone status sync — the direction every other feature in this app
//! runs backwards.
//!
//! AirSync's `MacInfoSyncManager` pushes the Mac's own name and battery to the
//! phone so its Home screen can show both devices side by side. This is the
//! same idea, deliberately kept to the smallest useful payload:
//!
//! ```json
//! {"type":"mac-info","name":"Himanshu's MacBook","battery":76,"charging":false,"hasBattery":true}
//! ```
//!
//! # Why this one is *not* gated like `mac_remote`
//!
//! `mac_remote` moves authority — a phone holding the pairing token can drive
//! the cursor. This moves read-only status, to a device that already completed
//! the handshake and can already see the Mac's name in `welcome`. So it
//! defaults **on** (`Config.mac_info_sync`), matching AirSync, and the only
//! reason to switch it off is preference rather than safety. It still honours
//! the global pause, because "pause" means "stop talking to the phone".
//!
//! # Why a poll rather than an event
//!
//! macOS has no supported push notification for battery level that Tauri
//! surfaces, and `IOPowerSources` would mean a new C-interop dependency for one
//! integer. `pmset -g batt` is a stable, documented, zero-dependency read, and
//! at one call a minute its cost is unmeasurable. The phone is told again on
//! every reconnect, so a missed tick can never leave a stale reading pinned.

use crate::config::Config;
use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::time::Duration;
use crate::host::{AppHandle, Manager};

/// Advertised in `welcome.caps` while the feature is on, so the phone's UI can
/// hide the row entirely rather than render an empty card.
pub const CAP: &str = "macinfo";

/// How often the Mac re-states its status while a phone is linked. Battery
/// moves by single percents over tens of minutes; a faster tick would buy
/// nothing and add a `pmset` fork per interval.
const TICK: Duration = Duration::from_secs(60);

pub fn enabled(app: &AppHandle) -> bool {
    let cfg: Config = app.state::<crate::AppState>().config.lock().unwrap().clone();
    cfg.mac_info_sync && !cfg.is_paused()
}

/// Battery percentage, whether it's on mains, and whether this Mac has an
/// internal battery at all (a Mac mini/Studio has none — the phone should show
/// "on AC", not "0%").
#[derive(Debug, PartialEq)]
pub struct Power {
    pub percent: Option<i64>,
    pub charging: bool,
    pub has_battery: bool,
}

/// Split out from the `pmset` call so the parsing is testable without a shell.
fn parse_pmset(out: &str) -> Power {
    // `Now drawing from 'AC Power'` / `'Battery Power'` is the first line on
    // every macOS version this app supports.
    let charging = out.contains("'AC Power'");
    // ` -InternalBattery-0 (id=…)\t76%; discharging; 3:22 remaining present: true`
    let has_battery = out.contains("InternalBattery");
    let percent = out
        .split_whitespace()
        .find_map(|tok| tok.strip_suffix("%;").or_else(|| tok.strip_suffix('%')))
        .and_then(|n| n.parse::<i64>().ok())
        .filter(|p| (0..=100).contains(p));
    Power { percent, charging, has_battery: has_battery && percent.is_some() }
}

/// Battery level and power source from IOKit, in-process: no `pmset` fork.
/// `None` when IOKit has nothing to say (the caller then falls back to
/// `pmset`).
#[cfg(target_os = "macos")]
fn read_power_iokit() -> Option<Power> {
    use objc2::rc::Retained;
    use objc2::runtime::AnyObject;
    use objc2_foundation::{NSArray, NSDictionary, NSNumber, NSString};
    use std::ffi::c_void;

    #[link(name = "IOKit", kind = "framework")]
    extern "C" {
        fn IOPSCopyPowerSourcesInfo() -> *const c_void;
        fn IOPSCopyPowerSourcesList(blob: *const c_void) -> *const c_void;
        fn IOPSGetPowerSourceDescription(blob: *const c_void, ps: *const c_void) -> *const c_void;
        fn IOPSGetProvidingPowerSourceType(blob: *const c_void) -> *const c_void;
    }

    // SAFETY: the IOPS "Copy" functions return +1 CF objects, which are
    // toll-free bridged to ObjC objects, so `Retained` releases them on drop.
    // The dictionary / string returned by the "Get" functions are borrowed
    // from `info` / `list`, which stay alive for the whole block.
    unsafe {
        let info_ptr = IOPSCopyPowerSourcesInfo();
        let info = Retained::<AnyObject>::from_raw(info_ptr as *mut AnyObject)?;
        let info_ptr = &*info as *const AnyObject as *const c_void;
        let list_ptr = IOPSCopyPowerSourcesList(info_ptr);
        let list = Retained::<AnyObject>::from_raw(list_ptr as *mut AnyObject)?;
        let arr: &NSArray<AnyObject> = &*(&*list as *const AnyObject as *const NSArray<AnyObject>);

        let prov = IOPSGetProvidingPowerSourceType(info_ptr);
        let charging = if prov.is_null() {
            true
        } else {
            let s: &NSString = &*(prov as *const NSString);
            s.to_string() == "AC Power"
        };

        let key = |k: &str| NSString::from_str(k);
        for i in 0..arr.count() {
            let ps = arr.objectAtIndex(i);
            let desc_ptr = IOPSGetPowerSourceDescription(info_ptr, &*ps as *const AnyObject as *const c_void);
            if desc_ptr.is_null() {
                continue;
            }
            let desc: &NSDictionary<NSString, AnyObject> =
                &*(desc_ptr as *const NSDictionary<NSString, AnyObject>);
            let ty = desc
                .objectForKey(&key("Type"))
                .and_then(|o| o.downcast::<NSString>().ok())
                .map(|s| s.to_string());
            if ty.as_deref() != Some("InternalBattery") {
                continue;
            }
            let num = |k: &str| {
                desc.objectForKey(&key(k))
                    .and_then(|o| o.downcast::<NSNumber>().ok())
                    .map(|n| n.intValue() as i64)
            };
            let cur = num("Current Capacity")?;
            let max = num("Max Capacity").filter(|m| *m > 0).unwrap_or(100);
            let percent = (cur * 100 / max).clamp(0, 100);
            return Some(Power { percent: Some(percent), charging, has_battery: true });
        }
        // Powered, with no battery in the list (desktop Mac).
        Some(Power { percent: None, charging, has_battery: false })
    }
}

#[cfg(target_os = "macos")]
async fn read_power() -> Power {
    if let Some(p) = read_power_iokit() {
        return p;
    }
    let out = tokio::process::Command::new("/usr/bin/pmset")
        .args(["-g", "batt"])
        .output()
        .await;
    match out {
        Ok(o) => parse_pmset(&String::from_utf8_lossy(&o.stdout)),
        // A missing/failing pmset is not worth surfacing to the user — the
        // phone just shows the Mac without a battery reading.
        Err(_) => Power { percent: None, charging: true, has_battery: false },
    }
}

#[cfg(not(target_os = "macos"))]
async fn read_power() -> Power {
    Power { percent: None, charging: true, has_battery: false }
}

/// Build the message. Public so a future Settings "test" action can render it
/// without going near the socket.
pub async fn snapshot(app: &AppHandle) -> Value {
    let p = read_power().await;
    json!({
        "type": "mac-info",
        "name": ws_server::mac_name(app),
        "battery": p.percent,
        "charging": p.charging,
        "hasBattery": p.has_battery,
        // Phase 2: seeds the phone's volume slider so it opens where the Mac
        // actually is. Rides this message rather than getting its own, since it
        // is status of exactly the kind this push already carries — and an older
        // phone simply ignores the extra key.
        "volume": crate::mac_remote::output_volume().await,
        // Built-in display brightness 0..1; null on a Mac with only external
        // displays. Older phones ignore the key.
        "brightness": crate::mac_brightness::get(),
    })
}

/// Send one update if there's a phone that asked for it. Silent no-op
/// otherwise — a disabled toggle, a paused app, or a phone build that never
/// advertised the cap all take the same quiet path.
pub async fn push_now(app: &AppHandle, state: &SharedState) {
    if !enabled(app) {
        return;
    }
    if !ws_server::phone_has_cap(state, CAP).await {
        return;
    }
    let _ = ws_server::push(state, snapshot(app).await).await;
}

/// One long-lived ticker for the whole app, spawned on the networking thread
/// alongside the clipboard watcher and the link-quality pinger.
///
/// Deliberately not a task-per-connection: that shape leaks a loop on every
/// reconnect unless each one is tracked and cancelled, and there is nothing
/// here worth that bookkeeping. `push_now` is a cheap no-op when no phone is
/// listening.
pub async fn run(app: AppHandle, state: SharedState) {
    loop {
        // Cheapest of the four loops — a minute apart, and `push_now` bails
        // before the `pmset` fork when nothing is listening. Gated anyway, so
        // that "unlinked means genuinely idle" holds for the whole app rather
        // than for most of it.
        ws_server::await_connected(&state).await;
        tokio::time::sleep(TICK).await;
        push_now(&app, &state).await;
    }
}

#[cfg(test)]
mod tests {
    use super::parse_pmset;

    /// The IOKit read must agree with what `pmset -g batt` prints on this Mac.
    #[test]
    #[cfg(target_os = "macos")]
    fn iokit_power_matches_pmset() {
        let out = std::process::Command::new("/usr/bin/pmset").args(["-g", "batt"]).output().unwrap();
        let want = parse_pmset(&String::from_utf8_lossy(&out.stdout));
        let got = super::read_power_iokit().expect("IOKit power read");
        assert_eq!(got.charging, want.charging);
        assert_eq!(got.has_battery, want.has_battery);
        if let (Some(a), Some(b)) = (got.percent, want.percent) {
            assert!((a - b).abs() <= 1, "iokit {a} vs pmset {b}");
        }
    }

    #[test]
    fn laptop_on_battery() {
        let out = "Now drawing from 'Battery Power'\n \
                   -InternalBattery-0 (id=4653155)\t76%; discharging; 3:22 remaining present: true";
        let p = parse_pmset(out);
        assert_eq!(p.percent, Some(76));
        assert!(!p.charging);
        assert!(p.has_battery);
    }

    #[test]
    fn laptop_charging() {
        let out = "Now drawing from 'AC Power'\n \
                   -InternalBattery-0 (id=4653155)\t100%; charged; 0:00 remaining present: true";
        let p = parse_pmset(out);
        assert_eq!(p.percent, Some(100));
        assert!(p.charging);
        assert!(p.has_battery);
    }

    #[test]
    fn desktop_mac_has_no_battery() {
        // A Mac mini reports the power source and nothing else. Reporting 0%
        // here would be worse than reporting nothing.
        let p = parse_pmset("Now drawing from 'AC Power'\n");
        assert_eq!(p.percent, None);
        assert!(p.charging);
        assert!(!p.has_battery);
    }

    #[test]
    fn garbage_output_never_panics_or_invents_a_level() {
        let p = parse_pmset("pmset: command not found");
        assert_eq!(p.percent, None);
        assert!(!p.has_battery);
        // A percentage outside 0..=100 is a parse artefact, not a reading.
        assert_eq!(parse_pmset("Now drawing from 'AC Power'\n 999%;").percent, None);
    }
}
