import { useSyncExternalStore } from "react";
import { on, transfersList, type TransferEntry } from "./bridge";

/// The Transfers list, held outside React.
///
/// Rust sends the whole list up to four times a second while anything is
/// moving. Kept here, like `mediaStore`, so those updates re-render only the
/// two readers — the Transfers page and the rail's badge — and not `App`.

let entries: TransferEntry[] = [];
let activeCount = 0;
const listeners = new Set<() => void>();
let started = false;

function publish(next: TransferEntry[] | null) {
  entries = next ?? [];
  activeCount = entries.filter((e) => e.status === "active").length;
  for (const l of listeners) l();
}

/// Runs once, on the first reader. Subscribes before seeding, so an update
/// that lands while the seed is in flight isn't lost: whichever arrives last
/// is the newer list either way, and the seed only fills an empty store.
function start() {
  if (started) return;
  started = true;
  let live = false;
  on<TransferEntry[]>("transfers", (list) => {
    live = true;
    publish(list);
  });
  transfersList()
    .then((list) => {
      if (!live) publish(list);
    })
    .catch(() => {});
}

function subscribe(cb: () => void) {
  start();
  listeners.add(cb);
  return () => listeners.delete(cb);
}

/// Every entry, oldest first, as Rust keeps them.
export function useTransfers(): TransferEntry[] {
  return useSyncExternalStore(subscribe, () => entries);
}

/// How many are moving right now — the rail's badge.
export function useActiveTransferCount(): number {
  return useSyncExternalStore(subscribe, () => activeCount);
}
