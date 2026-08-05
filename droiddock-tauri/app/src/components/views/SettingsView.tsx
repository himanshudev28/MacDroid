import { useEffect, useState, memo } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import Icon from "../Icon";
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
import { widgetSet, accessibilityTrusted, openAccessibilitySettings } from "../../lib/bridge";
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
  type DroidConfig,
  type PhotoSyncProgress,
  type UpdateInfo,
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
  { id: "menubar", label: "Menu bar", icon: "squareStack" },
  { id: "photos", label: "Photo sync", icon: "image" },
  { id: "macfiles", label: "Mac files", icon: "folder" },
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
  updateAvailable,
}: {
  config: DroidConfig | null;
  onConfig: (c: DroidConfig) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
  /// Found by the once-a-day background check in `updater.rs`. Seeds the row so
  /// a user who follows the rail badge here sees the result immediately, rather
  /// than having to press Check to be told what the badge already told them.
  updateAvailable: UpdateInfo | null;
}) {
  const [name, setName] = useState(config?.deviceName ?? "");
  const [autostart, setAutostart] = useState(false);
  const [axTrusted, setAxTrusted] = useState(true);
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
  // expecting the warning to have cleared. Only runs while the toggle is on.
  useEffect(() => {
    if (!config?.remoteControl) return;
    let alive = true;
    const check = () =>
      accessibilityTrusted()
        .then((t) => alive && setAxTrusted(t))
        .catch(() => {});
    check();
    const id = setInterval(check, 2000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [config?.remoteControl]);

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
      setUpdate({ kind: "error", message: "The update installed but the app didn't relaunch. Quit and reopen DroidDock." });
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
    return <div className="p-8 text-[12px] text-dim">Loading settings…</div>;
  }

  return (
    <div className="flex h-full min-h-0">
      {/* Category list — the AirSync settings shape, replacing one long
          scrolling page. */}
      <div className="w-36 shrink-0 overflow-y-auto border-r border-line px-2 py-4" role="tablist">
        {CATEGORIES.map((c) => (
          <button
            key={c.id}
            onClick={() => setTab(c.id)}
            role="tab"
            aria-selected={tab === c.id}
            className={`flex w-full items-center gap-2.5 rounded-[9px] px-2.5 py-1.5 text-left transition-colors ${
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
          <Section icon="wifi" title="Connection">
            <Field label="Device name" hint="Shown on your phone as the Mac's name">
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                onBlur={() => name.trim() !== (config.deviceName ?? "") && set("deviceName", name.trim())}
                onKeyDown={(e) => e.key === "Enter" && (e.currentTarget as HTMLInputElement).blur()}
                placeholder={config.deviceName ?? "This Mac"}
                className="field w-48 text-right"
              />
            </Field>
            <Toggle
              label="Clipboard sync"
              hint="Share copied text both ways. Off stops all clipboard traffic."
              on={config.clipboardSync}
              onChange={(v) => set("clipboardSync", v)}
            />
            <Toggle
              label="Phone notifications"
              hint="Show your phone's notifications on the Mac"
              on={config.notifications}
              onChange={(v) => set("notifications", v)}
            />
            <Toggle
              label="Show on Mac (native banners)"
              hint="Also raise a macOS pop-up for each notification"
              on={config.nativeNotifs}
              onChange={(v) => set("nativeNotifs", v)}
            />
            <Toggle
              label="Low battery alerts"
              hint={`Raise a banner when the phone drops below ${config.lowBatteryPct}% while off the charger. Fires once per discharge, not once per update.`}
              on={config.lowBatteryAlert}
              onChange={(v) => set("lowBatteryAlert", v)}
            />
            <Field label="Alert threshold" hint="Percentage the phone has to fall below.">
              <div className="flex items-center gap-3">
                <input
                  type="range"
                  min={5}
                  max={50}
                  step={5}
                  value={config.lowBatteryPct}
                  onChange={(e) => set("lowBatteryPct", Number(e.target.value))}
                  aria-label="Low battery threshold"
                  className="vol-slider w-32"
                />
                <span className="data w-9 shrink-0 text-right text-dim">{config.lowBatteryPct}%</span>
              </div>
            </Field>
            <Toggle
              label="Encrypt messages"
              hint="AES-256-GCM on clipboard, notifications, messages, contacts and call events, keyed off your pairing code. File transfers, thumbnails and screen mirroring stay unencrypted. Needs a phone app new enough to support it — if it isn't, the link quietly stays as it is."
              on={config.encryptLink}
              onChange={(v) => set("encryptLink", v)}
            />
          </Section>
        )}

        {tab === "mirroring" && (
          <Section icon="monitor" title="Mirroring">
            <Field
              label="Start with"
              hint="Which mirror the Mirror tab's primary button launches. Wi-Fi needs no ADB; the others need scrcpy."
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
              label="Desktop display size"
              hint="Virtual display for desktop mode — scrcpy's “flex display”. Leave empty to let the phone choose. The window is resizable either way."
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

            {/* One quality group for both transports. Wi-Fi passes these to the
                phone's MediaCodec on `mirror-start`; ADB passes them to scrcpy.
                They were two hardcoded constants before — 6 Mbps/30fps on the
                phone, scrcpy's own 8 Mbps default — neither of which anyone
                chose for a LAN. */}
            <Field
              label="Quality"
              hint="Video bit rate for both Wi-Fi and ADB mirroring. Higher is sharper and uses more Wi-Fi; drop it if the mirror stutters on a busy network."
            >
              <div className="flex items-center gap-3">
                <input
                  type="range"
                  min={2}
                  max={30}
                  step={1}
                  value={config.mirrorBitrateMbps}
                  onChange={(e) => set("mirrorBitrateMbps", Number(e.target.value))}
                  aria-label="Mirror bit rate"
                  className="vol-slider w-32"
                />
                <span className="data w-12 shrink-0 text-right text-dim">
                  {config.mirrorBitrateMbps} Mb
                </span>
              </div>
            </Field>
            <Field
              label="Frame rate"
              hint="60 feels smooth; 30 halves the bandwidth and is fine for reading."
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
              label="Resolution cap"
              hint="Longest edge of the streamed image. The single biggest bandwidth lever — “Phone” sends the device's own resolution."
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
            <Field
              label="Reset quality"
              hint="Back to 12 Mb · 60 fps · phone resolution — the defaults these ship with."
            >
              <button
                onClick={async () => {
                  await set("mirrorBitrateMbps", 12);
                  await set("mirrorFps", 60);
                  await set("mirrorMaxSize", 0);
                  onToast("ok", "Mirror quality reset to defaults");
                }}
                className="btn btn-secondary"
              >
                Reset
              </button>
            </Field>
          </Section>
        )}

        {tab === "menubar" && (
          <Section icon="squareStack" title="Menu bar">
            <Field label="Show beside the icon" hint="What the menu bar displays while a phone is linked.">
              <Choice
                value={config.menubarText}
                onChange={(v) => set("menubarText", v)}
                options={[
                  ["none", "Nothing"],
                  ["battery", "Battery"],
                  ["media", "Now playing"],
                  ["device", "Phone name"],
                ]}
              />
            </Field>
            {config.menubarText === "battery" && (
              <Field label="Battery style" hint="How the reading is drawn.">
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
                label="Maximum width"
                hint="macOS gives no way to set the menu-bar font size from here, so this caps the text length instead — which is what controls how much menu bar DroidDock takes up."
              >
                <div className="flex items-center gap-3">
                  <input
                    type="range"
                    min={6}
                    max={60}
                    step={2}
                    value={config.menubarMaxLen}
                    onChange={(e) => set("menubarMaxLen", Number(e.target.value))}
                    aria-label="Menu bar text length"
                    className="vol-slider w-32"
                  />
                  <span className="data w-16 shrink-0 text-right text-dim">
                    {config.menubarMaxLen} chars
                  </span>
                </div>
              </Field>
            )}
            <Field label="Album art in the panel" hint="How cover art appears in the menu-bar panel's now-playing card.">
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
              label="Floating status widget"
              hint="A small always-on-top panel with battery and now-playing that you can park anywhere. Not a macOS Widget — those need a Swift extension a Tauri app can't ship — but it's the same glanceable readout."
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
          <Section icon="image" title="Photo sync">
            <Toggle
              label="Auto-sync new photos & videos"
              hint="New shots on the phone land in the destination folder below, automatically"
              on={config.photoSyncEnabled}
              onChange={(v) => set("photoSyncEnabled", v)}
            />
            <Field label="Destination" hint={config.photoSyncDest ?? "~/Pictures/DroidDock (default)"}>
              <button onClick={pickDest} className="btn btn-secondary">
                Choose…
              </button>
            </Field>
            <Field
              label="Back-fill existing library"
              hint={
                syncProg
                  ? syncProg.total > 0
                    ? `Syncing ${syncProg.done}/${syncProg.total}${syncProg.name ? ` — ${syncProg.name}` : ""}`
                    : "Checking phone library…"
                  : "Pull everything already on the phone, not just new items going forward"
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
          <Section icon="folder" title="Mac files">
            <Toggle
              label="Let the phone browse these folders"
              hint="Adds a “Mac Files” tab to the phone app for the folders listed below. Off by default; while off, the phone doesn't show the tab at all. Also muted while DroidDock is paused."
              on={config.macFsEnabled}
              onChange={(v) => set("macFsEnabled", v)}
            />
            {(config.macFsRoots ?? []).map((root) => (
              <Field key={root} label={root} mono>
                <button onClick={() => removeMacFsRoot(root)} className="btn btn-danger">
                  Remove
                </button>
              </Field>
            ))}
            <Field
              label="Add folder"
              hint="Folders the phone's Mac Files tab may browse and pull from. Nothing outside this list is ever reachable."
            >
              <button onClick={addMacFsRoot} className="btn btn-secondary">
                Choose…
              </button>
            </Field>
          </Section>
        )}

        {tab === "system" && (
          <Section icon="terminal" title="System">
            <Toggle
              label="Launch at login"
              hint="Start DroidDock automatically when you log in"
              on={autostart}
              onChange={toggleAutostart}
            />
            <Toggle
              label="Let the phone control this Mac"
              hint="Adds a trackpad, keyboard, media controls, lock, screensaver, brightness and volume to the phone app. Needs macOS Accessibility permission — see below. Anyone holding your paired phone gets this, so leave it off unless you want it."
              on={config.remoteControl}
              onChange={(v) => set("remoteControl", v)}
            />
            {config.remoteControl && !axTrusted && (
              <div className="ax-warn">
                <Icon name="alert-triangle" />
                <div>
                  <strong>Accessibility permission not granted</strong>
                  <p>
                    macOS silently discards synthesised input until DroidDock is
                    ticked in Privacy &amp; Security → Accessibility, so every
                    remote action will appear to do nothing. If DroidDock is
                    already listed, untick and re-tick it — macOS caches the old
                    answer after an app is rebuilt.
                  </p>
                </div>
                <button onClick={() => openAccessibilitySettings()}>Open Settings</button>
              </div>
            )}
            <Toggle
              label="Send what's playing to the phone"
              hint="Shows the current track on the phone's home screen. Play/pause/skip already work for every app; this only supplies the title, and only for Music, Spotify and media websites."
              on={config.macMediaSync}
              onChange={(v) => set("macMediaSync", v)}
            />
            {config.macMediaSync && (
              <Toggle
                label="Include media websites"
                hint="Reads the active browser tab's title so YouTube and similar show a track name. Only tabs on known media sites are ever read — a bank or mail tab is ignored entirely."
                on={config.macMediaBrowser}
                onChange={(v) => set("macMediaBrowser", v)}
              />
            )}
            <Toggle
              label="Share this Mac's status with the phone"
              hint="Sends this Mac's name and battery level so the phone's home screen can show both devices. Read-only — it grants the phone nothing."
              on={config.macInfoSync}
              onChange={(v) => set("macInfoSync", v)}
            />
            <Field
              label="Pause DroidDock"
              hint={
                config.pausedUntil
                  ? config.pausedUntil - Date.now() > TEN_YEARS_MS
                    ? "Paused indefinitely — notifications & clipboard muted"
                    : `Paused until ${new Date(config.pausedUntil).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`
                  : "Mutes notification banners & clipboard sync (same tray menu control)"
              }
            >
              {config.pausedUntil ? (
                <button onClick={() => pause(null)} className="btn btn-secondary">
                  Resume
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
          <Section icon="monitor" title="Appearance">
            <Field
              label="Theme"
              hint="Dark is espresso; light is warm cream. System follows macOS and switches with it."
            >
              <SegmentedControl<Theme>
                value={theme}
                onChange={(t) => {
                  setTheme(t);
                  setThemeState(t);
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
              label="Glass"
              hint="How translucent the sidebar, panels and popovers are, and how much they blur what's behind them. At 0 they're solid — turn it down if the desktop showing through is distracting, or if translucency costs you battery."
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
                  aria-label="Glass strength"
                  className="vol-slider w-36"
                />
                <span className="data w-9 shrink-0 text-right text-dim">{glass}%</span>
              </div>
            </Field>

            <Field
              label="Phone clock"
              hint="How the clock on the phone card is drawn. Neon, Outline, Bubble and Gradient are tinted with your accent colour; Stacked and Bubble put the hour above the minute. Mono is the only one that shows seconds; Minimal drops the date."
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
              label="Accent colour"
              hint="Amber is the app's own. System follows macOS (System Settings › Appearance) — useful if you want DroidDock to match everything else, at the cost of one cool colour in a warm palette."
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
          <Section icon="info" title="About">
            <Field label="App" hint="">
              <span className="text-[13px] text-dim">DroidDock</span>
            </Field>
            <Field label="Version" hint="">
              <span className="data text-dim">{version ?? "…"}</span>
            </Field>
            <UpdateRow state={update} onCheck={checkForUpdate} onInstall={installUpdate} />
            <Toggle
              label="Check for updates automatically"
              hint="Looks for a new release shortly after launch, at most once a day. It only tells you — nothing downloads or installs without the button above."
              on={config.autoCheckUpdates}
              onChange={(v) => set("autoCheckUpdates", v)}
            />
            <Field label="Port" hint="Wi-Fi link + UDP discovery (port + 1)">
              <span className="data text-dim">{config.port}</span>
            </Field>
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
    <Field label={state.kind === "available" ? `Update to ${state.info.version}` : "Software update"} hint={hint}>
      {state.kind === "available" ? (
        <button onClick={() => onInstall(state.info)} className="btn btn-primary">
          Install and restart
        </button>
      ) : (
        <button onClick={onCheck} disabled={busy} className="btn btn-secondary">
          {busy && <Icon name="reload" size={12} className="spinner" />}
          {state.kind === "downloading" ? "Installing…" : "Check for updates"}
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
            on ? "left-4.5" : "left-0.5"
          }`}
        />
      </button>
    </Field>
  );
}

/* Memoised: App holds `media`, which the phone pushes once a second while
   something is playing. Without this, every one of those ticks re-rendered this
   whole view (thumbnail grids, file lists) even though none of its props
   changed. All props here are primitives or stable useCallback refs, so the
   comparison is sound. */
export default memo(SettingsView);
