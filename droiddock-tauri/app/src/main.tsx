import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import MirrorWindow from "./components/MirrorWindow";
import "./index.css";

// The mirror pop-out is a separate Tauri window loading this same bundle at
// the `#mirror` hash (see `mirror.rs::open_mirror_window`) — same routing
// approach the Electron reference used for its pop-out `BrowserWindow`.
const Root = window.location.hash === "#mirror" ? MirrorWindow : App;

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
);
