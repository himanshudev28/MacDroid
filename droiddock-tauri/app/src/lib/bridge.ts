import { Channel, invoke as tauriInvoke, isTauri } from "@tauri-apps/api/core";
import { listen as tauriListen } from "@tauri-apps/api/event";

export function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauri()) {
    if (cmd === "wifi_status") {
      return Promise.resolve({ connected: false, phoneName: null } as unknown as T);
    }
    if (cmd === "get_config") {
      return Promise.resolve({
        token: "demo-token-123",
        port: 48484,
        notifications: true,
        nativeNotifs: true,
        clipboardSync: true,
        deviceName: "MacBook Pro",
        deviceGuid: "mac-guid-123",
        tcpAddr: null,
        autoReconnect: true,
        pausedUntil: null,
        photoSyncEnabled: false,
        photoSyncDest: null,
        macFsEnabled: false,
        macFsRoots: [],
        encryptLink: false,
        remoteControl: false,
        mutedApps: [],
        menubarText: "battery",
        menubarBatteryStyle: "percent",
        menubarMaxLen: 20,
        menubarAlbumArt: "thumb",
        lowBatteryAlert: true,
        lowBatteryPct: 20,
        desktopDisplaySize: "",
        defaultMirrorMode: "wifi",
        widgetEnabled: false,
      } as unknown as T);
    }
    if (cmd === "get_appearance") {
      return Promise.resolve({
        accent_color: "#0a84ff",
        reduce_transparency: false,
      } as unknown as T);
    }
    if (cmd === "adb_tools") {
      return Promise.resolve({
        adb: false,
        scrcpy: false,
        brew: true,
        adbPath: null,
        scrcpyPath: null,
      } as unknown as T);
    }
    if (cmd === "adb_devices") return Promise.resolve([] as unknown as T);
    if (cmd === "contacts_list") return Promise.resolve([] as unknown as T);
    if (cmd === "apps_list") return Promise.resolve([] as unknown as T);
    if (cmd === "photos_list") return Promise.resolve([] as unknown as T);
    if (cmd === "fs_list") return Promise.resolve([] as unknown as T);
    if (cmd === "sms_threads") return Promise.resolve([] as unknown as T);
    if (cmd === "autostart_get") return Promise.resolve(false as unknown as T);
    if (cmd === "adb_paired_info") return Promise.resolve({ guid: null } as unknown as T);
    return Promise.resolve(null as unknown as T);
  }
  return tauriInvoke<T>(cmd, args);
}

/// Subscribe to a Tauri event (the `invoke()`+`listen()` shim that replaces the
/// Electron `window.droid.on*` preload bridge). Returns an unsubscribe fn that
/// is safe to call before the async `listen` has resolved.
export function on<T>(event: string, cb: (payload: T) => void): () => void {
  if (!isTauri()) {
    return () => {};
  }
  let un: (() => void) | null = null;
  let cancelled = false;
  tauriListen<T>(event, (e) => cb(e.payload)).catch(() => {}).then((f) => {
    if (f) {
      if (cancelled) f();
      else un = f;
    }
  });
  return () => {
    cancelled = true;
    un?.();
  };
}

// ── Config / settings ────────────────────────────────────────────────────

export type DroidConfig = {
  token: string;
  port: number;
  notifications: boolean;
  nativeNotifs: boolean;
  clipboardSync: boolean;
  deviceName: string | null;
  deviceGuid: string | null;
  tcpAddr: string | null;
  autoReconnect: boolean;
  pausedUntil: number | null;
  photoSyncEnabled: boolean;
  photoSyncDest: string | null;
  /// Phase 19: reverse file browsing. Now opt-in — the Mac only advertises the
  /// capability (and the phone only shows the tab) while this is on.
  macFsEnabled: boolean;
  /// Mac directories the paired phone may browse/pull from — edited wholesale
  /// (add/remove folder) via `setSetting`, same as any other setting here.
  macFsRoots: string[];
  /// Tier C: AES-256-GCM on JSON control messages, keyed off the pairing token.
  /// Off by default and negotiated per connection — see the Settings copy for
  /// exactly what it does and doesn't cover.
  encryptLink: boolean;
  /// Tier D: let the paired phone drive this Mac's pointer and keyboard.
  /// Off by default; the Mac only advertises the capability while it's on.
  remoteControl: boolean;
  /// Packages whose notifications never raise a macOS banner. They still appear
  /// in the in-app list — this mutes the interruption, not the record.
  mutedApps: string[];
  /// Menu bar: what shows beside the tray icon and how.
  menubarText: "none" | "battery" | "media" | "device";
  menubarBatteryStyle: "percent" | "bar" | "both";
  menubarMaxLen: number;
  menubarAlbumArt: "none" | "thumb" | "background";
  /// Raise a banner when the phone drops below `lowBatteryPct` on battery.
  lowBatteryAlert: boolean;
  lowBatteryPct: number;
  /// Virtual-display size for desktop mode, e.g. "1920x1080". Empty = device default.
  desktopDisplaySize: string;
  /// Which mirror the Mirror tab's primary action starts.
  defaultMirrorMode: "wifi" | "adb" | "desktop";
  /// The floating always-on-top status widget.
  widgetEnabled: boolean;
};

export const getConfig = () => invoke<DroidConfig>("get_config");
export const setSetting = (key: string, value: unknown) =>
  invoke<DroidConfig>("set_setting", { key, value });
/// Config can also change from the tray (Phase 14 pause) or an expiry
/// timer, not just `setSetting` — subscribe to pick those up live.
export const onConfigUpdate = (cb: (c: DroidConfig) => void) => on<DroidConfig>("config", cb);

export type SystemAppearance = { accent_color: string; reduce_transparency: boolean };
export const getAppearance = () => invoke<SystemAppearance>("get_appearance");

// ── Phase 4: notifications ───────────────────────────────────────────────

export type Notif = {
  key: string;
  app?: string;
  title?: string;
  text?: string;
  replyable?: boolean;
  time?: number;
  type?: string; // "call" for incoming-call cards
  /// Tier B: source package, used to fetch the real app icon.
  pkg?: string;
  /// v4 parity. `low`/`min` land in the panel but never raise a banner —
  /// Android already judged them background noise.
  priority?: "max" | "high" | "default" | "low" | "min";
  ongoing?: boolean;
  /// Download/upload progress. `progressIndeterminate` means "working, no
  /// percentage" — a spinner, not a bar.
  progress?: number;
  progressMax?: number;
  progressIndeterminate?: boolean;
  /// Plain action-button labels, in the order the phone will fire them by index.
  actions?: string[];
  number?: string;
  name?: string;
};

export const notifReply = (key: string, text: string) =>
  invoke<void>("notif_reply", { key, text });
export const notifDismiss = (key: string) => invoke<void>("notif_dismiss", { key });
/// Fire the Nth action button of a notification back on the phone.
export const notifAction = (key: string, index: number) =>
  invoke<void>("notif_action", { key, index });

// ── Phase 5: files ───────────────────────────────────────────────────────

export type FsEntry = { name: string; dir: boolean; size: number; modified: number };

export const fsList = (path: string) => invoke<FsEntry[]>("fs_list", { path });
export const fsDelete = (path: string) => invoke<void>("fs_delete", { path });
export const fsRename = (path: string, newName: string) =>
  invoke("fs_rename", { path, newName });
export const fsPull = (path: string, name: string) =>
  invoke<string>("fs_pull", { path, name });
export const fsPush = (localPath: string, dest: string) =>
  invoke<void>("fs_push", { localPath, dest });
export const fsCancel = (transferId: number) => invoke<void>("fs_cancel", { transferId });

export type Progress = {
  transferId: number;
  name: string;
  sent: number;
  total: number;
  dir: "push" | "pull" | "phone";
  done: boolean;
  error: string | null;
};

// ── Phase 17: open-in-place with edit-writeback ──────────────────────────

export const fsOpenInPlace = (path: string) => invoke<void>("fs_open_in_place", { path });
/// Phone paths with an edit-in-place save still waiting to sync back — used to
/// hydrate the pending-sync badge on mount (live `edit-sync` events alone miss
/// anything that settled while the Files tab was unmounted).
export const fsPendingSyncs = () => invoke<string[]>("fs_pending_syncs");

export type EditSync = {
  localPath: string;
  phonePath: string;
  status: "syncing" | "synced" | "pending";
  error: string | null;
};
export const onEditSync = (cb: (e: EditSync) => void) => on<EditSync>("edit-sync", cb);

// ── Phase 6: photos ──────────────────────────────────────────────────────

export type MediaItem = {
  id: number;
  name: string;
  date: number;
  size: number;
  path: string;
  kind: "image" | "video";
  duration?: number;
};

export const photosList = (offset: number, limit: number) =>
  invoke<MediaItem[]>("photos_list", { offset, limit });
export const photoThumb = (id: number, kind: string) =>
  invoke<string>("photo_thumb", { id, kind });
export const photoOpen = (path: string, name: string) =>
  invoke<void>("photo_open", { path, name });

// ── Phase 18: photo auto-sync ─────────────────────────────────────────────

/// Manual "sync everything already on the phone" action — ignores the enable
/// toggle/caps (those only gate the automatic forward-sync path).
export const photoSyncBackfill = () => invoke<void>("photo_sync_backfill");

export type PhotoSyncProgress = {
  done: number;
  total: number;
  name: string | null;
  error: string | null;
};
export const onPhotoSyncProgress = (cb: (p: PhotoSyncProgress) => void) =>
  on<PhotoSyncProgress>("photosync-progress", cb);

// ── Phase 7: messages (SMS) ──────────────────────────────────────────────

export type SmsThread = {
  threadId: number;
  address: string;
  name: string;
  snippet: string;
  date: number;
};
export type SmsMessage = { id: number; body: string; date: number; out: boolean };

export const smsThreads = () => invoke<SmsThread[]>("sms_threads");
export const smsMessages = (threadId: number) =>
  invoke<{ messages: SmsMessage[]; address: string }>("sms_messages", { threadId });
export const smsSend = (address: string, text: string) =>
  invoke<void>("sms_send", { address, text });

// ── Phase 8: contacts ────────────────────────────────────────────────────

export type Contact = { name: string; number: string; starred: boolean };
export const contactsList = () => invoke<Contact[]>("contacts_list");

// ── Phase 9: calls ───────────────────────────────────────────────────────

export type IncomingCall = { state: string; number?: string; name?: string; key?: string };
export const actionCall = (number: string) => invoke<void>("action_call", { number });

// ── Phase 10: media remote ───────────────────────────────────────────────

export type MediaState = {
  active: boolean;
  title?: string;
  artist?: string;
  app?: string;
  playing?: boolean;
  vol?: number;
  volMax?: number;
  pos?: number;
  dur?: number;
  /// Tier B. Identifies the *artwork*, not the position — the phone only sends
  /// `art` when this changes (it pushes once a second while playing, so inlining
  /// the image every tick would dwarf all other traffic on the link).
  trackKey?: string;
  /// Base64 JPEG, present only on a track change or right after link-up.
  /// Explicitly `null` means "this track has no art" — drop any cached image.
  art?: string | null;
};
export const mediaCmd = (cmd: string, value = 0) =>
  invoke<void>("media_cmd", { cmd, value });

// ── Phase 3 (addendum): explicit clipboard push ──────────────────────────

/// Send the Mac's current pasteboard text to the phone on demand. The 1s
/// watcher still runs and still owns automatic sync — this is the phone card's
/// "send clipboard now" button, which deliberately bypasses the echo guards.
/// Rejects with a human-readable reason (sync off / no phone / empty).
export const clipboardPushNow = () => invoke<void>("clipboard_push_now");

// ── Tier B: wallpaper + apps ─────────────────────────────────────────────

/// The phone's wallpaper as a data URL. Caps-gated on the phone (`"wallpaper"`);
/// rejects with a readable reason if storage access was declined, in which case
/// the phone card keeps its generated backdrop.
export const wallpaperGet = () => invoke<string>("wallpaper_get");

export type PhoneApp = { pkg: string; label: string };

/// Every launchable app on the phone, label-sorted. Caps-gated (`"apps"`).
export const appsList = () => invoke<PhoneApp[]>("apps_list");
/// One app icon as a PNG data URL. Answers over the same binary frame photo
/// thumbnails use, so it inherits their timeout and error path.
export const appIcon = (pkg: string) => invoke<string>("app_icon", { pkg });
/// Open an app on the phone. Fire-and-forget.
export const appLaunch = (pkg: string) => invoke<void>("app_launch", { pkg });

// ── Phase 11: screen mirroring ───────────────────────────────────────────

export type MirrorStarted = {
  width: number;
  height: number;
  codec: string;
  source: "screen" | "camera";
  facing: "front" | "back";
};
export type MirrorError = { error?: string };

export const mirrorPopout = (source: "screen" | "camera") =>
  invoke<void>("mirror_popout", { source });
export const mirrorStop = () => invoke<void>("mirror_stop");
export const mirrorFocus = () => invoke<void>("mirror_focus");
export const mirrorSetOnTop = (on: boolean) => invoke<void>("mirror_set_on_top", { on });
export const mirrorInput = (msg: Record<string, unknown>) => invoke<void>("mirror_input", { msg });

/// Register the pop-out's frame sink with the backend. Each message is the raw
/// `[flags][H.264 access unit]` bytes of one kind-3 wire frame (flags bit0 =
/// keyframe) delivered over a Tauri IPC channel (ArrayBuffer — no base64/JSON
/// per frame). Resolves with the pending `mirror-started` payload when the
/// stream was announced before this window finished loading (the
/// `did-finish-load` replay in the Electron reference), else null.
export const mirrorAttach = (onFrame: (key: boolean, data: Uint8Array) => void) => {
  const channel = new Channel<ArrayBuffer | number[]>();
  channel.onmessage = (buf) => {
    const bytes = buf instanceof ArrayBuffer ? new Uint8Array(buf) : Uint8Array.from(buf);
    if (bytes.length < 2) return;
    onFrame((bytes[0] & 1) === 1, bytes.subarray(1));
  };
  return invoke<MirrorStarted | null>("mirror_attach", { channel });
};

export const onMirrorStarted = (cb: (m: MirrorStarted) => void) => on<MirrorStarted>("mirror-started", cb);
export const onMirrorStopped = (cb: () => void) => on<void>("mirror-stopped", cb);
export const onMirrorError = (cb: (e: MirrorError) => void) => on<MirrorError>("mirror-error", cb);

// ── Phase 13: ADB/scrcpy fallback ─────────────────────────────────────────

export type ToolsStatus = {
  adb: boolean;
  scrcpy: boolean;
  brew: boolean;
  adbPath: string | null;
  scrcpyPath: string | null;
};
export type AdbDevice = {
  id: string;
  serial: string;
  model: string;
  state: string;
  transport: "usb" | "wifi";
  usbSerial: string | null;
  wifiSerial: string | null;
};
export type DeviceInfo = { model: string; android: string; sdk: string; battery: number | null; charging: boolean };
export type Volume = { level: number; max: number };
export type QrPairStatus = { state: "waiting" | "connecting" | "connected" | "error"; text: string; addr?: string | null };

export const adbTools = () => invoke<ToolsStatus>("adb_tools");
export const adbScrcpyInstall = () => invoke<void>("adb_scrcpy_install");
export const adbDevices = () => invoke<AdbDevice[]>("adb_devices");
export const adbDeviceInfo = (serial: string) => invoke<DeviceInfo>("adb_device_info", { serial });
export const adbGoWireless = (serial: string) => invoke<string>("adb_go_wireless", { serial });
export const adbPairWireless = (hostPort: string, code: string) =>
  invoke<{ guid: string; addr: string | null }>("adb_pair_wireless", { hostPort, code });
export const adbUnpair = () => invoke<void>("adb_unpair");
export const adbPairedInfo = () => invoke<{ guid: string | null }>("adb_paired_info");
export const adbReconnectNow = () => invoke<string>("adb_reconnect_now");
export const adbQrPairStart = (serviceName: string, password: string) =>
  invoke<void>("adb_qr_pair_start", { serviceName, password });
export const adbQrPairCancel = () => invoke<void>("adb_qr_pair_cancel");
export const adbCamera = (serial: string) => invoke<void>("adb_camera", { serial });
export const adbMirror = (serial: string) => invoke<void>("adb_mirror", { serial });
/// Tier D: mirror a new *virtual* display instead of the phone's screen, so the
/// phone stays usable — scrcpy's `--new-display`, i.e. "wireless DeX".
/// Needs Android 11+ and scrcpy 2.5+; older combinations fail at spawn.
export const adbDesktop = (serial: string, size?: string) =>
  invoke<void>("adb_desktop", { serial, size: size || null });
/// Tier D: open one Android app in its own Mac window. With `newDisplay` it
/// lands on a virtual display and the phone screen is left alone.
export const adbMirrorApp = (serial: string, packageName: string, newDisplay: boolean) =>
  invoke<void>("adb_mirror_app", { serial, package: packageName, newDisplay });
export const adbScreenshot = (serial: string) => invoke<string>("adb_screenshot", { serial });
export const adbVolumeGet = () => invoke<Volume>("adb_volume_get");
export const adbVolumeSet = (level: number, currentLevel: number) =>
  invoke<string>("adb_volume_set", { level, currentLevel });
export const adbCallEnd = () => invoke<void>("adb_call_end");
export const adbCallSpeaker = () => invoke<void>("adb_call_speaker");
export const adbCallMute = () => invoke<void>("adb_call_mute");
export const adbCallDtmf = (digit: string) => invoke<void>("adb_call_dtmf", { digit });
export const adbCallStartPolling = (serial?: string) => invoke<void>("adb_call_start_polling", { serial: serial ?? null });

export const onTools = (cb: (t: ToolsStatus) => void) => on<ToolsStatus>("tools", cb);
export const onDevices = (cb: (d: AdbDevice[]) => void) => on<AdbDevice[]>("devices", cb);
export const onQrPairStatus = (cb: (s: QrPairStatus) => void) => on<QrPairStatus>("adb-qr-status", cb);
export const onWifiEvent = (cb: (e: { kind: "ok" | "bad" | "info"; text: string }) => void) =>
  on<{ kind: "ok" | "bad" | "info"; text: string }>("wifi-event", cb);
export const onCallState = (cb: (s: { state: string; serial: string }) => void) =>
  on<{ state: string; serial: string }>("call-state", cb);

/// Phone-pushed `device-info` over the Wi-Fi app link (model/battery/charging
/// the companion app reports on its own, independent of ADB) — forwarded
/// as-is by `ws_server::route_text`. Shape is a loose superset since it's
/// whatever the phone happens to include.
export type AppDeviceInfo = { model?: string; android?: string; battery?: number; charging?: boolean };
export const onAppDeviceInfo = (cb: (i: AppDeviceInfo) => void) => on<AppDeviceInfo>("device-info", cb);

// ── Phase 14: native integration polish ───────────────────────────────────

export const pauseSet = (until: number | null) => invoke<DroidConfig>("pause_set", { until });

/// Menu-bar panel window controls (see `tray.rs`). The panel is a separate
/// webview loading this bundle at `#menubar`, so it re-subscribes to the same
/// app-wide events the main window does — `app.emit` broadcasts to every window.
// ── Tier C: link quality ─────────────────────────────────────────────────

/// Published by the Rust `link_quality` probe loop whenever the grade changes
/// (not on every sample). `stalled` means the socket is still open but the
/// phone's read loop stopped answering — the case a plain connected/disconnected
/// flag can never see.
export type LinkQuality = {
  rttMs: number | null;
  grade: "good" | "fair" | "weak" | "stalled";
};
export const onLinkQuality = (cb: (q: LinkQuality) => void) => on<LinkQuality>("link-quality", cb);

export const menubarHide = () => invoke<void>("menubar_hide");
export const openMainWindow = () => invoke<void>("open_main_window");
/// Show/hide the floating status widget. Persists, so it reopens on restart.
export const widgetSet = (show: boolean) => invoke<void>("widget_set", { show });
export const autostartGet = () => invoke<boolean>("autostart_get");
export const autostartSet = (enabled: boolean) => invoke<void>("autostart_set", { enabled });
