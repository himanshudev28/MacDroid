import { useEffect, useState, memo } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import Icon from "../Icon";
import { LOCALES, localeChoice, setLocale, t, useT } from "../../lib/i18n";
import {
  getAccentSource,
  getClockStyle,
  getGlass,
  getTheme,
  setAccentSource,
  setClockStyle,
  setGlass,
  setTheme,
  type AccentSource,
  type ClockStyle,
  type Theme,
} from "../../lib/appearance";
import {
  widgetSet,
  accessibilityTrusted,
  openAccessibilitySettings,
  accessibilityReset,
  spacesBindingActive,
  spacesBindingClear,
} from "../../lib/bridge";
import {
  setSetting,
  pauseSet,
  autostartGet,
  autostartSet,
  photoSyncBackfill,
  onPhotoSyncProgress,
  appVersion,
  updateCheck,
  updateInstall,
  onUpdateProgress,
  onOpenUpdates,
  adbFreeformStatus,
  adbFreeformEnable,
  adbFreeformRevert,
  crashLogCount,
  webdavStart,
  webdavStop,
  webdavStatus,
  crashLogsReveal,
  crashLogsClear,
  type DroidConfig,
  type PhotoSyncProgress,
  type UpdateInfo,
  type FreeformStatus,
  type WebdavStatus,
} from "../../lib/bridge";

const HOUR_MS = 3_600_000;
// Matches tray.rs's TEN_YEARS_MS bucket — anything further out just reads as
// "indefinite" rather than requiring an exact sentinel match.
const TEN_YEARS_MS = 10 * 365 * 24 * 3600 * 1000;

type CategoryId = "connection" | "mirroring" | "menubar" | "photos" | "macfiles" | "system" | "appearance" | "about";

/// Categories are a 1:1 remap of the sections that used to stack on one
/// scrolling page — no control moved groups, so muscle memory for "which
/// switch lives with which" still holds. `appearance` is the only new one.
const CATEGORIES: { id: CategoryId; label: string; icon: string }[] = [
  { id: "connection", label: "Connection", icon: "wifi" },
  { id: "mirroring", label: "Mirroring", icon: "monitor" },
  { id: "menubar", label: t("Menu bar"), icon: "squareStack" },
  { id: "photos", label: t("Photo sync"), icon: "image" },
  { id: "macfiles", label: t("Mac files"), icon: "folder" },
  { id: "system", label: "System", icon: "terminal" },
  { id: "appearance", label: "Appearance", icon: "monitor" },
  { id: "about", label: "About", icon: "info" },
];

/// Settings — Phase 3 wires the real toggles (clipboard sync, phone
/// notifications, native banners) plus the device-name override. Each change
/// goes through `set_setting`, which persists to droiddock.json and returns the
/// updated config (same contract as Electron's `settings:set`).
/// The update row's whole life, kept as one union rather than four booleans —
/// "downloading" and "error" are mutually exclusive, and a flag soup lets them
/// both be true.
type UpdateState =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "current" }
  | { kind: "available"; info: UpdateInfo }
  | { kind: "downloading"; info: UpdateInfo; downloaded: number; total: number | null }
  | { kind: "error"; message: string };

function SettingsView({
  config,
  onConfig,
  onToast,
  linked,
  updateAvailable,
  onOpenHealth,
}: {
  config: DroidConfig | null;
  onConfig: (c: DroidConfig) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
  /// Whether a phone is currently linked — the phone volume can't mount without one.
  linked: boolean;
  /// Found by the once-a-day background check in `updater.rs`. Seeds the row so
  /// a user who follows the rail badge here sees the result immediately, rather
  /// than having to press Check to be told what the badge already told them.
  updateAvailable: UpdateInfo | null;
  /// Opens the setup check. It lives in `App` rather than here because the same
  /// panel is reached from the strip under the header, and two copies of it
  /// polling the phone independently would double the traffic for nothing.
  onOpenHealth: () => void;
}) {
  // Memoised: its props do not change when only the language does, so without
  // its own subscription it would keep rendering the old strings.
  useT();
  const [name, setName] = useState(config?.deviceName ?? "");
  const [autostart, setAutostart] = useState(false);
  const [axTrusted, setAxTrusted] = useState(true);
  // Both default to "nothing wrong": these drive warnings, and a warning that
  // flashes up for one frame on every open is worse than one that arrives a
  // moment late.
  const [spacesBound, setSpacesBound] = useState(false);
  const [spacesFixFailed, setSpacesFixFailed] = useState(false);
  const [syncProg, setSyncProg] = useState<PhotoSyncProgress | null>(null);
  const [backfilling, setBackfilling] = useState(false);
  const [tab, setTab] = useState<CategoryId>("connection");
  const [version, setVersion] = useState<string | null>(null);
  const [update, setUpdate] = useState<UpdateState>({ kind: "idle" });
  // Appearance lives in localStorage, not the config file — see lib/appearance.
  // Mirrored into React state only so the controls re-render on change; the
  // module is still the source of truth.
  const [theme, setThemeState] = useState(getTheme);
  const [glass, setGlassState] = useState(getGlass);
  const [accent, setAccentState] = useState(getAccentSource);
  const [clock, setClockState] = useState(getClockStyle);

  // Re-checked on a timer rather than once: the grant is made in System
  // Settings, in another window, and the user comes straight back here
  // expecting the warning to have cleared.
  //
  // Gated on the System tab being open, not just on the toggle. `axTrusted` is
  // rendered in exactly one place — inside the `tab === "system"` block — so
  // with Remote Control on and Settings sitting on any other tab, this was an
  // IPC round trip every two seconds, forever, for a value nothing displayed.
  // It also keeps running while the window is hidden or behind another app,
  // which is why the visibility listener is here too: the grant cannot change
  // without the user going to System Settings and coming back, and coming back
  // fires `visibilitychange`, so a hidden window has nothing to poll for.
  const watchAx = !!config?.remoteControl && tab === "system";
  useEffect(() => {
    if (!watchAx) return;
    let alive = true;
    let id: ReturnType<typeof setInterval> | null = null;
    const check = () =>
      accessibilityTrusted()
        .then((trusted) => alive && setAxTrusted(trusted))
        .catch(() => {});
    const stop = () => {
      if (id !== null) clearInterval(id);
      id = null;
    };
    const sync = () => {
      stop();
      if (document.hidden) return;
      // Check on the way back in as well as on the interval — returning from
      // System Settings is the exact moment the answer changes.
      check();
      id = setInterval(check, 2000);
    };
    sync();
    document.addEventListener("visibilitychange", sync);
    return () => {
      alive = false;
      stop();
      document.removeEventListener("visibilitychange", sync);
    };
  }, [watchAx]);

  // Read once per visit to the tab, not on a timer: the assignment only changes
  // when someone uses the Dock's menu, and re-reading it means shelling out to
  // `defaults`. Re-running on tab change is what refreshes it after the user
  // has been off fixing it by hand.
  useEffect(() => {
    if (tab !== "system") return;
    let alive = true;
    spacesBindingActive()
      .then((bound) => {
        if (!alive) return;
        setSpacesBound(bound);
        if (!bound) setSpacesFixFailed(false);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [tab]);

  useEffect(() => {
    setName(config?.deviceName ?? "");
  }, [config?.deviceName]);

  useEffect(() => {
    autostartGet().then(setAutostart).catch(() => {});
    appVersion().then(setVersion).catch(() => {});
  }, []);

  // The tray's "Check for Updates…" opens the window and broadcasts this; the
  // rail switches to Settings, and this lands on the right category. Without
  // it the user arrives on whichever tab they last used, with nothing to see.
  useEffect(() => onOpenUpdates(() => setTab("about")), []);

  // A background find is worth as much as a manual one — same row, same state.
  // Guarded so it can't clobber a download the user has already started.
  useEffect(() => {
    if (!updateAvailable) return;
    setUpdate((prev) =>
      prev.kind === "downloading" ? prev : { kind: "available", info: updateAvailable },
    );
  }, [updateAvailable]);

  // Phase 18: live progress for both the automatic forward-sync and the
  // manual backfill below — one small inline indicator covers both triggers.
  useEffect(() => {
    return onPhotoSyncProgress((p) => {
      setSyncProg(p);
      if (p.error) onToast("bad", `${p.name ?? "photo"}: ${p.error}`);
      // done >= total also covers the "nothing new to sync" case (both 0).
      if (p.done >= p.total) {
        setBackfilling(false);
        if (!p.error && p.total > 0) onToast("ok", `Photo sync — ${p.total} item${p.total === 1 ? "" : "s"} synced`);
        setTimeout(() => setSyncProg(null), 2000);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const set = async (key: string, value: unknown) => {
    try {
      const next = await setSetting(key, value);
      onConfig(next);
    } catch (e) {
      onToast("bad", String(e));
    }
  };

  const toggleAutostart = async (on: boolean) => {
    try {
      await autostartSet(on);
      setAutostart(on);
    } catch (e) {
      onToast("bad", String(e));
    }
  };

  const pause = async (ms: number | null) => {
    try {
      onConfig(await pauseSet(ms));
    } catch (e) {
      onToast("bad", String(e));
    }
  };

  const pickDest = async () => {
    const sel = await openDialog({ directory: true });
    if (!sel) return;
    await set("photoSyncDest", Array.isArray(sel) ? sel[0] : sel);
  };

  // Phase 19: reverse file browsing's root allowlist — same directory-picker
  // pattern as `pickDest` above, just appending to a list instead of
  // overwriting a single value.
  const addMacFsRoot = async () => {
    const sel = await openDialog({ directory: true });
    if (!sel) return;
    const picked = Array.isArray(sel) ? sel[0] : sel;
    const current = config?.macFsRoots ?? [];
    if (current.includes(picked)) return;
    await set("macFsRoots", [...current, picked]);
  };

  const removeMacFsRoot = async (root: string) => {
    const current = config?.macFsRoots ?? [];
    await set("macFsRoots", current.filter((r) => r !== root));
  };

  const checkForUpdate = async () => {
    setUpdate({ kind: "checking" });
    try {
      const info = await updateCheck();
      setUpdate(info ? { kind: "available", info } : { kind: "current" });
    } catch (e) {
      setUpdate({ kind: "error", message: String(e) });
    }
  };

  /// Point of no return: on success the app relaunches, so this promise
  /// resolving at all means something went wrong on the way. The progress
  /// subscription is scoped to the download rather than the component — it has
  /// nothing to say the other 99.9% of the time.
  const installUpdate = async (info: UpdateInfo) => {
    setUpdate({ kind: "downloading", info, downloaded: 0, total: null });
    const off = onUpdateProgress((p) =>
      setUpdate((prev) =>
        prev.kind === "downloading" ? { ...prev, downloaded: p.downloaded, total: p.total } : prev,
      ),
    );
    try {
      await updateInstall();
      // Reached only if the relaunch didn't happen. Say so rather than sitting
      // on a full progress bar forever.
      setUpdate({ kind: "error", message: t("The update installed but the app didn't relaunch. Quit and reopen DroidDock.") });
    } catch (e) {
      setUpdate({ kind: "error", message: String(e) });
      onToast("bad", `Update failed — ${String(e)}`);
    } finally {
      off();
    }
  };

  const backfill = async () => {
    setBackfilling(true);
    setSyncProg({ done: 0, total: 0, name: null, error: null });
    try {
      await photoSyncBackfill();
    } catch (e) {
      setBackfilling(false);
      setSyncProg(null);
      onToast("bad", String(e));
    }
  };

  if (!config) {
    return <div className="p-8 text-[12px] text-dim">{t("Loading settings…")}</div>;
  }

  return (
    <div className="flex h-full min-h-0">
      {/* Category list — the AirSync settings shape, replacing one long
          scrolling page. */}
      <div className="w-36 shrink-0 overflow-y-auto border-e border-line px-2 py-4" role="tablist">
        {CATEGORIES.map((c) => (
          <button
            key={c.id}
            onClick={() => setTab(c.id)}
            role="tab"
            aria-selected={tab === c.id}
            className={`flex w-full items-center gap-2.5 rounded-[9px] px-2.5 py-1.5 text-start transition-colors ${
              tab === c.id
                ? "bg-[color-mix(in_srgb,var(--color-accent)_14%,transparent)] text-fg"
                : "text-dim hover:bg-[color-mix(in_srgb,var(--color-fg)_6%,transparent)] hover:text-fg"
            }`}
          >
            <Icon name={c.icon} size={14} className={tab === c.id ? "text-(--color-accent)" : "text-dim"} />
            <span className="text-[12.5px] leading-none">{c.label}</span>
          </button>
        ))}
      </div>

      <div className="@container min-w-0 flex-1 overflow-x-hidden overflow-y-auto px-5 py-6">
        <h1 className="mb-5 font-display text-[16px] font-semibold text-fg">
          {CATEGORIES.find((c) => c.id === tab)?.label}
        </h1>

        {tab === "connection" && (
          <Section icon="wifi" title={t("Connection")}>
            <Field label={t("Device name")} hint={t("Shown on your phone as the Mac's name")}>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                onBlur={() => name.trim() !== (config.deviceName ?? "") && set("deviceName", name.trim())}
                onKeyDown={(e) => e.key === "Enter" && (e.currentTarget as HTMLInputElement).blur()}
                placeholder={config.deviceName ?? t("This Mac")}
                className="field w-48 text-end"
              />
            </Field>
            <Toggle
              label={t("Clipboard sync")}
              hint={t("Share what you copy both ways. Text syncs in both directions on its own; images sync Mac → phone automatically, but the phone can only send one when you ask it to — Android refuses background clipboard reads, and unlike text there is no accessibility event carrying the picture. Off stops all clipboard traffic.")}
              on={config.clipboardSync}
              onChange={(v) => set("clipboardSync", v)}
            />
            <Toggle
              label={t("Phone notifications")}
              hint={t("Show your phone's notifications on the Mac")}
              on={config.notifications}
              onChange={(v) => set("notifications", v)}
            />
            <Toggle
              label={t("Show on Mac (native banners)")}
              hint={t("Also raise a macOS pop-up for each notification")}
              on={config.nativeNotifs}
              onChange={(v) => set("nativeNotifs", v)}
            />
            <Toggle
              label={t("Low battery alerts")}
              hint={`Raise a banner when the phone drops below ${config.lowBatteryPct}% while off the charger. Fires once per discharge, not once per update.`}
              on={config.lowBatteryAlert}
              onChange={(v) => set("lowBatteryAlert", v)}
            />
            <Field label={t("Alert threshold")} hint={t("Percentage the phone has to fall below.")}>
              <div className="flex items-center gap-3">
                <input
                  type="range"
                  min={5}
                  max={50}
                  step={5}
                  value={config.lowBatteryPct}
                  onChange={(e) => set("lowBatteryPct", Number(e.target.value))}
                  aria-label={t("Low battery threshold")}
                  className="vol-slider w-32"
                />
                <span className="data w-9 shrink-0 text-end text-dim">{config.lowBatteryPct}%</span>
              </div>
            </Field>
            <Toggle
              label={t("Encrypt the link")}
              hint={t("AES-256-GCM on everything after pairing — clipboard, notifications, messages, contacts, calls, and now file transfers, thumbnails and screen mirroring too. Keyed off your pairing code. Each half needs a phone app new enough to support it; whatever isn't supported quietly stays as it was rather than failing.")}
              on={config.encryptLink}
              onChange={(v) => set("encryptLink", v)}
            />
          </Section>
        )}

        {tab === "mirroring" && (
          <Section icon="monitor" title={t("Mirroring")}>
            <Field
              label={t("Start with")}
              hint={t("Which mirror the Mirror tab's primary button launches. Wi-Fi needs no ADB; the others need scrcpy.")}
            >
              <Choice
                value={config.defaultMirrorMode}
                onChange={(v) => set("defaultMirrorMode", v)}
                options={[
                  ["wifi", "Wi-Fi"],
                  ["adb", "ADB"],
                  ["desktop", "Desktop"],
                ]}
              />
            </Field>
            <Field
              label={t("Desktop display size")}
              hint={t("Virtual display for desktop mode. “Auto” derives a landscape size from this Mac's screen — a phone left to choose picks its own portrait shape.")}
            >
              <Choice
                value={config.desktopDisplaySize}
                onChange={(v) => set("desktopDisplaySize", v)}
                options={[
                  ["", "Auto"],
                  ["1280x800", "1280×800"],
                  ["1920x1080", "1920×1080"],
                  ["2560x1440", "2560×1440"],
                ]}
              />
            </Field>
            {/* The density knob. Android picks phone vs tablet/desktop layouts
                from px ÷ (dpi ÷ 160), so this — not the resolution above — is
                what decides whether an app opens as a desktop window or a
                magnified phone. Also surfaced on the Mirror tab, because it is
                the one people flip per-app. */}
            <Field
              label={t("Window layout")}
              hint={t("Which layout Android serves on a virtual display. “Phone” keeps the device's own density, which is how this behaved before the setting existed.")}
            >
              <Choice
                value={config.desktopUiMode}
                onChange={(v) => set("desktopUiMode", v)}
                options={[
                  ["desktop", "Desktop"],
                  ["tablet", "Tablet"],
                  ["phone", "Phone"],
                ]}
              />
            </Field>
            <Toggle
              label={t("Resize display with the window")}
              hint={t("scrcpy's flex display — dragging the window edge resizes the Android display itself instead of scaling a fixed one. Needs scrcpy 4.0 or newer; ignored on older builds.")}
              on={config.desktopFlex}
              onChange={(v) => set("desktopFlex", v)}
            />
            {/* The Apps grid's click behaviour lives here rather than under a
                section of its own: what it switches between is "launch on the
                phone" and "open a virtual-display window", and every knob that
                shapes that window is the next three rows down. */}
            <Toggle
              label={t("Open apps on this Mac")}
              hint={t("Clicking an app in the Apps tab opens it in its own Mac window instead of launching it on the phone. Needs ADB and scrcpy; without a connected device the click falls back to the phone. Hold Option to do the other one.")}
              on={config.openAppsOnMac}
              onChange={(v) => set("openAppsOnMac", v)}
            />
            <Toggle
              label={t("Show Android bars in app windows")}
              hint={t("Keeps the virtual display's launcher, status and nav bars around a single app opened on this Mac. Off makes the window read as that app rather than a phone screen.")}
              on={config.appWindowChrome}
              onChange={(v) => set("appWindowChrome", v)}
            />
            <Toggle
              label={t("Keep apps running when the window closes")}
              hint={t("Hands the app back to the phone's own screen instead of killing it. Needs scrcpy 3.1 or newer.")}
              on={config.appWindowKeepAlive}
              onChange={(v) => set("appWindowKeepAlive", v)}
            />

            {/* One quality group for both transports. Wi-Fi passes these to the
                phone's MediaCodec on `mirror-start`; ADB passes them to scrcpy.
                They were two hardcoded constants before — 6 Mbps/30fps on the
                phone, scrcpy's own 8 Mbps default — neither of which anyone
                chose for a LAN. */}
            <Field
              label={t("Quality")}
              hint={t("Video bit rate for both Wi-Fi and ADB mirroring. Higher is sharper and uses more Wi-Fi; drop it if the mirror stutters on a busy network.")}
            >
              <div className="flex items-center gap-3">
                <input
                  type="range"
                  min={2}
                  max={30}
                  step={1}
                  value={config.mirrorBitrateMbps}
                  onChange={(e) => set("mirrorBitrateMbps", Number(e.target.value))}
                  aria-label={t("Mirror bit rate")}
                  className="vol-slider w-32"
                />
                <span className="data w-12 shrink-0 text-end text-dim">
                  {config.mirrorBitrateMbps} Mb
                </span>
              </div>
            </Field>
            <Field
              label={t("Frame rate")}
              hint={t("60 feels smooth; 30 halves the bandwidth and is fine for reading.")}
            >
              <Choice
                value={String(config.mirrorFps)}
                onChange={(v) => set("mirrorFps", Number(v))}
                options={[
                  ["30", "30"],
                  ["45", "45"],
                  ["60", "60"],
                  ["90", "90"],
                ]}
              />
            </Field>
            <Field
              label={t("Resolution cap")}
              hint={t("Longest edge of the streamed image. The single biggest bandwidth lever — “Phone” sends the device's own resolution.")}
            >
              <Choice
                value={String(config.mirrorMaxSize)}
                onChange={(v) => set("mirrorMaxSize", Number(v))}
                options={[
                  ["0", "Phone"],
                  ["1920", "1920"],
                  ["1280", "1280"],
                  ["720", "720"],
                ]}
              />
            </Field>
            {/* Codec and audio apply to both transports. Over Wi-Fi each end
                degrades on its own — the phone to H.264 without an HEVC
                encoder, this Mac by never asking when its decoder says no — so
                neither setting can produce a stream that fails to play. */}
            <Field
              label={t("Video codec")}
              hint={t("H.265 roughly halves the bandwidth at the same quality. Used on both the Wi-Fi and ADB paths, and falls back to H.264 on its own if either the phone can't encode it or this Mac can't decode it.")}
            >
              <Choice
                value={config.mirrorCodec}
                onChange={(v) => set("mirrorCodec", v)}
                options={[
                  ["h264", "H.264"],
                  ["h265", "H.265"],
                ]}
              />
            </Field>
            <Toggle
              label={t("Phone audio")}
              hint={t("Play the phone's audio through this Mac while mirroring. Over Wi-Fi it needs Android 10+ and the microphone permission, because Android routes captured playback through the same API. Apps that opt out of capture — most paid music and video apps — come through silent.")}
              on={config.mirrorAudio}
              onChange={(v) => set("mirrorAudio", v)}
            />
            {/* scrcpy passthrough. Every one of these is off / scrcpy-default
                unless switched on, so a config that predates them behaves
                exactly as it did. */}
            <Toggle
              label={t("Low-level keyboard")}
              hint={t("Sends keystrokes as a virtual USB keyboard, which fixes non-Latin layouts and games. Changes how every key reaches the phone, so it's off by default. Needs scrcpy 2.4 or newer.")}
              on={config.scrcpyUhid}
              onChange={(v) => set("scrcpyUhid", v)}
            />
            <Toggle
              label={t("Keep the phone awake")}
              hint={t("Stops the phone's screen timing out while it's mirroring.")}
              on={config.scrcpyStayAwake}
              onChange={(v) => set("scrcpyStayAwake", v)}
            />
            <Toggle
              label={t("Blank the phone screen")}
              hint={t("Turns the phone's own display off while mirroring — the phone still responds, it just isn't showing anything.")}
              on={config.scrcpyTurnScreenOff}
              onChange={(v) => set("scrcpyTurnScreenOff", v)}
            />
            <Toggle
              label={t("Float mirror windows on top")}
              hint={t("Keeps scrcpy windows above other Mac windows.")}
              on={config.scrcpyAlwaysOnTop}
              onChange={(v) => set("scrcpyAlwaysOnTop", v)}
            />

            <FreeformCard />

            <Field
              label={t("Reset quality")}
              hint={t("Back to 12 Mb · 60 fps · phone resolution — the defaults these ship with.")}
            >
              <button
                onClick={async () => {
                  await set("mirrorBitrateMbps", 12);
                  await set("mirrorFps", 60);
                  await set("mirrorMaxSize", 0);
                  onToast("ok", t("Mirror quality reset to defaults"));
                }}
                className="btn btn-secondary"
              >{t("Reset")}
              </button>
            </Field>
          </Section>
        )}

        {tab === "menubar" && (
          <Section icon="squareStack" title={t("Menu bar")}>
            <Field label={t("Show beside the icon")} hint={t("What the menu bar displays while a phone is linked.")}>
              <Choice
                value={config.menubarText}
                onChange={(v) => set("menubarText", v)}
                options={[
                  ["none", "Nothing"],
                  ["battery", "Battery"],
                  ["media", t("Now playing")],
                  ["device", t("Phone name")],
                ]}
              />
            </Field>
            {config.menubarText === "battery" && (
              <Field label={t("Battery style")} hint={t("How the reading is drawn.")}>
                <Choice
                  value={config.menubarBatteryStyle}
                  onChange={(v) => set("menubarBatteryStyle", v)}
                  options={[
                    ["percent", "82%"],
                    ["bar", "▮▮▮▮▯"],
                    ["both", "▮▮▮▮▯ 82%"],
                  ]}
                />
              </Field>
            )}
            {config.menubarText === "media" && (
              <Field
                label={t("Maximum width")}
                hint={t("macOS gives no way to set the menu-bar font size from here, so this caps the text length instead — which is what controls how much menu bar DroidDock takes up.")}
              >
                <div className="flex items-center gap-3">
                  <input
                    type="range"
                    min={6}
                    max={60}
                    step={2}
                    value={config.menubarMaxLen}
                    onChange={(e) => set("menubarMaxLen", Number(e.target.value))}
                    aria-label={t("Menu bar text length")}
                    className="vol-slider w-32"
                  />
                  <span className="data w-16 shrink-0 text-end text-dim">
                    {config.menubarMaxLen} chars
                  </span>
                </div>
              </Field>
            )}
            <Field label={t("Album art in the panel")} hint={t("How cover art appears in the menu-bar panel's now-playing card.")}>
              <Choice
                value={config.menubarAlbumArt}
                onChange={(v) => set("menubarAlbumArt", v)}
                options={[
                  ["none", "Hidden"],
                  ["thumb", "Thumbnail"],
                  ["background", "Backdrop"],
                ]}
              />
            </Field>
            <Toggle
              label={t("Floating status widget")}
              hint={t("A small always-on-top panel with battery and now-playing that you can park anywhere. Not a macOS Widget — those need a Swift extension a Tauri app can't ship — but it's the same glanceable readout.")}
              on={config.widgetEnabled}
              onChange={async (v) => {
                try {
                  await widgetSet(v);
                  onConfig({ ...config, widgetEnabled: v });
                } catch (e) {
                  onToast("bad", String(e));
                }
              }}
            />
          </Section>
        )}

        {tab === "photos" && (
          <Section icon="image" title={t("Photo sync")}>
            <Toggle
              label={t("Auto-sync new photos & videos")}
              hint={t("New shots on the phone land in the destination folder below, automatically")}
              on={config.photoSyncEnabled}
              onChange={(v) => set("photoSyncEnabled", v)}
            />
            <Field label={t("Destination")} hint={config.photoSyncDest ?? "~/Pictures/DroidDock (default)"}>
              <button onClick={pickDest} className="btn btn-secondary">{t("Choose…")}
              </button>
            </Field>
            <Field
              label={t("Back-fill existing library")}
              hint={
                syncProg
                  ? syncProg.total > 0
                    ? `Syncing ${syncProg.done}/${syncProg.total}${syncProg.name ? ` — ${syncProg.name}` : ""}`
                    : t("Checking phone library…")
                  : t("Pull everything already on the phone, not just new items going forward")
              }
            >
              <button onClick={backfill} disabled={backfilling} className="btn btn-secondary">
                {backfilling && <Icon name="reload" size={12} className="spinner" />}
                Back-fill
              </button>
            </Field>
          </Section>
        )}

        {tab === "macfiles" && (
          <Section icon="folder" title={t("Mac files")}>
            <Toggle
              label={t("Let the phone browse these folders")}
              hint={t("Adds a “Mac Files” tab to the phone app for the folders listed below. Off by default; while off, the phone doesn't show the tab at all. Also muted while DroidDock is paused.")}
              on={config.macFsEnabled}
              onChange={(v) => set("macFsEnabled", v)}
            />
            {(config.macFsRoots ?? []).map((root) => (
              <Field key={root} label={root} mono>
                <button onClick={() => removeMacFsRoot(root)} className="btn btn-danger">{t("Remove")}
                </button>
              </Field>
            ))}
            <Field
              label={t("Add folder")}
              hint={t("Folders the phone's Mac Files tab may browse and pull from. Nothing outside this list is ever reachable.")}
            >
              <button onClick={addMacFsRoot} className="btn btn-secondary">{t("Choose…")}
              </button>
            </Field>
            <PhoneVolumeCard
              linked={linked}
              writable={config.webdavWritable ?? false}
              onWritable={(v) => set("webdavWritable", v)}
            />
          </Section>
        )}

        {tab === "macfiles" && (
          <Section icon="download" title={t("Quick Share")}>
            <Toggle
              label={t("Receive files from nearby devices")}
              hint={t("Makes this Mac appear in the Quick Share sheet on any nearby Android, ChromeOS or Windows device — no DroidDock needed on the sender. Every transfer still has to be accepted here, and the code shown must match the sender's. Off by default: Quick Share's “contacts only” mode needs Google account access this app doesn't have, so while it's on, this Mac is visible to everyone on the network.")}
              on={config.quickShareEnabled}
              onChange={(v) => set("quickShareEnabled", v)}
            />
          </Section>
        )}

        {tab === "system" && (
          <Section icon="terminal" title={t("System")}>
            {/* Deliberately the first row in System. Everything below it is a
                switch that assumes the permissions underneath it are in place,
                and when they aren't, each one fails without saying so. */}
            <Field
              label={t("Setup check")}
              hint={t("Every permission both devices need, what breaks without each, and a button that opens the screen to grant it.")}
            >
              <button onClick={onOpenHealth} className="btn btn-secondary">{t("Check now")}
              </button>
            </Field>
            <Toggle
              label={t("Launch at login")}
              hint={t("Start DroidDock automatically when you log in")}
              on={autostart}
              onChange={toggleAutostart}
            />
            <Toggle
              label={t("Let the phone control this Mac")}
              hint={t("Adds a trackpad, keyboard, media controls, lock, screensaver, brightness and volume to the phone app. Needs macOS Accessibility permission — see below. Anyone holding your paired phone gets this, so leave it off unless you want it.")}
              on={config.remoteControl}
              onChange={(v) => set("remoteControl", v)}
            />
            {config.remoteControl && !axTrusted && (
              <div className="ax-warn">
                <Icon name="alert-triangle" />
                <div>
                  <strong>{t("macOS is ignoring DroidDock's input")}</strong>
                  <p>
                    Until DroidDock holds Accessibility permission, macOS
                    discards every synthesised click and keystroke without an
                    error, so remote control just appears to do nothing.
                  </p>
                  <p>
                    <strong>{t("If DroidDock already looks ticked")}</strong> in Privacy
                    &amp; Security → Accessibility, the tick is stale. macOS
                    stores the permission against a signature of the exact app
                    binary, and every DroidDock update replaces that binary — the
                    row survives the update, the permission doesn't.{" "}
                    <em>{t("Reset permission")}</em> deletes the stale row and asks
                    again for the copy you're running; tick DroidDock when macOS
                    prompts.
                  </p>
                </div>
                <div className="ax-warn-actions">
                  <button
                    onClick={() => {
                      accessibilityReset().catch(() => {});
                    }}
                  >{t("Reset permission")}
                  </button>
                  <button onClick={() => openAccessibilitySettings()}>{t("Open Settings")}</button>
                </div>
              </div>
            )}
            {spacesBound && (
              <div className="ax-warn">
                <Icon name="alert-triangle" />
                <div>
                  <strong>{t("DroidDock is assigned to every desktop")}</strong>
                  <p>
                    The window server has this window on more than one desktop,
                    which is why it shows up on whichever one you switch to. It
                    comes from the Dock's per-app Space assignment (Dock icon →
                    Options → Assign To), which the Dock stamps onto every window
                    DroidDock opens and which overrides anything the app asks for
                    its own window. It lives with your Dock preferences, so it
                    outlives reinstalls and updates.
                  </p>
                  <p>
                    <strong>{t("If the button below doesn't help,")}</strong> set it by
                    hand: right-click DroidDock in the Dock → Options → Assign To
                    → None. The Dock doesn't always write this choice somewhere
                    DroidDock can read or change, so the menu is the reliable fix.
                  </p>
                  {spacesFixFailed && (
                    <p>
                      Clearing it from here didn't take. Right-click DroidDock in
                      the Dock → Options → Assign To → None.
                    </p>
                  )}
                </div>
                <div className="ax-warn-actions">
                  <button
                    onClick={() => {
                      spacesBindingClear()
                        .then((ok) => {
                          setSpacesBound(!ok);
                          setSpacesFixFailed(!ok);
                        })
                        .catch(() => setSpacesFixFailed(true));
                    }}
                  >{t("Keep on one desktop")}
                  </button>
                </div>
              </div>
            )}
            <Toggle
              label={t("Send what's playing to the phone")}
              hint={t("Shows the current track on the phone's home screen. Play/pause/skip already work for every app; this only supplies the title, and only for Music, Spotify and media websites.")}
              on={config.macMediaSync}
              onChange={(v) => set("macMediaSync", v)}
            />
            {config.macMediaSync && (
              <Toggle
                label={t("Include media websites")}
                hint={t("Reads the active browser tab's title so YouTube and similar show a track name. Only tabs on known media sites are ever read — a bank or mail tab is ignored entirely.")}
                on={config.macMediaBrowser}
                onChange={(v) => set("macMediaBrowser", v)}
              />
            )}
            <Toggle
              label={t("Share this Mac's status with the phone")}
              hint={t("Sends this Mac's name and battery level so the phone's home screen can show both devices. Read-only — it grants the phone nothing.")}
              on={config.macInfoSync}
              onChange={(v) => set("macInfoSync", v)}
            />
            <Field
              label={t("Pause DroidDock")}
              hint={
                config.pausedUntil
                  ? config.pausedUntil - Date.now() > TEN_YEARS_MS
                    ? t("Paused indefinitely — notifications & clipboard muted")
                    : `Paused until ${new Date(config.pausedUntil).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`
                  : t("Mutes notification banners & clipboard sync (same tray menu control)")
              }
            >
              {config.pausedUntil ? (
                <button onClick={() => pause(null)} className="btn btn-secondary">{t("Resume")}
                </button>
              ) : (
                <div className="flex gap-1.5">
                  <button onClick={() => pause(Date.now() + HOUR_MS)} className="btn btn-secondary px-2.5">
                    1h
                  </button>
                  <button onClick={() => pause(Date.now() + 8 * HOUR_MS)} className="btn btn-secondary px-2.5">
                    8h
                  </button>
                  <button onClick={() => pause(Number.MAX_SAFE_INTEGER)} className="btn btn-secondary px-2.5">
                    ∞
                  </button>
                </div>
              )}
            </Field>
          </Section>
        )}

        {tab === "appearance" && (
          <Section icon="monitor" title={t("Appearance")}>
            {/* Always shown. The hint tells the truth about what is bundled
                rather than the control hiding itself — a picker that appears
                only once a translation exists means "where is the language
                setting?" has no answer at all. Adding `src/locales/<tag>.ts`
                and one line to LOCALES fills it — see locales/README.md. */}
            <Field
              label={t("Language")}
              hint={
                LOCALES.length > 1
                  ? t("System follows your Mac's language. Anything DroidDock hasn't been translated into shows in English.")
                  : t("Only English is bundled with this build, so this has nothing else to switch to yet. System follows your Mac's language when a translation for it exists.")
              }
            >
              <select
                aria-label={t("Language")}
                className="select"
                value={localeChoice()}
                onChange={(e) => setLocale(e.target.value)}
              >
                <option value="system">{t("System")}</option>
                {LOCALES.map((l) => (
                  <option key={l.tag} value={l.tag}>
                    {l.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label={t("Theme")}
              hint={t("Dark is espresso; light is warm cream. System follows macOS and switches with it.")}
            >
              <SegmentedControl<Theme>
                value={theme}
                onChange={(next) => {
                  setTheme(next);
                  setThemeState(next);
                }}
                options={[
                  { value: "dark", label: "Dark" },
                  { value: "light", label: "Light" },
                  { value: "system", label: "System" },
                ]}
                ariaLabel="Theme"
              />
            </Field>

            <Field
              label={t("Glass")}
              hint={t("How translucent the sidebar, panels and popovers are, and how much they blur what's behind them. At 0 they're solid — turn it down if the desktop showing through is distracting, or if translucency costs you battery.")}
            >
              <div className="flex items-center gap-3">
                <input
                  type="range"
                  min={0}
                  max={100}
                  step={1}
                  value={glass}
                  onChange={(e) => {
                    const v = Number(e.target.value);
                    setGlass(v);
                    setGlassState(v);
                  }}
                  aria-label={t("Glass strength")}
                  className="vol-slider w-36"
                />
                <span className="data w-9 shrink-0 text-end text-dim">{glass}%</span>
              </div>
            </Field>

            <Field
              label={t("Phone clock")}
              hint={t("How the clock on the phone card is drawn. Neon, Outline, Bubble and Gradient are tinted with your accent colour; Stacked and Bubble put the hour above the minute. Mono is the only one that shows seconds; Minimal drops the date.")}
            >
              <SegmentedControl<ClockStyle>
                value={clock}
                onChange={(c) => {
                  setClockStyle(c);
                  setClockState(c);
                }}
                options={[
                  { value: "row", label: "Row" },
                  { value: "stacked", label: "Stacked" },
                  { value: "mono", label: "Mono" },
                  { value: "minimal", label: "Minimal" },
                  { value: "neon", label: "Neon" },
                  { value: "outline", label: "Outline" },
                  { value: "bubble", label: "Bubble" },
                  { value: "gradient", label: "Gradient" },
                ]}
                ariaLabel="Phone clock style"
              />
            </Field>

            <Field
              label={t("Accent colour")}
              hint={t("Amber is the app's own. System follows macOS (System Settings › Appearance) — useful if you want DroidDock to match everything else, at the cost of one cool colour in a warm palette.")}
            >
              <div className="flex items-center gap-2.5">
                <SegmentedControl<AccentSource>
                  value={accent}
                  onChange={(a) => {
                    setAccentSource(a);
                    setAccentState(a);
                  }}
                  options={[
                    { value: "warm", label: "Amber" },
                    { value: "system", label: "System" },
                  ]}
                  ariaLabel="Accent colour source"
                />
                <span
                  className="h-5 w-5 shrink-0 rounded-full border border-line bg-(--color-accent)"
                  aria-hidden="true"
                />
              </div>
            </Field>
          </Section>
        )}

        {tab === "about" && (
          <Section icon="info" title={t("About")}>
            <Field label={t("App")} hint="">
              <span className="text-[13px] text-dim">{t("DroidDock")}</span>
            </Field>
            <Field label={t("Version")} hint="">
              <span className="data text-dim">{version ?? "…"}</span>
            </Field>
            <UpdateRow state={update} onCheck={checkForUpdate} onInstall={installUpdate} />
            <Toggle
              label={t("Check for updates automatically")}
              hint={t("Looks for a new release shortly after launch, at most once a day. It only tells you — nothing downloads or installs without the button above.")}
              on={config.autoCheckUpdates}
              onChange={(v) => set("autoCheckUpdates", v)}
            />
            <Field label={t("Port")} hint={t("Wi-Fi link + UDP discovery (port + 1)")}>
              <span className="data text-dim">{config.port}</span>
            </Field>
            <CrashLogsRow />
          </Section>
        )}
      </div>
    </div>
  );
}

/// One `Field` that reads differently in each of six states, rather than six
/// rows that appear and disappear — the row never moves, so the button stays
/// where the user's pointer already is between "Check" and "Install".
function UpdateRow({
  state,
  onCheck,
  onInstall,
}: {
  state: UpdateState;
  onCheck: () => void;
  onInstall: (info: UpdateInfo) => void;
}) {
  const busy = state.kind === "checking" || state.kind === "downloading";
  const hint = (() => {
    switch (state.kind) {
      case "checking":
        return "Asking GitHub…";
      case "current":
        return "You're on the latest release.";
      case "available":
        return `${state.info.currentVersion} → ${state.info.version}${
          // The release body can be paragraphs of markdown; one line of it is a
          // useful smell of what changed, the whole thing would bury the button.
          state.info.notes ? ` — ${firstLine(state.info.notes)}` : ""
        }`;
      case "downloading":
        return state.total
          ? `Downloading ${state.info.version} — ${Math.round((state.downloaded / state.total) * 100)}%`
          : `Downloading ${state.info.version}…`;
      case "error":
        return state.message;
      default:
        return "DroidDock installs updates itself — it isn't in the App Store.";
    }
  })();

  return (
    <Field label={state.kind === "available" ? `Update to ${state.info.version}` : t("Software update")} hint={hint}>
      {state.kind === "available" ? (
        <button onClick={() => onInstall(state.info)} className="btn btn-primary">{t("Install and restart")}
        </button>
      ) : (
        <button onClick={onCheck} disabled={busy} className="btn btn-secondary">
          {busy && <Icon name="reload" size={12} className="spinner" />}
          {state.kind === "downloading" ? "Installing…" : t("Check for updates")}
        </button>
      )}
    </Field>
  );
}

function firstLine(notes: string): string {
  const line = notes.split("\n").map((l) => l.trim()).find(Boolean) ?? "";
  return line.length > 90 ? `${line.slice(0, 89)}…` : line;
}

function Section({ icon, title, children }: { icon: string; title: string; children: React.ReactNode }) {
  return (
    <div className="mb-7">
      <div className="mb-2 flex items-center gap-2 px-1">
        <Icon name={icon} size={13} className="text-dim" />
        <span className="label">{title}</span>
      </div>
      <div className="card divide-y divide-line">{children}</div>
    </div>
  );
}

function Field({
  label,
  hint,
  mono,
  children,
}: {
  label: string;
  hint?: string;
  mono?: boolean;
  children: React.ReactNode;
}) {
  return (
    // Wraps instead of squeezing. The control is `shrink-0` — it has to be, a
    // squashed segmented picker is unusable — so the label column absorbed
    // every pixel the control wanted. With a four-option picker like "Desktop
    // display size" that left the label about one word wide, reading as a
    // column of single words down the page.
    //
    // `flex-wrap` + a min width on the label means the control drops to its own
    // line the moment they can't sit side by side, at any pane width, with no
    // breakpoint to keep in sync with the layout.
    // Stacks by default, sits side by side once the *pane* is wide enough —
    // a container query, not a viewport one, because this pane's width depends
    // on the phone panel and the rail, not on the window. A viewport breakpoint
    // would put a row side-by-side while the pane it lives in is 300px wide.
    <div className="flex flex-col gap-2 px-4 py-3 @2xl:flex-row @2xl:items-center @2xl:justify-between @2xl:gap-4">
      <div className="min-w-0 @2xl:flex-1">
        {mono ? (
          <p className="data truncate text-fg">{label}</p>
        ) : (
          <p className="text-[13px] font-medium text-fg">{label}</p>
        )}
        {hint && <p className="mt-0.5 text-[11px] leading-snug text-dim">{hint}</p>}
      </div>
      <div className="shrink-0 self-start @2xl:self-auto">{children}</div>
    </div>
  );
}

/// Segmented picker for the small closed sets above — a native-feeling
/// alternative to a <select> for 3–4 options.
function Choice({
  value,
  onChange,
  options,
}: {
  value: string;
  onChange: (v: string) => void;
  options: [string, string][];
}) {
  return (
    // Wraps only when it genuinely has to. A segmented control is a single row
    // by convention, and that held while every set was 2–4 options — the clock
    // picker is eight, which overflows a narrow pane. So: `flex-wrap` for that
    // case, and *no* width cap.
    //
    // A cap was tried (`max-w-72`) and was worse than the problem: it forced
    // four-option rows to break onto two lines with a ragged gap even on a wide
    // window, so every picker looked broken instead of just the long one.
    <div className="flex flex-wrap gap-0.5 rounded-lg bg-panel3 p-0.5" role="radiogroup">
      {options.map(([id, label]) => (
        <button
          key={id}
          role="radio"
          aria-checked={value === id}
          onClick={() => onChange(id)}
          className={`rounded-[7px] px-2.5 py-1 text-[12px] transition-colors ${
            value === id
              // Not `text-white`: on the light theme's darker amber white is
              // right, but on the dark theme's bright amber it fails contrast.
              // The token flips with the theme.
              ? "bg-(--color-accent) font-medium text-(--color-accent-ink)"
              : "text-dim hover:text-fg"
          }`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/// `Choice` with the value type preserved, so callers get a checked union
/// instead of a bare string they have to cast on the way back out.
function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string }[];
  ariaLabel: string;
}) {
  return (
    <div aria-label={ariaLabel}>
      <Choice
        value={value}
        onChange={(v) => onChange(v as T)}
        options={options.map((o) => [o.value, o.label] as [string, string])}
      />
    </div>
  );
}

/// Mount the phone's storage as a Finder volume.
///
/// Lives in Mac files rather than Mirroring because it is the same question as
/// the rest of that section — where files live and who can reach them.
function PhoneVolumeCard({
  linked,
  writable,
  onWritable,
}: {
  linked: boolean;
  /// From config, not local state — it has to survive a restart, and the Rust
  /// side reads the same value when the volume is mounted.
  writable: boolean;
  onWritable: (v: boolean) => void;
}) {
  const [status, setStatus] = useState<WebdavStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    webdavStatus().then(setStatus).catch(() => {});
  }, []);

  const act = async (fn: () => Promise<WebdavStatus>) => {
    setBusy(true);
    setErr(null);
    try {
      setStatus(await fn());
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mt-1 rounded-xl bg-panel3/60 p-4">
      <p className="text-[12px] font-semibold text-fg">{t("Phone in Finder")}</p>
      <p className="mt-1 text-[11px] leading-relaxed text-dim">
        Mounts your phone's storage as a volume, so any Mac app can open a file from it —
        not just the Files tab here.
      </p>
      {writable ? (
        <p className="mt-2 text-[11px] leading-relaxed text-dim">
          <span className="text-fg/80">{t("Writable.")}</span>{" "}
          {t("Saving from any Mac app writes straight to the phone. Finder's own droppings (.DS_Store and ._ sidecars) are discarded rather than stored. Moving a file between folders isn't supported — rename in place, or use the Files tab.")}
        </p>
      ) : (
        <p className="mt-2 text-[11px] leading-relaxed text-dim">
          <span className="text-fg/80">{t("Read-only.")}</span>{" "}
          {t("Use the Files tab to upload, rename or delete — it confirms first.")}
        </p>
      )}

      <label className="mt-3 flex cursor-pointer items-start gap-2.5">
        <input
          type="checkbox"
          checked={writable}
          onChange={(e) => onWritable(e.target.checked)}
          className="mt-0.5"
        />
        <span className="text-[11px] leading-relaxed text-dim">
          <span className="text-fg/80">{t("Allow writing to the phone")}</span>
          <br />
          {t("Off by default on purpose: a bug on this path damages files on your phone, where a bug on the read path only shows a wrong listing. Takes effect the next time you mount.")}
        </span>
      </label>

      {status?.running && status.mountPoint && (
        <p className="mt-2 font-mono text-[10.5px] text-dim">{status.mountPoint}</p>
      )}
      {err && <p className="mt-2 text-[11px] leading-relaxed text-dim">{err}</p>}
      {!linked && (
        <p className="mt-2 text-[11px] text-dim">{t("Needs a linked phone.")}</p>
      )}

      <div className="mt-3 flex items-center gap-2">
        {status?.running ? (
          <button onClick={() => act(webdavStop)} disabled={busy} className="btn btn-secondary">{t("Unmount")}
          </button>
        ) : (
          <button
            onClick={() => act(webdavStart)}
            disabled={busy || !linked}
            className="btn btn-secondary"
          >
            {busy ? "Mounting…" : t("Mount in Finder")}
          </button>
        )}
      </div>
    </div>
  );
}

/// Panic logs, written locally and never sent anywhere.
///
/// Shown only once there is something to show — an always-visible "0 logs" row
/// invites people to worry about a folder that is empty precisely because
/// nothing is wrong.
function CrashLogsRow() {
  const [count, setCount] = useState(0);

  useEffect(() => {
    crashLogCount().then(setCount).catch(() => setCount(0));
  }, []);

  if (count === 0) return null;

  return (
    <Field
      label={t("Crash logs")}
      hint={`${count} ${count === 1 ? "log" : "logs"} in ~/Library/Logs/DroidDock. Written on this Mac only — DroidDock never sends them anywhere.`}
    >
      <div className="flex items-center gap-2">
        <button onClick={() => crashLogsReveal()} className="btn btn-secondary">{t("Reveal")}
        </button>
        <button
          onClick={async () => {
            await crashLogsClear();
            setCount(0);
          }}
          className="btn btn-secondary"
        >{t("Clear")}
        </button>
      </div>
    </Field>
  );
}

/// Android's freeform-windowing settings, applied over ADB.
///
/// Deliberately a card with its own buttons rather than a Toggle: these are
/// three secure system settings on the *user's phone* that survive DroidDock
/// being uninstalled. Nothing here fires as a side effect of starting a mirror,
/// the current values are shown before anything is changed, and Revert restores
/// what was captured rather than blindly writing 0.
function FreeformCard() {
  const [status, setStatus] = useState<FreeformStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    try {
      setStatus(await adbFreeformStatus());
      setErr(null);
    } catch (e) {
      // No ADB device is the ordinary case, not a failure worth shouting
      // about — the card just explains that it needs one.
      setStatus(null);
      setErr(String(e));
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const act = async (fn: () => Promise<FreeformStatus>) => {
    setBusy(true);
    try {
      setStatus(await fn());
      setErr(null);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mt-1 rounded-xl bg-panel3/60 p-4">
      <p className="text-[12px] font-semibold text-fg">{t("Freeform windows on the phone")}</p>
      <p className="mt-1 text-[11px] leading-relaxed text-dim">
        Desktop mode gives you large-screen layouts on its own. Draggable, resizable app
        windows additionally need three Android developer settings switched on. DroidDock can
        set them for you over ADB.
      </p>
      <p className="mt-2 text-[11px] leading-relaxed text-dim">
        These are settings on <span className="text-fg/80">{t("your phone")}</span>, not in DroidDock —
        they stay on if you uninstall this app. Revert puts back exactly what was there before.
      </p>

      {status && (
        <div className="mt-3 space-y-1 font-mono text-[10.5px] text-dim">
          <div>enable_freeform_support: {status.values[0] ?? "unset"}</div>
          <div>force_desktop_mode_on_external_displays: {status.values[1] ?? "unset"}</div>
          <div>enable_non_resizable_multi_window: {status.values[2] ?? "unset"}</div>
          {status.sdk != null && (
            <div className="pt-1 not-italic">
              Android API {status.sdk}
              {status.supported ? "" : " — needs 35 (Android 15) or newer"}
            </div>
          )}
        </div>
      )}

      {err && <p className="mt-3 text-[11px] leading-relaxed text-dim">{err}</p>}

      <div className="mt-3 flex items-center gap-2">
        <button
          onClick={() => act(adbFreeformEnable)}
          disabled={busy || !status?.supported || status?.enabled}
          className="btn btn-secondary"
        >
          {status?.enabled ? t("Already on") : t("Enable on phone")}
        </button>
        <button onClick={() => act(adbFreeformRevert)} disabled={busy} className="btn btn-secondary">{t("Revert")}
        </button>
        <button onClick={() => void refresh()} disabled={busy} className="btn btn-secondary">{t("Refresh")}
        </button>
      </div>
    </div>
  );
}

function Toggle({
  label,
  hint,
  on,
  onChange,
}: {
  label: string;
  hint?: string;
  on: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <Field label={label} hint={hint}>
      <button
        onClick={() => onChange(!on)}
        role="switch"
        aria-checked={on}
        className={`relative h-5 w-9 rounded-full transition-colors ${
          on ? "bg-(--color-accent)" : "bg-panel3 ring-1 ring-line ring-inset"
        }`}
      >
        {/* The knob is white in both themes — it reads as the moving part
            against the amber track when on. Off, it sits on beige in the light
            theme, where white alone is too soft to find; the hairline ring
            gives it an edge without darkening the control. */}
        <span
          className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow-sm ring-1 ring-black/10 transition-all ${
            on ? "start-4.5" : "start-0.5"
          }`}
        />
      </button>
    </Field>
  );
}

/* Memoised. This was originally defence against the phone's 1 Hz now-playing
   push re-rendering every view; that push no longer reaches `App` at all (it
   lives in `lib/mediaStore`, read only by the two components that show it). The
   memo stays because `App` still re-renders for its own reasons — an arriving
   notification, a toast appearing and expiring, a transfer's progress — and
   none of those change this view's props. All props here are primitives or
   stable useCallback refs, so the comparison is sound. */
export default memo(SettingsView);
