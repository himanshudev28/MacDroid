import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import MirrorWindow from "./components/MirrorWindow";
import MenubarPanel from "./components/MenubarPanel";
import StatusWidget from "./components/StatusWidget";
import ErrorBoundary from "./ErrorBoundary";
import { initAppearance } from "./lib/appearance";
import { watchWindowVisibility } from "./lib/idle";
import "./index.css";

// Before React mounts, not inside an effect. Theme and glass are read from
// localStorage synchronously, so doing this here means the very first painted
// frame is already correct — an effect would paint the default theme first and
// then flip, which is visible on every window open, including the menu-bar
// panel and the mirror pop-out. All four routes below want it.
initAppearance();

// Same reasoning, same place: all four routes are windows that can end up
// covered, on another Space, or hidden, and none of them should be spending GPU
// while they are. See lib/idle.ts.
watchWindowVisibility();

// DroidDock runs in a WKWebView, so a right-click (or two-finger tap) gets the
// *web view's* menu — Reload, Back, Inspect Element — which is a browser
// artefact leaking through, not a feature of this app. Reload in particular is
// destructive-looking and does nothing a user would want here.
//
// Suppressed everywhere except where a context menu is genuinely useful and
// native: inside a text field, and over a selection you might want to copy.
document.addEventListener("contextmenu", (e) => {
  const t = e.target as HTMLElement | null;
  const editable =
    t instanceof HTMLInputElement ||
    t instanceof HTMLTextAreaElement ||
    !!t?.isContentEditable;
  const hasSelection = !window.getSelection()?.isCollapsed;
  if (!editable && !hasSelection) e.preventDefault();
});

// Secondary windows load this same bundle at a distinguishing hash — the
// mirror pop-out (`mirror.rs::open_mirror_window`, the routing approach the
// Electron reference used for its pop-out `BrowserWindow`) and the menu-bar
// panel (`tray.rs::toggle_panel`).
const ROUTES: Record<string, () => React.ReactElement> = {
  "#mirror": MirrorWindow,
  "#menubar": MenubarPanel,
  "#widget": StatusWidget,
};
const Root = ROUTES[window.location.hash] ?? App;

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <Root />
    </ErrorBoundary>
  </React.StrictMode>,
);
