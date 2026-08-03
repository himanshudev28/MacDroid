import { useEffect, useState } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import Icon from "../Icon";
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

/// Settings — Phase 3 wires the real toggles (clipboard sync, phone
/// notifications, native banners) plus the device-name override. Each change
/// goes through `set_setting`, which persists to droiddock.json and returns the
/// updated config (same contract as Electron's `settings:set`).
export default function SettingsView({
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

  if (!config) {
    return <div className="p-8 text-[12px] text-dim">Loading settings…</div>;
  }

  return (
    <div className="mx-auto h-full max-w-2xl overflow-y-auto p-8">
      <h1 className="mb-6 font-display text-[17px] font-semibold text-fg">Settings</h1>

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
      </Section>

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

      <Section icon="folder" title="Mac files">
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

      <Section icon="terminal" title="System">
        <Toggle
          label="Launch at login"
          hint="Start DroidDock automatically when you log in"
          on={autostart}
          onChange={toggleAutostart}
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

      <Section icon="clipboard" title="About">
        <Field label="App" hint="">
          <span className="text-[13px] text-dim">DroidDock</span>
        </Field>
        <Field label="Port" hint="Wi-Fi link + UDP discovery (port + 1)">
          <span className="data text-dim">{config.port}</span>
        </Field>
      </Section>
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
