import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  IconAudio, IconBattery, IconClip, IconDevices, IconFiles, IconNotes,
  IconPair, IconRefresh, IconScreen, IconSend, IconSettings, Mark,
} from "./icons";
import type { Device, LogEntry, View } from "./state";

/* ------------------------------------------------------------------ */
/* backend bridge                                                       */
/* ------------------------------------------------------------------ */

let invokeFn: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>;
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const core = await import("@tauri-apps/api/core");
  invokeFn = core.invoke;
} catch {
  invokeFn = async () => {
    throw new Error("no backend");
  };
}

export const api = {
  invoke: invokeFn,
};

/* ------------------------------------------------------------------ */
/* app state                                                           */
/* ------------------------------------------------------------------ */

interface ClipItem { id: number; text: string; source: "phone" | "pc"; at: number }
interface FileItem { id: number; name: string; size: number; at: number; status: "queued" | "sent" | "failed" }
interface NoteItem { id: number; app: string; title: string; body: string; at: number }

let uid = 0;
const nid = () => ++uid;

function useApp() {
  const [view, setView] = useState<View>("devices");
  const [scanning, setScanning] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [paired, setPaired] = useState<Device[]>([]);
  const [pairTarget, setPairTarget] = useState<Device | null>(null);
  const [connected, setConnected] = useState<Device | null>(null);
  const [log, setLog] = useState<LogEntry[]>([]);
  const [clipItems, setClipItems] = useState<ClipItem[]>([]);
  const [files, setFiles] = useState<FileItem[]>([]);
  const [notes, setNotes] = useState<NoteItem[]>([]);
  const [battery, setBattery] = useState<{ pct: number; charging: boolean } | null>(null);

  const pushLog = useCallback((msg: string) => {
    setLog((l) => [...l.slice(-200), { at: Date.now(), msg }]);
  }, []);

  return {
    view, setView, scanning, setScanning,
    devices, setDevices, paired, setPaired,
    pairTarget, setPairTarget, connected, setConnected,
    log, pushLog, clipItems, setClipItems,
    files, setFiles, notes, setNotes,
    battery, setBattery,
  };
}

type AppCtx = ReturnType<typeof useApp>;

/* ------------------------------------------------------------------ */
/* shell                                                               */
/* ------------------------------------------------------------------ */

export default function App() {
  const app = useApp();
  return (
    <div className={app.scanning ? "app scanning" : "app"}>
      <Shell app={app} />
    </div>
  );
}

function Shell({ app }: { app: AppCtx }) {
  return (
    <div className="shell">
      <div className="brand">
        <Mark />
        <span className="wordmark">Lynko</span>
      </div>

      <TopBar app={app} />

      <nav className="rail">
        <NavItem app={app} id="devices" label="Devices" Icon={IconDevices} />
        <NavItem app={app} id="screen" label="Screen" Icon={IconScreen} />
        <NavItem app={app} id="clipboard" label="Clipboard" Icon={IconClip} />
        <NavItem app={app} id="files" label="Files" Icon={IconFiles} />
        <NavItem app={app} id="notifications" label="Notifications" Icon={IconNotes} badge={app.notes.length} />
        <NavItem app={app} id="audio" label="Audio" Icon={IconAudio} />
        <NavItem app={app} id="settings" label="Settings" Icon={IconSettings} />
        <div className="rail-foot">v0.1.0 · protocol 1</div>
      </nav>

      <main className="stage">
        {app.view === "devices" && <DevicesView app={app} />}
        {app.view === "screen" && <ScreenView app={app} />}
        {app.view === "clipboard" && <ClipboardView app={app} />}
        {app.view === "files" && <FilesView app={app} />}
        {app.view === "notifications" && <NotificationsView app={app} />}
        {app.view === "audio" && <AudioView app={app} />}
        {app.view === "settings" && <SettingsView app={app} />}
      </main>

      <Inspector app={app} />
      <StatusBar app={app} />
    </div>
  );
}

function NavItem({ app, id, label, Icon, badge }: {
  app: AppCtx; id: View; label: string; Icon: () => JSX.Element; badge?: number;
}) {
  return (
    <button
      className={app.view === id ? "rail-item on" : "rail-item"}
      onClick={() => app.setView(id)}
    >
      <Icon />
      {label}
      {badge != null && badge > 0 && <span className="rail-badge">{badge}</span>}
    </button>
  );
}

/* ------------------------------------------------------------------ */
/* top bar                                                             */
/* ------------------------------------------------------------------ */

function TopBar({ app }: { app: AppCtx }) {
  const live = !!app.connected;
  return (
    <div className="top">
      <button
        className={live ? "conn-chip live" : "conn-chip"}
        onClick={() => !live && app.setView("devices")}
        title={live ? "Connected" : "No phone connected"}
      >
        <span className="dot" />
        {live ? app.connected!.name : "not connected"}
      </button>
      {live && app.battery && (
        <span className="conn-chip">
          <IconBattery />
          {app.battery.pct}%{app.battery.charging ? " charging" : ""}
        </span>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* devices                                                             */
/* ------------------------------------------------------------------ */

function DevicesView({ app }: { app: AppCtx }) {
  const all = useMemo(() => {
    const byAddr = new Map<string, Device>();
    for (const d of [...app.paired, ...app.devices]) byAddr.set(d.address, d);
    return [...byAddr.values()];
  }, [app.devices, app.paired]);

  const scan = async () => {
    app.setScanning(true);
    app.pushLog("mDNS scan started");
    try {
      const found = await api.invoke<Device[]>("discover_devices");
      app.setDevices(found);
      app.pushLog(`scan found ${found.length} device${found.length === 1 ? "" : "s"}`);
    } catch (e) {
      app.pushLog(`scan failed: ${String(e)}`);
    } finally {
      app.setScanning(false);
    }
  };

  return (
    <div className="view">
      <PageHead
        title="The link room"
        sub="Every Lynko phone on this network shows up here. Pair once and it stays remembered."
      />

      <div className="discover">
        <div className="card radar">
          <h2>Discovery radar</h2>
          <p className="desc">Phones advertise themselves over mDNS. Click a ping to pair.</p>
          <div className="radar-world">
            <div className="ring r1" />
            <div className="ring r2" />
            <div className="ring r3" />
            <div className="radar-core" />
            {all.map((d, i) => {
              const n = Math.max(all.length, 1);
              const angle = (i / n) * Math.PI * 2 - Math.PI / 2;
              const radius = 32 + ((i * 37) % 3) * 12;
              const x = 50 + Math.cos(angle) * radius;
              const y = 50 + Math.sin(angle) * radius * 0.82;
              return (
                <button
                  key={d.id}
                  className="ping"
                  style={{ left: `${x}%`, top: `${y}%` }}
                  title={`${d.name} — ${d.paired ? "paired" : "click to pair"}`}
                  onClick={() => {
                    if (!d.paired) {
                      app.setPairTarget(d);
                      app.pushLog(`pairing started: ${d.name}`);
                    } else {
                      app.setConnected(d);
                      app.pushLog(`connect: ${d.name}`);
                    }
                  }}
                >
                  <span className="radar-label" style={{ left: "50%", top: "100%" }}>{d.name}</span>
                </button>
              );
            })}
          </div>
          <div className="radar-foot">
            <strong>{all.length}</strong> phone{all.length === 1 ? "" : "s"} on this network
            <button className="btn sm ghost" style={{ marginLeft: 12 }} onClick={scan} disabled={app.scanning}>
              {app.scanning ? "Scanning…" : "Scan again"}
            </button>
          </div>
        </div>

        <div className="stack">
          <div className="card">
            <h2>Paired phones</h2>
            <p className="desc">Pairing survives restarts on both sides.</p>
            {app.paired.length === 0 ? (
              <p className="empty-inline">Nothing paired yet. Scan and pair your first phone.</p>
            ) : app.paired.map((d) => (
              <div key={d.id} className="devrow">
                <span className={d.online ? "dev-dot on" : "dev-dot off"} />
                <div className="who">
                  <strong>{d.name}</strong>
                  <span className="addr">{d.address}</span>
                </div>
                <div className="act">
                  {d.online && (
                    <button
                      className={app.connected?.id === d.id ? "btn sm" : "btn sm primary"}
                      onClick={() => app.setConnected(app.connected?.id === d.id ? null : d)}
                    >
                      {app.connected?.id === d.id ? "Disconnect" : "Connect"}
                    </button>
                  )}
                  <button
                    className="btn sm ghost"
                    onClick={() => {
                      app.setPaired(app.paired.filter((p) => p.id !== d.id));
                      app.pushLog(`forgot ${d.name}`);
                    }}
                  >
                    Forget
                  </button>
                </div>
              </div>
            ))}
          </div>

          <div className="card">
            <h2>First time on a phone?</h2>
            <ol className="steps">
              <li>Install Lynko from GitHub Releases and open it once.</li>
              <li>Grant screen, input, and notification permissions when asked.</li>
              <li>It appears here. Scan if it doesn't show within a few seconds.</li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* screen                                                              */
/* ------------------------------------------------------------------ */

function ScreenView({ app }: { app: AppCtx }) {
  const live = !!app.connected;
  return (
    <div className="view">
      <PageHead
        title="Live screen"
        sub={live ? "Click the screen to take control. Keyboard goes to the phone." : "Connect a phone to mirror its screen here."}
      />
      <div className="screen-frame">
        <div className="cam" />
        <div className="screen-canvas">
          {live ? (
            <div className="no-signal">
              <strong>Stream starting…</strong>
              MediaProjection consent happens on the phone.
            </div>
          ) : (
            <div className="no-signal">
              <strong>No phone connected</strong>
              Go to Devices, scan, and connect.
            </div>
          )}
        </div>
        <div className="screen-bar">
          <span className="mono-note">{live ? "H.264 · WebRTC · LAN" : "idle"}</span>
        </div>
        <div className="screen-toolbar">
          <button className="btn ghost sm" disabled={!live}>Rotate</button>
          <button className="btn ghost sm" disabled={!live}>Fullscreen</button>
          <button className="btn ghost sm" disabled={!live}>Record</button>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* clipboard                                                           */
/* ------------------------------------------------------------------ */

function ClipboardView({ app }: { app: AppCtx }) {
  const [draft, setDraft] = useState("");

  const pushToPhone = async () => {
    if (!draft.trim()) return;
    const text = draft;
    try {
      await api.invoke("push_clipboard", { text });
      app.setClipItems((c) => [{ id: nid(), text, source: "pc" as const, at: Date.now() }, ...c].slice(0, 30));
      setDraft("");
      app.pushLog(`clipboard → phone (${text.length} chars)`);
    } catch (e) {
      app.pushLog(`clipboard push failed: ${String(e)}`);
    }
  };

  const pullFromPhone = async () => {
    try {
      const t = await api.invoke<string>("pull_clipboard");
      app.setClipItems((c) => [{ id: nid(), text: t, source: "phone" as const, at: Date.now() }, ...c].slice(0, 30));
      app.pushLog(`clipboard ← phone (${t.length} chars)`);
    } catch (e) {
      app.pushLog(`clipboard pull failed: ${String(e)}`);
    }
  };

  return (
    <div className="view">
      <PageHead
        title="Clipboard"
        sub="Your PC and phone share one clipboard while connected. Either direction, instantly."
      />
      <div className="feature-grid">
        <div className="card">
          <h2>Send to phone</h2>
          <p className="desc">Type or paste here; it lands on the phone's clipboard.</p>
          <textarea
            className="field"
            rows={4}
            placeholder="Paste something for the phone…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <div className="pair-actions" style={{ marginTop: 10 }}>
            <button
              className="btn primary"
              onClick={pushToPhone}
              disabled={!app.connected || !draft.trim()}
            >
              <IconSend /> Send
            </button>
          </div>
        </div>
        <div className="card">
          <h2>History</h2>
          <p className="desc">Everything that crossed the link, newest first.</p>
          {app.clipItems.length === 0 ? (
            <p className="empty-inline">Nothing yet. Send your first snippet.</p>
          ) : (
            <div className="clip-history">
              {app.clipItems.map((c) => (
                <div className="clip-item" key={c.id} onClick={() => setDraft(c.text)}>
                  {c.text}
                </div>
              ))}
            </div>
          )}
          <div className="pair-actions" style={{ marginTop: 10 }}>
            <button className="btn" onClick={pullFromPhone} disabled={!app.connected}>
              <IconRefresh /> Pull from phone
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* files                                                               */
/* ------------------------------------------------------------------ */

function FilesView({ app }: { app: AppCtx }) {
  const [dragOver, setDragOver] = useState(false);

  const onDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const fs = Array.from(e.dataTransfer.files);
    if (!fs.length) return;
    for (const f of fs) {
      const item = { id: nid(), name: f.name, size: f.size, at: Date.now(), status: "queued" as const };
      app.setFiles((q) => [item, ...q]);
      app.pushLog(`queued ${f.name} (${fmtSize(f.size)})`);
      if (!app.connected) {
        app.setFiles((q) => q.map((x) => (x.id === item.id ? { ...x, status: "failed" } : x)));
        app.pushLog(`${f.name} failed: no phone connected`);
        continue;
      }
      try {
        await api.invoke("push_file", { name: f.name });
        app.setFiles((q) => q.map((x) => (x.id === item.id ? { ...x, status: "sent" } : x)));
        app.pushLog(`${f.name} sent`);
      } catch (err) {
        app.setFiles((q) => q.map((x) => (x.id === item.id ? { ...x, status: "failed" } : x)));
        app.pushLog(`${f.name} failed: ${String(err)}`);
      }
    }
  };

  return (
    <div className="view">
      <PageHead
        title="Files"
        sub="Drop files onto the phone, pull them back out. No cables, no cloud."
      />
      <div className="card">
        <h2>Drag & drop</h2>
        <p className="desc">
          {app.connected
            ? `Files land on ${app.connected.name}.`
            : "Connect a phone to send files."}
        </p>
        <div
          className={dragOver ? "dropzone over" : "dropzone"}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
        >
          <strong>Drop files to send</strong>
          they travel over the same encrypted link as everything else
        </div>
        {app.files.length > 0 && (
          <div style={{ marginTop: 12 }}>
            {app.files.map((f) => (
              <div className="file-row" key={f.id}>
                <IconFiles />
                <span>{f.name}</span>
                <span className="sz">
                  {fmtSize(f.size)} · {f.status}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* notifications                                                       */
/* ------------------------------------------------------------------ */

function NotificationsView({ app }: { app: AppCtx }) {
  return (
    <div className="view">
      <PageHead
        title="Notifications"
        sub="Phone notifications land here while connected. Read them without picking up the phone."
      />
      <div className="card">
        <h2>Stream</h2>
        <p className="desc">Newest first, from every app the phone allows.</p>
        {app.notes.length === 0 ? (
          <p className="empty-inline">
            No notifications yet. They appear here live once a phone is connected.
          </p>
        ) : (
          app.notes.map((n) => (
            <div className="note-row" key={n.id}>
              <div className="app">{n.app}</div>
              <p className="title">{n.title}</p>
              <p className="body">{n.body}</p>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* audio                                                               */
/* ------------------------------------------------------------------ */

function AudioView({ app }: { app: AppCtx }) {
  const [level, setLevel] = useState(72);
  return (
    <div className="view">
      <PageHead
        title="Phone audio"
        sub="Route the phone's audio through the desktop speakers while connected."
      />
      <div className="card">
        <h2>Output</h2>
        <p className="desc">Media, calls, and system sounds play on this PC.</p>
        <div className="screen-bar">
          <span className="mono-note">
            {app.connected ? "AudioPlaybackCapture · Opus · live" : "idle"}
          </span>
        </div>
        <div className="set-row" style={{ border: 0, paddingTop: 4 }}>
          <div className="what">
            <strong>Desktop volume</strong>
            <span>Output level for phone audio.</span>
          </div>
          <div className="ctl">
            <input
              type="range"
              min={0}
              max={100}
              value={level}
              onChange={(e) => setLevel(Number(e.target.value))}
              style={{ width: 160, accentColor: "var(--signal)" }}
              aria-label="Desktop volume"
            />
          </div>
        </div>
        <div className="screen-toolbar">
          <button className="btn ghost sm" disabled={!app.connected}>Mute phone</button>
          <button className="btn ghost sm" disabled={!app.connected}>Play on phone</button>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* settings                                                            */
/* ------------------------------------------------------------------ */

function SettingsView(_app: { app: AppCtx }) {
  const [autoReconnect, setAutoReconnect] = useState(true);
  const [toasts, setToasts] = useState(true);
  const [hotkeyMirror, setHotkeyMirror] = useState(true);

  return (
    <div className="view">
      <PageHead title="Settings" sub="Lynko remembers everything you turn on." />
      <div className="card">
        <SetRow
          title="Auto-connect paired phones"
          desc="Connect automatically when a paired phone appears on the network."
          on={autoReconnect}
          onToggle={() => setAutoReconnect(!autoReconnect)}
        />
        <SetRow
          title="Desktop notifications"
          desc="Show phone notifications as Windows toasts."
          on={toasts}
          onToggle={() => setToasts(!toasts)}
        />
        <SetRow
          title="Global hotkey for screen"
          desc="Jump to the phone screen from anywhere."
          on={hotkeyMirror}
          onToggle={() => setHotkeyMirror(!hotkeyMirror)}
          extra={<span className="kbd">Ctrl+Shift+L</span>}
        />
      </div>
    </div>
  );
}

function SetRow(props: {
  title: string; desc: string; on: boolean;
  onToggle: () => void; extra?: React.ReactNode;
}) {
  return (
    <div className="set-row">
      <div className="what">
        <strong>{props.title}</strong>
        <span>{props.desc}</span>
      </div>
      {props.extra}
      <button
        className={props.on ? "toggle on" : "toggle"}
        onClick={props.onToggle}
        role="switch"
        aria-checked={props.on}
        aria-label={props.title}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* inspector                                                           */
/* ------------------------------------------------------------------ */

function Inspector({ app }: { app: AppCtx }) {
  const pair = app.pairTarget;
  const [pin, setPin] = useState(["", "", "", ""]);
  const pinRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    setPin(["", "", "", ""]);
  }, [pair]);

  const confirm = () => {
    const code = pin.join("");
    if (code.length < 4 || !pair) return;
    const wasPaired = app.paired.some((x) => x.id === pair.id);
    if (!wasPaired) app.setPaired((p) => [...p, { ...pair, paired: true }]);
    app.setPairTarget(null);
    app.pushLog(`paired ${pair.name} via PIN`);
  };

  return (
    <aside className="insp">
      {pair ? (
        <>
          <h3>Pair</h3>
          <div className="pair-target">
            <strong>{pair.name}</strong>
            <span className="addr">{pair.address}</span>
          </div>
          <p style={{ color: "var(--ink1)", fontSize: 13, margin: "6px 0 0" }}>
            Enter the four digits shown on the phone.
          </p>
          <div className="pin-row">
            {pin.map((v, i) => (
              <input
                key={i}
                ref={(el) => { pinRefs.current[i] = el; }}
                className="pin-cell"
                inputMode="numeric"
                maxLength={1}
                value={v}
                aria-label={`PIN digit ${i + 1}`}
                onChange={(e) => {
                  const nv = e.target.value.replace(/\D/g, "").slice(0, 1);
                  const np = [...pin];
                  np[i] = nv;
                  setPin(np);
                  if (nv && i < 3) pinRefs.current[i + 1]?.focus();
                }}
                onKeyDown={(e) => {
                  if (e.key === "Backspace" && !pin[i] && i > 0) {
                    pinRefs.current[i - 1]?.focus();
                  }
                }}
              />
            ))}
          </div>
          <div className="pair-actions">
            <button className="btn ghost" onClick={() => app.setPairTarget(null)}>Cancel</button>
            <button className="btn primary" onClick={confirm} disabled={pin.join("").length < 4}>
              Pair
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="sect">
            <h3>Connection</h3>
            {app.connected ? (
              <div className="devrow" style={{ background: "var(--card)" }}>
                <span className="dev-dot on" />
                <div className="who">
                  <strong>{app.connected.name}</strong>
                  <span className="addr">connected · {app.connected.address}</span>
                </div>
                <div className="act">
                  <button className="btn sm ghost" onClick={() => app.setConnected(null)}>
                    Drop
                  </button>
                </div>
              </div>
            ) : (
              <p className="empty-inline" style={{ textAlign: "left", padding: "4px 0" }}>
                No phone connected.
              </p>
            )}
            {app.battery && (
              <div className="perm" style={{ marginTop: 10 }}>
                <IconBattery />
                Battery {app.battery.pct}%{app.battery.charging ? " · charging" : ""}
              </div>
            )}
          </div>

          <div className="sect">
            <h3>Phone permissions</h3>
            <div className="perm"><IconScreen /> Screen capture <span className="st wait">on phone</span></div>
            <div className="perm"><IconPair /> Input control <span className="st wait">on phone</span></div>
            <div className="perm"><IconNotes /> Notifications <span className="st wait">on phone</span></div>
            <div className="perm"><IconAudio /> Audio capture <span className="st wait">on phone</span></div>
          </div>

          <div className="sect">
            <h3>Activity</h3>
            <div className="console">
              {app.log.length === 0 ? (
                <div><span className="t">--:--</span>waiting for something to happen…</div>
              ) : (
                app.log.slice(-40).map((l, i) => (
                  <div key={i}>
                    <span className="t">{fmtTime(l.at)}</span>{l.msg}
                  </div>
                ))
              )}
            </div>
          </div>
        </>
      )}
    </aside>
  );
}

/* ------------------------------------------------------------------ */
/* status bar                                                          */
/* ------------------------------------------------------------------ */

function StatusBar({ app }: { app: AppCtx }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const online = app.paired.filter((d) => d.online).length;
  return (
    <footer className="status">
      <span>{app.connected ? <span className="lit">● live</span> : "○ idle"}</span>
      <span>{app.scanning ? "scanning…" : "mDNS idle"}</span>
      <span>{online} online / {app.paired.length} paired</span>
      <span className="sp">LAN · DTLS · {fmtClock(now)}</span>
    </footer>
  );
}

/* ------------------------------------------------------------------ */
/* bits                                                                */
/* ------------------------------------------------------------------ */

function PageHead({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="pagehead">
      <h1>{title}</h1>
      <p className="sub">{sub}</p>
    </div>
  );
}

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

function fmtTime(at: number): string {
  const d = new Date(at);
  return [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map((x) => String(x).padStart(2, "0"))
    .join(":");
}

function fmtClock(at: number): string {
  const d = new Date(at);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
