import { useEffect, useRef, useState } from "react";
import Icon from "../Icon";
import type { WifiStatus } from "../../lib/wifi";
import type { AdbDevice, DroidConfig, LinkQuality } from "../../lib/bridge";
import { t } from "../../lib/i18n";

/// Live transport summary, sitting at the top of the phone card. Each glyph is
/// one *fact*: the Wi-Fi link, the ADB link (and whether it's cable or
/// wireless), clipboard sync. Click for the detail popover — device name, both
/// addresses, the ADB serial. Modelled on AirSync's `ConnectionStatusPill`,
/// which is the only place that app shows connection detail at all.
export default function ConnectionPill({
  status,
  adb,
  config,
  quality,
  port,
  ip,
}: {
  status: WifiStatus;
  adb: AdbDevice | null;
  config: DroidConfig | null;
  quality: LinkQuality | null;
  port: number | null;
  ip: string | null;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const wired = adb?.transport === "usb";

  // The Wi-Fi glyph carries the grade: green while the link is healthy, amber
  // once round-trips slow down, red once the phone stops answering entirely.
  const linkTint = !status.connected
    ? "text-white/35"
    : quality?.grade === "stalled"
      ? "text-(--color-bad)"
      : quality?.grade === "weak"
        ? "text-(--color-warn)"
        : quality?.grade === "fair"
          ? "text-(--color-warn)"
          : "text-(--color-link)";

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        title={t("Connection detail")}
        className="on-glass flex items-center gap-2 rounded-full px-2.5 py-1 text-white/85 transition-colors"
      >
        <span className="flex items-center gap-1">
          <Icon name="wifi" size={12} strokeWidth={2} className={linkTint} />
        </span>

        {adb && (
          <span className="flex items-center gap-1" title={wired ? "Wired ADB" : "Wireless ADB"}>
            <Icon name={wired ? "terminal" : "monitor"} size={12} strokeWidth={2} />
          </span>
        )}

        {config?.clipboardSync && (
          <Icon name="clipboard" size={12} strokeWidth={2} className="text-white/55" title={t("Clipboard sync on")} />
        )}

        {config?.pausedUntil != null && (
          <Icon name="pause" size={11} strokeWidth={2} className="text-(--color-warn)" />
        )}
      </button>

      {open && (
        <div className="rise-fast glass-heavy absolute left-1/2 top-[calc(100%+8px)] z-30 w-63 -translate-x-1/2 rounded-xl border border-line p-3 text-start float-md">
          <Row label={t("Device")} value={status.phoneName ?? t("Not linked")} />
          <Row
            label={t("Wi-Fi link")}
            value={
              !status.connected
                ? "Waiting"
                : quality?.grade === "stalled"
                  ? t("Not responding")
                  : quality?.rttMs != null
                    ? `${GRADE_LABEL[quality.grade]} · ${Math.round(quality.rttMs)} ms`
                    : "Connected"
            }
            accent={status.connected && quality?.grade !== "stalled"}
          />
          {ip && <Row label={t("Mac address")} value={`${ip}${port ? `:${port}` : ""}`} mono />}
          {adb ? (
            <>
              <Row label="ADB" value={wired ? "Cable" : "Wireless"} accent />
              <Row label={t("Serial")} value={adb.serial} mono />
            </>
          ) : (
            <Row label="ADB" value="Not connected" />
          )}
          {config?.pausedUntil != null && (
            <Row
              label={t("Paused until")}
              value={new Date(config.pausedUntil).toLocaleString()}
            />
          )}
        </div>
      )}
    </div>
  );
}

const GRADE_LABEL: Record<LinkQuality["grade"], string> = {
  good: "Good",
  fair: "Fair",
  weak: "Weak",
  stalled: t("Not responding"),
};

function Row({
  label,
  value,
  mono,
  accent,
}: {
  label: string;
  value: string;
  mono?: boolean;
  accent?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1">
      <span className="label shrink-0">{label}</span>
      <span
        className={`min-w-0 truncate text-end text-[12px] ${mono ? "data" : ""} ${
          accent ? "text-(--color-link)" : "text-fg"
        }`}
        title={value}
      >
        {value}
      </span>
    </div>
  );
}
