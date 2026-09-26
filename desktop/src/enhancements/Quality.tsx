import { useEffect, useState } from "react";
import type { Lang } from "../locales";
import { text } from "./strings";

type QualityKey = "q_eco" | "q_balanced" | "q_sharp" | "q_native";

type Preset = { id: string; maxWidth: number; quality: number };
const PRESETS: Preset[] = [
  { id: "eco", maxWidth: 360, quality: 50 },
  { id: "balanced", maxWidth: 540, quality: 65 },
  { id: "sharp", maxWidth: 720, quality: 78 },
  { id: "native", maxWidth: 1080, quality: 85 },
];

const KEY = "lynko-mirror-quality";
export const savedQuality = () => localStorage.getItem(KEY) ?? "balanced";
/** Preset → [width px, jpeg quality]. Kept here so the Settings card and
 *  the mirror start path never drift apart. */
export const presetDims = (id: string): [number, number] => {
  const p = PRESETS.find((x) => x.id === id) ?? PRESETS[1];
  return [p.maxWidth, p.quality];
};

let invokeFn: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>;
try {
  const core = await import("@tauri-apps/api/core");
  invokeFn = core.invoke;
} catch {
  invokeFn = async () => { throw new Error("no backend"); };
}

export function QualitySection({ lang }: { lang: Lang }) {
  const [cur, setCur] = useState(savedQuality);
  const [sent, setSent] = useState(savedQuality);

  // Push the saved preset when a fresh link starts. The parent remounts this
  // section on every connect (key={gen}), so mount == "new session".
  useEffect(() => {
    const p = PRESETS.find((x) => x.id === savedQuality()) ?? PRESETS[1];
    invokeFn("set_quality", { maxWidth: p.maxWidth, quality: p.quality }).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const apply = (p: Preset) => {
    setCur(p.id);
    localStorage.setItem(KEY, p.id);
    invokeFn("set_quality", { maxWidth: p.maxWidth, quality: p.quality })
      .then(() => setSent(p.id))
      .catch(() => {});
  };

  return (
    <div className="card quality-card">
      <div className="set-row">
        <div className="what">
          <strong>{text(lang, "quality")}</strong>
          <span>{text(lang, "qualityDesc")}</span>
        </div>
      </div>
      <div className="quality-row" role="radiogroup" aria-label={text(lang, "quality")}>
        {PRESETS.map((p) => (
          <button
            key={p.id}
            role="radio"
            aria-checked={cur === p.id}
            className={cur === p.id ? "qbtn on" : "qbtn"}
            onClick={() => apply(p)}
            title={`${p.maxWidth}p · q${p.quality}${sent === p.id ? "" : " · …"}`}
          >
            {text(lang, (`q_${p.id}` as QualityKey))}
          </button>
        ))}
      </div>
    </div>
  );
}
