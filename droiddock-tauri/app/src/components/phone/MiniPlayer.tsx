import Icon from "../Icon";
import { fmtDuration } from "../../lib/ui";
import { mediaCmd, type MediaState } from "../../lib/bridge";

/// Now-playing, inline on the phone card. Same `media` push and `media-cmd`
/// wire calls the full Media tab uses — this is a second surface on the same
/// data, not a second source of truth. Sits on glass, so everything is white.
export default function MiniPlayer({ media }: { media: MediaState }) {
  const cmd = (c: string, v = 0) => mediaCmd(c, v);
  const hasSeek = (media.dur ?? 0) > 0;
  const pos = Math.min(media.pos ?? 0, media.dur ?? 0);

  return (
    <div className="on-glass rise-fast rounded-xl px-3 py-2.5">
      <div className="flex items-center gap-1.5">
        <Icon name="volume" size={11} strokeWidth={2} className="shrink-0 text-white/50" />
        <p className="min-w-0 flex-1 truncate text-[11.5px] font-medium text-white/95">
          {media.title || "Unknown track"}
        </p>
      </div>
      <p className="mt-0.5 truncate text-[10.5px] text-white/55">
        {media.artist || media.app || ""}
      </p>

      {hasSeek && (
        <div className="mt-2">
          <input
            type="range"
            min={0}
            max={media.dur}
            value={pos}
            onChange={(e) => cmd("seek", Number(e.target.value))}
            aria-label="Seek"
            className="seek-slider seek-on-glass w-full"
          />
          <div className="mt-0.5 flex justify-between text-[9.5px] tabular-nums text-white/45">
            <span>{fmtDuration(pos)}</span>
            <span>{fmtDuration(media.dur ?? 0)}</span>
          </div>
        </div>
      )}

      <div className="mt-1.5 flex items-center justify-center gap-4">
        <GlassCtl label="Previous" onClick={() => cmd("prev")}>
          <Icon name="skipBack" size={13} fill="currentColor" strokeWidth={0} />
        </GlassCtl>
        <GlassCtl
          label={media.playing ? "Pause" : "Play"}
          primary
          onClick={() => cmd(media.playing ? "pause" : "play")}
        >
          <Icon name={media.playing ? "pause" : "play"} size={13} fill="currentColor" strokeWidth={0} />
        </GlassCtl>
        <GlassCtl label="Next" onClick={() => cmd("next")}>
          <Icon name="skipForward" size={13} fill="currentColor" strokeWidth={0} />
        </GlassCtl>
      </div>
    </div>
  );
}

function GlassCtl({
  label,
  primary,
  onClick,
  children,
}: {
  label: string;
  primary?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      title={label}
      aria-label={label}
      className={`flex items-center justify-center rounded-full transition-transform active:scale-95 ${
        primary
          ? "h-8 w-8 bg-white/90 text-black hover:bg-white"
          : "h-7 w-7 text-white/75 hover:bg-white/12 hover:text-white"
      }`}
    >
      {children}
    </button>
  );
}
