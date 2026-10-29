//! Bluetooth LE fallback link, layers L1 and L2 (docs/BLE-NEARBY-DESIGN.md §4, §5).
//!
//! This file is sans-IO: no CoreBluetooth, no sockets, no clock. Swift's
//! `BleService` owns the radio and only splits/joins ATT values; everything
//! that needs the pairing token (rotating id, mutual auth, AEAD, DEFLATE,
//! counters) lives here. `ble_policy.rs` drives it.
//!
//! The same [`Session`] runs both roles (`Role::Mac`, `Role::Phone`) so the
//! loopback tests and `examples/fake_phone_ble.rs` exercise the exact code the
//! Mac uses against a faithful phone peer.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use serde_json::Value;
use sha2::Sha256;

/// Frame stream id for outbound L2 frames (`on_frame`). Not in the frozen header.
pub const STREAM_BLE_TX: u32 = 3;

pub const SERVICE_UUID: &str = "B5800E35-6F66-41DF-A777-60B974C4B5F9";
pub const MAX_FRAME: usize = 65_536;
pub const MAX_INFLATED: usize = 256 * 1024;
const SALT: &[u8] = b"droiddock-ble-v1";
const DEFLATE_MIN: usize = 200;
const EPOCH_MS: i64 = 900_000;

pub const FLAG_CTL: u8 = 0x80;
pub const FLAG_DEFLATE: u8 = 0x01;

pub const OP_P_HELLO: u8 = 0x01;
pub const OP_M_AUTH: u8 = 0x02;
pub const OP_P_AUTH: u8 = 0x03;
pub const OP_PING: u8 = 0x10;
pub const OP_PONG: u8 = 0x11;
pub const OP_BYE: u8 = 0x12;
pub const OP_FAIL: u8 = 0x7F;

pub const BYE_HANDBACK: u8 = 1;
pub const BYE_MODE_OFF: u8 = 2;
pub const BYE_PROTOCOL: u8 = 3;
pub const BYE_USER: u8 = 4;

type HmacSha256 = Hmac<Sha256>;

// ── Keys, rotating id, proofs ────────────────────────────────────────────

fn hkdf32(token: &str, info: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(SALT), token.as_bytes());
    let mut out = [0u8; 32];
    hk.expand(info, &mut out).expect("32 bytes is a valid HKDF-SHA256 length");
    out
}

fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut m = HmacSha256::new_from_slice(key).expect("hmac accepts any key length");
    for p in parts {
        m.update(p);
    }
    m.finalize().into_bytes().into()
}

pub fn k_rid(token: &str) -> [u8; 32] {
    hkdf32(token, b"rid")
}

pub fn epoch_of(unix_ms: i64) -> u64 {
    (unix_ms.max(0) / EPOCH_MS) as u64
}

/// The 8-byte rotating id for one 15-minute epoch.
pub fn rid(k_rid: &[u8; 32], epoch: u64) -> [u8; 8] {
    let h = hmac(k_rid, &[b"rid", &epoch.to_be_bytes()]);
    h[..8].try_into().unwrap()
}

/// `rid(e-1)`, `rid(e)`, `rid(e+1)` as lowercase hex, for the scanner to match.
pub fn rids_hex(token: &str, unix_ms: i64) -> Vec<String> {
    let k = k_rid(token);
    let e = epoch_of(unix_ms);
    [e.saturating_sub(1), e, e + 1].iter().map(|e| hex(&rid(&k, *e))).collect()
}

pub fn k_auth(token: &str) -> [u8; 32] {
    hkdf32(token, b"auth")
}

pub fn proof_m(k_auth: &[u8; 32], nonce_p: &[u8; 16], nonce_m: &[u8; 16]) -> [u8; 32] {
    hmac(k_auth, &[b"DDB1-M", nonce_p, nonce_m])
}

pub fn proof_p(k_auth: &[u8; 32], nonce_m: &[u8; 16], nonce_p: &[u8; 16]) -> [u8; 32] {
    hmac(k_auth, &[b"DDB1-P", nonce_m, nonce_p])
}

pub fn k_session(token: &str, nonce_p: &[u8; 16], nonce_m: &[u8; 16]) -> [u8; 32] {
    let mut info = b"session".to_vec();
    info.extend_from_slice(nonce_p);
    info.extend_from_slice(nonce_m);
    hkdf32(token, &info)
}

fn verify(key: &[u8; 32], parts: &[&[u8]], tag: &[u8]) -> bool {
    let mut m = HmacSha256::new_from_slice(key).expect("hmac key");
    for p in parts {
        m.update(p);
    }
    m.verify_slice(tag).is_ok()
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn unhex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok()).collect()
}

// ── L2 AEAD ──────────────────────────────────────────────────────────────

pub const DIR_P: u8 = 0x50;
pub const DIR_M: u8 = 0x4D;

fn nonce(dir: u8, ctr: u32) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[0] = dir;
    n[4..].copy_from_slice(&(ctr as u64).to_be_bytes());
    n
}

fn aad(flags: u8, ctr: u32, dir: u8) -> [u8; 6] {
    let c = ctr.to_be_bytes();
    [flags, c[0], c[1], c[2], c[3], dir]
}

/// Seal `plaintext` into a complete L2 frame `[flags][ctr u32][ct||tag]`.
pub fn seal_frame(key: &[u8; 32], dir: u8, ctr: u32, flags: u8, plaintext: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new(&Key::<Aes256Gcm>::from(*key));
    let ct = cipher
        .encrypt(
            &Nonce::from(nonce(dir, ctr)),
            Payload { msg: plaintext, aad: &aad(flags, ctr, dir) },
        )
        .expect("aes-gcm encrypt");
    let mut out = Vec::with_capacity(5 + ct.len());
    out.push(flags);
    out.extend_from_slice(&ctr.to_be_bytes());
    out.extend_from_slice(&ct);
    out
}

/// Open a sealed frame sent by `dir`. Returns `(flags, ctr, plaintext)`.
pub fn open_frame(key: &[u8; 32], dir: u8, frame: &[u8]) -> Option<(u8, u32, Vec<u8>)> {
    if frame.len() < 1 + 4 + 16 {
        return None;
    }
    let flags = frame[0];
    let ctr = u32::from_be_bytes(frame[1..5].try_into().ok()?);
    let cipher = Aes256Gcm::new(&Key::<Aes256Gcm>::from(*key));
    let pt = cipher
        .decrypt(&Nonce::from(nonce(dir, ctr)), Payload { msg: &frame[5..], aad: &aad(flags, ctr, dir) })
        .ok()?;
    Some((flags, ctr, pt))
}

/// Compress per §4 rules: JSON ≥ 200 B, not a clipboard message, and smaller out.
fn maybe_deflate(json: &[u8], is_clipboard: bool) -> Option<Vec<u8>> {
    if json.len() < DEFLATE_MIN || is_clipboard {
        return None;
    }
    let out = miniz_oxide::deflate::compress_to_vec(json, 6);
    (out.len() < json.len()).then_some(out)
}

// ── Session state machine ────────────────────────────────────────────────

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Role {
    Mac,
    Phone,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum St {
    /// Mac: waiting for P_HELLO. Phone: (not started)
    AwaitHello,
    /// Mac: sent M_AUTH, waiting for P_AUTH.
    AwaitPAuth,
    /// Phone: sent P_HELLO, waiting for M_AUTH.
    AwaitMAuth,
    Live,
    Dead,
}

#[derive(Debug, PartialEq)]
pub enum Ev {
    /// A frame to put on the wire (handshake replies, automatic PONG).
    Send(Vec<u8>),
    /// Mutual auth finished; the session key is live. Follow with the JSON hello.
    Authed,
    Json(Value),
    Pong(Vec<u8>),
    Bye(u8),
}

#[derive(Debug, PartialEq)]
pub enum LinkErr {
    /// Peer sent FAIL(reason).
    PeerFail(u8),
    /// Anything wrong enough to drop the session. Carries a short reason.
    Protocol(&'static str),
    /// Proof mismatch: counts toward the auth-failure rate limit.
    BadProof,
}

pub struct Session {
    role: Role,
    token: String,
    st: St,
    nonce_p: [u8; 16],
    nonce_m: [u8; 16],
    key: Option<[u8; 32]>,
    tx_ctr: u32,
    rx_ctr: u32,
}

fn rand16() -> [u8; 16] {
    let mut b = [0u8; 16];
    getrandom::fill(&mut b).expect("system randomness");
    b
}

fn ctl(op: u8, payload: &[u8]) -> Vec<u8> {
    let mut f = vec![FLAG_CTL, op];
    f.extend_from_slice(payload);
    f
}

impl Session {
    pub fn mac(token: &str) -> Self {
        Self::new(Role::Mac, token, [0; 16], rand16())
    }

    /// Test/vector constructor with fixed nonces (the role's own nonce is used).
    pub fn with_nonces(role: Role, token: &str, nonce_p: [u8; 16], nonce_m: [u8; 16]) -> Self {
        Self::new(role, token, nonce_p, nonce_m)
    }

    fn new(role: Role, token: &str, nonce_p: [u8; 16], nonce_m: [u8; 16]) -> Self {
        Session {
            role,
            token: token.to_string(),
            st: St::AwaitHello,
            nonce_p,
            nonce_m,
            key: None,
            tx_ctr: 0,
            rx_ctr: 0,
        }
    }

    /// Phone role only: the first frame, P_HELLO. (Sent on the CCCD write.)
    pub fn phone_start(token: &str) -> (Self, Vec<u8>) {
        let mut s = Self::new(Role::Phone, token, rand16(), [0; 16]);
        let f = s.start_phone();
        (s, f)
    }

    pub fn start_phone(&mut self) -> Vec<u8> {
        self.st = St::AwaitMAuth;
        let mut p = vec![1u8];
        p.extend_from_slice(&self.nonce_p);
        ctl(OP_P_HELLO, &p)
    }

    pub fn is_live(&self) -> bool {
        self.st == St::Live
    }

    pub fn session_key(&self) -> Option<[u8; 32]> {
        self.key
    }

    fn my_dir(&self) -> u8 {
        if self.role == Role::Mac { DIR_M } else { DIR_P }
    }
    fn peer_dir(&self) -> u8 {
        if self.role == Role::Mac { DIR_P } else { DIR_M }
    }

    pub fn fail_frame(reason: u8) -> Vec<u8> {
        ctl(OP_FAIL, &[reason])
    }

    /// Feed one complete inbound L2 frame.
    pub fn on_frame(&mut self, frame: &[u8]) -> Result<Vec<Ev>, LinkErr> {
        if self.st == St::Dead {
            return Err(LinkErr::Protocol("session closed"));
        }
        let r = self.on_frame_inner(frame);
        if r.is_err() {
            self.st = St::Dead;
        }
        r
    }

    fn on_frame_inner(&mut self, frame: &[u8]) -> Result<Vec<Ev>, LinkErr> {
        let Some(&flags) = frame.first() else { return Err(LinkErr::Protocol("empty frame")) };
        if flags & 0x7E != 0 {
            return Err(LinkErr::Protocol("unknown flag bits"));
        }
        // FAIL is the one unsealed frame that is valid at any time.
        if flags == FLAG_CTL && frame.get(1) == Some(&OP_FAIL) {
            return Err(LinkErr::PeerFail(*frame.get(2).unwrap_or(&0)));
        }
        if self.st != St::Live {
            return self.handshake(flags, frame);
        }
        // Live: everything is sealed.
        let (flags, ctr, pt0) =
            open_frame(&self.key.unwrap(), self.peer_dir(), frame).ok_or(LinkErr::Protocol("bad tag"))?;
        if ctr != self.rx_ctr {
            return Err(LinkErr::Protocol("counter out of order"));
        }
        self.rx_ctr = self.rx_ctr.wrapping_add(1);
        let mut pt = pt0;
        if flags & FLAG_DEFLATE != 0 {
            pt = miniz_oxide::inflate::decompress_to_vec_with_limit(&pt, MAX_INFLATED)
                .map_err(|_| LinkErr::Protocol("bad deflate"))?;
        }
        if flags & FLAG_CTL != 0 {
            let Some((&op, rest)) = pt.split_first() else { return Err(LinkErr::Protocol("empty ctl")) };
            return match op {
                OP_PING => Ok(vec![Ev::Send(self.seal_ctl(OP_PONG, rest))]),
                OP_PONG => Ok(vec![Ev::Pong(rest.to_vec())]),
                OP_BYE => Ok(vec![Ev::Bye(*rest.first().unwrap_or(&0))]),
                _ => Err(LinkErr::Protocol("unknown ctl op")),
            };
        }
        let v: Value = serde_json::from_slice(&pt).map_err(|_| LinkErr::Protocol("bad json"))?;
        Ok(vec![Ev::Json(v)])
    }

    fn handshake(&mut self, flags: u8, frame: &[u8]) -> Result<Vec<Ev>, LinkErr> {
        if flags != FLAG_CTL {
            return Err(LinkErr::Protocol("unsealed non-ctl before auth"));
        }
        let op = *frame.get(1).ok_or(LinkErr::Protocol("short ctl"))?;
        let body = &frame[2..];
        match (self.role, self.st, op) {
            (Role::Mac, St::AwaitHello, OP_P_HELLO) => {
                if body.len() != 17 || body[0] != 1 {
                    return Err(LinkErr::Protocol("bad P_HELLO"));
                }
                self.nonce_p.copy_from_slice(&body[1..17]);
                let ka = k_auth(&self.token);
                let proof = proof_m(&ka, &self.nonce_p, &self.nonce_m);
                let mut p = self.nonce_m.to_vec();
                p.extend_from_slice(&proof);
                self.st = St::AwaitPAuth;
                Ok(vec![Ev::Send(ctl(OP_M_AUTH, &p))])
            }
            (Role::Mac, St::AwaitPAuth, OP_P_AUTH) => {
                let ka = k_auth(&self.token);
                if !verify(&ka, &[b"DDB1-P", &self.nonce_m, &self.nonce_p], body) {
                    return Err(LinkErr::BadProof);
                }
                self.go_live();
                Ok(vec![Ev::Authed])
            }
            (Role::Phone, St::AwaitMAuth, OP_M_AUTH) => {
                if body.len() != 48 {
                    return Err(LinkErr::Protocol("bad M_AUTH"));
                }
                self.nonce_m.copy_from_slice(&body[..16]);
                let ka = k_auth(&self.token);
                if !verify(&ka, &[b"DDB1-M", &self.nonce_p, &self.nonce_m], &body[16..]) {
                    return Err(LinkErr::BadProof);
                }
                let proof = proof_p(&ka, &self.nonce_m, &self.nonce_p);
                self.go_live();
                Ok(vec![Ev::Send(ctl(OP_P_AUTH, &proof)), Ev::Authed])
            }
            _ => Err(LinkErr::Protocol("unexpected handshake frame")),
        }
    }

    fn go_live(&mut self) {
        self.key = Some(k_session(&self.token, &self.nonce_p, &self.nonce_m));
        self.tx_ctr = 0;
        self.rx_ctr = 0;
        self.st = St::Live;
    }

    /// Seal an app JSON message. `Err("too large")` when the frame exceeds 64 KiB.
    pub fn seal_json(&mut self, v: &Value) -> Result<Vec<u8>, &'static str> {
        let json = serde_json::to_vec(v).map_err(|_| "bad json")?;
        let is_clip = v.get("type").and_then(Value::as_str) == Some("clipboard");
        let (flags, body) = match maybe_deflate(&json, is_clip) {
            Some(d) => (FLAG_DEFLATE, d),
            None => (0, json),
        };
        if 5 + body.len() + 16 > MAX_FRAME {
            return Err("too large");
        }
        Ok(self.seal(flags, &body))
    }

    pub fn seal_ctl(&mut self, op: u8, payload: &[u8]) -> Vec<u8> {
        let mut pt = vec![op];
        pt.extend_from_slice(payload);
        self.seal(FLAG_CTL, &pt)
    }

    fn seal(&mut self, flags: u8, pt: &[u8]) -> Vec<u8> {
        let f = seal_frame(&self.key.expect("seal before live"), self.my_dir(), self.tx_ctr, flags, pt);
        self.tx_ctr = self.tx_ctr.wrapping_add(1);
        f
    }
}

// ── L1 fragmentation (Swift owns the real one; this backs vectors and tests) ──

/// Split one L2 frame into ATT values no larger than `max_value`.
/// `seq` is the per-direction counter (mod 64), advanced in place.
pub fn fragment(frame: &[u8], max_value: usize, seq: &mut u8) -> Vec<Vec<u8>> {
    assert!(max_value > 4);
    let len = frame.len();
    let mut out = Vec::new();
    let mut off = 0;
    while off < len || out.is_empty() {
        let first = out.is_empty();
        let head = if first { 4 } else { 1 };
        let n = (max_value - head).min(len - off);
        let last = off + n == len;
        let mut hdr = *seq & 0x3F;
        if first {
            hdr |= 0x80;
        }
        if last {
            hdr |= 0x40;
        }
        let mut v = vec![hdr];
        if first {
            v.extend_from_slice(&(len as u32).to_be_bytes()[1..]);
        }
        v.extend_from_slice(&frame[off..off + n]);
        out.push(v);
        *seq = (*seq + 1) & 0x3F;
        off += n;
    }
    out
}

/// Receiver side of L1. Any `Err` means: drop the connection.
#[derive(Default)]
pub struct Reassembler {
    buf: Vec<u8>,
    want: usize,
    next_seq: Option<u8>,
    active: bool,
}

impl Reassembler {
    pub fn push(&mut self, v: &[u8]) -> Result<Option<Vec<u8>>, &'static str> {
        let Some(&hdr) = v.first() else { return Err("empty") };
        let (start, end, seq) = (hdr & 0x80 != 0, hdr & 0x40 != 0, hdr & 0x3F);
        if let Some(exp) = self.next_seq {
            if exp != seq {
                return Err("sequence gap");
            }
        }
        self.next_seq = Some((seq + 1) & 0x3F);
        let data = if start {
            if v.len() < 4 {
                return Err("short start");
            }
            self.want = u32::from_be_bytes([0, v[1], v[2], v[3]]) as usize;
            if self.want > MAX_FRAME {
                return Err("oversize");
            }
            self.buf.clear();
            self.active = true;
            &v[4..]
        } else {
            if !self.active {
                return Err("data without start");
            }
            &v[1..]
        };
        self.buf.extend_from_slice(data);
        if self.buf.len() > self.want {
            return Err("overrun");
        }
        if end {
            self.active = false;
            if self.buf.len() != self.want {
                return Err("length mismatch");
            }
            return Ok(Some(std::mem::take(&mut self.buf)));
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const TOKEN: &str = "acad86c1-6041-408c-bce7-2495dd338711";
    fn np() -> [u8; 16] {
        std::array::from_fn(|i| i as u8)
    }
    fn nm() -> [u8; 16] {
        std::array::from_fn(|i| 0x10 + i as u8)
    }

    #[test]
    fn rid_vectors() {
        let k = k_rid(TOKEN);
        assert_eq!(hex(&k), "292f553951c2c10d9c06bcfb2eb473809e51542ef7d76db90057d3ddd2a5e8ec");
        assert_eq!(epoch_of(1_900_000_000_000), 2111111);
        assert_eq!(hex(&rid(&k, 2111110)), "a6d762b56cb65216");
        assert_eq!(hex(&rid(&k, 2111111)), "df638fe5722213b6");
        assert_eq!(hex(&rid(&k, 2111112)), "a1b3e70a3219af3b");
        assert_eq!(
            rids_hex(TOKEN, 1_900_000_000_000),
            vec!["a6d762b56cb65216", "df638fe5722213b6", "a1b3e70a3219af3b"]
        );
    }

    #[test]
    fn handshake_vectors() {
        let ka = k_auth(TOKEN);
        assert_eq!(hex(&ka), "bd1d063d94603644f48f268ef4bfcf44dabfd3e7aac48533e2c2f13611435f99");
        assert_eq!(
            hex(&proof_m(&ka, &np(), &nm())),
            "09f3919abd55e8f7c51baf05c68c0d8a7982486b5b8eccc932984ad5ae0600d6"
        );
        assert_eq!(
            hex(&proof_p(&ka, &nm(), &np())),
            "35adeacc175006673cffba01e5aaafde866951ffc0b908ae93d45d79f0e4c915"
        );
        assert_eq!(
            hex(&k_session(TOKEN, &np(), &nm())),
            "7c7e1b811fdf9464357a83402df82cd83a4e9b4779820957ab437ca225cf3ad1"
        );
    }

    /// Run a full handshake between a Mac and a phone session, returning both live.
    fn live_pair() -> (Session, Session) {
        let mut mac = Session::with_nonces(Role::Mac, TOKEN, [0; 16], nm());
        let mut phone = Session::with_nonces(Role::Phone, TOKEN, np(), [0; 16]);
        let hello = phone.start_phone();
        let Ev::Send(m_auth) = mac.on_frame(&hello).unwrap().remove(0) else { panic!() };
        let mut evs = phone.on_frame(&m_auth).unwrap();
        assert_eq!(evs.pop(), Some(Ev::Authed));
        let Ev::Send(p_auth) = evs.remove(0) else { panic!() };
        assert_eq!(mac.on_frame(&p_auth).unwrap(), vec![Ev::Authed]);
        assert_eq!(mac.session_key(), phone.session_key());
        (mac, phone)
    }

    #[test]
    fn full_handshake_and_json_both_ways() {
        let (mut mac, mut phone) = live_pair();
        let hello = json!({"type":"hello","token":TOKEN,"name":"Pixel","caps":["ble"]});
        let f = phone.seal_json(&hello).unwrap();
        assert_eq!(mac.on_frame(&f).unwrap(), vec![Ev::Json(hello)]);
        let w = json!({"type":"welcome","name":"Mac","caps":["ble"]});
        let f = mac.seal_json(&w).unwrap();
        assert_eq!(phone.on_frame(&f).unwrap(), vec![Ev::Json(w)]);
    }

    #[test]
    fn wrong_token_fails_with_bad_proof() {
        let mut mac = Session::with_nonces(Role::Mac, "other-token", [0; 16], nm());
        let mut phone = Session::with_nonces(Role::Phone, TOKEN, np(), [0; 16]);
        let hello = phone.start_phone();
        let Ev::Send(m_auth) = mac.on_frame(&hello).unwrap().remove(0) else { panic!() };
        assert_eq!(phone.on_frame(&m_auth).unwrap_err(), LinkErr::BadProof);
    }

    #[test]
    fn forged_phone_proof_is_rejected_by_mac() {
        let mut mac = Session::with_nonces(Role::Mac, TOKEN, [0; 16], nm());
        let mut phone = Session::with_nonces(Role::Phone, "other-token", np(), [0; 16]);
        let hello = phone.start_phone();
        let Ev::Send(m_auth) = mac.on_frame(&hello).unwrap().remove(0) else { panic!() };
        // The phone with the wrong token cannot verify M_AUTH; forge P_AUTH anyway.
        assert!(phone.on_frame(&m_auth).is_err());
        let forged = ctl(OP_P_AUTH, &[0u8; 32]);
        assert_eq!(mac.on_frame(&forged).unwrap_err(), LinkErr::BadProof);
    }

    #[test]
    fn replay_reorder_and_tamper_are_rejected() {
        let (mut mac, mut phone) = live_pair();
        let a = phone.seal_json(&json!({"type":"ping"})).unwrap();
        let b = phone.seal_json(&json!({"type":"pong"})).unwrap();
        // replay of frame 0 after receiving it
        let mut m1 = live_pair().0;
        let (_, mut p1) = live_pair();
        let f0 = p1.seal_json(&json!({"type":"ping"})).unwrap();
        // same nonces => same key, so m1 can read f0
        assert!(m1.on_frame(&f0).is_ok());
        assert_eq!(m1.on_frame(&f0).unwrap_err(), LinkErr::Protocol("counter out of order"));
        // skipping a frame is a gap
        assert_eq!(mac.on_frame(&b).unwrap_err(), LinkErr::Protocol("counter out of order"));
        // tamper
        let (mut mac, mut phone) = live_pair();
        let mut f = phone.seal_json(&json!({"type":"ping"})).unwrap();
        *f.last_mut().unwrap() ^= 1;
        assert_eq!(mac.on_frame(&f).unwrap_err(), LinkErr::Protocol("bad tag"));
        let _ = a;
    }

    #[test]
    fn unknown_flag_bits_dropped() {
        let (mut mac, _) = live_pair();
        assert!(mac.on_frame(&[0x02, 0, 0, 0, 0]).is_err());
    }

    #[test]
    fn ping_gets_sealed_pong() {
        let (mut mac, mut phone) = live_pair();
        let ping = mac.seal_ctl(OP_PING, &[0, 0, 0, 7]);
        let Ev::Send(pong) = phone.on_frame(&ping).unwrap().remove(0) else { panic!() };
        assert_eq!(mac.on_frame(&pong).unwrap(), vec![Ev::Pong(vec![0, 0, 0, 7])]);
        let bye = phone.seal_ctl(OP_BYE, &[BYE_HANDBACK]);
        assert_eq!(mac.on_frame(&bye).unwrap(), vec![Ev::Bye(1)]);
    }

    #[test]
    fn deflate_rules() {
        let (mut mac, mut phone) = live_pair();
        let big = json!({"type":"sms-threads","items":(0..80).map(|i| json!({"a":i,"b":"hello hello hello"})).collect::<Vec<_>>()});
        let f = phone.seal_json(&big).unwrap();
        assert_eq!(f[0] & FLAG_DEFLATE, FLAG_DEFLATE);
        assert_eq!(mac.on_frame(&f).unwrap(), vec![Ev::Json(big)]);
        // small => not compressed
        let f = phone.seal_json(&json!({"type":"ping"})).unwrap();
        assert_eq!(f[0], 0);
        let _ = mac.on_frame(&f).unwrap();
        // clipboard never compressed even when large and compressible
        let clip = json!({"type":"clipboard","text":"a".repeat(5000)});
        let f = phone.seal_json(&clip).unwrap();
        assert_eq!(f[0], 0);
    }

    #[test]
    fn inflate_bomb_is_capped_and_oversize_send_refused() {
        let (mut mac, mut phone) = live_pair();
        // A deflate frame that inflates past 256 KiB must be rejected.
        let bomb = miniz_oxide::deflate::compress_to_vec(&vec![b' '; MAX_INFLATED + 10], 6);
        let key = phone.session_key().unwrap();
        let f = seal_frame(&key, DIR_P, 0, FLAG_DEFLATE, &bomb);
        assert_eq!(mac.on_frame(&f).unwrap_err(), LinkErr::Protocol("bad deflate"));
        // Incompressible big payload cannot be sent.
        let mut x = 0x9E3779B9u32;
        let noise: String = (0..160_000u32)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                char::from(b'!' + (x % 90) as u8)
            })
            .collect();
        let (_, mut p) = live_pair();
        assert_eq!(p.seal_json(&json!({"type":"x","d":noise})).unwrap_err(), "too large");
    }

    #[test]
    fn peer_fail_surfaces() {
        let (mut mac, _) = live_pair();
        assert_eq!(mac.on_frame(&Session::fail_frame(1)).unwrap_err(), LinkErr::PeerFail(1));
    }

    #[test]
    fn l1_fragment_and_reassemble() {
        let frame: Vec<u8> = (0..600u32).map(|i| (i % 251) as u8).collect();
        let mut seq = 0;
        let pk = fragment(&frame, 182, &mut seq);
        assert_eq!(pk.len(), 4);
        assert_eq!(pk[0][0], 0x80);
        assert_eq!(&pk[0][1..4], &[0, 2, 88]);
        assert_eq!(pk[3][0], 0x40 | 3);
        assert!(pk.iter().all(|p| p.len() <= 182));
        let mut r = Reassembler::default();
        let mut got = None;
        for p in &pk {
            got = r.push(p).unwrap();
        }
        assert_eq!(got.unwrap(), frame);
        // single-packet frame
        let mut seq = 5;
        let pk = fragment(&[1, 2, 3], 182, &mut seq);
        assert_eq!(pk, vec![vec![0x80 | 0x40 | 5, 0, 0, 3, 1, 2, 3]]);
    }

    #[test]
    fn l1_rejects_gaps_oversize_and_orphans() {
        let frame = vec![9u8; 600];
        let mut seq = 0;
        let pk = fragment(&frame, 182, &mut seq);
        let mut r = Reassembler::default();
        r.push(&pk[0]).unwrap();
        assert_eq!(r.push(&pk[2]).unwrap_err(), "sequence gap");
        let mut r = Reassembler::default();
        assert_eq!(r.push(&[0x01, 1, 2]).unwrap_err(), "data without start");
        let mut r = Reassembler::default();
        assert_eq!(r.push(&[0xC0, 0x01, 0x00, 0x01, 1]).unwrap_err(), "oversize");
    }

    /// Writes docs/ble-vectors.json for the Android builder. Run explicitly:
    /// `cargo test --target aarch64-apple-darwin --lib gen_ble_vectors -- --ignored`
    #[test]
    #[ignore]
    fn gen_ble_vectors() {
        let ks = k_session(TOKEN, &np(), &nm());
        let ping = json_ping_plain();
        let sealed_ping_p = seal_frame(&ks, DIR_P, 0, FLAG_CTL, &ping);
        let sealed_ping_m = seal_frame(&ks, DIR_M, 0, FLAG_CTL, &ping);
        let plain = br#"{"type":"ping","t":1730200000000}"#.to_vec();
        let sealed_json = seal_frame(&ks, DIR_P, 1, 0, &plain);
        let big = serde_json::to_vec(&json!({"type":"sms-threads","items":(0..40).map(|i| json!({"id":i,"body":"hello hello hello hello"})).collect::<Vec<_>>()})).unwrap();
        let defl = miniz_oxide::deflate::compress_to_vec(&big, 6);
        let sealed_deflate = seal_frame(&ks, DIR_P, 2, FLAG_DEFLATE, &defl);
        let frame600: Vec<u8> = (0..600u32).map(|i| (i % 251) as u8).collect();
        let mut seq = 0;
        let frags: Vec<String> = fragment(&frame600, 182, &mut seq).iter().map(|p| hex(p)).collect();
        let v = json!({
            "token": TOKEN, "nonceP": hex(&np()), "nonceM": hex(&nm()), "sessionKey": hex(&ks),
            "notes": "dir P=0x50 phone->Mac, M=0x4D Mac->phone. nonce=[dir][0 0 0][u64be ctr]. AAD=[flags][ctr u32be][dir]. Deflate bytes are miniz_oxide level 6; verify by opening and inflating, do not compare compressor output.",
            "sealedCtlPing": { "dir":"P", "ctr":0, "flags":"80", "plaintext": hex(&ping), "frame": hex(&sealed_ping_p) },
            "sealedCtlPingMac": { "dir":"M", "ctr":0, "flags":"80", "plaintext": hex(&ping), "frame": hex(&sealed_ping_m) },
            "sealedJson": { "dir":"P", "ctr":1, "flags":"00", "plaintext": hex(&plain), "frame": hex(&sealed_json) },
            "sealedDeflateJson": { "dir":"P", "ctr":2, "flags":"01", "json": String::from_utf8(big).unwrap(), "frame": hex(&sealed_deflate) },
            "l1": { "frame": hex(&frame600), "fragmentSize": 182, "startSeq": 0, "packets": frags },
        });
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../docs/ble-vectors.json");
        std::fs::write(path, serde_json::to_string_pretty(&v).unwrap() + "\n").unwrap();
    }

    fn json_ping_plain() -> Vec<u8> {
        vec![OP_PING, 0, 0, 0x03, 0xE8]
    }

    /// The committed vector file must still match the code.
    #[test]
    fn committed_vectors_open() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../docs/ble-vectors.json");
        let Ok(s) = std::fs::read_to_string(path) else { return };
        let v: Value = serde_json::from_str(&s).unwrap();
        let ks: [u8; 32] = unhex(v["sessionKey"].as_str().unwrap()).unwrap().try_into().unwrap();
        for (name, dir) in [("sealedCtlPing", DIR_P), ("sealedCtlPingMac", DIR_M), ("sealedJson", DIR_P), ("sealedDeflateJson", DIR_P)] {
            let f = unhex(v[name]["frame"].as_str().unwrap()).unwrap();
            let (flags, _, pt) = open_frame(&ks, dir, &f).unwrap_or_else(|| panic!("{name} must open"));
            let pt = if flags & FLAG_DEFLATE != 0 {
                miniz_oxide::inflate::decompress_to_vec_with_limit(&pt, MAX_INFLATED).unwrap()
            } else {
                pt
            };
            if name == "sealedDeflateJson" {
                assert_eq!(pt, v[name]["json"].as_str().unwrap().as_bytes());
            }
        }
    }
}
