//! Which messages may travel over the Bluetooth LE fallback link
//! (docs/BLE-NEARBY-DESIGN.md §2).
//!
//! Every message type is **ANY** (BLE-eligible) or **WIFI** (Wi-Fi only).
//! Unknown types are WIFI. Binary frames never reach this function: they are
//! always Wi-Fi only. The Kotlin twin is `link/TransportClass.kt`.

use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Class {
    Any,
    Wifi,
}

/// Direction of the message, because the two ANY lists differ.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dir {
    PhoneToMac,
    MacToPhone,
}

/// Clipboard text above this never goes over BLE (§2 rule 6).
pub const MAX_BLE_CLIPBOARD: usize = 16 * 1024;

const P2M_ANY: &[&str] = &[
    "hello", "notification", "notification-removed", "reply-result", "notif-open-result", "call", "call-action-result",
    "media", "device-info", "sms-changed", "ring", "health", "health-fix", "sms-threads",
    "sms-messages", "sms-send", "ping", "pong", "mac-brightness-set", "pause", "resume", "link-mode", "link-prefer",
];

const M2P_ANY: &[&str] = &[
    "welcome", "caps", "ping", "pong", "mac-info", "mac-media", "reply", "dismiss", "notif-action", "notif-open",
    "action-call", "action-sms", "call-action", "ring", "health", "health-fix", "media-cmd",
    "sms-threads", "sms-messages", "sms-send", "app-launch", "link-mode", "link-prefer",
];

const REMOTE_ANY: &[&str] = &["key", "text", "media", "lock", "screensaver", "brightness", "volume_set"];

/// Classify one JSON message.
pub fn class_of(v: &Value, dir: Dir) -> Class {
    let ty = v.get("type").and_then(Value::as_str).unwrap_or("");
    if ty == "clipboard" {
        if v.get("kind").and_then(Value::as_str) == Some("image") {
            return Class::Wifi;
        }
        let len = v.get("text").and_then(Value::as_str).map_or(0, str::len);
        return if len <= MAX_BLE_CLIPBOARD { Class::Any } else { Class::Wifi };
    }
    if ty == "remote" && dir == Dir::PhoneToMac {
        let action = v.get("action").and_then(Value::as_str).unwrap_or("");
        return if REMOTE_ANY.contains(&action) { Class::Any } else { Class::Wifi };
    }
    let list = match dir {
        Dir::PhoneToMac => P2M_ANY,
        Dir::MacToPhone => M2P_ANY,
    };
    if list.contains(&ty) { Class::Any } else { Class::Wifi }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn c(v: Value, d: Dir) -> Class {
        class_of(&v, d)
    }

    #[test]
    fn every_phone_to_mac_any_type() {
        for ty in P2M_ANY {
            assert_eq!(c(json!({"type": ty}), Dir::PhoneToMac), Class::Any, "{ty}");
        }
        assert_eq!(c(json!({"type":"clipboard","text":"hi"}), Dir::PhoneToMac), Class::Any);
    }

    #[test]
    fn every_mac_to_phone_any_type() {
        for ty in M2P_ANY {
            assert_eq!(c(json!({"type": ty}), Dir::MacToPhone), Class::Any, "{ty}");
        }
        assert_eq!(c(json!({"type":"clipboard","text":"hi"}), Dir::MacToPhone), Class::Any);
    }

    #[test]
    fn wifi_only_types() {
        for ty in [
            "photos-changed", "remote-button", "fs-list", "fs-pull", "phone-push", "phone-push-begin",
            "photos-list", "photo-thumb", "app-icon", "apps-list", "wallpaper", "screenshot",
            "mirror-started", "audio-started", "camera-start", "control-unavailable", "mac-fs-list",
            "mac-apps-list", "mac-app-launch", "contacts", "nonsense",
        ] {
            assert_eq!(c(json!({"type": ty}), Dir::PhoneToMac), Class::Wifi, "{ty}");
            assert_eq!(c(json!({"type": ty}), Dir::MacToPhone), Class::Wifi, "{ty}");
        }
    }

    #[test]
    fn clipboard_image_and_oversize_are_wifi() {
        assert_eq!(c(json!({"type":"clipboard","kind":"image"}), Dir::MacToPhone), Class::Wifi);
        let big = "x".repeat(MAX_BLE_CLIPBOARD + 1);
        assert_eq!(c(json!({"type":"clipboard","text":big}), Dir::PhoneToMac), Class::Wifi);
        let ok = "x".repeat(MAX_BLE_CLIPBOARD);
        assert_eq!(c(json!({"type":"clipboard","text":ok}), Dir::PhoneToMac), Class::Any);
    }

    #[test]
    fn remote_actions() {
        for a in REMOTE_ANY {
            assert_eq!(c(json!({"type":"remote","action":a}), Dir::PhoneToMac), Class::Any, "{a}");
        }
        for a in ["mouse_move", "mouse_click", "mouse_scroll", ""] {
            assert_eq!(c(json!({"type":"remote","action":a}), Dir::PhoneToMac), Class::Wifi, "{a}");
        }
    }

    #[test]
    fn unknown_is_wifi() {
        assert_eq!(c(json!({}), Dir::PhoneToMac), Class::Wifi);
        assert_eq!(c(json!({"type":"zzz"}), Dir::MacToPhone), Class::Wifi);
    }
}
