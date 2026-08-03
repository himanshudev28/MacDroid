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
    Ping,
    Pong,
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
    MirrorTap(Extra),
    MirrorSwipe(Extra),
    MirrorKey(Extra),
    MirrorText(Extra),
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
        assert_eq!(ping, Message::Ping);
        let pong: Message = serde_json::from_value(serde_json::json!({ "type": "pong" })).unwrap();
        assert_eq!(pong, Message::Pong);
    }
}
