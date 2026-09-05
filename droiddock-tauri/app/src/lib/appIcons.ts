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

/// How many icon requests may be on the wire at once.
///
/// Every tile in the Apps grid asks for its icon the moment it mounts, and the
/// grid is not virtualised — so opening the tab on a phone with 300 apps fired
/// 300 simultaneous round-trips over Wi-Fi, each with a 12s timeout on the Rust
/// side. The phone answers them one at a time regardless, so the only thing the
/// fan-out achieved was making every request contend with the mirror stream and
/// with each other, and turning a slow phone into a wall of timeouts. Four at a
/// time saturates the link without starving anything else on it — the photo
/// thumbnail path had already settled on the same shape (`CONCURRENCY = 3`).
const MAX_INFLIGHT = 4;

let active = 0;
const waiting: (() => void)[] = [];

/// Wait for a slot. Resolves immediately while under the cap.
function acquire(): Promise<void> {
  if (active < MAX_INFLIGHT) {
    active++;
    return Promise.resolve();
  }
  return new Promise((resolve) => waiting.push(resolve));
}

function release() {
  const next = waiting.shift();
  // Hand the slot straight to the next waiter rather than decrementing and
  // letting it re-check — that keeps exactly MAX_INFLIGHT in flight with no
  // window where a newly-queued request could jump the line.
  if (next) next();
  else active--;
}

/// `null` means "asked, and there is no icon" — cached like a hit so a broken
/// package doesn't get retried forever.
export function fetchIcon(pkg: string): Promise<string | null> {
  const hit = cache.get(pkg);
  if (hit !== undefined) return Promise.resolve(hit);

  const existing = inflight.get(pkg);
  if (existing) return existing;

  const p = acquire()
    .then(() => appIcon(pkg))
    .then((url) => {
      cache.set(pkg, url);
      return url;
    })
    .catch(() => {
      cache.set(pkg, null);
      return null;
    })
    .finally(() => {
      inflight.delete(pkg);
      release();
    });

  inflight.set(pkg, p);
  return p;
}

/// Drop everything — call on disconnect, since the next phone may not be the
/// same phone.
export function clearIcons(): void {
  cache.clear();
  inflight.clear();
  // The queue is deliberately left alone. A waiter only exists while the cap is
  // full, so every one of them is behind a request that is about to fail
  // against the dead link — each failure hands its slot on, and the queue
  // drains itself in order. Resolving them here instead would run them without
  // a slot and leave `active` counting requests that no longer exist.
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

/// Drop one app from the recents row.
///
/// The row is a Mac-side convenience list, not a view of the phone's task
/// stack, so removing here changes nothing on the phone — it just stops the
/// Mac offering that shortcut. Without this the only way off the list was to
/// launch eight other apps and push it off the end.
export function removeRecent(pkg: string): void {
  const next = recentApps().filter((p) => p !== pkg);
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
