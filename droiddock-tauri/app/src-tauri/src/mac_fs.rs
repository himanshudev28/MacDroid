//! Phase 19: reverse file browsing — the phone browses/pulls from a Mac-side
//! directory allowlist (`Config::mac_fs_roots`). This is the first feature
//! that widens what a paired phone can reach on the Mac filesystem, so
//! `check_root` below is the hard security boundary: `list` and
//! `pull_to_phone` both call it before touching disk, and every wire failure
//! path returns an `Err` rather than silently falling back to a weaker check.

use crate::transfer::{self, KIND_DATA};
use crate::ws_server::{self, SharedState};
use serde::Serialize;
use std::path::PathBuf;
use tauri::AppHandle;
use tokio::io::AsyncReadExt;

/// One directory entry — same shape as the existing Mac→phone Files-view
/// `FsEntry` (`src/lib/bridge.ts`): `{name, dir, size, modified}`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Entry {
    pub name: String,
    pub dir: bool,
    pub size: u64,
    /// Absolute path, sent ONLY for the synthetic root listing — a root's
    /// display name is its basename, but the phone has no base to join it to,
    /// so it needs the full path to navigate into. Omitted for ordinary
    /// entries, which the phone joins onto the directory it asked for.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    /// Epoch milliseconds, same unit as the rest of the config/protocol code
    /// (see `config::now_ms`).
    pub modified: i64,
}

/// Verify `path` resolves inside one of the configured allowed roots.
///
/// Both `path` and every root are run through `std::fs::canonicalize`, which
/// resolves symlinks *and* `..` components — a raw string-prefix check would
/// let a symlink planted inside an allowed root (or a `../../`-laden path)
/// slip through to anywhere on disk, so canonicalize-then-compare is the only
/// check that's actually airtight here. `Path::starts_with` compares whole
/// path components, so an allowed root of e.g. `/Users/foo/Doc` can never
/// spuriously match a request for `/Users/foo/Documents`.
pub fn check_root(roots: &[String], path: &str) -> Result<PathBuf, String> {
    // A path that doesn't exist (or has an unreadable component) can't be
    // canonicalized — treated as a hard rejection, never a fallback to an
    // uncanonicalized check.
    let target = std::fs::canonicalize(path).map_err(|e| format!("cannot resolve path: {e}"))?;

    for root in roots {
        let Ok(canon_root) = std::fs::canonicalize(root) else {
            continue; // a configured root that's missing/unreadable just can't match anything
        };
        if target == canon_root || target.starts_with(&canon_root) {
            return Ok(target);
        }
    }
    Err("path escapes allowed roots".to_string())
}

/// List a directory's entries. Rejects (via `check_root`) before touching
/// disk if `path` isn't inside an allowed root.
pub async fn list(roots: &[String], path: &str) -> Result<Vec<Entry>, String> {
    let dir = check_root(roots, path)?;

    let mut rd = tokio::fs::read_dir(&dir)
        .await
        .map_err(|e| format!("cannot read directory: {e}"))?;

    let mut entries = Vec::new();
    while let Some(item) = rd
        .next_entry()
        .await
        .map_err(|e| format!("cannot read directory: {e}"))?
    {
        let Ok(meta) = item.metadata().await else {
            continue; // skip entries that vanish/deny stat mid-listing
        };
        let modified = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        entries.push(Entry {
            name: item.file_name().to_string_lossy().to_string(),
            dir: meta.is_dir(),
            size: if meta.is_dir() { 0 } else { meta.len() },
            modified,
            path: None,
        });
    }
    Ok(entries)
}

/// The synthetic top level: the configured roots themselves.
///
/// The phone's Mac Files tab opens with `path: ""`, which has no directory to
/// list — `canonicalize("")` fails, so before this the feature errored the
/// instant it was opened. An empty path now means "show me what I'm allowed to
/// see", which is also the only way to discover the roots without hardcoding
/// them phone-side.
pub fn list_roots(roots: &[String]) -> Vec<Entry> {
    roots
        .iter()
        .filter_map(|root| {
            // Skip roots that don't resolve — listing one would only produce an
            // entry that errors when tapped.
            let canon = std::fs::canonicalize(root).ok()?;
            let name = canon
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_else(|| canon.to_string_lossy().to_string());
            let modified = canon
                .metadata()
                .ok()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            Some(Entry {
                name,
                dir: true,
                size: 0,
                modified,
                path: Some(canon.to_string_lossy().to_string()),
            })
        })
        .collect()
}

/// Send a file from an allowed root to the phone: `mac-fs-pull-begin` (with a
/// freshly Mac-allocated transferId, reusing `TransferRegistry::alloc_mac_id`)
/// followed by the file's bytes as ordinary `KIND_DATA` binary frames, then
/// `mac-fs-pull-done`. Rejects via `check_root` first. On any failure —
/// including partway through, from an IO error — this returns `Err` and does
/// NOT itself push `mac-fs-pull-error`; the caller (`ws_server::route_text`)
/// owns sending that reply so there's exactly one place that does it for
/// every failure path.
pub async fn pull_to_phone(
    _app: AppHandle,
    ws_state: SharedState,
    roots: &[String],
    path: String,
    req_id: String,
) -> Result<(), String> {
    let file_path = check_root(roots, &path)?;
    let meta = tokio::fs::metadata(&file_path)
        .await
        .map_err(|e| format!("cannot read file: {e}"))?;
    if meta.is_dir() {
        return Err("path is a directory".to_string());
    }
    let size = meta.len();
    let transfer_id = ws_state.transfers.alloc_mac_id();

    // Arm the ready-waiter BEFORE announcing the transfer, so a phone that
    // answers instantly can't beat us to the registration.
    let ready = ws_server::arm_mac_fs_ready(&ws_state, &req_id).await;

    ws_server::push(
        &ws_state,
        serde_json::json!({
            "type": "mac-fs-pull-begin",
            "reqId": req_id,
            "transferId": transfer_id,
            "size": size,
        }),
    )
    .await;

    // Wait for the phone to say its receiver is registered. Without this the
    // first chunks race the phone's own `receiveMacFsPull` registration and are
    // dropped on the floor — the file lands truncated and still reports success.
    // An older phone build never sends `mac-fs-pull-ready`; the timeout below
    // makes that degrade to the previous (racy) behaviour rather than hanging.
    ready.await;

    let mut file = tokio::fs::File::open(&file_path)
        .await
        .map_err(|e| format!("cannot open file: {e}"))?;
    let mut buf = vec![0u8; transfer::CHUNK];
    let mut seq: u32 = 0;
    loop {
        let n = file
            .read(&mut buf)
            .await
            .map_err(|e| format!("read error: {e}"))?;
        if n == 0 {
            break;
        }
        if !ws_server::send_binary(&ws_state, transfer::frame(KIND_DATA, transfer_id, seq, &buf[..n])).await {
            return Err("Phone disconnected mid-transfer".to_string());
        }
        seq += 1;
    }

    ws_server::push(
        &ws_state,
        serde_json::json!({ "type": "mac-fs-pull-done", "reqId": req_id, "transferId": transfer_id }),
    )
    .await;
    Ok(())
}

#[cfg(test)]
mod tests {
    /// The phone's Mac Files tab opens with `path: ""`. Before the root
    /// listing existed that hit `canonicalize("")` and errored instantly, so
    /// the feature was unusable from the moment it was opened.
    #[test]
    fn empty_path_lists_the_configured_roots_with_absolute_paths() {
        let dir = std::env::temp_dir().join("droiddock-roots-test");
        let _ = std::fs::create_dir_all(&dir);
        let roots = vec![dir.to_string_lossy().to_string()];

        let entries = super::list_roots(&roots);
        assert_eq!(entries.len(), 1);
        assert!(entries[0].dir);
        // Display name is the basename…
        assert_eq!(entries[0].name, "droiddock-roots-test");
        // …but an absolute path rides along, because the phone has no base to
        // join the basename onto at the top level.
        let path = entries[0].path.as_deref().expect("root entries carry a path");
        assert!(std::path::Path::new(path).is_absolute());
        // And that path must itself pass the security check.
        assert!(super::check_root(&roots, path).is_ok());
    }

    #[test]
    fn roots_that_do_not_resolve_are_skipped_not_listed() {
        // Listing a broken root would only produce an entry that errors on tap.
        let entries = super::list_roots(&vec!["/definitely/not/a/real/path".to_string()]);
        assert!(entries.is_empty());
    }

    #[test]
    fn an_empty_root_list_yields_nothing() {
        assert!(super::list_roots(&[]).is_empty());
    }

    use super::*;
    use std::fs;
    use std::sync::atomic::{AtomicU64, Ordering};

    static COUNTER: AtomicU64 = AtomicU64::new(0);

    /// A fresh scratch directory under the OS temp dir — unique per call (not
    /// just per test) so parallel `cargo test` threads never collide.
    fn scratch_dir(tag: &str) -> PathBuf {
        let n = COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "droiddock-mac-fs-test-{tag}-{}-{n}",
            std::process::id()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// (a) A path inside an allowed root passes.
    #[test]
    fn path_inside_allowed_root_passes() {
        let root = scratch_dir("inside");
        let file = root.join("hello.txt");
        fs::write(&file, b"hi").unwrap();

        let roots = vec![root.to_string_lossy().to_string()];
        let result = check_root(&roots, file.to_str().unwrap());
        assert!(result.is_ok(), "expected ok, got {result:?}");
        assert_eq!(result.unwrap(), fs::canonicalize(&file).unwrap());

        let _ = fs::remove_dir_all(&root);
    }

    /// (b) A `../../`-style traversal that escapes the root is rejected.
    #[test]
    fn traversal_escaping_root_is_rejected() {
        let root = scratch_dir("traversal-root");
        let outside = scratch_dir("traversal-outside");
        fs::write(outside.join("secret.txt"), b"nope").unwrap();

        // From inside `root`, walk up to the temp dir and back down into the
        // sibling `outside` dir — a classic `..` traversal.
        let escaping = root.join("..").join(outside.file_name().unwrap()).join("secret.txt");

        let roots = vec![root.to_string_lossy().to_string()];
        let result = check_root(&roots, escaping.to_str().unwrap());
        assert!(result.is_err());
        assert_eq!(result.unwrap_err(), "path escapes allowed roots");

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_dir_all(&outside);
    }

    /// (c) A path entirely outside any configured root is rejected.
    #[test]
    fn path_entirely_outside_configured_roots_is_rejected() {
        let root = scratch_dir("unrelated-root");
        let unrelated = scratch_dir("unrelated-target");
        fs::write(unrelated.join("file.txt"), b"x").unwrap();

        let roots = vec![root.to_string_lossy().to_string()];
        let result = check_root(&roots, unrelated.join("file.txt").to_str().unwrap());
        assert!(result.is_err());
        assert_eq!(result.unwrap_err(), "path escapes allowed roots");

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_dir_all(&unrelated);
    }

    /// (d) A symlink planted *inside* an allowed root but pointing *outside*
    /// every configured root must still be rejected — proving `check_root`
    /// actually resolves symlinks (via canonicalize) rather than just
    /// string-matching the uncanonicalized path.
    #[test]
    fn symlink_inside_root_pointing_outside_is_rejected() {
        let root = scratch_dir("symlink-root");
        let outside = scratch_dir("symlink-outside-target");
        fs::write(outside.join("secret.txt"), b"nope").unwrap();

        let link = root.join("escape-link");
        std::os::unix::fs::symlink(&outside, &link).unwrap();

        let roots = vec![root.to_string_lossy().to_string()];
        let via_link = link.join("secret.txt");
        let result = check_root(&roots, via_link.to_str().unwrap());
        assert!(result.is_err(), "symlink escape should be rejected, got {result:?}");
        assert_eq!(result.unwrap_err(), "path escapes allowed roots");

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_dir_all(&outside);
    }

    /// A symlink inside an allowed root that points to another location
    /// *still inside* an allowed root is legitimately allowed through — this
    /// isn't itself a required test from the spec, but pins down that
    /// `check_root` isn't over-rejecting every symlink, only escaping ones.
    #[test]
    fn symlink_inside_root_pointing_inside_another_root_passes() {
        let root_a = scratch_dir("symlink-inside-a");
        let root_b = scratch_dir("symlink-inside-b");
        fs::write(root_b.join("ok.txt"), b"fine").unwrap();

        let link = root_a.join("link-to-b");
        std::os::unix::fs::symlink(&root_b, &link).unwrap();

        let roots = vec![root_a.to_string_lossy().to_string(), root_b.to_string_lossy().to_string()];
        let via_link = link.join("ok.txt");
        let result = check_root(&roots, via_link.to_str().unwrap());
        assert!(result.is_ok(), "expected ok, got {result:?}");

        let _ = fs::remove_dir_all(&root_a);
        let _ = fs::remove_dir_all(&root_b);
    }

    #[tokio::test]
    async fn list_rejects_path_outside_roots() {
        let root = scratch_dir("list-root");
        let outside = scratch_dir("list-outside");

        let roots = vec![root.to_string_lossy().to_string()];
        let result = list(&roots, outside.to_str().unwrap()).await;
        assert!(result.is_err());
        assert_eq!(result.unwrap_err(), "path escapes allowed roots");

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_dir_all(&outside);
    }

    #[tokio::test]
    async fn list_returns_entries_for_an_allowed_directory() {
        let root = scratch_dir("list-ok-root");
        fs::write(root.join("a.txt"), b"hello").unwrap();
        fs::create_dir_all(root.join("sub")).unwrap();

        let roots = vec![root.to_string_lossy().to_string()];
        let entries = list(&roots, root.to_str().unwrap()).await.unwrap();
        assert_eq!(entries.len(), 2);
        assert!(entries.iter().any(|e| e.name == "a.txt" && !e.dir && e.size == 5));
        assert!(entries.iter().any(|e| e.name == "sub" && e.dir));

        let _ = fs::remove_dir_all(&root);
    }
}
