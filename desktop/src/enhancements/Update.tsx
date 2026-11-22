import { invoke } from "@tauri-apps/api/core";
import { useEffect, useState } from "react";
import iconUrl from "../../src-tauri/icons/128x128.png";
import type { Lang } from "../locales";
import { text } from "./strings";
import { type UpdateInfo, autoCheck, markSeen, seenVersion, setAutoCheck, useUpdateCheck } from "./updater";

const gt = (lang: Lang, k: Parameters<typeof text>[1]) => text(lang, k);

export function UpdateSection({ lang }: { lang: Lang }) {
  const [auto, setAuto] = useState(autoCheck);
  const { info, busy, error, check } = useUpdateCheck(false);
  const status = error ? gt(lang, "checkFailed") : info?.has_update ? gt(lang, "updateAvailable") : info ? gt(lang, "uptodate") : gt(lang, "updatesDesc");

  return (
    <>
      <div className="card">
        <div className="set-row">
          <div className="what">
            <strong>{gt(lang, "updates")}</strong>
            <span>{info?.has_update ? `${status} · ${info.latest_version}` : status}</span>
          </div>
          <div className="update-actions">
            <span className="ver">{info ? `v${info.current_version}` : ""}</span>
            <button className="btn sm" disabled={busy} onClick={() => void check()}>
              {busy ? "…" : gt(lang, "checkNow")}
            </button>
          </div>
        </div>
        {error && <div className="update-err" role="alert">{gt(lang, "checkFailed")} — {gt(lang, "checkOffline")}</div>}
        <div className="set-row">
          <div className="what"><strong>{gt(lang, "autoCheck")}</strong></div>
          <button
            className={auto ? "toggle on" : "toggle"}
            onClick={() => { setAuto(!auto); setAutoCheck(!auto); }}
            role="switch" aria-checked={auto} aria-label={gt(lang, "autoCheck")}
          />
        </div>
      </div>
    </>
  );
}

/* Mounted at the app root, not in settings, so a new release is announced
 * wherever the user happens to be. */
export function UpdatePrompt({ lang }: { lang: Lang }) {
  const { info } = useUpdateCheck();
  const [show, setShow] = useState(false);
  // dev: #update-demo previews the modal without a real release.
  const [demoOn, setDemoOn] = useState(() => location.hash === "#update-demo");

  // Announce a newer version this install has not seen before.
  useEffect(() => {
    if (info?.has_update && info.latest_version && info.latest_version !== seenVersion()) setShow(true);
  }, [info]);

  // Or the user just installed an update: running version is ahead of last seen.
  useEffect(() => {
    const seen = seenVersion();
    if (!seen || !info || info.has_update) return;
    const rank = (v: string) => v.split(".").map(Number).slice(0, 3);
    const [a1, a2, a3] = rank(info.current_version);
    const [b1, b2, b3] = rank(seen);
    if ([a1, a2, a3].every((n) => typeof n === "number") && [b1, b2, b3].every((n) => typeof n === "number")
      && (a1! > b1! || (a1 === b1 && a2! > b2!) || (a1 === b1 && a2 === b2 && a3! > b3!))) setShow(true);
  }, [info]);

  const close = () => {
    if (info?.latest_version) markSeen(info.latest_version);
    setShow(false);
    setDemoOn(false);
  };

  const modalInfo = info ?? (demoOn ? DEMO_INFO : null);
  if (!modalInfo || (!show && !demoOn)) return null;
  return <UpdateModal lang={lang} info={modalInfo} onClose={close} />;
}

/* Release card: details on the start side, version panel on the end side. */

const splitNotes = (raw: string) => {
  const out: string[] = [];
  for (const line of raw.replace(/\r/g, "").split("\n")) {
    const t = line.replace(/^\s*(?:[-*+]\s+|\d+\.\s+|#{1,6}\s+)/, "").trim();
    if (!t || /^[-=_*]{3,}$/.test(t)) continue;
    if (/^(\*\*)?full changelog(\*\*)?\s*:/i.test(t)) continue;
    out.push(t);
  }
  return out;
};

const rich = (line: string) =>
  line.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith("**") && part.endsWith("**") && part.length > 4
      ? <strong key={i}>{part.slice(2, -2)}</strong>
      : <span key={i}>{part}</span>,
  );

function UpdateModal({ lang, info, onClose }: { lang: Lang; info: UpdateInfo; onClose: () => void }) {
  const [phase, setPhase] = useState<
    { kind: "idle" } | { kind: "downloading"; got: number; total: number } | { kind: "installing" }
  >({ kind: "idle" });
  const [err, setErr] = useState("");

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const download = async () => {
    // Nothing runnable on this platform: the release page is the only route.
    if (!info.download_url) {
      try {
        await invoke("open_external", { url: info.html_url });
      } catch {
        window.open(info.html_url, "_blank", "noopener");
      }
      onClose();
      return;
    }
    setErr("");
    setPhase({ kind: "downloading", got: 0, total: 0 });
    let un: (() => void) | undefined;
    try {
      const { listen } = await import("@tauri-apps/api/event");
      un = await listen<{ downloaded: number; total: number }>("update_progress", (e) =>
        setPhase({ kind: "downloading", got: e.payload.downloaded, total: e.payload.total }),
      );
      const path = await invoke<string>("download_update", { url: info.download_url });
      setPhase({ kind: "installing" });
      await invoke("run_update", { path });
    } catch (e) {
      setErr(String(e));
      setPhase({ kind: "idle" });
    } finally {
      un?.();
    }
  };

  const notes = splitNotes(info.release_notes || "");
  const rtl = lang === "fa";
  const pct = phase.kind === "downloading" && phase.total
    ? Math.min(100, Math.round((phase.got / phase.total) * 100))
    : 0;
  const mb = (n: number) => `${(n / 1048576).toFixed(1)} MB`;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="updm"
        dir={rtl ? "rtl" : "ltr"}
        onClick={(e) => e.stopPropagation()}
        role="dialog" aria-modal="true" aria-label={gt(lang, "whatsnew")}
      >
        <div className="updm-main">
          <div className="updm-head">
            <span className="updm-pill">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
              </svg>
              {gt(lang, "updateAvailable")}
            </span>
            <h2>{gt(lang, "whatsnew")}</h2>
            <p className="updm-sub">{info.release_name || `Lynko ${info.latest_version}`}</p>
          </div>

          <div className="updm-notes">
            {notes.length ? (
              <ul>
                {notes.map((line, i) => (
                  <li key={i}>
                    <span className="updm-dot" aria-hidden="true" />
                    <span>{rich(line)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="updm-empty">{info.published_at ? `${gt(lang, "published")} ${info.published_at.slice(0, 10)}` : ""}</p>
            )}
          </div>

          <div className="updm-actions">
            {phase.kind === "downloading" ? (
              <>
                <div
                  className="updm-prog" role="progressbar" aria-label={gt(lang, "downloading")}
                  aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}
                >
                  <span style={{ width: `${pct}%` }} />
                </div>
                <span className="updm-prog-lbl">
                  {phase.total ? `${pct}% · ${mb(phase.got)} / ${mb(phase.total)}` : mb(phase.got)}
                </span>
              </>
            ) : (
              <button className="btn primary" onClick={download} disabled={phase.kind === "installing"}>
                {phase.kind === "installing" ? gt(lang, "installing") : gt(lang, "download")}
              </button>
            )}
            {phase.kind === "idle" && <button className="btn ghost" onClick={onClose}>{gt(lang, "later")}</button>}
          </div>
          {!!err && <p className="updm-err">{gt(lang, "downloadFailed")} — {err}</p>}
        </div>

        <aside className="updm-side" aria-hidden="true">
          <span className="updm-glow" />
          <img className="updm-icon" src={iconUrl} alt="" />
          <span className="updm-side-label">{rtl ? "نسخه‌ی جدید" : "Latest version"}</span>
          <span className="updm-ver" dir="ltr">{info.latest_version}</span>
          <span className="updm-cur">
            {rtl ? "الان " : "You are on "}
            <b dir="ltr">{info.current_version}</b>
            {rtl ? " داری" : ""}
          </span>
          {!!info.published_at && <span className="updm-date">{info.published_at.slice(0, 10)}</span>}
        </aside>
      </div>
    </div>
  );
}

const DEMO_INFO: UpdateInfo = {
  current_version: "0.1.0",
  latest_version: "0.2.0",
  release_name: "Lynko 0.2.0",
  release_notes: [
    "**Clipboard sync** — copy on the phone, paste on the desktop, both ways.",
    "- File queue now survives a reconnect and resumes where it stopped",
    "- Notification replies work while the phone screen is locked",
    "- Mirror falls back to H.264 when H.265 is unavailable",
    "",
    "Fixed: window size reset on every launch.",
  ].join("\n"),
  published_at: "2026-11-14T10:00:00Z",
  html_url: "https://github.com/ErfanBagheri404/Lynko/releases/latest",
  download_url: "",
  has_update: true,
};
