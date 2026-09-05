use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// The full wire vocabulary, verified against the actual reference source —
/// not the PRD's Part 2 summary, which turned out to omit several real
/// message types (dismiss, sms-threads/messages/send, contacts, photo-thumb,
/// app-shot, fs-cancel, fs-pull, fs-push-begin/done, phone-push*) found by
/// grepping `wifi.js` + `transfer.js` + `index.js` + `ConnectionManager.kt`.
///
/// Only the handshake variants (Hello/Welcome/Ping/Pong/Pause/Resume) are
/// precisely typed — Phase 2 is the only phase that behaviorally needs them.
/// Every other variant carries a generic field map instead of hand-typed
/// fields: giving them precise types now would mean guessing shapes from
/// call sites rather than reading each feature's real producer (SmsRepo.kt,
/// ContactsRepo.kt, PhotoRepo.kt, ...) — exactly what the PRD's per-feature
/// phases (4, 6, 7, 8, 9, 10, 11, 12) are each individually scoped to do.
/// This still gives every phase a stable enum to build on, and every real
/// captured message (see protocol-corpus/) deserializes and re-serializes
/// through it losslessly today.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum Message {
    // ── Handshake (Phase 2) ──────────────────────────────────────────
    Hello {
        token: String,
        name: String,
        #[serde(default)]
        caps: Vec<String>,
        /// A stable per-install id the phone generates once and persists.
        /// Additive and optional so a captured corpus hello (and any older
        /// phone build) still round-trips byte-for-byte.
        ///
        /// The explicit rename is load-bearing: the enum's `rename_all` applies
        /// to *variant* names, not to struct-variant *fields*, so without this
        /// serde looks for `device_id` while ConnectionManager.kt sends
        /// `deviceId` — and `#[serde(default)]` then swallows the mismatch
        /// silently, leaving the photo-sync ledger keyed on the display name
        /// exactly as before. Caught by running the real app against the phone.
        #[serde(rename = "deviceId", default, skip_serializing_if = "Option::is_none")]
        device_id: Option<String>,
    },
    Welcome {
        name: String,
        /// Phase 19: additive capability advertisement (e.g. `"macfs"`) so an
        /// older phone build that doesn't know the field simply ignores it —
        /// `skip_serializing_if` keeps a caps-less welcome (older captured
        /// corpus entries, pre-Phase-19 builds) round-tripping byte-for-byte.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        caps: Vec<String>,
    },
    /// Tier C link-quality probe. `t` is the Mac's epoch-ms at send time,
    /// echoed verbatim in the `Pong` so the phone needs no synchronised clock.
    /// Optional + skip-if-none so a bare `{"type":"ping"}` (wifi.js parity,
    /// and what the captured corpus contains) still round-trips byte-for-byte.
    Ping {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        t: Option<i64>,
    },
    Pong {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        t: Option<i64>,
    },
    Pause {
        until: i64,
    },
    Resume,

    // ── Everything else: real vocabulary, fields typed by their own phase ──
    Clipboard(Extra),
    Notification(Extra),
    NotificationRemoved(Extra),
    Reply(Extra),
    ReplyResult(Extra),
    DeviceInfo(Extra),
    Media(Extra),
    MediaCmd(Extra),
    SmsChanged(Extra),
    // Phase 18: payload-less ping from BridgeService's MediaStore ContentObserver.
    // No IDs on the wire — the phone tracks no cursor; the Mac re-diffs its own
    // ledger against the full photos-list on every fire (and on reconnect).
    PhotosChanged,
    Call(Extra),
    ActionCall(Extra),
    /// Answer / hang up / mute / speaker for the phone's current call, over the
    /// plain Wi-Fi link rather than ADB. Numeric-reqId request/reply pair like
    /// the other `respond()`-based features, caps-gated on `"callctl"` so an
    /// older phone build never receives one. See `CallControl.kt`.
    CallAction(Extra),
    CallActionResult(Extra),
    /// Permission-health request and its reply share this one type, the way
    /// `wallpaper` and `apps-list` do — see `health.rs` and
    /// `PermissionHealth.kt`. `health-fix` likewise: the Mac asks, the phone
    /// echoes back which of "opened it" / "left a notification" happened.
    Health(Extra),
    HealthFix(Extra),
    /// "Find my phone": request and reply share the type, and the reply carries
    /// `ringing` so the Mac's button reflects what the phone is actually doing
    /// rather than what it was last told. See `Ringer.kt`.
    Ring(Extra),
    ActionSms(Extra),
    Dismiss(Extra),

    FsList(Extra),
    FsDelete(Extra),
    FsRename(Extra),
    FsPull(Extra),
    FsPullBegin(Extra),
    FsPullDone(Extra),
    FsPullError(Extra),
    FsPush(Extra),
    FsPushBegin(Extra),
    FsPushDone(Extra),
    FsPushResult(Extra),
    FsPushError(Extra),
    FsCancel(Extra),

    // Phase 19: reverse file browsing (phone browses/pulls from a Mac-side
    // root allowlist — see `mac_fs.rs`). Phone-originated `reqId`s here are
    // opaque strings the Mac just echoes back, a disjoint namespace from
    // both `req_seq`'s numeric reqIds and the `phone-push*` string reqIds.
    MacFsList(Extra),
    MacFsListResult(Extra),
    MacFsListError(Extra),
    MacFsPull(Extra),
    MacFsPullBegin(Extra),
    MacFsPullDone(Extra),
    MacFsPullError(Extra),

    PhotosList(Extra),
    PhotoThumb(Extra),
    PhotoThumbError(Extra),

    // Tier B: the phone's wallpaper and its launchable apps. All four are
    // caps-gated (`"wallpaper"` / `"apps"` in the phone's hello) and follow
    // shapes that already exist here — `wallpaper`/`apps-list` are numeric-reqId
    // request/reply pairs like `photos-list`, and `app-icon` answers over the
    // existing KIND_THUMB binary frame exactly like `photo-thumb`.
    Wallpaper(Extra),
    AppsList(Extra),
    AppIcon(Extra),
    AppIconError(Extra),
    /// Fire-and-forget: open a package on the phone. No reply.
    AppLaunch(Extra),

    PhonePush(Extra),
    PhonePushBegin(Extra),
    PhonePushDone(Extra),
    PhonePushResult(Extra),
    AppShot(Extra),

    SmsThreads(Extra),
    SmsMessages(Extra),
    SmsSend(Extra),
    Contacts(Extra),

    MirrorStart(Extra),
    MirrorStop(Extra),
    MirrorStarted(Extra),
    MirrorStopped(Extra),
    MirrorError(Extra),
    CameraStart(Extra),
    CameraStop(Extra),
    CameraFlip(Extra),
    /// Tier D: phone→Mac input (`mac_remote`). Caps-gated behind `"remote"`,
    /// which the Mac only advertises while the feature is switched on.
    Remote(Extra),

    MirrorTap(Extra),
    MirrorSwipe(Extra),
    MirrorKey(Extra),
    MirrorText(Extra),

    /// Phone playback audio over the Wi-Fi path (`AudioCapture.kt`). The samples
    /// themselves ride binary kind-4 frames; these only carry the stream's
    /// format and lifecycle.
    AudioStarted(Extra),
    AudioStopped(Extra),
    AudioError(Extra),
}

/// Generic field bag for variants not yet behaviorally implemented — see
/// the `Message` doc comment for why these aren't precisely typed yet.
pub type Extra = Map<String, Value>;

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::Path;

    /// Every JSON message captured in the Phase 0.4 corpus (sanitized —
    /// see droiddock-tauri/CLAUDE.md) must deserialize into `Message` and
    /// re-serialize to an equivalent JSON value. Binary-frame log lines
    /// carry no message body and are skipped.
    #[test]
    fn corpus_round_trips() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../protocol-corpus/corpus.jsonl");
        let corpus = fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("failed to read {}: {e}", path.display()));

        let mut checked = 0;
        for (i, line) in corpus.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let entry: Value = serde_json::from_str(line)
                .unwrap_or_else(|e| panic!("corpus line {}: invalid JSON: {e}", i + 1));
            if entry.get("kind").and_then(Value::as_str) != Some("json") {
                continue;
            }
            let Some(original) = entry.get("message") else {
                continue;
            };

            let parsed: Message = serde_json::from_value(original.clone()).unwrap_or_else(|e| {
                panic!(
                    "corpus line {}: message did not deserialize into Message: {e}\n{original}",
                    i + 1
                )
            });
            let round_tripped = serde_json::to_value(&parsed).unwrap();
            assert_eq!(
                &round_tripped,
                original,
                "corpus line {}: round-trip mismatch",
                i + 1
            );
            checked += 1;
        }

        assert!(checked > 0, "corpus produced zero JSON messages to check");
    }

    #[test]
    fn hello_accepts_the_camelcase_deviceid_the_phone_actually_sends() {
        // ConnectionManager.kt puts "deviceId". If serde is expecting the
        // snake_case field name, this silently deserializes to None and the
        // photo-sync ledger keeps falling back to the display name.
        let json = serde_json::json!({
            "type": "hello", "token": "t", "name": "Pixel", "deviceId": "uuid-1"
        });
        let msg: Message = serde_json::from_value(json).unwrap();
        match msg {
            Message::Hello { device_id, .. } => {
                assert_eq!(device_id.as_deref(), Some("uuid-1"), "deviceId was dropped on the floor");
            }
            _ => panic!("not a hello"),
        }
    }

    #[test]
    fn hello_round_trips() {
        let json = serde_json::json!({
            "type": "hello",
            "token": "abc",
            "name": "Pixel",
            "caps": ["fs", "photos"]
        });
        let msg: Message = serde_json::from_value(json.clone()).unwrap();
        assert_eq!(
            msg,
            Message::Hello {
                token: "abc".into(),
                name: "Pixel".into(),
                caps: vec!["fs".into(), "photos".into()],
                device_id: None,
            }
        );
        assert_eq!(serde_json::to_value(&msg).unwrap(), json);
    }

    #[test]
    fn welcome_round_trips() {
        // Pre-Phase-19 shape (no `caps` field at all) must still deserialize
        // and, since `caps` defaults to empty and is `skip_serializing_if`,
        // re-serialize back to the exact same JSON.
        let json = serde_json::json!({ "type": "welcome", "name": "MacBookAir" });
        let msg: Message = serde_json::from_value(json.clone()).unwrap();
        assert_eq!(msg, Message::Welcome { name: "MacBookAir".into(), caps: vec![] });
        assert_eq!(serde_json::to_value(&msg).unwrap(), json);
    }

    #[test]
    fn welcome_with_caps_round_trips() {
        // Phase 19: the Mac now always sends `caps: ["macfs"]` — an additive
        // field a phone build from before this phase safely ignores.
        let json = serde_json::json!({ "type": "welcome", "name": "MacBookAir", "caps": ["macfs"] });
        let msg: Message = serde_json::from_value(json.clone()).unwrap();
        assert_eq!(
            msg,
            Message::Welcome { name: "MacBookAir".into(), caps: vec!["macfs".into()] }
        );
        assert_eq!(serde_json::to_value(&msg).unwrap(), json);
    }

    #[test]
    fn ping_pong_round_trip() {
        let ping: Message = serde_json::from_value(serde_json::json!({ "type": "ping" })).unwrap();
        assert_eq!(ping, Message::Ping { t: None });
        let pong: Message = serde_json::from_value(serde_json::json!({ "type": "pong" })).unwrap();
        assert_eq!(pong, Message::Pong { t: None });

        // Tier C: the timestamped forms round-trip too, and a bare ping still
        // re-serializes without injecting a null `t`.
        let stamped = serde_json::json!({ "type": "ping", "t": 1_700_000_000_000i64 });
        let msg: Message = serde_json::from_value(stamped.clone()).unwrap();
        assert_eq!(msg, Message::Ping { t: Some(1_700_000_000_000) });
        assert_eq!(serde_json::to_value(&msg).unwrap(), stamped);
        assert_eq!(
            serde_json::to_value(&Message::Ping { t: None }).unwrap(),
            serde_json::json!({ "type": "ping" })
        );
    }
}
