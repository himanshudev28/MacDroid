import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import Rail from "./components/Rail";
import Icon from "./components/Icon";
import Onboarding from "./components/Onboarding";
import AppsView from "./components/views/AppsView";
import PhoneCard from "./components/phone/PhoneCard";
import type { QuickAction } from "./components/phone/QuickActions";
import { ALL_ITEMS, itemFor, type ViewId } from "./lib/nav";
import { getPairingInfo, type PairingInfo } from "./lib/pairing";
import { applyOpacity } from "./lib/appearance";
import { clearIcons } from "./lib/appIcons";
import DashboardView from "./components/views/DashboardView";
import FilesView from "./components/views/FilesView";
import PhotosView from "./components/views/PhotosView";
import MessagesView, { type MessageTarget } from "./components/views/MessagesView";
import ContactsView from "./components/views/ContactsView";
import CallsView from "./components/views/CallsView";
import NotificationsView from "./components/views/NotificationsView";
import ClipboardView from "./components/views/ClipboardView";
import MediaView from "./components/views/MediaView";
import MirrorView from "./components/views/MirrorView";
import CameraView from "./components/views/CameraView";
import DevicesView from "./components/views/DevicesView";
import SettingsView from "./components/views/SettingsView";
import CallOverlay, { type ActiveCall } from "./components/CallOverlay";
import SetupModal from "./components/SetupModal";
import WirelessPairModal from "./components/WirelessPairModal";
import Toasts, { type Toast } from "./components/Toasts";
import { onWifiStatus, wifiStatus, type WifiStatus } from "./lib/wifi";
import {
  on,
  getConfig,
  getAppearance,
  setSetting,
  notifDismiss,
  adbTools,
  adbDevices,
  adbDeviceInfo,
  adbPairedInfo,
  adbGoWireless,
  adbReconnectNow,
  adbUnpair,
  adbScreenshot,
  adbScrcpyInstall,
  adbMirror,
  adbDesktop,
  adbCamera,
  onTools,
  onDevices,
  onWifiEvent,
  onAppDeviceInfo,
  onCallState,
  onConfigUpdate,
  onEditSync,
  clipboardPushNow,
  fsPush,
  wallpaperGet,
  onLinkQuality,
  type DroidConfig,
  type LinkQuality,
  type Notif,
  type IncomingCall,
  type MediaState,
  type Progress,
  type ToolsStatus,
  type AdbDevice,
  type DeviceInfo,
  type AppDeviceInfo,
} from "./lib/bridge";

export default function App() {
  const [view, setView] = useState<ViewId>("dashboard");
  console.log("[DroidDock] Rendering App component with view:", view);
  const [config, setConfig] = useState<DroidConfig | null>(null);
  const [status, setStatus] = useState<WifiStatus>({ connected: false, phoneName: null });
  const [notifs, setNotifs] = useState<Notif[]>([]);
  const [media, setMedia] = useState<MediaState | null>(null);
  const [activeCall, setActiveCall] = useState<ActiveCall | null>(null);
  const [messageTarget, setMessageTarget] = useState<MessageTarget | null>(null);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const toastId = useRef(0);

  // ── Phase 13: ADB/scrcpy fallback state ─────────────────────────────────
  const [tools, setTools] = useState<ToolsStatus | null>(null);
  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [serial, setSerial] = useState<string | null>(null);
  const [deviceInfo, setDeviceInfo] = useState<DeviceInfo | null>(null);
  const [appDeviceInfo, setAppDeviceInfo] = useState<AppDeviceInfo | null>(null);
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [pairedGuid, setPairedGuid] = useState<string | null>(null);
  const [setup, setSetup] = useState<{ reason: string } | null>(null);
  const [wPairOpen, setWPairOpen] = useState(false);

  // ── Shell chrome (the AirSync-style phone panel) ────────────────────────
  // Purely presentational state: which columns are showing, and the pairing
  // address the connection popover reports. No feature behaviour depends on it.
  const [phoneOpen, setPhoneOpen] = useState(
    () => localStorage.getItem("phonePanel") !== "off"
  );
  const [pairing, setPairing] = useState<PairingInfo | null>(null);
  /// Tier B. The phone's wallpaper, fetched once per connection (it's a ~100 KB
  /// payload that rarely changes), and the current track's album art, which the
  /// phone sends only on a track change. Album art wins while something is
  /// playing — same precedence AirSync's phone card uses.
  const [wallpaper, setWallpaper] = useState<string | null>(null);
  const [albumArt, setAlbumArt] = useState<string | null>(null);
  /// Tier C. Grade of the live link, from the Rust ping/pong probe. Distinct
  /// from `status.connected`: a socket can stay open long after the phone stops
  /// answering, which is exactly the case worth showing.
  const [quality, setQuality] = useState<LinkQuality | null>(null);
  /// Set when a link we *had* goes away, so the reconnect strip can distinguish
  /// "never paired" (the phone card already says so) from "lost the phone".
  const [lostLink, setLostLink] = useState(false);
  /// First run only, and never blocking — "Skip" is always available and the
  /// flag is written the moment it's dismissed either way.
  const [onboarding, setOnboarding] = useState(
    () => localStorage.getItem("onboarded") !== "1"
  );

  useEffect(() => {
    localStorage.setItem("phonePanel", phoneOpen ? "on" : "off");
  }, [phoneOpen]);

  useEffect(() => {
    getPairingInfo().then(setPairing).catch(() => setPairing(null));
    applyOpacity();
  }, []);

  const toast = useCallback((kind: Toast["kind"], text: string) => {
    const id = ++toastId.current;
    setToasts((t) => [...t, { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4000);
  }, []);

  useEffect(() => {
    getConfig().then(setConfig).catch(console.error);
    // Phase 14: tray-driven pause (or its auto-expiry) updates config outside
    // of setSetting/pauseSet's own return value — keep it live everywhere.
    const offConfig = onConfigUpdate(setConfig);
    getAppearance()
      .then((a) => {
        const root = document.documentElement;
        root.style.setProperty("--color-accent", a.accent_color);
        root.dataset.reduceTransparency = String(a.reduce_transparency);
      })
      .catch(console.error);
    return () => offConfig();
  }, []);

  // ── Wire the feature events (the invoke()+listen() shim for wifi.js's
  //    onForward channels). ────────────────────────────────────────────────
  useEffect(() => {
    // Covers a webview reload while the phone is already linked — the listener
    // below only ever hears about *changes*.
    wifiStatus().then(setStatus).catch(() => {});

    const offStatus = onWifiStatus((s) => {
      setStatus(s);
      if (!s.connected) {
        setMedia(null);
        setActiveCall(null);
      }
    });

    const offNotif = on<Notif>("notification", (n) =>
      setNotifs((list) => [n, ...list.filter((x) => x.key !== n.key)].slice(0, 100))
    );
    const offNotifGone = on<{ key: string }>("notification-removed", ({ key }) =>
      setNotifs((list) => list.filter((x) => x.key !== key))
    );

    const offMedia = on<MediaState>("media", (m) => {
      setMedia(m);
      // `art` is present only when the track changed (or right after link-up);
      // an explicit null means the new track genuinely has no artwork, which is
      // different from "unchanged, keep what you have".
      if (m.art !== undefined) {
        setAlbumArt(m.art ? `data:image/jpeg;base64,${m.art}` : null);
      }
    });

    // Phone-initiated pushes (Android share sheet → DroidDock) land in Downloads
    // — surface them on whatever view is open.
    const offPhonePush = on<Progress>("transfer-progress", (p) => {
      if (p.dir !== "phone") return;
      if (!p.done) return;
      if (p.error) toast("bad", `${p.name}: ${p.error}`);
      else toast("ok", `${p.name} saved to Downloads`);
    });

    // Phase 17: edit-in-place writeback lands here regardless of which view
    // is open — the badge itself lives in FilesView's own subscription.
    const offEditSync = onEditSync((e) => {
      if (e.status === "synced") toast("ok", "Synced back to phone");
      else if (e.status === "pending" && e.error) toast("bad", `Will retry sync — ${e.error}`);
    });

    const offCall = on<IncomingCall>("call", (m) => {
      if (m.state === "ringing") {
        const item: Notif = {
          key: m.key || "call-incoming",
          type: "call",
          app: "Phone",
          title: m.name || m.number || "Unknown caller",
          text: m.number && m.name ? m.number : "Incoming call on your phone",
          replyable: false,
          time: Date.now(),
        };
        setNotifs((list) => [item, ...list.filter((x) => x.key !== item.key)].slice(0, 100));
        setActiveCall({ state: "ringing", number: m.number, name: m.name });
      } else {
        // idle/ended — clear only a ringing overlay (leave an outbound dial alone)
        setActiveCall((prev) => (prev?.state === "ringing" ? null : prev));
      }
    });

    // Phase 13: live ADB call-state polling upgrades a Mac-initiated dial to
    // the rich overlay (mute/speaker/DTMF/duration) the moment ADB confirms
    // it — mirrors index.js's `onCallState` merge exactly (IDLE always
    // clears; anything else preserves the caller's name/number).
    const offQuality = onLinkQuality(setQuality);

    const offCallState = onCallState(({ state }) => {
      setActiveCall((prev) => {
        if (state === "IDLE") return null;
        return { state: state as "RINGING" | "ACTIVE", number: prev?.number, name: prev?.name };
      });
    });

    return () => {
      offStatus();
      offNotif();
      offNotifGone();
      offMedia();
      offPhonePush();
      offEditSync();
      offCall();
      offQuality();
      offCallState();
    };
  }, [toast]);

  // ── Phase 13: ADB/scrcpy fallback wiring ────────────────────────────────
  useEffect(() => {
    adbTools().then(setTools).catch(console.error);
    adbDevices().then(setDevices).catch(console.error);
    adbPairedInfo()
      .then((r) => setPairedGuid(r.guid))
      .catch(console.error);
    const offTools = onTools(setTools);
    const offDevices = onDevices(setDevices);
    const offWifiEvent = onWifiEvent((ev) => toast(ev.kind, ev.text));
    const offAppInfo = onAppDeviceInfo(setAppDeviceInfo);
    return () => {
      offTools();
      offDevices();
      offWifiEvent();
      offAppInfo();
    };
  }, [toast]);

  // Auto-pick the first live ("device"-state) ADB device, matching index.js's
  // `useEffect` that resets `serial` whenever the current one drops off.
  // Auto-pick only when the current choice is gone. An explicit pick from the
  // Devices tab survives, so plugging in a second phone no longer silently
  // reassigns every ADB action to whichever enumerated first.
  useEffect(() => {
    const ready = devices.filter((d) => d.state === "device");
    if (!ready.find((d) => d.serial === serial)) {
      setSerial(ready[0]?.serial ?? null);
      setDeviceInfo(null);
    }
  }, [devices, serial]);

  useEffect(() => {
    if (!serial) return;
    adbDeviceInfo(serial).then(setDeviceInfo).catch(() => setDeviceInfo(null));
  }, [serial]);

  useEffect(() => {
    if (!status.connected) setAppDeviceInfo(null);
  }, [status.connected]);

  // Tier B: pull the wallpaper once per connection, and drop every phone-derived
  // image on disconnect — the next phone to link may not be the same phone.
  // A phone that never granted storage access (or an older build without the
  // "wallpaper" cap) just errors here, and the card keeps its generated backdrop.
  useEffect(() => {
    if (!status.connected) {
      setWallpaper(null);
      setAlbumArt(null);
      setQuality(null);
      clearIcons();
      return;
    }
    let live = true;
    wallpaperGet()
      .then((url) => live && setWallpaper(url))
      .catch(() => live && setWallpaper(null));
    return () => {
      live = false;
    };
  }, [status.connected]);

  // Remember that we *had* a link, so a drop reads as "lost the phone" rather
  // than "never paired" — the two want different words on screen.
  const everLinked = useRef(false);
  useEffect(() => {
    if (status.connected) {
      everLinked.current = true;
      setLostLink(false);
    } else if (everLinked.current) {
      setLostLink(true);
    }
  }, [status.connected]);

  // ── ⌘-accelerators for the rail ─────────────────────────────────────────
  // Deliberately conservative: only ⌘-combos, so nothing a view already
  // handles (Enter-to-send in Messages/Notifications/Settings, double-click in
  // Files) can ever be intercepted. Text fields are skipped outright, and any
  // combo we don't own falls through untouched.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.metaKey || e.ctrlKey) return;

      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;

      if (e.altKey) {
        // ⌘⌥S — show/hide the phone panel. Matched on `code`, not `key`:
        // holding Option on macOS turns "s" into "ß".
        if (e.code === "KeyS") {
          e.preventDefault();
          setPhoneOpen((o) => !o);
        }
        return;
      }

      // ⌘1…⌘9 and ⌘, — jump straight to a view.
      const hit = ALL_ITEMS.find((i) => i.key === e.key);
      if (hit) {
        e.preventDefault();
        setView(hit.id);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const withFlag = useCallback(async (key: string, fn: () => Promise<void>) => {
    setBusy((b) => ({ ...b, [key]: true }));
    try {
      await fn();
    } finally {
      setBusy((b) => ({ ...b, [key]: false }));
    }
  }, []);

  const connectedDevice = devices.find((d) => d.serial === serial && d.state === "device") ?? null;

  const goWireless = () =>
    withFlag("wireless", async () => {
      if (!serial) return;
      try {
        const addr = await adbGoWireless(serial);
        toast("ok", `Wireless ADB on — ${addr}. You can unplug the cable.`);
      } catch (e) {
        toast("bad", String(e));
      }
    });

  const reconnectAdb = () =>
    withFlag("reconnect", async () => {
      try {
        const addr = await adbReconnectNow();
        toast("ok", `Reconnected — ${addr}`);
      } catch (e) {
        toast("bad", String(e));
      }
    });

  const unpairAdb = () =>
    withFlag("unpair", async () => {
      try {
        await adbUnpair();
        setPairedGuid(null);
        toast("ok", "Forgot this phone.");
      } catch (e) {
        toast("bad", String(e));
      }
    });

  const screenshotAdb = () =>
    withFlag("shot", async () => {
      if (!serial) return;
      try {
        await adbScreenshot(serial);
        toast("ok", "Screenshot saved to Downloads");
      } catch (e) {
        toast("bad", String(e));
      }
    });

  const installScrcpy = async () => {
    try {
      await adbScrcpyInstall();
      return { ok: true };
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  };

  const mirrorAdb = async () => {
    if (!tools?.scrcpy) return setSetup({ reason: "Screen mirroring needs scrcpy." });
    if (!serial) return;
    try {
      await adbMirror(serial);
    } catch (e) {
      toast("bad", String(e));
    }
  };

  const desktopAdb = async () => {
    if (!tools?.scrcpy) return setSetup({ reason: "Desktop mode needs scrcpy." });
    if (!serial) return;
    try {
      await adbDesktop(serial, config?.desktopDisplaySize);
    } catch (e) {
      // scrcpy itself rejects --new-display on Android < 11 or scrcpy < 2.5;
      // surface its message rather than a generic failure.
      toast("bad", String(e));
    }
  };

  const cameraAdb = async () => {
    if (!tools?.scrcpy) return setSetup({ reason: "Phone camera needs scrcpy." });
    if (!serial) return;
    try {
      await adbCamera(serial);
    } catch (e) {
      toast("bad", String(e));
    }
  };

  // Persist a single setting and reflect it locally (used by the notifications
  // "On Mac" toggle and the clipboard toggle; Settings does its own).
  const updateSetting = useCallback(
    async (key: string, value: unknown) => {
      try {
        setConfig(await setSetting(key, value));
      } catch (e) {
        toast("bad", String(e));
      }
    },
    [toast]
  );

  const linked = status.connected;

  // ── Phone-card quick actions ────────────────────────────────────────────
  // Every one of these is a second entry point to something that already
  // exists (Files' upload, the notifications toggle, clipboard sync) — none
  // introduces a new code path, so no existing flow changes behaviour.
  const quickActions: QuickAction[] = useMemo(
    () => [
      {
        id: "send",
        icon: "upload",
        label: "Send files to phone",
        disabled: !linked,
        onClick: async () => {
          const sel = await openDialog({ multiple: true });
          if (!sel) return;
          const paths = Array.isArray(sel) ? sel : [sel];
          for (const p of paths) {
            try {
              // Same destination TransferManager already lands Mac→phone
              // pushes in; Files' own uploader targets the browsed folder.
              await fsPush(p, "/sdcard/Download");
              toast("ok", `Sent ${p.split("/").pop()} → Download`);
            } catch (e) {
              toast("bad", String(e));
            }
          }
        },
      },
      {
        id: "browse",
        icon: "folder",
        label: "Browse phone files",
        disabled: !linked,
        onClick: () => setView("files"),
      },
      {
        id: "notifs",
        icon: "bell",
        label: config?.nativeNotifs ?? true ? "Mute Mac banners" : "Unmute Mac banners",
        active: !(config?.nativeNotifs ?? true),
        onClick: () => updateSetting("nativeNotifs", !(config?.nativeNotifs ?? true)),
      },
      {
        id: "clip",
        icon: "clipboard",
        label: "Send clipboard to phone",
        disabled: !linked,
        onClick: async () => {
          try {
            await clipboardPushNow();
            toast("ok", "Clipboard sent to phone");
          } catch (e) {
            toast("bad", String(e));
          }
        },
      },
    ],
    [linked, config?.nativeNotifs, updateSetting, toast]
  );

  const renderView = () => {
    switch (view) {
      case "dashboard":
        return <DashboardView onNavigate={setView} />;
      case "apps":
        return <AppsView linked={linked} adbSerial={connectedDevice?.serial ?? null} onToast={toast} />;
      case "files":
        return <FilesView linked={linked} onToast={toast} />;
      case "photos":
        return <PhotosView linked={linked} onToast={toast} />;
      case "messages":
        return <MessagesView linked={linked} target={messageTarget} onToast={toast} />;
      case "contacts":
        return (
          <ContactsView
            linked={linked}
            onToast={toast}
            onCall={(c) => setActiveCall({ state: "dialing", number: c.number, name: c.name })}
            onOpenSms={(c) => {
              setMessageTarget(c);
              setView("messages");
            }}
          />
        );
      case "calls":
        return <CallsView linked={linked} />;
      case "notifications":
        return (
          <NotificationsView
            linked={linked}
            items={notifs}
            config={config}
            onClear={() => setNotifs([])}
            onDismiss={(key) => {
              notifDismiss(key);
              setNotifs((l) => l.filter((x) => x.key !== key));
            }}
            onToggleNative={(on) => updateSetting("nativeNotifs", on)}
            onToggleMute={(pkg, muted) => {
              const current = config?.mutedApps ?? [];
              updateSetting(
                "mutedApps",
                muted ? [...current, pkg] : current.filter((p) => p !== pkg)
              );
            }}
            onToast={toast}
          />
        );
      case "clipboard":
        return (
          <ClipboardView
            linked={linked}
            config={config}
            onToggle={(on) => updateSetting("clipboardSync", on)}
          />
        );
      case "media":
        return <MediaView media={media} />;
      case "settings":
        return <SettingsView config={config} onConfig={setConfig} onToast={toast} />;
      case "mirror":
        return (
          <MirrorView
            linked={linked}
            adbSerial={connectedDevice?.serial ?? null}
            scrcpyReady={!!tools?.scrcpy}
            onAdbMirror={mirrorAdb}
            onAdbDesktop={desktopAdb}
            defaultMode={config?.defaultMirrorMode ?? "wifi"}
            onToast={toast}
          />
        );
      case "camera":
        return (
          <CameraView
            linked={linked}
            adbSerial={connectedDevice?.serial ?? null}
            scrcpyReady={!!tools?.scrcpy}
            onAdbCamera={cameraAdb}
            onToast={toast}
          />
        );
      case "devices":
        return (
          <DevicesView
            connected={connectedDevice}
            info={deviceInfo}
            appInfo={appDeviceInfo}
            wifi={status}
            tools={tools}
            busy={busy}
            paired={!!pairedGuid}
            onPair={() => setView("dashboard")}
            onWireless={goWireless}
            onPairWireless={() => setWPairOpen(true)}
            onUnpair={unpairAdb}
            onReconnect={reconnectAdb}
            onScreenshot={screenshotAdb}
            devices={devices.filter((d) => d.state === "device")}
            selected={serial}
            onSelect={(s) => {
              setSerial(s);
              setDeviceInfo(null);
            }}
          />
        );
      default:
        return null;
    }
  };

  return (
    <div className="flex h-screen w-full overflow-hidden">
      <Rail
        view={view}
        setView={setView}
        notifCount={notifs.length}
        phoneOpen={phoneOpen}
        onTogglePhone={() => setPhoneOpen((o) => !o)}
      />

      {/* The phone panel — the app's centre of gravity, present on every view.
          Collapsible (⌘⌥S) so a wide Files or Messages session can reclaim
          the column. */}
      {phoneOpen && (
        <aside className="glass-chrome flex w-64 shrink-0 flex-col border-r border-line">
          <div className="drag h-7 shrink-0" style={{ WebkitAppRegion: "drag" } as React.CSSProperties} />
          <div className="no-drag min-h-0 flex-1 p-3 pt-1">
            <PhoneCard
              status={status}
              info={appDeviceInfo}
              media={media}
              config={config}
              adb={connectedDevice}
              quality={quality}
              ip={pairing?.ips[0] ?? null}
              port={pairing?.port ?? null}
              actions={quickActions}
              artwork={(media?.active && albumArt) || wallpaper}
              onRecentError={(m) => toast("bad", m)}
              onPair={() => setView("dashboard")}
            />
          </div>
        </aside>
      )}

      <main className="app-surface relative flex min-w-0 flex-1 flex-col overflow-hidden">
        {/* Title bar: gives the traffic lights a drag region now that the
            sidebar no longer spans the window, and names the current view. */}
        <header
          className="drag flex h-11 shrink-0 items-center gap-2 border-b border-line px-5"
          style={{ WebkitAppRegion: "drag" } as React.CSSProperties}
        >
          <h2 className="font-display text-[13px] font-semibold text-fg/85">{itemFor(view).label}</h2>
          {linked && (
            <span className="flex items-center gap-1.5 text-[11.5px] text-(--color-link)">
              <span className="led h-1.5 w-1.5 rounded-full bg-(--color-link)" />
              {status.phoneName ?? "Linked"}
            </span>
          )}
        </header>

        <div className="min-h-0 flex-1 overflow-hidden">{renderView()}</div>

        {/* Lost-link strip. Only shown once a link actually existed, so a
            never-paired app doesn't nag — the phone card already says that. */}
        {(lostLink && !linked) || quality?.grade === "stalled" ? (
          <div className="rise-fast pointer-events-none absolute inset-x-0 bottom-4 flex justify-center px-4">
            <div className="glass-heavy pointer-events-auto flex items-center gap-3 rounded-full border border-line px-4 py-2 float-md">
              <Icon name="wifi" size={14} className="shrink-0 text-(--color-warn)" />
              <span className="text-[12.5px] text-fg/85">
                {quality?.grade === "stalled"
                  ? `${status.phoneName ?? "Your phone"} stopped responding — the connection is still open but idle.`
                  : `Lost the link to ${status.phoneName ?? "your phone"} — waiting for it to come back.`}
              </span>
              <button onClick={() => setLostLink(false)} className="btn-icon shrink-0" title="Dismiss">
                <Icon name="x" size={13} />
              </button>
            </div>
          </div>
        ) : null}
      </main>

      {activeCall && <CallOverlay call={activeCall} onDismiss={() => setActiveCall(null)} onToast={toast} />}
      {setup && (
        <SetupModal tools={tools} reason={setup.reason} onClose={() => setSetup(null)} onInstallScrcpy={installScrcpy} />
      )}
      {wPairOpen && (
        <WirelessPairModal
          onClose={() => setWPairOpen(false)}
          onPaired={() => adbPairedInfo().then((r) => setPairedGuid(r.guid))}
          onToast={toast}
        />
      )}
      {onboarding && (
        <Onboarding
          onClose={() => {
            localStorage.setItem("onboarded", "1");
            setOnboarding(false);
          }}
          onGoToDashboard={() => setView("dashboard")}
        />
      )}
      <Toasts items={toasts} />
    </div>
  );
}
