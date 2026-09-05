#!/usr/bin/env node
/** Print every translatable key, one JSON string per line. The input to
 *  writing a catalog: translating anything not on this list is wasted, and
 *  missing one leaves an English string in a translated UI. */
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
const src = join(dirname(fileURLToPath(import.meta.url)), "..", "src");
const walk = (d) => readdirSync(d, { withFileTypes: true }).flatMap((e) =>
  e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)]);
const q = String.raw`"((?:[^"\\]|\\.)*)"`;
const used = new Set();
for (const f of walk(src).filter((f) => /\.tsx?$/.test(f))) {
  if (f.includes("/locales/") || f.endsWith("/i18n.ts")) continue;
  const code = readFileSync(f, "utf8");
  for (const m of code.matchAll(new RegExp(String.raw`\bt\(\s*${q}`, "g"))) used.add(JSON.parse(`"${m[1]}"`));
  for (const m of code.matchAll(new RegExp(String.raw`\btn\(\s*${q}\s*,\s*${q}`, "g"))) {
    used.add(JSON.parse(`"${m[1]}"`)); used.add(JSON.parse(`"${m[2]}"`));
  }
}
for (const k of [...used].sort()) console.log(JSON.stringify(k));
