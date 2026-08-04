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
use tauri::{AppHandle, Emitter, Manager};

/// How often to probe. Frequent enough to notice a stall within a few seconds,
/// rare enough to be invisible next to real traffic.
const PING_EVERY: Duration = Duration::from_secs(4);
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

    fn report(&self) -> QualityReport {
        let stalled = {
            let awaiting = self.awaiting.load(Ordering::Relaxed);
            awaiting != 0 && now_ms() - awaiting as i64 > STALL_AFTER_MS
        };
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
        QualityReport { rtt_ms: rtt, grade }
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
    let mut ticker = tokio::time::interval(PING_EVERY);
    let mut last_grade = "";

    loop {
        ticker.tick().await;

        let quality = app.state::<LinkQuality>();
        if !ws_server::is_connected(&state).await {
            if !last_grade.is_empty() {
                quality.reset();
                last_grade = "";
            }
            continue;
        }

        // Only stamp a new outstanding probe if the previous one came back;
        // otherwise the stall clock would keep resetting and never fire.
        let now = now_ms();
        let _ = quality.awaiting.compare_exchange(
            0,
            now as u64,
            Ordering::Relaxed,
            Ordering::Relaxed,
        );
        ws_server::push(&state, json!({ "type": "ping", "t": now })).await;

        let report = quality.report();
        if report.grade != last_grade {
            last_grade = report.grade;
            let _ = app.emit("link-quality", &report);
        }
    }
}
