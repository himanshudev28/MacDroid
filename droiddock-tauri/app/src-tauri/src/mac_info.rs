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
use tauri::{AppHandle, Manager};

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

#[cfg(target_os = "macos")]
async fn read_power() -> Power {
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
        tokio::time::sleep(TICK).await;
        push_now(&app, &state).await;
    }
}

#[cfg(test)]
mod tests {
    use super::parse_pmset;

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
