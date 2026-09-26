export type KeyAction =
  | { kind: "text"; text: string }
  | { kind: "key"; key: string }
  | { kind: "drop"; reason: "modifier" | "unknown" };

export function mapKey(ev: {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
}): KeyAction;
