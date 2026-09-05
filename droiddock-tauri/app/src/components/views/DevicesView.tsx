import { useEffect, useRef, useState } from "react";
import Icon from "../Icon";
import { adbVolumeGet, adbVolumeSet, type AdbDevice, type AppDeviceInfo, type DeviceInfo, type ToolsStatus } from "../../lib/bridge";
import type { WifiStatus } from "../../lib/wifi";
import { t } from "../../lib/i18n";

/// Phase 13 — port of DevicesView.jsx. Shows the live ADB device (if any),
/// the Wi-Fi app-link status, tool availability, device volume, and the
/// ADB-only actions (Go Wireless / QR pair / Screenshot / Unpair).
export default function DevicesView({
  connected,
  info,
  appInfo,
  wifi,
  tools,
  busy,
  paired,
  onPair,
  onWireless,
  onPairWireless,
  onUnpair,
  onReconnect,
  onScreenshot,
  devices,
  selected,
  onSelect,
}: {
  connected: AdbDevice | null;
  info: DeviceInfo | null;
  appInfo: AppDeviceInfo | null;
  wifi: WifiStatus;
  tools: ToolsStatus | null;
  busy: Record<string, boolean>;
  paired: boolean;
  onPair: () => void;
  onWireless: () => void;
  onPairWireless: () => void;
  onUnpair: () => void;
  onReconnect: () => void;
  onScreenshot: () => void;
  /// Every ready ADB device. More than one and the picker appears — before
  /// this, a second phone silently reassigned every ADB action to whichever
  /// enumerated first.
  devices: AdbDevice[];
  selected: string | null;
  onSelect: (serial: string) => void;
}) {
  const picker =
    devices.length > 1 ? (
      <div className="card flex items-center gap-3 px-4 py-3">
        <Icon name="terminal" size={14} className="shrink-0 text-dim" />
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-medium text-fg">{t("ADB device")}</p>
          <p className="mt-0.5 text-[11px] text-dim">
            {devices.length} connected — pick which one the ADB actions target.
          </p>
        </div>
        <select
          value={selected ?? ""}
          onChange={(e) => onSelect(e.target.value)}
          aria-label={t("ADB device")}
          className="field shrink-0 max-w-56"
        >
          {devices.map((d) => (
            <option key={d.serial} value={d.serial}>
              {d.model || d.serial} ({d.transport})
            </option>
          ))}
        </select>
      </div>
    ) : null;

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-xl space-y-3">
        <div className="flex items-center justify-between">
          <h1 className="font-display text-[17px] font-semibold text-fg">{t("Devices")}</h1>
          <button onClick={onReconnect} disabled={busy.reconnect} className="btn btn-ghost">
            <Icon name="reload" size={14} className={busy.reconnect ? "spinner" : ""} />{t("Reconnect")}
          </button>
        </div>

        {picker}

        {connected ? <AdbDeviceCard device={connected} info={info} appInfo={appInfo} /> : <EmptyDeviceCard onPair={onPair} />}

        <AppLinkCard wifi={wifi} onPair={onPair} />

        <ToolsCard tools={tools} onSetup={onPair} />

        {connected && <VolumeCard deviceSerial={connected.serial} />}

        <ActionsCard
          connected={connected}
          busy={busy}
          paired={paired}
          onWireless={onWireless}
          onPairWireless={onPairWireless}
          onUnpair={onUnpair}
          onScreenshot={onScreenshot}
        />
      </div>
    </div>
  );
}

function AdbDeviceCard({ device, info, appInfo }: { device: AdbDevice; info: DeviceInfo | null; appInfo: AppDeviceInfo | null }) {
  const model = appInfo?.model || info?.model || device.model || device.serial;
  const android = info?.android || appInfo?.android;
  const battery = appInfo?.battery ?? info?.battery ?? null;
  const charging = appInfo?.charging ?? info?.charging;
  const isWifi = device.transport === "wifi";

  return (
    <div className="card-raised p-5">
      <div className="mb-3 flex items-center justify-between">
        <span className="label">{t("ADB device")}</span>
        <span className="flex items-center gap-1.5 rounded-md bg-panel3 px-1.5 py-0.5 text-[10px] font-medium text-dim">
          {isWifi && <span className="h-1.5 w-1.5 rounded-full bg-(--color-link)" />}
          {isWifi ? t("Wi-Fi ADB") : "USB"}
        </span>
      </div>
      <div className="flex items-center gap-4">
        <div className="relative shrink-0">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-panel3">
            <Icon name="phone" size={20} strokeWidth={1.5} className="text-fg/80" />
          </div>
          <span className="led absolute -end-0.5 -top-0.5 h-3 w-3 rounded-full border-2 border-panel2 bg-(--color-link)" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate font-display text-[15px] font-semibold text-fg">{model}</p>
          {android && <p className="mt-0.5 text-[11.5px] text-dim">Android {android}</p>}
          <p className="data mt-0.5 truncate text-[10px] text-faint">{device.serial}</p>
        </div>
      </div>
      {battery != null && (
        <div className="mt-4">
          <div className="mb-1.5 flex items-center justify-between">
            <span className="label">{t("Battery")}</span>
            <span className="data flex items-center gap-1 text-[11px] text-fg/80">
              {charging && <Icon name="reload" size={10} className="text-(--color-link)" />}
              {battery}%
            </span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-panel3">
            <div
              className={`h-full rounded-full transition-all duration-700 ${battery > 20 ? "bg-ok" : "bg-bad"}`}
              style={{ width: `${battery}%` }}
            />
          </div>
        </div>
      )}
    </div>
  );
}

function EmptyDeviceCard({ onPair }: { onPair: () => void }) {
  return (
    <button
      onClick={onPair}
      className="card group w-full border-dashed p-5 text-start transition-colors hover:bg-panel2"
    >
      <div className="flex items-center gap-4">
        <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-dashed border-line">
          <Icon name="phone" size={20} strokeWidth={1.5} className="text-faint" />
        </div>
        <div className="min-w-0">
          <p className="text-[13px] font-medium text-fg/80">{t("No ADB device")}</p>
          <p className="mt-0.5 text-[11.5px] text-dim">{t("Connect a USB cable, or pair wirelessly below.")}</p>
        </div>
      </div>
    </button>
  );
}

function AppLinkCard({ wifi, onPair }: { wifi: WifiStatus; onPair: () => void }) {
  const linked = wifi.connected;
  return (
    <div className="card p-5">
      <div className="mb-3 flex items-center justify-between">
        <span className="label">{t("Phone app")}</span>
        {linked ? (
          <span className="flex items-center gap-1.5 text-[11px] font-medium text-(--color-link)">
            <span className="led h-1.5 w-1.5 rounded-full bg-(--color-link)" />{t("Linked over Wi-Fi")}
          </span>
        ) : (
          <span className="text-[11px] text-faint">{t("Not linked")}</span>
        )}
      </div>
      <div className="flex items-center gap-4">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-panel3">
          <Icon name="wifi" size={17} className={linked ? "text-(--color-link)" : "text-faint"} />
        </div>
        <div className="min-w-0 flex-1">
          {linked ? (
            <p className="truncate text-[13px] font-medium text-fg">{wifi.phoneName || "Phone"}</p>
          ) : (
            <>
              <p className="text-[13px] font-medium text-dim">{t("Phone app not paired")}</p>
              <p className="mt-0.5 text-[11px] text-faint">{t("Install DroidDock on your phone, then pair.")}</p>
            </>
          )}
        </div>
        {!linked && (
          <button onClick={onPair} className="btn btn-secondary shrink-0">{t("Pair")}
          </button>
        )}
      </div>
    </div>
  );
}

function ToolsCard({ tools, onSetup }: { tools: ToolsStatus | null; onSetup: () => void }) {
  if (!tools) return null;
  return (
    <div className="card p-5">
      <div className="mb-3">
        <span className="label">{t("Tools")}</span>
      </div>
      <div className="flex gap-2">
        {[
          { label: "ADB", on: tools.adb, hint: t("Android Debug Bridge") },
          { label: "scrcpy", on: tools.scrcpy, hint: t("Screen mirroring engine") },
        ].map(({ label, on, hint }) => (
          <button
            key={label}
            onClick={onSetup}
            className="flex flex-1 items-center gap-2.5 rounded-xl bg-panel2 px-3 py-2.5 text-start transition-colors hover:bg-panel3"
          >
            <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${on ? "bg-ok" : "bg-bad"}`} />
            <div className="min-w-0">
              <p className="text-[12.5px] font-medium text-fg">{label}</p>
              <p className="truncate text-[10.5px] text-faint">{hint}</p>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

function VolumeCard({ deviceSerial }: { deviceSerial: string }) {
  const [vol, setVol] = useState<{ level: number; max: number } | null>(null);
  const [err, setErr] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const prevLevelRef = useRef<number | null>(null);

  useEffect(() => {
    if (!deviceSerial) {
      setVol(null);
      return;
    }
    adbVolumeGet()
      .then((v) => {
        setVol(v);
        prevLevelRef.current = v.level;
      })
      .catch(() => setErr(true));
  }, [deviceSerial]);

  if (!deviceSerial || err || !vol) return null;

  const pct = Math.round((vol.level / vol.max) * 100);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const level = Number(e.target.value);
    const prevLevel = prevLevelRef.current ?? vol.level;
    setVol((v) => (v ? { ...v, level } : v));
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      await adbVolumeSet(level, prevLevel).catch(() => {});
      prevLevelRef.current = level;
    }, 80);
  };

  return (
    <div className="card p-5">
      <div className="mb-3 flex items-center justify-between">
        <span className="label">{t("Volume")}</span>
        <span className="data text-[11px] text-dim">{pct}%</span>
      </div>
      <div className="flex items-center gap-3">
        <Icon name="volume" size={14} className="shrink-0 text-dim" />
        <input type="range" min={0} max={vol.max} value={vol.level} onChange={handleChange} className="vol-slider min-w-0 flex-1" />
      </div>
    </div>
  );
}

function ActionsCard({
  connected,
  busy,
  paired,
  onWireless,
  onPairWireless,
  onUnpair,
  onScreenshot,
}: {
  connected: AdbDevice | null;
  busy: Record<string, boolean>;
  paired: boolean;
  onWireless: () => void;
  onPairWireless: () => void;
  onUnpair: () => void;
  onScreenshot: () => void;
}) {
  const actions = [
    connected &&
      connected.transport === "usb" && {
        label: t("Go wireless"),
        hint: t("Switch to Wi-Fi ADB"),
        spinning: busy.wireless,
        onClick: onWireless,
        icon: "wifi",
      },
    {
      label: t("Connect via QR"),
      hint: t("Wireless pairing, Android 11+"),
      spinning: false,
      onClick: onPairWireless,
      icon: "qrcode",
    },
    connected && {
      label: t("Take screenshot"),
      hint: t("Saves to Downloads"),
      spinning: busy.shot,
      onClick: onScreenshot,
      icon: "camera",
    },
    paired && {
      label: t("Forget phone"),
      hint: t("Unpair this device"),
      spinning: busy.unpair,
      onClick: onUnpair,
      danger: true,
      icon: "x",
    },
  ].filter(Boolean) as {
    label: string;
    hint: string;
    spinning?: boolean;
    onClick: () => void;
    icon: string;
    danger?: boolean;
  }[];

  if (actions.length === 0) return null;

  return (
    <div className="card divide-y divide-line overflow-hidden">
      {actions.map(({ label, hint, spinning, onClick, icon, danger }) => (
        <button
          key={label}
          onClick={onClick}
          disabled={spinning}
          className="flex w-full items-center gap-3 px-4 py-3 text-start transition-colors hover:bg-panel2 disabled:opacity-40"
        >
          <Icon
            name={icon}
            size={15}
            className={`shrink-0 ${spinning ? "spinner text-dim" : danger ? "text-bad" : "text-dim"}`}
          />
          <span className={`flex-1 truncate text-[13px] font-medium ${danger ? "text-bad" : "text-fg"}`}>{label}</span>
          <span className="shrink-0 text-[11px] text-faint">{hint}</span>
        </button>
      ))}
    </div>
  );
}
