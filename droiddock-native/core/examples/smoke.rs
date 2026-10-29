//! Smoke test for the C ABI, driven from Rust.
//!
//! Uses a throwaway data dir with port 48600 - never the real app's. Does NOT
//! call `dd_core_shutdown` (that would `adb kill-server` the user's adb).
//!
//!   cargo run --release --target aarch64-apple-darwin --example smoke
//! then, while it sleeps:
//!   lsof -nP -iTCP:48600 -sTCP:LISTEN
//!   timeout 5 dns-sd -B _droiddock._tcp local.

use droiddock_core::ffi::*;
use serde_json::Value;
use std::collections::HashMap;
use std::ffi::{c_char, c_void, CStr, CString};
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// Default 48600; override with DD_SMOKE_PORT if another dev instance has it.
fn port() -> u16 {
    std::env::var("DD_SMOKE_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(48600)
}

type Results = (Mutex<HashMap<u64, (i32, String)>>, Condvar);
fn results() -> &'static Results {
    static R: OnceLock<Results> = OnceLock::new();
    R.get_or_init(|| (Mutex::new(HashMap::new()), Condvar::new()))
}
fn events() -> &'static Mutex<Vec<(String, String)>> {
    static E: OnceLock<Mutex<Vec<(String, String)>>> = OnceLock::new();
    E.get_or_init(|| Mutex::new(Vec::new()))
}

unsafe extern "C" fn on_event(_: *mut c_void, name: *const c_char, payload: *const c_char) {
    let n = CStr::from_ptr(name).to_string_lossy().into_owned();
    let p = CStr::from_ptr(payload).to_string_lossy().into_owned();
    let shown: String = p.chars().take(160).collect();
    println!("[event] {n} {shown}");
    events().lock().unwrap().push((n, p));
}
unsafe extern "C" fn on_frame(_: *mut c_void, stream: u32, _data: *const u8, len: usize) {
    println!("[frame] stream={stream} len={len}");
}
unsafe extern "C" fn on_result(_: *mut c_void, id: u64, ok: i32, json: *const c_char) {
    let s = CStr::from_ptr(json).to_string_lossy().into_owned();
    let (m, cv) = results();
    m.lock().unwrap().insert(id, (ok, s));
    cv.notify_all();
}

fn call(id: u64, cmd: &str, args: &str) -> (i32, String) {
    let c = CString::new(cmd).unwrap();
    let a = CString::new(args).unwrap();
    unsafe { dd_core_invoke(c.as_ptr(), a.as_ptr(), id) };
    let (m, cv) = results();
    let mut g = m.lock().unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Some(r) = g.remove(&id) {
            println!("[result] {cmd} ok={} {}", r.0, r.1.chars().take(200).collect::<String>());
            return r;
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            panic!("timeout waiting for {cmd}");
        }
        g = cv.wait_timeout(g, left).unwrap().0;
    }
}

fn main() {
    assert_eq!(dd_abi_version(), 1);
    let port = port();
    let dir = std::env::temp_dir().join(format!("dd-smoke-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    // A token is required for the file to parse; if it did not, the core would
    // fall back to the default port (48484) - the real app's.
    let token = uuid::Uuid::new_v4().to_string();
    std::fs::write(
        dir.join("droiddock.json"),
        format!("{{\"token\":\"{token}\",\"port\":{port}}}"),
    )
    .unwrap();
    println!("data dir: {}", dir.display());

    let cfg = CString::new(format!(
        "{{\"dataDir\":{},\"identifier\":\"com.droiddock.smoke\",\"version\":\"2.3.0\",\"dev\":true}}",
        serde_json::to_string(dir.to_str().unwrap()).unwrap()
    ))
    .unwrap();
    let cbs = DdCallbacks {
        ctx: std::ptr::null_mut(),
        on_event: Some(on_event),
        on_frame: Some(on_frame),
        on_result: Some(on_result),
    };
    let rc = unsafe { dd_core_start(cfg.as_ptr(), cbs) };
    println!("dd_core_start -> {rc}");
    assert_eq!(rc, DD_OK);
    assert_eq!(unsafe { dd_core_start(cfg.as_ptr(), cbs) }, DD_ERR_ALREADY);

    let (ok, s) = call(1, "core_ping", "{}");
    assert!(ok == 1 && s == "\"pong\"");

    let (ok, s) = call(2, "get_config", "{}");
    assert_eq!(ok, 1);
    let v: Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["port"], port);
    assert_eq!(v["token"], token.as_str());
    println!("get_config round-trip OK (port {port}, token matches)");

    let (ok, s) = call(3, "set_setting", r#"{"key":"menubarText","value":"media"}"#);
    assert_eq!(ok, 1);
    let v: Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["menubarText"], "media");
    assert!(
        events().lock().unwrap().iter().any(|(n, _)| n == "menubar-title"),
        "set_setting should emit menubar-title"
    );
    println!("set_setting emitted menubar-title OK");

    let (ok, s) = call(4, "no_such_command", "{}");
    assert!(ok == 0 && s.contains("unknown command"));
    let (ok, _) = call(5, "get_pairing_info", "");
    assert_eq!(ok, 1);
    let (ok, _) = call(6, "app_version", "{}");
    assert_eq!(ok, 1);

    println!("SMOKE OK - listening on {port} for 12 s (check lsof / dns-sd now)");
    std::thread::sleep(Duration::from_secs(12));
    println!("done (dd_core_shutdown intentionally not called)");
    std::process::exit(0);
}
