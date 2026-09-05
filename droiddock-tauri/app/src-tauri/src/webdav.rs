//! The phone's storage as a Finder volume, over WebDAV.
//!
//! # What this is
//!
//! A localhost WebDAV server that translates Finder's requests into the
//! `fs-list` / `fs-pull` messages the link already speaks. Mounting it puts the
//! phone in the Finder sidebar, so every Mac app that can open a file can open
//! one from the phone — which is the thing a file *browser* inside our own
//! window can never give you.
//!
//! # Read-only by default, writable by choice
//!
//! Writes are off unless **Settings → Mac files → Allow writing to the phone**
//! is on. Read-only stays the default posture for the second of the two reasons
//! below, which no amount of implementation removes.
//!
//! 1. **Finder litters.** It drops `.DS_Store` into every directory it merely
//!    *looks at*, and writes `._name` AppleDouble sidecars beside files it
//!    saves — plus `.Trashes`, `.fseventsd` and friends at a volume root.
//!    Mounted read-write and unfiltered, browsing phone storage dirties it.
//!
//!    Handled by [`is_finder_junk`]: those names are accepted and **discarded**
//!    rather than sent to the phone. That is a deliberate lie to Finder —
//!    refusing them instead produces a stream of error dialogs — and it is
//!    confined to a fixed list of names macOS generates and no user types.
//!
//! 2. **A write bug destroys files; a read bug shows a wrong listing.** Still
//!    true, still the reason for the default. What reduces it: the junk filter,
//!    the path handling `DavPath` already does, and a `MOVE` narrow enough to
//!    have no failure mode of its own (below).
//!
//! The Files tab remains the better place for deliberate destructive work — it
//! confirms. This exists for the thing a browser inside our own window can
//! never do: ⌘S in any Mac app, straight onto a file on the phone.
//!
//! # What each method maps to
//!
//! | WebDAV | Phone |
//! |---|---|
//! | `PUT` | `fs-push-begin` with `overwrite: true` |
//! | `DELETE` | `fs-delete` |
//! | `MOVE` | `fs-rename`, **same directory only** |
//! | `MKCOL` | `fs-mkdir` (caps-gated on `fsmkdir`) |
//! | `LOCK`/`UNLOCK` | in-memory [`FakeLs`], nothing reaches the phone |
//!
//! `MOVE` across directories is refused rather than emulated. The emulation
//! would be pull-push-delete: slow for a large file, and if it fails between
//! the push and the delete the user has two copies, or between pull and push,
//! none. A clean "not supported" is a better outcome than either.
//!
//! Locks are a formality: macOS's WebDAV client asks for them before writing,
//! and a lock on a volume only this Mac can reach protects against nothing, so
//! `FakeLs` answers without involving the phone.
//!
//! # Access control
//!
//! Bound to `127.0.0.1` only, so nothing off this Mac can reach it. That still
//! leaves every local process, so the mount URL carries a random capability
//! token in its path: `http://127.0.0.1:<port>/<token>/...`. A request whose
//! first path segment isn't the current token gets 404 — not 403, which would
//! confirm the server is there.
//!
//! The token is regenerated every time the server starts, so a stale mount from
//! a previous session stops working rather than silently continuing to serve.
//!
//! Note the app already claims the `droiddock://` URL scheme, and the pairing
//! QR payload is a `droiddock://pair?...` URL. That is unrelated to this: the
//! mount is plain HTTP on loopback, because `mount_webdav` and Finder speak
//! HTTP, not a custom scheme.

use crate::ws_server::{self, SharedState};
use dav_server::davpath::DavPath;
use dav_server::fs::{
    DavDirEntry, DavFile, DavFileSystem, DavMetaData, FsError, FsFuture, FsResult, FsStream,
    OpenOptions, ReadDirMeta,
};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::AppHandle;

/// Where on the phone the volume is rooted. Matches the Files tab's own
/// default, and `FileRepo.list` treats a blank path as this too.
const PHONE_ROOT: &str = "/sdcard";

/// How long a directory listing stays cached.
///
/// Finder issues a `PROPFIND` for a directory and then, in many cases, one
/// `PROPFIND` per child immediately afterwards. Without a cache, opening a
/// folder of 200 files means 201 round trips to the phone. This collapses that
/// burst into one, and is short enough that a file added on the phone shows up
/// on the next look rather than needing a remount.
const LISTING_TTL: Duration = Duration::from_secs(5);

// ── Metadata ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
struct Meta {
    is_dir: bool,
    len: u64,
    /// Epoch millis, as the phone reports them.
    modified_ms: i64,
}

impl DavMetaData for Meta {
    fn len(&self) -> u64 {
        self.len
    }
    fn is_dir(&self) -> bool {
        self.is_dir
    }
    fn modified(&self) -> FsResult<SystemTime> {
        // A phone that reports 0 (or a nonsense negative) would otherwise show
        // as 1970 in Finder's Date Modified column; the epoch is a better lie
        // than a negative duration, which would panic the conversion.
        let ms = self.modified_ms.max(0) as u64;
        Ok(UNIX_EPOCH + Duration::from_millis(ms))
    }
}

/// One row of a directory listing.
#[derive(Debug, Clone)]
struct Entry {
    name: String,
    meta: Meta,
}

impl DavDirEntry for Entry {
    fn name(&self) -> Vec<u8> {
        self.name.as_bytes().to_vec()
    }
    fn metadata(&self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        let m = self.meta.clone();
        Box::pin(async move { Ok(Box::new(m) as Box<dyn DavMetaData>) })
    }
}

/// Names macOS writes on its own that must never reach the phone.
///
/// Finder creates `.DS_Store` in any directory it displays and `._name`
/// AppleDouble sidecars beside files it writes; the rest appear at volume
/// roots. A write to one of these is answered as a success and thrown away —
/// see the module docs for why that is a lie worth telling.
///
/// The list is exact names and one prefix, not a general "hidden file" rule: a
/// dotfile the user actually wants on their phone must still get there.
fn is_finder_junk(name: &str) -> bool {
    name.starts_with("._")
        || matches!(
            name,
            ".DS_Store"
                | ".Trashes"
                | ".fseventsd"
                | ".Spotlight-V100"
                | ".TemporaryItems"
                | ".apdisk"
                | ".DocumentRevisions-V100"
                | ".VolumeIcon.icns"
        )
}

/// Parse one `fs-list` entry. Shape is fixed by `FileRepo.list` on the phone:
/// `{name, dir, size, modified}` — the Android app is the protocol's source of
/// truth, so this reads those names rather than inventing its own.
fn parse_entry(v: &Value) -> Option<Entry> {
    let name = v.get("name")?.as_str()?.to_string();
    // A listing must never contain path separators — a crafted name would
    // otherwise let a phone escape the directory it was listed from when the
    // name is joined onto a path below.
    if name.is_empty() || name.contains('/') || name == "." || name == ".." {
        return None;
    }
    Some(Entry {
        name,
        meta: Meta {
            is_dir: v.get("dir").and_then(Value::as_bool).unwrap_or(false),
            len: v.get("size").and_then(Value::as_u64).unwrap_or(0),
            modified_ms: v.get("modified").and_then(Value::as_i64).unwrap_or(0),
        },
    })
}

// ── Filesystem ───────────────────────────────────────────────────────────

type Listing = Arc<Vec<Entry>>;

#[derive(Clone)]
struct PhoneFs {
    app: AppHandle,
    ws: SharedState,
    /// Where pulled files land before being served. One directory per server
    /// run, cleared on stop.
    cache: PathBuf,
    listings: Arc<tokio::sync::Mutex<std::collections::HashMap<String, (std::time::Instant, Listing)>>>,
    /// Read from config at mount time, not per request. Toggling the setting
    /// while a volume is mounted therefore does nothing until it is remounted —
    /// which is the honest behaviour: Finder has already been told what the
    /// volume supports, and a mount that silently changes capability underneath
    /// it produces failures with no explanation.
    writable: bool,
}

impl PhoneFs {
    /// Map a WebDAV path onto a phone path. `/` becomes `/sdcard`.
    ///
    /// `DavPath` has already resolved `..` and percent-decoding by the time it
    /// reaches here, so this is a join rather than a sanitiser — but the
    /// separator check stays as defence in depth.
    fn phone_path(&self, path: &DavPath) -> String {
        let rel = path.as_pathbuf();
        let rel = rel.to_string_lossy();
        let rel = rel.trim_start_matches('/').trim_end_matches('/');
        if rel.is_empty() {
            PHONE_ROOT.to_string()
        } else {
            format!("{PHONE_ROOT}/{rel}")
        }
    }

    async fn list(&self, phone_path: &str) -> FsResult<Listing> {
        {
            let cache = self.listings.lock().await;
            if let Some((at, listing)) = cache.get(phone_path) {
                if at.elapsed() < LISTING_TTL {
                    return Ok(listing.clone());
                }
            }
        }

        let reply = ws_server::request_default(
            &self.ws,
            crate::map_of(json!({ "type": "fs-list", "path": phone_path })),
        )
        .await
        .map_err(|_| FsError::NotFound)?;

        // The phone answers a listing it can't produce with an `error` field
        // (no all-files permission, or the directory is gone) rather than a
        // transport failure, so an empty `entries` is not the same as an error.
        if reply.get("error").is_some() {
            return Err(FsError::NotFound);
        }
        let entries: Vec<Entry> = reply
            .get("entries")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(parse_entry).collect())
            .unwrap_or_default();

        let listing: Listing = Arc::new(entries);
        self.listings
            .lock()
            .await
            .insert(phone_path.to_string(), (std::time::Instant::now(), listing.clone()));
        Ok(listing)
    }

    /// Metadata for one path, by listing its parent and finding it.
    ///
    /// The protocol has no `fs-stat`, and adding one would mean an Android
    /// change — which this feature deliberately avoids. The parent listing is
    /// cached, so Finder's usual "PROPFIND the directory, then each child"
    /// pattern still costs one round trip in total.
    async fn stat(&self, phone_path: &str) -> FsResult<Meta> {
        if phone_path == PHONE_ROOT {
            return Ok(Meta { is_dir: true, len: 0, modified_ms: 0 });
        }
        let (parent, name) = phone_path.rsplit_once('/').ok_or(FsError::NotFound)?;
        let parent = if parent.is_empty() { "/" } else { parent };
        let listing = self.list(parent).await?;
        listing
            .iter()
            .find(|e| e.name == name)
            .map(|e| e.meta.clone())
            .ok_or(FsError::NotFound)
    }

    /// Drop a cached listing so the next look reflects a write we just made.
    ///
    /// Without this, creating a file and immediately listing its directory
    /// shows the pre-write state for up to `LISTING_TTL` — which Finder reads
    /// as the save having failed.
    async fn forget(&self, phone_path: &str) {
        let parent = phone_path.rsplit_once('/').map(|(p, _)| p).unwrap_or("/");
        let parent = if parent.is_empty() { "/" } else { parent };
        let mut cache = self.listings.lock().await;
        cache.remove(parent);
        cache.remove(phone_path);
    }

    /// Refuse anything that would modify the phone while writes are off.
    fn writes_allowed(&self) -> FsResult<()> {
        write_guard(self.writable)
    }

    async fn ask(&self, body: Value) -> FsResult<Value> {
        let reply = ws_server::request_default(&self.ws, crate::map_of(body))
            .await
            .map_err(|_| FsError::GeneralFailure)?;
        if let Some(e) = reply.get("error").and_then(Value::as_str) {
            eprintln!("[webdav] phone refused: {e}");
            // The phone reports "already exists", "no permission" and "not
            // found" all as `error`. Mapping every one to Exists would make a
            // permission failure look like a name clash, so the generic failure
            // is the honest answer where the text isn't parsed.
            return Err(FsError::GeneralFailure);
        }
        Ok(reply)
    }
}

impl DavFileSystem for PhoneFs {
    fn read_dir<'a>(
        &'a self,
        path: &'a DavPath,
        _meta: ReadDirMeta,
    ) -> FsFuture<'a, FsStream<Box<dyn DavDirEntry>>> {
        Box::pin(async move {
            let listing = self.list(&self.phone_path(path)).await?;
            let items: Vec<FsResult<Box<dyn DavDirEntry>>> = listing
                .iter()
                .cloned()
                .map(|e| Ok(Box::new(e) as Box<dyn DavDirEntry>))
                .collect();
            let stream: FsStream<Box<dyn DavDirEntry>> =
                Box::pin(futures_util::stream::iter(items));
            Ok(stream)
        })
    }

    fn metadata<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, Box<dyn DavMetaData>> {
        Box::pin(async move {
            let m = self.stat(&self.phone_path(path)).await?;
            Ok(Box::new(m) as Box<dyn DavMetaData>)
        })
    }

    fn open<'a>(&'a self, path: &'a DavPath, options: OpenOptions) -> FsFuture<'a, Box<dyn DavFile>> {
        Box::pin(async move {
            if options.write || options.append || options.truncate || options.create_new {
                self.writes_allowed()?;
                let phone_path = self.phone_path(path);
                let name = leaf(&phone_path).to_string();

                // Finder's own droppings never reach the phone. The handle it
                // gets back is a real file in our cache, so writing to it
                // succeeds and reading it back works — it simply never syncs.
                if is_finder_junk(&name) {
                    return Ok(Box::new(
                        WriteFile::discarding(self.cache.join(cache_name(&phone_path, &name))).await?,
                    ) as Box<dyn DavFile>);
                }

                // `append` would mean reading the phone's copy back first and
                // then rewriting the whole file, since the transfer protocol
                // has no partial write. Nothing on macOS opens a WebDAV file
                // for append, so refusing is better than a read-modify-write
                // that silently costs a full download per save.
                if options.append {
                    return Err(FsError::NotImplemented);
                }
                if options.create_new && self.stat(&phone_path).await.is_ok() {
                    return Err(FsError::Exists);
                }

                let local = self.cache.join(cache_name(&phone_path, &name));
                return Ok(Box::new(
                    WriteFile::syncing(
                        self.clone(),
                        local,
                        phone_path,
                    )
                    .await?,
                ) as Box<dyn DavFile>);
            }
            let phone_path = self.phone_path(path);
            let meta = self.stat(&phone_path).await?;
            if meta.is_dir {
                return Err(FsError::Forbidden);
            }

            // Pull to a cache file and serve from there, rather than adding a
            // second streaming reader over the binary frame protocol.
            // `transfer::pull` is the verified path this app already uses for
            // every download.
            let name = phone_path.rsplit('/').next().unwrap_or("file").to_string();
            let dest = self.cache.join(cache_name(&phone_path, &name));
            if !dest.exists() {
                crate::transfer::pull(self.app.clone(), self.ws.clone(), phone_path, dest.clone())
                    .await
                    .map_err(|_| FsError::NotFound)?;
            }
            let file = tokio::fs::File::open(&dest).await.map_err(|_| FsError::NotFound)?;
            Ok(Box::new(CachedFile { file, meta }) as Box<dyn DavFile>)
        })
    }

    fn create_dir<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move {
            self.writes_allowed()?;
            let phone_path = self.phone_path(path);
            let name = leaf(&phone_path).to_string();
            if is_finder_junk(&name) {
                return Ok(());
            }
            // Caps-gated: an older phone would ignore `fs-mkdir` entirely and
            // Finder would sit waiting for a folder that never appears.
            if !ws_server::phone_has_cap(&self.ws, "fsmkdir").await {
                return Err(FsError::NotImplemented);
            }
            let parent = phone_path.rsplit_once('/').map(|(p, _)| p).unwrap_or("/");
            self.ask(json!({
                "type": "fs-mkdir",
                "path": if parent.is_empty() { "/" } else { parent },
                "name": name,
            }))
            .await?;
            self.forget(&phone_path).await;
            Ok(())
        })
    }

    fn remove_file<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move { self.remove(path).await })
    }

    /// Same operation as [`Self::remove_file`] — the phone's `fs-delete` is
    /// recursive and does not distinguish, so splitting them here would only
    /// invent a difference the other end does not have.
    fn remove_dir<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move { self.remove(path).await })
    }

    fn rename<'a>(&'a self, from: &'a DavPath, to: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move {
            self.writes_allowed()?;
            let src = self.phone_path(from);
            let dst = self.phone_path(to);
            let (src_parent, _) = src.rsplit_once('/').ok_or(FsError::NotFound)?;
            let (dst_parent, dst_name) = dst.rsplit_once('/').ok_or(FsError::NotFound)?;

            if is_finder_junk(leaf(&src)) || is_finder_junk(dst_name) {
                return Ok(());
            }
            // Same-directory renames only — see the module docs for why moving
            // between directories is refused rather than emulated.
            if src_parent != dst_parent {
                return Err(FsError::NotImplemented);
            }
            self.ask(json!({ "type": "fs-rename", "path": src, "name": dst_name })).await?;
            self.forget(&src).await;
            self.forget(&dst).await;
            Ok(())
        })
    }
}

/// The single gate every mutating operation passes through.
///
/// A free function so it can be tested for what it is — the thing standing
/// between a read-only mount and the user's phone storage — without needing an
/// `AppHandle` to build a `PhoneFs` around.
fn write_guard(writable: bool) -> FsResult<()> {
    if writable {
        Ok(())
    } else {
        Err(FsError::Forbidden)
    }
}

/// The last path component. `phone_path` never produces a trailing slash, so
/// this is the file or directory name.
fn leaf(phone_path: &str) -> &str {
    phone_path.rsplit('/').next().unwrap_or(phone_path)
}

impl PhoneFs {
    async fn remove(&self, path: &DavPath) -> FsResult<()> {
        self.writes_allowed()?;
        let phone_path = self.phone_path(path);
        // Deleting junk that was never sent has to succeed, or Finder reports
        // an error emptying a folder it filled itself.
        if is_finder_junk(leaf(&phone_path)) {
            return Ok(());
        }
        // Refusing to delete the volume root is not paranoia: `fs-delete` is
        // recursive on the phone, so one stray request here wipes /sdcard.
        if phone_path == PHONE_ROOT {
            return Err(FsError::Forbidden);
        }
        self.ask(json!({ "type": "fs-delete", "path": phone_path })).await?;
        self.forget(&phone_path).await;
        Ok(())
    }
}

/// A cache filename that can't collide across directories and can't escape the
/// cache dir — the phone path's bytes in hex, plus the real extension so
/// `mime_guess` still picks the right content type.
fn cache_name(phone_path: &str, name: &str) -> String {
    let mut s = String::with_capacity(phone_path.len() * 2 + 8);
    for b in phone_path.as_bytes() {
        s.push_str(&format!("{b:02x}"));
    }
    match name.rsplit_once('.') {
        Some((_, ext)) if !ext.is_empty() && ext.len() <= 8 && ext.chars().all(|c| c.is_ascii_alphanumeric()) => {
            format!("{s}.{ext}")
        }
        _ => s,
    }
}

/// `DavFile` requires `Debug`; `tokio::fs::File` has no useful one, and the
/// path is deliberately not held here — a cache filename in a log line would
/// hex-encode the phone path back into readable form.
struct CachedFile {
    file: tokio::fs::File,
    meta: Meta,
}

impl std::fmt::Debug for CachedFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CachedFile").field("len", &self.meta.len).finish()
    }
}

impl DavFile for CachedFile {
    fn metadata(&mut self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        let m = self.meta.clone();
        Box::pin(async move { Ok(Box::new(m) as Box<dyn DavMetaData>) })
    }

    fn read_bytes(&mut self, count: usize) -> FsFuture<'_, bytes::Bytes> {
        Box::pin(async move {
            use tokio::io::AsyncReadExt;
            let mut buf = vec![0u8; count];
            let n = self.file.read(&mut buf).await.map_err(|_| FsError::GeneralFailure)?;
            buf.truncate(n);
            Ok(bytes::Bytes::from(buf))
        })
    }

    fn seek(&mut self, pos: std::io::SeekFrom) -> FsFuture<'_, u64> {
        Box::pin(async move {
            use tokio::io::AsyncSeekExt;
            self.file.seek(pos).await.map_err(|_| FsError::GeneralFailure)
        })
    }

    // Every mutating operation is refused rather than silently succeeding — a
    // no-op write would let Finder believe it had saved a file it hadn't.
    fn write_bytes(&mut self, _buf: bytes::Bytes) -> FsFuture<'_, ()> {
        Box::pin(async move { Err(FsError::Forbidden) })
    }
    fn write_buf(&mut self, _buf: Box<dyn bytes::Buf + Send>) -> FsFuture<'_, ()> {
        Box::pin(async move { Err(FsError::Forbidden) })
    }
    fn flush(&mut self) -> FsFuture<'_, ()> {
        Box::pin(async move { Ok(()) })
    }
}

/// A file being written through the mount.
///
/// # Why it buffers instead of streaming
///
/// The link's push is `fs-push-begin` → chunks → `fs-push-done`, and it
/// declares the total size up front — the phone allocates a receiver for
/// exactly that many bytes. WebDAV gives no size until the last byte arrives
/// (`Content-Length` is advisory and absent for chunked uploads), so there is
/// nothing to declare at the point streaming would have to start.
///
/// So bytes land in the same per-mount cache directory reads already use, and
/// the push happens on close, when the size is known. The cost is that a save
/// is not durable on the phone until the app closes the file — which is exactly
/// when Finder expects a save to complete anyway.
///
/// # `flush` is not close
///
/// `dav-server` calls `flush` at the end of a `PUT`, and an application may
/// call it mid-write. Pushing on every flush would send the file once per call
/// — so the push is guarded by `synced` and the local file is closed first, or
/// the phone can receive a partially-written file.
struct WriteFile {
    file: Option<tokio::fs::File>,
    local: PathBuf,
    /// `None` for a discarded write — Finder junk, which is written to the
    /// cache and never sent. See [`is_finder_junk`].
    target: Option<(PhoneFs, String)>,
    written: u64,
    synced: bool,
}

impl std::fmt::Debug for WriteFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // The phone path is deliberately not printed: it is user data, and this
        // type's Debug is what ends up in dav-server's own logging.
        f.debug_struct("WriteFile")
            .field("written", &self.written)
            .field("discarded", &self.target.is_none())
            .finish()
    }
}

impl WriteFile {
    async fn create(local: PathBuf, target: Option<(PhoneFs, String)>) -> FsResult<Self> {
        let file = tokio::fs::File::create(&local)
            .await
            .map_err(|_| FsError::GeneralFailure)?;
        Ok(Self { file: Some(file), local, target, written: 0, synced: false })
    }

    /// A real local file that never reaches the phone.
    async fn discarding(local: PathBuf) -> FsResult<Self> {
        Self::create(local, None).await
    }

    async fn syncing(fs: PhoneFs, local: PathBuf, phone_path: String) -> FsResult<Self> {
        Self::create(local, Some((fs, phone_path))).await
    }

    async fn sync(&mut self) -> FsResult<()> {
        if self.synced {
            return Ok(());
        }
        self.synced = true;

        // Close the local file before reading it back: an open handle with
        // buffered bytes would push a file missing its tail.
        if let Some(mut f) = self.file.take() {
            use tokio::io::AsyncWriteExt;
            f.flush().await.map_err(|_| FsError::GeneralFailure)?;
            drop(f);
        }

        let Some((fs, phone_path)) = self.target.clone() else {
            return Ok(());
        };
        let (dir, _) = phone_path.rsplit_once('/').ok_or(FsError::GeneralFailure)?;
        crate::transfer::push(
            fs.app.clone(),
            fs.ws.clone(),
            self.local.to_string_lossy().into_owned(),
            if dir.is_empty() { "/".into() } else { dir.to_string() },
            // Always overwrite. Without it the phone's `uniqueDest` forks every
            // save into "name (2).ext" and leaves the file the user opened
            // untouched, while reporting success — the exact failure this flag
            // was added for.
            true,
        )
        .await
        .map_err(|e| {
            eprintln!("[webdav] push failed: {e}");
            FsError::GeneralFailure
        })?;
        fs.forget(&phone_path).await;
        Ok(())
    }
}

impl DavFile for WriteFile {
    fn metadata(&mut self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        Box::pin(async move {
            Ok(Box::new(Meta { is_dir: false, len: self.written, modified_ms: 0 })
                as Box<dyn DavMetaData>)
        })
    }

    fn write_bytes(&mut self, buf: bytes::Bytes) -> FsFuture<'_, ()> {
        Box::pin(async move {
            use tokio::io::AsyncWriteExt;
            let file = self.file.as_mut().ok_or(FsError::GeneralFailure)?;
            file.write_all(&buf).await.map_err(|_| FsError::GeneralFailure)?;
            self.written += buf.len() as u64;
            Ok(())
        })
    }

    fn write_buf(&mut self, mut buf: Box<dyn bytes::Buf + Send>) -> FsFuture<'_, ()> {
        Box::pin(async move {
            use bytes::Buf;
            use tokio::io::AsyncWriteExt;
            let file = self.file.as_mut().ok_or(FsError::GeneralFailure)?;
            while buf.has_remaining() {
                let chunk = buf.chunk().to_vec();
                file.write_all(&chunk).await.map_err(|_| FsError::GeneralFailure)?;
                self.written += chunk.len() as u64;
                buf.advance(chunk.len());
            }
            Ok(())
        })
    }

    fn read_bytes(&mut self, _count: usize) -> FsFuture<'_, bytes::Bytes> {
        // A handle opened for writing is never read back through the mount;
        // reads go through `CachedFile`.
        Box::pin(async move { Err(FsError::NotImplemented) })
    }

    fn seek(&mut self, _pos: std::io::SeekFrom) -> FsFuture<'_, u64> {
        // Seeking would mean a sparse or out-of-order write, and the push sends
        // one contiguous file. Refused rather than silently producing a file
        // whose bytes are in the wrong places.
        Box::pin(async move { Err(FsError::NotImplemented) })
    }

    fn flush(&mut self) -> FsFuture<'_, ()> {
        Box::pin(async move { self.sync().await })
    }
}

// ── Server ───────────────────────────────────────────────────────────────

/// A running mount: the loopback port, the capability token, and the handle
/// that shuts the listener down.
pub struct Running {
    pub port: u16,
    pub token: String,
    stop: tokio::sync::oneshot::Sender<()>,
    cache: PathBuf,
}

#[derive(Default)]
pub struct WebdavState(pub std::sync::Mutex<Option<Running>>);

#[derive(serde::Serialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct WebdavStatus {
    pub running: bool,
    pub url: Option<String>,
    /// Where it is mounted in Finder, once `mount_webdav` has succeeded.
    pub mount_point: Option<String>,
}

/// Volume name in Finder. `mount_webdav` uses the last path component of the
/// mount point, so this is what the user sees in the sidebar.
const VOLUME_DIR: &str = "DroidDock Phone";

fn mount_point() -> Option<PathBuf> {
    let home = std::env::var("HOME").ok()?;
    Some(PathBuf::from(home).join(format!("Library/Caches/DroidDock/{VOLUME_DIR}")))
}

/// Start the server and mount it in Finder.
#[tauri::command]
pub async fn webdav_start(
    app: AppHandle,
    ws: tauri::State<'_, SharedState>,
    state: tauri::State<'_, WebdavState>,
) -> Result<WebdavStatus, String> {
    if state.0.lock().unwrap().is_some() {
        return webdav_status(state);
    }

    // A fresh token per run, so a mount left over from a previous session
    // cannot keep reading the phone.
    let token = uuid::Uuid::new_v4().to_string();
    let cache = std::env::temp_dir().join(format!("droiddock-dav-{token}"));
    std::fs::create_dir_all(&cache).map_err(|e| e.to_string())?;

    // Read once, here. See the field's own note for why this is not consulted
    // per request.
    let writable = {
        use tauri::Manager;
        app.state::<crate::AppState>().config.lock().unwrap().webdav_writable
    };

    let fs = PhoneFs {
        app: app.clone(),
        ws: ws.inner().clone(),
        cache: cache.clone(),
        listings: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
        writable,
    };
    // The token is the first path segment, so it is stripped before the
    // filesystem ever sees a path — `strip_prefix` is what enforces it.
    // A lock system is only attached when writes are on. macOS asks to LOCK
    // before writing, and `FakeLs` answers from memory — nothing reaches the
    // phone, because a lock on a volume only this Mac can reach protects
    // against nothing. Leaving it off for a read-only mount keeps that mount
    // advertising itself as class-1 WebDAV, which is what it is.
    let mut builder = dav_server::DavHandler::builder()
        .filesystem(Box::new(fs))
        .strip_prefix(format!("/{token}"));
    if writable {
        builder = builder.locksystem(dav_server::fakels::FakeLs::new());
    }
    let dav = builder.build_handler();

    // Port 0 = let the OS choose. Loopback only.
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|e| format!("couldn't open a local port: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();

    let (stop_tx, mut stop_rx) = tokio::sync::oneshot::channel();
    let expected = format!("/{token}");
    tokio::spawn(async move {
        loop {
            let accepted = tokio::select! {
                _ = &mut stop_rx => break,
                a = listener.accept() => a,
            };
            let Ok((stream, _)) = accepted else { continue };
            let dav = dav.clone();
            let expected = expected.clone();
            tokio::spawn(async move {
                let io = hyper_util::rt::TokioIo::new(stream);
                let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
                    let dav = dav.clone();
                    let expected = expected.clone();
                    async move {
                        // Capability check. 404 rather than 403: a local process
                        // guessing at the port learns nothing about what is here.
                        let p = req.uri().path();
                        if !(p == expected || p.starts_with(&format!("{expected}/"))) {
                            return Ok::<_, std::convert::Infallible>(
                                hyper::Response::builder()
                                    .status(404)
                                    .body(dav_server::body::Body::from("".to_string()))
                                    .expect("static 404 response is well-formed"),
                            );
                        }
                        Ok(dav.handle(req).await)
                    }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(io, svc)
                    .await;
            });
        }
    });

    let mp = mount_point().ok_or("no home directory")?;
    std::fs::create_dir_all(&mp).map_err(|e| e.to_string())?;
    let url = format!("http://127.0.0.1:{port}/{token}");

    // `mount_webdav` is the same thing Finder's "Connect to Server" uses.
    // `-S` keeps it out of the keychain prompt path; there are no credentials
    // here because the token is in the URL.
    let out = tokio::process::Command::new("/sbin/mount_webdav")
        .args(["-S", "-v", VOLUME_DIR, &url, &mp.to_string_lossy()])
        .output()
        .await
        .map_err(|e| format!("mount_webdav didn't run: {e}"))?;
    if !out.status.success() {
        let _ = stop_tx.send(());
        let _ = std::fs::remove_dir_all(&cache);
        let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
        return Err(if err.is_empty() {
            "macOS refused to mount the phone volume".to_string()
        } else {
            err
        });
    }

    *state.0.lock().unwrap() = Some(Running { port, token, stop: stop_tx, cache });
    webdav_status(state)
}

/// Unmount and stop the server.
#[tauri::command]
pub async fn webdav_stop(state: tauri::State<'_, WebdavState>) -> Result<WebdavStatus, String> {
    let running = state.0.lock().unwrap().take();
    let Some(running) = running else {
        return Ok(WebdavStatus::default());
    };
    if let Some(mp) = mount_point() {
        // Unmount before killing the listener: umount on a server that has
        // already gone away leaves the volume wedged until a reboot.
        let _ = tokio::process::Command::new("/sbin/umount")
            .arg(mp.to_string_lossy().to_string())
            .output()
            .await;
    }
    let _ = running.stop.send(());
    let _ = std::fs::remove_dir_all(&running.cache);
    Ok(WebdavStatus::default())
}

#[tauri::command]
pub fn webdav_status(state: tauri::State<'_, WebdavState>) -> Result<WebdavStatus, String> {
    let g = state.0.lock().unwrap();
    Ok(match g.as_ref() {
        None => WebdavStatus::default(),
        Some(r) => WebdavStatus {
            running: true,
            url: Some(format!("http://127.0.0.1:{}/{}", r.port, r.token)),
            mount_point: mount_point().map(|p| p.to_string_lossy().to_string()),
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A listing entry carrying a separator must be dropped, not joined onto a
    /// path. The phone is trusted, but a filename is still attacker-influenced
    /// data — anything that syncs a file onto the device names it.
    #[test]
    fn listing_entries_cannot_escape_their_directory() {
        assert!(parse_entry(&json!({ "name": "../../etc/passwd", "dir": false })).is_none());
        assert!(parse_entry(&json!({ "name": "a/b", "dir": false })).is_none());
        assert!(parse_entry(&json!({ "name": "..", "dir": true })).is_none());
        assert!(parse_entry(&json!({ "name": ".", "dir": true })).is_none());
        assert!(parse_entry(&json!({ "name": "", "dir": false })).is_none());
        assert!(parse_entry(&json!({ "dir": false })).is_none());
    }

    #[test]
    fn ordinary_entries_parse_with_their_metadata() {
        let e = parse_entry(&json!({
            "name": "photo.jpg", "dir": false, "size": 1234, "modified": 1_700_000_000_000i64
        }))
        .expect("should parse");
        assert_eq!(e.name, "photo.jpg");
        assert!(!e.meta.is_dir);
        assert_eq!(e.meta.len, 1234);
        assert_eq!(e.meta.modified_ms, 1_700_000_000_000);

        // A directory with no size reported still parses, as 0.
        let d = parse_entry(&json!({ "name": "DCIM", "dir": true })).expect("should parse");
        assert!(d.meta.is_dir);
        assert_eq!(d.meta.len, 0);
    }

    /// A phone reporting 0 or a negative mtime must not panic the conversion —
    /// `UNIX_EPOCH + Duration` cannot represent a negative offset.
    #[test]
    fn a_nonsense_mtime_clamps_instead_of_panicking() {
        let m = Meta { is_dir: false, len: 0, modified_ms: -5 };
        assert_eq!(m.modified().unwrap(), UNIX_EPOCH);
        let m0 = Meta { is_dir: false, len: 0, modified_ms: 0 };
        assert_eq!(m0.modified().unwrap(), UNIX_EPOCH);
    }

    /// Cache names must be collision-free across directories (same filename,
    /// different folder) and must never contain a separator.
    #[test]
    fn cache_names_are_unique_per_path_and_flat() {
        let a = cache_name("/sdcard/DCIM/a.jpg", "a.jpg");
        let b = cache_name("/sdcard/Download/a.jpg", "a.jpg");
        assert_ne!(a, b);
        for n in [&a, &b] {
            assert!(!n.contains('/'));
            assert!(!n.contains(".."));
        }
        assert!(a.ends_with(".jpg"), "extension is kept for mime detection");
        // A hostile "extension" must not survive into the cache filename.
        assert!(!cache_name("/sdcard/x.a/b", "x.a/b").contains('/'));
    }

    /// The filter is what makes a writable mount survivable: without it,
    /// *browsing* the phone in Finder leaves `.DS_Store` in every folder.
    #[test]
    fn finder_junk_is_recognised() {
        for n in [
            ".DS_Store",
            "._notes.txt",
            "._",
            ".Trashes",
            ".fseventsd",
            ".Spotlight-V100",
            ".TemporaryItems",
            ".apdisk",
            ".DocumentRevisions-V100",
            ".VolumeIcon.icns",
        ] {
            assert!(is_finder_junk(n), "{n} should be filtered");
        }
    }

    /// It is a fixed list, not a "hidden files" rule. A dotfile the user
    /// actually put on their phone has to keep working — and a normal file
    /// whose name merely resembles one must not be silently discarded.
    #[test]
    fn ordinary_files_are_not_junk() {
        for n in [
            ".gitignore",
            ".bashrc",
            "DS_Store",
            "notes.txt",
            "_photo.jpg",
            "my._file.txt",
            ".Trash",
            "IMG_0001.HEIC",
        ] {
            assert!(!is_finder_junk(n), "{n} must reach the phone");
        }
    }

    #[test]
    fn leaf_is_the_last_component() {
        assert_eq!(leaf("/sdcard/Download/a.txt"), "a.txt");
        assert_eq!(leaf("/sdcard"), "sdcard");
        assert_eq!(leaf("a.txt"), "a.txt");
    }

    /// Every mutating path calls this first, so a read-only mount cannot be
    /// talked into a write by any request shape.
    #[test]
    fn writes_are_refused_while_read_only() {
        assert!(matches!(write_guard(false), Err(FsError::Forbidden)));
        assert!(write_guard(true).is_ok());
    }
}
