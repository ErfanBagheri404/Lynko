// Mirror of lynko-core crate types — kept hand-aligned until codegen lands.
import type { Capabilities } from "./lynx-types";

export interface Device {
  id: string;
  name: string;
  address: string;
  caps: Capabilities;
  paired: boolean;
  online: boolean;
  /** discovered via mDNS but never paired */
  discovered?: boolean;
}

export type ConnState =
  | { kind: "idle" }
  | { kind: "scanning" }
  | { kind: "pairing"; device: Device; pin: string }
  | { kind: "connected"; device: Device }
  | { kind: "error"; message: string };

export interface ClipItem {
  id: string;
  text: string;
  source: "phone" | "pc";
  at: number;
}

export interface FileItem {
  id: string;
  name: string;
  size: number;
  from: "phone" | "pc";
  at: number;
}

export interface NoteItem {
  id: string;
  app: string;
  title: string;
  body: string;
  at: number;
}

export interface LogEntry {
  at: number;
  msg: string;
}

export const NAV = [
  "devices",
  "screen",
  "clipboard",
  "files",
  "notifications",
  "audio",
  "settings",
] as const;

export type View = (typeof NAV)[number];
