import { useEffect, useState } from "react";
import type { Lang } from "../locales";
import { text } from "./strings";
import { type UpdateInfo, autoCheck, markSeen, seenVersion, setAutoCheck, useUpdateCheck } from "./updater";

const gt = (lang: Lang, k: Parameters<typeof text>[1]) => text(lang, k);

export function UpdateSection({ lang }: { lang: Lang }) {
  const [auto, setAuto] = useState(autoCheck);
  const { info, busy, error, check } = useUpdateCheck(auto);
  const [show, setShow] = useState(false);

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
  };

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
            {info && <code className="ver">{gt(lang, "version")} {info.current_version}</code>}
            <button className="btn sm" onClick={() => void check()} disabled={busy}>
              {busy ? gt(lang, "checking") : gt(lang, "checkNow")}
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
      {show && info && <UpdateModal lang={lang} info={info} onClose={close} />}
    </>
  );
}

function UpdateModal({ lang, info, onClose }: { lang: Lang; info: UpdateInfo; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const download = () => {
    window.open(info.html_url, "_blank", "noopener");
    onClose();
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal update-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={gt(lang, "whatsnew")}>
        <h2>
          <span className="upd-badge">{info.latest_version}</span>
          {gt(lang, "whatsnew")}
        </h2>
        <p className="desc">{info.release_name || `Lynko ${info.latest_version}`}</p>
        {!!info.published_at && <p className="upd-date">{gt(lang, "published")}: {info.published_at.slice(0, 10)}</p>}
        {!!info.release_notes && <pre className="upd-notes">{info.release_notes.trim()}</pre>}
        <div className="modal-actions">
          <button className="btn ghost" onClick={onClose}>{gt(lang, "later")}</button>
          <button className="btn primary" onClick={download}>{gt(lang, "download")}</button>
        </div>
      </div>
    </div>
  );
}
