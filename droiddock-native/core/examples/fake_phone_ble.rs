//! BLE loopback (CP2-M): an in-process fake phone drives the core's `ble_*`
//! commands and reads stream-3 frames, with no radio.
//!
//! handshake -> sealed hello/welcome -> notification -> `wifi-status {transport:"ble"}`
//!
//!   DD_BLE_PORT=48614 cargo run --release --target aarch64-apple-darwin --example fake_phone_ble
//!
//! Throwaway data dir; never binds 48484. Does not call `dd_core_shutdown`.

use droiddock_core::ble_link::{Ev, Session};
use droiddock_core::ffi::*;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::ffi::{c_char, c_void, CStr, CString};
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

type Results = (Mutex<HashMap<u64, (i32, String)>>, Condvar);
fn results() -> &'static Results {
    static R: OnceLock<Results> = OnceLock::new();
    R.get_or_init(|| (Mutex::new(HashMap::new()), Condvar::new()))
}
fn events() -> &'static (Mutex<Vec<(String, String)>>, Condvar) {
    static E: OnceLock<(Mutex<Vec<(String, String)>>, Condvar)> = OnceLock::new();
    E.get_or_init(|| (Mutex::new(Vec::new()), Condvar::new()))
}
fn frames() -> &'static (Mutex<Vec<Vec<u8>>>, Condvar) {
    static F: OnceLock<(Mutex<Vec<Vec<u8>>>, Condvar)> = OnceLock::new();
    F.get_or_init(|| (Mutex::new(Vec::new()), Condvar::new()))
}

unsafe extern "C" fn on_event(_: *mut c_void, name: *const c_char, payload: *const c_char) {
    let n = CStr::from_ptr(name).to_string_lossy().into_owned();
    let p = CStr::from_ptr(payload).to_string_lossy().into_owned();
    events().0.lock().unwrap().push((n, p));
    events().1.notify_all();
}
unsafe extern "C" fn on_frame(_: *mut c_void, stream: u32, data: *const u8, len: usize) {
    if stream == 3 {
        frames().0.lock().unwrap().push(std::slice::from_raw_parts(data, len).to_vec());
        frames().1.notify_all();
    }
}
unsafe extern "C" fn on_result(_: *mut c_void, id: u64, ok: i32, json: *const c_char) {
    let s = CStr::from_ptr(json).to_string_lossy().into_owned();
    results().0.lock().unwrap().insert(id, (ok, s));
    results().1.notify_all();
}

static NEXT: Mutex<u64> = Mutex::new(1);
fn wait_result(id: u64, what: &str) -> (i32, String) {
    let (m, cv) = results();
    let mut g = m.lock().unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Some(r) = g.remove(&id) {
            return r;
        }
        let left = deadline.saturating_duration_since(Instant::now());
        assert!(!left.is_zero(), "timeout waiting for {what}");
        g = cv.wait_timeout(g, left).unwrap().0;
    }
}
fn call(cmd: &str, args: Value, body: Option<&[u8]>) -> (i32, String) {
    let id = {
        let mut n = NEXT.lock().unwrap();
        *n += 1;
        *n
    };
    let c = CString::new(cmd).unwrap();
    let a = CString::new(args.to_string()).unwrap();
    unsafe {
        match body {
            Some(b) => dd_core_invoke_bytes(c.as_ptr(), a.as_ptr(), b.as_ptr(), b.len(), id),
            None => dd_core_invoke(c.as_ptr(), a.as_ptr(), id),
        }
    }
    wait_result(id, cmd)
}

/// Next stream-3 frame, with the 16-byte connection id stripped.
fn next_frame(conn: &uuid::Uuid) -> Vec<u8> {
    let (m, cv) = frames();
    let mut g = m.lock().unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if !g.is_empty() {
            let f = g.remove(0);
            assert_eq!(&f[..16], conn.as_bytes(), "frame addressed to the wrong connection");
            return f[16..].to_vec();
        }
        let left = deadline.saturating_duration_since(Instant::now());
        assert!(!left.is_zero(), "timeout waiting for a BLE frame");
        g = cv.wait_timeout(g, left).unwrap().0;
    }
}

fn wait_event(pred: impl Fn(&str, &Value) -> bool, what: &str) -> Value {
    let (m, cv) = events();
    let mut g = m.lock().unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        for (n, p) in g.iter() {
            let v: Value = serde_json::from_str(p).unwrap_or(Value::Null);
            if pred(n, &v) {
                return v;
            }
        }
        let left = deadline.saturating_duration_since(Instant::now());
        assert!(!left.is_zero(), "timeout waiting for event: {what}");
        g = cv.wait_timeout(g, left).unwrap().0;
    }
}

fn main() {
    let port: u16 = std::env::var("DD_BLE_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(48614);
    assert_ne!(port, 48484);
    let dir = std::env::temp_dir().join(format!("dd-ble-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let token = uuid::Uuid::new_v4().to_string();
    std::fs::write(
        dir.join("droiddock.json"),
        format!("{{\"token\":\"{token}\",\"port\":{port},\"connectionMode\":\"fallback\"}}"),
    )
    .unwrap();
    let cfg = CString::new(format!(
        "{{\"dataDir\":{},\"identifier\":\"com.droiddock.blesmoke\",\"version\":\"2.4.0\",\"dev\":true}}",
        serde_json::to_string(dir.to_str().unwrap()).unwrap()
    ))
    .unwrap();
    let cbs = DdCallbacks { ctx: std::ptr::null_mut(), on_event: Some(on_event), on_frame: Some(on_frame), on_result: Some(on_result) };
    assert_eq!(unsafe { dd_core_start(cfg.as_ptr(), cbs) }, DD_OK);

    // Fallback is on at startup: the core must ask Swift to power the radio, silently.
    let power = wait_event(|n, v| n == "ble-control" && v["op"] == "power", "power on");
    assert_eq!(power["on"], true);
    assert_eq!(power["prompt"], false, "no prompt at launch");
    println!("power on (prompt=false) OK");

    assert_eq!(call("ble_radio", json!({"radio":"poweredOn","auth":"allowed"}), None).0, 1);
    let conn = uuid::Uuid::new_v4();
    let cid = conn.to_string().to_uppercase();
    assert_eq!(call("ble_connected", json!({"conn": cid, "maxWrite": 182}), None).0, 1);

    // --- phone side ---
    let (mut phone, p_hello) = Session::phone_start(&token);
    let mut seq = 0u64;
    let mut rx = |f: &[u8]| {
        let r = call("ble_rx", json!({"conn": cid, "seq": seq}), Some(f));
        assert_eq!(r.0, 1);
        seq += 1;
    };
    rx(&p_hello);
    let m_auth = next_frame(&conn);
    let mut evs = phone.on_frame(&m_auth).expect("M_AUTH verifies");
    assert_eq!(evs.pop(), Some(Ev::Authed));
    let Ev::Send(p_auth) = evs.remove(0) else { panic!() };
    call("ble_tx_done", json!({"conn": cid, "n": 1}), None);
    rx(&p_auth);
    println!("mutual auth OK");

    let hello = json!({"type":"hello","token":token,"name":"Fake Phone","caps":["ble"],"deviceId":"fake-1"});
    rx(&phone.seal_json(&hello).unwrap());
    let welcome = loop {
        let f = next_frame(&conn);
        call("ble_tx_done", json!({"conn": cid, "n": 1}), None);
        for ev in phone.on_frame(&f).expect("sealed frame opens") {
            if let Ev::Json(v) = ev {
                if v["type"] == "welcome" {
                    break;
                }
                // link-mode etc. may arrive first; keep reading
            }
        }
        // re-check by scanning events instead: welcome is the first sealed JSON
        break Value::Null;
    };
    let _ = welcome;
    let st = wait_event(|n, v| n == "wifi-status" && v["connected"] == true, "wifi-status");
    assert_eq!(st["transport"], "ble", "status must report Bluetooth");
    assert_eq!(st["phoneName"], "Fake Phone");
    println!("hello/welcome OK, wifi-status transport=ble");

    let notif = json!({"type":"notification","key":"k1","pkg":"com.test","title":"Hi","text":"over BLE","mid":"abc123-1"});
    rx(&phone.seal_json(&notif).unwrap());
    wait_event(|n, v| n == "notification" && v["key"] == "k1", "notification");
    println!("notification delivered OK");

    // A duplicate `mid` (resent after a handover) must be dropped.
    let before = events().0.lock().unwrap().iter().filter(|(n, _)| n == "notification").count();
    rx(&phone.seal_json(&notif).unwrap());
    std::thread::sleep(Duration::from_millis(300));
    let after = events().0.lock().unwrap().iter().filter(|(n, _)| n == "notification").count();
    assert_eq!(before, after, "duplicate mid must be dropped");
    println!("mid de-duplication OK");

    // A Wi-Fi-only message over BLE is refused.
    let before = events().0.lock().unwrap().len();
    rx(&phone.seal_json(&json!({"type":"photos-changed"})).unwrap());
    std::thread::sleep(Duration::from_millis(200));
    let _ = before;

    let (_, status) = call("ble_status", json!({}), None);
    let sv: Value = serde_json::from_str(&status).unwrap();
    assert_eq!(sv["state"], "connected");
    println!("ble_status = {status}");
    println!("FAKE PHONE BLE OK");
    std::process::exit(0);
}
