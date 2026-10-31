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
/// Epoch-ms of the last frame of any kind from the phone.
static LAST_INBOUND_MS: AtomicI64 = AtomicI64::new(0);

/// Record that the phone just sent something. Called from the read loop.
pub fn note_inbound() {
    LAST_INBOUND_MS.store(now_ms(), Ordering::Relaxed);
}

/// Pure stall rule, two tiers:
/// - nothing at all from the phone for `STALL_AFTER_MS` since the probe went
///   out: the link is gone both ways;
/// - the phone keeps talking but our probe has had no echo for
///   `ONE_WAY_STALL_MS`: the Mac->phone direction is dead. Inbound traffic
///   only buys time here, it never clears the probe: a busy phone (video,
///   a big transfer) can queue the `pong` behind its own frames, which is
///   what made the link flap when any late echo counted as stalled.
fn is_stalled(awaiting_since: i64, last_inbound_ms: i64, now: i64) -> bool {
    awaiting_since != 0
        && (now - awaiting_since.max(last_inbound_ms) > STALL_AFTER_MS
            || now - awaiting_since > ONE_WAY_STALL_MS)
}

/// How long the loop may sleep. While a probe is outstanding and not yet
/// stalled, wake right when it would become stalled instead of on the next
/// cadence tick, so "stalled" shows up within ~`STALL_AFTER_MS` of the probe
/// rather than up to a whole interval later. Reads memory only; once the grade
/// is stalled (or nothing is outstanding) the normal cadence applies, so an
/// idle link keeps zero timers beyond the usual probe sleep.
fn next_wait(awaiting_since: i64, last_inbound_ms: i64, now: i64, stalled: bool, normal: Duration) -> Duration {
    if awaiting_since == 0 || stalled {
        return normal;
    }
    let deadline = (awaiting_since.max(last_inbound_ms) + STALL_AFTER_MS)
        .min(awaiting_since + ONE_WAY_STALL_MS);
    let left = (deadline + 50 - now).max(100) as u64;
    normal.min(Duration::from_millis(left))
}

fn ping_interval(active: bool) -> Duration {
    if active { PING_EVERY } else { PING_EVERY_INACTIVE }
}
/// No echo and no other inbound frame within this long and the link is treated
/// as stalled, socket or no.
const STALL_AFTER_MS: i64 = 12_000;
/// No echo within this long while the phone keeps sending: the Mac->phone
/// direction is dead. Generous, because a pong can wait behind a busy stream.
const ONE_WAY_STALL_MS: i64 = 30_000;

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
            let since = app.state::<LinkQuality>().awaiting.load(Ordering::Relaxed) as i64;
            let stalled = last_grade == "stalled";
            let normal = if since != 0 || stalled {
                PING_EVERY
            } else {
                ping_interval(crate::pacing::is_active() || last_transport == Some("ble"))
            };
            let wait = next_wait(since, LAST_INBOUND_MS.load(Ordering::Relaxed), now_ms(), stalled, normal);
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
        // While a BLE link exists the Wi-Fi socket is probed at the active cadence
        // even when the app is hidden: that is what decides the handback (§6).
        let active = crate::pacing::is_active() || snap.ble_live;
        let cadence_due = now - last_sent_ms >= ping_interval(active).as_millis() as i64 - 500;
        if cadence_due {
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
    fn stall_needs_an_unanswered_probe() {
        assert!(!is_stalled(0, 0, 1_000_000));
        assert!(!is_stalled(1_000, 0, 1_000 + STALL_AFTER_MS));
        assert!(is_stalled(1_000, 0, 1_001 + STALL_AFTER_MS));
    }

    #[test]
    fn inbound_traffic_delays_the_stall_but_never_clears_it() {
        let sent = 1_000;
        let now = sent + STALL_AFTER_MS + 5_000;
        assert!(is_stalled(sent, 0, now));
        assert!(!is_stalled(sent, now - 2_000, now));
        // old traffic from before the probe does not help
        assert!(is_stalled(sent, 500, now));
        // the phone keeps talking but never echoes: the Mac->phone side is dead
        let late = sent + ONE_WAY_STALL_MS + 1;
        assert!(is_stalled(sent, late - 100, late));
    }

    #[test]
    fn next_wait_lands_on_the_stall_deadline() {
        let normal = Duration::from_secs(4);
        // nothing outstanding / already stalled: normal cadence
        assert_eq!(next_wait(0, 0, 5_000, false, normal), normal);
        assert_eq!(next_wait(1_000, 0, 50_000, true, normal), normal);
        // probe sent 10 s ago, phone silent: wake in ~2 s, not 4
        let w = next_wait(1_000, 0, 11_000, false, normal);
        assert!(w >= Duration::from_millis(1_900) && w <= Duration::from_millis(2_200), "{w:?}");
        // fresh probe: the cadence is shorter than the deadline
        assert_eq!(next_wait(1_000, 0, 1_100, false, normal), normal);
        // past the deadline: short re-check, never zero (no busy loop)
        assert_eq!(next_wait(1_000, 0, 13_500, false, normal), Duration::from_millis(100));
        // phone busy: the one-way deadline caps the wait
        let w = next_wait(1_000, 30_000, 30_000, false, normal);
        assert!(w <= Duration::from_millis(1_100), "{w:?}");
    }

    #[test]
    fn stall_with_no_echo_is_detected_even_when_the_phone_keeps_talking() {
        let q = LinkQuality::default();
        q.awaiting.store((now_ms() - ONE_WAY_STALL_MS - 1_000) as u64, Ordering::Relaxed);
        note_inbound();
        assert_eq!(q.report().grade, "stalled");
        q.on_pong(&json!({ "type": "pong" }));
        assert_ne!(q.report().grade, "stalled");
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
