pub fn handle(app: &tauri::AppHandle, tx: &Sender<Message>, gen: u64, t: &str, d: &Value) {
    handle_events(&TauriEvents(app), tx, gen, t, d)
}
pub fn expire(app: &tauri::AppHandle, gen: u64) {
    expire_events(&TauriEvents(app), gen)
}

#[cfg(test)]
mod tests {
    #[test]
    fn protocol_saves_only_after_acceptance_and_verified_end() {
        use base64::Engine;
        let dir = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir(&dir).unwrap();
        let path = dir.join("received.txt");
        let (tx, mut rx) = tokio::sync::mpsc::channel(16);
        let events = NullEvents;
        let data = b"Lynko file share test\n";
        let offer = json!({"id":"test-transfer","name":"tiny.txt","size":data.len()});
        handle_events(&events, &tx, 99, "share_offer", &offer);
        assert!(rx.try_recv().is_err());
        assert!(!path.exists());
        answer_share_with(&events, "test-transfer".into(), None).unwrap();
        let decline: Value =
            serde_json::from_str(rx.try_recv().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(decline["d"]["payload"]["accepted"], false);
        handle_events(&events, &tx, 99, "share_offer", &offer);
        answer_share_with(
            &events,
            "test-transfer".into(),
            Some(path.to_string_lossy().into()),
        )
        .unwrap();
        let accept: Value =
            serde_json::from_str(rx.try_recv().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(accept["d"]["payload"]["accepted"], true);
        handle_events(
            &events,
            &tx,
            99,
            "share_chunk",
            &json!({"id":"test-transfer","offset":0,"data":base64::engine::general_purpose::STANDARD.encode(data)}),
        );
        assert!(!path.exists());
        handle_events(
            &events,
            &tx,
            99,
            "share_end",
            &json!({"id":"test-transfer","size":data.len(),"sha256":format!("{:x}",sha2::Sha256::digest(data))}),
        );
        let done: Value = serde_json::from_str(rx.try_recv().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(done["d"]["payload"]["ok"], true);
        assert_eq!(std::fs::read(&path).unwrap(), data);
        assert!(pending().lock().unwrap().is_none());
        std::fs::remove_dir_all(dir).unwrap();
    }
    use super::*;
    #[test]
    fn validates_bytes_and_never_overwrites() {
        let dir = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir(&dir).unwrap();
        let path = dir.join("test.txt");
        let mut file = Incoming::create("id".into(), 3, path.clone()).unwrap();
        assert!(file.chunk(1, b"abc").is_err());
        file.chunk(0, b"abc").unwrap();
        assert!(file.finish("wrong").is_err());
        drop(file);
        assert!(!path.exists());
        let mut file = Incoming::create("id".into(), 3, path.clone()).unwrap();
        file.chunk(0, b"abc").unwrap();
        file.finish(&format!("{:x}", sha2::Sha256::digest(b"abc")))
            .unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"abc");
        assert!(Incoming::create("id".into(), 3, path.clone()).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
use sha2::Digest;

use serde_json::{json, Value};
use std::{
    fs::{File, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::{Mutex, OnceLock},
    time::Instant,
};
use tauri::Emitter;

/// Observe transfer lifecycle without coupling the core to Tauri.
pub trait ShareEvents {
    fn emit(&self, event: &'static str, payload: serde_json::Value);
}
pub struct TauriEvents<'a>(pub &'a tauri::AppHandle);
impl ShareEvents for TauriEvents<'_> {
    fn emit(&self, event: &'static str, payload: serde_json::Value) {
        let _ = self.0.emit(event, payload);
    }
}
pub struct NullEvents;
impl ShareEvents for NullEvents {
    fn emit(&self, _: &'static str, _: serde_json::Value) {}
}
use base64::Engine;
use tokio::sync::mpsc::Sender;
use tokio_tungstenite::tungstenite::Message;

pub struct Incoming {
    id: String,
    size: u64,
    written: u64,
    path: PathBuf,
    temp: PathBuf,
    file: Option<File>,
    hash: sha2::Sha256,
}
impl Incoming {
    pub fn create(id: String, size: u64, path: PathBuf) -> Result<Self, String> {
        if size > 1_073_741_824 || path.exists() {
            return Err("Choose a new filename; existing files are never overwritten".into());
        }
        let temp = path.with_file_name(format!(".lynko-{}.part", uuid::Uuid::new_v4()));
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)
            .map_err(|e| e.to_string())?;
        Ok(Self {
            id,
            size,
            written: 0,
            path,
            temp,
            file: Some(file),
            hash: sha2::Sha256::new(),
        })
    }
    pub fn chunk(&mut self, offset: u64, data: &[u8]) -> Result<(), String> {
        if offset != self.written
            || data.len() > 49152
            || self.written + data.len() as u64 > self.size
        {
            return Err("Invalid file chunk".into());
        }
        self.file
            .as_mut()
            .ok_or("File closed")?
            .write_all(data)
            .map_err(|e| e.to_string())?;
        self.hash.update(data);
        self.written += data.len() as u64;
        Ok(())
    }
    pub fn finish(&mut self, hash: &str) -> Result<(), String> {
        if self.written != self.size || format!("{:x}", self.hash.clone().finalize()) != hash {
            return Err("File integrity check failed".into());
        }
        self.file
            .take()
            .ok_or("File closed")?
            .sync_all()
            .map_err(|e| e.to_string())?;
        // Atomic no-clobber publication on the same filesystem.
        std::fs::hard_link(&self.temp, &self.path).map_err(|e| e.to_string())?;
        let _ = std::fs::remove_file(&self.temp);
        Ok(())
    }
}
impl Drop for Incoming {
    fn drop(&mut self) {
        self.file.take();
        let _ = std::fs::remove_file(&self.temp);
    }
}
struct Offer {
    gen: u64,
    id: String,
    name: String,
    size: u64,
    tx: Sender<Message>,
    file: Option<Incoming>,
    last: Instant,
}
static PENDING: OnceLock<Mutex<Option<Offer>>> = OnceLock::new();
fn pending() -> &'static Mutex<Option<Offer>> {
    PENDING.get_or_init(|| Mutex::new(None))
}
fn reply(tx: &Sender<Message>, payload: Value) {
    let _ = tx.try_send(Message::Text(
        json!({"t":"signal","d":{"payload":payload}})
            .to_string()
            .into(),
    ));
}
pub fn clear(gen: u64) {
    let mut p = pending().lock().unwrap();
    if p.as_ref().is_some_and(|o| o.gen == gen) {
        *p = None;
    }
}
pub fn expire_events(events: &dyn ShareEvents, gen: u64) {
    let mut p = pending().lock().unwrap();
    if p.as_ref()
        .is_some_and(|o| o.gen == gen && o.last.elapsed().as_secs() > 120)
    {
        if let Some(o) = p.take() {
            reply(
                &o.tx,
                json!({"kind":"share_result","id":o.id,"ok":false,"error":"Transfer timed out"}),
            );
            events.emit(
                "share_status",
                json!({"status":"failed","error":"Transfer timed out"}),
            );
        }
    }
}
#[tauri::command]
pub fn answer_share(app: tauri::AppHandle, id: String, path: Option<String>) -> Result<(), String> {
    answer_share_with(&TauriEvents(&app), id, path)
}
pub fn answer_share_with(
    events: &dyn ShareEvents,
    id: String,
    path: Option<String>,
) -> Result<(), String> {
    let mut p = pending().lock().unwrap();
    let o = p.as_mut().ok_or("Offer expired")?;
    if o.id != id {
        return Err("Offer changed".into());
    }
    if o.file.is_some() {
        return Err("Already accepted".into());
    }
    if let Some(path) = path {
        o.file = Some(Incoming::create(id.clone(), o.size, PathBuf::from(path))?);
        o.last = Instant::now();
        reply(
            &o.tx,
            json!({"kind":"share_answer","id":id,"accepted":true}),
        );
        events.emit("share_status", json!({"status":"receiving","name":o.name}));
    } else {
        reply(
            &o.tx,
            json!({"kind":"share_answer","id":id,"accepted":false}),
        );
        *p = None;
    }
    Ok(())
}
pub fn handle_events(events: &dyn ShareEvents, tx: &Sender<Message>, gen: u64, t: &str, d: &Value) {
    let id = d.get("id").and_then(Value::as_str).unwrap_or("");
    if id.len() > 64 || id.is_empty() {
        return;
    }
    let mut p = pending().lock().unwrap();
    if t == "share_offer" {
        if p.is_some() {
            reply(tx, json!({"kind":"share_answer","id":id,"accepted":false}));
            return;
        }
        let Some(size) = d
            .get("size")
            .and_then(Value::as_u64)
            .filter(|n| *n <= 1_073_741_824)
        else {
            return;
        };
        let name = d.get("name").and_then(Value::as_str).unwrap_or("file");
        // Display-only filename: sender never chooses a destination path.
        let name: String = name
            .chars()
            .filter(|c| !c.is_control() && *c != '/' && *c != '\\')
            .take(240)
            .collect();
        *p = Some(Offer {
            gen,
            id: id.into(),
            name: name.clone(),
            size,
            tx: tx.clone(),
            file: None,
            last: Instant::now(),
        });
        events.emit("share_offer", json!({"id":id,"name":name,"size":size}));
        return;
    }
    let Some(o) = p.as_mut().filter(|o| o.gen == gen && o.id == id) else {
        return;
    };
    o.last = Instant::now();
    if t == "share_cancel" {
        *p = None;
        events.emit("share_status", json!({"status":"cancelled"}));
        return;
    }
    let result = (|| -> Result<(), String> {
        let f = o.file.as_mut().ok_or("Transfer not accepted")?;
        match t {
            "share_chunk" => {
                let encoded = d
                    .get("data")
                    .and_then(Value::as_str)
                    .ok_or("Missing data")?;
                if encoded.len() > 65536 {
                    return Err("Chunk too large".into());
                }
                let data = base64::engine::general_purpose::STANDARD
                    .decode(encoded)
                    .map_err(|e| e.to_string())?;
                f.chunk(
                    d.get("offset")
                        .and_then(Value::as_u64)
                        .ok_or("Missing offset")?,
                    &data,
                )?;
                events.emit(
                    "share_status",
                    json!({"status":"receiving","name":o.name,"written":f.written,"size":f.size}),
                );
            }
            "share_end" => {
                if d.get("size").and_then(Value::as_u64) != Some(f.size) {
                    return Err("Size mismatch".into());
                }
                f.finish(d.get("sha256").and_then(Value::as_str).unwrap_or(""))?;
                reply(tx, json!({"kind":"share_result","id":id,"ok":true}));
                events.emit(
                    "share_status",
                    json!({"status":"saved","name":o.name,"path":f.path}),
                );
            }
            _ => return Err("Unknown transfer message".into()),
        };
        Ok(())
    })();
    if let Err(e) = result {
        reply(
            tx,
            json!({"kind":"share_result","id":id,"ok":false,"error":e}),
        );
        events.emit("share_status", json!({"status":"failed","error":e}));
        *p = None;
    } else if t == "share_end" {
        *p = None;
    }
}
