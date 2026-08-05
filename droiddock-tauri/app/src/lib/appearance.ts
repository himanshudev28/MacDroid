/// Everything about how the app *looks* that the user gets to choose: theme,
/// glass strength, and whether the accent follows macOS or stays with the
/// app's own warm amber.
///
/// All of it lives in `localStorage`, not `droiddock.json`. The config file is
/// the phone protocol's state — token, port, feature toggles — and a display
/// preference has no business travelling with it. It also means these apply
/// synchronously on load, before the first `invoke()` round-trip resolves, so
/// the app never paints one theme and then flips to another.

export type Theme = "dark" | "light" | "system";
export type AccentSource = "warm" | "system";

/// How the phone card draws its clock.
///
/// · `row`      — hour and minute on one line (the default)
/// · `stacked`  — hour above minute, the lock-screen look AirSync animates to
/// · `mono`     — tabular digits, smaller, with seconds
/// · `minimal`  — time only, no date, quiet weight
/// · `neon`     — the accent colour, lit
/// · `outline`  — hollow numerals with the backdrop showing through
/// · `bubble`   — fat accent numerals in a thick outline, stacked 2×2
/// · `gradient` — accent swept through the glyphs themselves
export type ClockStyle =
  | "row"
  | "stacked"
  | "mono"
  | "minimal"
  | "neon"
  | "outline"
  | "bubble"
  | "gradient";

const KEY_THEME = "theme";
const KEY_GLASS = "glassStrength";
const KEY_ACCENT = "accentSource";
const KEY_CLOCK = "clockStyle";

/// 0 = flat and opaque, 100 = maximum translucency + blur. Defaults to the
/// middle because both ends are legitimate destinations: some people want the
/// desktop showing through, some want an opaque tool that never distracts.
const GLASS_DEFAULT = 55;

const clampPct = (v: number) => Math.min(100, Math.max(0, Math.round(v)));

// ── Theme ────────────────────────────────────────────────────────────────

export function getTheme(): Theme {
  const raw = localStorage.getItem(KEY_THEME);
  return raw === "light" || raw === "dark" || raw === "system" ? raw : "dark";
}

export function setTheme(t: Theme): void {
  localStorage.setItem(KEY_THEME, t);
  applyTheme(t);
}

/// The concrete theme in effect right now — `system` resolved against the OS.
export function resolvedTheme(t: Theme = getTheme()): "dark" | "light" {
  if (t !== "system") return t;
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

export function applyTheme(t: Theme = getTheme()): void {
  const resolved = resolvedTheme(t);
  document.documentElement.dataset.theme = resolved;
  // Tells the webview to draw the things CSS can't reach — form controls, the
  // window background before first paint — in the matching scheme. Without it,
  // a light theme still flashes black on every window open.
  document.documentElement.style.colorScheme = resolved;
}

/// Re-resolve when the OS flips, but only while the user is on `system`.
/// Returns an unsubscribe.
export function watchSystemTheme(): () => void {
  const mq = window.matchMedia("(prefers-color-scheme: light)");
  const onChange = () => {
    if (getTheme() === "system") applyTheme("system");
  };
  mq.addEventListener("change", onChange);
  return () => mq.removeEventListener("change", onChange);
}

// ── Glass ────────────────────────────────────────────────────────────────

export function getGlass(): number {
  const raw = localStorage.getItem(KEY_GLASS);
  if (raw === null) return GLASS_DEFAULT;
  const n = Number(raw);
  return Number.isFinite(n) ? clampPct(n) : GLASS_DEFAULT;
}

export function setGlass(v: number): void {
  const next = clampPct(v);
  localStorage.setItem(KEY_GLASS, String(next));
  applyGlass(next);
}

/// One slider drives four variables, because tuning them separately produces
/// combinations that stop reading as a single material: blur radius, how much
/// saturation the blur pushes back (blurred backdrops go grey without it), and
/// how much paint the chrome and content surfaces keep.
export function applyGlass(v: number = getGlass()): void {
  const s = clampPct(v) / 100;
  const root = document.documentElement.style;
  root.setProperty("--glass-strength", s.toFixed(3));
  root.setProperty("--glass-blur", `${(s * 30).toFixed(1)}px`);
  root.setProperty("--glass-saturate", `${(100 + s * 90).toFixed(0)}%`);
  // Alpha runs the other way: more glass means less paint.
  //
  // The first version topped out at 0.55 chrome / 0.80 surface, which meant
  // the slider's whole range moved the content surface by 20% opacity — over
  // a dark desktop that is invisible, and the setting read as broken. The
  // range now goes far enough that the top of the slider is unmistakably
  // glass; the bottom is still fully opaque, so both ends are real
  // destinations rather than two shades of the same thing.
  root.setProperty("--chrome-alpha", (1 - s * 0.62).toFixed(3));
  root.setProperty("--surface-alpha", (1 - s * 0.45).toFixed(3));
}

// ── Clock ────────────────────────────────────────────────────────────────

export const CLOCK_STYLES: ClockStyle[] = [
  "row",
  "stacked",
  "mono",
  "minimal",
  "neon",
  "outline",
  "bubble",
  "gradient",
];

export function getClockStyle(): ClockStyle {
  const raw = localStorage.getItem(KEY_CLOCK) as ClockStyle | null;
  return raw && CLOCK_STYLES.includes(raw) ? raw : "row";
}

export function setClockStyle(s: ClockStyle): void {
  localStorage.setItem(KEY_CLOCK, s);
  // No DOM attribute here — unlike theme and glass this is read by one
  // component, so a `useSyncExternalStore`-style event is the whole mechanism.
  window.dispatchEvent(new CustomEvent("droiddock:clock"));
}

// ── Accent ───────────────────────────────────────────────────────────────

export function getAccentSource(): AccentSource {
  return localStorage.getItem(KEY_ACCENT) === "system" ? "system" : "warm";
}

export function setAccentSource(a: AccentSource): void {
  localStorage.setItem(KEY_ACCENT, a);
  applyAccentSource(a);
}

export function applyAccentSource(a: AccentSource = getAccentSource()): void {
  document.documentElement.dataset.accent = a;
}

/// The macOS system accent, read natively at startup. Kept in its own variable
/// rather than overwriting `--color-accent` directly, so switching back to the
/// warm accent doesn't need a restart to undo it.
export function applySystemAccent(hex: string): void {
  document.documentElement.style.setProperty("--dd-system-accent", hex);
}

/// Call once, as early as possible — theme first, so the first painted frame is
/// already the right scheme.
export function initAppearance(): void {
  applyTheme();
  applyGlass();
  applyAccentSource();
}
