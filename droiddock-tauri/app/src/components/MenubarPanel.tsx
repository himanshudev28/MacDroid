import { useEffect, useState } from "react";
import Icon from "./Icon";
import { applySystemAccent } from "../lib/appearance";
import { useAppIcon } from "../lib/appIcons";
import { fmtTime } from "../lib/ui";
import { onWifiStatus, wifiStatus, type WifiStatus } from "../lib/wifi";
import { t, useT } from "../lib/i18n";
import {
  on,
  getConfig,
  getAppearance,
  setSetting,
  notifDismiss,
  mediaState,
  mediaCmd,
  clipboardPushNow,
  menubarHide,
  openMainWindow,
  onAppDeviceInfo,
  onConfigUpdate,
  onCallState,
  adbCallEnd,
  adbCallMute,
  adbCallSpeaker,
  type AppDeviceInfo,
  type DroidConfig,
  type MediaState,
  type Notif,
  type IncomingCall,
} from "../lib/bridge";

/// The menu-bar panel: the phone at a glance without raising the main window.
/// A separate Tauri window loading this same bundle at `#menubar` (see
/// `tray.rs`), so it subscribes to the same broadcast events the main window
/// does rather than sharing state with it.
///
/// Every action here is a second entry point to something that already exists —
/// nothing is only reachable from the panel.
export default function MenubarPanel() {
  // Its own window, so it needs its own subscription — a language change in the
  // main window has to repaint this one too. See App.tsx.
  useT();

  const [status, setStatus] = useState<WifiStatus>({ connected: false, phoneName: null });
  const [info, setInfo] = useState<AppDeviceInfo | null>(null);
  const [media, setMedia] = useState<MediaState | null>(null);
  const [config, setConfig] = useState<DroidConfig | null>(null);
  const [notifs, setNotifs] = useState<Notif[]>([]);
  const [flash, setFlash] = useState<string | null>(null);
  /// Album art, cached the same way the main window does it: the phone sends
  /// `art` only on a track change, so an absent field means "unchanged".
  const [art, setArt] = useState<string | null>(null);
  /// v4 parity: answer/hang-up without raising the main window. `call` gives
  /// the ringing event; `call-state` is ADB's live in-call polling.
  const [call, setCall] = useState<{ state: string; who: string } | null>(null);

  useEffect(() => {
    getConfig().then(setConfig).catch(() => {});
    getAppearance()
      .then((a) => {
        // Parked, not written over the token — the theme decides whether
        // the system accent or the app's amber wins. See lib/appearance.
        applySystemAccent(a.accent_color);
        document.documentElement.dataset.reduceTransparency = String(a.reduce_transparency);
      })
      .catch(() => {});

    // This panel is a separate window created on demand, so it never witnessed
    // the `wifi-status` event that fired when the phone linked — without this it
    // opens saying "No phone linked" over a perfectly live connection.
    wifiStatus().then(setStatus).catch(() => {});

    // Same reason, and the reason Settings › Menu bar › album art looked like a
    // dead setting: `art` was only ever assigned from a `media` event, and the
    // phone attaches art only on a track change. A panel opened mid-song saw no
    // art at all, so "thumb" and "background" rendered identically to "none".
    mediaState()
      .then((m) => {
        if (!m) return;
        setMedia(m);
        if (m.art) setArt(`data:image/jpeg;base64,${m.art}`);
      })
      .catch(() => {});

    const offConfig = onConfigUpdate(setConfig);
    const offStatus = onWifiStatus((s) => {
      setStatus(s);
      if (!s.connected) {
        setMedia(null);
        setArt(null);
      }
    });
    const offInfo = onAppDeviceInfo(setInfo);
    const offMedia = on<MediaState>("media", (m) => {
      setMedia(m);
      if (m.art !== undefined) setArt(m.art ? `data:image/jpeg;base64,${m.art}` : null);
    });
    const offCall = on<IncomingCall>("call", (c) => {
      if (c.state === "ringing") {
        setCall({ state: "ringing", who: c.name || c.number || "Unknown caller" });
      } else {
        setCall(null);
      }
    });
    const offCallState = onCallState(({ state }) =>
      setCall((prev) => (state === "IDLE" ? null : prev ? { ...prev, state } : null))
    );

    const offNotif = on<Notif>("notification", (n) =>
      setNotifs((list) => [n, ...list.filter((x) => x.key !== n.key)].slice(0, 25))
    );
    const offGone = on<{ key: string }>("notification-removed", ({ key }) =>
      setNotifs((list) => list.filter((x) => x.key !== key))
    );

    return () => {
      offConfig();
      offStatus();
      offInfo();
      offMedia();
      offCall();
      offCallState();
      offNotif();
      offGone();
    };
  }, []);

  const say = (text: string) => {
    setFlash(text);
    setTimeout(() => setFlash((f) => (f === text ? null : f)), 1800);
  };

  const battery = typeof info?.battery === "number" ? Math.round(info.battery) : null;
  const hasTrack = !!media?.active && !!(media.title || media.artist);
  const bannersOn = config?.nativeNotifs ?? true;
  const artLayout = config?.menubarAlbumArt ?? "thumb";

  return (
    <div className="panel-drop glass-heavy flex h-screen flex-col overflow-hidden rounded-[14px] border border-line">
      {/* Header */}
      <div className="flex shrink-0 items-center gap-2.5 border-b border-line px-3.5 py-3">
        <span
          className={`h-2 w-2 shrink-0 rounded-full ${
            status.connected ? "led bg-(--color-link)" : "bg-faint"
          }`}
        />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-semibold text-fg">
            {status.connected ? status.phoneName ?? "Phone" : t("No phone linked")}
          </p>
          <p className="truncate text-[11px] text-dim">
            {status.connected
              ? [battery !== null ? `${battery}%${info?.charging ? " charging" : ""}` : null, t("Linked over Wi-Fi")]
                  .filter(Boolean)
                  .join(" · ")
              : t("Open DroidDock to pair")}
          </p>
        </div>
        <button onClick={() => openMainWindow()} className="btn-icon shrink-0" title={t("Open DroidDock")}>
          <Icon name="monitor" size={14} />
        </button>
      </div>

      {/* Live call — only while one is actually ringing or connected. */}
      {call && (
        <div className="rise-fast shrink-0 border-b border-line px-3 py-2.5">
          <div className="flex items-center gap-2.5">
            <Icon name="phone" size={14} className="shrink-0 text-ok" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[12px] font-medium text-fg">{call.who}</p>
              <p className="text-[10.5px] text-dim">
                {call.state === "ringing" ? t("Incoming call") : t("In call")}
              </p>
            </div>
            {/* ADB-only, same as the main window's overlay: there is no Wi-Fi
                control message for these, and inventing one would need an
                Android receive path that doesn't exist. */}
            <button onClick={() => adbCallMute().catch(() => say(t("Needs ADB")))} className="btn-icon shrink-0" title={t("Mute")}>
              <Icon name="micOff" size={13} />
            </button>
            <button onClick={() => adbCallSpeaker().catch(() => say(t("Needs ADB")))} className="btn-icon shrink-0" title={t("Speaker")}>
              <Icon name="volume" size={13} />
            </button>
            <button
              onClick={() => adbCallEnd().catch(() => say(t("Needs ADB")))}
              title={t("Hang up")}
              className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-(--color-bad) text-white"
            >
              <Icon name="phoneOff" size={12} />
            </button>
          </div>
        </div>
      )}

      {/* Quick actions */}
      <div className="grid shrink-0 grid-cols-3 gap-1.5 px-3 py-3">
        <PanelAction
          icon="clipboard"
          label={t("Clipboard")}
          disabled={!status.connected}
          onClick={async () => {
            try {
              await clipboardPushNow();
              say(t("Clipboard sent"));
            } catch (e) {
              say(String(e));
            }
          }}
        />
        <PanelAction
          icon="bell"
          label={bannersOn ? "Mute" : "Unmute"}
          active={!bannersOn}
          onClick={async () => {
            try {
              setConfig(await setSetting("nativeNotifs", !bannersOn));
              say(bannersOn ? t("Banners muted") : t("Banners on"));
            } catch (e) {
              say(String(e));
            }
          }}
        />
        <PanelAction
          icon="folder"
          label={t("Files")}
          onClick={() => openMainWindow()}
        />
      </div>

      {/* Now playing */}
      {hasTrack && media && (
        <div className="shrink-0 px-3 pb-3">
          <div className="card relative overflow-hidden px-3 py-2.5">
            {/* Album-art layout (Settings › Menu bar): a small cover beside the
                text, or bled across the card behind it. */}
            {art && artLayout === "background" && (
              <>
                <img src={art} alt="" className="absolute inset-0 h-full w-full object-cover opacity-35" />
                <div className="absolute inset-0 bg-gradient-to-r from-panel via-panel/70 to-panel/30" />
              </>
            )}
            <div className="relative flex items-center gap-2.5">
              {art && artLayout === "thumb" && (
                <img src={art} alt="" className="h-9 w-9 shrink-0 rounded-md object-cover" />
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate text-[12px] font-medium text-fg">{media.title || "Unknown track"}</p>
                <p className="mt-0.5 truncate text-[11px] text-dim">{media.artist || media.app || ""}</p>
              </div>
            </div>
            <div className="relative mt-2 flex items-center justify-center gap-4">
              <button onClick={() => mediaCmd("prev")} className="btn-icon" title={t("Previous")}>
                <Icon name="skipBack" size={14} fill="currentColor" strokeWidth={0} />
              </button>
              <button
                onClick={() => mediaCmd(media.playing ? "pause" : "play")}
                title={media.playing ? "Pause" : "Play"}
                className="flex h-8 w-8 items-center justify-center rounded-full bg-(--color-accent) text-(--color-accent-ink) transition-opacity hover:opacity-90"
              >
                <Icon name={media.playing ? "pause" : "play"} size={13} fill="currentColor" strokeWidth={0} />
              </button>
              <button onClick={() => mediaCmd("next")} className="btn-icon" title={t("Next")}>
                <Icon name="skipForward" size={14} fill="currentColor" strokeWidth={0} />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Notifications */}
      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">
        <div className="mb-1.5 flex items-center justify-between px-1">
          <span className="label">{t("Notifications")}</span>
          {notifs.length > 0 && (
            <button onClick={() => setNotifs([])} className="text-[11px] text-dim transition-colors hover:text-fg">{t("Clear")}
            </button>
          )}
        </div>

        {notifs.length === 0 ? (
          <p className="px-1 py-6 text-center text-[11.5px] text-faint">{t("Nothing new.")}</p>
        ) : (
          <div className="card divide-y divide-line overflow-hidden">
            {notifs.map((n) => (
              <div key={n.key} className="group flex items-start gap-2.5 px-3 py-2.5">
                <NotifIcon pkg={n.pkg} app={n.app} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="label truncate">{n.app || "App"}</span>
                    <span className="shrink-0 font-mono text-[9.5px] tabular-nums text-faint">{fmtTime(n.time)}</span>
                  </div>
                  {n.title && <p className="truncate text-[12px] font-medium text-fg">{n.title}</p>}
                  {n.text && <p className="line-clamp-2 text-[11px] leading-snug text-dim">{n.text}</p>}
                </div>
                <button
                  onClick={() => {
                    notifDismiss(n.key);
                    setNotifs((l) => l.filter((x) => x.key !== n.key));
                  }}
                  title={t("Dismiss")}
                  className="btn-icon shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
                >
                  <Icon name="x" size={11} />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-2">
        <span className="min-w-0 flex-1 truncate text-[11px] text-dim">{flash ?? ""}</span>
        <button onClick={() => menubarHide()} className="text-[11px] text-dim transition-colors hover:text-fg">{t("Close")}
        </button>
      </div>
    </div>
  );
}

function PanelAction({
  icon,
  label,
  active,
  disabled,
  onClick,
}: {
  icon: string;
  label: string;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`flex flex-col items-center gap-1 rounded-[10px] border px-2 py-2.5 transition-colors disabled:pointer-events-none disabled:opacity-40 ${
        active
          ? "border-[color-mix(in_srgb,var(--color-accent)_35%,transparent)] bg-[color-mix(in_srgb,var(--color-accent)_14%,transparent)] text-(--color-accent)"
          : "border-line bg-panel text-dim hover:bg-panel2 hover:text-fg"
      }`}
    >
      <Icon name={icon} size={15} strokeWidth={1.8} />
      <span className="text-[10.5px] font-medium">{label}</span>
    </button>
  );
}

/// The sending app's real icon, exactly as the main Notifications view shows
/// it. The panel used to draw two letters of the app name on a grey square —
/// legible, but it turned a glanceable list into something you had to read.
/// Icons are already cached in memory by `appIcons`, so a notification from an
/// app you've seen costs nothing.
function NotifIcon({ pkg, app }: { pkg?: string; app?: string }) {
  const icon = useAppIcon(pkg);
  if (icon) {
    return <img src={icon} alt="" className="mt-0.5 h-6 w-6 shrink-0 rounded-md object-cover" />;
  }
  // Until the icon arrives (or for a phone build that doesn't send `pkg`),
  // the initials are still the best fallback available.
  return (
    <div className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-panel3 text-[9px] font-semibold text-dim">
      {(app || "?").slice(0, 2).toUpperCase()}
    </div>
  );
}
