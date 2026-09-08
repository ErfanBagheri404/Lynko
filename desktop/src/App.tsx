import { useCallback, useEffect, useRef, useState } from "react";
import {
  IconAudio, IconBattery, IconClip, IconDevices, IconFiles, IconNotes,
  IconRefresh, IconScreen, IconSend, IconSettings, Mark,
} from "./icons";

/* ------------------------------------------------------------------ */
/* backend bridge                                                       */
/* ------------------------------------------------------------------ */

let invokeFn: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>;
let listenFn: <T>(event: string, handler: (payload: { payload: T }) => void) => Promise<() => void>;
try {
  const core = await import("@tauri-apps/api/core");
  const eventMod = await import("@tauri-apps/api/event");
  invokeFn = core.invoke;
  listenFn = eventMod.listen;
} catch {
  invokeFn = async () => { throw new Error("no backend"); };
  listenFn = async () => () => {};
}

const api = { invoke: invokeFn, on: listenFn };

/* ------------------------------------------------------------------ */
/* types                                                                */
/* ------------------------------------------------------------------ */

interface Device {
  id: string;
  name: string;
  address: string;
  caps: {
    screen_capture: boolean;
    input_injection: boolean;
    text_input: boolean;
    clipboard_sync: boolean;
    file_transfer: boolean;
    notifications: boolean;
    audio_capture: boolean;
    battery_status: boolean;
  };
  paired: boolean;
  online: boolean;
}

interface LinkState {
  connected: boolean;
  device_id?: string;
}

interface BatteryState {
  pct: number;
  charging: boolean;
}

interface ClipItem {
  id: number;
  text: string;
  source: "phone" | "pc";
  at: number;
}

interface FileItem {
  id: number;
  name: string;
  size?: number;
  written?: number;
  at: number;
  status: "queued" | "sending" | "sent" | "failed";
}

interface NoteItem {
  id: number;
  app: string;
  title: string;
  body: string;
  at: number;
}

type View = "devices" | "screen" | "clipboard" | "files" | "notifications" | "audio" | "settings";

/* ------------------------------------------------------------------ */
/* toast system                                                         */
/* ------------------------------------------------------------------ */

interface Toast {
  id: number;
  msg: string;
  kind: "info" | "ok" | "err";
}

let toastUid = 0;

function useToasts() {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const push = useCallback((msg: string, kind: Toast["kind"] = "info") => {
    const id = ++toastUid;
    setToasts((t) => [...t.slice(-4), { id, msg, kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4000);
  }, []);

  return { toasts, push };
}

/* ------------------------------------------------------------------ */
/* App                                                                  */
/* ------------------------------------------------------------------ */

export default function App() {
  const [view, setView] = useState<View>("devices");
  const [devices, setDevices] = useState<Device[]>([]);
  const [link, setLink] = useState<LinkState>({ connected: false });
  const [battery, setBattery] = useState<BatteryState | null>(null);
  const [clipItems, setClipItems] = useState<ClipItem[]>([]);
  const [files, setFiles] = useState<FileItem[]>([]);
  const [notes, setNotes] = useState<NoteItem[]>([]);
  const { toasts, push: toast } = useToasts();

  const connectedDevice = link.connected && link.device_id
    ? devices.find((d) => d.id === link.device_id) ?? null
    : null;

  useEffect(() => {
    const unsubs: (() => void)[] = [];

    api.on<Device[]>("discovery", (e) => setDevices(e.payload)).then((u) => unsubs.push(u));

    api.on<LinkState>("link_state", (e) => {
      setLink(e.payload);
      if (e.payload.connected) {
        toast("Connected", "ok");
      } else {
        setBattery(null);
      }
    }).then((u) => unsubs.push(u));

    api.on<{ t: string; d: Record<string, unknown> }>("link_event", (e) => {
      const ev = e.payload as { t: string; d: Record<string, unknown> };
      if (ev.t === "battery") {
        setBattery({ pct: ev.d.pct as number, charging: ev.d.charging as boolean });
      } else if (ev.t === "clipboard") {
        setClipItems((c) => [{ id: ++toastUid, text: ev.d.text as string, source: "phone" as const, at: Date.now() }, ...c].slice(0, 30));
        toast("Clipboard received from phone", "ok");
      } else if (ev.t === "clipboard_reply") {
        setClipItems((c) => [{ id: ++toastUid, text: ev.d.text as string, source: "phone" as const, at: Date.now() }, ...c].slice(0, 30));
        toast("Clipboard pulled from phone", "ok");
      } else if (ev.t === "notification") {
        setNotes((n) => [{ id: ++toastUid, app: ev.d.app as string, title: ev.d.title as string, body: ev.d.body as string, at: Date.now() }, ...n].slice(0, 50));
      }
    }).then((u) => unsubs.push(u));

    api.on<{ id: string; name: string; written: number; total?: number } | { id: string; name: string; error: string }>("file_progress", (e) => {
      const p = e.payload as Record<string, unknown>;
      if (p.error) {
        setFiles((f) => f.map((x) => x.id === Number(p.id) ? { ...x, status: "failed" } : x));
        toast(`${p.name}: ${p.error}`, "err");
      } else {
        setFiles((f) => f.map((x) => {
          if (x.name === p.name && (x.status === "queued" || x.status === "sending")) {
            return { ...x, written: p.written as number, size: (p.total ?? x.size ?? 0) as number, status: "sent" as const };
          }
          return x;
        }));
      }
    }).then((u) => unsubs.push(u));

    api.on<{ msg: string }>("log", (e) => {
      toast(e.payload.msg, "info");
    }).then((u) => unsubs.push(u));

    return () => unsubs.forEach((u) => u());
  }, [toast]);

  useEffect(() => {
    api.invoke<Device[]>("list_devices").then(setDevices).catch(() => {});
  }, []);

  return (
    <div className={link.connected ? "app live" : "app"}>
      <Shell
        view={view} setView={setView}
        devices={devices}
        link={link} battery={battery}
        clipItems={clipItems} setClipItems={setClipItems}
        files={files} setFiles={setFiles}
        notes={notes}
        connectedDevice={connectedDevice}
        toast={toast}
      />
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.kind}`}>
            {t.kind === "ok" ? "✓" : t.kind === "err" ? "✕" : "●"} {t.msg}
          </div>
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Shell                                                                */
/* ------------------------------------------------------------------ */

interface ShellProps {
  view: View; setView: (v: View) => void;
  devices: Device[];
  link: LinkState; battery: BatteryState | null;
  clipItems: ClipItem[]; setClipItems: React.Dispatch<React.SetStateAction<ClipItem[]>>;
  files: FileItem[]; setFiles: React.Dispatch<React.SetStateAction<FileItem[]>>;
  notes: NoteItem[];
  connectedDevice: Device | null;
  toast: (msg: string, kind?: "info" | "ok" | "err") => void;
}

function Shell(props: ShellProps) {
  const { view, setView, devices, link, battery, connectedDevice, notes } = props;

  return (
    <div className="shell">
      <div className="brand">
        <Mark />
        <span className="wordmark">Lynko</span>
      </div>

      <div className="top">
        <button
          className={link.connected ? "conn-chip live" : "conn-chip"}
          onClick={() => !link.connected && setView("devices")}
        >
          <span className="dot" />
          {link.connected ? (connectedDevice?.name ?? "phone") : "not connected"}
        </button>
        {link.connected && battery && (
          <span className="conn-chip">
            <IconBattery />{battery.pct}%
          </span>
        )}
      </div>

      <nav className="rail">
        <NavItem view={view} setView={setView} id="devices" label="Devices" Icon={IconDevices} />
        <NavItem view={view} setView={setView} id="screen" label="Screen" Icon={IconScreen} />
        <NavItem view={view} setView={setView} id="clipboard" label="Clipboard" Icon={IconClip} />
        <NavItem view={view} setView={setView} id="files" label="Files" Icon={IconFiles} />
        <NavItem view={view} setView={setView} id="notifications" label="Notifications" Icon={IconNotes} badge={notes.length} />
        <NavItem view={view} setView={setView} id="audio" label="Audio" Icon={IconAudio} />
        <div className="rail-spacer" />
        <NavItem view={view} setView={setView} id="settings" label="Settings" Icon={IconSettings} />
      </nav>

      <main className="stage">
        {view === "devices" && <DevicesView {...props} />}
        {view === "screen" && <ScreenView {...props} />}
        {view === "clipboard" && <ClipboardView {...props} />}
        {view === "files" && <FilesView {...props} />}
        {view === "notifications" && <NotificationsView {...props} />}
        {view === "audio" && <AudioView {...props} />}
        {view === "settings" && <SettingsView />}
      </main>

      <footer className="status">
        <span>{link.connected ? <span className="lit">● live</span> : "○ idle"}</span>
        <span>{devices.filter((d) => d.online).length} online</span>
        <span>{devices.filter((d) => d.paired).length} paired</span>
        <span className="sp">v0.1.0</span>
      </footer>
    </div>
  );
}

function NavItem({ view, setView, id, label, Icon, badge }: {
  view: View; setView: (v: View) => void; id: View; label: string; Icon: () => JSX.Element; badge?: number;
}) {
  return (
    <button className={view === id ? "rail-item on" : "rail-item"} onClick={() => setView(id)}>
      <Icon />{label}
      {badge != null && badge > 0 && <span className="rail-badge">{badge}</span>}
    </button>
  );
}

/* ------------------------------------------------------------------ */
/* Devices                                                              */
/* ------------------------------------------------------------------ */

function DevicesView({ devices, link, toast }: ShellProps) {
  const scan = async () => {
    try {
      const found = await api.invoke<Device[]>("list_devices");
      toast(`Found ${found.length} device(s)`, "ok");
    } catch (e) { toast(`Scan failed: ${e}`, "err"); }
  };

  const pair = async (d: Device) => {
    try {
      await api.invoke("pair_device", { deviceId: d.id, pin: "1234", desktopName: "Desktop" });
      toast(`Paired ${d.name}`, "ok");
    } catch (e) { toast(`Pair failed: ${e}`, "err"); }
  };

  const connect = async (d: Device) => {
    try {
      await api.invoke("connect", { deviceId: d.id });
      toast(`Connecting to ${d.name}…`, "info");
    } catch (e) { toast(`Connect failed: ${e}`, "err"); }
  };

  const disconnect = async () => {
    await api.invoke("disconnect");
    toast("Disconnected", "info");
  };

  const forget = async (d: Device) => {
    await api.invoke("forget_device", { deviceId: d.id });
    toast(`Forgot ${d.name}`, "info");
  };

  const paired = devices.filter((d) => d.paired);
  const unpaired = devices.filter((d) => !d.paired);

  return (
    <div className="view">
      <PageHead title="The link room" sub="Every Lynko phone on this network. Pair once, stays remembered." />

      <div className="discover">
        <div className="card radar">
          <h2>Discovery radar</h2>
          <p className="desc">Phones advertise themselves on the network via mDNS.</p>
          <div className="radar-world">
            <div className="ring r1" /><div className="ring r2" /><div className="ring r3" /><div className="radar-core" />
            {devices.map((d, i) => {
              const n = Math.max(devices.length, 1);
              const angle = (i / n) * Math.PI * 2 - Math.PI / 2;
              const r = 32 + ((i * 37) % 3) * 12;
              return (
                <button
                  key={d.id}
                  className="ping"
                  style={{ left: `${50 + Math.cos(angle) * r}%`, top: `${50 + Math.sin(angle) * r * 0.82}%` }}
                  title={d.name}
                  onClick={() => d.paired ? connect(d) : pair(d)}
                >
                  <span className="radar-label">{d.name}</span>
                </button>
              );
            })}
          </div>
          <div className="radar-foot">
            <strong>{devices.length}</strong> phone(s)
            <button className="btn sm ghost" onClick={scan}>Scan</button>
          </div>
        </div>

        <div className="stack">
          <div className="card">
            <h2>Paired phones</h2>
            {paired.length === 0 ? <p className="empty-inline">Nothing paired yet.</p> : paired.map((d) => (
              <div key={d.id} className="devrow">
                <span className={d.online ? "dev-dot on" : "dev-dot off"} />
                <div className="who">
                  <strong>{d.name}</strong>
                  <span className="addr">{d.address}</span>
                </div>
                <div className="act">
                  {d.online && (
                    link.connected && link.device_id === d.id
                      ? <button className="btn sm" onClick={disconnect}>Disconnect</button>
                      : <button className="btn sm primary" onClick={() => connect(d)}>Connect</button>
                  )}
                  <button className="btn sm ghost" onClick={() => forget(d)}>Forget</button>
                </div>
              </div>
            ))}
          </div>

          {unpaired.length > 0 && (
            <div className="card">
              <h2>Found (not paired)</h2>
              {unpaired.map((d) => (
                <div key={d.id} className="devrow">
                  <span className="dev-dot on" />
                  <div className="who"><strong>{d.name}</strong><span className="addr">{d.address}</span></div>
                  <button className="btn sm primary" onClick={() => pair(d)}>Pair</button>
                </div>
              ))}
            </div>
          )}

          <div className="card">
            <h2>First time on a phone?</h2>
            <ol className="steps">
              <li>Install Lynko from GitHub Releases.</li>
              <li>Grant permissions when asked.</li>
              <li>It appears here — scan if it doesn't show.</li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Screen                                                               */
/* ------------------------------------------------------------------ */

function ScreenView(props: ShellProps) {
  const { link, connectedDevice, toast } = props;
  const [streaming, setStreaming] = useState(false);
  const [frame, setFrame] = useState<string | null>(null);
  const [frameCount, setFrameCount] = useState(0);
  const [lastTap, setLastTap] = useState<{ x: number; y: number } | null>(null);
  const [textBuf, setTextBuf] = useState("");
  const [rotated, setRotated] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const dragStart = useRef<{ x: number; y: number } | null>(null);

  // subscribe to JPEG frames from the Rust link layer
  useEffect(() => {
    let un: (() => void) | undefined;
    api.on<{ jpeg: string }>("screen_frame", (e) => {
      setFrame(`data:image/jpeg;base64,${e.payload.jpeg}`);
      setFrameCount((c) => c + 1);
    }).then((u) => { un = u; });
    return () => un?.();
  }, []);

  const start = async () => {
    try {
      await api.invoke("screen_start");
      setStreaming(true);
      toast("Screen stream starting…", "info");
    } catch (e) { toast(`Stream failed: ${e}`, "err"); }
  };

  const stop = async () => {
    try {
      await api.invoke("screen_stop");
      setStreaming(false);
      setFrame(null);
      toast("Stream stopped", "info");
    } catch (e) { toast(`Stop failed: ${e}`, "err"); }
  };

  const norm = (ev: React.PointerEvent) => {
    const r = (ev.currentTarget as HTMLElement).getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (ev.clientY - r.top) / r.height)),
    };
  };

  const onTap = async (ev: React.PointerEvent) => {
    if (!streaming) return;
    // only fire tap if this wasn't the end of a swipe
    if (dragStart.current) {
      const s = dragStart.current;
      dragStart.current = null;
      const p = norm(ev);
      const dx = Math.abs(p.x - s.x), dy = Math.abs(p.y - s.y);
      if (dx > 0.02 || dy > 0.02) {
        try { await api.invoke("inject_swipe", { x1: s.x, y1: s.y, x2: p.x, y2: p.y }); } catch {}
        return;
      }
    }
    const p = norm(ev);
    setLastTap(p);
    try { await api.invoke("inject_tap", { x: p.x, y: p.y }); } catch {}
  };

  const onKey = async (ev: React.KeyboardEvent) => {
    if (!streaming) return;
    if (ev.key.length === 1) {
      try { await api.invoke("inject_text", { text: ev.key }); } catch {}
    } else if (["Enter", "Backspace", "Escape", "Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Home"].includes(ev.key)) {
      ev.preventDefault();
      try { await api.invoke("inject_key", { key: ev.key }); } catch {}
    }
  };

  const toggleRotate = () => setRotated((r) => !r);

  const toggleFullscreen = async () => {
    const el = canvasRef.current;
    if (!el) return;
    try {
      if (!document.fullscreenElement) {
        await el.requestFullscreen();
        setFullscreen(true);
      } else {
        await document.exitFullscreen();
        setFullscreen(false);
      }
    } catch {}
  };

  const sendText = async () => {
    if (!textBuf) return;
    try {
      await api.invoke("inject_text", { text: textBuf });
      setTextBuf("");
      toast("Text sent", "ok");
    } catch (e) { toast(`Send failed: ${e}`, "err"); }
  };

  return (
    <div className="view">
      <PageHead
        title="Live screen"
        sub={link.connected ? `Mirror ${connectedDevice?.name ?? "phone"} in real time` : "Connect a phone to mirror its screen."}
      />
      <div className="screen-frame">
        <div
          className={`screen-canvas interactive${rotated ? " rotated" : ""}`}
          ref={canvasRef}
          tabIndex={link.connected && streaming ? 0 : -1}
          onPointerDown={(e) => { dragStart.current = norm(e); }}
          onPointerUp={onTap}
          onKeyDown={onKey}
          title={streaming ? "Click = tap · drag = swipe · type = text" : undefined}
        >
          {frame ? (
            <img src={frame} alt="phone screen" draggable={false} />
          ) : link.connected ? (
            <div className="no-signal">
              <strong>{streaming ? "Waiting for frames…" : "Stream off"}</strong>
              {streaming ? "Starting capture on the phone." : "Press Start stream."}
            </div>
          ) : (
            <div className="no-signal"><strong>No phone connected</strong>Go to Devices, connect.</div>
          )}
          {lastTap && frame && (
            <span className="tap-ripple" style={{ left: `${lastTap.x * 100}%`, top: `${lastTap.y * 100}%` }} key={`${lastTap.x}-${lastTap.y}-${frameCount}`} />
          )}
        </div>
        <div className="screen-bar">
          <span>{streaming && frameCount > 0 ? `${frameCount} frames · tap/drag/type` : link.connected ? "ready" : "idle"}</span>
        </div>
        <div className="screen-toolbar">
          {!streaming
            ? <button className="btn primary sm" disabled={!link.connected} onClick={start}>Start stream</button>
            : <button className="btn sm" onClick={stop}>Stop</button>}
          <button className="btn ghost sm" disabled={!link.connected} onClick={toggleRotate}>{rotated ? "Upright" : "Rotate"}</button>
          <button className="btn ghost sm" disabled={!link.connected} onClick={toggleFullscreen}>{fullscreen ? "Exit" : "Fullscreen"}</button>
        </div>
        {streaming && (
          <div className="screen-typebar">
            <input
              className="field"
              placeholder="Type on the phone…"
              value={textBuf}
              onChange={(e) => setTextBuf(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") sendText(); }}
            />
            <button className="btn sm" onClick={sendText} disabled={!textBuf}>Send</button>
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Clipboard                                                            */
/* ------------------------------------------------------------------ */

function ClipboardView(props: ShellProps) {
  const { link, clipItems, setClipItems, toast } = props;
  const [draft, setDraft] = useState("");

  const sendToPhone = async () => {
    if (!draft.trim()) return;
    try {
      await api.invoke("send_copy", { text: draft });
      setClipItems((c) => [{ id: ++toastUid, text: draft, source: "pc" as const, at: Date.now() }, ...c].slice(0, 30));
      setDraft("");
      toast("Clipboard sent to phone", "ok");
    } catch (e) { toast(`Send failed: ${e}`, "err"); }
  };

  const pullFromPhone = async () => {
    try {
      await api.invoke("request_paste");
      toast("Pulling clipboard from phone…", "info");
    } catch (e) { toast(`Pull failed: ${e}`, "err"); }
  };

  return (
    <div className="view">
      <PageHead title="Clipboard" sub="Sync your clipboard between PC and phone." />
      <div className="feature-grid">
        <div className="card">
          <h2>Send to phone</h2>
          <p className="desc">Type or paste; it lands on the phone clipboard.</p>
          <textarea className="field" rows={4} placeholder="Paste something…" value={draft} onChange={(e) => setDraft(e.target.value)} />
          <div className="card-actions">
            <button className="btn primary" onClick={sendToPhone} disabled={!link.connected || !draft.trim()}><IconSend /> Send</button>
          </div>
        </div>
        <div className="card">
          <h2>History</h2>
          {clipItems.length === 0 ? <p className="empty-inline">Nothing yet.</p> : (
            <div className="clip-history">
              {clipItems.map((c) => (
                <div className="clip-item" key={c.id} onClick={() => setDraft(c.text)}>
                  {c.source === "pc" ? "PC" : "phone"} {c.text}
                </div>
              ))}
            </div>
          )}
          <div className="card-actions">
            <button className="btn" onClick={pullFromPhone} disabled={!link.connected}><IconRefresh /> Pull from phone</button>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Files                                                                */
/* ------------------------------------------------------------------ */

function FilesView(props: ShellProps) {
  const { link, files, toast } = props;
  const [dragOver, setDragOver] = useState(false);

  const onDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (!link.connected) { toast("Connect a phone first", "err"); return; }
    for (const f of Array.from(e.dataTransfer.files)) {
      const path = (f as any).path as string;
      if (!path) { toast(`${f.name}: path unavailable`, "err"); continue; }
      toast(`Sending ${f.name}…`, "info");
      try {
        await api.invoke("send_file", { path });
      } catch (e) { toast(`Send failed: ${e}`, "err"); }
    }
  };

  return (
    <div className="view">
      <PageHead title="Files" sub="Drop files onto the phone. No cables, no cloud." />
      <div className="card">
        <div
          className={dragOver ? "dropzone over" : "dropzone"}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
        >
          <strong>Drop files to send</strong>
          <span>They travel over the same encrypted link.</span>
        </div>
        {files.length > 0 && (
          <div className="file-list">
            {files.map((f) => (
              <div className="file-row" key={f.id}>
                <IconFiles /><span>{f.name}</span>
                <span className="sz">{f.size ? `${(f.size / 1024).toFixed(0)} KB` : "…"} · {f.status}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Notifications                                                        */
/* ------------------------------------------------------------------ */

function NotificationsView(props: ShellProps) {
  const { notes } = props;
  return (
    <div className="view">
      <PageHead title="Notifications" sub="Phone notifications land here while connected." />
      <div className="card">
        {notes.length === 0 ? <p className="empty-inline">No notifications yet. Connect a phone.</p> :
          notes.map((n) => (
            <div className="note-row" key={n.id}>
              <div className="note-app">{n.app}</div>
              <p className="note-title">{n.title}</p>
              <p className="note-body">{n.body}</p>
            </div>
          ))
        }
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Audio                                                                */
/* ------------------------------------------------------------------ */

function AudioView(props: ShellProps) {
  const { link } = props;
  return (
    <div className="view">
      <PageHead title="Phone audio" sub="Route phone audio through desktop speakers." />
      <div className="card">
        <h2>Output</h2>
        <p className="desc">Media, calls, system sounds play on this PC.</p>
        <div className="screen-bar"><span>{link.connected ? "AudioPlaybackCapture · Opus" : "idle"}</span></div>
        <div className="card-actions">
          <button className="btn ghost sm" disabled={!link.connected}>Mute phone</button>
          <button className="btn ghost sm" disabled={!link.connected}>Play on phone</button>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Settings                                                             */
/* ------------------------------------------------------------------ */

function SettingsView() {
  const [autoReconnect, setAutoReconnect] = useState(true);
  const [toasts, setToasts] = useState(true);
  return (
    <div className="view">
      <PageHead title="Settings" sub="Lynko remembers everything you turn on." />
      <div className="card">
        <SetRow title="Auto-connect paired phones" desc="Connect automatically when a paired phone appears." on={autoReconnect} onToggle={() => setAutoReconnect(!autoReconnect)} />
        <SetRow title="Desktop notifications" desc="Show phone notifications as Windows toasts." on={toasts} onToggle={() => setToasts(!toasts)} />
      </div>
    </div>
  );
}

function SetRow({ title, desc, on, onToggle }: { title: string; desc: string; on: boolean; onToggle: () => void }) {
  return (
    <div className="set-row">
      <div className="what"><strong>{title}</strong><span>{desc}</span></div>
      <button className={on ? "toggle on" : "toggle"} onClick={onToggle} role="switch" aria-checked={on} aria-label={title} />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Bits                                                                 */
/* ------------------------------------------------------------------ */

function PageHead({ title, sub }: { title: string; sub: string }) {
  return <div className="pagehead"><h1>{title}</h1><p className="sub">{sub}</p></div>;
}
