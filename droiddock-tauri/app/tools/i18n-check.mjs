#!/usr/bin/env node
/**
 * Reports the two ways an English-keyed catalog goes wrong.
 *
 *   gaps    — a string the UI asks for that a catalog has no entry for.
 *             Harmless at runtime (English shows), so nothing else would ever
 *             tell you.
 *   orphans — a translated string the UI no longer asks for. Almost always the
 *             fingerprint of edited English copy: the key changed, so the
 *             translation silently stopped applying.
 *
 * Orphans are the reason this exists. Keying on English makes a missing
 * translation invisible by design; this is what makes it visible on purpose.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const src = join(dirname(fileURLToPath(import.meta.url)), "..", "src");

/** Every file under src/, recursively. */
function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)],
  );
}

/**
 * String literals passed as the first argument to `t(` — and both of the first
 * two to `tn(`.
 *
 * Deliberately literal-only: a `t(someVariable)` cannot be resolved statically,
 * and pretending otherwise would report false gaps. Those are rare and worth
 * keeping rare.
 */
function keysIn(code) {
  const found = new Set();
  const quoted = String.raw`"((?:[^"\\]|\\.)*)"`;
  for (const m of code.matchAll(new RegExp(String.raw`\bt\(\s*${quoted}`, "g"))) {
    found.add(JSON.parse(`"${m[1]}"`));
  }
  for (const m of code.matchAll(new RegExp(String.raw`\btn\(\s*${quoted}\s*,\s*${quoted}`, "g"))) {
    found.add(JSON.parse(`"${m[1]}"`));
    found.add(JSON.parse(`"${m[2]}"`));
  }
  return found;
}

const files = walk(src).filter((f) => /\.(tsx?|ts)$/.test(f));
const used = new Set();
for (const f of files) {
  // i18n.ts documents the API using `t("…")` in prose; those are examples,
  // not strings the UI asks for.
  if (f.includes("/locales/") || f.endsWith("/i18n.ts")) continue;
  for (const k of keysIn(readFileSync(f, "utf8"))) used.add(k);
}

console.log(`${used.size} translatable strings across ${files.length} files`);

const localeDir = join(src, "locales");
const catalogs = readdirSync(localeDir).filter((f) => f.endsWith(".ts"));
if (catalogs.length === 0) {
  console.log("No catalogs yet — English is the source, so there is nothing to check against.");
  process.exit(0);
}

/**
 * Things that must survive translation, and the reason each is checked.
 *
 * These are the failure modes that a coverage count cannot see: a catalog can
 * be 538/538 and still print `{n}` to the user or reverse the meaning of a
 * direction. Every one of these was a real defect caught in the first pass.
 */
function contentProblems(catalog) {
  const out = [];
  for (const [en, tr] of Object.entries(catalog)) {
    // A dropped placeholder prints its own braces on screen.
    for (const ph of ["{n}", "{name}"]) {
      if (en.includes(ph) && !tr.includes(ph)) out.push(`lost ${ph}: ${JSON.stringify(en).slice(0, 70)}`);
    }
    // "Mac → phone" rephrased as prose loses the direction it exists to state.
    if (en.includes("→") && !tr.includes("→")) {
      out.push(`lost the arrow: ${JSON.stringify(en).slice(0, 70)}`);
    }
    // A translated command or filename is a command that does not run.
    for (const w of ["scrcpy", "Homebrew", "WebKit", ".DS_Store", "AES-256-GCM", "H.265"]) {
      if (en.includes(w) && !tr.includes(w)) out.push(`lost "${w}": ${JSON.stringify(en).slice(0, 70)}`);
    }
    if (!tr.trim()) out.push(`empty: ${JSON.stringify(en).slice(0, 70)}`);
  }
  return out;
}

// Deliberately NOT checked: product names the vendor itself localises. Apple
// ships Finder as 访达 in Simplified Chinese and Google ships Quick Share as
// 快速分享, so a catalog rendering them that way is correct, not broken — while
// Traditional Chinese keeping "Finder" is equally correct. A brand-name check
// would flag all of that as failure, so the list above stops at strings no
// vendor translates: commands, file names and codec identifiers.

let bad = 0;
for (const file of catalogs) {
  const code = readFileSync(join(localeDir, file), "utf8");
  const translated = new Set(
    [...code.matchAll(/^\s*"((?:[^"\\]|\\.)*)"\s*:/gm)].map((m) => JSON.parse(`"${m[1]}"`)),
  );
  const gaps = [...used].filter((k) => !translated.has(k));
  const orphans = [...translated].filter((k) => !used.has(k));

  const content = contentProblems(JSON.parse(
    "{" + (code.match(/^\s*"(?:[^"\\]|\\.)*"\s*:\s*"(?:[^"\\]|\\.)*",?$/gm) ?? [])
      .join("\n").replace(/,\s*$/, "") + "}",
  ));
  console.log(
    `\n${file}: ${translated.size} entries, ${gaps.length} gaps, ${orphans.length} orphans, ` +
      `${content.length} content problems`,
  );
  for (const c of content.slice(0, 10)) console.log(`  content  ${c}`);
  if (content.length) bad = 1;
  for (const k of orphans) console.log(`  orphan   ${JSON.stringify(k)}`);
  for (const k of gaps.slice(0, 20)) console.log(`  gap      ${JSON.stringify(k)}`);
  if (gaps.length > 20) console.log(`  … and ${gaps.length - 20} more gaps`);
  // Orphans fail; gaps do not. A gap shows correct English and is a normal
  // state for a catalog in progress. An orphan means a translation that used to
  // apply and silently stopped.
  if (orphans.length) bad = 1;
}
process.exit(bad);
