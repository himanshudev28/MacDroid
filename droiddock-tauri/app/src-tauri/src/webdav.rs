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
//! # Read-only, deliberately
//!
//! `PUT`/`DELETE`/`MOVE`/`LOCK` are not implemented. Two reasons:
//!
//! 1. Finder is aggressive with writes — it drops `.DS_Store` into every
//!    directory it looks at, and writes `._` AppleDouble sidecars next to
//!    files. Mounting read-write means Finder litters the phone's storage as a
//!    side effect of browsing it.
//! 2. A write bug here destroys the user's phone files. A read bug shows the
//!    wrong listing.
//!
//! The existing Files tab still does uploads, renames and deletes, with
//! confirmation, which is the right place for destructive operations.
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
            // Read-only: anything that would modify the phone is refused here
            // rather than half-implemented. See the module docs.
            if options.write || options.append || options.truncate || options.create_new {
                return Err(FsError::Forbidden);
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

    let fs = PhoneFs {
        app: app.clone(),
        ws: ws.inner().clone(),
        cache: cache.clone(),
        listings: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
    };
    // The token is the first path segment, so it is stripped before the
    // filesystem ever sees a path — `strip_prefix` is what enforces it.
    let dav = dav_server::DavHandler::builder()
        .filesystem(Box::new(fs))
        .strip_prefix(format!("/{token}"))
        .build_handler();

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
}
