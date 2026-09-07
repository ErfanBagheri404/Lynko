import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

interface ProtocolInfo {
  protocol_version: number;
  service_type: string;
}

interface Device {
  name: string;
  address: string;
  caps: Record<string, boolean>;
}

const FEATURES = [
  { key: "screen", label: "Live screen" },
  { key: "input", label: "Mouse & keyboard" },
  { key: "clipboard", label: "Clipboard sync" },
  { key: "files", label: "File drag & drop" },
  { key: "notifications", label: "Notifications" },
  { key: "audio", label: "Phone audio" },
] as const;

export default function App() {
  const [info, setInfo] = useState<ProtocolInfo | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [scanning, setScanning] = useState(false);

  useEffect(() => {
    invoke<ProtocolInfo>("protocol_info").then(setInfo).catch(console.error);
  }, []);

  async function scan() {
    setScanning(true);
    try {
      const found = await invoke<Device[]>("discover_devices");
      setDevices(found);
    } finally {
      setScanning(false);
    }
  }

  return (
    <div className={scanning ? "app scanning" : "app"}>
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden="true" />
          <h1>Lynko</h1>
          <span className="tagline">your phone, on your desktop</span>
        </div>
        <span className="proto">
          {info ? `protocol v${info.protocol_version}` : "…"}
        </span>
      </header>

      <main className="content">
        <section className="panel">
          <div className="panel-head">
            <h2>Phones on this network</h2>
            <button onClick={scan} disabled={scanning}>
              {scanning ? "Scanning…" : "Scan"}
            </button>
          </div>

          {devices.length === 0 ? (
            <div className="empty">
              <p>No phones yet.</p>
              <p>
                Install the Lynko app on your Android phone, open it once, and
                it appears here automatically.
              </p>
            </div>
          ) : (
            <ul className="devices">
              {devices.map((d) => (
                <li key={d.address} className="device">
                  <span className="dot" aria-hidden="true" />
                  <div>
                    <strong>{d.name}</strong>
                    <span className="addr">{d.address}</span>
                  </div>
                  <button className="pair">Pair</button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel quiet">
          <h2>What connects</h2>
          <ul className="features">
            {FEATURES.map((f) => (
              <li key={f.key}>{f.label}</li>
            ))}
          </ul>
          <p className="fine">
            Everything runs on your local network. No cloud, no account, no
            USB debugging.
          </p>
        </section>
      </main>
    </div>
  );
}
