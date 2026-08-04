/// Window opacity — how much of the desktop shows through the app's frosted
/// background.
///
/// This is purely a CSS concern, not a native one: the window is already
/// `transparent: true` with a native NSVisualEffectView behind it (see
/// tauri.conf.json's `windowEffects`), so lowering the alpha of the content
/// surface reveals that vibrancy rather than punching a literal hole. Stored
/// locally rather than in droiddock.json because it's a per-display preference
/// with no bearing on the link, and the config file is the phone protocol's
/// state, not the window's.

const KEY = "windowOpacity";
const MIN = 0.55;
const MAX = 1;

const clamp = (v: number) => Math.min(MAX, Math.max(MIN, v));

export function getOpacity(): number {
  const raw = Number(localStorage.getItem(KEY));
  return Number.isFinite(raw) && raw > 0 ? clamp(raw) : MAX;
}

export function setOpacity(v: number): void {
  const next = clamp(v);
  localStorage.setItem(KEY, String(next));
  applyOpacity(next);
}

/// Push the current value onto the document. Called once at startup and on
/// every change; `--app-opacity` is consumed by `.app-surface` in index.css.
export function applyOpacity(v: number = getOpacity()): void {
  document.documentElement.style.setProperty("--app-opacity", String(clamp(v)));
}
