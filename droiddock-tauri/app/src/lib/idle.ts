/// Doing nothing while nobody is looking.
///
/// The app's window is `transparent: true` sitting on an `NSVisualEffectView`
/// (see `tauri.conf.json` → `windowEffects`), and its two big surfaces —
/// `.app-surface` and `.glass-chrome` — each carry a `backdrop-filter`. That
/// stack is cheap when it is static and cached, and expensive per *frame*: a
/// single animating pixel makes macOS re-composite a translucent window through
/// several gaussian blurs.
///
/// So the rule for this app is that a frame has to be earned. The heartbeat LED
/// and the Link thread run for as long as the phone is connected, which is to
/// say permanently, and there is no reason for them to run while the window is
/// on another Space, fully covered by another app, or hidden. WebKit throttles a
/// *minimised* window on its own — which is precisely why minimising was the
/// workaround people found — but not the other three.
///
/// The mechanism is one attribute on `<html>`; `index.css` pauses the infinite
/// animations off it. Nothing is skipped or dropped: `animation-play-state`
/// resumes from where it stopped, and by definition none of it was on screen.

import { getCurrentWindow } from "@tauri-apps/api/window";

const ATTR = "windowHidden";
const ATTR_BLUR = "windowBlurred";

function sync(): void {
  document.documentElement.dataset[ATTR] = document.hidden ? "true" : "false";
}

function setBlurred(blurred: boolean): void {
  document.documentElement.dataset[ATTR_BLUR] = blurred ? "true" : "false";
}

/// Call once at startup. Returns an unsubscribe for symmetry with
/// `watchSystemTheme` — nothing currently needs it, since this lives as long as
/// the window does.
export function watchWindowVisibility(): () => void {
  sync();
  document.addEventListener("visibilitychange", sync);

  // Focus is a second, weaker signal, and only the Link thread listens to it —
  // see the `[data-window-blurred]` rule in index.css for which animations do
  // not, and why. `document.hidden` cannot see this case: a window sitting
  // behind your editor is fully visible to WebKit and gets every frame.
  //
  // Tauri's event, not the DOM's `blur`, which in a WKWebView also fires when
  // focus moves to a native menu or a sheet while the window is still the key
  // window and still on screen.
  let unlistenFocus: (() => void) | undefined;
  try {
    const win = getCurrentWindow();
    void win.isFocused().then(setBlurred_inverted).catch(() => setBlurred(false));
    void win
      .onFocusChanged(({ payload: focused }) => setBlurred(!focused))
      .then((un) => {
        unlistenFocus = un;
      })
      .catch(() => {});
  } catch {
    // Not running under Tauri (a plain browser during `vite dev`): the
    // visibility half still works, and nothing here is load-bearing.
    setBlurred(false);
  }

  return () => {
    document.removeEventListener("visibilitychange", sync);
    unlistenFocus?.();
  };
}

const setBlurred_inverted = (focused: boolean) => setBlurred(!focused);
