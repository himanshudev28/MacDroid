//! Phase 18: photo auto-sync.
//!
//! The phone tracks no cursor at all (see `BridgeService.kt`'s doc comment) —
//! `photos-changed` is just a doorbell. All the diffing lives here: every
//! sync pass re-lists the phone's full photo/video library through the
//! *existing* `photos-list` request (same one `PhotosView.tsx` uses) and
//! diffs it against a small on-disk ledger of already-synced MediaStore ids,
//! so a missed message (offline, race, whatever) can never cause a permanent
//! gap — the next pass (reconnect or manual backfill) just re-diffs from
//! scratch.

use crate::transfer;
use crate::ws_server::{self, SharedState};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::Mutex;

const LEDGER_FILE: &str = "photo-sync-ledger.json";
/// Page size for the `photos-list` walk — matches the limit `PhotosView.tsx`
/// already pages with, so this reuses a request shape the phone already knows.
const PAGE: u32 = 500;

/// Keyed by the phone's `hello.name` (see `ws_server::PhoneHandle::name`) —
/// the only per-phone identifier available on every connection, ADB-paired
/// or not. This means a re-paired *different* phone (or a factory-reset
/// phone whose MediaStore ids restarted low) can never collide with another
/// device's already-synced ids. `#[serde(default)]` so an old pre-this-fix
/// ledger file (flat `{"synced_ids": [...]}`, no device split) deserializes
/// as empty rather than erroring — its entries are lost, causing a one-time
/// re-download of already-present files, never silent data loss.
#[derive(Default, Serialize, Deserialize)]
struct Ledger {
    #[serde(default)]
    by_device: HashMap<String, HashSet<i64>>,
}

impl Ledger {
    /// Move a device's synced-id set from an old key to a new one.
    ///
    /// Returns whether anything moved, so the caller only rewrites the file
    /// when it must. No-ops when the destination already exists (migration has
    /// run) or the source doesn't (nothing to move), which makes it safe to
    /// call on every sync pass.
    fn migrate_key(&mut self, from: &str, to: &str) -> bool {
        if from == to || self.by_device.contains_key(to) {
            return false;
        }
        match self.by_device.remove(from) {
            Some(ids) => {
                self.by_device.insert(to.to_string(), ids);
                true
            }
            None => false,
        }
    }
}

pub struct PhotoSyncState {
    ledger_path: PathBuf,
    ledger: Mutex<Ledger>,
    /// Guards against overlapping passes — a rapid-fire `photos-changed`, a
    /// reconnect race, or a manual backfill kicked off mid-auto-sync all
    /// share this single flag, exactly like `edit_cache::EditCacheState
    /// ::retrying`.
    running: AtomicBool,
}

pub type PhotoSync = Arc<PhotoSyncState>;

/// Load (or create) the ledger under `data_dir` — same app data dir
/// `droiddock.json` lives in, just a second small file alongside it.
pub fn init(data_dir: PathBuf) -> PhotoSync {
    let _ = std::fs::create_dir_all(&data_dir);
    let ledger_path = data_dir.join(LEDGER_FILE);
    let ledger = load_ledger(&ledger_path);
    Arc::new(PhotoSyncState {
        ledger_path,
        ledger: Mutex::new(ledger),
        running: AtomicBool::new(false),
    })
}

fn load_ledger(path: &Path) -> Ledger {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

/// Write-to-temp-then-rename, same atomic-landing pattern as
/// `edit_cache::save_manifest` — a crash mid-write can never truncate the
/// ledger, and this is called once per completed item (not batched at the
/// end of a run) so a crash mid-batch loses no already-recorded progress.
fn save_ledger(path: &Path, ledger: &Ledger) {
    let Ok(raw) = serde_json::to_string_pretty(ledger) else { return };
    let tmp = path.with_file_name(format!("{LEDGER_FILE}.tmp"));
    if std::fs::write(&tmp, raw).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

impl PhotoSyncState {
    async fn mark_synced(&self, device_key: &str, id: i64) {
        let mut l = self.ledger.lock().await;
        l.by_device.entry(device_key.to_string()).or_default().insert(id);
        save_ledger(&self.ledger_path, &l);
    }

    async fn already_synced(&self, device_key: &str) -> HashSet<i64> {
        self.ledger.lock().await.by_device.get(device_key).cloned().unwrap_or_default()
    }

    /// One-time migration for ledgers written before the key changed from the
    /// phone's display name to its persisted device id.
    ///
    /// Without this, the first sync after that change sees an unknown key, finds
    /// nothing "already synced", and re-downloads the entire library — landing
    /// every photo a second time as `name (2).jpg`, since `unique_dest` never
    /// overwrites. Moves the entries rather than copying so it can only run once.
    async fn migrate_key(&self, from: &str, to: &str) {
        let mut l = self.ledger.lock().await;
        if l.migrate_key(from, to) {
            eprintln!("[photo-sync] migrated ledger key {from:?} → device id");
            save_ledger(&self.ledger_path, &l);
        }
    }
}

#[derive(Clone, Deserialize)]
struct MediaItem {
    id: i64,
    name: String,
    #[serde(default)]
    path: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncProgress {
    done: u32,
    total: u32,
    name: Option<String>,
    error: Option<String>,
}

fn emit_progress(app: &AppHandle, done: u32, total: u32, name: Option<String>, error: Option<String>) {
    // Distinct event name from `transfer-progress` on purpose — FilesView.tsx's
    // consumer filters that stream by `dir` for an unrelated purpose, and
    // conflating the two would either confuse it or need a fake `dir` value.
    let _ = app.emit("photosync-progress", SyncProgress { done, total, name, error });
}

fn json_map(v: Value) -> Map<String, Value> {
    match v {
        Value::Object(m) => m,
        _ => Map::new(),
    }
}

/// Page through the *existing* `photos-list` request until a short page ends
/// the walk. No new wire message — same request `photos_list` (the Tauri
/// command backing the Photos tab) already issues.
async fn fetch_all_items(state: &SharedState) -> Result<Vec<MediaItem>, String> {
    let mut all = Vec::new();
    let mut offset: u32 = 0;
    loop {
        let reply = ws_server::request_default(
            state,
            json_map(json!({ "type": "photos-list", "offset": offset, "limit": PAGE })),
        )
        .await?;
        let items: Vec<MediaItem> = reply
            .get("items")
            .cloned()
            .and_then(|v| serde_json::from_value(v).ok())
            .unwrap_or_default();
        let got = items.len();
        all.extend(items);
        if got < PAGE as usize {
            break;
        }
        offset += PAGE;
    }
    Ok(all)
}

/// A dest path for `file_name` under `dest_dir` that never collides with an
/// existing file — same "insert (2), (3), …" scheme as `adb.rs`'s (unrelated,
/// dead-code) `unique_dest`, copied here rather than shared since that one is
/// module-private and this is the only other place that needs the behavior.
fn unique_dest(dest_dir: &Path, file_name: &str) -> PathBuf {
    let path = Path::new(file_name);
    let ext = path.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    let stem = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| file_name.to_string());
    let mut candidate = dest_dir.join(file_name);
    let mut i = 2;
    while candidate.exists() {
        candidate = dest_dir.join(format!("{stem} ({i}){ext}"));
        i += 1;
    }
    candidate
}

/// `photo_sync_dest` if the user set one, else the lazily-resolved default —
/// resolved fresh on every call rather than cached/eagerly stored in config,
/// so flipping the setting takes effect on the very next sync pass.
pub fn resolve_dest(app: &AppHandle, configured: Option<&str>) -> PathBuf {
    match configured {
        Some(s) if !s.trim().is_empty() => PathBuf::from(s),
        _ => {
            let pictures = app
                .path()
                .picture_dir()
                .ok()
                .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Pictures")))
                .unwrap_or_else(|| PathBuf::from("."));
            pictures.join("DroidDock")
        }
    }
}

/// The shared diff-and-download core for both the automatic sync-check and
/// the manual backfill command. Sequential downloads (one `transfer::pull` at
/// a time) — simplest correct option; the phone link is already the
/// bottleneck (single WebSocket, one binary stream at a time per
/// `ws_server`'s bounded outbox), so bounded concurrency would mostly just
/// interleave chunks from two files without actually increasing throughput.
async fn run_sync(
    app: AppHandle,
    ws_state: SharedState,
    photo: PhotoSync,
    dest_dir: PathBuf,
    device_key: String,
) -> Result<(), String> {
    if photo.running.swap(true, Ordering::SeqCst) {
        return Err("Photo sync is already running".into());
    }
    let _guard = RunGuard(&photo.running);

    let items = fetch_all_items(&ws_state).await?;
    let already = photo.already_synced(&device_key).await;
    let new_items: Vec<MediaItem> = items.into_iter().filter(|it| !already.contains(&it.id)).collect();

    let total = new_items.len() as u32;
    if total == 0 {
        emit_progress(&app, 0, 0, None, None);
        return Ok(());
    }

    // Emitted only once the destination is confirmed usable, so the frontend
    // never sees a "syncing" state that then hangs forever with no terminal
    // event — a `create_dir_all` failure below emits its own terminal
    // (error-carrying, done==total) event instead of leaving one dangling.
    if let Err(e) = tokio::fs::create_dir_all(&dest_dir).await {
        let msg = format!("cannot create {}: {e}", dest_dir.display());
        emit_progress(&app, total, total, None, Some(msg.clone()));
        return Err(msg);
    }
    emit_progress(&app, 0, total, None, None);

    let mut done = 0u32;
    for item in new_items {
        let dest = unique_dest(&dest_dir, &item.name);
        // Record-as-completed happens per item (not batched at the end) so a
        // crash mid-run leaves the ledger reflecting exactly what actually
        // landed on disk — a re-run only re-downloads what's still missing.
        match transfer::pull(app.clone(), ws_state.clone(), item.path.clone(), dest).await {
            Ok(_) => {
                photo.mark_synced(&device_key, item.id).await;
                done += 1;
                emit_progress(&app, done, total, Some(item.name), None);
            }
            Err(e) => {
                // Not marked synced — left for the next pass to retry. Still
                // counted toward `done` so the progress indicator completes
                // this run instead of stalling on a stuck item.
                done += 1;
                emit_progress(&app, done, total, Some(item.name), Some(e));
            }
        }
    }
    Ok(())
}

struct RunGuard<'a>(&'a AtomicBool);
impl Drop for RunGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

/// Automatic trigger point — a `photos-changed` ping or a caps-gated
/// (re)connect. No-ops silently if the feature is off or DroidDock is
/// paused, exactly as the caller expects (see `ws_server.rs`).
pub async fn check(
    app: AppHandle,
    ws_state: SharedState,
    photo: PhotoSync,
    cfg: crate::config::Config,
    device_key: String,
    // The phone's display name, when it differs from `device_key` — i.e. when
    // the phone now sends a device id. Used once to migrate a ledger written
    // under the old name-based key; `None` means there is nothing to migrate.
    legacy_key: Option<String>,
) {
    if !cfg.photo_sync_enabled || cfg.is_paused() {
        return;
    }
    if let Some(legacy) = legacy_key.as_deref() {
        photo.migrate_key(legacy, &device_key).await;
    }
    let dest = resolve_dest(&app, cfg.photo_sync_dest.as_deref());
    let _ = run_sync(app, ws_state, photo, dest, device_key).await;
}

/// Manual "back-fill existing library" action (Settings button): runs the
/// same diff regardless of the enable toggle — it's an explicit one-off, not
/// gated on the forward-sync feature having just been turned on. Pause is
/// still honored here, for consistency with the PRD's blanket "honors global
/// Pause mode" (Phase 14's pause is meant to mute all phone-link activity,
/// not just the automatic path).
pub async fn backfill(
    app: AppHandle,
    ws_state: SharedState,
    photo: PhotoSync,
    cfg: crate::config::Config,
    device_key: String,
    // The phone's display name, when it differs from `device_key` — i.e. when
    // the phone now sends a device id. Used once to migrate a ledger written
    // under the old name-based key; `None` means there is nothing to migrate.
    legacy_key: Option<String>,
) -> Result<(), String> {
    if cfg.is_paused() {
        return Err("DroidDock is paused — resume it first".into());
    }
    if let Some(legacy) = legacy_key.as_deref() {
        photo.migrate_key(legacy, &device_key).await;
    }
    let dest = resolve_dest(&app, cfg.photo_sync_dest.as_deref());
    run_sync(app, ws_state, photo, dest, device_key).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    fn tmp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("droiddock-photo-sync-test-{tag}-{}", Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn migrating_the_ledger_key_preserves_already_synced_ids() {
        let mut ledger = Ledger::default();
        ledger.by_device.entry("Google Pixel 7".into()).or_default().extend([1, 2]);

        // The state that would have re-downloaded the whole library: the new
        // id-based key knows nothing about what the name-based key synced.
        assert!(!ledger.by_device.contains_key("uuid-abc"));

        assert!(ledger.migrate_key("Google Pixel 7", "uuid-abc"));
        assert_eq!(ledger.by_device["uuid-abc"].len(), 2);
        // Source removed, so a second pass can't duplicate or resurrect it.
        assert!(!ledger.by_device.contains_key("Google Pixel 7"));
        assert!(!ledger.migrate_key("Google Pixel 7", "uuid-abc"));
    }

    #[test]
    fn migration_never_clobbers_an_existing_id_keyed_entry() {
        let mut ledger = Ledger::default();
        ledger.by_device.entry("Pixel".into()).or_default().extend([1]);
        ledger.by_device.entry("uuid-abc".into()).or_default().extend([9]);

        // Already migrated (or a genuinely different phone) — the id-keyed set
        // is authoritative and must not be overwritten by the stale name one.
        assert!(!ledger.migrate_key("Pixel", "uuid-abc"));
        assert_eq!(ledger.by_device["uuid-abc"], [9].into_iter().collect());
    }

    #[test]
    fn migration_is_a_noop_when_the_keys_are_the_same() {
        let mut ledger = Ledger::default();
        ledger.by_device.entry("Pixel".into()).or_default().extend([1]);
        // An older phone with no device id keys on its name already.
        assert!(!ledger.migrate_key("Pixel", "Pixel"));
        assert_eq!(ledger.by_device["Pixel"].len(), 1);
    }

    #[test]
    fn ledger_scopes_ids_per_device_so_overlapping_raw_ids_never_collide() {
        let mut ledger = Ledger::default();
        ledger.by_device.entry("Phone A".into()).or_default().insert(1);
        ledger.by_device.entry("Phone B".into()).or_default();

        assert!(ledger.by_device["Phone A"].contains(&1));
        assert!(!ledger.by_device["Phone B"].contains(&1));
    }

    #[test]
    fn ledger_round_trips_through_save_and_load() {
        let root = tmp_dir("ledger");
        let path = root.join(LEDGER_FILE);
        let mut ledger = Ledger::default();
        ledger.by_device.entry("Phone A".into()).or_default().insert(42);
        save_ledger(&path, &ledger);

        assert!(!path.with_file_name(format!("{LEDGER_FILE}.tmp")).exists());
        let reloaded = load_ledger(&path);
        assert!(reloaded.by_device["Phone A"].contains(&42));

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn old_format_ledger_without_by_device_loads_as_empty_not_error() {
        let root = tmp_dir("legacy");
        let path = root.join(LEDGER_FILE);
        std::fs::write(&path, r#"{"synced_ids":[1,2,3]}"#).unwrap();

        let reloaded = load_ledger(&path);
        assert!(reloaded.by_device.is_empty());

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn unique_dest_avoids_collision_with_an_existing_file() {
        let root = tmp_dir("unique-dest");
        std::fs::write(root.join("IMG.jpg"), b"x").unwrap();

        let dest = unique_dest(&root, "IMG.jpg");
        assert_eq!(dest, root.join("IMG (2).jpg"));
        assert!(!dest.exists());

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn unique_dest_skips_multiple_existing_collisions() {
        let root = tmp_dir("unique-dest-multi");
        std::fs::write(root.join("a.png"), b"x").unwrap();
        std::fs::write(root.join("a (2).png"), b"x").unwrap();

        let dest = unique_dest(&root, "a.png");
        assert_eq!(dest, root.join("a (3).png"));

        let _ = std::fs::remove_dir_all(&root);
    }
}
