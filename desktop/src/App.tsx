import { useCallback, useEffect, useRef, useState } from "react";
import { Lang, LANGS, t as tr } from "./locales";
import {
  IconAudio, IconBack, IconBattery, IconClip, IconDevices, IconExpand, IconFiles,
  IconHome, IconNotes, IconPlay, IconRecents, IconRotate, IconScreen,
  IconSettings, IconStop, Mark,
} from "./icons";
import QRCode from "qrcode";
import {IncomingShare} from "./enhancements/IncomingShare";
import {usePreferences, readPreferences, useTransfers, useHotkeys, type Preferences, type TransferItem} from './enhancements/hooks';
import {mapKey} from './enhancements/keyboard.mjs';
import {filterNotification} from './enhancements/logic.mjs';
import {HealthPanel, FeatureControls, PrivacyControls} from './enhancements/Panels';
import {text as extra} from './enhancements/strings';
import './enhancements/styles.css';

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

const api = { invoke: <T,>(cmd:string, args?:Record<string,unknown>):Promise<T> => {
  const p = readPreferences();
  const blocked = (cmd.startsWith('inject_') && !p.input) ||
    ((cmd.includes('clipboard') || cmd === 'paste_to_phone') && !p.clipboard) ||
    (cmd === 'screen_start' && !p.screen) || (cmd === 'audio_start' && !p.audio) ||
    (cmd.startsWith('send_file') && !p.files) || (cmd === 'notif_reply' && !p.notifications);
  if (blocked) return Promise.reject(new Error('Disabled by desktop feature controls'));
  return invokeFn<T>(cmd,args);
}, on: listenFn };

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
  /** true when this row is the live link (desktop ↔ phone right now) */
  linked?: boolean;
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
  mirror?: boolean;
  locked?: boolean;
  /** phone-side: a11y bound — false means taps/swipes are dead */
  control?: boolean;
  /** phone-side: USB cable connected */
  usb?: boolean;
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


interface NoteItem {
  id: number;
  app: string;
  title: string;
  body: string;
  at: number;
  notifId?: number;
}

type View = "devices" | "screen" | "files" | "notifications" | "health" | "settings";

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
  const {prefs, updatePrefs} = usePreferences();
  const prefsLangRef = useRef(lang);
  prefsLangRef.current = lang;
  // Global hotkeys live in Rust (OS-level, so they work backgrounded). The
  // pref flip is the only trigger; a rejection means another app already owns
  // the combo — roll the toggle back and say so rather than showing "on"
  // for a binding that does not exist.
  const onHotkeyConflict = useCallback((reason: string) => {
    toast(`${extra(prefsLangRef.current, "hotkeyConflict")} (${reason})`, "err");
    updatePrefs({ hotkeys: false });
  }, [updatePrefs]);
  useHotkeys(api, prefs.hotkeys, onHotkeyConflict);
  const {files, queueFile: enqueueFile, cancelFile, retryFile} = useTransfers(api,link.connected,link.device_id,prefs.files);
  const [lastFrame, setLastFrame] = useState<number|null>(null);
  const lastFrameRef = useRef<number|null>(null);
  const [now,setNow] = useState(Date.now());
  useEffect(() => { lastFrameRef.current = null; setLastFrame(null); }, [link.connected,link.device_id]);
  const [notes, setNotes] = useState<NoteItem[]>([]);
  const { toasts, push: toast } = useToasts();
  // Mirror FPS, lifted from ScreenView for the footer: updated 1/sec, so the
  // whole-tree re-render cost is negligible (frames bypass React anyway).
  const [screenFps, setScreenFps] = useState(0);
  // Counted here, not in ScreenView: the footer must show live fps even
  // while the user browses Files/Notifications. Delta per tick — the old
  // in-rail counter showed the CUMULATIVE frame count ("4193fps") because
  // it never subtracted the previous second.
  const frameTickRef = useRef(0);
  const framePrevRef = useRef(0);
  useEffect(() => {
    let un: (() => void) | undefined;
    api.on("screen_frame", () => { frameTickRef.current++; lastFrameRef.current = Date.now(); }).then((u) => { un = u; });
    const t = setInterval(() => {
      setNow(Date.now()); setLastFrame(lastFrameRef.current);
      setScreenFps(frameTickRef.current - framePrevRef.current);
      framePrevRef.current = frameTickRef.current;
    }, 1000);
    return () => { un?.(); clearInterval(t); };
  }, []);

  const connectedDevice = link.connected && link.device_id
    ? devices.find((d) => d.id === link.device_id) ?? null
    : null;

  // Live view of `link` for use inside []-scoped event handlers (which
  // capture the first render's state and never see updates otherwise).
  const linkRef = useRef(link);
  linkRef.current = link;

  /* App-level audio pipeline. Lives HERE, not in AudioView: the phone mutes
   * its speaker while mirroring, so if the user leaves the Audio tab the
   * chunks had no listener — sound existed nowhere. One AudioContext + one
   * queue survive all tab switches. Audio follows the mirror: start on
   * audio_start (mirror or manual), stop on mirror end or disconnect. */
  const audioCtxRef = useRef<AudioContext | null>(null);
  const audioQueueRef = useRef<{ rate: number; chans: number; pcm: Int16Array }[]>([]);
  const audioHeadRef = useRef(0);

  // Chromium autoplay policy: AudioContext created/resumed outside a user
  // gesture stays "suspended" and schedules silently — nothing plays even
  // though chunks arrive fine. Arm it on the first real click instead, so
  // when startAudio runs the context is already unlocked.
  useEffect(() => {
    const unlock = () => {
      try {
        if (!audioCtxRef.current) audioCtxRef.current = new AudioContext();
        void audioCtxRef.current.resume();
      } catch {}
      document.removeEventListener("pointerdown", unlock);
    };
    document.addEventListener("pointerdown", unlock);
    return () => document.removeEventListener("pointerdown", unlock);
  }, []);
  const [audioPlaying, setAudioPlaying] = useState(false);
  const audioPlayingRef = useRef(false);
  audioPlayingRef.current = audioPlaying;

  useEffect(() => {
    let un: (() => void) | undefined;
    api.on<{ rate: number; chans: number; count: number; pcm: number[] }>("audio_chunk", (e) => {
      if (!readPreferences().audio) return;
      const { rate, pcm } = e.payload;
      const arr = Int16Array.from(pcm);
      if (!arr.length) return;
      // Not playing: DROP, don't queue — a backlog would replay seconds-old
      // audio the moment playback resumes.
      if (!audioPlayingRef.current) return;
      audioQueueRef.current.push({ rate, chans: e.payload.chans, pcm: arr });
    }).then((u) => { un = u; });
    return () => { un?.(); };
  }, []);

  // Drain the queue into the AudioContext while playing.
  useEffect(() => {
    if (!audioPlaying) return;
    const tick = setInterval(() => {
      const ctx = audioCtxRef.current;
      // Suspended context freezes currentTime while chunks keep arriving —
      // scheduling against that clock builds a seconds-long backlog that
      // plays late the moment audio finally unlocks. Don't schedule at all
      // until the context is actually running.
      if (!ctx || ctx.state !== "running") {
        audioQueueRef.current = [];
        audioHeadRef.current = 0;
        return;
      }
      // Catch-up guard: if the schedule head drifted >0.5s ahead of the wall
      // clock (bursty LAN delivery), snap it back — live audio must stay
      // live; a 2s echo of what just happened on the phone is useless.
      if (audioHeadRef.current > ctx.currentTime + 0.5) {
        audioHeadRef.current = ctx.currentTime + 0.02;
        if (audioQueueRef.current.length > 3) audioQueueRef.current.splice(0, audioQueueRef.current.length - 2);
      }
      while (audioQueueRef.current.length) {
        const item = audioQueueRef.current.shift()!;
        const when = Math.max(ctx.currentTime + 0.02, audioHeadRef.current);
        const buf = ctx.createBuffer(1, item.pcm.length, item.rate);
        const data = buf.getChannelData(0);
        for (let i = 0; i < item.pcm.length; i++) data[i] = item.pcm[i] / 32768;
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(ctx.destination);
        src.start(when);
        audioHeadRef.current = when + item.pcm.length / item.rate;
      }
    }, 100);
    return () => clearInterval(tick);
  }, [audioPlaying]);

  // Mirror start/stop + disconnect drive the phone's audio capture — the
  // phone mutes itself while mirrored, so the desktop MUST run the speakers.
  const startAudio = async () => {
    try {
      if (!audioCtxRef.current) audioCtxRef.current = new AudioContext();
      await audioCtxRef.current.resume();
      audioHeadRef.current = 0;
      await api.invoke("audio_start");
      setAudioPlaying(true);
    } catch (e) { setAudioPlaying(false); toast(String(e), "err"); }
  };
  const stopAudio = async () => {
    try { await api.invoke("audio_stop"); } catch { /* link may be gone */ }
    setAudioPlaying(false);
    audioQueueRef.current = [];
    audioHeadRef.current = 0;
  };

  useEffect(() => {
    const unsubs: (() => void)[] = [];

    // Push the persisted auto-reconnect preference into the Rust side at
    // startup — the toggle must survive restarts without the user visiting
    // the Settings view (that component unmounts on navigation).
    void api.invoke("set_auto_reconnect", { on: localStorage.getItem("lynko-autoreconnect") !== "0" }).catch(() => {});

    // OS-level hotkey fired. The window toggle is handled in Rust (it owns the
    // window); mirror toggling needs the live `streaming` flag, which only
    // ScreenView has — re-dispatch as a DOM event it subscribes to.
    api.on<string>("lynko-hotkey", (e) => {
      if (e.payload === "toggle_mirror") window.dispatchEvent(new Event("lynko-toggle-mirror"));
    }).then((u) => unsubs.push(u));

    api.on<Device[]>("discovery", (e) => setDevices(e.payload)).then((u) => unsubs.push(u));

    api.on<LinkState>("link_state", (e) => {
      setLink(e.payload);
      if (e.payload.connected) {
        toast("Connected", "ok");
      } else {
        setBattery(null);
        // Link dropped: the phone can't be muted-for-mirror anymore; make
        // sure the desktop isn't left holding a dead audio stream.
        void stopAudio();
      }
    }).then((u) => unsubs.push(u));

    api.on<{ t: string; d: Record<string, unknown> }>("link_event", (e) => {
      const ev = e.payload as { t: string; d: Record<string, unknown> };
      if (ev.t === "log" && typeof ev.d.msg === "string") {
        const msg = ev.d.msg;
        if (msg.startsWith("Audio unavailable:") || msg === "screen permission required for audio capture" || msg === "audio capture needs Android 10+") {
          setAudioPlaying(false); audioQueueRef.current = []; toast(msg, "err");
        }
      } else if (ev.t === "battery") {
        setBattery({ pct: ev.d.pct as number, charging: ev.d.charging as boolean });
      } else if (ev.t === "clipboard") {
        if (!readPreferences().clipboard) return;
        setClipItems((c) => [{ id: ++toastUid, text: ev.d.text as string, source: "phone" as const, at: Date.now() }, ...c].slice(0, 30));
        toast("Clipboard received from phone", "ok");
      } else if (ev.t === "clipboard_reply") {
        if (!readPreferences().clipboard) return;
        setClipItems((c) => [{ id: ++toastUid, text: ev.d.text as string, source: "phone" as const, at: Date.now() }, ...c].slice(0, 30));
        toast("Clipboard pulled from phone", "ok");
      } else if (ev.t === "phone_state") {
        const hadControl = linkRef.current.control;
        const wasMirror = linkRef.current.mirror;
        const nowMirror = ev.d.mirror as boolean;
        // Audio follows the mirror (phone self-mutes while mirrored): start
        // capture when mirroring begins, stop when it ends. Browser autoplay
        // policy is satisfied because Start stream is a user gesture upstream.
        if (nowMirror && !wasMirror) void startAudio();
        else if (!nowMirror && wasMirror) void stopAudio();
        setLink((l) => ({
          ...l,
          mirror: nowMirror,
          locked: ev.d.locked as boolean,
          control: typeof ev.d.control === "boolean" ? ev.d.control : undefined,
          usb: (ev.d.usb as boolean) ?? false,
        }));
        if (ev.d.locked) toast("Phone locked — mirror survives locks; it returns on unlock", "info");
        // Flipped to false while a desktop is live: gestures are being eaten.
        // Say it once per transition, with the exact cure (MIUI shows the
        // toggle as ON even when the service is dead).
        if (hadControl && ev.d.control === false) {
          toast("Phone can't receive input — open Settings → Accessibility → Lynko, toggle OFF then ON", "err");
        }
      } else if (ev.t === "input_error") {
        const kind = ev.d.kind as string;
        if (kind === "accessibility") toast("Taps & swipes need Accessibility: open Settings → Accessibility → Lynko → enable", "err");
        else if (kind === "field") toast("No focused text field on the phone — tap one in the mirror first", "err");
      } else if (ev.t === "notification") {
        const safe = filterNotification({app:String(ev.d.app ?? ''), title:String(ev.d.title ?? ''), body:String(ev.d.text ?? ev.d.body ?? '')}, readPreferences());
        if (!safe) return;
        const {app,title,body} = safe;
        // The Rust core re-serializes events in snake_case: the phone sends
        // notifId, the frontend receives notif_id. Read both — falling back
        // to 0 silently breaks every notification reply.
        const nid = ((ev.d.notif_id ?? ev.d.notifId) as number) ?? 0;
        setNotes((n) => [{ id: ++toastUid, app, title, body, at: Date.now(), notifId: nid }, ...n].slice(0, 50));
        // Native OS toast (Samsung Flow parity: alerts while you work on the
        // PC). Honors the persisted Settings → desktop-notifications toggle;
        // read from localStorage so it works from any component.
        if (localStorage.getItem("lynko-desktop-notifs") !== "0") void (async () => {
          try {
            const { isPermissionGranted, requestPermission, sendNotification } = await import("@tauri-apps/plugin-notification");
            let granted = await isPermissionGranted();
            if (!granted) granted = (await requestPermission()) === "granted" || (await isPermissionGranted());
            const latest = filterNotification({app,title,body},readPreferences());
            if (granted && latest) sendNotification({ title: latest.title ? `${latest.title} — ${app}` : app, body: latest.body });
          } catch { /* plugin missing in dev: silent */ }
        })();
      }
    }).then((u) => unsubs.push(u));

    api.on<{ msg: string }>("log", (e) => {
      toast(e.payload.msg, "info");
    }).then((u) => unsubs.push(u));

    /* native OS file drops (real paths) feed the Files queue too */
    dropHandler = (paths) => {
      for (const p of paths) { try { enqueueFile(p); } catch(e) { toast(String(e), "err"); } }
    };

    return () => { unsubs.forEach((u) => u()); dropHandler = null; };
  }, [toast]);

  useEffect(() => {
    api.invoke<Device[]>("list_devices").then(setDevices).catch(() => {});
  }, []);

  const queueFile = (path:string) => {
    try { enqueueFile(path); } catch(e) { toast(String(e),'err'); }
  };
  const updateFeatures = (change:Partial<Preferences>) => {
    const next = {...prefs,...change};
    updatePrefs(change);
    setNotes(items => items.flatMap(n => {const safe = filterNotification(n,next);return safe ? [safe] : [];}));
    if (!next.clipboard) setClipItems([]);
    if (change.screen === false) void api.invoke('screen_stop').catch(e=>toast(String(e),'err'));
    if (change.audio === false) void stopAudio();
  };

  return (
    <div className={link.connected ? "app live" : "app"}>
      <Shell
        view={view} setView={setView}
        lang={lang} setLang={setLang}
        devices={devices}
        link={link} battery={battery}
        clipItems={clipItems} setClipItems={setClipItems}
        files={files} cancelFile={cancelFile} retryFile={retryFile}
        prefs={prefs} updatePrefs={updateFeatures} clearNotes={()=>setNotes([])} lastFrame={lastFrame} now={now}
        notes={notes}
        connectedDevice={connectedDevice}
        toast={toast}
        queueFile={queueFile}
        audioPlaying={audioPlaying}
        startAudio={startAudio}
        stopAudio={stopAudio}
        screenFps={screenFps}
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
  files: TransferItem[]; cancelFile:(id:number)=>void; retryFile:(id:number)=>void;
  prefs:Preferences; updatePrefs:(patch:Partial<Preferences>)=>void; clearNotes:()=>void; lastFrame:number|null; now:number;
  notes: NoteItem[];
  connectedDevice: Device | null;
  toast: (msg: string, kind?: "info" | "ok" | "err") => void;
  queueFile: (path: string) => void;
  audioPlaying: boolean;
  startAudio: () => Promise<void>;
  stopAudio: () => Promise<void>;
  screenFps: number;
}

function Shell(props: ShellProps) {
  const { view, setView, lang, devices, link, battery, connectedDevice, notes, screenFps } = props;
  const T = (k: string) => tr(lang, "desktop", k);

  return (
    <div className="shell">
      <nav className="rail">
        <div className="brand">
          <Mark />
          <span className="wordmark">Lynko</span>
        </div>
        <NavItem view={view} setView={setView} id="devices" label={T("nav_devices")} Icon={IconDevices} />
        <NavItem view={view} setView={setView} id="screen" label={T("nav_screen")} Icon={IconScreen} />
        <NavItem view={view} setView={setView} id="files" label={T("nav_files")} Icon={IconFiles} />
        <NavItem view={view} setView={setView} id="notifications" label={T("nav_notifications")} Icon={IconNotes} badge={notes.length} />
        <NavItem view={view} setView={setView} id="health" label={extra(lang,"health")} Icon={IconBattery} />
        <div className="rail-spacer" />
        <NavItem view={view} setView={setView} id="settings" label={T("nav_settings")} Icon={IconSettings} />
      </nav>

      <IncomingShare lang={lang}/>
      <main className="stage">
        {view === 'health' && <div className="view"><HealthPanel lang={lang} link={link} lastFrame={props.lastFrame} now={props.now} capable={connectedDevice?.caps.screen_capture} refresh={()=>{void api.invoke('request_status').catch(e=>props.toast(String(e),'err'));}}/></div>}
        {view === "devices" && <DevicesView {...props} />}
        {view === "screen" && <ScreenView {...props} />}
        {view === "files" && <FilesView {...props} />}
        {view === "notifications" && <NotificationsView {...props} />}
        {view === "settings" && <SettingsView {...props} />}
      </main>

      <footer className="status">
        <button
          className={link.connected ? "conn-chip live" : "conn-chip"}
          onClick={() => !link.connected && setView("devices")}
        >
          <span className="dot" />
          {link.connected ? (connectedDevice?.name ?? "phone") : T("not_connected")}
        </button>
        {link.connected && link.mirror && (
          <span className="conn-chip live">
            {link.locked ? T("phone_locked") : T("mirroring")}
          </span>
        )}
        {link.connected && !link.mirror && (
          <span className="conn-chip">{T("phone_idle")}</span>
        )}
        {link.connected && link.control === false && (
          <span className="conn-chip err" title={T("enable_a11y_hint")}>
            {T("no_control")}
          </span>
        )}
        {link.connected && battery && (
          <span className="conn-chip">
            <IconBattery />{battery.pct}%
          </span>
        )}
        <span className="sp">{devices.filter((d) => d.online).length} online</span>
        <span className="sp">{devices.filter((d) => d.paired).length} paired</span>
        {link.connected && link.mirror && screenFps > 0 && (
          <span className="sp">{screenFps}fps</span>
        )}
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

  const [pairingDevice, setPairingDevice] = useState<Device | null>(null);
  const [pinInput, setPinInput] = useState("");
  const [qrDataUrl, setQrDataUrl] = useState("");
  const RELEASE_URL = "https://github.com/ErfanBagheri404/Lynko/releases/latest";

  useEffect(() => {
    QRCode.toDataURL(RELEASE_URL, { width: 180, margin: 1, color: { dark: "#e8e6e1", light: "#0a0b0d" } })
      .then(setQrDataUrl)
      .catch(() => {});
  }, []);

  const [checkingPair, setCheckingPair] = useState<string | null>(null);
  const [pairUnavailable, setPairUnavailable] = useState<string | null>(null);
  const pair = async (d: Device) => {
    if (checkingPair) return;
    setCheckingPair(d.id);
    setPairUnavailable(null);
    try {
      const ready = await api.invoke<boolean>("check_pairing_ready", { deviceId: d.id });
      if (!ready) { setPairUnavailable(d.id); return; }
      setPairingDevice(d);
      setPinInput("");
    } catch {
      setPairUnavailable(d.id);
    } finally { setCheckingPair(null); }
  };

  const submitPin = async () => {
    if (!pairingDevice || !pinInput.trim()) return;
    try {
      await api.invoke("pair_device", { deviceId: pairingDevice.id, pin: pinInput.trim(), desktopName: "Desktop" });
      toast(tr(lang, "toasts", "paired").replace("{name}", pairingDevice.name), "ok");
    } catch (e) { toast(tr(lang, "toasts", "pair_failed").replace("{e}", String(e)), "err"); }
    setPairingDevice(null);
    setPinInput("");
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
            <span className="radar-count"><strong>{devices.length}</strong> phone(s)</span>
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
                  <span className="addr" title={d.address}>{d.id.startsWith("usb:") ? T("via_usb") : d.address}</span>
                  {d.linked && <span className="linked-tag">{T("status_connected")}</span>}
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
                  <span className={d.id.startsWith("usb:") ? "dev-dot usb" : "dev-dot on"} />
                  <div className="who"><strong>{d.name}</strong><span className="addr" title={d.address}>{d.id.startsWith("usb:") ? T("via_usb") : d.address}</span>
                    {pairUnavailable === d.id && <span role="status" style={{color: "var(--amber, #ffb454)", maxWidth: 340}}>{lang === "fa" ? "لینکو را در گوشی باز کنید و شروع را بزنید، سپس دوباره تلاش کنید." : "Open Lynko on your phone and tap Start, then try again."}</span>}
                  </div>
                  <button className="btn sm primary" disabled={checkingPair !== null} onClick={() => pair(d)}>{checkingPair === d.id ? (lang === "fa" ? "در حال بررسی…" : "Checking…") : T("pair")}</button>
                </div>
              ))}
            </div>
          )}

          <div className="card">
            <h2>{T("first_time")}</h2>
            <p className="desc">{T("qr_desc")}</p>
            {qrDataUrl && <a href={RELEASE_URL} target="_blank" rel="noopener noreferrer" style={{display:"inline-block",cursor:"pointer"}}><img className="qr-img" src={qrDataUrl} alt="QR code" /></a>}
            <span className="qr-url">{RELEASE_URL}</span>
          </div>
        </div>
      </div>

      {pairingDevice && (
        <div className="modal-overlay" onClick={() => setPairingDevice(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>{T("enter_pin")}</h2>
            <p className="desc">{T("pin_prompt").replace("{name}", pairingDevice.name)}</p>
            <input
              className="field pin-field"
              type="text"
              inputMode="numeric"
              maxLength={8}
              autoFocus
              placeholder="0000"
              value={pinInput}
              onChange={(e) => setPinInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") submitPin(); }}
            />
            <div className="modal-actions">
              <button className="btn ghost" onClick={() => setPairingDevice(null)}>{T("cancel")}</button>
              <button className="btn primary" onClick={submitPin} disabled={!pinInput.trim()}>{T("pair_connect")}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Screen                                                               */
/* ------------------------------------------------------------------ */

function ScreenView(props: ShellProps) {
  const { link, toast, lang, audioPlaying, startAudio, stopAudio } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const [streaming, setStreaming] = useState(false);
  const [frame, setFrame] = useState<string | null>(null);
  const [rotated, setRotated] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [zoom,setZoom]=useState(1);
  const [fill,setFill]=useState(false);
  const [onTop,setOnTop]=useState(false);
  const toggleTop=async()=>{try {const {getCurrentWindow}=await import('@tauri-apps/api/window');const win=getCurrentWindow();const next=!(await win.isAlwaysOnTop());await win.setAlwaysOnTop(next);setOnTop(next);}catch(e){toast(String(e),'err');}};
  useEffect(()=>{setStreaming(Boolean(link.connected && link.mirror));if(!link.mirror || !props.prefs.screen){setFrame(null);frameArrived.current=false;}},[link.connected,link.mirror,props.prefs.screen]);
  const canvasRef = useRef<HTMLDivElement>(null);
  const dragStart = useRef<{ x: number; y: number } | null>(null);
  const dragging = useRef(false);
  const lastMove = useRef({ x: 0, y: 0 });
  // Wall-clock of the last pointer sample, so each move can carry its TRUE
  // inter-sample gap (dt) to the phone. Network arrival timing is bursty —
  // sizing stroke segments from it replays the swipe late (fires on release).
  const lastMoveT = useRef(0);

  const sendMove = (p: { x: number; y: number }, dtMs: number) => {
    // Fire-and-forget ON PURPOSE: awaiting each move serializes the stroke
    // behind IPC+network RTT (~10-30ms each), so a 120Hz pointer compounds
    // into visible lag. dt carries the REAL pointer-sample gap so the phone
    // sizes stroke segments from true timing, not bursty arrival timing.
    try { void api.invoke("inject_drag_move", { x: p.x, y: p.y, dt: dtMs }); } catch (e) { console.warn("drag move failed:", e); }
  };

  const onDown = (ev: React.PointerEvent) => {
    // Record only — do NOT press the phone finger yet. A click that never
    // moves becomes a clean single tap on release; only REAL motion opens a
    // stroke chain. (Pressing on pointerdown turned every tap into a
    // continuation gesture whose window can expire mid-press and leave the
    // phone finger stuck down — one stuck finger blocks every later
    // gesture: "nothing works anymore".)
    if (!props.prefs.input || !props.prefs.screen || rotated) return;
    const p = norm(ev);
    dragStart.current = p;
    lastMove.current = p;
    lastMoveT.current = performance.now();
    dragging.current = false;
    try { ev.currentTarget.setPointerCapture(ev.pointerId); } catch {}
  };

  const onMove = (ev: React.PointerEvent) => {
    if (!streaming || !props.prefs.input || !props.prefs.screen || rotated) return;
    const p = norm(ev);
    const now = performance.now();
    if (!dragging.current) {
      const s = dragStart.current;
      if (!s) return;
      // 2% of screen ≈ 20px: below that, it's a click with a shaky hand.
      if (Math.hypot(p.x - s.x, p.y - s.y) < 0.02) return;
      // Finger goes down at the ORIGIN, then chases the cursor — the phone
      // sees one continuous press-drag like a real finger. Fire-and-forget:
      // the stroke points arrive in order on the single WS channel; awaiting
      // dragStart before the first move serialized LAN RTT into the gesture.
      dragging.current = true;
      lastMove.current = p;
      const dt0 = Math.max(8, Math.round(now - lastMoveT.current));
      lastMoveT.current = now;
      void api.invoke("inject_drag_start", { x: s.x, y: s.y, dt: 0 }).catch((e) => {
        console.warn("drag start failed:", e);
        dragging.current = false;
      });
      sendMove(p, dt0);
      return;
    }
    const dx = Math.abs(p.x - lastMove.current.x), dy = Math.abs(p.y - lastMove.current.y);
    if (dx < 0.008 && dy < 0.008) return; // throttle: segments only on real motion
    const dt = Math.max(4, Math.round(now - lastMoveT.current));
    lastMove.current = p;
    lastMoveT.current = now;
    void sendMove(p, dt);
  };

  // subscribe to JPEG frames from the Rust link layer.
  // Direct <img>.src writes — NOT React state: setState at 15fps re-renders
  // the whole component tree per frame; a direct assignment paints the
  // moment the frame arrives and skips reconciliation entirely.
  const imgRef = useRef<HTMLImageElement | null>(null);
  const frameArrived = useRef(false);
  // Link-drop cleanup: when the link dies, the <img> keeps painting the
  // LAST frozen frame — a stale screenshot that looks like a live mirror.
  // Subscribe to link_state and clear the frame state so the canvas falls
  // back to its no-signal states instead of lying. (FPS is counted at App
  // level for the footer — no local tick here.)
  useEffect(() => {
    let un: (() => void) | undefined;
    // Frames come from the localhost frame server (Rust writes the newest
    // JPEG into memory; http://127.0.0.1:7919/frame.jpg serves it). Polling
    // beats event-push here: the browser loads the image natively (no base64
    // through the Tauri bridge) and always gets the NEWEST frame — a slow
    // paint can never queue up stale frames, which is exactly what made the
    // mirror look delayed while taps felt instant.
    const FRAME_URL = "http://127.0.0.1:7919/frame.jpg";
    let lastSeq = -1;
    let stop = false;
    // Blob URLs MUST be tracked in one place: unrevoked 24KB blobs at 15fps
    // kill the WebView renderer in ~1h (white screen). Two leak paths here:
    // the no-ref branch (setFrame called every frame) and a fresh mount
    // inheriting el.dataset.blobUrl from a prior effect run.
    let liveUrl: string | null = null;
    const setLiveUrl = (url: string | null) => {
      if (liveUrl && liveUrl !== url) URL.revokeObjectURL(liveUrl);
      liveUrl = url;
    };
    const tick = async () => {
      if (stop) return;
      if (!readPreferences().screen) { setTimeout(tick, 250); return; }
      try {
        const r = await fetch(`${FRAME_URL}?t=${Date.now()}`, { cache: "no-store" });
        if (r.ok) {
          const seq = Number(r.headers.get("X-Frame-Seq") ?? "-1");
          if (seq !== lastSeq) {
            lastSeq = seq;
            const blob = await r.blob();
            const url = URL.createObjectURL(blob);
            const el = imgRef.current;
            if (el) {
              el.src = url;
            } else if (!frameArrived.current) {
              frameArrived.current = true;
              setFrame(url);
            }
            // After the new frame is in use, free the previous blob.
            setLiveUrl(url);
          }
        }
      } catch { /* server not up yet */ }
      setTimeout(tick, 16);
    };
    tick();
    const un2Promise = api.on<LinkState>("link_state", (e) => {
      if (!e.payload.connected) {
        frameArrived.current = false;
        setFrame(null);
        setStreaming(false);
      }
    });
    return () => {
      // Kill the poller + free the last blob: without this, every remount
      // (dev HMR, strict-mode double-invoke) stacks another 16ms loop and
      // the last 24KB blob stays resident forever.
      stop = true;
      setLiveUrl(null);
      un?.();
      void un2Promise.then((u) => u?.());
    };
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

  // Ctrl+Shift+M from anywhere in the OS. Toggle direction comes from
  // `streaming` (mirroring on → stop, otherwise start), and the screen
  // feature toggle still wins — a hotkey must not bypass what the user
  // switched off in Settings.
  useEffect(() => {
    const flip = () => {
      if (!props.prefs.screen) { void start(); return; }
      if (streaming) void stop(); else void start();
    };
    window.addEventListener("lynko-toggle-mirror", flip);
    return () => window.removeEventListener("lynko-toggle-mirror", flip);
  }, [streaming, props.prefs.screen, start, stop]);

  const norm = (ev: React.PointerEvent) => {
    // Map onto the PAINTED image, not the <img> element box: with
    // object-fit:contain the element box is the whole canvas and the bitmap
    // is letterboxed inside it — measuring the element box sent side taps
    // into the black bars (clamped to the edge). Compute the content rect:
    // the largest centered rect with the bitmap's aspect ratio that fits in
    // the element box, then normalize against THAT. Zoom scales the bitmap
    // around the canvas center, so the same math applies at any zoom.
    const img = imgRef.current;
    const el = (ev.currentTarget as HTMLElement).getBoundingClientRect();
    const iw = img?.naturalWidth || el.width;
    const ih = img?.naturalHeight || el.height;
    const scale = Math.min(el.width / iw, el.height / ih) * (fill ? Math.max(el.width / iw, el.height / ih) : 1);
    const w = iw * scale, h = ih * scale;
    const left = el.left + (el.width - w) / 2, top = el.top + (el.height - h) / 2;
    return {
      x: Math.min(1, Math.max(0, (ev.clientX - left) / w)),
      y: Math.min(1, Math.max(0, (ev.clientY - top) / h)),
    };
  };

  const onTap = async (ev: React.PointerEvent) => {
    if (!streaming || !props.prefs.input || !props.prefs.screen || rotated) return;
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
    try { await api.invoke("inject_tap", { x: p.x, y: p.y }); } catch {}
  };

  const onKey = async (ev: React.KeyboardEvent) => {
    if (!streaming || !props.prefs.input || !props.prefs.screen || rotated) return;
    // Ctrl/Cmd+V: paste desktop clipboard content as typed text on the phone.
    if ((ev.ctrlKey || ev.metaKey) && (ev.key === "v" || ev.key === "V" || ev.key === "۰")) {
      ev.preventDefault();
      try {
        const clip = await api.invoke<string>("get_pc_clipboard");
        if (clip) await api.invoke("inject_text", { text: clip });
      } catch {}
      return;
    }
    // Everything else goes through the shared, tested key map — so a modifier
    // combo is never forwarded as "type the bare character", and unknown keys
    // are dropped rather than sent to the phone as a name it will reject.
    const act = mapKey({
      key: ev.key,
      ctrlKey: ev.ctrlKey,
      metaKey: ev.metaKey,
      altKey: ev.altKey,
      shiftKey: ev.shiftKey,
    });
    if (act.kind === "drop") return;
    if (act.kind === "text") {
      try { await api.invoke("inject_text", { text: act.text }); } catch {}
    } else {
      ev.preventDefault();
      try { await api.invoke("inject_key", { key: act.key }); } catch {}
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
    <div className="view screen-view">
      <PageHead title={T("screen_title")} sub={T("screen_sub")} />
      <div className="comfort-row">
        <button className="btn sm" aria-pressed={!fill} onClick={()=>setFill(false)}>{extra(lang,'fit')}</button>
        <button className="btn sm" aria-pressed={fill} onClick={()=>setFill(true)}>{extra(lang,'fill')}</button>
        <label>{extra(lang,'zoom')} <input aria-label={extra(lang,'zoom')} type="range" min="1" max="3" step="0.1" value={zoom} onChange={e=>setZoom(Number(e.target.value))}/>{zoom.toFixed(1)}×</label>
        <button className="btn sm" aria-pressed={onTop} onClick={()=>void toggleTop()}>{extra(lang,'top')}</button>
        <button className="btn sm" disabled={!link.connected} onClick={()=>void stop()}>{extra(lang,'stopCapture')}</button>
      </div>
      <div className="screen-body">
      <aside className="screen-rail">
        <div className="screen-rail-group">
          {!streaming
            ? <button className="rail-icon primary" disabled={!link.connected} onClick={start} title={T("start_stream")} aria-label={T("start_stream")}><IconPlay /></button>
            : <button className="rail-icon" onClick={stop} title={T("stop")} aria-label={T("stop")}><IconStop /></button>}
        </div>
        <div className="screen-rail-group">
          <button className="rail-icon" disabled={!link.connected} onClick={() => { try { void api.invoke("inject_key", { key: "BACK" }); } catch {} }} title={T("back")} aria-label={T("back")}><IconBack /></button>
          <button className="rail-icon" disabled={!link.connected} onClick={() => { try { void api.invoke("inject_key", { key: "HOME" }); } catch {} }} title={T("home")} aria-label={T("home")}><IconHome /></button>
          <button className="rail-icon" disabled={!link.connected} onClick={() => { try { void api.invoke("inject_key", { key: "RECENTS" }); } catch {} }} title={T("recents")} aria-label={T("recents")}><IconRecents /></button>
        </div>
        <div className="screen-rail-group">
          <button className="rail-icon" disabled={!link.connected} onClick={toggleRotate} title={rotated ? T("upright") : T("rotate")} aria-label={rotated ? T("upright") : T("rotate")}><IconRotate /></button>
          <button className="rail-icon" disabled={!link.connected} onClick={toggleFullscreen} title={fullscreen ? T("exit") : T("fullscreen")} aria-label={fullscreen ? T("exit") : T("fullscreen")}><IconExpand /></button>
          <button className={`rail-icon${audioPlaying ? " active" : ""}`} disabled={!link.connected} onClick={() => audioPlaying ? stopAudio?.() : startAudio?.()} title={audioPlaying ? T("stop_audio") : T("play_audio")} aria-label={audioPlaying ? T("stop_audio") : T("play_audio")}><IconAudio /></button>
          <button className="rail-icon" disabled={!link.connected} onClick={async () => {
            try {
              const clip = await api.invoke<string>("get_pc_clipboard");
              if (clip) { await api.invoke("inject_text", { text: clip }); toast(tr(lang, "toasts", "clip_pasted"), "ok"); }
            } catch (e) { toast(tr(lang, "toasts", "clip_failed").replace("{e}", String(e)), "err"); }
          }} title={T("paste_clip")} aria-label={T("paste_clip")}><IconClip /></button>
        </div>
      </aside>
      <div className={`screen-frame${frame || frameArrived.current ? " lit" : ""}`}>
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
        >
          {frame || frameArrived.current ? (
            <div className="tap-layer">
              <img ref={imgRef} src={frame ?? undefined} alt="phone screen" draggable={false} style={{transform:`scale(${zoom})`,...(fill?{width:'100%',height:'100%',objectFit:'cover' as const}: {})}} />
            </div>
          ) : link.connected ? (
            <div className="no-signal">
              <strong>{streaming ? T("waiting_frames") : T("stream_off")}</strong>
              <span>{streaming ? T("starting_capture") : T("press_start")}</span>
            </div>
          ) : (
            <div className="no-signal">
              <strong>{T("no_signal")}</strong>
              <span>{T("no_signal_sub")}</span>
            </div>
          )}
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
      <p className="desc">{extra(lang,'queueHint')}</p><p className="desc">{extra(lang,'queuePaused')}</p>
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
          <strong>{T("drop_send")}</strong>
          <span>{T("drop_browse")}</span>
        </div>
        {files.length > 0 && (
          <div className="file-list">
            {files.map((f) => {
              const pct = f.size && f.written ? Math.min(100, Math.round((f.written / f.size) * 100)) : null;
              return (
                <div className="file-row" key={f.id}>
                  <IconFiles /><span>{f.name}</span>
                  <span className="sz">
                    {f.size ? `${(f.size / 1024).toFixed(0)} KB` : "…"} · {extra(lang,f.status)}
                    {pct != null && f.status === "sent" && f.written! < (f.size ?? 0) ? ` · ${pct}%` : ""}
                  </span>
                  {f.status==='queued' && <button className="btn sm ghost" onClick={()=>props.cancelFile(f.id)}>{extra(lang,'cancel')}</button>}
                  {['failed','cancelled'].includes(f.status) && <button className="btn sm ghost" onClick={()=>props.retryFile(f.id)}>{extra(lang,'retry')}</button>}
                  {f.error && <span role="status">{f.error}</span>}
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
  const { lang, link } = props;
  const T = (k: string) => tr(lang, "desktop", k);
  const { notes, toast } = props;
  const [replyTo, setReplyTo] = useState<NoteItem | null>(null);
  const [replyText, setReplyText] = useState("");

  const sendReply = async () => {
    if (!replyTo || !replyText.trim() || !readPreferences().notifications || readPreferences().blockedApps.includes(replyTo.app)) return;
    try {
      await api.invoke("notif_reply", { app: replyTo.app, notifId: replyTo.notifId ?? 0, text: replyText });
      toast(`Reply sent to ${replyTo.app}`, "ok");
      setReplyTo(null); setReplyText("");
    } catch (e) {
      toast(String(e), "err");
    }
  };

  return (
    <div className="view">
      <PageHead title={T("notif_title")} sub={T("notif_sub")} />
      <PrivacyControls lang={lang} prefs={props.prefs} updatePrefs={props.updatePrefs} clear={props.clearNotes}/>
      <div className="card">
        {notes.length === 0 ? <p className="empty-inline">{T("no_notifications_yet")}</p> :
          notes.map((n) => (
            <div className="note-row" key={n.id}>
              <div className="note-app">{n.app}</div>
              <p className="note-title">{n.title}</p>
              <p className="note-body">{n.body}</p>
              {link.connected && <button className="ghost" onClick={() => setReplyTo(n)}>{T("reply")}</button>}
            </div>
          ))
        }
      </div>
      {replyTo && (
        <div className="card reply-box">
          <p className="desc">{T("replying_to")} <strong>{replyTo.title}</strong> ({replyTo.app})</p>
          <div className="row">
            <input
              autoFocus
              value={replyText}
              onChange={(e) => setReplyText(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") void sendReply(); }}
              placeholder={T("reply_placeholder")}
            />
            <button onClick={() => void sendReply()}>{T("send")}</button>
            <button className="ghost" onClick={() => { setReplyTo(null); setReplyText(""); }}>{T("cancel")}</button>
          </div>
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Settings                                                             */
/* ------------------------------------------------------------------ */

function SettingsView(props: ShellProps) {
  const { lang, setLang } = props;
  // Both toggles persist: flipping one actually does something on THIS run
  // and survives a restart (localStorage), instead of resetting on every
  // navigation the way plain useState(true) did.
  const [autoReconnect, setAutoReconnect] = useState(() =>
    localStorage.getItem("lynko-autoreconnect") !== "0");
  const [toasts, setToasts] = useState(() =>
    localStorage.getItem("lynko-desktop-notifs") !== "0");
  const toggleAutoReconnect = (on: boolean) => {
    setAutoReconnect(on);
    localStorage.setItem("lynko-autoreconnect", on ? "1" : "0");
    void api.invoke("set_auto_reconnect", { on }).catch(() => {});
  };
  const toggleToasts = (on: boolean) => {
    setToasts(on);
    localStorage.setItem("lynko-desktop-notifs", on ? "1" : "0");
  };
  const T = (k: string) => tr(lang, "settings", k);
  return (
    <div className="view">
      <PageHead title={T("settings_title")} sub={T("settings_sub")} />
      <FeatureControls lang={lang} prefs={props.prefs} updatePrefs={props.updatePrefs}/>
      <SetRow title={extra(lang,'animations')} desc={extra(lang,'motionHint')} on={props.prefs.animations} onToggle={()=>props.updatePrefs({animations:!props.prefs.animations})}/>
      <SetRow title={extra(lang,'hotkeys')} desc={extra(lang,'hotkeysHint')} on={props.prefs.hotkeys} onToggle={()=>props.updatePrefs({hotkeys:!props.prefs.hotkeys})}/>
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
        <SetRow title={T("autoconnect")} desc={T("autoconnect_desc")} on={autoReconnect} onToggle={() => toggleAutoReconnect(!autoReconnect)} />
        <SetRow title={T("desktop_notifs")} desc={T("desktop_notifs_desc")} on={toasts} onToggle={() => toggleToasts(!toasts)} />
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
