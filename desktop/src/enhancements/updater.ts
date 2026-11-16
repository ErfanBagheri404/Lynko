import { useCallback, useEffect, useSyncExternalStore } from "react";

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

/* One shared result per app: the settings row and the update prompt are
 * mounted in different places and must not each run their own request. */
type State = { info: UpdateInfo | null; busy: boolean; error: string };
let state: State = { info: null, busy: false, error: "" };
const subs = new Set<() => void>();
const put = (next: State) => { state = next; for (const f of subs) f(); };
const snapshot = () => state;
const subscribe = (f: () => void) => { subs.add(f); return () => { subs.delete(f); }; };

export async function checkUpdate() {
  if (state.busy) return;
  put({ ...state, busy: true, error: "" });
  try {
    const r = await invokeFn<UpdateInfo>("check_update");
    put({ info: r, busy: false, error: "" });
  } catch (e) {
    put({ ...state, busy: false, error: String(e) });
  }
}

export function useUpdateCheck(automatic = true) {
  const s = useSyncExternalStore(subscribe, snapshot);

  useEffect(() => {
    if (!automatic || !autoCheck()) return;
    const t = setTimeout(() => { void checkUpdate(); }, 2500);
    return () => clearTimeout(t);
  }, [automatic]);

  const check = useCallback(() => void checkUpdate(), []);
  return { info: s.info, busy: s.busy, error: s.error, check };
}
