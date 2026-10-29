//! A small shim that mimics the subset of the Tauri API the core used, so the
//! protocol / transfer / ADB code carries over unchanged. See
//! `droiddock-native/ARCHITECTURE.md` section C.

pub mod async_runtime;
pub mod ipc;

use crate::ffi::DdCallbacks;
use serde::Serialize;
use std::any::{Any, TypeId};
use std::collections::HashMap;
use std::ffi::CString;
use std::marker::PhantomData;
use std::ops::Deref;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub struct Error(pub String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for Error {}

/// Empty marker traits: the real methods are inherent on [`AppHandle`], these
/// only keep `use ...::{Manager, Emitter}` lines compiling.
pub trait Manager {}
pub trait Emitter {}

pub struct HostConfig {
    pub identifier: String,
}
pub struct PackageInfo {
    pub version: String,
}

struct Inner {
    states: RwLock<HashMap<TypeId, Arc<dyn Any + Send + Sync>>>,
    cbs: DdCallbacks,
    paths: PathResolver,
    config: HostConfig,
    package: PackageInfo,
}

#[derive(Clone)]
pub struct AppHandle(Arc<Inner>);

impl Manager for AppHandle {}
impl Emitter for AppHandle {}

impl AppHandle {
    pub fn new(cbs: DdCallbacks, data_dir: PathBuf, identifier: String, version: String) -> Self {
        AppHandle(Arc::new(Inner {
            states: RwLock::new(HashMap::new()),
            cbs,
            paths: PathResolver { data_dir },
            config: HostConfig { identifier },
            package: PackageInfo { version },
        }))
    }

    /// Returns false (and keeps the old value) if `T` is already managed.
    pub fn manage<T: Send + Sync + 'static>(&self, v: T) -> bool {
        let mut map = self.0.states.write().unwrap();
        let id = TypeId::of::<T>();
        if map.contains_key(&id) {
            return false;
        }
        map.insert(id, Arc::new(v));
        true
    }

    pub fn state<T: Send + Sync + 'static>(&self) -> State<'_, T> {
        self.try_state::<T>()
            .unwrap_or_else(|| panic!("state not managed: {}", std::any::type_name::<T>()))
    }

    pub fn try_state<T: Send + Sync + 'static>(&self) -> Option<State<'_, T>> {
        let map = self.0.states.read().unwrap();
        let any = map.get(&TypeId::of::<T>())?.clone();
        let arc = any.downcast::<T>().ok()?;
        Some(State(arc, PhantomData))
    }

    pub fn emit<S: Serialize>(&self, event: &str, payload: S) -> Result<()> {
        let json = serde_json::to_string(&payload).map_err(|e| Error(e.to_string()))?;
        let name = CString::new(event).map_err(|e| Error(e.to_string()))?;
        let body = CString::new(json).map_err(|e| Error(e.to_string()))?;
        if let Some(cb) = self.0.cbs.on_event {
            unsafe { cb(self.0.cbs.ctx, name.as_ptr(), body.as_ptr()) };
        }
        Ok(())
    }

    /// Single native window: the target is irrelevant.
    pub fn emit_to<S: Serialize>(&self, _target: &str, event: &str, payload: S) -> Result<()> {
        self.emit(event, payload)
    }

    pub fn emit_frame(&self, stream: u32, bytes: &[u8]) {
        if let Some(cb) = self.0.cbs.on_frame {
            unsafe { cb(self.0.cbs.ctx, stream, bytes.as_ptr(), bytes.len()) };
        }
    }

    pub fn path(&self) -> &PathResolver {
        &self.0.paths
    }
    pub fn config(&self) -> &HostConfig {
        &self.0.config
    }
    pub fn package_info(&self) -> &PackageInfo {
        &self.0.package
    }
    pub fn handle(&self) -> &AppHandle {
        self
    }
    pub fn app_handle(&self) -> AppHandle {
        self.clone()
    }
    pub fn opener(&self) -> Opener {
        Opener
    }
}

pub struct State<'a, T: Send + Sync + 'static>(Arc<T>, PhantomData<&'a T>);

impl<'a, T: Send + Sync + 'static> State<'a, T> {
    pub fn inner(&self) -> &T {
        &self.0
    }
}
impl<'a, T: Send + Sync + 'static> Deref for State<'a, T> {
    type Target = T;
    fn deref(&self) -> &T {
        &self.0
    }
}
impl<'a, T: Send + Sync + 'static> Clone for State<'a, T> {
    fn clone(&self) -> Self {
        State(self.0.clone(), PhantomData)
    }
}

pub struct PathResolver {
    data_dir: PathBuf,
}

impl PathResolver {
    pub fn app_data_dir(&self) -> Result<PathBuf> {
        Ok(self.data_dir.clone())
    }
    pub fn home_dir(&self) -> Result<PathBuf> {
        std::env::var_os("HOME").map(PathBuf::from).ok_or_else(|| Error("no HOME".into()))
    }
    pub fn download_dir(&self) -> Result<PathBuf> {
        self.home_dir().map(|h| h.join("Downloads"))
    }
    pub fn picture_dir(&self) -> Result<PathBuf> {
        self.home_dir().map(|h| h.join("Pictures"))
    }
    pub fn video_dir(&self) -> Result<PathBuf> {
        self.home_dir().map(|h| h.join("Movies"))
    }
}

/// `/usr/bin/open` wrapper standing in for tauri-plugin-opener.
pub struct Opener;

impl Opener {
    /// `with` is the application to open the path with (`open -a`).
    pub fn open_path<P: AsRef<str>, W: AsRef<str>>(&self, path: P, with: Option<W>) -> Result<()> {
        let mut cmd = std::process::Command::new("/usr/bin/open");
        if let Some(w) = with {
            cmd.arg("-a").arg(w.as_ref());
        }
        cmd.arg(path.as_ref());
        cmd.spawn().map(|_| ()).map_err(|e| Error(e.to_string()))
    }

    pub fn reveal_item_in_dir<P: AsRef<std::path::Path>>(&self, path: P) -> Result<()> {
        std::process::Command::new("/usr/bin/open")
            .arg("-R")
            .arg(path.as_ref())
            .spawn()
            .map(|_| ())
            .map_err(|e| Error(e.to_string()))
    }
}

pub fn is_dev() -> bool {
    std::env::var_os("DD_DEV").is_some()
}
