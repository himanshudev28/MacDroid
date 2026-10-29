//! The C ABI (`include/droiddock_core.h`, ABI v1). Every body is wrapped in
//! `catch_unwind`: nothing may unwind across the FFI boundary.

use crate::host::{async_runtime, AppHandle};
use futures_util::FutureExt;
use serde_json::Value;
use std::ffi::{c_char, c_void, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, OnceLock};
use std::time::Duration;

pub const DD_ABI_VERSION: u32 = 1;
pub const DD_OK: i32 = 0;
pub const DD_ERR_ALREADY: i32 = 1;
pub const DD_ERR_BAD_CONFIG: i32 = 2;
pub const DD_ERR_INIT: i32 = 3;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct DdCallbacks {
    pub ctx: *mut c_void,
    pub on_event: Option<unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char)>,
    pub on_frame: Option<unsafe extern "C" fn(*mut c_void, u32, *const u8, usize)>,
    pub on_result: Option<unsafe extern "C" fn(*mut c_void, u64, i32, *const c_char)>,
}
unsafe impl Send for DdCallbacks {}
unsafe impl Sync for DdCallbacks {}

impl DdCallbacks {
    /// No callbacks at all (tests).
    pub fn null() -> Self {
        DdCallbacks { ctx: std::ptr::null_mut(), on_event: None, on_frame: None, on_result: None }
    }
}

static STARTED: AtomicBool = AtomicBool::new(false);
static APP: OnceLock<AppHandle> = OnceLock::new();
static CBS: OnceLock<DdCallbacks> = OnceLock::new();

#[no_mangle]
pub extern "C" fn dd_abi_version() -> u32 {
    DD_ABI_VERSION
}

unsafe fn cstr(p: *const c_char) -> Option<String> {
    if p.is_null() {
        None
    } else {
        Some(CStr::from_ptr(p).to_string_lossy().into_owned())
    }
}

/// # Safety
/// `config_json` must be a valid NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn dd_core_start(config_json: *const c_char, cbs: DdCallbacks) -> i32 {
    let cfg = cstr(config_json);
    catch_unwind(AssertUnwindSafe(|| start(cfg, cbs))).unwrap_or(DD_ERR_INIT)
}

fn start(cfg: Option<String>, cbs: DdCallbacks) -> i32 {
    // A Rust `main` ignores SIGPIPE; a static library hosted by Swift does not. A write to a closed
    // pipe/socket (adb or scrcpy stdin, Finder's WebDAV socket, a vanished peer) would kill the app.
    #[cfg(unix)]
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
    }
    // Before anything spawns: a panic in a background task is otherwise silent.
    crate::crash::install();

    let Some(cfg) = cfg.and_then(|s| serde_json::from_str::<Value>(&s).ok()) else {
        return DD_ERR_BAD_CONFIG;
    };
    let Some(data_dir) = cfg.get("dataDir").and_then(Value::as_str).filter(|s| !s.is_empty()) else {
        return DD_ERR_BAD_CONFIG;
    };
    let identifier = cfg
        .get("identifier")
        .and_then(Value::as_str)
        .unwrap_or("com.droiddock.mac")
        .to_string();
    let version = cfg
        .get("version")
        .and_then(Value::as_str)
        .unwrap_or(env!("CARGO_PKG_VERSION"))
        .to_string();
    if cfg.get("dev").and_then(Value::as_bool) == Some(true) {
        std::env::set_var("DD_DEV", "1");
    }

    if STARTED.swap(true, Ordering::SeqCst) {
        return DD_ERR_ALREADY;
    }
    let data_dir = PathBuf::from(data_dir);
    if std::fs::create_dir_all(&data_dir).is_err() {
        STARTED.store(false, Ordering::SeqCst);
        return DD_ERR_INIT;
    }

    let _ = CBS.set(cbs);
    let app = AppHandle::new(cbs, data_dir, identifier, version);
    let _ = APP.set(app.clone());
    // Build the runtime now so the first `spawn` is not paying for it.
    let _ = async_runtime::runtime();
    crate::setup(&app);
    DD_OK
}

fn deliver(req_id: u64, r: Result<Value, String>) {
    let Some(cbs) = CBS.get() else { return };
    let Some(cb) = cbs.on_result else { return };
    let (ok, text) = match r {
        Ok(v) => (1, serde_json::to_string(&v).unwrap_or_else(|_| "null".into())),
        Err(e) => (0, serde_json::to_string(&e).unwrap_or_else(|_| "\"error\"".into())),
    };
    // serde_json escapes NUL, so this cannot fail.
    if let Ok(c) = CString::new(text) {
        unsafe { cb(cbs.ctx, req_id, ok, c.as_ptr()) };
    }
}

fn invoke(cmd: String, args: Option<String>, body: Option<Vec<u8>>, req_id: u64) {
    let Some(app) = APP.get().cloned() else {
        deliver(req_id, Err("core not started".into()));
        return;
    };
    let args = match args.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        None => Value::Object(Default::default()),
        Some(s) => match serde_json::from_str::<Value>(s) {
            Ok(Value::Null) => Value::Object(Default::default()),
            Ok(v) => v,
            Err(e) => {
                deliver(req_id, Err(format!("bad args JSON: {e}")));
                return;
            }
        },
    };
    async_runtime::spawn_command(async move {
        let fut = crate::dispatch::dispatch(&app, &cmd, args, body);
        let r = match AssertUnwindSafe(fut).catch_unwind().await {
            Ok(r) => r,
            Err(_) => Err(format!("internal error: `{cmd}` panicked")),
        };
        deliver(req_id, r);
    });
}

/// # Safety
/// `cmd` must be a valid NUL-terminated string; `args_json` may be NULL.
#[no_mangle]
pub unsafe extern "C" fn dd_core_invoke(cmd: *const c_char, args_json: *const c_char, req_id: u64) {
    let (cmd, args) = (cstr(cmd), cstr(args_json));
    let _ = catch_unwind(AssertUnwindSafe(|| match cmd {
        Some(cmd) => invoke(cmd, args, None, req_id),
        None => deliver(req_id, Err("missing command".into())),
    }));
}

/// # Safety
/// As `dd_core_invoke`; `bytes` must point to `len` readable bytes (or be NULL
/// when `len` is 0).
#[no_mangle]
pub unsafe extern "C" fn dd_core_invoke_bytes(
    cmd: *const c_char,
    args_json: *const c_char,
    bytes: *const u8,
    len: usize,
    req_id: u64,
) {
    let (cmd, args) = (cstr(cmd), cstr(args_json));
    let body = if bytes.is_null() || len == 0 {
        Vec::new()
    } else {
        std::slice::from_raw_parts(bytes, len).to_vec()
    };
    let _ = catch_unwind(AssertUnwindSafe(|| match cmd {
        Some(cmd) => invoke(cmd, args, Some(body), req_id),
        None => deliver(req_id, Err("missing command".into())),
    }));
}

/// Blocking, bounded (~8 s). Idempotent (`shutdown::on_quit` runs once).
#[no_mangle]
pub extern "C" fn dd_core_shutdown() {
    let _ = catch_unwind(|| {
        let Some(app) = APP.get().cloned() else { return };
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let _ = catch_unwind(AssertUnwindSafe(|| crate::shutdown::on_quit(&app)));
            let _ = tx.send(());
        });
        let _ = rx.recv_timeout(Duration::from_secs(8));
    });
}
