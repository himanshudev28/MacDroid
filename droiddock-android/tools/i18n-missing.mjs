#!/usr/bin/env node
// Finds t("...") / tn("...","...") English keys used in app sources that have no
// translation in ANY locale (generated Locale_<code>.kt or i18n-extra/<code>.json).
// Writes i18n-extra/missing.json = [{key, where}], prints per-locale missing counts.
// Usage: node tools/i18n-missing.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const srcRoot = path.join(root, 'app/src/main/java');
const appDir = path.join(srcRoot, 'com/droiddock/app');
const CODES = 'ar bn de es fr hi id it ja ko pt ru tr vi zh zh_Hant'.split(' ');

function unescapeKt(s) {
  return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, e) => {
    if (e[0] === 'u' && e.length === 5) return String.fromCharCode(parseInt(e.slice(1), 16));
    return { n: '\n', t: '\t', r: '\r', b: '\b', '"': '"', "'": "'", '\\': '\\', $: '$' }[e] ?? e;
  });
}
const STR = String.raw`"((?:[^"\\\n]|\\.)*)"`;

function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

// --- used keys ---
const used = new Map(); // key -> first "file:line"
const reT = new RegExp(String.raw`(?<![\w.])(?:I18n\.)?t\(\s*${STR}`, 'g');
const reTn = new RegExp(String.raw`(?<![\w.])(?:I18n\.)?tn\(\s*${STR}\s*,\s*${STR}`, 'g');
for (const f of walk(srcRoot)) {
  const b = path.basename(f);
  if (/^Locale_|^LocaleExtra_|^I18n\.kt$/.test(b)) continue;
  const text = fs.readFileSync(f, 'utf8');
  const rel = path.relative(root, f);
  const add = (raw, idx) => {
    if (/(?<!\\)\$[A-Za-z_{]/.test(raw)) return; // string template, not static
    const key = unescapeKt(raw);
    if (!used.has(key)) used.set(key, `${rel}:${text.slice(0, idx).split('\n').length}`);
  };
  for (const m of text.matchAll(reT)) add(m[1], m.index);
  for (const m of text.matchAll(reTn)) { add(m[1], m.index); add(m[2], m.index); }
}

// --- known keys per locale ---
function parseKtCatalog(text) {
  const keys = new Set();
  const re = new RegExp(String.raw`${STR}\s+to\s+${STR}`, 'g');
  for (const m of text.matchAll(re)) keys.add(unescapeKt(m[1]));
  return keys;
}
const have = {};
for (const c of CODES) {
  const set = new Set();
  const gf = path.join(appDir, `Locale_${c}.kt`);
  if (fs.existsSync(gf)) for (const k of parseKtCatalog(fs.readFileSync(gf, 'utf8'))) set.add(k);
  const jf = path.join(root, 'i18n-extra', `${c}.json`);
  if (fs.existsSync(jf)) {
    const j = JSON.parse(fs.readFileSync(jf, 'utf8'));
    for (const [k, v] of Object.entries(j)) if (typeof v === 'string' && v.trim()) set.add(k);
  }
  have[c] = set;
}

const missingAll = [];
for (const [key, where] of used) if (CODES.every(c => !have[c].has(key))) missingAll.push({ key, where });
missingAll.sort((a, b) => a.key.localeCompare(b.key));
fs.mkdirSync(path.join(root, 'i18n-extra'), { recursive: true });
fs.writeFileSync(path.join(root, 'i18n-extra/missing.json'), JSON.stringify(missingAll, null, 2) + '\n');

console.log(`used static keys: ${used.size}`);
for (const c of CODES) {
  const n = [...used.keys()].filter(k => !have[c].has(k)).length;
  console.log(`  ${c.padEnd(7)} missing ${n}`);
}
console.log(`missing in ALL locales: ${missingAll.length} -> i18n-extra/missing.json`);
