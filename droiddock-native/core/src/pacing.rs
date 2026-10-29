//! Shared pacing state for the background tickers: whether the Mac UI is being
//! looked at, how to back off a retry loop, and the wake-ups that let a parked
//! loop resume the moment something relevant happens.
//!
//! Everything here is Mac-local. Nothing in this module touches the wire
//! protocol with the phone.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::sync::Notify;

static ACTIVE: AtomicBool = AtomicBool::new(true);
static ACTIVE_CHANGED: Notify = Notify::const_new();
static RECONNECT_POKE: Notify = Notify::const_new();
static SETTINGS_CHANGED: Notify = Notify::const_new();
static PAUSE_CHANGED: Notify = Notify::const_new();

/// Whether the host UI is visible / the app is frontmost. Defaults to `true`
/// until the host says otherwise, so a host that never calls
/// `app_set_active` keeps the old cadence.
pub fn is_active() -> bool {
    ACTIVE.load(Ordering::Relaxed)
}

/// Called by the host (`app_set_active`). Going active also nudges the adb
/// reconnect scheduler: someone is looking, so try now rather than at the end
/// of a long back-off.
pub fn set_active(active: bool) {
    let was = ACTIVE.swap(active, Ordering::Relaxed);
    if was != active {
        ACTIVE_CHANGED.notify_waiters();
        if active {
            poke_reconnect();
        }
    }
}

/// Resolves the next time the active flag flips.
pub async fn active_changed() {
    ACTIVE_CHANGED.notified().await
}

/// Reset the adb reconnect back-off and retry soon: network change, WS
/// connect, user action.
pub fn poke_reconnect() {
    RECONNECT_POKE.notify_one();
}

pub async fn reconnect_poked() {
    RECONNECT_POKE.notified().await
}

/// Any `set_setting` / caps change: parked loops that were waiting for a
/// toggle to flip re-evaluate.
pub fn poke_settings() {
    SETTINGS_CHANGED.notify_one();
}

pub async fn settings_changed() {
    SETTINGS_CHANGED.notified().await
}

pub fn poke_pause() {
    PAUSE_CHANGED.notify_one();
}

pub async fn pause_changed() {
    PAUSE_CHANGED.notified().await
}

/// Exponential back-off: `base`, `2*base`, `4*base`, … capped at `max`.
#[derive(Debug, Clone)]
pub struct Backoff {
    base: Duration,
    max: Duration,
    failures: u32,
}

impl Backoff {
    pub const fn new(base: Duration, max: Duration) -> Self {
        Self { base, max, failures: 0 }
    }

    /// The delay to wait before the next attempt.
    pub fn delay(&self) -> Duration {
        let shift = self.failures.min(20);
        self.base
            .checked_mul(1u32 << shift)
            .map_or(self.max, |d| d.min(self.max))
    }

    /// An attempt failed (or found nothing): wait longer next time.
    pub fn fail(&mut self) {
        self.failures = self.failures.saturating_add(1);
    }

    pub fn reset(&mut self) {
        self.failures = 0;
    }

    #[cfg(test)]
    pub fn failures(&self) -> u32 {
        self.failures
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: fn(u64) -> Duration = Duration::from_secs;

    #[test]
    fn backs_off_exponentially_and_caps() {
        let mut b = Backoff::new(S(10), S(300));
        let seq: Vec<u64> = (0..7)
            .map(|_| {
                let d = b.delay().as_secs();
                b.fail();
                d
            })
            .collect();
        assert_eq!(seq, vec![10, 20, 40, 80, 160, 300, 300]);
    }

    #[test]
    fn reset_returns_to_base() {
        let mut b = Backoff::new(S(2), S(60));
        for _ in 0..10 {
            b.fail();
        }
        assert_eq!(b.delay(), S(60));
        b.reset();
        assert_eq!(b.delay(), S(2));
        assert_eq!(b.failures(), 0);
    }

    #[test]
    fn huge_failure_counts_do_not_overflow() {
        let mut b = Backoff::new(S(10), S(300));
        for _ in 0..10_000 {
            b.fail();
        }
        assert_eq!(b.delay(), S(300));
    }

    #[test]
    fn active_flag_round_trips() {
        set_active(false);
        assert!(!is_active());
        set_active(true);
        assert!(is_active());
    }
}
