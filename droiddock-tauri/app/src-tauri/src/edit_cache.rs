//! Phase 17: open-in-place with edit-writeback.
//!
//! Pulls a phone file into a per-session cache dir, opens it in its native Mac
//! app, and watches it (FSEvents via `notify`) for saves. A settled save is
//! pushed back to the phone's original path via the existing `transfer::push`
//! — no protocol/Android changes, no new wire message.

use crate::transfer;
use crate::ws_server::SharedState;
use notify::{Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use serde::{Deserialize, Serialize};
use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tauri_plugin_opener::OpenerExt;
use tokio::sync::Mutex;
use uuid::Uuid;

const DEBOUNCE: Duration = Duration::from_millis(700);
const CACHE_CAP_BYTES: u64 = 300 * 1024 * 1024;
const MANIFEST_FILE: &str = "manifest.json";

#[derive(Clone, Serialize, Deserialize)]
struct ManifestEntry {
    phone_path: String,
    pending: bool,
    last_synced_ms: Option<i64>,
}

#[derive(Default, Serialize, Deserialize)]
struct Manifest {
    entries: HashMap<String, ManifestEntry>,
}

pub struct EditCacheState {
    root: PathBuf,
    pub session_dir: PathBuf,
    manifest: Mutex<Manifest>,
    watchers: StdMutex<HashMap<PathBuf, RecommendedWatcher>>,
    /// Per-local-path serialization for `do_writeback`: the debounce/generation
    /// counter in `start_watch` only picks the winning *timer*, so without this
    /// a still-in-flight push from an earlier debounce could race a newer one
    /// and — if it finishes last — clobber the phone with stale content. Held
    /// for the full read+push of `do_writeback`, so at most one writeback for
    /// a given path runs at a time.
    write_locks: StdMutex<HashMap<PathBuf, Arc<Mutex<()>>>>,
    /// Guards against a flapping connection queuing overlapping retry passes.
    retrying: AtomicBool,
}

pub type EditCache = Arc<EditCacheState>;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct EditSync {
    local_path: String,
    phone_path: String,
    status: &'static str, // "syncing" | "synced" | "pending"
    error: Option<String>,
}

fn emit_sync(app: &AppHandle, local: &Path, phone_path: &str, status: &'static str, error: Option<String>) {
    let _ = app.emit(
        "edit-sync",
        EditSync { local_path: local.to_string_lossy().to_string(), phone_path: phone_path.to_string(), status, error },
    );
}

/// Strip path separators and neutralize bare `.`/`..` basenames (e.g. a phone
/// path ending in `/..`) — `cache_relative_path` joins this straight onto the
/// per-file bucket dir, and a `..` basename would otherwise make `transfer::pull`'s
/// final rename silently target the bucket dir itself instead of a file inside it.
fn sanitize(name: &str) -> String {
    let cleaned = name.replace(['/', '\\'], "_");
    if cleaned == "." || cleaned == ".." {
        "file".to_string()
    } else {
        cleaned
    }
}

/// A local cache path for `phone_path` that can never collide with another
/// phone path that happens to share a basename (e.g. `/sdcard/DCIM/IMG.jpg`
/// vs `/sdcard/Download/IMG.jpg`): each full phone path gets its own bucket
/// subdirectory, and the real basename lives inside it — so the file handed
/// to the native app still has a sensible name/extension.
fn cache_relative_path(phone_path: &str) -> PathBuf {
    let mut hasher = DefaultHasher::new();
    phone_path.hash(&mut hasher);
    let bucket = format!("{:016x}", hasher.finish());
    let name = phone_path.rsplit('/').find(|s| !s.is_empty()).unwrap_or("file");
    PathBuf::from(bucket).join(sanitize(name))
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn parent_phone_dir(phone_path: &str) -> String {
    match phone_path.rsplit_once('/') {
        Some((parent, _)) if !parent.is_empty() => parent.to_string(),
        _ => "/".to_string(),
    }
}

fn dir_size(dir: &Path) -> u64 {
    let Ok(rd) = std::fs::read_dir(dir) else { return 0 };
    let mut total = 0u64;
    for entry in rd.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            total += dir_size(&entry.path());
        } else {
            total += meta.len();
        }
    }
    total
}

fn load_manifest(root: &Path) -> Manifest {
    std::fs::read_to_string(root.join(MANIFEST_FILE))
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

/// Write-to-temp-then-rename so a crash mid-write can never truncate/corrupt
/// `manifest.json` — same atomic-landing pattern `transfer.rs` uses for
/// downloads (`.part` file, renamed only once fully written).
fn save_manifest(root: &Path, manifest: &Manifest) {
    let Ok(raw) = serde_json::to_string_pretty(manifest) else { return };
    let tmp = root.join(format!("{MANIFEST_FILE}.tmp"));
    if std::fs::write(&tmp, raw).is_ok() {
        let _ = std::fs::rename(&tmp, root.join(MANIFEST_FILE));
    }
}

/// Scan the edit-cache root for previous-session dirs and delete them, except
/// any dir still holding a pending unsynced edit (per the on-disk manifest),
/// then start a fresh session dir. Synchronous — called once from `.setup()`.
pub fn init(root: PathBuf) -> EditCache {
    let _ = std::fs::create_dir_all(&root);
    let mut manifest = load_manifest(&root);

    let old_dirs: Vec<PathBuf> = std::fs::read_dir(&root)
        .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect())
        .unwrap_or_default();

    for dir in old_dirs {
        let dir_has_pending = manifest.entries.iter().any(|(k, e)| e.pending && Path::new(k).starts_with(&dir));
        if dir_has_pending {
            continue;
        }
        let _ = std::fs::remove_dir_all(&dir);
        manifest.entries.retain(|k, _| !Path::new(k).starts_with(&dir));
    }
    save_manifest(&root, &manifest);

    let session_dir = root.join(Uuid::new_v4().to_string());
    let _ = std::fs::create_dir_all(&session_dir);

    Arc::new(EditCacheState {
        root,
        session_dir,
        manifest: Mutex::new(manifest),
        watchers: StdMutex::new(HashMap::new()),
        write_locks: StdMutex::new(HashMap::new()),
        retrying: AtomicBool::new(false),
    })
}

impl EditCacheState {
    async fn save(&self) {
        let manifest = self.manifest.lock().await;
        save_manifest(&self.root, &manifest);
    }

    /// Phone paths currently marked `pending` in the manifest — used to
    /// hydrate the frontend's pending-sync badge on mount (`fs_pending_syncs`
    /// in `lib.rs`), since it otherwise only learns about pending edits from
    /// live `edit-sync` events that may have fired while the Files tab was
    /// unmounted.
    pub async fn pending_phone_paths(&self) -> Vec<String> {
        let m = self.manifest.lock().await;
        m.entries.values().filter(|e| e.pending).map(|e| e.phone_path.clone()).collect()
    }

    async fn record_synced(&self, local: &Path, phone_path: &str) {
        let key = local.to_string_lossy().to_string();
        let mut m = self.manifest.lock().await;
        m.entries.insert(key, ManifestEntry { phone_path: phone_path.to_string(), pending: false, last_synced_ms: Some(now_ms()) });
        drop(m);
        self.save().await;
    }

    async fn mark_pending(&self, local: &Path, phone_path: &str) {
        let key = local.to_string_lossy().to_string();
        let mut m = self.manifest.lock().await;
        m.entries
            .entry(key)
            .and_modify(|e| e.pending = true)
            .or_insert_with(|| ManifestEntry { phone_path: phone_path.to_string(), pending: true, last_synced_ms: None });
        drop(m);
        self.save().await;
    }

    async fn mark_synced(&self, local: &Path) {
        let key = local.to_string_lossy().to_string();
        let mut m = self.manifest.lock().await;
        if let Some(e) = m.entries.get_mut(&key) {
            e.pending = false;
            e.last_synced_ms = Some(now_ms());
        }
        drop(m);
        self.save().await;
    }

    async fn forget(&self, local: &Path) {
        let key = local.to_string_lossy().to_string();
        let mut m = self.manifest.lock().await;
        m.entries.remove(&key);
        drop(m);
        self.save().await;
    }

    /// The per-path writeback lock, created on first use. Held across the
    /// whole `do_writeback` read+push so overlapping debounce-triggered
    /// writebacks for the same file are always strictly sequential.
    fn write_lock_for(&self, local_path: &Path) -> Arc<Mutex<()>> {
        self.write_locks
            .lock()
            .unwrap()
            .entry(local_path.to_path_buf())
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    }

    fn forget_watcher_and_lock(&self, path: &Path) {
        self.watchers.lock().unwrap().remove(path);
        self.write_locks.lock().unwrap().remove(path);
    }

    /// Evict oldest already-synced, not-currently-watched files if the cache
    /// is over its cap. If `new_file` itself can't be made to fit, it is
    /// deleted and an error returned (never leaves the cache silently
    /// over-cap or half-written).
    async fn enforce_cap(&self, new_file: &Path) -> Result<(), String> {
        // Paths whose delete failed this pass (locked/permission) — excluded
        // from further victim picks so one undeletable file can't make the
        // loop retry it forever instead of evicting other candidates.
        let mut failed: std::collections::HashSet<PathBuf> = std::collections::HashSet::new();
        loop {
            if dir_size(&self.root) <= CACHE_CAP_BYTES {
                return Ok(());
            }
            let victim = {
                let m = self.manifest.lock().await;
                let watched = self.watchers.lock().unwrap();
                pick_eviction_victim(&m.entries, &watched, new_file, &failed)
            };
            match victim {
                Some(path) => {
                    if tokio::fs::remove_file(&path).await.is_ok() {
                        self.forget(&path).await;
                        self.forget_watcher_and_lock(&path);
                    } else {
                        // Leave it tracked in the manifest (still counted by
                        // `dir_size`, still on disk) but never pick it again
                        // this pass so eviction can make progress elsewhere.
                        failed.insert(path);
                    }
                }
                None => {
                    let _ = tokio::fs::remove_file(new_file).await;
                    self.forget(new_file).await;
                    self.forget_watcher_and_lock(new_file);
                    return Err("Edit cache is full — free up space and try again".into());
                }
            }
        }
    }
}

/// Pure victim-selection for cap eviction: oldest `last_synced_ms` among
/// entries that are neither `pending` (unsynced), the file just pulled,
/// currently open under a live watcher, nor a path that already failed to
/// delete earlier in this eviction pass (`skip`) — split out so it's
/// unit-testable without a real `EditCacheState`/`AppHandle`.
fn pick_eviction_victim(
    entries: &HashMap<String, ManifestEntry>,
    watched: &HashMap<PathBuf, RecommendedWatcher>,
    exclude: &Path,
    skip: &std::collections::HashSet<PathBuf>,
) -> Option<PathBuf> {
    entries
        .iter()
        .filter(|(k, e)| {
            !e.pending
                && Path::new(k.as_str()) != exclude
                && !watched.contains_key(Path::new(k.as_str()))
                && !skip.contains(Path::new(k.as_str()))
        })
        .min_by_key(|(_, e)| e.last_synced_ms.unwrap_or(0))
        .map(|(k, _)| PathBuf::from(k))
}

fn start_watch(app: AppHandle, ws_state: SharedState, cache: EditCache, local_path: PathBuf, phone_path: String) -> Result<(), String> {
    let generation = Arc::new(AtomicU64::new(0));
    let app2 = app.clone();
    let ws2 = ws_state.clone();
    let cache2 = cache.clone();
    let local2 = local_path.clone();
    let phone2 = phone_path.clone();
    let gen2 = generation.clone();

    let mut watcher = notify::recommended_watcher(move |res: notify::Result<Event>| {
        let Ok(event) = res else { return };
        if !matches!(event.kind, EventKind::Modify(_) | EventKind::Create(_)) {
            return;
        }
        let my_gen = gen2.fetch_add(1, Ordering::SeqCst) + 1;
        let app3 = app2.clone();
        let ws3 = ws2.clone();
        let cache3 = cache2.clone();
        let local3 = local2.clone();
        let phone3 = phone2.clone();
        let gen3 = gen2.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(DEBOUNCE).await;
            if gen3.load(Ordering::SeqCst) != my_gen {
                return;
            }
            do_writeback(app3, ws3, cache3, local3, phone3).await;
        });
    })
    .map_err(|e| e.to_string())?;

    watcher.watch(&local_path, RecursiveMode::NonRecursive).map_err(|e| e.to_string())?;
    cache.watchers.lock().unwrap().insert(local_path, watcher);
    Ok(())
}

async fn do_writeback(app: AppHandle, ws_state: SharedState, cache: EditCache, local_path: PathBuf, phone_path: String) {
    // Serialize writebacks per local path: hold this for the full read+push so
    // an overlapping debounce-triggered writeback for the same file can never
    // run concurrently and (if it finished last) clobber a newer save.
    let lock = cache.write_lock_for(&local_path);
    let _guard = lock.lock().await;

    cache.mark_pending(&local_path, &phone_path).await;
    emit_sync(&app, &local_path, &phone_path, "syncing", None);

    let dest_dir = parent_phone_dir(&phone_path);
    let local_str = local_path.to_string_lossy().to_string();
    match transfer::push(app.clone(), ws_state, local_str, dest_dir).await {
        Ok(()) => {
            cache.mark_synced(&local_path).await;
            emit_sync(&app, &local_path, &phone_path, "synced", None);
        }
        Err(e) => {
            emit_sync(&app, &local_path, &phone_path, "pending", Some(e));
        }
    }
}

/// RAII guard that resets `retrying` back to `false` on drop — including on
/// an early return or a panic-unwind partway through the loop below, unlike a
/// manual trailing `store` which would leave retries permanently disabled
/// (until app restart) if a panic ever interrupted the loop.
struct RetryGuard<'a>(&'a AtomicBool);

impl Drop for RetryGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

/// Re-attempt writeback for every still-pending manifest entry — hooked into
/// the phone (re)connect path in `ws_server.rs`. A flapping connection can
/// fire this repeatedly in quick succession, so an in-flight guard skips a
/// new pass while one is already running rather than duplicating pushes.
pub async fn retry_pending(app: AppHandle, ws_state: SharedState, cache: EditCache) {
    if cache.retrying.swap(true, Ordering::SeqCst) {
        return;
    }
    let _guard = RetryGuard(&cache.retrying);

    let pending: Vec<(PathBuf, String)> = {
        let m = cache.manifest.lock().await;
        m.entries.iter().filter(|(_, e)| e.pending).map(|(k, e)| (PathBuf::from(k), e.phone_path.clone())).collect()
    };
    for (local, phone) in pending {
        do_writeback(app.clone(), ws_state.clone(), cache.clone(), local, phone).await;
    }
}

/// Pull `path` from the phone into this session's cache dir, open it in its
/// native Mac app, and start watching it for saves. Mirrors `photo_open`'s
/// pull-then-open shape, plus the watch/writeback machinery.
pub async fn open_in_place(app: AppHandle, ws_state: SharedState, cache: EditCache, path: String) -> Result<(), String> {
    let dest = cache.session_dir.join(cache_relative_path(&path));

    let saved = transfer::pull(app.clone(), ws_state.clone(), path.clone(), dest).await?;
    cache.record_synced(&saved, &path).await;
    cache.enforce_cap(&saved).await?;
    start_watch(app.clone(), ws_state.clone(), cache, saved.clone(), path)?;

    app.opener().open_path(saved.to_string_lossy().to_string(), None::<&str>).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("droiddock-edit-cache-test-{tag}-{}", Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn manifest_save_is_atomic_and_round_trips() {
        let root = tmp_dir("manifest");
        let mut m = Manifest::default();
        m.entries.insert(
            "/tmp/a.txt".into(),
            ManifestEntry { phone_path: "/sdcard/a.txt".into(), pending: true, last_synced_ms: Some(1) },
        );
        save_manifest(&root, &m);

        assert!(!root.join(format!("{MANIFEST_FILE}.tmp")).exists());
        let reloaded = load_manifest(&root);
        assert!(reloaded.entries["/tmp/a.txt"].pending);
        assert_eq!(reloaded.entries["/tmp/a.txt"].phone_path, "/sdcard/a.txt");

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn eviction_prefers_oldest_evictable_and_skips_watched_pending_excluded() {
        let root = tmp_dir("evict");
        let watched_path = root.join("watched.txt");
        std::fs::write(&watched_path, b"x").unwrap();
        let excluded_path = root.join("new.txt");
        let pending_path = root.join("pending.txt");
        let older_path = root.join("older.txt");
        let newer_path = root.join("newer.txt");

        let mut entries = HashMap::new();
        entries.insert(
            watched_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/watched.txt".into(), pending: false, last_synced_ms: Some(0) },
        );
        entries.insert(
            excluded_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/new.txt".into(), pending: false, last_synced_ms: Some(0) },
        );
        entries.insert(
            pending_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/pending.txt".into(), pending: true, last_synced_ms: Some(0) },
        );
        entries.insert(
            older_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/older.txt".into(), pending: false, last_synced_ms: Some(10) },
        );
        entries.insert(
            newer_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/newer.txt".into(), pending: false, last_synced_ms: Some(20) },
        );

        let mut watched: HashMap<PathBuf, RecommendedWatcher> = HashMap::new();
        let mut watcher = notify::recommended_watcher(|_res: notify::Result<Event>| {}).unwrap();
        watcher.watch(&watched_path, RecursiveMode::NonRecursive).unwrap();
        watched.insert(watched_path.clone(), watcher);

        let no_skip = std::collections::HashSet::new();
        let victim = pick_eviction_victim(&entries, &watched, &excluded_path, &no_skip);
        assert_eq!(victim, Some(older_path));

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn eviction_skips_paths_that_already_failed_to_delete_this_pass() {
        // Simulates `enforce_cap` after a `remove_file` failure on the oldest
        // candidate: it should fall through to the next-oldest evictable
        // entry instead of picking the undeletable one again.
        let older_path = PathBuf::from("/cache/older.txt");
        let newer_path = PathBuf::from("/cache/newer.txt");
        let excluded_path = PathBuf::from("/cache/new.txt");

        let mut entries = HashMap::new();
        entries.insert(
            older_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/older.txt".into(), pending: false, last_synced_ms: Some(10) },
        );
        entries.insert(
            newer_path.to_string_lossy().to_string(),
            ManifestEntry { phone_path: "/sdcard/newer.txt".into(), pending: false, last_synced_ms: Some(20) },
        );

        let watched: HashMap<PathBuf, RecommendedWatcher> = HashMap::new();

        let no_skip = std::collections::HashSet::new();
        assert_eq!(pick_eviction_victim(&entries, &watched, &excluded_path, &no_skip), Some(older_path.clone()));

        // `older_path`'s delete "failed" — mark it skipped for the rest of
        // this pass, as `enforce_cap` does on a failed `remove_file`.
        let mut failed = std::collections::HashSet::new();
        failed.insert(older_path.clone());
        assert_eq!(pick_eviction_victim(&entries, &watched, &excluded_path, &failed), Some(newer_path.clone()));

        // If every evictable candidate has failed, none remain — the caller
        // falls back to evicting `new_file` itself and erroring out, rather
        // than looping forever.
        failed.insert(newer_path);
        assert_eq!(pick_eviction_victim(&entries, &watched, &excluded_path, &failed), None);
    }

    #[test]
    fn sanitize_neutralizes_dot_and_dotdot_basenames() {
        assert_eq!(sanitize("."), "file");
        assert_eq!(sanitize(".."), "file");
        assert_eq!(sanitize("normal.txt"), "normal.txt");
    }

    #[test]
    fn cache_relative_path_neutralizes_dotdot_basename() {
        // A phone path ending in `/..` must not produce a `..` basename —
        // that would make `transfer::pull`'s final rename target the bucket
        // dir itself instead of a file inside it.
        let rel = cache_relative_path("/sdcard/foo/..");
        assert_eq!(rel.file_name().unwrap(), "file");
    }

    #[test]
    fn cache_relative_path_never_collides_on_shared_basename() {
        let a = cache_relative_path("/sdcard/DCIM/IMG_20260704.jpg");
        let b = cache_relative_path("/sdcard/Download/IMG_20260704.jpg");
        assert_ne!(a, b);
        assert_eq!(a.file_name(), b.file_name());
    }
}
