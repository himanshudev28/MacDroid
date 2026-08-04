import { useEffect, useState, memo } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import Icon from "../Icon";
import { getOpacity, setOpacity } from "../../lib/appearance";
import { widgetSet } from "../../lib/bridge";
import {
  setSetting,
  pauseSet,
  autostartGet,
  autostartSet,
  photoSyncBackfill,
  onPhotoSyncProgress,
  type DroidConfig,
  type PhotoSyncProgress,
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
function SettingsView({
  config,
  onConfig,
  onToast,
}: {
  config: DroidConfig | null;
  onConfig: (c: DroidConfig) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [name, setName] = useState(config?.deviceName ?? "");
  const [autostart, setAutostart] = useState(false);
  const [syncProg, setSyncProg] = useState<PhotoSyncProgress | null>(null);
  const [backfilling, setBackfilling] = useState(false);
  const [tab, setTab] = useState<CategoryId>("connection");
  const [opacity, setOpacityState] = useState(getOpacity);

  useEffect(() => {
    setName(config?.deviceName ?? "");
  }, [config?.deviceName]);

  useEffect(() => {
    autostartGet().then(setAutostart).catch(() => {});
  }, []);

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

  const applyOpacity = (v: number) => {
    setOpacityState(v);
    setOpacity(v);
  };

  if (!config) {
    return <div className="p-8 text-[12px] text-dim">Loading settings…</div>;
  }

  return (
    <div className="flex h-full min-h-0">
      {/* Category list — the AirSync settings shape, replacing one long
          scrolling page. */}
      <div className="w-44 shrink-0 overflow-y-auto border-r border-line px-2.5 py-4" role="tablist">
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

      <div className="min-w-0 flex-1 overflow-y-auto px-7 py-6">
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
              hint="Adds a trackpad and keyboard to the phone app that drive this Mac's pointer and typing. macOS will also ask for Accessibility permission the first time. Anyone holding your paired phone gets this — leave it off unless you want it."
              on={config.remoteControl}
              onChange={(v) => set("remoteControl", v)}
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
              label="Window opacity"
              hint="How much of the desktop shows through the window's frosted background. 100% is fully opaque."
            >
              <div className="flex items-center gap-3">
                <input
                  type="range"
                  min={55}
                  max={100}
                  step={1}
                  value={Math.round(opacity * 100)}
                  onChange={(e) => applyOpacity(Number(e.target.value) / 100)}
                  aria-label="Window opacity"
                  className="vol-slider w-36"
                />
                <span className="data w-9 shrink-0 text-right text-dim">{Math.round(opacity * 100)}%</span>
              </div>
            </Field>
            <Field
              label="Accent colour"
              hint="Follows the macOS system accent — change it in System Settings › Appearance."
            >
              <span className="h-5 w-5 rounded-full border border-line bg-(--color-accent)" aria-hidden="true" />
            </Field>
          </Section>
        )}

        {tab === "about" && (
          <Section icon="info" title="About">
            <Field label="App" hint="">
              <span className="text-[13px] text-dim">DroidDock</span>
            </Field>
            <Field label="Port" hint="Wi-Fi link + UDP discovery (port + 1)">
              <span className="data text-dim">{config.port}</span>
            </Field>
          </Section>
        )}
      </div>
    </div>
  );
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
    <div className="flex items-center justify-between gap-4 px-4 py-3">
      <div className="min-w-0">
        {mono ? (
          <p className="data truncate text-fg">{label}</p>
        ) : (
          <p className="text-[13px] font-medium text-fg">{label}</p>
        )}
        {hint && <p className="mt-0.5 text-[11px] leading-snug text-dim">{hint}</p>}
      </div>
      <div className="shrink-0">{children}</div>
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
    <div className="flex gap-0.5 rounded-lg bg-panel3 p-0.5" role="radiogroup">
      {options.map(([id, label]) => (
        <button
          key={id}
          role="radio"
          aria-checked={value === id}
          onClick={() => onChange(id)}
          className={`rounded-[7px] px-2.5 py-1 text-[12px] transition-colors ${
            value === id ? "bg-(--color-accent) font-medium text-white" : "text-dim hover:text-fg"
          }`}
        >
          {label}
        </button>
      ))}
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
        <span
          className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-all ${
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
