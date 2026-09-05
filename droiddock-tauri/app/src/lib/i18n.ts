import { useSyncExternalStore } from "react";
// <locale-imports>
import { ar } from "../locales/ar";
import { bn } from "../locales/bn";
import { de } from "../locales/de";
import { es } from "../locales/es";
import { fr } from "../locales/fr";
import { hi } from "../locales/hi";
import { id } from "../locales/id";
import { it } from "../locales/it";
import { ja } from "../locales/ja";
import { ko } from "../locales/ko";
import { pt } from "../locales/pt";
import { ru } from "../locales/ru";
import { tr } from "../locales/tr";
import { vi } from "../locales/vi";
import { zh } from "../locales/zh";
import { zh_Hant } from "../locales/zh_Hant";
// </locale-imports>

/// Translation, with **English as the key**.
///
/// # Why the key is the English string
///
/// The alternative — `t("settings.system.health.title")` — means inventing and
/// maintaining a name for every one of several hundred strings, keeping an
/// `en.json` in sync with those names, and getting a raw key on screen the
/// moment one drifts. Using the English text itself removes all three: there is
/// no English catalog to desync, a missing translation degrades to correct
/// English rather than to `settings.system.health.title`, and reading the call
/// site tells you exactly what the user sees.
///
/// The cost is real and worth naming: **editing English copy orphans its
/// translations**, because the key changed. `npm run i18n:check` reports
/// orphans and gaps, so that shows up as a listed diff rather than a silent
/// gap.
///
/// # Locale resolution
///
/// `"system"` follows the OS through the webview's own `navigator.language`,
/// which in a Tauri window is the macOS preferred language — no plugin needed.
/// An explicit choice overrides it and lives in `localStorage`, the same place
/// the appearance preference lives, because it is a per-Mac display preference
/// and not part of the pairing config the phone reads.

/// **A local named `t` shadows this.** The codebase uses `t` as a loop variable
/// in a number of places (`items.map((t) => …)`), and a translated string added
/// inside one of those bodies calls the local instead. TypeScript rejects it —
/// a `SmsThread` is not callable — so it fails at build time rather than in
/// front of a user, but the error names the wrong problem. Rename the local.
export type Catalog = Record<string, string>;

/// Every locale this build can display, in the order the picker lists them.
///
/// English has no catalog on purpose: it *is* the keys. **Adding a language is
/// one file under `src/locales/` and one line here** — no code changes
/// anywhere else, which was the entire point of keying on English.
///
/// Only English ships today. The picker in Settings is shown regardless and
/// says so in its hint — a control that appears only once a translation exists
/// leaves "where is the language setting?" with no answer. It fills itself the
/// moment a second catalog is added here.
///
/// ```ts
/// import { de } from "../locales/de";
/// { tag: "de", label: "Deutsch", catalog: de },
/// ```
export const LOCALES: { tag: string; label: string; catalog?: Catalog; rtl?: boolean }[] = [
  // <locale-list>
  { tag: "en", label: "English" },
  { tag: "ar", label: "العربية", catalog: ar, rtl: true },
  { tag: "bn", label: "বাংলা", catalog: bn },
  { tag: "de", label: "Deutsch", catalog: de },
  { tag: "es", label: "Español", catalog: es },
  { tag: "fr", label: "Français", catalog: fr },
  { tag: "hi", label: "हिन्दी", catalog: hi },
  { tag: "id", label: "Bahasa Indonesia", catalog: id },
  { tag: "it", label: "Italiano", catalog: it },
  { tag: "ja", label: "日本語", catalog: ja },
  { tag: "ko", label: "한국어", catalog: ko },
  { tag: "pt", label: "Português", catalog: pt },
  { tag: "ru", label: "Русский", catalog: ru },
  { tag: "tr", label: "Türkçe", catalog: tr },
  { tag: "vi", label: "Tiếng Việt", catalog: vi },
  { tag: "zh", label: "简体中文", catalog: zh },
  { tag: "zh-Hant", label: "繁體中文", catalog: zh_Hant },
  // </locale-list>
];

const STORAGE_KEY = "droiddock.locale";

/// `"system"` or a tag from [`LOCALES`].
export type LocaleChoice = string;

function stored(): LocaleChoice {
  try {
    return localStorage.getItem(STORAGE_KEY) || "system";
  } catch {
    // Private windows and cleared site data both throw rather than return null.
    return "system";
  }
}

/// The language actually in use, resolving `"system"` against the OS.
///
/// Matches on the primary subtag, so `hi-IN` finds `hi`. Anything unrecognised
/// falls through to English rather than to a partially-populated catalog.
export function activeTag(choice: LocaleChoice = stored()): string {
  const wanted = choice === "system" ? navigator.language || "en" : choice;
  const primary = wanted.split("-")[0].toLowerCase();
  return LOCALES.some((l) => l.tag === primary) ? primary : "en";
}

function catalog(): Catalog | undefined {
  return LOCALES.find((l) => l.tag === activeTag())?.catalog;
}

/// Whether the active language is written right-to-left.
export function isRtl(choice: LocaleChoice = stored()): boolean {
  return LOCALES.find((l) => l.tag === activeTag(choice))?.rtl === true;
}

/**
 * Put the document's language and direction on `<html>`.
 *
 * `dir` is what makes the layout mirror. The app's Tailwind classes are logical
 * (`ms-`/`me-`/`ps-`/`pe-`/`start-`/`end-`/`text-start`) rather than physical,
 * so margins, padding, absolute offsets and text alignment all follow this
 * attribute — there is no second RTL stylesheet to keep in sync.
 *
 * Called once at startup as well as on every change: a stored Arabic
 * preference has to apply to the first frame, not after the first toggle.
 */
export function applyDocumentLocale(): void {
  const el = document.documentElement;
  el.lang = activeTag();
  el.dir = isRtl() ? "rtl" : "ltr";
}

// ── Change notification ──────────────────────────────────────────────────
// A locale change has to re-render the whole tree, and every component reads
// through `useT()` rather than importing `t` directly for exactly that reason.

const listeners = new Set<() => void>();
let version = 0;

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function snapshot(): number {
  return version;
}

/// Change the language. Persisted, and every subscribed component re-renders.
export function setLocale(choice: LocaleChoice): void {
  try {
    if (choice === "system") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    // Non-persistent is still better than not applying the change at all.
  }
  version += 1;
  applyDocumentLocale();
  listeners.forEach((fn) => fn());
}

/// The stored choice, which is what the picker shows — `"system"` stays
/// `"system"` rather than resolving, so the row reads "System" and follows the
/// Mac if its language changes.
export function localeChoice(): LocaleChoice {
  return stored();
}

/**
 * Translate `text`, substituting `{name}` placeholders from `vars`.
 *
 * Untranslated strings return unchanged, which is the whole point of keying on
 * English: a gap in a catalog shows the user correct English.
 */
export function t(text: string, vars?: Record<string, string | number>): string {
  const out = catalog()?.[text] ?? text;
  if (!vars) return out;
  return out.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in vars ? String(vars[name]) : whole,
  );
}

/**
 * Singular/plural, chosen by `count`.
 *
 * Deliberately two full English strings rather than a suffix rule: English
 * plurals are not always "+s" ("1 entry"/"2 entries"), and a language whose
 * plural rules differ from English needs to translate both forms independently
 * anyway. `{n}` in either string is replaced with `count`.
 */
export function tn(one: string, other: string, count: number, vars?: Record<string, string | number>): string {
  return t(count === 1 ? one : other, { n: count, ...vars });
}

/**
 * `t` for components.
 *
 * Returns the same function every render, but subscribes the component to
 * locale changes — so picking a language repaints the app instead of leaving
 * the old strings until something else happens to re-render.
 */
export function useT(): typeof t {
  useSyncExternalStore(subscribe, snapshot, snapshot);
  return t;
}

/// Same, for the plural helper.
export function useTn(): typeof tn {
  useSyncExternalStore(subscribe, snapshot, snapshot);
  return tn;
}
