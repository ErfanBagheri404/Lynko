import { useCallback, useEffect, useState } from "react";

export type UpdateInfo = {
  current_version: string;
  latest_version: string;
  release_name: string;
  release_notes: string;
  published_at: string;
  html_url: string;
  has_update: boolean;
};

const SEEN_KEY = "lynko-seen-version";
const AUTO_KEY = "lynko-update-autocheck";

let invokeFn: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>;
try {
  const core = await import("@tauri-apps/api/core");
  invokeFn = core.invoke;
} catch {
  invokeFn = async () => { throw new Error("no backend"); };
}

export const seenVersion = () => localStorage.getItem(SEEN_KEY) ?? "";
export const markSeen = (v: string) => localStorage.setItem(SEEN_KEY, v);
export const autoCheck = () => localStorage.getItem(AUTO_KEY) !== "0";
export const setAutoCheck = (on: boolean) => localStorage.setItem(AUTO_KEY, on ? "1" : "0");

export function useUpdateCheck(automatic: boolean) {
  const [info, setInfo] = useState<UpdateInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const check = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      const r = await invokeFn<UpdateInfo>("check_update");
      setInfo(r);
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (!automatic) return;
    const t = setTimeout(() => { void check(); }, 2500);
    return () => clearTimeout(t);
  }, [automatic, check]);

  return { info, busy, error, check };
}
