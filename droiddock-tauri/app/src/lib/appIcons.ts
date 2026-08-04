import { useEffect, useState } from "react";
import { appIcon, appLaunch } from "./bridge";

/// Shared app-icon cache + the recent-apps list.
///
/// Icons are fetched one per request over the binary thumb frame, so a 200-app
/// grid would otherwise re-ask on every scroll and every remount. The cache is
/// in-memory only and deliberately not persisted: a few hundred base64 PNGs is
/// megabytes, which is the wrong thing to put in localStorage, and re-fetching
/// on app start is cheap and always correct (icons change when apps update).

const cache = new Map<string, string | null>();
const inflight = new Map<string, Promise<string | null>>();

/// `null` means "asked, and there is no icon" — cached like a hit so a broken
/// package doesn't get retried forever.
export function fetchIcon(pkg: string): Promise<string | null> {
  const hit = cache.get(pkg);
  if (hit !== undefined) return Promise.resolve(hit);

  const existing = inflight.get(pkg);
  if (existing) return existing;

  const p = appIcon(pkg)
    .then((url) => {
      cache.set(pkg, url);
      return url;
    })
    .catch(() => {
      cache.set(pkg, null);
      return null;
    })
    .finally(() => inflight.delete(pkg));

  inflight.set(pkg, p);
  return p;
}

/// Drop everything — call on disconnect, since the next phone may not be the
/// same phone.
export function clearIcons(): void {
  cache.clear();
  inflight.clear();
}

/// Subscribe a component to one package's icon.
export function useAppIcon(pkg: string | undefined | null): string | null {
  const [url, setUrl] = useState<string | null>(() => (pkg ? cache.get(pkg) ?? null : null));

  useEffect(() => {
    if (!pkg) {
      setUrl(null);
      return;
    }
    let live = true;
    fetchIcon(pkg).then((u) => live && setUrl(u));
    return () => {
      live = false;
    };
  }, [pkg]);

  return url;
}

// ── Recent apps ──────────────────────────────────────────────────────────

const RECENTS_KEY = "recentApps";
const RECENTS_MAX = 8;

/// Tracked entirely on the Mac. AirSync does the same — "recent" here means
/// "recently launched *from here*", which is a different and more useful list
/// than the phone's own task stack, and it needs no protocol support.
export function recentApps(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(RECENTS_KEY) ?? "[]");
    return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

/// Launch on the phone and move the app to the front of the recents list.
/// Order is only committed if the launch actually went through.
export async function launchApp(pkg: string): Promise<void> {
  await appLaunch(pkg);
  const next = [pkg, ...recentApps().filter((p) => p !== pkg)].slice(0, RECENTS_MAX);
  localStorage.setItem(RECENTS_KEY, JSON.stringify(next));
  window.dispatchEvent(new CustomEvent("droiddock:recents"));
}

/// Live view of the recents list — updates when any surface launches an app.
export function useRecentApps(): string[] {
  const [list, setList] = useState(recentApps);
  useEffect(() => {
    const refresh = () => setList(recentApps());
    window.addEventListener("droiddock:recents", refresh);
    return () => window.removeEventListener("droiddock:recents", refresh);
  }, []);
  return list;
}
