import en from "./en.json";
import fa from "./fa.json";

export type Lang = "en" | "fa";

const bundles: Record<Lang, typeof en> = { en, fa };

/**
 * Dot-path lookup, e.g. t("nav_devices").
 * Falls back to English, then the key itself — missing keys never crash.
 */
export function t(lang: Lang, section: "desktop" | "settings" | "toasts" | "phone", key: string): string {
  const b = bundles[lang]?.[section] as Record<string, string> | undefined;
  const v = b?.[key];
  if (typeof v === "string" && v.length > 0) return v;
  const enV = (en[section] as Record<string, string>)[key];
  return typeof enV === "string" ? enV : key;
}

export const LANGS: { id: Lang; label: string; autonym: string }[] = [
  { id: "en", label: "English", autonym: "English" },
  { id: "fa", label: "Persian", autonym: "فارسی" },
];

export const isRTL = (lang: Lang) => lang === "fa";
