//! Lynko shared protocol — used by the desktop (Tauri) and the phone
//! companion (Kotlin via JNI, or the Rust simulator during development).

use serde::{Deserialize, Serialize};

/// Protocol version exchanged during pairing. Mismatching majors are
/// incompatible; minors are compatible.
pub const PROTOCOL_VERSION: u32 = 1;

/// mDNS service type phones advertise.
pub const SERVICE_TYPE: &str = "_lynko._tcp.local.";

/// Default ports. Pairing is plain HTTP on `pair_port`, the control link is a
/// WebSocket on `link_port`.
pub const PAIR_PORT: u16 = 7912;
pub const LINK_PORT: u16 = 7913;

/// Maximum file chunk payload (bytes) carried in one binary WS frame.
pub const FILE_CHUNK_SIZE: usize = 256 * 1024;

/// Capabilities a phone reports at discovery (TXT) and pairing (JSON).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
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

impl Capabilities {
    /// Parse the `cap=` TXT value: comma-separated keys, e.g. `cap=clip,files,notif`.
    pub fn from_txt(val: &str) -> Self {
        let mut c = Capabilities::default();
        for k in val.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            match k {
                "screen" => c.screen_capture = true,
                "input" => c.input_injection = true,
                "text" => c.text_input = true,
                "clip" => c.clipboard_sync = true,
                "files" => c.file_transfer = true,
                "notif" => c.notifications = true,
                "audio" => c.audio_capture = true,
                "batt" => c.battery_status = true,
                _ => {}
            }
        }
        c
    }

    /// Render the TXT `cap=` value.
    pub fn to_txt(&self) -> String {
        let mut v = Vec::new();
        if self.screen_capture {
            v.push("screen");
        }
        if self.input_injection {
            v.push("input");
        }
        if self.text_input {
            v.push("text");
        }
        if self.clipboard_sync {
            v.push("clip");
        }
        if self.file_transfer {
            v.push("files");
        }
        if self.notifications {
            v.push("notif");
        }
        if self.audio_capture {
            v.push("audio");
        }
        if self.battery_status {
            v.push("batt");
        }
        v.join(",")
    }
}

/// Desktop → phone pairing request (HTTP POST /pair body).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequest {
    pub protocol_version: u32,
    pub pin: String,
    pub desktop_name: String,
}

/// Phone → desktop pairing response (HTTP body, 200 on success).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairResponse {
    pub ok: bool,
    pub error: Option<String>,
    pub device_name: String,
    pub capabilities: Capabilities,
    /// WS port for the control link (usually `LINK_PORT`).
    pub link_port: u16,
    /// HTTP port for the LocalSend-style transfer server (0 = unsupported).
    #[serde(default)]
    pub transfer_port: u16,
}

/// Commands desktop → phone over the control link (JSON text frames).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "t", content = "d", rename_all = "snake_case")]
pub enum Command {
    /// Phone should copy this text to its clipboard.
    Copy {
        text: String,
    },
    /// Phone should return its current clipboard text.
    Paste,
    /// Phone should start screen capture (returns an SDP offer response later
    /// via the signaling channel on the same socket).
    StartScreen,
    StopScreen,
    StartAudio,
    StopAudio,
    /// Ask for battery/status snapshot (phone also pushes `Event::Battery`).
    StatusGet,
    /// Binary file transfer preamble (chunks follow as binary frames).
    FileBegin {
        id: String,
        name: String,
        size: u64,
    },
    /// Last chunk sent — receiver flushes its buffer to disk now.
    FileEnd {
        id: String,
    },
    /// Screen/audio signaling payload (SDP offer/answer, ICE — opaque JSON).
    /// The real phone answers WebRTC; the simulator answers `{"kind":"sim-video"}`.
    Signal {
        payload: serde_json::Value,
    },
    /// Inject a tap at normalized screen coordinates (0..1).
    Tap {
        x: f32,
        y: f32,
    },
    /// Inject a swipe between normalized coordinates.
    Swipe {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
    },
    /// Live-drag segments (stroke continuation): first segment starts the
    /// pointer down, `false` segments continue it, the last `true` one lifts.
    DragStart {
        x: f32,
        y: f32,
    },
    DragMove {
        x: f32,
        y: f32,
    },
    DragEnd {
        x: f32,
        y: f32,
    },
    /// Inject a key press (android keycode name or UI key, e.g. "Enter").
    Key {
        key: String,
    },
    /// Inject a text commit (IME string).
    Text {
        text: String,
    },
    /// Reply to a phone notification via its RemoteInput action.
    NotifReply {
        app: String,
        notif_id: i32,
        text: String,
    },
}

/// Events phone → desktop over the control link (JSON text frames).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "t", content = "d", rename_all = "snake_case")]
pub enum Event {
    Clipboard {
        text: String,
    },
    Battery {
        pct: u8,
        charging: bool,
    },
    Notification {
        app: String,
        title: String,
        body: String,
        /// Android notification id — needed to reply to this notification.
        #[serde(default)]
        notif_id: i32,
    },
    /// Reply to `Command::Paste`.
    ClipboardReply {
        text: String,
    },
    /// File transfer finished (phone-side).
    FileDone {
        id: String,
        ok: bool,
        error: Option<String>,
    },
    /// Screen signaling payload (SDP/ICE, opaque JSON blob).
    Signal {
        payload: serde_json::Value,
    },
    Log {
        msg: String,
    },
}

/// Binary WS frame header for file chunks (desktop → phone).
/// Layout: b"LF1" + id_len(u8) + id(utf8) + data…
pub const CHUNK_MAGIC: &[u8; 3] = b"LF1";

/// Binary WS frame header for screen frames (phone → desktop).
/// Layout: b"LV1" + jpeg bytes.
pub const FRAME_MAGIC: &[u8; 3] = b"LV1";

pub fn encode_chunk(id: &str, data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(3 + 1 + id.len() + data.len());
    out.extend_from_slice(CHUNK_MAGIC);
    out.push(id.len() as u8);
    out.extend_from_slice(id.as_bytes());
    out.extend_from_slice(data);
    out
}

pub enum DecodedFrame {
    Chunk { id: String, data: Vec<u8> },
    Text,
}

/// Decode a binary WS frame; `Text` for anything that isn't a chunk.
pub fn decode_frame(frame: &[u8]) -> DecodedFrame {
    if frame.len() > 4 && &frame[..3] == CHUNK_MAGIC {
        let id_len = frame[3] as usize;
        if frame.len() > 4 + id_len {
            let id = String::from_utf8_lossy(&frame[4..4 + id_len]).into_owned();
            let data = frame[4 + id_len..].to_vec();
            return DecodedFrame::Chunk { id, data };
        }
    }
    DecodedFrame::Text
}

/// Audio chunk header after the `b"LF1"` magic (phone -> desktop):
/// u16 LE sample rate, u16 LE channels, u32 LE sample count, then PCM i16 LE.
pub fn parse_audio_chunk(bin: &[u8]) -> Option<(u16, u16, u32, Vec<i16>)> {
    if bin.len() < 3 + 8 || &bin[..3] != CHUNK_MAGIC {
        return None;
    }
    let rate = u16::from_le_bytes([bin[3], bin[4]]);
    let chans = u16::from_le_bytes([bin[5], bin[6]]);
    let count = u32::from_le_bytes([bin[7], bin[8], bin[9], bin[10]]) as usize;
    let bytes = &bin[11..];
    if bytes.len() < count * 2 || rate == 0 || chans == 0 {
        return None;
    }
    let samples: Vec<i16> = bytes[..count * 2]
        .as_chunks::<2>()
        .0
        .iter()
        .map(|c| i16::from_le_bytes([c[0], c[1]]))
        .collect();
    Some((rate, chans, count as u32, samples))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caps_roundtrip() {
        let c = Capabilities {
            screen_capture: true,
            clipboard_sync: true,
            notifications: true,
            battery_status: true,
            ..Default::default()
        };
        let txt = c.to_txt();
        assert_eq!(txt, "screen,clip,notif,batt");
        assert_eq!(Capabilities::from_txt(&txt), c);
    }

    #[test]
    fn command_json_roundtrip() {
        let cmd = Command::Copy {
            text: "hello".into(),
        };
        let json = serde_json::to_string(&cmd).unwrap();
        assert!(json.contains(r#""t":"copy""#));
        let back: Command = serde_json::from_str(&json).unwrap();
        assert!(matches!(back, Command::Copy { text } if text == "hello"));
    }

    #[test]
    fn event_json_roundtrip() {
        let ev = Event::Battery {
            pct: 87,
            charging: true,
        };
        let json = serde_json::to_string(&ev).unwrap();
        let back: Event = serde_json::from_str(&json).unwrap();
        assert!(matches!(
            back,
            Event::Battery {
                pct: 87,
                charging: true
            }
        ));
    }

    #[test]
    fn chunk_roundtrip() {
        let data = vec![1u8, 2, 3, 250];
        let frame = encode_chunk("file-9", &data);
        match decode_frame(&frame) {
            DecodedFrame::Chunk { id, data: d } => {
                assert_eq!(id, "file-9");
                assert_eq!(d, data);
            }
            _ => panic!("expected chunk"),
        }
        // text passes through
        assert!(matches!(
            decode_frame(b"{\"t\":\"log\"}"),
            DecodedFrame::Text
        ));
    }

    #[test]
    fn pair_request_json() {
        let req = PairRequest {
            protocol_version: PROTOCOL_VERSION,
            pin: "1234".into(),
            desktop_name: "PC".into(),
        };
        let json = serde_json::to_string(&req).unwrap();
        let back: PairRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(back.pin, "1234");
        assert_eq!(back.protocol_version, 1);
    }

    #[test]
    fn signal_and_input_roundtrip() {
        let sig = Command::Signal {
            payload: serde_json::json!({ "type": "offer", "sdp": "v=0" }),
        };
        let json = serde_json::to_string(&sig).unwrap();
        assert!(json.contains(r#""t":"signal""#));
        let back: Command = serde_json::from_str(&json).unwrap();
        assert!(matches!(back, Command::Signal { .. }));

        let tap = Command::Tap { x: 0.5, y: 0.25 };
        let json = serde_json::to_string(&tap).unwrap();
        let back: Command = serde_json::from_str(&json).unwrap();
        assert!(matches!(back, Command::Tap { x, y } if x == 0.5 && y == 0.25));

        let sw = Command::Swipe {
            x1: 0.1,
            y1: 0.2,
            x2: 0.3,
            y2: 0.4,
        };
        let json = serde_json::to_string(&sw).unwrap();
        let back: Command = serde_json::from_str(&json).unwrap();
        assert!(matches!(back, Command::Swipe { .. }));

        let k = Command::Key {
            key: "Enter".into(),
        };
        let json = serde_json::to_string(&k).unwrap();
        assert!(serde_json::from_str::<Command>(&json).is_ok());

        let t = Command::Text {
            text: "salam".into(),
        };
        let json = serde_json::to_string(&t).unwrap();
        assert!(serde_json::from_str::<Command>(&json).is_ok());
    }
}
