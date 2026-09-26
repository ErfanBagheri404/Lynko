/**
 * Desktop keyboard → phone key mapping. Pure so it can be unit-tested; the
 * mirror view calls it on every keydown. Output names are the SNAKE_CASE
 * keycodes LinkService understands — NEVER DOM names like "Escape": the
 * phone rejects unknown names with an input_error toast.
 *
 * Design rules (each one is a bug we refuse to ship):
 *  - A phone has no modifier-held state. Ctrl/Alt/Meta + printable must NOT
 *    fall through to "type the bare character" — that types a character the
 *    user never asked for. Such combos are dropped, and `kind` says so.
 *  - Shift IS meaningful: Shift+a is just "A", so shift is ignored for
 *    printables and the uppercase char is typed.
 *  - Ctrl/Cmd+V is the desktop→phone clipboard paste and is handled by the
 *    caller before this map is consulted.
 *  - Unknown non-printable keys (F5, Insert, …) are dropped rather than sent
 *    as a name the phone will reject with an input_error toast.
 *  - Home/End become MOVE_HOME/MOVE_END (caret in the focused field) — NOT
 *    HOME (the Android launcher button, which the rail already sends).
 */

/** @typedef {{kind:"text", text:string}|{kind:"key", key:string}|{kind:"drop", reason:"modifier"|"unknown"}} KeyAction */

/** Non-printable keys with a real phone equivalent. */
const NAMED = {
  Enter: "ENTER",
  Return: "ENTER",
  Backspace: "BACKSPACE",
  Delete: "DEL",
  Escape: "BACK",
  Esc: "BACK",
  Tab: "TAB",
  ArrowUp: "DPAD_UP",
  ArrowDown: "DPAD_DOWN",
  ArrowLeft: "DPAD_LEFT",
  ArrowRight: "DPAD_RIGHT",
  Home: "MOVE_HOME",
  End: "MOVE_END",
  PageUp: "PAGE_UP",
  PageDown: "PAGE_DOWN",
};

/**
 * @param {{key:string, ctrlKey?:boolean, metaKey?:boolean, altKey?:boolean, shiftKey?:boolean}} ev
 * @returns {KeyAction}
 */
export function mapKey(ev) {
  const { key } = ev;
  if (!key) return { kind: "drop", reason: "unknown" };

  // Ctrl/Alt/Meta are accelerators (incl. the global hotkeys), never text.
  const modified = !!(ev.ctrlKey || ev.metaKey || ev.altKey);

  if (key.length === 1) {
    if (modified) return { kind: "drop", reason: "modifier" };
    return { kind: "text", text: key };
  }

  const named = NAMED[key];
  if (!named) return { kind: "drop", reason: "unknown" };
  // Shift+Tab is "backwards focus" on desktop; the phone has no equivalent
  // and a bare TAB would move focus forwards — the opposite of what the user
  // pressed. Drop it honestly instead of guessing.
  if (key === "Tab" && ev.shiftKey) return { kind: "drop", reason: "modifier" };
  return { kind: "key", key: named };
}
