import { useEffect, useRef, useState } from "react";
import { Lang, t as tr } from "./locales";
import { api } from "./App";
import type { Preferences } from "./enhancements/hooks";

/* First-run allow list. Every setter returns the post-change state from the
   backend and that is what gets rendered — never an optimistic flip. */
type Preflight = { firewall?: boolean; autostart?: boolean };

export default function Onboarding({ lang, prefs, updatePrefs, toast, onDone }: {
  lang: Lang;
  prefs: Preferences;
  updatePrefs: (p: Partial<Preferences>) => void;
  toast: (msg: string, kind?: "info" | "ok" | "err") => void;
  onDone: () => void;
}) {
  const T = (k: string) => tr(lang, "desktop", k);
  const fail = () => toast(tr(lang, "toasts", "ob_failed"), "err");

  const [firewall, setFirewall] = useState(false);
  const [autostart, setAutostart] = useState(false);
  const [busy, setBusy] = useState<string[]>([]);

  // Only the newest read wins: a focus re-read can resolve after a toggle and
  // would otherwise restore the pre-toggle state on screen.
  const seq = useRef(0);
  const apply = (n: number, s: Preflight | null | undefined) => {
    if (n !== seq.current || !s) return;
    if (typeof s.firewall === "boolean") setFirewall(s.firewall);
    if (typeof s.autostart === "boolean") setAutostart(s.autostart);
  };

  useEffect(() => {
    const read = () => {
      const n = ++seq.current;
      api.invoke<Preflight>("preflight_state").then((s) => apply(n, s)).catch(fail);
    };
    read();
    // Windows' own firewall / startup screens can be changed while the wizard
    // sits open; re-read on focus so the toggles are not stale (BUG-003).
    window.addEventListener("focus", read);
    return () => window.removeEventListener("focus", read);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const preview = prefs.hidePreview;

  const lock = (id: string) => setBusy((b) => (b.includes(id) ? b : [...b, id]));
  const unlock = (id: string) => setBusy((b) => b.filter((x) => x !== id));

  const turn = async (id: string, cmd: string, on: boolean) => {
    lock(id);
    const n = ++seq.current;
    try {
      apply(n, await api.invoke<Preflight>(cmd, { on }));
    } catch {
      fail();
    } finally {
      unlock(id);
    }
  };

  const toggle = (id: string) => {
    if (id === "firewall") void turn("firewall", "set_firewall", !firewall);
    else if (id === "autostart") void turn("autostart", "set_autostart", !autostart);
    else updatePrefs({ hidePreview: !preview });
  };

  const rows = [
    { id: "firewall", on: firewall, title: T("ob_firewall"), desc: T("ob_firewall_desc"), Icon: IconFirewall },
    { id: "autostart", on: autostart, title: T("ob_autostart"), desc: T("ob_autostart_desc"), Icon: IconAutostart },
    { id: "preview", on: preview, title: T("ob_preview"), desc: T("ob_preview_desc"), Icon: IconPreview },
  ];
  const offCount = rows.filter((r) => !r.on).length;

  // CTA with things off turns them on one after another, then stops — the
  // user continues explicitly (or "Not now" always just continues).
  const runAll = async () => {
    lock("cta");
    if (!firewall) await turn("firewall", "set_firewall", true);
    if (!autostart) await turn("autostart", "set_autostart", true);
    if (!preview) updatePrefs({ hidePreview: true });
    unlock("cta");
  };

  const ctaLabel =
    offCount === 0 ? T("ob_cta_all") :
    offCount === 1 ? T("ob_cta_one") :
    T("ob_cta_n").replace("{n}", String(offCount));

  return (
    <div className="onb">
      <div className="onb-col">
        <h1>{T("ob_title")}</h1>
        <p className="onb-lede">{T("ob_lede")}</p>
        <div className="onb-sect">
          <b>{T("ob_section")}</b>
          <span>{T("ob_count").replace("{n}", String(3 - offCount))}</span>
        </div>
        {rows.map(({ id, on, title, desc, Icon }) => (
          <div className={"onb-prow" + (on ? " done" : "")} key={id}>
            <span className="onb-ico"><Icon /></span>
            <div className="onb-txt">
              <div className="onb-t">{title}</div>
              <div className="onb-d">{desc}</div>
            </div>
            <button
              className={"onb-sw" + (on ? " on" : "") + (busy.includes(id) ? " busy" : "")}
              role="switch"
              aria-checked={on}
              aria-label={title}
              disabled={busy.includes(id)}
              onClick={() => toggle(id)}
            />
          </div>
        ))}
        <div className="onb-sect onb-sect-theme">
          <b>{T("ob_appearance")}</b>
        </div>
        <div className="onb-prow onb-theme">
          <span className="onb-ico"><IconTheme /></span>
          <div className="onb-txt">
            <div className="onb-t">{T("ob_theme")}</div>
            <div className="onb-d">{T("ob_theme_desc")}</div>
          </div>
          <div className="lang-switch">
            {(["system", "dark", "light"] as const).map((th) => (
              <button
                key={th}
                className={prefs.theme === th ? "lang-btn on" : "lang-btn"}
                aria-pressed={prefs.theme === th}
                onClick={() => updatePrefs({ theme: th })}
              >
                {T(th === "system" ? "ob_theme_system" : th === "dark" ? "ob_theme_dark" : "ob_theme_light")}
              </button>
            ))}
          </div>
        </div>
        <div className="onb-act">
          <button
            className="onb-btn"
            disabled={busy.length > 0}
            onClick={() => { if (offCount === 0) onDone(); else void runAll(); }}
          >
            {ctaLabel}
          </button>
          <button className="onb-link" onClick={onDone}>{T("ob_notnow")}</button>
        </div>
        <div className="onb-foot">
          <span className="onb-dot" />
          {T("ob_local_only")}
        </div>
      </div>
    </div>
  );
}

/* Icons: mock's glyphs, 20px, stroke currentColor so .done can light them. */

function IconFirewall() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="14" rx="1" />
      <path d="M3 10h18" />
    </svg>
  );
}

function IconAutostart() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 7h16M4 12h10M4 17h7" />
    </svg>
  );
}

function IconTheme() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
    </svg>
  );
}

function IconPreview() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 5h16v11H4z" />
      <path d="M12 16v4" />
      <path d="M8 20h8" />
      <path d="M9 9h6M9 12h4" />
    </svg>
  );
}
