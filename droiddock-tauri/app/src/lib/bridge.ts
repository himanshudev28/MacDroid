import { Channel, invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

/// Subscribe to a Tauri event (the `invoke()`+`listen()` shim that replaces the
/// Electron `window.droid.on*` preload bridge). Returns an unsubscribe fn that
/// is safe to call before the async `listen` has resolved.
export function on<T>(event: string, cb: (payload: T) => void): () => void {
  let un: (() => void) | null = null;
  let cancelled = false;
  listen<T>(event, (e) => cb(e.payload)).then((f) => {
    if (cancelled) f();
    else un = f;
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
  /// Phase 19: Mac directories the paired phone may browse/pull from (reverse
  /// file browsing) — edited wholesale (add/remove folder) via `setSetting`,
  /// same as any other setting here.
  macFsRoots: string[];
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
  number?: string;
  name?: string;
};

export const notifReply = (key: string, text: string) =>
  invoke<void>("notif_reply", { key, text });
export const notifDismiss = (key: string) => invoke<void>("notif_dismiss", { key });

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
};
export const mediaCmd = (cmd: string, value = 0) =>
  invoke<void>("media_cmd", { cmd, value });

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
export const autostartGet = () => invoke<boolean>("autostart_get");
export const autostartSet = (enabled: boolean) => invoke<void>("autostart_set", { enabled });
