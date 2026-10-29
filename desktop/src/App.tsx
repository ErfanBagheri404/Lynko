import { useCallback, useEffect, useRef, useState } from "react";
import { Lang, LANGS, t as tr } from "./locales";
import {
  IconAudio, IconBack, IconBattery, IconBell, IconBellOff, IconCamera, IconClip,
  IconDevices, IconExpand, IconFiles, IconHome, IconNotes, IconPin, IconPlay,
  IconRecents, IconRotate, IconScreen, IconSettings, IconStop, Mark,
} from "./icons";
import QRCode from "qrcode";
import {IncomingShare,ShareHistory} from "./enhancements/IncomingShare";
import {usePreferences, readPreferences, useTransfers, useHotkeys, type Preferences, type TransferItem} from './enhancements/hooks';
import {mapKey} from './enhancements/keyboard.mjs';
import {filterNotification, isAppSuppressed} from './enhancements/logic.mjs';
import {HealthPanel, FeatureControls, PrivacyControls} from './enhancements/Panels';
import {UpdateSection} from './enhancements/Update';
import {QualitySection, presetDims} from './enhancements/Quality';
import {text as extra, type EnhancementKey} from './enhancements/strings';
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
  /** OTP found in a mirrored notification — rendered as a Copy-code button. */
  code?: string;
}

let toastUid = 0;

function useToasts() {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const push = useCallback((msg: string, kind: Toast["kind"] = "info", code?: string) => {
    const id = ++toastUid;
    setToasts((t) => [...t.slice(-4), { id, msg, kind, code }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4000);
  }, []);

  return { toasts, push };
}

/* ------------------------------------------------------------------ */
/* OTP detection + per-device feature caps                             */
/* ------------------------------------------------------------------ */

/* One-time codes in mirrored notification text: 4–8 digits not adjacent to
   any other digit. The lookarounds ARE the production-number guard: a phone
   number or order id (9+ digit run) can never match a substring, because
   every inner 4–8 digit window is digit-adjacent on both sides.
   ponytail: no word-context heuristics ("code", "PIN"); add only if a real
   false positive shows up. */
const OTP_RE = /(?<!\d)\d{4,8}(?!\d)/;
const otpOf = (s: string) => s.match(OTP_RE)?.[0] ?? null;

/* Per-device feature switches (NectarLink parity): each paired phone keeps
   its own allow-list, persisted as `lynko-caps-<device-id>` (JSON). UI-only
   for now — the link layer does not consult it yet (a future track wires
   the backend), so flipping a switch changes storage and nothing else. */
type CapKey = "clipboard" | "files" | "notifications" | "screen" | "audio" | "desktop-control";
const CAP_KEYS: CapKey[] = ["clipboard", "files", "notifications", "screen", "audio", "desktop-control"];
const CAP_LABELS: Record<CapKey, EnhancementKey> = {
  clipboard: "clipboard", files: "files", notifications: "notifications",
  screen: "screen", audio: "audio", "desktop-control": "desktopControl",
};
// Sensitive features stay OFF until the user allows them (NectarLink default).
const CAP_DEFAULTS: Record<CapKey, boolean> = {
  clipboard: true, files: true, notifications: true, screen: false, audio: false, "desktop-control": false,
};
const readCaps = (id: string): Record<CapKey, boolean> => {
  try {
    const raw = JSON.parse(localStorage.getItem(`lynko-caps-${id}`) ?? "{}");
    return { ...CAP_DEFAULTS, ...(raw as Partial<Record<CapKey, boolean>>) };
  } catch { return { ...CAP_DEFAULTS }; }
};

/* ------------------------------------------------------------------ */
/* App                                                                  */
/* ------------------------------------------------------------------ */

export default function App() {
  const [view, setView] = useState<View>("devices");
  const appSetView = useRef(setView);
  appSetView.current = setView;
  const [lang, setLang] = useState<Lang>(() => {
    const saved = localStorage.getItem("lynko-lang");
    return saved === "fa" || saved === "en" ? saved : (navigator.language || "").startsWith("fa") ? "fa" : "en";
  });
  useEffect(() => {
    localStorage.setItem("lynko-lang", lang);
    document.documentElement.lang = lang;
    // dir is what actually mirrors the UI. lang alone only sets the locale for
    // font selection and :lang() rules — without dir every LTR-only CSS
    // (flex row order, text-align:left, margin-left) stays left-to-right.
    document.documentElement.dir = lang === "fa" ? "rtl" : "ltr";
  }, [lang]);
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
  const [notes, setNotes] = useState<NoteItem[]>(() => {
    try {
      const raw = localStorage.getItem("lynko-notes");
      if (!raw) return [];
      const arr = JSON.parse(raw);
      if (!Array.isArray(arr)) return [];
      return arr.filter((n) => n && typeof n.app === "string").slice(0, 50);
    } catch { return []; }
  });
  useEffect(() => {
    try { localStorage.setItem("lynko-notes", JSON.stringify(notes.slice(0, 50))); } catch { /* quota */ }
  }, [notes]);
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

  // Schedule each chunk the moment it arrives. The old path queued chunks and
  // drained them on a 100ms setInterval, so every chunk waited up to a full
  // tick before it was even scheduled — that quantization is what made the
  // audio lag behind the picture and stutter whenever a chunk missed a tick.
  // Scheduling on arrival also lets a small jitter buffer absorb LAN bursts
  // without accumulating latency.
  const playChunk = (rate: number, pcm: Int16Array) => {
    const ctx = audioCtxRef.current;
    if (!ctx || ctx.state !== "running" || !pcm.length) return;
    const now = ctx.currentTime;
    const head = audioHeadRef.current;
    // Underrun (head already passed) or drift ahead: re-anchor to a small
    // jitter buffer. Never let the head run away — a backlog replays
    // seconds-old audio and stays late forever.
    if (head < now || head > now + 0.2) audioHeadRef.current = now + 0.06;
    const when = audioHeadRef.current;
    const buf = ctx.createBuffer(1, pcm.length, rate);
    const data = buf.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) data[i] = pcm[i] / 32768;
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(ctx.destination);
    src.start(when);
    audioHeadRef.current = when + pcm.length / rate;
  };
  const playChunkRef = useRef(playChunk);
  playChunkRef.current = playChunk;

  useEffect(() => {
    if (!audioPlaying) return;
    let stop = false;
    let lastSeq = 0;
    // Long-poll the localhost audio server: the Rust side holds the newest
    // chunk and waits for a newer one, so each response carries a fresh 20ms
    // frame ~20ms after it exists. Replaces the old push event (whose JSON
    // encoding of 960 numbers per chunk was the stutter) and the old 100ms
    // drain timer (which quantized latency) in one loop.
    const AUDIO_URL = "http://127.0.0.1:7920/audio.pcm";
    const loop = async () => {
      while (!stop) {
        if (!readPreferences().audio || !audioPlayingRef.current) break;
        try {
          const r = await fetch(`${AUDIO_URL}?seq=${lastSeq}`, { cache: "no-store" });
          if (r.status === 204) continue; // long-poll timeout: nothing new
          const seq = Number(r.headers.get("X-Audio-Seq") ?? "0");
          const rate = Number(r.headers.get("X-Audio-Rate") ?? "0");
          if (!r.ok || !rate || seq <= lastSeq) continue;
          lastSeq = seq;
          const bytes = await r.arrayBuffer();
          // Round to a whole i16 — Int16Array throws on an odd byteLength.
          const even = bytes.byteLength - (bytes.byteLength % 2);
          if (even >= 2) playChunkRef.current(rate, new Int16Array(bytes, 0, even / 2));
        } catch { await new Promise(res => setTimeout(res, 100)); }
      }
    };
    void loop();
    return () => { stop = true; };
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
          setAudioPlaying(false); toast(msg, "err");
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
        // Per-app mute/snooze is enforced HERE, before history and before the
        // OS toast, so muting an app stops its alerts immediately without a
        // reconnect and without dropping the notification from the phone.
        const mutedApps: string[] = (() => { try { return JSON.parse(localStorage.getItem("lynko-muted-apps") ?? "[]"); } catch { return []; } })();
        const snoozedApps: Record<string, number> = (() => { try { return JSON.parse(localStorage.getItem("lynko-snoozed-apps") ?? "{}"); } catch { return {}; } })();
        if (isAppSuppressed(app, mutedApps, snoozedApps)) return;
        // The Rust core re-serializes events in snake_case: the phone sends
        // notifId, the frontend receives notif_id. Read both — falling back
        // to 0 silently breaks every notification reply.
        const nid = ((ev.d.notif_id ?? ev.d.notifId) as number) ?? 0;
        setNotes((n) => [{ id: ++toastUid, app, title, body, at: Date.now(), notifId: nid }, ...n].slice(0, 50));
        // OTP parity: surface the code with a one-click Copy button. The
        // native OS toast cannot carry actions, so the in-app toast does.
        const otp = otpOf(`${title} ${body}`);
        if (otp) toast(`${extra(prefsLangRef.current, "otpToast")}: ${otp}`, "ok", otp);
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

    /* native OS file drops (real paths) feed the Files queue too. No link yet:
       names the cause and opens the pairing tab instead of a raw hook error. */
    dropHandler = (paths) => {
      const setView = appSetView.current;
      if (!linkRef.current.connected && paths.length) {
        if (setView) {
          toast(extra(prefsLangRef.current, "sharePickRecipient"), "err");
          setView("devices");
        }
        return;
      }
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

  /* Copy-code: the Tauri clipboard command is the reliable writer under
     WebView2; navigator.clipboard is the browser-dev fallback. */
  const copyText = useCallback(async (text: string) => {
    try { await api.invoke("set_pc_clipboard", { text }); }
    catch { try { await navigator.clipboard.writeText(text); } catch { /* no clipboard */ } }
    toast(extra(prefsLangRef.current, "copied"), "ok");
  }, [toast]);

  /* Dismiss-sync: drop the card here AND tell the phone to cancel that
     notification when the link is up. No `notif_dismiss` command exists in
     the Rust Command enum yet, so the JSON rides the one generic passthrough
     (send_signal → phone receives t="signal" carrying this payload).
     ponytail: swap to a core Command::NotifDismiss + main.rs command when it
     lands; the phone side matches on the payload kind either way. */
  const dismissNote = useCallback((n: NoteItem) => {
    setNotes((items) => items.filter((x) => x.id !== n.id));
    if (!linkRef.current.connected || !n.notifId) return;
    void api.invoke("send_signal", { payload: { kind: "notif_dismiss", app: n.app, notif_id: n.notifId } }).catch(() => {});
  }, []);

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
        copyText={copyText}
        dismissNote={dismissNote}
      />
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.kind}`}>
            {t.kind === "ok" ? "✓" : t.kind === "err" ? "✕" : "●"} {t.msg}
            {t.code && (
              <button className="toast-copy" onClick={() => void copyText(t.code!)}>{extra(lang, "copyCode")}</button>
            )}
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
  /** Copy a verified OTP to the desktop clipboard. */
  copyText: (text: string) => Promise<void>;
  /** Clear a mirrored notification locally + on the phone (dismiss-sync). */
  dismissNote: (n: NoteItem) => void;
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
    // Standard QR polarity, always: DARK modules on a LIGHT background.
    // An inverted render (light modules on the dark card) only decodes on
    // inversion-tolerant scanners, which drops a real chunk of phone
    // cameras. margin 4 satisfies the spec's required quiet zone.
    QRCode.toDataURL(RELEASE_URL, {
      width: 240,
      margin: 4,
      color: { dark: "#0a0b0d", light: "#ffffff" },
    })
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
  // Only devices reachable right now — an offline mDNS entry would be a
  // tappable "Pair" that can only time out.
  const unpaired = devices.filter((d) => !d.paired && d.online);

  return (
    <div className="view">
      <PageHead title={T("link_room")} sub={T("link_room_sub")} />

      <div className="discover">
        <div className="card radar">
          <h2>{T("radar")}</h2>
          <p className="desc">{T("radar_desc")}</p>
          <div className="radar-world">
            <div className="ring r1" /><div className="ring r2" /><div className="ring r3" /><div className="radar-core" />
            {/* Radar = LIVE network (or USB) devices only. Offline paired
                entries stay in the Paired list below — showing them here
                implies they are reachable when nothing is on the air. */}
            {devices.filter((d) => d.online).map((d, i) => {
              const live = devices.filter((x) => x.online);
              const n = Math.max(live.length, 1);
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
            <span className="radar-count"><strong>{devices.filter((d) => d.online).length}</strong> phone(s)</span>
            <button className="btn sm ghost" onClick={scan}>{T("scan")}</button>
          </div>
          {devices.filter((d) => d.online).length === 0 && (
            <div className="radar-hint">
              <span>{extra(lang, "shareSameWifi")}</span>
              <button className="btn sm ghost" onClick={scan}>{extra(lang, "shareTroubleshoot")}</button>
            </div>
          )}
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
            {qrDataUrl && <a href={RELEASE_URL} target="_blank" rel="noopener noreferrer" className="qr-link"><img className="qr-img" src={qrDataUrl} alt="QR code" /></a>}
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
  const [fill,setFill]=useState(false);
  const [onTop,setOnTop]=useState(false);
  /* Samsung Flow parity: the Smart View control bar. DND mutes the phone's
     notification relay, screen capture writes the mirrored frame to disk,
     favorites pin up to 8 phone apps for one-click launch. */
  const [dnd, setDnd] = useState(false);
  const [apps, setApps] = useState<{ package: string; label: string }[]>([]);
  const [favs, setFavs] = useState<{ package: string; label: string }[]>(() => {
    try {
      const raw = JSON.parse(localStorage.getItem("lynko-favorites") ?? "[]");
      return Array.isArray(raw) ? raw.filter((a) => a && typeof a.package === "string").slice(0, 8) : [];
    } catch { return []; }
  });
  const [showFavs, setShowFavs] = useState(false);
  useEffect(() => {
    try { localStorage.setItem("lynko-favorites", JSON.stringify(favs)); } catch { /* quota */ }
  }, [favs]);
  const toggleTop=async()=>{try {const {getCurrentWindow}=await import('@tauri-apps/api/window');const win=getCurrentWindow();const next=!(await win.isAlwaysOnTop());await win.setAlwaysOnTop(next);setOnTop(next);}catch(e){toast(String(e),'err');}};
  useEffect(()=>{setStreaming(Boolean(link.connected && link.mirror));if(!link.mirror || !props.prefs.screen){setFrame(null);frameArrived.current=false;}},[link.connected,link.mirror,props.prefs.screen]);
  const canvasRef = useRef<HTMLDivElement>(null);
  const paintRef = useRef<HTMLCanvasElement | null>(null);
  // Phone resolution, kept outside the bitmap: the tap mapper (norm) needs
  // iw/ih to hit-test, and reading them off the <img> cost a layout read on
  // every pointer event.
  const intrRef = useRef<{ w: number; h: number }>({ w: 0, h: 0 });
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
  // Direct canvas paints — NOT React state: setState per frame re-renders the
  // whole component tree; drawImage paints the moment the bitmap is decoded.
  const frameArrived = useRef(false);
  // Link-drop cleanup: when the link dies, the canvas keeps painting the LAST
  // frozen frame — a stale screenshot that looks like a live mirror.
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
            // createImageBitmap decodes OFF the main thread and resolves
            // async. createObjectURL + <img>.src made the WebView decode and
            // paint on the main thread, so each frame stalled the input
            // handlers and the picture landed a frame or two late.
            let bmp: ImageBitmap;
            try {
              bmp = await createImageBitmap(blob);
            } catch {
              setTimeout(tick, 16);
              return;
            }
            const el = paintRef.current;
            if (el) {
              // Resize only when it actually changes: assigning width/height
              // every frame clears the canvas and reallocates its buffer.
              if (el.width !== bmp.width || el.height !== bmp.height) {
                el.width = bmp.width;
                el.height = bmp.height;
                intrRef.current = { w: bmp.width, h: bmp.height };
              }
              const ctx = el.getContext("2d");
              if (ctx) { ctx.drawImage(bmp, 0, 0); bmp.close(); }
            } else {
              bmp.close();
            }
            if (!frameArrived.current) {
              frameArrived.current = true;
              // one state flip, to swap the placeholder for the canvas
              setFrame("canvas");
            }
          }
        }
      } catch { /* server not up yet */ }
      // The fetch+decode above is already the rate limiter; the old 16ms
      // timer on top of it added a full poll period of dead time before the
      // NEXT request went out.
      setTimeout(tick, 0);
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
      // Kill the poller: without this, every remount (dev HMR, strict-mode
      // double-invoke) stacks another fetch loop that keeps painting.
      stop = true;
      un?.();
      void un2Promise.then((u) => u?.());
    };
  }, []);

  const start = async () => {
    try {
      await api.invoke("screen_start");
      // Re-apply the saved quality preset on every fresh capture: the phone
      // resets to its 540p/q65 default on each pipeline build, so a preset
      // chosen last session would otherwise be lost on every start.
      try {
        const [maxWidth, quality] = presetDims(localStorage.getItem("lynko-mirror-quality") ?? "balanced");
        await api.invoke("set_quality", { maxWidth, quality });
      } catch { /* quality preset is best-effort on start */ }
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
    const el = (ev.currentTarget as HTMLElement).getBoundingClientRect();
    const iw = intrRef.current.w || el.width;
    const ih = intrRef.current.h || el.height;
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

  /* The phone answers `list_apps` with one `app_list` event. Only the phone
     can see the launcher, so the list arrives on demand, not at connect. */
  useEffect(() => {
    let un: (() => void) | undefined;
    void api
      .on<{ t: string; d: { apps?: { package: string; label: string }[] } }>("link_event", (e) => {
        if (e.payload.t !== "app_list") return;
        const list = Array.isArray(e.payload.d?.apps) ? e.payload.d.apps : [];
        setApps(list.filter((a) => a && typeof a.package === "string"));
      })
      .then((u) => { un = u; });
    return () => un?.();
  }, []);

  const toggleRotate = () => setRotated((r) => !r);

  /* Samsung Flow parity: the Smart View control bar. */
  const toggleDnd = async () => {
    const next = !dnd;
    try {
      await api.invoke("set_dnd", { on: next });
      setDnd(next);
      toast(next ? T("dnd_on") : T("dnd_off"), "ok");
    } catch (e) { toast(`${T("dnd_failed")}: ${e}`, "err"); }
  };

  /* Screen capture: the desktop already holds the newest mirrored JPEG, so
     the shot is that exact frame written to disk — no second capture. */
  const captureShot = async () => {
    try {
      const p = await api.invoke<string>("capture_screenshot");
      toast(`${T("capture_saved")} ${p}`, "ok");
    } catch (e) { toast(`${T("capture_failed")}: ${e}`, "err"); }
  };

  /* Favorites: the phone owns the app list (only it can see the launcher),
     so the desktop asks for it and renders whatever comes back. */
  const openFavPicker = async () => {
    const next = !showFavs;
    setShowFavs(next);
    if (next && apps.length === 0) {
      try { await api.invoke("request_app_list"); } catch (e) { toast(String(e), "err"); }
    }
  };

  const launchApp = async (pkg: string) => {
    try { await api.invoke("launch_app", { package: pkg }); }
    catch (e) { toast(String(e), "err"); }
  };

  const toggleFav = (app: { package: string; label: string }) => {
    setFavs((f) => f.some((x) => x.package === app.package)
      ? f.filter((x) => x.package !== app.package)
      : f.length >= 8 ? f : [...f, app]);
  };

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
        <button className="btn sm" aria-pressed={onTop} onClick={()=>void toggleTop()}>{extra(lang,'top')}</button>
      </div>
      {/* Pinned favorites: one click launches the app on the phone. */}
      {favs.length > 0 && (
        <div className="fav-strip">
          {favs.map((f) => (
            <button key={f.package} className="fav-chip" disabled={!link.connected}
              title={f.package} onClick={() => void launchApp(f.package)}>{f.label}</button>
          ))}
        </div>
      )}
      {/* Favorites picker (Samsung Flow: pin up to 8 apps inside the mirror). */}
      {showFavs && (
        <div className="fav-picker">
          <div className="fav-picker-head">
            <strong>{T("favorites")}</strong>
            <span>{T("favorites_hint")}</span>
            <button className="btn sm ghost" onClick={() => setShowFavs(false)}>{T("favorites_close")}</button>
          </div>
          {apps.length === 0 ? (
            <p className="desc">{T("favorites_empty")}</p>
          ) : (
            <div className="fav-picker-list">
              {apps.map((a) => {
                const on = favs.some((f) => f.package === a.package);
                return (
                  <button key={a.package} className={`fav-row${on ? " on" : ""}`} onClick={() => toggleFav(a)}
                    aria-pressed={on} disabled={!on && favs.length >= 8}>
                    <span className="fav-row-label">{a.label}</span>
                    <span className="fav-row-pkg">{a.package}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      )}
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
        {/* Samsung Flow parity: the rest of the Smart View control bar. */}
        <div className="screen-rail-group">
          <button className={`rail-icon${dnd ? " active" : ""}`} disabled={!link.connected} onClick={toggleDnd}
            title={dnd ? T("dnd_on") : T("dnd")} aria-label={dnd ? T("dnd_on") : T("dnd")}
            aria-pressed={dnd}>{dnd ? <IconBellOff /> : <IconBell />}</button>
          <button className="rail-icon" disabled={!link.connected || !frame} onClick={captureShot}
            title={T("capture")} aria-label={T("capture")}><IconCamera /></button>
          <button className={`rail-icon${showFavs ? " active" : ""}`} disabled={!link.connected} onClick={openFavPicker}
            title={T("favorites")} aria-label={T("favorites")} aria-pressed={showFavs}><IconPin /></button>
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
              {/* A canvas has no object-fit: its bitmap stretches to the
                  element box. contain = keep the intrinsic aspect and fit
                  inside (max-*); fill = stretch to cover, the old crop. */}
              <canvas
                ref={paintRef}
                className="paint"
                draggable={false}
                style={fill
                  ? { width: "100%", height: "100%" }
                  : { maxWidth: "100%", maxHeight: "100%" }}
              />
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
  /* LocalSend's target card: the device a send would go to, and a state line.
     When nothing is linked, name the likely cause first (same Wi-Fi) instead
     of leaving the user with a dead dropzone — then offer a way to fix it. */
  const target = props.devices.find((d) => d.id === link.device_id);

  const needRecipient = () => {
    toast(extra(lang, "sharePickRecipient"), "err");
    props.setView("devices");
  };

  /* DOM dropzone: Tauri's WebView never delivers real paths here, so the
     native window-level drop (initNativeDrop → queueFile) is the real path.
     The dropzone only collects Browser paths when running in dev mode. */
  const onDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (!link.connected) { needRecipient(); return; }
    const list = Array.from(e.dataTransfer.files);
    if (!list.length) return;
    for (const f of list) {
      const path = (f as unknown as { path?: string }).path;
      if (!path) { toast(`${f.name}: drop files onto the app window (Tauri handles it)`, "err"); continue; }
      queueFile(path);
    }
  };

  const browse = async () => {
    if (!link.connected) { needRecipient(); return; }
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
        {link.connected && target ? (
          <div className="share-target">
            <span className="share-target-label">{extra(lang, "shareTarget")}</span>
            <button
              className="btn sm ghost"
              onClick={() => props.setView("devices")}
              title={extra(lang, "sharePickRecipient")}
            >
              {target.name}
            </button>
            <span className="share-target-state">{extra(lang, "shareConnected")}</span>
          </div>
        ) : (
          <div className="share-target off">
            <span className="share-target-label">{extra(lang, "shareTarget")}</span>
            <span className="share-target-none">{extra(lang, "shareTargetNone")}</span>
            <span className="share-target-hint">{extra(lang, "shareSameWifi")}</span>
            <button className="btn sm ghost" onClick={() => props.setView("devices")}>
              {extra(lang, "shareTroubleshoot")}
            </button>
          </div>
        )}
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
      <ShareHistory lang={lang} />
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
  // Per-app mute + snooze, persisted. `muted` is a hard block list;
  // `snoozed` maps app → epoch-ms the suppression lifts.
  const [muted, setMuted] = useState<string[]>(() => {
    try { return JSON.parse(localStorage.getItem("lynko-muted-apps") ?? "[]"); } catch { return []; }
  });
  const [snoozed, setSnoozed] = useState<Record<string, number>>(() => {
    try { return JSON.parse(localStorage.getItem("lynko-snoozed-apps") ?? "{}"); } catch { return {}; }
  });
  useEffect(() => { try { localStorage.setItem("lynko-muted-apps", JSON.stringify(muted)); } catch { /* quota */ } }, [muted]);
  useEffect(() => { try { localStorage.setItem("lynko-snoozed-apps", JSON.stringify(snoozed)); } catch { /* quota */ } }, [snoozed]);
  const isMuted = (app: string) => isAppSuppressed(app, muted, snoozed);
  const toggleMute = (app: string) => {
    setMuted((m) => m.includes(app) ? m.filter((a) => a !== app) : [...m, app]);
    // Muting also clears any stale snooze for the app.
    setSnoozed((z) => { const { [app]: _, ...rest } = z; return rest; });
  };
  const snooze = (app: string, ms: number) => {
    setSnoozed((z) => ({ ...z, [app]: Date.now() + ms }));
  };
  // Apps seen this session, most-recent first — the mute/snooze surface.
  const seenApps = [...new Set(notes.map((n) => n.app))];
  const visible = notes.filter((n) => !isMuted(n.app));

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
      {seenApps.length > 0 && (
        <div className="card notif-apps">
          <div className="set-row">
            <div className="what">
              <strong>{T("notif_filter_apps")}</strong>
              <span>{T("notif_filter_hint")}</span>
            </div>
          </div>
          {seenApps.map((app) => (
            <div className="set-row notif-app" key={app}>
              <div className="what"><strong>{app}</strong></div>
              <div className="row">
                <button
                  className="ghost sm"
                  onClick={() => snooze(app, 60 * 60 * 1000)}
                  title={T("notif_snooze_1h")}
                >
                  1h
                </button>
                <button
                  className="ghost sm"
                  onClick={() => snooze(app, 24 * 60 * 60 * 1000)}
                  title={T("notif_snooze_tomorrow")}
                >
                  {T("notif_snooze_tomorrow_short")}
                </button>
                <button
                  className={muted.includes(app) ? "btn sm on" : "btn sm"}
                  onClick={() => toggleMute(app)}
                  aria-pressed={muted.includes(app)}
                >
                  {muted.includes(app) ? T("notif_unmute") : T("notif_mute")}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      <div className="card">
        {visible.length === 0 ? <p className="empty-inline">{T("no_notifications_yet")}</p> :
          visible.map((n) => {
            // Same regex as the incoming-notification toast: the card shows
            // Copy code for anything the toast would have offered.
            const otp = otpOf(`${n.title} ${n.body}`);
            return (
            <div className="note-row" key={n.id}>
              <button
                className="note-dismiss"
                onClick={() => props.dismissNote(n)}
                title={extra(lang, "dismiss")}
                aria-label={extra(lang, "dismiss")}
              >✕</button>
              <div className="note-app">{n.app}</div>
              <p className="note-title">{n.title}</p>
              <p className="note-body">{n.body}</p>
              <div className="note-acts">
                {otp && (
                  <button className="btn sm ghost" onClick={() => void props.copyText(otp)}>
                    {extra(lang, "copyCode")} <span className="otp-code">{otp}</span>
                  </button>
                )}
                {link.connected && <button className="ghost" onClick={() => setReplyTo(n)}>{T("reply")}</button>}
              </div>
            </div>
            );
          })
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
      {/* NectarLink parity: every paired phone carries its own allow-list.
          Persisted under lynko-caps-<device-id>; a future track consults it. */}
      <DeviceCapsCard lang={lang} devices={props.devices} />
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
      <UpdateSection lang={lang}/>
      <QualitySection lang={lang}/>
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

/* Per-device feature switches (Settings › Devices card). Flat rows with the
   stock hairline toggle — no rounded cards per row. Values live in
   localStorage only; nothing on the link path reads them yet. */
function DeviceCapsCard({ lang, devices }: { lang: Lang; devices: Device[] }) {
  // localStorage is the store, so a toggle only needs a re-render — no copy
  // of the caps tree to keep in sync (devices arrive async via discovery).
  const [, force] = useState(0);
  const paired = devices.filter((d) => d.paired);
  const setCap = (id: string, key: CapKey, on: boolean) => {
    try { localStorage.setItem(`lynko-caps-${id}`, JSON.stringify({ ...readCaps(id), [key]: on })); } catch { /* quota */ }
    force((n) => n + 1);
  };
  return (
    <div className="card">
      <div className="set-row">
        <div className="what"><strong>{extra(lang, "deviceCapsTitle")}</strong><span>{extra(lang, "deviceCapsHint")}</span></div>
      </div>
      {paired.length === 0 && <p className="empty-inline">{extra(lang, "deviceCapsEmpty")}</p>}
      {paired.map((d) => {
        const caps = readCaps(d.id);
        return (
          <div key={d.id} className="cap-device">
            <div className="cap-device-name">{d.name}</div>
            {CAP_KEYS.map((k) => (
              <div className="set-row cap-row" key={k}>
                <div className="what"><strong>{extra(lang, CAP_LABELS[k])}</strong></div>
                <button
                  className={caps[k] ? "toggle on" : "toggle"}
                  onClick={() => setCap(d.id, k, !caps[k])}
                  role="switch" aria-checked={caps[k]} aria-label={`${d.name}: ${extra(lang, CAP_LABELS[k])}`}
                />
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Bits                                                                 */
/* ------------------------------------------------------------------ */

function PageHead({ title, sub }: { title: string; sub: string }) {
  return <div className="pagehead"><h1>{title}</h1><p className="sub">{sub}</p></div>;
}
