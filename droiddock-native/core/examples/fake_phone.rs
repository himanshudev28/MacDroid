//! A fake Android phone for exercising the core without a device.
//!
//!   cargo run --release --target aarch64-apple-darwin --example fake_phone -- <port> <token> [seconds]
//!
//! Performs the real hello/auth handshake (message shapes copied from
//! `ws_server::handle_connection` and `ConnectionManager.kt`'s `onOpen`), waits
//! for `welcome`, then pushes one `notification` (shape from `NotifListener.kt`)
//! and one `device-info` (shape from `DeviceInfo.kt`). Stays connected for
//! `seconds` (default 10), answering `ping` with `pong` and printing anything
//! else the Mac sends.

use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio_tungstenite::tungstenite::Message;

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let port: u16 = args.next().and_then(|p| p.parse().ok()).expect("usage: fake_phone <port> <token> [seconds]");
    let token = args.next().expect("usage: fake_phone <port> <token> [seconds]");
    let secs: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(10);
    // Optional flag: `--files` answers fs-list / photos-list / apps-list / photo-thumb / app-icon
    // with canned data (a small sdcard tree, 40 photos, 24 apps, solid-colour PNG thumbnails).
    let canned = std::env::args().any(|a| a == "--files");

    // Optional flags (after the positionals): `--demo-data` answers sms-threads / sms-messages /
    // sms-send / contacts with canned data; `--ring <secs>` pushes a ringing `call` after that
    // delay, then `active` 6 s later and `idle` after another 12 s. Defaults are unchanged.
    let all: Vec<String> = std::env::args().collect();
    // `--brightness <0..1>` sends one `mac-brightness-set` after the welcome; `--volume <0..100>` sends
    // `remote {action: volume_set}`. Both also advertise `macinfo` and print any `mac-info` / `caps` seen.
    let brightness: Option<f64> = all.iter().position(|a| a == "--brightness").and_then(|i| all.get(i + 1)).and_then(|s| s.parse().ok());
    let volume: Option<f64> = all.iter().position(|a| a == "--volume").and_then(|i| all.get(i + 1)).and_then(|s| s.parse().ok());
    let demo_data = all.iter().any(|a| a == "--demo-data");
    let ring_after: Option<u64> = all.iter().position(|a| a == "--ring").and_then(|i| all.get(i + 1)).and_then(|s| s.parse().ok());

    let url = format!("ws://127.0.0.1:{port}");
    let (ws, _) = tokio_tungstenite::connect_async(&url).await.expect("connect");
    println!("connected to {url}");
    let (mut tx, mut rx) = ws.split();

    // ConnectionManager.kt onOpen: type, token, name, caps, deviceId.
    let hello = json!({
        "type": "hello",
        "token": token,
        "name": "Fake Pixel",
        "caps": ["info", "fs", "photos", "apps", "health", "fsmkdir", "macinfo"],
        "deviceId": "fake-phone-0000-0000-0000",
    });
    tx.send(Message::text(hello.to_string())).await.unwrap();

    // The Mac replies `welcome {name, caps}` (or closes the socket on a bad token).
    let welcome = loop {
        match tokio::time::timeout(Duration::from_secs(5), rx.next()).await {
            Ok(Some(Ok(Message::Text(t)))) => {
                let v: Value = serde_json::from_str(&t).expect("welcome json");
                if v["type"] == "welcome" {
                    break v;
                }
                println!("[mac] {v}");
            }
            Ok(Some(Ok(_))) => continue,
            other => panic!("no welcome from the Mac: {other:?}"),
        }
    };
    println!("WELCOME: {welcome}");

    // NotifListener.kt
    let now_ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
    let notification = json!({
        "type": "notification",
        "key": "0|com.whatsapp|1|fake|10001",
        "app": "WhatsApp",
        "pkg": "com.whatsapp",
        "title": "Ada",
        "text": "Hello from the fake phone",
        "replyable": true,
        "when": now_ms,
        "backfill": false,
        "priority": "high",
        "ongoing": false,
        "actions": ["Mark as read"],
    });
    tx.send(Message::text(notification.to_string())).await.unwrap();
    println!("sent notification");

    // DeviceInfo.kt
    let info = json!({
        "type": "device-info",
        "model": "Google Pixel 8",
        "android": "15",
        "sdk": 35,
        "battery": 17,
        "charging": false,
    });
    tx.send(Message::text(info.to_string())).await.unwrap();
    println!("sent device-info");

    // Optional `--media`: one `media` push (MediaRemote.kt shape; art is a solid PNG, base64).
    if all.iter().any(|a| a == "--media") {
        use base64::Engine as _;
        let art = base64::engine::general_purpose::STANDARD.encode(png(96, 96, [200, 90, 60]));
        let m = json!({ "type": "media", "active": true, "title": "Midnight City", "artist": "M83",
            "app": "Spotify", "playing": true, "vol": 8, "volMax": 15, "pos": 84000, "dur": 243000,
            "trackKey": "Midnight City|M83|Hurry Up, We're Dreaming", "art": art });
        tx.send(Message::text(m.to_string())).await.unwrap();
        println!("sent media");
    }

    if let Some(v) = volume {
        tx.send(Message::text(json!({ "type": "remote", "action": "volume_set", "level": v }).to_string())).await.unwrap();
        println!("sent volume_set {v}");
    }
    if let Some(b) = brightness {
        tx.send(Message::text(json!({ "type": "mac-brightness-set", "level": b }).to_string())).await.unwrap();
        println!("sent mac-brightness-set {b}");
    }
    // `--drag`: remote-button down, a few moves, remote-button up (watch the Mac log with DD_REMOTE_DRYRUN=1).
    if all.iter().any(|a| a == "--drag") {
        let send = |v: Value| Message::text(v.to_string());
        tx.send(send(json!({ "type": "remote", "action": "mouse_move", "x": 100.0, "y": 100.0 }))).await.unwrap();
        tx.send(send(json!({ "type": "remote-button", "button": "left", "state": "down" }))).await.unwrap();
        for i in 1..=3 {
            tx.send(send(json!({ "type": "remote", "action": "mouse_move", "x": 100.0 + 40.0 * i as f64, "y": 100.0 + 20.0 * i as f64 }))).await.unwrap();
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        tx.send(send(json!({ "type": "remote-button", "button": "left", "state": "up" }))).await.unwrap();
        tx.send(send(json!({ "type": "remote", "action": "mouse_move", "x": 400.0, "y": 300.0 }))).await.unwrap();
        println!("sent drag sequence");
    }
    let started = Instant::now();
    let mut ring_step = 0u8;
    let deadline = Instant::now() + Duration::from_secs(secs);
    while let Some(left) = deadline.checked_duration_since(Instant::now()) {
        if let Some(r) = ring_after {
            let el = started.elapsed().as_secs();
            let (due, state) = match ring_step {
                0 => (el >= r, "ringing"),
                1 => (el >= r + 6, "active"),
                2 => (el >= r + 18, "idle"),
                _ => (false, ""),
            };
            if due {
                let m = json!({ "type": "call", "state": state, "number": "+1 415 555 0134", "name": "Ada Lovelace",
                    "canAnswer": true, "canEnd": true, "canAudio": true, "muted": false, "speaker": false });
                tx.send(Message::text(m.to_string())).await.unwrap();
                println!("sent call {state}");
                ring_step += 1;
            }
        }
        let left = left.min(Duration::from_millis(500));
        match tokio::time::timeout(left, rx.next()).await {
            Ok(Some(Ok(Message::Text(t)))) => {
                let v: Value = serde_json::from_str(&t).unwrap_or(Value::Null);
                if canned && canned_reply(&v, &mut tx).await {
                    // answered by the --files mode
                } else if demo_data && matches!(v["type"].as_str(), Some("sms-threads" | "sms-messages" | "sms-send" | "contacts")) {
                    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64;
                    let ty = v["type"].as_str().unwrap();
                    let mut out = json!({ "type": ty, "reqId": v["reqId"] });
                    match ty {
                        "sms-threads" => out["threads"] = json!([
                            { "threadId": 1, "address": "+14155550134", "name": "Ada Lovelace", "snippet": "See you at 6, bringing the notes", "date": now - 120_000 },
                            { "threadId": 2, "address": "+14155550177", "name": "Grace Hopper", "snippet": "The compiler finished 🎉", "date": now - 3_600_000 * 5 },
                            { "threadId": 3, "address": "AMZN-DLV", "name": "AMZN-DLV", "snippet": "Your package arrives today.", "date": now - 86_400_000 * 2 },
                        ]),
                        "sms-messages" => {
                            let tid = v["threadId"].as_i64().unwrap_or(1);
                            out["threadId"] = json!(tid);
                            out["address"] = json!("+14155550134");
                            out["messages"] = json!([
                                { "id": 1, "body": "Are we still on for tonight?", "date": now - 86_400_000 * 2, "out": false },
                                { "id": 2, "body": "Yes! Dinner at 7?", "date": now - 86_400_000 * 2 + 60_000, "out": true },
                                { "id": 3, "body": "Perfect. I will book the table.", "date": now - 3_600_000, "out": false },
                                { "id": 4, "body": "See you at 6, bringing the notes", "date": now - 120_000, "out": false },
                            ]);
                        }
                        "sms-send" => out["ok"] = json!(true),
                        _ => out["contacts"] = json!([
                            { "name": "Ada Lovelace", "number": "+1 415 555 0134", "starred": true },
                            { "name": "Grace Hopper", "number": "+1 415 555 0177", "starred": true },
                            { "name": "Alan Turing", "number": "+44 20 7946 0958", "starred": false },
                            { "name": "Margaret Hamilton", "number": "+1 617 555 0102", "starred": false },
                        ]),
                    }
                    tx.send(Message::text(out.to_string())).await.unwrap();
                } else if v["type"] == "ping" {
                    // ConnectionManager.kt: {type:"pong", t: <echo>}
                    let pong = json!({ "type": "pong", "t": v["t"] });
                    tx.send(Message::text(pong.to_string())).await.unwrap();
                } else {
                    let s = t.to_string();
                    println!("[mac] {}", s.chars().take(200).collect::<String>());
                }
            }
            Ok(Some(Ok(Message::Close(_)))) | Ok(None) => {
                println!("Mac closed the socket");
                break;
            }
            Ok(Some(Ok(_))) => {}
            Ok(Some(Err(e))) => {
                println!("socket error: {e}");
                break;
            }
            Err(_) => {}
        }
    }
    let _ = tx.close().await;
    println!("fake phone done");
}

// ── Optional canned replies (`--files`) ─────────────────────────────────────

const APPS: &[(&str, &str)] = &[
    ("com.whatsapp", "WhatsApp"), ("com.google.android.gm", "Gmail"), ("com.google.android.apps.maps", "Maps"),
    ("com.spotify.music", "Spotify"), ("com.instagram.android", "Instagram"), ("com.android.chrome", "Chrome"),
    ("com.google.android.youtube", "YouTube"), ("org.telegram.messenger", "Telegram"), ("com.android.camera", "Camera"),
    ("com.google.android.calendar", "Calendar"), ("com.android.settings", "Settings"), ("com.google.android.apps.photos", "Photos"),
    ("com.twitter.android", "X"), ("com.slack", "Slack"), ("com.zhiliaoapp.musically", "TikTok"), ("com.netflix.mediaclient", "Netflix"),
    ("com.google.android.keep", "Keep Notes"), ("com.android.calculator2", "Calculator"), ("com.google.android.dialer", "Phone"),
    ("com.google.android.apps.messaging", "Messages"), ("com.discord", "Discord"), ("com.reddit.frontpage", "Reddit"),
    ("com.google.android.apps.docs", "Drive"), ("com.android.vending", "Play Store"),
];

fn rgb_for(seed: u64) -> [u8; 3] {
    let h = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15);
    [(h >> 8) as u8 | 0x30, (h >> 24) as u8 | 0x30, (h >> 40) as u8 | 0x30]
}

/// Minimal valid PNG (stored-deflate, 8-bit RGB) of one solid colour with a vertical gradient.
fn png(w: u32, h: u32, rgb: [u8; 3]) -> Vec<u8> {
    fn crc(data: &[u8]) -> u32 {
        let mut c = 0xFFFF_FFFFu32;
        for &b in data {
            c ^= b as u32;
            for _ in 0..8 { c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 }; }
        }
        !c
    }
    fn chunk(out: &mut Vec<u8>, ty: &[u8; 4], data: &[u8]) {
        out.extend((data.len() as u32).to_be_bytes());
        let mut body = ty.to_vec();
        body.extend(data);
        out.extend(&body);
        out.extend(crc(&body).to_be_bytes());
    }
    let mut raw = Vec::new();
    for y in 0..h {
        raw.push(0);
        let k = 255 - (y * 90 / h) as i32;
        for _ in 0..w { for c in rgb { raw.push(((c as i32 * k) / 255) as u8); } }
    }
    let mut z = vec![0x78, 0x01];
    let mut i = 0;
    while i < raw.len() {
        let n = (raw.len() - i).min(65535);
        z.push(if i + n == raw.len() { 1 } else { 0 });
        z.extend((n as u16).to_le_bytes());
        z.extend((!(n as u16)).to_le_bytes());
        z.extend(&raw[i..i + n]);
        i += n;
    }
    let (mut a, mut b) = (1u32, 0u32);
    for &x in &raw { a = (a + x as u32) % 65521; b = (b + a) % 65521; }
    z.extend(((b << 16) | a).to_be_bytes());
    let mut out = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    let mut ihdr = Vec::new();
    ihdr.extend(w.to_be_bytes());
    ihdr.extend(h.to_be_bytes());
    ihdr.extend([8, 2, 0, 0, 0]);
    chunk(&mut out, b"IHDR", &ihdr);
    chunk(&mut out, b"IDAT", &z);
    chunk(&mut out, b"IEND", &[]);
    out
}

type Tx = futures_util::stream::SplitSink<
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
    Message,
>;

/// Returns true when the message was a request this mode answers.
async fn canned_reply(v: &Value, tx: &mut Tx) -> bool {
    let ty = v["type"].as_str().unwrap_or("");
    let req = &v["reqId"];
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
    let reply = match ty {
        "fs-list" => {
            let path = v["path"].as_str().unwrap_or("/sdcard");
            let e = |name: &str, dir: bool, size: u64, ago_h: u64| {
                json!({ "name": name, "dir": dir, "size": size, "modified": now - ago_h * 3_600_000 })
            };
            let entries = match path {
                "/sdcard" => vec![
                    e("DCIM", true, 0, 5), e("Download", true, 0, 2), e("Documents", true, 0, 70), e("Music", true, 0, 400),
                    e("Pictures", true, 0, 30), e("notes.txt", false, 2_300, 9), e("budget-2026.xlsx", false, 184_000, 50),
                    e("trip-itinerary.pdf", false, 2_400_000, 120), e("backup.zip", false, 812_000_000, 300),
                    e("voice-memo.m4a", false, 4_100_000, 12), e("clip.mp4", false, 96_000_000, 20),
                ],
                "/sdcard/Download" => vec![e("invoice-1042.pdf", false, 310_000, 3), e("wallpaper.png", false, 5_600_000, 8)],
                _ => vec![],
            };
            json!({ "type": "fs-list", "reqId": req, "entries": entries })
        }
        "fs-mkdir" => {
            let parent = v["path"].as_str().unwrap_or("/sdcard");
            let name = v["name"].as_str().unwrap_or("New folder");
            json!({ "type": "fs-mkdir", "reqId": req, "path": format!("{}/{}", parent.trim_end_matches('/'), name) })
        }
        "photos-list" => {
            let items: Vec<Value> = (0..40u64).map(|i| {
                let vid = i % 7 == 3;
                json!({ "id": i + 1, "name": format!("{}_{:04}.{}", if vid { "VID" } else { "IMG" }, 100 + i, if vid { "mp4" } else { "jpg" }),
                    "date": now - i * 5_400_000, "size": 2_000_000 + i * 31_000, "path": format!("/sdcard/DCIM/Camera/{}_{}", i, if vid { "v" } else { "p" }),
                    "kind": if vid { "video" } else { "image" }, "duration": if vid { json!(12 + i) } else { Value::Null } })
            }).collect();
            json!({ "type": "photos-list", "reqId": req, "items": items })
        }
        "apps-list" => {
            let apps: Vec<Value> = APPS.iter().map(|(p, l)| json!({ "pkg": p, "label": l })).collect();
            json!({ "type": "apps-list", "reqId": req, "apps": apps })
        }
        "photo-thumb" | "app-icon" => {
            let seed = v["id"].as_u64().unwrap_or_else(|| v["pkg"].as_str().map_or(0, |p| p.bytes().map(|b| b as u64).sum()));
            let (w, h) = if ty == "app-icon" { (96, 96) } else { (160, 160) };
            let mut frame = vec![2u8]; // KIND_THUMB
            frame.extend((req.as_u64().unwrap_or(0) as u32).to_be_bytes());
            frame.extend(0u32.to_be_bytes());
            frame.extend(png(w, h, rgb_for(seed)));
            tx.send(Message::binary(frame)).await.unwrap();
            return true;
        }
        _ => return false,
    };
    tx.send(Message::text(reply.to_string())).await.unwrap();
    true
}
