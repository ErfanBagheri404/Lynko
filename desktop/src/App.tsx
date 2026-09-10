import { useCallback, useEffect, useRef, useState } from "react";
import { Lang, LANGS, t as tr } from "./locales";
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

/* Native file-drop hook. In Tauri 2 (WebView2) the DOM never sees real
   file drops — only the window-level drag-drop event carries OS paths.
   App() installs the handler; until then drops are parked. */
let dropHandler: ((paths: string[]) => void) | null = null;
async function initNativeDrop() {
  try {
    const apiWin = await import("@tauri-apps/api/window");
    const win = apiWin.getCurrentWindow();
    await win.onDragDropEvent((ev) => {
      if (ev.payload.type === "drop" && dropHandler) dropHandler(ev.payload.paths);
    });
  } catch { /* browser dev mode — DOM drop fallback stays */ }
}
void initNativeDrop();

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
  const [lang, setLang] = useState<Lang>(() => {
    const saved = localStorage.getItem("lynko-lang");
    return saved === "fa" || saved === "en" ? saved : (navigator.language || "").startsWith("fa") ? "fa" : "en";
  });
  useEffect(() => { localStorage.setItem("lynko-lang", lang); document.documentElement.lang = lang; }, [lang]);
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
      } else if (ev.t === "input_error") {
        const kind = ev.d.kind as string;
        if (kind === "accessibility") toast("Taps & swipes need Accessibility: open Settings → Accessibility → Lynko → enable", "err");
        else if (kind === "field") toast("No focused text field on the phone — tap one in the mirror first", "err");
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

    /* native OS file drops (real paths) feed the Files queue too */
    dropHandler = (paths) => {
      for (const p of paths) queueFile(p);
    };

    return () => { unsubs.forEach((u) => u()); dropHandler = null; };
  }, [toast]);

  useEffect(() => {
    api.invoke<Device[]>("list_devices").then(setDevices).catch(() => {});
  }, []);

  /** One shared send path: DOM dropzone and native window drop both land here. */
  const queueFile = useCallback(async (path: string) => {
    const name = path.split(/[\\/]/).pop() ?? path;
    if (!link.connected) {
      toast(`Connect a phone before sending ${name}`, "err");
      return;
    }
    setFiles((f) => [
      { id: ++toastUid, name, at: Date.now(), status: "sending" as const },
      ...f,
    ]);
    try {
      await api.invoke("send_file", { path });
      toast(`Sending ${name}…`, "info");
    } catch (e) {
      setFiles((f) => f.map((x) => x.name === name && x.status === "sending" ? { ...x, status: "failed" as const } : x));
      toast(`Send failed: ${name}: ${e}`, "err");
    }
  }, [link.connected, toast]);

  return (
    <div className={link.connected ? "app live" : "app"}>
      <Shell
        view={view} setView={setView}
        lang={lang} setLang={setLang}
        devices={devices}
        link={link} battery={battery}
        clipItems={clipItems} setClipItems={setClipItems}
        files={files} setFiles={setFiles}
        notes={notes}
        connectedDevice={connectedDevice}
        toast={toast}
        queueFile={queueFile}
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
  lang: Lang; setLang: (l: Lang) => void;
  devices: Device[];
  link: LinkState; battery: BatteryState | null;
  clipItems: ClipItem[]; setClipItems: React.Dispatch<React.SetStateAction<ClipItem[]>>;
  files: FileItem[]; setFiles: React.Dispatch<React.SetStateAction<FileItem[]>>;
  notes: NoteItem[];
  connectedDevice: Device | null;
  toast: (msg: string, kind?: "info" | "ok" | "err") => void;
  queueFile: (path: string) => void;
}

function Shell(props: ShellProps) {
  const { view, setView, lang, devices, link, battery, connectedDevice, notes } = props;
  const T = (k: string) => tr(lang, "desktop", k);

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
          {link.connected ? (connectedDevice?.name ?? "phone") : T("not_connected")}
        </button>
        {link.connected && battery && (
          <span className="conn-chip">
            <IconBattery />{battery.pct}%
          </span>
        )}
      </div>

      <nav className="rail">
        <NavItem view={view} setView={setView} id="devices" label={T("nav_devices")} Icon={IconDevices} />
        <NavItem view={view} setView={setView} id="screen" label={T("nav_screen")} Icon={IconScreen} />
        <NavItem view={view} setView={setView} id="clipboard" label={T("nav_clipboard")} Icon={IconClip} />
        <NavItem view={view} setView={setView} id="files" label={T("nav_files")} Icon={IconFiles} />
        <NavItem view={view} setView={setView} id="notifications" label={T("nav_notifications")} Icon={IconNotes} badge={notes.length} />
        <NavItem view={view} setView={setView} id="audio" label={T("nav_audio")} Icon={IconAudio} />
        <div className="rail-spacer" />
        <NavItem view={view} setView={setView} id="settings" label={T("nav_settings")} Icon={IconSettings} />
      </nav>

      <main className="stage">
        {view === "devices" && <DevicesView {...props} />}
        {view === "screen" && <ScreenView {...props} />}
        {view === "clipboard" && <ClipboardView {...props} />}
        {view === "files" && <FilesView {...props} />}
        {view === "notifications" && <NotificationsView {...props} />}
        {view === "audio" && <AudioView {...props} />}
        {view === "settings" && <SettingsView {...props} />}
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

function DevicesView({ devices, link, toast, lang }: ShellProps) {
  const T = (k: string) => tr(lang, "desktop", k);
  const scan = async () => {
    try {
      const found = await api.invoke<Device[]>("list_devices");
      toast(tr(lang, "toasts", "found_devices").replace("{n}", String(found.length)), "ok");
    } catch (e) { toast(tr(lang, "toasts", "scan_failed").replace("{e}", String(e)), "err"); }
  };

  const pair = async (d: Device) => {
    try {
      await api.invoke("pair_device", { deviceId: d.id, pin: "1234", desktopName: "Desktop" });
      toast(tr(lang, "toasts", "paired").replace("{name}", d.name), "ok");
    } catch (e) { toast(tr(lang, "toasts", "pair_failed").replace("{e}", String(e)), "err"); }
  };

  const connect = async (d: Device) => {
    try {
      await api.invoke("connect", { deviceId: d.id });
      toast(tr(lang, "toasts", "connecting").replace("{name}", d.name), "info");
    } catch (e) { toast(tr(lang, "toasts", "connect_failed").replace("{e}", String(e)), "err"); }
  };

  const disconnect = async () => {
    await api.invoke("disconnect");
    toast(tr(lang, "toasts", "disconnected"), "info");
  };

  const forget = async (d: Device) => {
    await api.invoke("forget_device", { deviceId: d.id });
    toast(tr(lang, "toasts", "forgot").replace("{name}", d.name), "info");
  };

  const paired = devices.filter((d) => d.paired);
  const unpaired = devices.filter((d) => !d.paired);

  return (
    <div className="view">
      <PageHead title={T("link_room")} sub={T("link_room_sub")} />

      <div className="discover">
        <div className="card radar">
          <h2>{T("radar")}</h2>
          <p className="desc">{T("radar_desc")}</p>
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
            <button className="btn sm ghost" onClick={scan}>{T("scan")}</button>
          </div>
        </div>

        <div className="stack">
          <div className="card">
            <h2>{T("paired_phones")}</h2>
            {paired.length === 0 ? <p className="empty-inline">{T("nothing_paired")}</p> : paired.map((d) => (
              <div key={d.id} className="devrow">
                <span className={d.online ? "dev-dot on" : "dev-dot off"} />
                <div className="who">
                  <strong>{d.name}</strong>
                  <span className="addr">{d.address}</span>
                </div>
                <div className="act">
                  {d.online && (
                    link.connected && link.device_id === d.id
                      ? <button className="btn sm" onClick={disconnect}>{T("disconnect")}</button>
                      : <button className="btn sm primary" onClick={() => connect(d)}>{T("connect")}</button>
                  )}
                  <button className="btn sm ghost" onClick={() => forget(d)}>{T("forget")}</button>
                </div>
              </div>
            ))}
          </div>

          {unpaired.length > 0 && (
            <div className="card">
              <h2>{T("found")}</h2>
              {unpaired.map((d) => (
                <div key={d.id} className="devrow">
                  <span className="dev-dot on" />
                  <div className="who"><strong>{d.name}</strong><span className="addr">{d.address}</span></div>
                  <button className="btn sm primary" onClick={() => pair(d)}>{T("pair")}</button>
                </div>
              ))}
            </div>
          )}

          <div className="card">
            <h2>{T("first_time")}</h2>
            <ol className="steps">
              <li>{T("step1")}</li>
              <li>{T("step2")}</li>
              <li>{T("step3")}</li>
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
  const { link, connectedDevice, toast, lang } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const [streaming, setStreaming] = useState(false);
  const [frame, setFrame] = useState<string | null>(null);
  // Frame counter kept in a ref — not state: 15fps setState re-renders the
  // whole tree per frame. Read into the status bar via a 1s interval tick.
  const frameCountRef = useRef(0);
  // Fire-once ripples: each tap pushes {x,y,id}; a timer removes it after the
  // CSS animation (0.45s). Never keyed by frameCount — that remounted the span
  // on every frame and restarted the animation forever ("ripple spam").
  const [ripples, setRipples] = useState<{ x: number; y: number; id: number }[]>([]);
  const [rotated, setRotated] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const dragStart = useRef<{ x: number; y: number } | null>(null);
  const dragging = useRef(false);
  const lastMove = useRef({ x: 0, y: 0 });

  const sendMove = async (p: { x: number; y: number }) => {
    try { await api.invoke("inject_drag_move", { x: p.x, y: p.y }); } catch {}
  };

  const onDown = async (ev: React.PointerEvent) => {
    const p = norm(ev);
    dragStart.current = p;
    dragging.current = true;
    lastMove.current = p;
    try { ev.currentTarget.setPointerCapture(ev.pointerId); } catch {}
    // Start the phone-side stroke immediately: finger goes down while the
    // desktop pointer is still moving, so the drag follows LIVE.
    try { await api.invoke("inject_drag_start", { x: p.x, y: p.y }); } catch {}
  };

  const onMove = (ev: React.PointerEvent) => {
    if (!dragging.current || !streaming) return;
    const p = norm(ev);
    const dx = Math.abs(p.x - lastMove.current.x), dy = Math.abs(p.y - lastMove.current.y);
    if (dx < 0.008 && dy < 0.008) return; // throttle: segments only on real motion
    lastMove.current = p;
    void sendMove(p);
  };

  // subscribe to JPEG frames from the Rust link layer.
  // Direct <img>.src writes — NOT React state: setState at 15fps re-renders
  // the whole component tree per frame; a direct assignment paints the
  // moment the frame arrives and skips reconciliation entirely.
  const imgRef = useRef<HTMLImageElement | null>(null);
  const frameArrived = useRef(false);
  const [fps, setFps] = useState(0);
  useEffect(() => {
    let un: (() => void) | undefined;
    api.on<{ jpeg: string }>("screen_frame", (e) => {
      frameCountRef.current++;
      const el = imgRef.current;
      if (el) {
        el.src = `data:image/jpeg;base64,${e.payload.jpeg}`;
      } else if (!frameArrived.current) {
        // first frame: mount the <img> via state (one re-render, ever)
        frameArrived.current = true;
        setFrame(`data:image/jpeg;base64,${e.payload.jpeg}`);
      }
    }).then((u) => { un = u; });
    return () => un?.();
  }, []);
  // 1s status tick: reads the ref into state at 1fps (vs 15fps re-renders before)
  useEffect(() => {
    const t = setInterval(() => setFps(frameCountRef.current), 1000);
    return () => clearInterval(t);
  }, []);

  const start = async () => {
    try {
      await api.invoke("screen_start");
      setStreaming(true);
      toast(tr(lang, "desktop", "screen_starting"), "info");
    } catch (e) { toast(`Stream failed: ${e}`, "err"); }
  };

  const stop = async () => {
    try {
      await api.invoke("screen_stop");
      setStreaming(false);
      frameArrived.current = false;
      setFrame(null);
      toast(tr(lang, "desktop", "stream_stopped"), "info");
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
    // If a live drag is in flight, finish it (lift the phone finger) instead
    // of treating the release as a tap.
    if (dragging.current) {
      dragging.current = false;
      const p = norm(ev);
      try { await api.invoke("inject_drag_end", { x: p.x, y: p.y }); } catch {}
      dragStart.current = null;
      return;
    }
    const p = norm(ev);
    const id = Date.now() + Math.random();
    setRipples((rs) => [...rs, { x: p.x, y: p.y, id }]);
    setTimeout(() => setRipples((rs) => rs.filter((r) => r.id !== id)), 500);
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

  return (
    <div className="view">
      <PageHead
        title={T("live_screen")}
        sub={link.connected ? `${connectedDevice?.name ?? "phone"} — live` : T("mirror_off")}
      />
      <div className="screen-frame">
        <div
          className={`screen-canvas interactive${rotated ? " rotated" : ""}`}
          ref={canvasRef}
          tabIndex={link.connected && streaming ? 0 : -1}
          onPointerDown={onDown}
          onPointerMove={onMove}
          onPointerUp={onTap}
          onPointerCancel={() => {
            if (dragging.current) {
              dragging.current = false;
              dragStart.current = null;
              void api.invoke("inject_drag_end", { x: lastMove.current.x, y: lastMove.current.y }).catch(() => {});
            }
          }}
          onKeyDown={(e) => { e.currentTarget.focus(); onKey(e); }}
          title={streaming ? T("click_tap_hint") : undefined}
        >
          {frame || frameArrived.current ? (
            <img ref={imgRef} src={frame ?? undefined} alt="phone screen" draggable={false} />
          ) : link.connected ? (
            <div className="no-signal">
              <strong>{streaming ? T("waiting_frames") : T("stream_off")}</strong>
              {streaming ? T("starting_capture") : T("press_start")}
            </div>
          ) : (
            <div className="no-signal"><strong>No phone connected</strong>Go to Devices, connect.</div>
          )}
          {ripples.map((r) => (
            <span key={r.id} className="tap-ripple" style={{ left: `${r.x * 100}%`, top: `${r.y * 100}%` }} />
          ))}
        </div>
        <div className="screen-bar">
          <span>{streaming && fps > 0 ? `${fps} frames · tap/drag/type` : link.connected ? "ready" : "idle"}</span>
        </div>
        <div className="screen-toolbar">
          {!streaming
            ? <button className="btn primary sm" disabled={!link.connected} onClick={start}>Start stream</button>
            : <button className="btn sm" onClick={stop}>Stop</button>}
          <button className="btn ghost sm" disabled={!link.connected} onClick={toggleRotate}>{rotated ? T("upright") : T("rotate")}</button>
          <button className="btn ghost sm" disabled={!link.connected} onClick={toggleFullscreen}>{fullscreen ? T("exit") : T("fullscreen")}</button>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Clipboard                                                            */
/* ------------------------------------------------------------------ */

function ClipboardView(props: ShellProps) {
  const { lang } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const { link, clipItems, setClipItems, toast } = props;
  const [draft, setDraft] = useState("");

  const sendToPhone = async () => {
    if (!draft.trim()) return;
    try {
      await api.invoke("send_copy", { text: draft });
      setClipItems((c) => [{ id: ++toastUid, text: draft, source: "pc" as const, at: Date.now() }, ...c].slice(0, 30));
      setDraft("");
      toast(tr(lang, "desktop", "clipboard_sent"), "ok");
    } catch (e) { toast(`Send failed: ${e}`, "err"); }
  };

  const pullFromPhone = async () => {
    try {
      await api.invoke("request_paste");
      toast(tr(lang, "desktop", "pulling_clipboard"), "info");
    } catch (e) { toast(`Pull failed: ${e}`, "err"); }
  };

  return (
    <div className="view">
      <PageHead title={T("clipboard_title")} sub={T("clipboard_sub")} />
      <div className="feature-grid">
        <div className="card">
          <h2>Send to phone</h2>
          <p className="desc">Type or paste; it lands on the phone clipboard.</p>
          <textarea className="field" rows={4} placeholder={T("paste_placeholder")} value={draft} onChange={(e) => setDraft(e.target.value)} />
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
  const { lang } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const { link, files, toast, queueFile } = props;
  const [dragOver, setDragOver] = useState(false);

  /* DOM dropzone: Tauri's WebView never delivers real paths here, so the
     native window-level drop (initNativeDrop → queueFile) is the real path.
     The dropzone only collects Browser paths when running in dev mode. */
  const onDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const list = Array.from(e.dataTransfer.files);
    if (!list.length && !link.connected) { toast(tr(lang, "desktop", "send_before_connect"), "err"); return; }
    for (const f of list) {
      const path = (f as unknown as { path?: string }).path;
      if (!path) { toast(`${f.name}: drop files onto the app window (Tauri handles it)`, "err"); continue; }
      queueFile(path);
    }
  };

  const browse = async () => {
    try {
      const dlg = await import("@tauri-apps/plugin-dialog");
      const picked = await dlg.open({ multiple: true, title: T("send_to_phone") });
      if (!picked) return;
      const paths = Array.isArray(picked) ? picked : [picked];
      for (const p of paths) queueFile(String(p));
    } catch (e) { toast(`Browse failed: ${e}`, "err"); }
  };

  return (
    <div className="view">
      <PageHead title={T("files_title")} sub={T("files_sub")} />
      <div className="card">
        <div
          className={dragOver ? "dropzone over" : "dropzone"}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          onClick={browse}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => { if (e.key === "Enter") browse(); }}
        >
          <strong>Drop files to send</strong>
          <span>…or click to browse. They travel over the same encrypted link.</span>
        </div>
        {files.length > 0 && (
          <div className="file-list">
            {files.map((f) => {
              const pct = f.size && f.written ? Math.min(100, Math.round((f.written / f.size) * 100)) : null;
              return (
                <div className="file-row" key={f.id}>
                  <IconFiles /><span>{f.name}</span>
                  <span className="sz">
                    {f.size ? `${(f.size / 1024).toFixed(0)} KB` : "…"} · {f.status}
                    {pct != null && f.status === "sent" && f.written! < (f.size ?? 0) ? ` · ${pct}%` : ""}
                  </span>
                  {pct != null && (
                    <span className="fbar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
                  )}
                </div>
              );
            })}
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
  const { lang } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const { notes } = props;
  return (
    <div className="view">
      <PageHead title={T("notif_title")} sub={T("notif_sub")} />
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
  const { lang } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const { link, toast } = props;
  const [playing, setPlaying] = useState(false);
  const [chunks, setChunks] = useState(0);
  const ctxRef = useRef<AudioContext | null>(null);
  const queueRef = useRef<{ rate: number; chans: number; pcm: Int16Array }[]>([]);
  const playHeadRef = useRef(0);

  useEffect(() => {
    let un: (() => void) | undefined;
    api.on<{ rate: number; chans: number; count: number; pcm: number[] }>("audio_chunk", (e) => {
      const { rate, chans, pcm } = e.payload;
      const arr = Int16Array.from(pcm);
      if (!arr.length) return;
      queueRef.current.push({ rate, chans, pcm: arr });
      setChunks((c) => c + 1);
    }).then((u) => { un = u; });
    return () => un?.();
  }, []);

  // schedule queued PCM as it arrives while playing
  useEffect(() => {
    if (!playing) return;
    const tick = setInterval(() => {
      const ctx = ctxRef.current;
      if (!ctx) return;
      while (queueRef.current.length) {
        const item = queueRef.current.shift()!;
        const when = Math.max(ctx.currentTime + 0.02, playHeadRef.current);
        const buf = ctx.createBuffer(1, item.pcm.length, item.rate);
        const data = buf.getChannelData(0);
        for (let i = 0; i < item.pcm.length; i++) data[i] = item.pcm[i] / 32768;
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(ctx.destination);
        src.start(when);
        playHeadRef.current = when + item.pcm.length / item.rate;
      }
    }, 100);
    return () => clearInterval(tick);
  }, [playing]);

  const start = async () => {
    try {
      if (!ctxRef.current) ctxRef.current = new AudioContext();
      await ctxRef.current.resume();
      playHeadRef.current = 0;
      await api.invoke("audio_start");
      setPlaying(true);
      toast(tr(lang, "desktop", "audio_started"), "ok");
    } catch (e) { toast(`Audio failed: ${e}`, "err"); }
  };

  const stop = async () => {
    try {
      await api.invoke("audio_stop");
      setPlaying(false);
      queueRef.current = [];
      playHeadRef.current = 0;
      toast(tr(lang, "desktop", "audio_stopped"), "info");
    } catch (e) { toast(`Stop failed: ${e}`, "err"); }
  };

  return (
    <div className="view">
      <PageHead title={T("audio_title")} sub={T("audio_sub")} />
      <div className="card">
        <h2>Output</h2>
        <p className="desc">Media, calls, system sounds play on this PC.</p>
        <div className="screen-bar">
          <span>{playing ? `${chunks} chunks · 16 kHz mono PCM` : link.connected ? "ready" : "idle"}</span>
        </div>
        <div className="card-actions">
          {!playing
            ? <button className="btn primary sm" disabled={!link.connected} onClick={start}>Start audio</button>
            : <button className="btn sm" onClick={stop}>Stop</button>}
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Settings                                                             */
/* ------------------------------------------------------------------ */

function SettingsView(props: ShellProps) {
  const { lang, setLang } = props;
  const [autoReconnect, setAutoReconnect] = useState(true);
  const [toasts, setToasts] = useState(true);
  const T = (k: string) => tr(lang, "settings", k);
  return (
    <div className="view">
      <PageHead title={T("settings_title")} sub={T("settings_sub")} />
      <div className="card">
        <div className="set-row">
          <div className="what"><strong>{T("language")}</strong></div>
          <div className="lang-switch">
            {LANGS.map((l) => (
              <button
                key={l.id}
                className={lang === l.id ? "lang-btn on" : "lang-btn"}
                onClick={() => setLang(l.id)}
                title={l.label}
              >
                {l.autonym}
              </button>
            ))}
          </div>
        </div>
      </div>
      <div className="card">
        <SetRow title={T("autoconnect")} desc={T("autoconnect_desc")} on={autoReconnect} onToggle={() => setAutoReconnect(!autoReconnect)} />
        <SetRow title={T("desktop_notifs")} desc={T("desktop_notifs_desc")} on={toasts} onToggle={() => setToasts(!toasts)} />
      </div>
      <div className="card about-card">
        <div className="set-row">
          <div className="what"><strong>{T("about")}</strong><span>{T("inspired_by")}</span></div>
        </div>
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
