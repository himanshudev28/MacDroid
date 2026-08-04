import { useEffect, useRef, useState } from "react";
import Icon from "../Icon";
import { mediaCmd, type AppDeviceInfo, type MediaState } from "../../lib/bridge";

/// The one-line footer of the phone card: battery, phone volume, and the
/// show/hide toggle for the mini player. Volume opens a popover slider that
/// drives the same `media-cmd setvol` the Media tab uses.
export default function StatusStrip({
  info,
  media,
  playerOpen,
  onTogglePlayer,
}: {
  info: AppDeviceInfo | null;
  media: MediaState | null;
  playerOpen: boolean;
  onTogglePlayer: () => void;
}) {
  const [volOpen, setVolOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!volOpen) return;
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setVolOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [volOpen]);

  const battery = typeof info?.battery === "number" ? Math.round(info.battery) : null;
  const volMax = media?.volMax ?? 0;
  const vol = media?.vol ?? 0;
  const volPct = volMax > 0 ? Math.round((vol / volMax) * 100) : null;
  const hasTrack = !!media?.active && !!(media.title || media.artist);

  return (
    <div className="flex items-center justify-center gap-1.5">
      {battery !== null && (
        <span
          className="on-glass flex items-center gap-1.5 rounded-full px-2.5 py-1"
          title={info?.charging ? `${battery}% — charging` : `${battery}%`}
        >
          <BatteryGlyph level={battery} charging={!!info?.charging} />
          <span className="text-[11px] font-medium tabular-nums text-white/85">{battery}%</span>
        </span>
      )}

      {volMax > 0 && (
        <div ref={ref} className="relative">
          <button
            onClick={() => setVolOpen((o) => !o)}
            title={volPct !== null ? `Phone volume — ${volPct}%` : "Phone volume"}
            aria-label="Phone volume"
            className="on-glass flex h-[26px] w-[26px] items-center justify-center rounded-full text-white/80 transition-colors hover:text-white"
          >
            <Icon name="volume" size={12} strokeWidth={2} />
          </button>

          {volOpen && (
            <div className="rise-fast glass-heavy absolute bottom-[calc(100%+8px)] left-1/2 z-30 w-45 -translate-x-1/2 rounded-xl border border-line p-3 float-md">
              <div className="flex items-center gap-2.5">
                <Icon name="volume" size={13} className="shrink-0 text-dim" />
                <input
                  type="range"
                  min={0}
                  max={volMax}
                  value={vol}
                  onChange={(e) => mediaCmd("setvol", Number(e.target.value))}
                  aria-label="Phone volume"
                  className="vol-slider min-w-0 flex-1"
                />
                <span className="data w-8 shrink-0 text-right text-faint">{volPct}%</span>
              </div>
            </div>
          )}
        </div>
      )}

      {hasTrack && (
        <button
          onClick={onTogglePlayer}
          title={playerOpen ? "Hide player" : "Show player"}
          aria-label={playerOpen ? "Hide player" : "Show player"}
          className={`flex h-[26px] w-[26px] items-center justify-center rounded-full transition-colors ${
            playerOpen ? "on-glass-active text-white" : "on-glass text-white/80 hover:text-white"
          }`}
        >
          <Icon name={media?.playing ? "play" : "volume"} size={12} strokeWidth={2} fill={media?.playing ? "currentColor" : "none"} />
        </button>
      )}
    </div>
  );
}

/// Drawn rather than glyph-mapped so the fill tracks the real percentage —
/// a 5-bucket SF-Symbols-style mapping loses that.
function BatteryGlyph({ level, charging }: { level: number; charging: boolean }) {
  const fill = Math.max(0, Math.min(100, level));
  const color = charging ? "var(--color-link)" : fill <= 15 ? "var(--color-bad)" : "#fff";
  return (
    <svg width="20" height="11" viewBox="0 0 20 11" aria-hidden="true" className="shrink-0">
      <rect x="0.5" y="0.5" width="16" height="10" rx="3" fill="none" stroke="currentColor" strokeOpacity="0.45" />
      <path d="M18 4v3a1.8 1.8 0 000-3z" fill="currentColor" fillOpacity="0.45" />
      <rect x="2" y="2" width={Math.max(1.5, (13 * fill) / 100)} height="7" rx="1.6" fill={color} />
      {charging && (
        <path d="M9.6 1.6L6.4 6.1h2.2l-1 3.4 3.4-4.7H8.8z" fill="#000" fillOpacity="0.55" stroke="none" />
      )}
    </svg>
  );
}
