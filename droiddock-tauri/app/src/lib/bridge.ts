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
        quickShareEnabled: false,
        macFsRoots: [],
        encryptLink: false,
        remoteControl: false,
        macInfoSync: true,
        macMediaSync: true,
        macMediaBrowser: true,
        mutedApps: [],
        menubarText: "battery",
        menubarBatteryStyle: "percent",
        menubarMaxLen: 20,
        menubarAlbumArt: "thumb",
        lowBatteryAlert: true,
        lowBatteryPct: 20,
        desktopDisplaySize: "",
        desktopUiMode: "desktop",
        desktopFlex: true,
        appWindowChrome: false,
        appWindowKeepAlive: true,
        mirrorCodec: "h264",
        mirrorAudio: true,
        scrcpyUhid: false,
        scrcpyStayAwake: false,
        scrcpyTurnScreenOff: false,
        scrcpyAlwaysOnTop: false,
        pinnedApps: [],
        defaultMirrorMode: "wifi",
        mirrorBitrateMbps: 12,
        mirrorFps: 60,
        mirrorMaxSize: 0,
        widgetEnabled: false,
        autoCheckUpdates: true,
        lastUpdateCheck: 0,
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
        scrcpyVersion: null,
        caps: { virtualDisplay: false, keepContent: false, flexDisplay: false, uhid: false },
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
    if (cmd === "app_version") return Promise.resolve("0.0.0-dev" as unknown as T);
    // Not `null`: the browser preview has no bundle to replace, and reporting
    // "you're up to date" there would be a lie the moment someone tested it.
    if (cmd === "update_check") {
      return Promise.reject(new Error("Updates aren't available in the browser preview."));
    }
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
  quickShareEnabled: boolean;
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
  /// Tell the phone this Mac's name and battery. Read-only in that direction,
  /// so unlike `remoteControl` this is on by default.
  macInfoSync: boolean;
  /// Push what's playing on this Mac to the phone. Read-only, so on by default.
  macMediaSync: boolean;
  /// Also read the active browser tab's title, but only on known media hosts —
  /// this is what makes YouTube show a track name instead of "Playing on your Mac".
  macMediaBrowser: boolean;
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
  /// Which Android layout a virtual display asks for. `desktop` forces ~160dpi
  /// so Android serves its large-screen layouts; `phone` leaves the device's
  /// own density alone, which is what makes an app look like a magnified phone.
  desktopUiMode: "desktop" | "tablet" | "phone";
  /// Resize the Android display with the Mac window (scrcpy 4.0+).
  desktopFlex: boolean;
  /// Keep the launcher/status/nav bars around a single-app window.
  appWindowChrome: boolean;
  /// On close, hand the app back to the phone instead of killing it (scrcpy 3.1+).
  appWindowKeepAlive: boolean;
  /// `h264` (scrcpy's default, flag omitted) or `h265`.
  mirrorCodec: "h264" | "h265";
  /// Forward phone audio over the ADB mirror. On by default — this only ever
  /// adds `--no-audio` when switched off.
  mirrorAudio: boolean;
  /// scrcpy passthrough, all opt-in.
  scrcpyUhid: boolean;
  scrcpyStayAwake: boolean;
  scrcpyTurnScreenOff: boolean;
  scrcpyAlwaysOnTop: boolean;
  /// Packages pinned to the top of the Apps grid, in user order.
  pinnedApps: string[];
  /// What a single click in the Apps grid does: `false` opens the app on the
  /// phone, `true` opens it in its own Mac window (virtual display + scrcpy).
  /// Holding Option always does the other one.
  openAppsOnMac: boolean;
  /// Which mirror the Mirror tab's primary action starts.
  defaultMirrorMode: "wifi" | "adb" | "desktop";
  /// Mirror quality, shared by both transports — Wi-Fi sends these to the
  /// phone's encoder on `mirror-start`, ADB passes them to scrcpy.
  mirrorBitrateMbps: number;
  mirrorFps: number;
  /// Longest edge in px; 0 = the phone's own resolution.
  mirrorMaxSize: number;
  /// The floating always-on-top status widget.
  widgetEnabled: boolean;
  /// Look for a new release ~10s after launch, at most once a day.
  autoCheckUpdates: boolean;
  lastUpdateCheck: number;
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
/// The last `media` push, artwork included. `media` is emit-only, and the phone
/// attaches `art` only on a track change — so anything that mounts mid-track
/// (a reload, the menu-bar panel, the widget) has to ask for the current state
/// rather than wait for an event that may not come until the next song.
export const mediaState = () => invoke<MediaState | null>("media_state");

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

/// Report whether this WebView can decode HEVC. Until this says yes, the
/// backend never asks the phone for H.265 — a codec the decoder then refuses
/// produces a black window, not an error.
export const mirrorSetHevcSupported = (supported: boolean) =>
  invoke<void>("mirror_set_hevc_supported", { supported });

// ── Phone audio over Wi-Fi (kind-4 frames) ────────────────────────────────

export type AudioStarted = { sampleRate: number; channels: number; format: string };
export type AudioError = { error: string };

/// Attach the main window's PCM channel. `reset` is set on the first chunk
/// after a silent gap, telling the player its schedule has gone stale.
/// Resolves with a replayed `audio-started` when the stream began before this
/// window was listening.
export const audioAttach = (onChunk: (reset: boolean, pcm: Uint8Array) => void) => {
  const channel = new Channel<ArrayBuffer | number[]>();
  channel.onmessage = (buf) => {
    const bytes = buf instanceof ArrayBuffer ? new Uint8Array(buf) : Uint8Array.from(buf);
    if (bytes.length < 2) return;
    onChunk((bytes[0] & 1) === 1, bytes.subarray(1));
  };
  return invoke<AudioStarted | null>("audio_attach", { channel });
};

// ── Quick Share (receive) ─────────────────────────────────────────────────

export type QuickShareFile = { name: string; size: number; mime: string };
export type QuickShareRequest = {
  id: number;
  peer: string;
  pin: string;
  files: QuickShareFile[];
};

/// Start or stop advertising this Mac as a Quick Share target.
export const quickshareSetEnabled = (on: boolean) =>
  invoke<boolean>("quickshare_set_enabled", { on });
export const quickshareStatus = () => invoke<boolean>("quickshare_status");
/// Answer a pending transfer prompt. The sender is waiting on this.
export const quickshareRespond = (id: number, accept: boolean) =>
  invoke<void>("quickshare_respond", { id, accept });

export const onQuickShareRequest = (cb: (r: QuickShareRequest) => void) =>
  on<QuickShareRequest>("quickshare-request", cb);
export const onQuickShareProgress = (cb: (p: { received: number; total: number }) => void) =>
  on<{ received: number; total: number }>("quickshare-progress", cb);
export const onQuickShareReceived = (cb: (r: { paths: string[] }) => void) =>
  on<{ paths: string[] }>("quickshare-received", cb);
export const onQuickShareError = (cb: (e: { error: string }) => void) =>
  on<{ error: string }>("quickshare-error", cb);

export const onAudioStarted = (cb: (m: AudioStarted) => void) => on<AudioStarted>("audio-started", cb);
export const onAudioStopped = (cb: () => void) => on<void>("audio-stopped", cb);
export const onAudioError = (cb: (e: AudioError) => void) => on<AudioError>("audio-error", cb);

// ── Phase 13: ADB/scrcpy fallback ─────────────────────────────────────────

/// Which optional scrcpy features the resolved binary actually supports.
///
/// The virtual-display flags arrived across four releases and an older scrcpy
/// rejects the whole command line rather than ignoring an unknown option — so
/// the UI disables an action with a reason instead of letting the spawn fail.
export type ScrcpyCaps = {
  /// `--new-display` — desktop mode and per-app windows (scrcpy 3.0+).
  virtualDisplay: boolean;
  /// `--no-vd-destroy-content` (3.1+).
  keepContent: boolean;
  /// `--flex-display` (4.0+).
  flexDisplay: boolean;
  /// `--keyboard=uhid` (2.4+).
  uhid: boolean;
};

export type ToolsStatus = {
  adb: boolean;
  scrcpy: boolean;
  brew: boolean;
  adbPath: string | null;
  scrcpyPath: string | null;
  /// e.g. "4.1", or null when scrcpy is missing or didn't answer `--version`.
  scrcpyVersion: string | null;
  caps: ScrcpyCaps;
};

/// The phone's freeform/desktop-windowing settings. Read-only unless the user
/// presses Enable — these persist after DroidDock is uninstalled.
export type FreeformStatus = {
  sdk: number | null;
  supported: boolean;
  values: (string | null)[];
  enabled: boolean;
};
/// `droiddock://…` routes, forwarded from the Rust side. See automation.rs for
/// why these come back through the UI rather than being run directly.
export type AutomationEvent =
  | { action: "mirror" }
  | { action: "mirror-adb" }
  | { action: "desktop" }
  | { action: "app"; pkg: string }
  | { action: "clipboard-push" };
export const onAutomation = (cb: (e: AutomationEvent) => void) =>
  on<AutomationEvent>("automation", cb);

/// The in-app ADB mirror: scrcpy's server driven directly, decoded by the
/// pop-out's own WebCodecs pipeline. No MediaProjection consent tap, and the
/// window is ours rather than scrcpy's.
export const scrcpyEmbeddedStart = (serial: string) =>
  invoke<void>("scrcpy_embedded_start", { serial });
export const scrcpyEmbeddedStop = () => invoke<void>("scrcpy_embedded_stop");
export const scrcpyEmbeddedRunning = () => invoke<boolean>("scrcpy_embedded_running");

/// The phone's storage mounted as a Finder volume. Read-only by design — see
/// webdav.rs for why writes are not implemented.
export type WebdavStatus = {
  running: boolean;
  url: string | null;
  mountPoint: string | null;
};
export const webdavStart = () => invoke<WebdavStatus>("webdav_start");
export const webdavStop = () => invoke<WebdavStatus>("webdav_stop");
export const webdavStatus = () => invoke<WebdavStatus>("webdav_status");

/// Local-only panic logs (~/Library/Logs/DroidDock). There is no reporting
/// service behind these — see `crash.rs` for why.
export const crashLogCount = () => invoke<number>("crash_log_count");
export const crashLogsReveal = () => invoke<void>("crash_logs_reveal");
export const crashLogsClear = () => invoke<number>("crash_logs_clear");

export const adbFreeformStatus = () => invoke<FreeformStatus>("adb_freeform_status");
export const adbFreeformEnable = () => invoke<FreeformStatus>("adb_freeform_enable");
export const adbFreeformRevert = () => invoke<FreeformStatus>("adb_freeform_revert");
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

/** Whether macOS trusts this app to synthesise input. Without it every remote
 *  action silently no-ops, so the Settings row surfaces it rather than letting
 *  the feature look broken. */
export const accessibilityTrusted = () => invoke<boolean>("accessibility_trusted");
export const openAccessibilitySettings = () =>
  invoke<void>("open_accessibility_settings");
/** Revoke the Accessibility grant and ask for it again, so macOS records the
 *  build that is actually running. The recovery for the state where System
 *  Settings shows DroidDock ticked and the Mac still ignores every remote
 *  action — see `mac_remote::accessibility_reset` for why that happens. */
export const accessibilityReset = () => invoke<boolean>("accessibility_reset");

/** Point the *window's* material at the app's theme rather than the Mac's.
 *  Without this, a Mac in light mode running the app in dark mode backs every
 *  translucent surface with a light vibrancy material — grey-white where the
 *  app is see-through, espresso where it isn't. Pass "system" to hand control
 *  back to macOS. See `appearance::window_theme_set`. */
export const windowThemeSet = (theme: "dark" | "light" | "system") =>
  invoke<void>("window_theme_set", { theme });

/** Whether the Dock has DroidDock assigned to a desktop (Dock icon → Options →
 *  Assign To). That assignment is app-wide and applied by the Dock *after* the
 *  app positions its own windows, so it is the one reason the main window can
 *  show up on every Space no matter what the app asks for. */
export const spacesBindingActive = () => invoke<boolean>("spaces_binding_active");
/** Clear it. Resolves `false` if it survived, in which case the only remaining
 *  route is the Dock's own menu. */
export const spacesBindingClear = () => invoke<boolean>("spaces_binding_clear");
export const autostartSet = (enabled: boolean) => invoke<void>("autostart_set", { enabled });

// ── In-app updates ───────────────────────────────────────────────────────

/// What `update_check` found. `null` from the command means "up to date" —
/// this type only ever describes a real, newer release.
export type UpdateInfo = {
  version: string;
  /// The GitHub release body, verbatim. Empty when the release had none.
  notes: string;
  currentVersion: string;
};

export type UpdateProgress = {
  downloaded: number;
  /// `null` when the server sent no Content-Length — render indeterminate
  /// rather than dividing by a guess.
  total: number | null;
};

/// The version this build reports (`tauri.conf.json`'s `version`), which is
/// also the number the updater compares against.
export const appVersion = () => invoke<string>("app_version");
export const updateCheck = () => invoke<UpdateInfo | null>("update_check");
/// Downloads, installs, and **relaunches the app** — it does not resolve on
/// success. Only ever call it from an explicit user action.
export const updateInstall = () => invoke<void>("update_install");
export const onUpdateProgress = (cb: (p: UpdateProgress) => void) =>
  on<UpdateProgress>("update-progress", cb);
/// Emitted by the once-a-day background check. Nothing has been downloaded at
/// this point — it exists purely to badge the Settings tab and relabel the tray.
export const onUpdateAvailable = (cb: (u: UpdateInfo) => void) =>
  on<UpdateInfo>("update-available", cb);
/// The tray's "Check for Updates…" item, which opens the window and asks the
/// frontend to land on Settings → About.
export const onOpenUpdates = (cb: () => void) => on<void>("open-updates", cb);
