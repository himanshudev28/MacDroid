import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import MirrorWindow from "./components/MirrorWindow";
import MenubarPanel from "./components/MenubarPanel";
import StatusWidget from "./components/StatusWidget";
import ErrorBoundary from "./ErrorBoundary";
import "./index.css";

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
