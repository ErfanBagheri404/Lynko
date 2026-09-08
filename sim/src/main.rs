//! Lynko phone simulator — the fake phone.
//!
//! Implements the three planes a real phone would:
//!   1. mDNS advertiser  (_lynko._tcp with capability TXT records)
//!   2. Pairing server   (POST /pair, PIN 1234)
//!   3. Control link     (ws://0.0.0.0:7913/link — commands, events, file chunks)
//!
//! Events it pushes so the desktop UI has something to chew on: battery ticks,
//! notifications every ~25s, clipboard replies on Paste.

use futures_util::{SinkExt, StreamExt};
use lynko_core::{
    decode_frame, encode_chunk, Capabilities, Command, DecodedFrame, Event, PairRequest,
    PairResponse, PAIR_PORT, PROTOCOL_VERSION, SERVICE_TYPE,
};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio_tungstenite::tungstenite::Message;
use tokio::sync::mpsc;

fn sim_caps() -> Capabilities {
    Capabilities {
        screen_capture: true,
        input_injection: true,
        text_input: true,
        clipboard_sync: true,
        file_transfer: true,
        notifications: true,
        audio_capture: true,
        battery_status: true,
    }
}

fn pick_ipv4() -> Option<IpAddr> {
    let mdns = ServiceDaemon::new().ok()?;
    let rx = mdns.browse("_services._dns-sd._udp.local.").ok()?;
    let ip = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(1200);
        while std::time::Instant::now() < deadline {
            if let Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) =
                rx.recv_timeout(deadline - std::time::Instant::now())
            {
                if let Some(ip) = info.get_addresses().iter().find(|i| i.is_ipv4()) {
                    return Some(*ip);
                }
            }
        }
        None
    }))
    .ok()
    .flatten();
    let _ = mdns.stop_browse("_services._dns-sd._udp.local.");
    ip
}

#[tokio::main]
async fn main() {
    println!("lynko-sim: fake phone starting");

    let ip = pick_ipv4().unwrap_or_else(|| IpAddr::from([127, 0, 0, 1]));
    println!("sim: advertising on {ip}");

    // 1) mDNS advertisement
    let mdns = ServiceDaemon::new().expect("mdns daemon");
    let props: HashMap<String, String> = [
        ("pretty_name", "Pixel Sim (Lynko)"),
        ("cap", &sim_caps().to_txt()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    let service = ServiceInfo::new(
        SERVICE_TYPE,
        "pixel-sim",
        &format!("{}.local.", hostname()),
        ip,
        PAIR_PORT,
        Some(props),
    )
    .expect("service info")
    .enable_addr_auto();
    mdns.register(service).expect("register");
    println!("sim: mDNS registered (pair={PAIR_PORT}, link={})", 7913);

    // 2) pairing server (blocking tiny_http on a thread)
    let link_port_holder = Arc::new(AtomicU64::new(7913));
    {
        let link_port = link_port_holder.clone();
        std::thread::spawn(move || run_pair_server(link_port));
    }

    // 3) control link WS server
    run_link_server().await;
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "pixel-sim".into())
}

fn run_pair_server(link_port: Arc<AtomicU64>) {
    let server = tiny_http::Server::http(format!("0.0.0.0:{PAIR_PORT}")).expect("pair server");
    println!("sim: pairing server on :{PAIR_PORT} (PIN 1234)");
    loop {
        match server.recv() {
            Ok(mut req) => {
                let url = req.url().to_string();
                let mut body = String::new();
                if req.as_reader().read_to_string(&mut body).is_ok() && url.starts_with("/pair") {
                    let resp = match serde_json::from_str::<PairRequest>(&body) {
                        Ok(pr) => {
                            if pr.pin == "1234" && pr.protocol_version == PROTOCOL_VERSION {
                                println!("sim: PAIRED by {}", pr.desktop_name);
                                PairResponse {
                                    ok: true,
                                    error: None,
                                    device_name: "Pixel Sim (Lynko)".into(),
                                    capabilities: sim_caps(),
                                    link_port: link_port.load(Ordering::Relaxed) as u16,
                                }
                            } else {
                                println!("sim: pair REJECTED (bad pin or version)");
                                PairResponse {
                                    ok: false,
                                    error: Some("wrong PIN (hint: 1234) or protocol version".into()),
                                    device_name: "Pixel Sim (Lynko)".into(),
                                    capabilities: sim_caps(),
                                    link_port: link_port.load(Ordering::Relaxed) as u16,
                                }
                            }
                        }
                        Err(e) => PairResponse {
                            ok: false,
                            error: Some(format!("bad request: {e}")),
                            device_name: "Pixel Sim (Lynko)".into(),
                            capabilities: sim_caps(),
                            link_port: link_port.load(Ordering::Relaxed) as u16,
                        },
                    };
                    let payload = serde_json::to_vec(&resp).unwrap();
                    let header = tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap();
                    let _ = req.respond(tiny_http::Response::from_data(payload).with_header(header));
                } else {
                    let _ = req.respond(tiny_http::Response::from_string("lynko-sim pair endpoint"));
                }
            }
            Err(e) => {
                eprintln!("sim: pair server error: {e}");
                break;
            }
        }
    }
}

async fn run_link_server() {
    let listener = tokio::net::TcpListener::bind("0.0.0.0:7913").await.expect("link listener");
    println!("sim: control link on ws://…:7913/link");
    loop {
        let (stream, addr) = match listener.accept().await {
            Ok(x) => x,
            Err(e) => {
                eprintln!("sim: accept error: {e}");
                continue;
            }
        };
        println!("sim: desktop connected from {addr}");
        tokio::spawn(handle_link(stream));
    }
}

async fn handle_link(stream: tokio::net::TcpStream) {
    let ws = tokio_tungstenite::accept_async(stream).await;
    let ws = match ws {
        Ok(w) => w,
        Err(e) => {
            eprintln!("sim: ws handshake failed: {e}");
            return;
        }
    };
    let (mut sink, mut stream_rx) = ws.split();

    let (tx, mut rx) = mpsc::unbounded_channel::<Message>();

    // screen stream state (activated by StartScreen)
    let streaming = Arc::new(AtomicU64::new(0)); // 0 = off, 1 = on
    let frame_tx = tx.clone();
    let stream_flag = streaming.clone();
    tokio::spawn(async move {
        let mut frame_n: u64 = 0;
        loop {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            if stream_flag.load(Ordering::Relaxed) != 1 { continue; }
            frame_n += 1;
            let jpeg = render_frame(frame_n);
            let mut msg = Vec::with_capacity(3 + jpeg.len());
            msg.extend_from_slice(b"LV1");
            msg.extend_from_slice(&jpeg);
            if frame_tx.send(Message::binary(msg)).is_err() { break; }
        }
    });

    // battery + notification ticker
    tokio::spawn(async move {
        let mut pct: i8 = 78;
        let mut tick: u64 = 0;
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(6)).await;
            tick += 1;
            pct = (pct - 1).clamp(20, 100);
            let charging = tick % 3 == 0;
            if tx.send(Message::text(
                serde_json::to_string(&Event::Battery { pct: pct as u8, charging }).unwrap(),
            ))
            .is_err()
            {
                break;
            }
            if tick % 4 == 0 {
                let apps = ["Telegram", "WhatsApp", "Gmail", "Instagram"];
                let titles = ["Sara: salam!", "New message", "Invoice #231", "ali liked your photo"];
                let bodies = [
                    "did you see the lynko repo?",
                    "Your package arrives tomorrow 10-14",
                    "Your internet bill is ready",
                    "nice shot 🔥",
                ];
                let i = (tick / 4) as usize % apps.len();
                if tx
                    .send(Message::text(
                        serde_json::to_string(&Event::Notification {
                            app: apps[i].into(),
                            title: titles[i].into(),
                            body: bodies[i].into(),
                        })
                        .unwrap(),
                    ))
                    .is_err()
                {
                    break;
                }
            }
        }
    });

    let mut open_files: HashMap<String, (std::fs::File, u64)> = HashMap::new();
    let clip: Arc<std::sync::Mutex<String>> = Arc::new(std::sync::Mutex::new(String::from("sim initial clipboard")));

    loop {
        tokio::select! {
            out = rx.recv() => {
                match out {
                    Some(m) => { if sink.send(m).await.is_err() { break; } }
                    None => break,
                }
            }
            inc = stream_rx.next() => {
                match inc {
                    Some(Ok(Message::Text(txt))) => {
                        match serde_json::from_str::<Command>(&txt) {
                            Ok(cmd) => {
                                let reply = handle_command(cmd, &clip, &mut open_files, &streaming).await;
                                if let Some(ev) = reply {
                                    if sink.send(Message::text(serde_json::to_string(&ev).unwrap())).await.is_err() { break; }
                                }
                            }
                            Err(e) => println!("sim: bad command: {e} — {txt}"),
                        }
                    }
                    Some(Ok(Message::Binary(bin))) => {
                        if let DecodedFrame::Chunk { id, data } = decode_frame(&bin) {
                            let entry = open_files.entry(id.clone()).or_insert_with(|| {
                                let path = format!("downloads/{id}.bin");
                                println!("sim: file begin {id} -> {path}");
                                (std::fs::File::create(&path).expect("create file"), 0u64)
                            });
                            use std::io::Write;
                            let _ = entry.0.write_all(&data);
                            entry.1 += data.len() as u64;
                        }
                    }
                    Some(Ok(Message::Close(_))) | None => {
                        println!("sim: desktop disconnected");
                        break;
                    }
                    _ => {}
                }
            }
        }
    }
}

async fn handle_command(
    cmd: Command,
    clip: &Arc<std::sync::Mutex<String>>,
    open_files: &mut HashMap<String, (std::fs::File, u64)>,
    streaming: &Arc<AtomicU64>,
) -> Option<Event> {
    match cmd {
        Command::Copy { text } => {
            *clip.lock().unwrap() = text.clone();
            println!("sim: clipboard set ({} chars)", text.len());
            Some(Event::Log { msg: format!("clipboard set ({} chars)", text.len()) })
        }
        Command::Paste => {
            let text = clip.lock().unwrap().clone();
            Some(Event::ClipboardReply { text })
        }
        Command::StatusGet => {
            Some(Event::Battery { pct: 82, charging: false })
        }
        Command::StartScreen => {
            streaming.store(1, Ordering::Relaxed);
            println!("sim: screen start → live JPEG frames (2fps)");
            Some(Event::Log { msg: "screen capture started (sim pattern)".into() })
        }
        Command::StopScreen => {
            streaming.store(0, Ordering::Relaxed);
            Some(Event::Log { msg: "screen stopped".into() })
        }
        Command::StartAudio => Some(Event::Log { msg: "audio capture requested".into() }),
        Command::StopAudio => Some(Event::Log { msg: "audio stopped".into() }),
        Command::FileBegin { id, name, size } => {
            let chunks = size.div_ceil(lynko_core::FILE_CHUNK_SIZE as u64);
            println!("sim: file begin {name} ({size} bytes, ~{chunks} chunks) id={id}");
            Some(Event::Log { msg: format!("receiving {name} ({size} bytes)") })
        }
        Command::Tap { x, y } => {
            println!("sim: TAP at ({x:.3},{y:.3})");
            Some(Event::Log { msg: format!("tap ({x:.2}, {y:.2})") })
        }
        Command::Swipe { x1, y1, x2, y2 } => {
            println!("sim: SWIPE ({x1:.2},{y1:.2})→({x2:.2},{y2:.2})");
            Some(Event::Log { msg: format!("swipe → ({x2:.2}, {y2:.2})") })
        }
        Command::Key { key } => {
            println!("sim: KEY {key}");
            Some(Event::Log { msg: format!("key {key}") })
        }
        Command::Text { text } => {
            println!("sim: TEXT {:?}", text);
            Some(Event::Log { msg: format!("text {:?}", text) })
        }
        Command::Signal { payload } => {
            println!("sim: SIGNAL {:?}", payload);
            None
        }
    }
    // encode_chunk is exercised by the desktop sender; sim just decodes.
    // (referenced so the import stays honest in this file)
    .map(|e| {
        let _ = encode_chunk;
        e
    })
}

/// Generate a 108×240 JPEG test pattern that shifts hue per frame.
fn render_frame(frame: u64) -> Vec<u8> {
    use jpeg_encoder::{ColorType, Encoder};
    let w: u32 = 108;
    let h: u32 = 240;
    let mut pixels = vec![0u8; (w * h * 3) as usize];
    for y in 0..h {
        for x in 0..w {
            let hue = ((x as f64 / w as f64 * 360.0) + (frame as f64 * 15.0)) % 360.0;
            let val = ((y as f64 / h as f64) * 0.7 + 0.3) * 255.0;
            let (r, g, b) = hsv_to_rgb(hue, 0.8, val / 255.0);
            let idx = ((y * w + x) * 3) as usize;
            pixels[idx] = r;
            pixels[idx + 1] = g;
            pixels[idx + 2] = b;
        }
    }
    let mut buf = Vec::new();
    {
        let enc = Encoder::new(&mut buf, 85);
        enc.encode(&pixels, w as u16, h as u16, ColorType::Rgb).unwrap();
    }
    buf
}

fn hsv_to_rgb(h: f64, s: f64, v: f64) -> (u8, u8, u8) {
    let c = v * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = v - c;
    let (r1, g1, b1) = match h as u32 {
        0..=59 => (c, x, 0.0),
        60..=119 => (x, c, 0.0),
        120..=179 => (0.0, c, x),
        180..=239 => (0.0, x, c),
        240..=299 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    (((r1 + m) * 255.0) as u8, ((g1 + m) * 255.0) as u8, ((b1 + m) * 255.0) as u8)
}
