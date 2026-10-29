//! Link quality (Tier C).
//!
//! Until now "connected" was binary: the socket is open or it isn't. But a
//! socket stays open across a Wi-Fi roam, a sleeping phone, or a congested
//! channel long after the link stops being usable — which is exactly when the
//! UI should say something, and exactly when it said nothing.
//!
//! So: a JSON `ping` carrying a Mac timestamp every few seconds, echoed back by
//! the phone as `pong` with the same `t`. Round-trip time is measured from the
//! echo, smoothed with an EMA so one unlucky sample doesn't flip the badge, and
//! published as a coarse grade the UI can render without knowing about
//! milliseconds.
//!
//! Deliberately *not* OkHttp's protocol-level ping: that one is answered by the
//! phone's TCP stack whether or not the app is actually processing messages. A
//! JSON round-trip proves the app's read loop is alive, which is the thing we
//! actually want to know.

use crate::ws_server::{self, SharedState};
use serde::Serialize;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::time::Duration;
use crate::host::{AppHandle, Emitter, Manager};

/// How often to probe. Frequent enough to notice a stall within a few seconds,
/// rare enough to be invisible next to real traffic.
const PING_EVERY: Duration = Duration::from_secs(4);
/// Probe cadence while the Mac UI is hidden / the app is in the background:
/// nobody is looking at the badge, so the radio gets to sleep. Stall detection
/// keeps its semantics (an unanswered probe is stalled after `STALL_AFTER_MS`);
/// it just starts later. Going active again probes at once.
const PING_EVERY_INACTIVE: Duration = Duration::from_secs(20);
/// A probe is skipped when any frame arrived this recently: that traffic is
/// itself proof the phone's side is alive, so the ping would add a radio wake
/// for no information. Never skipped while a probe is already outstanding —
/// that one must be allowed to stall.
const FRESH_INBOUND_MS: i64 = 3_000;

/// Epoch-ms of the last frame of any kind from the phone.
static LAST_INBOUND_MS: AtomicI64 = AtomicI64::new(0);

/// Record that the phone just sent something. Called from the read loop.
pub fn note_inbound() {
    LAST_INBOUND_MS.store(now_ms(), Ordering::Relaxed);
}

/// Pure decision, separated for testing: should this tick send a probe?
fn should_probe(awaiting: bool, last_inbound_ms: i64, now: i64) -> bool {
    awaiting || now - last_inbound_ms >= FRESH_INBOUND_MS
}

/// Pure stall rule: a probe is outstanding AND neither its echo nor any other
/// inbound frame has shown up for `STALL_AFTER_MS` since it was sent. Inbound
/// traffic of any kind proves the phone is alive, so it restarts the clock.
fn is_stalled(awaiting_since: i64, last_inbound_ms: i64, now: i64) -> bool {
    awaiting_since != 0 && now - awaiting_since.max(last_inbound_ms) > STALL_AFTER_MS
}

fn ping_interval(active: bool) -> Duration {
    if active { PING_EVERY } else { PING_EVERY_INACTIVE }
}
/// No echo within this long and the link is treated as stalled, socket or no.
const STALL_AFTER_MS: i64 = 12_000;

/// EMA weight for each new sample. Low enough to ride out a single spike.
const ALPHA: f64 = 0.3;

const FAIR_MS: f64 = 250.0;
const WEAK_MS: f64 = 900.0;

#[derive(Default)]
pub struct LinkQuality {
    /// Smoothed RTT in milliseconds, ×1000 so it fits an integer atomic.
    /// -1 means "no sample yet".
    ema_micros: AtomicI64,
    /// Epoch-ms of the most recent echo.
    last_pong_ms: AtomicI64,
    /// Epoch-ms of the outstanding probe, or 0 when none is in flight.
    awaiting: AtomicU64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct QualityReport {
    /// Smoothed round-trip time, or null before the first echo.
    pub rtt_ms: Option<f64>,
    /// `good` | `fair` | `weak` | `stalled`.
    pub grade: &'static str,
    /// `"wifi"` | `"ble"` — which link the numbers describe. Additive.
    pub transport: Option<&'static str>,
}

impl LinkQuality {
    /// A `pong` came back. `t` is the timestamp the Mac put in the ping, so the
    /// phone never needs a synchronised clock — it just echoes the number.
    pub fn on_pong(&self, raw: &Value) {
        let now = now_ms();
        self.last_pong_ms.store(now, Ordering::Relaxed);
        self.awaiting.store(0, Ordering::Relaxed);

        let Some(sent) = raw.get("t").and_then(Value::as_i64) else {
            return;
        };
        // A clock change mid-flight (NTP step, sleep/wake) can make this
        // negative; that sample is meaningless rather than "instant".
        let rtt = (now - sent) as f64;
        if !(0.0..60_000.0).contains(&rtt) {
            return;
        }

        let prev = self.ema_micros.load(Ordering::Relaxed);
        let next = if prev < 0 {
            rtt
        } else {
            ALPHA * rtt + (1.0 - ALPHA) * (prev as f64 / 1000.0)
        };
        self.ema_micros
            .store((next * 1000.0) as i64, Ordering::Relaxed);
    }

    /// Forget everything — called on connect and disconnect so a new phone
    /// never inherits the previous one's timings.
    pub fn reset(&self) {
        self.ema_micros.store(-1, Ordering::Relaxed);
        self.last_pong_ms.store(0, Ordering::Relaxed);
        self.awaiting.store(0, Ordering::Relaxed);
    }

    /// True when a probe went unanswered long enough to call the link stalled.
    pub fn is_stalled(&self) -> bool {
        self.report().grade == "stalled"
    }

    /// RTT sample from a BLE CTL PING/PONG. Feeds the smoothed value only; it
    /// must not clear a pending Wi-Fi probe.
    pub fn on_ble_rtt(&self, rtt: f64) {
        let prev = self.ema_micros.load(Ordering::Relaxed);
        let next = if prev < 0 { rtt } else { ALPHA * rtt + (1.0 - ALPHA) * (prev as f64 / 1000.0) };
        self.ema_micros.store((next * 1000.0) as i64, Ordering::Relaxed);
    }

    pub fn reset_for_new_link(&self) {
        self.reset();
    }

    fn report(&self) -> QualityReport {
        let stalled = is_stalled(
            self.awaiting.load(Ordering::Relaxed) as i64,
            LAST_INBOUND_MS.load(Ordering::Relaxed),
            now_ms(),
        );
        let ema = self.ema_micros.load(Ordering::Relaxed);
        let rtt = if ema < 0 { None } else { Some(ema as f64 / 1000.0) };

        let grade = if stalled {
            "stalled"
        } else {
            match rtt {
                None => "good", // no sample yet — don't cry wolf on connect
                Some(ms) if ms < FAIR_MS => "good",
                Some(ms) if ms < WEAK_MS => "fair",
                Some(_) => "weak",
            }
        };
        QualityReport { rtt_ms: rtt, grade, transport: None }
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Probe loop, for the app's lifetime. Silent while nothing is linked.
pub async fn run(app: AppHandle, state: SharedState) {
    let mut last_grade = "";
    let mut last_transport: Option<&'static str> = None;
    let mut first = true;
    let mut last_sent_ms: i64 = 0;

    loop {
        if !first {
            // Sleep one cadence, but wake early when the host becomes active
            // so a returning user gets a fresh reading immediately. The
            // interval is re-read every loop, so a flip applies at once.
            // While a probe is outstanding (or the grade is stalled) re-check
            // every PING_EVERY so recovery shows within seconds; this only
            // reads memory, the radio is untouched.
            let pending = last_grade == "stalled"
                || app.state::<LinkQuality>().awaiting.load(Ordering::Relaxed) != 0;
            let wait = if pending { PING_EVERY } else { ping_interval(crate::pacing::is_active() || last_transport == Some("ble")) };
            tokio::select! {
                _ = tokio::time::sleep(wait) => {}
                _ = crate::pacing::active_changed() => {}
            }
        }
        first = false;

        let quality = app.state::<LinkQuality>();
        if !ws_server::is_connected(&state).await {
            if !last_grade.is_empty() {
                quality.reset();
                last_grade = "";
            }
            // Nothing to probe and nothing that can change until a phone shows
            // up, so wait for that rather than for the next tick. The reset
            // above still runs first, on the one tick that observes the drop.
            ws_server::await_connected(&state).await;
            continue;
        }

        // BLE fallback: with no Wi-Fi socket there is nothing to probe over JSON;
        // the BLE session has its own CTL ping and feeds `on_ble_rtt`.
        let snap = ws_server::link_snapshot(&state).await;
        let transport = ws_server::transport(&state).await;
        if !snap.ws_up {
            let mut report = quality.report();
            if report.grade == "stalled" {
                report.grade = "good";
            }
            report.transport = transport;
            if report.grade != last_grade || transport != last_transport {
                last_grade = report.grade;
                last_transport = transport;
                let _ = app.emit("link-quality", &report);
            }
            // Re-check slowly; BLE liveness is decided by the hub, not here.
            tokio::time::sleep(ping_interval(false)).await;
            continue;
        }

        let now = now_ms();
        let awaiting = quality.awaiting.load(Ordering::Relaxed) != 0;
        // While a BLE link exists the Wi-Fi socket is probed at the active cadence
        // even when the app is hidden: that is what decides the handback (§6).
        let active = crate::pacing::is_active() || snap.ble_live;
        let cadence_due = now - last_sent_ms >= ping_interval(active).as_millis() as i64 - 500;
        if cadence_due && should_probe(awaiting, LAST_INBOUND_MS.load(Ordering::Relaxed), now) {
            last_sent_ms = now;
            // Only stamp a new outstanding probe if the previous one came back;
            // otherwise the stall clock would keep resetting and never fire.
            let _ = quality.awaiting.compare_exchange(
                0,
                now as u64,
                Ordering::Relaxed,
                Ordering::Relaxed,
            );
            ws_server::push_probe(&state, json!({ "type": "ping", "t": now })).await;
        }

        let mut report = quality.report();
        report.transport = transport;
        if report.grade != last_grade || transport != last_transport {
            if report.grade != last_grade {
                // The BLE policy treats "stalled" as "lost"; wake it now.
                crate::ble_policy::poke();
            }
            last_grade = report.grade;
            last_transport = transport;
            if std::env::var_os("DD_TRACE_LINK").is_some() {
                eprintln!("[link] {} grade={} rtt={:?}", now_ms(), report.grade, report.rtt_ms);
            }
            let _ = app.emit("link-quality", &report);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skips_probe_when_traffic_is_fresh() {
        assert!(!should_probe(false, 10_000, 11_000));
        assert!(should_probe(false, 10_000, 13_000));
        assert!(should_probe(false, 0, 13_000));
    }

    #[test]
    fn outstanding_probe_is_never_skipped_so_stall_detection_survives() {
        assert!(should_probe(true, 10_000, 10_100));
    }

    #[test]
    fn stall_needs_an_unanswered_probe() {
        assert!(!is_stalled(0, 0, 1_000_000));
        assert!(!is_stalled(1_000, 0, 1_000 + STALL_AFTER_MS));
        assert!(is_stalled(1_000, 0, 1_001 + STALL_AFTER_MS));
    }

    #[test]
    fn inbound_traffic_resets_the_stall_clock() {
        let sent = 1_000;
        let now = sent + STALL_AFTER_MS + 5_000;
        assert!(is_stalled(sent, 0, now));
        assert!(!is_stalled(sent, now - 2_000, now));
        // old traffic from before the probe does not help
        assert!(is_stalled(sent, 500, now));
    }

    #[test]
    fn new_link_clears_a_stale_outstanding_probe() {
        let q = LinkQuality::default();
        q.awaiting.store(1, Ordering::Relaxed);
        q.reset_for_new_link();
        assert_eq!(q.awaiting.load(Ordering::Relaxed), 0);
        assert_ne!(q.report().grade, "stalled");
    }

    #[test]
    fn inactive_cadence_is_slower() {
        assert_eq!(ping_interval(true), Duration::from_secs(4));
        assert!(ping_interval(false) >= Duration::from_secs(15));
        assert!(ping_interval(false) <= Duration::from_secs(30));
    }
}
