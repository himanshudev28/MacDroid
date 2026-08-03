import { useCallback, useEffect, useRef, useState } from "react";
import Sidebar, { type ViewId } from "./components/Sidebar";
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
import { onWifiStatus, type WifiStatus } from "./lib/wifi";
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
  adbCamera,
  onTools,
  onDevices,
  onWifiEvent,
  onAppDeviceInfo,
  onCallState,
  onConfigUpdate,
  onEditSync,
  type DroidConfig,
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

    const offMedia = on<MediaState>("media", setMedia);

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

  const renderView = () => {
    switch (view) {
      case "dashboard":
        return <DashboardView onNavigate={setView} />;
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
          />
        );
      default:
        return null;
    }
  };

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar view={view} setView={setView} status={status} notifCount={notifs.length} />

      <main className="relative flex min-w-0 flex-1 flex-col overflow-hidden bg-ink">
        <div className="min-h-0 flex-1 overflow-hidden">{renderView()}</div>
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
      <Toasts items={toasts} />
    </div>
  );
}
