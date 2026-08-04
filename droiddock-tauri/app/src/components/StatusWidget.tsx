import { useEffect, useState } from "react";
import Icon from "./Icon";
import { onWifiStatus, wifiStatus, type WifiStatus } from "../lib/wifi";
import {
  on,
  getAppearance,
  mediaCmd,
  onAppDeviceInfo,
  openMainWindow,
  type AppDeviceInfo,
  type MediaState,
} from "../lib/bridge";

/// The floating status widget — a small always-on-top panel you park anywhere.
///
/// **This is not a macOS Widget.** Real ones are WidgetKit, which means a Swift
/// app extension inside the bundle, and a Tauri app can't produce one. What it
/// does deliver is what the widget was for: phone battery and now-playing,
/// glanceable without raising the app or opening the menu bar.
///
/// The whole surface is a drag region except the controls, so it moves like a
/// native floating panel rather than needing a title bar it has no room for.
export default function StatusWidget() {
  const [status, setStatus] = useState<WifiStatus>({ connected: false, phoneName: null });
  const [info, setInfo] = useState<AppDeviceInfo | null>(null);
  const [media, setMedia] = useState<MediaState | null>(null);

  useEffect(() => {
    getAppearance()
      .then((a) => {
        const root = document.documentElement;
        root.style.setProperty("--color-accent", a.accent_color);
        root.dataset.reduceTransparency = String(a.reduce_transparency);
      })
      .catch(() => {});

    // Same reason as the menu-bar panel: its own window, created long after the
    // link came up, so the event that announced it is already gone.
    wifiStatus().then(setStatus).catch(() => {});

    const offStatus = onWifiStatus((s) => {
      setStatus(s);
      if (!s.connected) {
        setMedia(null);
        setInfo(null);
      }
    });
    const offInfo = onAppDeviceInfo(setInfo);
    const offMedia = on<MediaState>("media", setMedia);
    return () => {
      offStatus();
      offInfo();
      offMedia();
    };
  }, []);

  const battery = typeof info?.battery === "number" ? Math.round(info.battery) : null;
  const hasTrack = !!media?.active && !!(media.title || media.artist);

  return (
    // Same fix as the mirror pop-out: `-webkit-app-region` is a Chromium
    // extension WKWebView ignores, so this borderless widget — whose entire
    // point is that you can park it anywhere — could not be dragged at all.
    <div
      data-tauri-drag-region
      className="glass-heavy flex h-screen flex-col justify-between overflow-hidden rounded-[14px] border border-line p-3"
    >
      <div data-tauri-drag-region className="flex items-center gap-2">
        <span
          className={`h-2 w-2 shrink-0 rounded-full ${
            status.connected ? "led bg-(--color-link)" : "bg-faint"
          }`}
        />
        <span data-tauri-drag-region className="min-w-0 flex-1 truncate text-[12px] font-semibold text-fg">
          {status.connected ? status.phoneName ?? "Phone" : "Not linked"}
        </span>
        {battery !== null && (
          <span className="shrink-0 text-[12px] font-medium tabular-nums text-fg/85">
            {info?.charging && <span className="text-(--color-link)">⚡︎</span>}
            {battery}%
          </span>
        )}
        <button
          onClick={() => openMainWindow()}
          title="Open DroidDock"
          className="btn-icon shrink-0"
        >
          <Icon name="monitor" size={12} />
        </button>
      </div>

      {battery !== null && (
        <div className="h-1.5 overflow-hidden rounded-full bg-panel3">
          <div
            className="h-full rounded-full transition-[width] duration-500"
            style={{
              width: `${Math.max(2, battery)}%`,
              background: info?.charging
                ? "var(--color-link)"
                : battery <= 15
                  ? "var(--color-bad)"
                  : "var(--color-accent)",
            }}
          />
        </div>
      )}

      {hasTrack && media ? (
        <div
          className="flex items-center gap-2"
        >
          <div className="min-w-0 flex-1">
            <p className="truncate text-[11.5px] font-medium text-fg">{media.title || "Unknown"}</p>
            <p className="truncate text-[10.5px] text-dim">{media.artist || media.app || ""}</p>
          </div>
          <button onClick={() => mediaCmd("prev")} className="btn-icon shrink-0" title="Previous">
            <Icon name="skipBack" size={12} fill="currentColor" strokeWidth={0} />
          </button>
          <button
            onClick={() => mediaCmd(media.playing ? "pause" : "play")}
            title={media.playing ? "Pause" : "Play"}
            className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-(--color-accent) text-white"
          >
            <Icon name={media.playing ? "pause" : "play"} size={11} fill="currentColor" strokeWidth={0} />
          </button>
          <button onClick={() => mediaCmd("next")} className="btn-icon shrink-0" title="Next">
            <Icon name="skipForward" size={12} fill="currentColor" strokeWidth={0} />
          </button>
        </div>
      ) : (
        <p className="text-[11px] text-faint">
          {status.connected ? "Nothing playing" : "Open DroidDock to pair"}
        </p>
      )}
    </div>
  );
}
