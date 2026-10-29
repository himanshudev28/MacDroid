//! Bluetooth LE fallback: connection hub, command handlers and the switching
//! policy (docs/BLE-NEARBY-DESIGN.md §3, §6, §9a.3-4).
//!
//! * `Conn` is one GATT connection (one CBPeripheral) owned by Swift's
//!   `BleService`. It wraps a sans-IO [`ble_link::Session`], the priority
//!   outbox and the 8-frame credit window.
//! * `Policy` is a pure state machine (fake-clock testable) deciding when to
//!   power the radio, scan, hand back to Wi-Fi and drop BLE.
//! * `run` is the one task that feeds the policy and applies its actions. It
//!   is event-driven: with fallback off (the default) it never wakes.
//!
//! Priority of links: USB/ADB > Wi-Fi > Bluetooth. The USB link reaches the
//! core as a Wi-Fi-class WebSocket (phone connects through `adb reverse`), so
//! "Wi-Fi" below covers both.

use crate::ble_link::{self, Ev, LinkErr, Role, Session};
use crate::host::{AppHandle, Emitter, Manager};
use crate::protocol::Message;
use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tokio::sync::Notify;

const CREDITS: usize = 8;
const OUTBOX_CAP: usize = 32;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const KEEPALIVE: Duration = Duration::from_secs(15);
const IGNORE_MS: i64 = 60_000;

fn now_ms() -> i64 {
    crate::config::now_ms()
}

// ── message ids (§7) ────────────────────────────────────────────────────

static MID_PREFIX: OnceLock<String> = OnceLock::new();
static MID_SEQ: AtomicU64 = AtomicU64::new(1);

/// `"<6 base36 random chars per process>-<seq>"`.
pub fn next_mid() -> String {
    let prefix = MID_PREFIX.get_or_init(|| {
        let mut b = [0u8; 6];
        let _ = getrandom::fill(&mut b);
        b.iter().map(|x| char::from(b"0123456789abcdefghijklmnopqrstuvwxyz"[(*x % 36) as usize])).collect()
    });
    format!("{prefix}-{}", MID_SEQ.fetch_add(1, Ordering::Relaxed))
}

static APP: OnceLock<AppHandle> = OnceLock::new();
static POKE: Notify = Notify::const_new();

/// Wake the policy task. Cheap; callable from anywhere.
pub fn poke() {
    POKE.notify_one();
}

/// §2 rule 6: a clipboard too big for BLE was refused with no Wi-Fi to carry it.
pub fn note_clipboard_too_large() {
    if let Some(app) = APP.get() {
        let _ = app.emit("wifi-event", json!({ "kind": "info", "text": "Clipboard too large for Bluetooth" }));
    }
}

// ── one GATT connection ─────────────────────────────────────────────────

enum Out {
    Raw(Vec<u8>),
    Ctl(u8, Vec<u8>),
    Json(Value, u8, String),
}

impl Out {
    fn prio(&self) -> u8 {
        match self {
            Out::Raw(_) | Out::Ctl(..) => 0,
            Out::Json(_, p, _) => *p,
        }
    }
}

fn json_prio(ty: &str) -> u8 {
    match ty {
        "media" | "device-info" | "mac-info" | "mac-media" => 2,
        "sms-threads" | "sms-messages" | "sms-send" => 3,
        _ => 1,
    }
}

#[derive(Default)]
struct RxQueue {
    next: u64,
    map: BTreeMap<u64, Vec<u8>>,
}

struct ConnState {
    sess: Session,
    authed: bool,
    hello_done: bool,
    link_id: Option<u64>,
    out: Vec<Out>,
    inflight: usize,
    closed: bool,
    unanswered_pings: u32,
}

pub struct Conn {
    id: String,
    uuid: [u8; 16],
    app: AppHandle,
    st: Mutex<ConnState>,
    rxq: Mutex<RxQueue>,
    gate: tokio::sync::Mutex<()>,
}

impl Conn {
    fn new(app: &AppHandle, id: &str, uuid: [u8; 16], token: &str) -> Arc<Self> {
        Arc::new(Conn {
            id: id.to_string(),
            uuid,
            app: app.clone(),
            st: Mutex::new(ConnState {
                sess: Session::mac(token),
                authed: false,
                hello_done: false,
                link_id: None,
                out: Vec::new(),
                inflight: 0,
                closed: false,
                unanswered_pings: 0,
            }),
            rxq: Mutex::new(RxQueue::default()),
            gate: tokio::sync::Mutex::new(()),
        })
    }

    /// Queue an app JSON message. False when closed, over-size for BLE, or the
    /// bounded outbox has nothing droppable (§4).
    pub fn send_json(&self, v: &Value) -> bool {
        let ty = v.get("type").and_then(Value::as_str).unwrap_or("").to_string();
        if ty == "clipboard" && v.get("text").and_then(Value::as_str).map_or(0, str::len) > crate::transport_class::MAX_BLE_CLIPBOARD {
            note_clipboard_too_large();
            return false;
        }
        let mut g = self.st.lock().unwrap();
        if g.closed || !g.authed {
            return false;
        }
        let prio = json_prio(&ty);
        if prio == 2 {
            // latest-wins per type while queued
            g.out.retain(|o| !matches!(o, Out::Json(_, 2, t) if *t == ty));
        }
        if g.out.len() >= OUTBOX_CAP {
            // drop the oldest P2; if none, this send fails
            if let Some(i) = g.out.iter().position(|o| o.prio() == 2) {
                g.out.remove(i);
            } else {
                return false;
            }
        }
        g.out.push(Out::Json(v.clone(), prio, ty));
        self.pump(&mut g);
        true
    }

    fn queue_ctl(&self, op: u8, payload: Vec<u8>) {
        let mut g = self.st.lock().unwrap();
        if g.closed || !g.authed {
            return;
        }
        g.out.push(Out::Ctl(op, payload));
        self.pump(&mut g);
    }

    fn queue_raw(&self, frame: Vec<u8>) {
        let mut g = self.st.lock().unwrap();
        g.out.push(Out::Raw(frame));
        self.pump(&mut g);
    }

    /// Emit as many queued frames as the credit window allows, best priority
    /// first, sealing only at emission so counters follow wire order.
    fn pump(&self, g: &mut ConnState) {
        while g.inflight < CREDITS && !g.out.is_empty() {
            let mut best = 0;
            for (i, o) in g.out.iter().enumerate() {
                if o.prio() < g.out[best].prio() {
                    best = i;
                }
            }
            let frame = match g.out.remove(best) {
                Out::Raw(f) => f,
                Out::Ctl(op, p) => g.sess.seal_ctl(op, &p),
                Out::Json(v, _, ty) => match g.sess.seal_json(&v) {
                    Ok(f) => f,
                    Err(_) => {
                        eprintln!("[ble] dropping {ty:?}: too large for Bluetooth");
                        continue;
                    }
                },
            };
            let mut data = Vec::with_capacity(16 + frame.len());
            data.extend_from_slice(&self.uuid);
            data.extend_from_slice(&frame);
            g.inflight += 1;
            self.app.emit_frame(ble_link::STREAM_BLE_TX, &data);
        }
    }

    fn tx_done(&self, n: usize) {
        let mut g = self.st.lock().unwrap();
        g.inflight = g.inflight.saturating_sub(n);
        self.pump(&mut g);
    }

    /// Ask Swift to cancel this connection.
    pub fn kill(&self) {
        self.st.lock().unwrap().closed = true;
        let _ = self.app.emit("ble-control", json!({ "op": "drop", "conn": self.id }));
    }

    fn bye_and_kill(&self, reason: u8) {
        self.queue_ctl(ble_link::OP_BYE, vec![reason]);
        self.kill();
    }

    fn is_live(&self) -> bool {
        let g = self.st.lock().unwrap();
        g.hello_done && !g.closed
    }

    async fn on_rx(self: &Arc<Self>, seq: u64, data: Vec<u8>) {
        self.rxq.lock().unwrap().map.insert(seq, data);
        loop {
            let Ok(_guard) = self.gate.try_lock() else { return };
            loop {
                let next = {
                    let mut q = self.rxq.lock().unwrap();
                    let n = q.next;
                    q.map.remove(&n).map(|d| {
                        q.next += 1;
                        d
                    })
                };
                match next {
                    Some(d) => self.process(d).await,
                    None => break,
                }
            }
            drop(_guard);
            let more = {
                let q = self.rxq.lock().unwrap();
                q.map.contains_key(&q.next)
            };
            if !more {
                return;
            }
        }
    }

    async fn process(self: &Arc<Self>, frame: Vec<u8>) {
        let evs = {
            let mut g = self.st.lock().unwrap();
            if g.closed {
                return;
            }
            g.sess.on_frame(&frame)
        };
        let evs = match evs {
            Ok(e) => e,
            Err(e) => {
                self.fail(e);
                return;
            }
        };
        let state = self.app.state::<SharedState>().inner().clone();
        for ev in evs {
            match ev {
                Ev::Send(f) => self.queue_raw_authed_ok(f),
                Ev::Authed => {
                    self.st.lock().unwrap().authed = true;
                    self.spawn_keepalive();
                    poke();
                }
                Ev::Pong(p) => {
                    self.st.lock().unwrap().unanswered_pings = 0;
                    if let Ok(b) = <[u8; 4]>::try_from(p.as_slice()) {
                        let rtt = (now_ms() as u32).wrapping_sub(u32::from_be_bytes(b));
                        if rtt < 60_000 {
                            self.app.state::<crate::link_quality::LinkQuality>().on_ble_rtt(rtt as f64);
                        }
                    }
                }
                Ev::Bye(_) => {
                    self.kill();
                    return;
                }
                Ev::Json(v) => {
                    let done = self.st.lock().unwrap().hello_done;
                    if done {
                        ws_server::route_from_ble(&self.app, &state, v).await;
                    } else if !self.handle_hello(&state, v).await {
                        self.kill();
                        return;
                    }
                }
            }
        }
    }

    /// Frames produced by the session itself (handshake replies, automatic PONG).
    fn queue_raw_authed_ok(&self, f: Vec<u8>) {
        self.queue_raw(f);
    }

    /// The sealed JSON `hello`: the normal token check, then register the link.
    async fn handle_hello(self: &Arc<Self>, state: &SharedState, v: Value) -> bool {
        let Ok(Message::Hello { token, name, caps, device_id }) = serde_json::from_value::<Message>(v) else {
            eprintln!("[ble] first sealed message was not a hello");
            return false;
        };
        if token != self.app.state::<crate::AppState>().config.lock().unwrap().token {
            eprintln!("[ble] hello token mismatch from {name:?}");
            return false;
        }
        eprintln!("[ble] HANDSHAKE OK: {name:?} caps={caps:?}");
        let (id, reg) = ws_server::ble_attach(&self.app, state, name, device_id, caps, self.clone()).await;
        {
            let mut g = self.st.lock().unwrap();
            g.hello_done = true;
            g.link_id = Some(id);
        }
        // The BLE welcome never lists enc/enc2 (BLE is always sealed at L2).
        let welcome = json!({ "type": "welcome", "name": ws_server::mac_name(&self.app), "caps": reg.welcome_caps });
        self.send_json(&welcome);
        ws_server::ble_attached(&self.app, state, &reg).await;
        true
    }

    fn fail(&self, e: LinkErr) {
        eprintln!("[ble] dropping {}: {e:?}", self.id);
        if matches!(e, LinkErr::BadProof) {
            self.queue_raw(Session::fail_frame(1));
            ignore(&self.app, &self.id);
        }
        self.kill();
    }

    fn spawn_keepalive(self: &Arc<Self>) {
        let me = self.clone();
        crate::host::async_runtime::spawn(async move {
            loop {
                tokio::time::sleep(KEEPALIVE).await;
                let dead = {
                    let mut g = me.st.lock().unwrap();
                    if g.closed {
                        return;
                    }
                    g.unanswered_pings += 1;
                    g.unanswered_pings > 2
                };
                if dead {
                    eprintln!("[ble] {} missed 2 PONGs, dropping", me.id);
                    me.kill();
                    return;
                }
                me.queue_ctl(ble_link::OP_PING, (now_ms() as u32).to_be_bytes().to_vec());
            }
        });
    }
}

// ── hub ─────────────────────────────────────────────────────────────────

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Prefer {
    Auto,
    Wifi,
    Ble,
}

impl Prefer {
    fn parse(s: &str) -> Option<Self> {
        match s {
            "auto" => Some(Prefer::Auto),
            "wifi" => Some(Prefer::Wifi),
            "ble" => Some(Prefer::Ble),
            _ => None,
        }
    }
    fn as_str(self) -> &'static str {
        match self {
            Prefer::Auto => "auto",
            Prefer::Wifi => "wifi",
            Prefer::Ble => "ble",
        }
    }
}

struct HubInner {
    conns: HashMap<String, Arc<Conn>>,
    radio: String,
    auth: String,
    rssi: Option<i64>,
    scanning: bool,
    powered: Option<bool>,
    /// The next `power on` may show the system Bluetooth prompt (user-initiated).
    prompt: bool,
    ignore: HashMap<String, i64>,
    prefer: Prefer,
    last_status: Option<Value>,
}

pub struct BleHub(Mutex<HubInner>);

impl Default for BleHub {
    fn default() -> Self {
        BleHub(Mutex::new(HubInner {
            conns: HashMap::new(),
            radio: "unknown".into(),
            auth: "notDetermined".into(),
            rssi: None,
            scanning: false,
            powered: None,
            prompt: false,
            ignore: HashMap::new(),
            prefer: Prefer::Auto,
            last_status: None,
        }))
    }
}

fn hub(app: &AppHandle) -> crate::host::State<'_, BleHub> {
    app.state::<BleHub>()
}

fn ignore(app: &AppHandle, conn: &str) {
    hub(app).0.lock().unwrap().ignore.insert(conn.to_string(), now_ms() + IGNORE_MS);
}

fn live_config(app: &AppHandle) -> crate::config::Config {
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

// ── commands (Swift → Rust) ─────────────────────────────────────────────

fn s(args: &Value, k: &str) -> Result<String, String> {
    args.get(k).and_then(Value::as_str).map(str::to_string).ok_or_else(|| format!("bad arg `{k}`"))
}

pub fn cmd_radio(app: &AppHandle, args: &Value) -> Result<Value, String> {
    {
        let h = hub(app);
        let mut g = h.0.lock().unwrap();
        g.radio = s(args, "radio")?;
        g.auth = s(args, "auth")?;
    }
    poke();
    Ok(Value::Null)
}

pub fn cmd_connected(app: &AppHandle, args: &Value) -> Result<Value, String> {
    let id = s(args, "conn")?;
    let uuid = uuid::Uuid::parse_str(&id).map_err(|e| e.to_string())?;
    let token = live_config(app).token;
    {
        let h = hub(app);
        let mut g = h.0.lock().unwrap();
        if g.ignore.get(&id).is_some_and(|until| *until > now_ms()) || live_config(app).connection_mode != "fallback" {
            drop(g);
            let _ = app.emit("ble-control", json!({ "op": "drop", "conn": id }));
            return Ok(Value::Null);
        }
        let conn = Conn::new(app, &id, *uuid.as_bytes(), &token);
        g.conns.insert(id.clone(), conn.clone());
        // Steps 1-6 must finish within 5 s of connect, or both sides drop.
        crate::host::async_runtime::spawn(async move {
            tokio::time::sleep(HANDSHAKE_TIMEOUT).await;
            if !conn.is_live() {
                let fresh_failure = !conn.st.lock().unwrap().closed;
                if fresh_failure {
                    eprintln!("[ble] handshake timeout for {}", conn.id);
                    ignore(&conn.app, &conn.id);
                    conn.kill();
                }
            }
        });
    }
    poke();
    Ok(Value::Null)
}

pub async fn cmd_rx(app: &AppHandle, args: &Value, body: Option<Vec<u8>>) -> Result<Value, String> {
    let id = s(args, "conn")?;
    let conn = hub(app).0.lock().unwrap().conns.get(&id).cloned();
    let (Some(conn), Some(body)) = (conn, body) else { return Ok(Value::Null) };
    let seq = args.get("seq").and_then(Value::as_u64).unwrap_or_else(|| conn.rxq.lock().unwrap().next);
    conn.on_rx(seq, body).await;
    Ok(Value::Null)
}

pub fn cmd_tx_done(app: &AppHandle, args: &Value) -> Result<Value, String> {
    let id = s(args, "conn")?;
    let n = args.get("n").and_then(Value::as_u64).unwrap_or(1) as usize;
    if let Some(c) = hub(app).0.lock().unwrap().conns.get(&id).cloned() {
        c.tx_done(n);
    }
    Ok(Value::Null)
}

pub async fn cmd_closed(app: &AppHandle, args: &Value) -> Result<Value, String> {
    let id = s(args, "conn")?;
    let conn = hub(app).0.lock().unwrap().conns.remove(&id);
    if let Some(conn) = conn {
        let link_id = {
            let mut g = conn.st.lock().unwrap();
            g.closed = true;
            g.link_id
        };
        if let Some(l) = link_id {
            let state = app.state::<SharedState>().inner().clone();
            ws_server::ble_detach(app, &state, l).await;
        }
    }
    hub(app).0.lock().unwrap().rssi = None;
    poke();
    Ok(Value::Null)
}

pub fn cmd_rssi(app: &AppHandle, args: &Value) -> Result<Value, String> {
    hub(app).0.lock().unwrap().rssi = args.get("rssi").and_then(Value::as_i64);
    poke();
    Ok(Value::Null)
}

pub fn cmd_status(app: &AppHandle) -> Result<Value, String> {
    Ok(status_json(app))
}

/// `link_prefer {transport:"wifi"|"ble"|"auto"}` — the user forcing a switch (or
/// handing control back). Mirrored to the phone as `link-prefer`.
pub async fn cmd_link_prefer(app: &AppHandle, args: &Value) -> Result<Value, String> {
    let t = s(args, "transport")?;
    let p = Prefer::parse(&t).ok_or("transport must be wifi, ble or auto")?;
    hub(app).0.lock().unwrap().prefer = p;
    poke();
    let state = app.state::<SharedState>().inner().clone();
    if ws_server::phone_has_cap(&state, "ble").await {
        ws_server::push(&state, json!({ "type": "link-prefer", "transport": p.as_str() })).await;
    }
    Ok(Value::Null)
}

// ── settings sync (§8) ──────────────────────────────────────────────────

/// Called by `set_setting` after `connectionMode` / `bleAutoReturn` changed here.
pub fn mode_changed_locally(app: &AppHandle) {
    {
        let h = hub(app);
        let mut g = h.0.lock().unwrap();
        g.prompt = true;
        if live_config(app).connection_mode != "fallback" {
            g.prefer = Prefer::Auto;
        }
    }
    poke();
    let app2 = app.clone();
    crate::host::async_runtime::spawn(async move {
        let state = app2.state::<SharedState>().inner().clone();
        push_link_mode(&app2, &state).await;
    });
}

pub async fn push_link_mode(app: &AppHandle, state: &SharedState) {
    if !ws_server::phone_has_cap(state, "ble").await {
        return;
    }
    let cfg = live_config(app);
    ws_server::push(
        state,
        json!({ "type": "link-mode", "mode": cfg.connection_mode, "at": cfg.connection_mode_at, "autoReturn": cfg.ble_auto_return }),
    )
    .await;
}

/// Apply the phone's `link-mode` iff newer. Never prompts for Bluetooth itself.
pub fn on_link_mode(app: &AppHandle, raw: &Value) {
    let at = raw.get("at").and_then(Value::as_i64).unwrap_or(0);
    let mode = match raw.get("mode").and_then(Value::as_str) {
        Some("fallback") => "fallback",
        _ => "network",
    };
    let updated = {
        let st = app.state::<crate::AppState>();
        let mut cfg = st.config.lock().unwrap();
        if at <= cfg.connection_mode_at {
            return;
        }
        cfg.connection_mode = mode.to_string();
        cfg.connection_mode_at = at;
        if let Some(b) = raw.get("autoReturn").and_then(Value::as_bool) {
            cfg.ble_auto_return = b;
        }
        crate::config::save(app, &cfg);
        cfg.clone()
    };
    let _ = app.emit("config", &updated);
    {
        let h = hub(app);
        let mut g = h.0.lock().unwrap();
        g.prompt = false;
        if mode != "fallback" {
            g.prefer = Prefer::Auto;
        }
    }
    poke();
}

pub fn on_link_prefer(app: &AppHandle, raw: &Value) {
    if let Some(p) = raw.get("transport").and_then(Value::as_str).and_then(Prefer::parse) {
        hub(app).0.lock().unwrap().prefer = p;
        poke();
    }
}

/// After any link registers: re-sync the mode, wake the policy.
pub async fn after_register(app: &AppHandle, state: &SharedState) {
    push_link_mode(app, state).await;
    poke();
}

// ── status ──────────────────────────────────────────────────────────────

fn status_json(app: &AppHandle) -> Value {
    let cfg = live_config(app);
    let h = hub(app);
    let g = h.0.lock().unwrap();
    let fallback = cfg.connection_mode == "fallback";
    let live = g.conns.values().any(|c| c.is_live());
    let authing = !g.conns.is_empty();
    let state = if !fallback || g.powered != Some(true) {
        "off"
    } else if live {
        "connected"
    } else if authing {
        "authenticating"
    } else if g.scanning {
        "scanning"
    } else {
        "standby"
    };
    let link = app.state::<LinkFlags>();
    json!({
        "mode": cfg.connection_mode,
        "radio": g.radio,
        "auth": g.auth,
        "state": state,
        "rssi": if live { g.rssi.map(Value::from).unwrap_or(Value::Null) } else { Value::Null },
        "autoReturn": cfg.ble_auto_return,
        "prefer": g.prefer.as_str(),
        "wifiAvailable": link.wifi_available.load(Ordering::Relaxed),
        "bleLive": live,
    })
}

/// Mirrors of policy inputs the status needs (written by the policy task).
#[derive(Default)]
pub struct LinkFlags {
    wifi_available: std::sync::atomic::AtomicBool,
}

fn emit_status_if_changed(app: &AppHandle) {
    let v = status_json(app);
    let h = hub(app);
    let mut g = h.0.lock().unwrap();
    if g.last_status.as_ref() != Some(&v) {
        g.last_status = Some(v.clone());
        drop(g);
        let _ = app.emit("ble-status", v);
    }
}

// ── policy (pure) ───────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub struct In {
    pub now: i64,
    pub fallback: bool,
    pub radio_on: bool,
    pub ws_up: bool,
    pub ws_since: i64,
    pub ws_stalled: bool,
    pub ble_present: bool,
    pub ble_live: bool,
    pub paused: bool,
    pub prefer: Prefer,
    pub auto_return: bool,
}

#[derive(Debug, PartialEq)]
pub enum Act {
    Scan(bool),
    KillWs,
    SetWsPrimary(bool),
    Bye,
    DropBle,
}

pub struct Policy {
    no_wifi_since: i64,
    was_ws_up: bool,
    ws_primary: bool,
    t_back: i64,
    scan_on: bool,
    scan_started: Option<i64>,
    last_scan_cmd: i64,
    scan_epoch: u64,
    bye_at: Option<i64>,
    changes: VecDeque<i64>,
    last_primary: Option<bool>,
    last_change: i64,
}

const T_BACK: i64 = 10_000;
const GRACE: i64 = 5_000;

impl Policy {
    pub fn new(launch_ms: i64) -> Self {
        Policy {
            // At launch with no Wi-Fi, scan after 10 s rather than 3 s.
            no_wifi_since: launch_ms + 7_000,
            was_ws_up: false,
            ws_primary: false,
            t_back: T_BACK,
            scan_on: false,
            scan_started: None,
            last_scan_cmd: 0,
            scan_epoch: 0,
            bye_at: None,
            changes: VecDeque::new(),
            last_primary: None,
            last_change: launch_ms,
        }
    }

    pub fn scanning(&self) -> bool {
        self.scan_on
    }

    /// Returns actions and the next time (unix ms) the policy must run again.
    pub fn step(&mut self, i: &In) -> (Vec<Act>, Option<i64>) {
        let now = i.now;
        let mut acts = Vec::new();
        let mut next: Option<i64> = None;
        let mut at = |t: i64| next = Some(next.map_or(t, |n| n.min(t)));

        if !i.fallback {
            if self.scan_on {
                acts.push(Act::Scan(false));
                self.scan_on = false;
            }
            if self.ws_primary {
                acts.push(Act::SetWsPrimary(false));
                self.ws_primary = false;
            }
            self.scan_started = None;
            self.bye_at = None;
            self.was_ws_up = i.ws_up;
            return (acts, None);
        }

        if i.ws_up != self.was_ws_up {
            if !i.ws_up {
                self.no_wifi_since = now;
            }
            self.was_ws_up = i.ws_up;
        }

        // Stalled = lost (§6): close the socket so the fallback starts in ~16 s, not 40.
        if i.ws_up && i.ws_stalled {
            acts.push(Act::KillWs);
        }

        // Which link carries ANY-class traffic. A forced preference beats the
        // stability window; with auto-return off BLE is kept until the user acts.
        let healthy = i.ws_up && !i.ws_stalled;
        let want_primary = healthy
            && match i.prefer {
                Prefer::Wifi => true,
                Prefer::Ble => false,
                Prefer::Auto => {
                    if i.auto_return {
                        let ok = now - i.ws_since >= self.t_back;
                        if !ok {
                            at(i.ws_since + self.t_back);
                        }
                        ok
                    } else {
                        !i.ble_live
                    }
                }
            };
        if want_primary != self.ws_primary {
            self.ws_primary = want_primary;
            acts.push(Act::SetWsPrimary(want_primary));
        }

        // Flap guard: > 3 primary changes in 2 min doubles T_back (max 60 s).
        let primary_wifi = i.ws_up && (self.ws_primary || !i.ble_live);
        if self.last_primary != Some(primary_wifi) {
            if self.last_primary.is_some() {
                self.changes.push_back(now);
                self.last_change = now;
            }
            self.last_primary = Some(primary_wifi);
        }
        while self.changes.front().is_some_and(|t| now - *t > 120_000) {
            self.changes.pop_front();
        }
        if self.changes.len() > 3 {
            self.t_back = (self.t_back * 2).min(60_000);
            self.changes.clear();
        } else if self.t_back != T_BACK && now - self.last_change >= 300_000 {
            self.t_back = T_BACK;
        }

        // Hand back: BYE(1), 5 s grace, then drop.
        if i.ble_live && self.ws_primary {
            match self.bye_at {
                None => {
                    acts.push(Act::Bye);
                    self.bye_at = Some(now);
                    at(now + GRACE);
                }
                Some(t) if t != i64::MAX => {
                    if now - t >= GRACE {
                        acts.push(Act::DropBle);
                        self.bye_at = Some(i64::MAX);
                    } else {
                        at(t + GRACE);
                    }
                }
                _ => {}
            }
        } else if !i.ble_live {
            self.bye_at = None;
        }

        // Scanning (§3): only without a Wi-Fi link (or when BLE is forced).
        let can_scan = i.radio_on && !i.paused && !i.ble_present && (!i.ws_up || i.prefer == Prefer::Ble);
        let since_ok = i.prefer == Prefer::Ble || now - self.no_wifi_since >= 3_000;
        if can_scan && since_ok {
            let started = *self.scan_started.get_or_insert(now);
            let elapsed = now - started;
            let epoch = ble_link::epoch_of(now);
            let window_on = elapsed < 600_000 || (elapsed - 600_000) % 60_000 < 15_000;
            if window_on {
                if !self.scan_on || now - self.last_scan_cmd >= 60_000 || epoch != self.scan_epoch {
                    acts.push(Act::Scan(true));
                    self.scan_on = true;
                    self.last_scan_cmd = now;
                    self.scan_epoch = epoch;
                }
                at(self.last_scan_cmd + 60_000);
                at((epoch as i64 + 1) * 900_000);
                if elapsed < 600_000 {
                    at(started + 600_000);
                } else {
                    at(now + 15_000 - (elapsed - 600_000) % 60_000);
                }
            } else {
                if self.scan_on {
                    acts.push(Act::Scan(false));
                    self.scan_on = false;
                }
                at(now + 60_000 - (elapsed - 600_000) % 60_000);
            }
        } else {
            if self.scan_on {
                acts.push(Act::Scan(false));
                self.scan_on = false;
            }
            self.scan_started = None;
            if can_scan {
                at(self.no_wifi_since + 3_000);
            }
        }
        (acts, next)
    }
}

// ── the task ────────────────────────────────────────────────────────────

pub async fn run(app: AppHandle, state: SharedState) {
    let _ = APP.set(app.clone());
    let mut policy = Policy::new(now_ms());
    loop {
        let cfg = live_config(&app);
        let fallback = cfg.connection_mode == "fallback";
        let snap = ws_server::link_snapshot(&state).await;
        let (radio_on, ble_present, ble_live, prefer, powered, prompt) = {
            let h = hub(&app);
            let g = h.0.lock().unwrap();
            (
                g.radio == "poweredOn",
                !g.conns.is_empty(),
                g.conns.values().any(|c| c.is_live()),
                g.prefer,
                g.powered,
                g.prompt,
            )
        };
        let stalled = snap.ws_up && app.state::<crate::link_quality::LinkQuality>().is_stalled();
        app.state::<LinkFlags>().wifi_available.store(snap.ws_up && !stalled, Ordering::Relaxed);
        let paused = app.state::<crate::adb::AdbState>().paused.load(Ordering::Relaxed) || cfg.is_paused();

        // Power: the CBCentralManager (and with it the permission prompt) exists
        // only once fallback is on.
        if powered != Some(fallback) && (fallback || powered.is_some()) {
            {
                let h = hub(&app);
                let mut g = h.0.lock().unwrap();
                g.powered = Some(fallback);
                g.prompt = false;
            }
            let _ = app.emit("ble-control", json!({ "op": "power", "on": fallback, "prompt": fallback && prompt }));
            if !fallback {
                let conns: Vec<Arc<Conn>> = hub(&app).0.lock().unwrap().conns.values().cloned().collect();
                for c in conns {
                    c.bye_and_kill(ble_link::BYE_MODE_OFF);
                }
            }
        }

        let input = In {
            now: now_ms(),
            fallback,
            radio_on,
            ws_up: snap.ws_up,
            ws_since: snap.ws_since_ms,
            ws_stalled: stalled,
            ble_present,
            ble_live,
            paused,
            prefer,
            auto_return: cfg.ble_auto_return,
        };
        let (acts, next) = policy.step(&input);
        for a in acts {
            match a {
                Act::Scan(true) => {
                    let rids = ble_link::rids_hex(&cfg.token, now_ms());
                    hub(&app).0.lock().unwrap().scanning = true;
                    let _ = app.emit(
                        "ble-control",
                        json!({ "op": "scan", "on": true, "rids": rids, "service": ble_link::SERVICE_UUID }),
                    );
                }
                Act::Scan(false) => {
                    hub(&app).0.lock().unwrap().scanning = false;
                    let _ = app.emit("ble-control", json!({ "op": "scan", "on": false }));
                }
                Act::KillWs => ws_server::kill_ws(&state).await,
                Act::SetWsPrimary(on) => ws_server::set_ws_primary(&app, &state, on).await,
                Act::Bye | Act::DropBle => {
                    let conns: Vec<Arc<Conn>> = hub(&app).0.lock().unwrap().conns.values().cloned().collect();
                    for c in conns {
                        if a == Act::Bye {
                            c.queue_ctl(ble_link::OP_BYE, vec![ble_link::BYE_HANDBACK]);
                        } else {
                            c.kill();
                        }
                    }
                }
            }
        }
        emit_status_if_changed(&app);

        let wait = next.map(|t| Duration::from_millis((t - now_ms()).clamp(50, 3_600_000) as u64));
        match wait {
            Some(d) => {
                tokio::select! { _ = POKE.notified() => {}, _ = tokio::time::sleep(d) => {} }
            }
            None => POKE.notified().await,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base(now: i64) -> In {
        In {
            now,
            fallback: true,
            radio_on: true,
            ws_up: false,
            ws_since: 0,
            ws_stalled: false,
            ble_present: false,
            ble_live: false,
            paused: false,
            prefer: Prefer::Auto,
            auto_return: true,
        }
    }

    #[test]
    fn network_mode_is_silent() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.fallback = false;
        let (a, next) = p.step(&i);
        assert!(a.is_empty());
        assert_eq!(next, None);
    }

    #[test]
    fn no_scan_while_wifi_is_up() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.ws_up = true;
        i.ws_since = 50_000;
        let (a, _) = p.step(&i);
        assert!(!a.contains(&Act::Scan(true)));
    }

    #[test]
    fn scans_3s_after_wifi_loss() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.ws_up = true;
        i.ws_since = 0;
        p.step(&i);
        i.ws_up = false;
        i.now = 200_000;
        let (a, next) = p.step(&i);
        assert!(!a.contains(&Act::Scan(true)));
        assert_eq!(next, Some(203_000));
        i.now = 203_000;
        let (a, _) = p.step(&i);
        assert_eq!(a, vec![Act::Scan(true)]);
    }

    #[test]
    fn launch_without_wifi_scans_after_10s() {
        let mut p = Policy::new(1_000_000);
        let mut i = base(1_005_000);
        assert!(p.step(&i).0.is_empty());
        i.now = 1_010_000;
        assert_eq!(p.step(&i).0, vec![Act::Scan(true)]);
    }

    #[test]
    fn scan_restarts_every_60s_and_duty_cycles_after_10min() {
        let mut p = Policy::new(0);
        let mut i = base(1_000_000);
        assert_eq!(p.step(&i).0, vec![Act::Scan(true)]);
        i.now = 1_010_000;
        assert!(p.step(&i).0.is_empty());
        i.now = 1_060_000;
        assert_eq!(p.step(&i).0, vec![Act::Scan(true)]);
        // 10 minutes of fruitless scanning: 15 s on / 45 s off
        let started = 1_000_000;
        i.now = started + 600_000 + 20_000; // off window
        let a = p.step(&i).0;
        assert!(a.contains(&Act::Scan(false)));
        i.now = started + 600_000 + 60_000; // next on window
        assert!(p.step(&i).0.contains(&Act::Scan(true)));
    }

    #[test]
    fn stops_scanning_when_connected_or_paused_or_radio_off() {
        let mut p = Policy::new(0);
        let mut i = base(1_000_000);
        p.step(&i);
        i.now = 1_010_000;
        p.step(&i);
        assert!(p.scanning());
        i.ble_present = true;
        assert_eq!(p.step(&i).0, vec![Act::Scan(false)]);
        i.ble_present = false;
        i.now += 1000;
        assert_eq!(p.step(&i).0, vec![Act::Scan(true)]);
        i.paused = true;
        assert_eq!(p.step(&i).0, vec![Act::Scan(false)]);
    }

    #[test]
    fn stalled_wifi_is_killed() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.ws_up = true;
        i.ws_since = 0;
        i.ws_stalled = true;
        assert!(p.step(&i).0.contains(&Act::KillWs));
    }

    #[test]
    fn handback_after_10s_stability_then_bye_then_drop() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.ble_present = true;
        i.ble_live = true;
        p.step(&i); // BLE-only
        i.ws_up = true;
        i.ws_since = 200_000;
        i.now = 200_000;
        let (a, next) = p.step(&i);
        assert!(!a.contains(&Act::SetWsPrimary(true)));
        assert_eq!(next, Some(210_000));
        i.now = 210_000;
        let (a, _) = p.step(&i);
        assert_eq!(a, vec![Act::SetWsPrimary(true), Act::Bye]);
        i.now = 213_000;
        assert!(p.step(&i).0.is_empty());
        i.now = 215_000;
        assert_eq!(p.step(&i).0, vec![Act::DropBle]);
        i.now = 216_000;
        assert!(p.step(&i).0.is_empty(), "no second BYE after the drop");
    }

    #[test]
    fn auto_return_off_stays_on_ble_until_forced() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.ble_present = true;
        i.ble_live = true;
        i.auto_return = false;
        i.ws_up = true;
        i.ws_since = 0;
        i.now = 500_000;
        assert!(p.step(&i).0.is_empty());
        i.prefer = Prefer::Wifi;
        let a = p.step(&i).0;
        assert_eq!(a, vec![Act::SetWsPrimary(true), Act::Bye]);
    }

    #[test]
    fn force_ble_while_wifi_up_starts_scan_and_blocks_handback() {
        let mut p = Policy::new(0);
        let mut i = base(100_000);
        i.ws_up = true;
        i.ws_since = 0;
        i.prefer = Prefer::Ble;
        let a = p.step(&i).0;
        assert!(a.contains(&Act::Scan(true)));
        assert!(!a.contains(&Act::SetWsPrimary(true)));
    }

    #[test]
    fn flap_guard_doubles_the_window() {
        let mut p = Policy::new(0);
        let mut i = base(0);
        i.ble_present = true;
        i.ble_live = true;
        let mut t = 100_000;
        // Wi-Fi up/stable/down repeatedly within 2 minutes.
        for _ in 0..5 {
            i.ws_up = true;
            i.ws_since = t;
            i.now = t;
            p.step(&i);
            i.now = t + p.t_back;
            p.step(&i);
            i.ws_up = false;
            i.now = t + p.t_back + 1_000;
            p.step(&i);
            t += 12_000;
        }
        assert!(p.t_back > T_BACK);
        assert!(p.t_back <= 60_000);
    }

    #[test]
    fn mid_format_and_lru() {
        let a = next_mid();
        let b = next_mid();
        assert_ne!(a, b);
        let (p, n) = a.split_once('-').unwrap();
        assert_eq!(p.len(), 6);
        assert!(n.parse::<u64>().is_ok());
        let mut lru = ws_server::MidLru::default();
        assert!(!lru.seen("a-1"));
        assert!(lru.seen("a-1"));
        for i in 0..600 {
            lru.seen(&format!("x-{i}"));
        }
        assert!(!lru.seen("a-1"), "evicted after 512 newer ids");
    }
}
