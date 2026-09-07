//! Lynko shared protocol — used by both the desktop (Tauri) and, via JNI,
//! the Android companion app.

use serde::{Deserialize, Serialize};

/// Protocol version exchanged during pairing.
/// Mismatch warns; only a major bump breaks compatibility.
pub const PROTOCOL_VERSION: u32 = 1;

/// mDNS service advertised by the phone.
pub const SERVICE_TYPE: &str = "_lynko._tcp.local.";

/// Capabilities a device supports. Negotiated at connect time so either
/// side can ship features ahead of the other.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Capabilities {
    pub screen_capture: bool,
    pub input_injection: bool,
    pub text_input: bool,
    pub clipboard_sync: bool,
    pub file_transfer: bool,
    pub notifications: bool,
    pub audio_capture: bool,
    pub battery_status: bool,
}

/// Envelope for every DataChannel message. JSON for now; protobuf later
/// only if profiling justifies it.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum Message {
    /// Touch event from desktop mouse.
    Touch { x: f32, y: f32, action: TouchAction },
    /// Text to place into the phone's focused field.
    TextInput { text: String },
    /// Clipboard content synced from either side.
    Clipboard { text: String },
    /// Notification posted on the phone.
    Notification { package: String, title: String, body: String },
    /// File chunk (binary framing rides outside this envelope).
    FileStart { id: u32, name: String, size: u64 },
    FileChunk { id: u32, seq: u32 },
    FileEnd { id: u32 },
    /// Periodic status heartbeat.
    Status { battery_pct: u8, charging: bool, rssi: Option<i8> },
    /// Handshake on connect.
    Hello { protocol: u32, caps: Capabilities },
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TouchAction {
    Down,
    Move,
    Up,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn message_roundtrips() {
        let m = Message::Touch { x: 0.5, y: 0.25, action: TouchAction::Down };
        let json = serde_json::to_string(&m).unwrap();
        let back: Message = serde_json::from_str(&json).unwrap();
        match back {
            Message::Touch { x, y, action } => {
                assert_eq!((x, y), (0.5, 0.25));
                assert!(matches!(action, TouchAction::Down));
            }
            _ => panic!("wrong variant"),
        }
    }

    #[test]
    fn capabilities_default_off() {
        let caps = Capabilities::default();
        assert!(!caps.screen_capture);
        assert!(!caps.audio_capture);
    }
}
