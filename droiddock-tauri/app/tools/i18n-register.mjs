#!/usr/bin/env node
/**
 * Rewrite both apps' locale registries from whatever catalogs exist on disk.
 *
 * Registration is two lines per language in two files, in two languages — the
 * kind of edit that is fine once and drifts by the fifth. Generating it from
 * the directory listing means a catalog cannot exist unregistered, or be
 * registered after being deleted.
 */
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const app = join(dirname(fileURLToPath(import.meta.url)), "..");
const androidApp = join(app, "..", "..", "droiddock-android", "app", "src", "main", "java", "com", "droiddock", "app");

/** Endonyms — a language picker lists each language in that language. */
const LABELS = {
  ar: "العربية", bn: "বাংলা", de: "Deutsch", es: "Español", fa: "فارسی",
  fr: "Français", hi: "हिन्दी", id: "Bahasa Indonesia", it: "Italiano",
  ja: "日本語", ko: "한국어", ms: "Bahasa Melayu", nl: "Nederlands",
  pl: "Polski", pt: "Português", ru: "Русский", th: "ไทย", tr: "Türkçe",
  uk: "Українська", ur: "اردو", vi: "Tiếng Việt", zh: "简体中文", zh_Hant: "繁體中文",
};
/** Right-to-left scripts. The apps need to know, not just the translator. */
const RTL = new Set(["ar", "fa", "ur", "he"]);

const tags = readdirSync(join(app, "src", "locales"))
  .filter((f) => f.endsWith(".ts"))
  .map((f) => f.replace(/\.ts$/, ""))
  .sort();

const label = (t) => LABELS[t] ?? t;

// ── Mac ──────────────────────────────────────────────────────────────────
const i18n = join(app, "src", "lib", "i18n.ts");
let ts = readFileSync(i18n, "utf8");
ts = ts.replace(
  /\/\/ <locale-imports>[\s\S]*?\/\/ <\/locale-imports>/,
  `// <locale-imports>\n${tags.map((t) => `import { ${t} } from "../locales/${t}";`).join("\n")}\n// </locale-imports>`,
);
ts = ts.replace(
  /\/\/ <locale-list>[\s\S]*?\/\/ <\/locale-list>/,
  `// <locale-list>\n  { tag: "en", label: "English" },\n` +
    tags
      .map((t) => `  { tag: ${JSON.stringify(t.replace(/_/g, "-"))}, label: ${JSON.stringify(label(t))}, catalog: ${t}${RTL.has(t) ? ", rtl: true" : ""} },`)
      .join("\n") +
    `\n  // </locale-list>`,
);
writeFileSync(i18n, ts);

// ── Android ──────────────────────────────────────────────────────────────
const kt = join(androidApp, "I18n.kt");
let k = readFileSync(kt, "utf8");
k = k.replace(
  /\/\/ <locale-list>[\s\S]*?\/\/ <\/locale-list>/,
  `// <locale-list>\n` +
    tags.map((t) => `        ${JSON.stringify(t.replace(/_/g, "-"))} to catalog_${t},`).join("\n") +
    `\n        // </locale-list>`,
);
writeFileSync(kt, k);

console.log(`registered ${tags.length}: ${tags.join(" ")}`);
