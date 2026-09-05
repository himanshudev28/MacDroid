import { useSyncExternalStore } from "react";
import { mediaState, on, type MediaState } from "./bridge";

/// Now-playing state, held outside React.
///
/// The phone pushes `media` once a second for as long as something is playing.
/// While this lived in `App`'s `useState`, every one of those ticks re-rendered
/// `App` — and with it the rail, the phone card, and whichever view was open,
/// including lists of a few hundred rows that have nothing to do with music.
/// Six components had been individually wrapped in `memo()` to survive it, each
/// carrying the same copy-pasted comment about the tick, and two of those memos
/// were silently defeated by inline props anyway.
///
/// An external store inverts that: the tick notifies only the components that
/// actually read it (`PhoneCard`, `MediaView`), and nothing else in the tree
/// has to defend itself. `useSyncExternalStore` is React's own primitive for
/// this, so it stays correct under concurrent rendering.

type Snapshot = {
  media: MediaState | null;
  /// Ready-to-use data URL, or null when the current track has no artwork.
  /// Kept here rather than rebuilt per render because it is a ~40 KB string.
  albumArt: string | null;
};

let snapshot: Snapshot = { media: null, albumArt: null };
const listeners = new Set<() => void>();
let started = false;

function publish(next: Snapshot) {
  snapshot = next;
  for (const l of listeners) l();
}

/// Subscribe to the phone's pushes. Runs once, on the first mounted reader, and
/// stays for the app's lifetime — there is exactly one phone and one stream of
/// these, so refcounting the teardown would buy nothing.
function start() {
  if (started) return;
  started = true;

  // Seed before subscribing. A listener alone only reports transitions, and
  // `art` rides along only on a track change — so without this, mounting
  // mid-song shows no player card and no artwork until the next track starts.
  mediaState()
    .then((m) => {
      if (!m) return;
      publish({
        media: m,
        albumArt: m.art ? `data:image/jpeg;base64,${m.art}` : snapshot.albumArt,
      });
    })
    .catch(() => {});

  on<MediaState>("media", (m) => {
    // `art` is present only when the track changed (or right after link-up); an
    // explicit null means the new track genuinely has no artwork, which is
    // different from "unchanged, keep what you have".
    const albumArt =
      m.art === undefined
        ? snapshot.albumArt
        : m.art
          ? `data:image/jpeg;base64,${m.art}`
          : null;
    publish({ media: m, albumArt });
  });
}

function subscribe(listener: () => void): () => void {
  start();
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const getSnapshot = () => snapshot;

/// Forget the current track and its artwork. Called when the link drops: the
/// next phone to link may not be the same phone, and stale now-playing under a
/// fresh pairing is worse than an empty player.
export function clearNowPlaying() {
  if (snapshot.media === null && snapshot.albumArt === null) return;
  publish({ media: null, albumArt: null });
}

/// Everything the store holds. Prefer [useMedia] unless artwork is needed too:
/// this identity changes on every tick, that one doesn't have to.
export function useNowPlaying(): Snapshot {
  return useSyncExternalStore(subscribe, getSnapshot);
}

/// Just the playback state.
export function useMedia(): MediaState | null {
  return useSyncExternalStore(subscribe, () => snapshot.media);
}
