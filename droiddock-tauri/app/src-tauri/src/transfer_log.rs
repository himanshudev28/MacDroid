//! The Transfers page's record of every file moving between the phone and the
//! Mac, whichever feature moved it.
//!
//! Each feature that moves files calls `start`, then `progress` as bytes (or
//! items) go by, then `finish`. The log works out speed and time left, and
//! pushes a snapshot of the whole list to the UI as the `transfers` event, at
//! most four times a second. The page also asks for the list on open
//! (`transfers_list`), so nothing that happened while it was closed is lost.
//!
//! Kept in memory only: the list starts empty on every launch (the user's
//! choice). Finished entries are capped at `KEEP_FINISHED`.

use serde::Serialize;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};

/// Finished entries kept for the session; the oldest go first.
const KEEP_FINISHED: usize = 100;
/// Finder reads smaller than this aren't kept once finished (see `finish`).
const FINDER_KEEP_MIN: u64 = 10 * 1024 * 1024;
/// The fastest the UI is updated. Four a second reads as live.
const EMIT_EVERY: Duration = Duration::from_millis(250);
/// Speed is re-measured over windows at least this long, so one burst of
/// buffered chunks doesn't swing the number.
const SAMPLE_EVERY: Duration = Duration::from_millis(500);
/// How much each new measurement moves the shown speed (0..1). Low enough to
/// stay steady, high enough to follow a real change within a few seconds.
const SMOOTHING: f64 = 0.3;

#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Dir {
    ToPhone,
    ToMac,
}

/// Which feature started the transfer. The page labels the less obvious ones.
#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Source {
    /// The Files or Photos page, including opening a full-size photo.
    Files,
    /// The phone's Share menu → DroidDock.
    Share,
    /// The phone browsing and downloading the Mac's files.
    MacFiles,
    QuickShare,
    PhotoSync,
    /// Copies through the "DroidDock Phone" volume in Finder.
    Finder,
}

#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Unit {
    Bytes,
    /// Photo sync counts photos, not bytes.
    Items,
}

#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Status {
    Active,
    Done,
    Failed,
    Cancelled,
}

/// Who cancelled, when someone did. A transfer can also end in "cancelled"
/// because the phone disconnected (`TransferRegistry::abort_all`); with no
/// one recorded here, that is reported as a lost connection instead.
#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum CancelledBy {
    Mac,
    Phone,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub key: u64,
    pub name: String,
    pub dir: Dir,
    pub source: Source,
    pub unit: Unit,
    pub total: u64,
    pub done: u64,
    pub status: Status,
    pub error: Option<String>,
    pub cancelled_by: Option<CancelledBy>,
    /// Unix ms.
    pub started_ms: u64,
    /// Unix ms of the last progress, so the page can tell a stalled transfer.
    pub updated_ms: u64,
    pub ended_ms: Option<u64>,
    /// Units per second: smoothed while active, the average once finished.
    pub speed: f64,
    /// Seconds left, once there's a speed to estimate from.
    pub eta_secs: Option<f64>,
    /// Where a received file was saved, for Show in Finder and Open.
    pub path: Option<String>,
    /// Whether the page should offer Cancel.
    pub cancellable: bool,
    /// The id `transfer::cancel` takes. Not sent to the page.
    #[serde(skip)]
    cancel_id: Option<u32>,
    #[serde(skip)]
    started: Instant,
    #[serde(skip)]
    sample_at: Instant,
    #[serde(skip)]
    sample_done: u64,
}

/// What a finished transfer produced.
pub enum Outcome {
    Done { path: Option<PathBuf> },
    /// Finished, but not everything made it — photo sync skipping a photo
    /// it will retry next time. Shown as done, with the note.
    Partial { path: Option<PathBuf>, note: String },
    Failed(String),
}

impl<E: Into<String>> From<Result<Option<PathBuf>, E>> for Outcome {
    fn from(r: Result<Option<PathBuf>, E>) -> Self {
        match r {
            Ok(path) => Outcome::Done { path },
            Err(e) => Outcome::Failed(e.into()),
        }
    }
}

pub struct NewTransfer {
    pub name: String,
    pub dir: Dir,
    pub source: Source,
    pub unit: Unit,
    pub total: u64,
    /// The id `transfer::cancel` takes, for transfers that can be cancelled.
    pub cancel_id: Option<u32>,
}

#[derive(Default)]
pub struct TransferLog {
    entries: Mutex<Vec<Entry>>,
    next_key: Mutex<u64>,
    emit_pending: AtomicBool,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// A transfer being tracked. Call [`Tracked::finish`] when it ends.
///
/// If it is dropped without that — the task holding it was cancelled, or a
/// request future was dropped mid-transfer (a Finder copy cancelled halfway
/// through the WebDAV volume does exactly this) — the row is closed as
/// interrupted, instead of staying "active" until the app quits.
pub struct Tracked {
    app: AppHandle,
    key: u64,
    finished: bool,
}

impl Tracked {
    pub fn key(&self) -> u64 {
        self.key
    }

    pub fn finish(mut self, outcome: Outcome) {
        self.finished = true;
        finish(&self.app, self.key, outcome);
    }
}

impl Drop for Tracked {
    fn drop(&mut self) {
        if !self.finished {
            finish(&self.app, self.key, Outcome::Failed("Interrupted".into()));
        }
    }
}

/// Start tracking a transfer.
pub fn start(app: &AppHandle, t: NewTransfer) -> Tracked {
    let log = app.state::<TransferLog>();
    let key = {
        let mut k = log.next_key.lock().unwrap();
        *k += 1;
        *k
    };
    let now = Instant::now();
    let ms = now_ms();
    log.entries.lock().unwrap().push(Entry {
        key,
        name: t.name,
        dir: t.dir,
        source: t.source,
        unit: t.unit,
        total: t.total,
        done: 0,
        status: Status::Active,
        error: None,
        cancelled_by: None,
        started_ms: ms,
        updated_ms: ms,
        ended_ms: None,
        speed: 0.0,
        eta_secs: None,
        path: None,
        cancellable: t.cancel_id.is_some(),
        cancel_id: t.cancel_id,
        started: now,
        sample_at: now,
        sample_done: 0,
    });
    changed(app);
    Tracked { app: app.clone(), key, finished: false }
}

/// Record how much is done so far. Cheap enough to call per chunk.
pub fn progress(app: &AppHandle, key: u64, done: u64) {
    {
        let log = app.state::<TransferLog>();
        let mut entries = log.entries.lock().unwrap();
        let Some(e) = entries.iter_mut().find(|e| e.key == key && e.status == Status::Active) else {
            return;
        };
        e.done = done;
        e.updated_ms = now_ms();
        let now = Instant::now();
        let window = now.duration_since(e.sample_at);
        if window >= SAMPLE_EVERY {
            let rate = done.saturating_sub(e.sample_done) as f64 / window.as_secs_f64();
            e.speed = if e.speed == 0.0 { rate } else { SMOOTHING * rate + (1.0 - SMOOTHING) * e.speed };
            e.sample_at = now;
            e.sample_done = done;
        }
        e.eta_secs = (e.speed > 0.0 && e.total > 0)
            .then(|| e.total.saturating_sub(done) as f64 / e.speed);
    }
    changed(app);
}

/// Note who cancelled a transfer, before it ends, by the id
/// `transfer::cancel` takes. Applies to every active transfer with that id —
/// the phone's ids can repeat across directions, which is how `transfer::cancel`
/// itself treats them.
pub fn note_cancel(app: &AppHandle, cancel_id: u32, by: CancelledBy) {
    let log = app.state::<TransferLog>();
    for e in log.entries.lock().unwrap().iter_mut() {
        if e.status == Status::Active && e.cancel_id == Some(cancel_id) && e.cancelled_by.is_none() {
            e.cancelled_by = Some(by);
        }
    }
}

/// Record how a transfer ended. Private: callers go through
/// [`Tracked::finish`], so every row is closed exactly once.
fn finish(app: &AppHandle, key: u64, outcome: Outcome) {
    {
        let log = app.state::<TransferLog>();
        let mut entries = log.entries.lock().unwrap();
        let Some(e) = entries.iter_mut().find(|e| e.key == key && e.status == Status::Active) else {
            return;
        };
        let elapsed = e.started.elapsed().as_secs_f64();
        e.ended_ms = Some(now_ms());
        e.eta_secs = None;
        match outcome {
            Outcome::Partial { path, note } => {
                e.status = Status::Done;
                e.error = Some(note);
                e.path = path.map(|p| p.to_string_lossy().to_string());
            }
            Outcome::Done { path } => {
                e.status = Status::Done;
                // Some senders don't know the size up front; a finished
                // transfer is complete by definition.
                if e.total == 0 || e.done < e.total {
                    e.total = e.total.max(e.done);
                    e.done = e.total;
                }
                e.path = path.map(|p| p.to_string_lossy().to_string());
            }
            Outcome::Failed(err) if err == "cancelled" => {
                if e.cancelled_by.is_some() {
                    e.status = Status::Cancelled;
                } else {
                    // Nobody pressed Cancel: the link dropped and
                    // `abort_all` cancelled everything in flight.
                    e.status = Status::Failed;
                    e.error = Some("Connection to the phone was lost".into());
                }
            }
            Outcome::Failed(err) => {
                e.status = Status::Failed;
                e.error = Some(err);
            }
        }
        e.speed = if elapsed > 0.0 { e.done as f64 / elapsed } else { 0.0 };

        // Finder reads every file it previews (icons, Quick Look) through the
        // phone volume, so browsing a folder of photos would bury real
        // history under dozens of these. Small successful ones still show
        // while they run, but aren't kept once done.
        let finder_noise = e.source == Source::Finder
            && e.dir == Dir::ToMac
            && e.status == Status::Done
            && e.total < FINDER_KEEP_MIN;
        if finder_noise {
            entries.retain(|x| x.key != key);
        }

        // Cap the finished list, oldest first.
        let finished = entries.iter().filter(|e| e.status != Status::Active).count();
        if finished > KEEP_FINISHED {
            let mut drop = finished - KEEP_FINISHED;
            entries.retain(|e| {
                if drop > 0 && e.status != Status::Active {
                    drop -= 1;
                    false
                } else {
                    true
                }
            });
        }
    }
    changed(app);
}

/// The id to pass to `transfer::cancel`, if this transfer can be cancelled.
fn cancel_id(app: &AppHandle, key: u64) -> Option<u32> {
    let log = app.state::<TransferLog>();
    let entries = log.entries.lock().unwrap();
    entries
        .iter()
        .find(|e| e.key == key && e.status == Status::Active)
        .and_then(|e| e.cancel_id)
}

fn path_of(app: &AppHandle, key: u64) -> Option<String> {
    let log = app.state::<TransferLog>();
    let entries = log.entries.lock().unwrap();
    entries.iter().find(|e| e.key == key).and_then(|e| e.path.clone())
}

fn snapshot(app: &AppHandle) -> Vec<Entry> {
    app.state::<TransferLog>().entries.lock().unwrap().clone()
}

/// Schedule a snapshot for the UI, at most every `EMIT_EVERY`. A change
/// during the wait is picked up by that same snapshot, and the flag is cleared
/// before the snapshot is taken, so a later change schedules another: the
/// page always ends on the latest state.
fn changed(app: &AppHandle) {
    let log = app.state::<TransferLog>();
    if log.emit_pending.swap(true, Ordering::SeqCst) {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(EMIT_EVERY).await;
        app.state::<TransferLog>().emit_pending.store(false, Ordering::SeqCst);
        let _ = app.emit("transfers", snapshot(&app));
    });
}

// ── Commands ─────────────────────────────────────────────────────────────

#[tauri::command]
pub fn transfers_list(app: AppHandle) -> Vec<Entry> {
    snapshot(&app)
}

/// Remove finished entries. Active ones stay.
#[tauri::command]
pub fn transfers_clear(app: AppHandle) {
    app.state::<TransferLog>()
        .entries
        .lock()
        .unwrap()
        .retain(|e| e.status == Status::Active);
    changed(&app);
}

#[tauri::command]
pub async fn transfer_cancel(
    app: AppHandle,
    ws: tauri::State<'_, crate::ws_server::SharedState>,
    key: u64,
) -> Result<(), String> {
    let id = cancel_id(&app, key).ok_or("This transfer can't be cancelled")?;
    note_cancel(&app, id, CancelledBy::Mac);
    crate::transfer::cancel(&ws, id).await;
    Ok(())
}

/// Show a received file in Finder. Only paths this log recorded are opened —
/// the page passes a key, never a path.
#[tauri::command]
pub async fn transfer_reveal(app: AppHandle, key: u64) -> Result<(), String> {
    open_saved(&app, key, true).await
}

#[tauri::command]
pub async fn transfer_open(app: AppHandle, key: u64) -> Result<(), String> {
    open_saved(&app, key, false).await
}

/// File types that run code when opened. Received files carry no quarantine
/// flag, so `open` on one of these would run it with no Gatekeeper prompt —
/// and Quick Share accepts files from anyone nearby. Open refuses them; Show
/// in Finder still works. Kept in step with `RUNNABLE` in TransfersView.tsx.
const RUNNABLE: &[&str] = &[
    "app", "command", "terminal", "tool", "sh", "zsh", "bash", "csh", "py", "rb", "pl",
    "pkg", "mpkg", "dmg", "jar", "scpt", "scptd", "applescript", "workflow", "action",
    "prefpane", "kext", "dylib", "webloc", "inetloc", "fileloc", "url",
];

fn is_runnable(path: &std::path::Path) -> bool {
    let by_type = path
        .extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| RUNNABLE.contains(&e.to_ascii_lowercase().as_str()));
    // Anything marked executable counts too, whatever it's called.
    #[cfg(unix)]
    let by_mode = {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(path).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    };
    #[cfg(not(unix))]
    let by_mode = false;
    by_type || by_mode
}

async fn open_saved(app: &AppHandle, key: u64, reveal: bool) -> Result<(), String> {
    let path = path_of(app, key).ok_or("No saved file for this transfer")?;
    if !std::path::Path::new(&path).exists() {
        return Err("The file has been moved or deleted".into());
    }
    if !reveal && is_runnable(std::path::Path::new(&path)) {
        return Err("This file can run code, so it isn't opened from here. Use Show in Finder.".into());
    }
    let mut cmd = tokio::process::Command::new("/usr/bin/open");
    if reveal {
        cmd.arg("-R");
    }
    let status = cmd.arg(&path).status().await.map_err(|e| e.to_string())?;
    if status.success() {
        Ok(())
    } else {
        Err("macOS couldn't open the file".into())
    }
}

#[cfg(test)]
mod tests {
    use super::is_runnable;
    use std::path::Path;

    /// Open must refuse anything that runs code — received files carry no
    /// quarantine flag, so macOS wouldn't warn first.
    #[test]
    fn files_that_run_code_are_refused_whatever_the_case() {
        for name in ["run.command", "x.TERMINAL", "Setup.pkg", "a.sh", "Tool.app", "link.webloc"] {
            assert!(is_runnable(Path::new(name)), "{name} should be refused");
        }
    }

    #[test]
    fn ordinary_files_still_open() {
        for name in ["IMG_2041.jpg", "trip.mp4", "report.pdf", "notes.txt", "no-extension"] {
            assert!(!is_runnable(Path::new(name)), "{name} should open");
        }
    }
}
