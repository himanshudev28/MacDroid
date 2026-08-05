import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { fmtDuration } from "../../lib/ui";
import { mediaCmd, type MediaState } from "../../lib/bridge";

/// Phase 10 — now-playing card. Fields come from the phone's `media` push
/// (title/artist/app/playing/vol/volMax/pos/dur — no album art in the wire
/// protocol, so a music glyph stands in). Controls send `media-cmd` with an
/// integer `value` (ms for seek, a 0..volMax step for setvol). Live position
/// updates arrive once a second while playing.
export default function MediaView({ media }: { media: MediaState | null }) {
  if (!media || !media.active) {
    return (
      <EmptyState
        icon="volume"
        title="Nothing playing"
        body="Play music or a video on your phone and control it from here."
      />
    );
  }

  const cmd = (c: string, v = 0) => mediaCmd(c, v);
  const hasSeek = (media.dur ?? 0) > 0;
  const hasVol = (media.volMax ?? 0) > 0;

  return (
    <div className="flex h-full w-full items-center justify-center overflow-y-auto px-6 py-8">
      <div className="rise w-full max-w-95">
        <div className="card-raised p-6">
          <div className="flex items-center gap-4">
            <div
              className={`flex h-16 w-16 shrink-0 items-center justify-center rounded-xl transition-colors ${
                media.playing
                  ? "bg-[color-mix(in_srgb,var(--color-accent)_14%,transparent)] text-(--color-accent)"
                  : "bg-panel3 text-dim"
              }`}
            >
              <Icon name="volume" size={26} strokeWidth={1.5} />
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate font-display text-[15px] font-semibold text-fg">
                {media.title || "Unknown track"}
              </p>
              <p className="mt-0.5 truncate text-[12.5px] text-dim">{media.artist || media.app || ""}</p>
              <div className="mt-1.5 flex items-center gap-1.5">
                <span
                  className={`h-1.5 w-1.5 rounded-full ${media.playing ? "bg-(--color-accent)" : "bg-faint"}`}
                />
                <span className="truncate text-[11.5px] text-dim">
                  {media.playing ? "Playing" : "Paused"}
                  {media.app ? ` · ${media.app}` : ""}
                </span>
              </div>
            </div>
          </div>

          {hasSeek && (
            <div className="mt-5">
              <input
                type="range"
                min={0}
                max={media.dur}
                value={Math.min(media.pos ?? 0, media.dur ?? 0)}
                onChange={(e) => cmd("seek", Number(e.target.value))}
                className="seek-slider w-full"
              />
              <div className="mt-1.5 flex justify-between">
                <span className="data text-faint">{fmtDuration(media.pos ?? 0)}</span>
                <span className="data text-faint">{fmtDuration(media.dur ?? 0)}</span>
              </div>
            </div>
          )}

          <div className="mt-5 flex items-center justify-center gap-6">
            <button onClick={() => cmd("prev")} className="btn-icon" title="Previous">
              <Icon name="skipBack" size={20} fill="currentColor" strokeWidth={0} />
            </button>
            <button
              onClick={() => cmd(media.playing ? "pause" : "play")}
              title={media.playing ? "Pause" : "Play"}
              className="flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent) text-(--color-accent-ink) transition-opacity hover:opacity-90"
            >
              <Icon name={media.playing ? "pause" : "play"} size={20} fill="currentColor" strokeWidth={0} />
            </button>
            <button onClick={() => cmd("next")} className="btn-icon" title="Next">
              <Icon name="skipForward" size={20} fill="currentColor" strokeWidth={0} />
            </button>
          </div>

          {hasVol && (
            <div className="mt-6 flex items-center gap-3">
              <Icon name="volume" size={14} className="shrink-0 text-dim" />
              <input
                type="range"
                min={0}
                max={media.volMax}
                value={media.vol ?? 0}
                onChange={(e) => cmd("setvol", Number(e.target.value))}
                className="vol-slider min-w-0 flex-1"
              />
              <span className="data w-9 shrink-0 text-right text-faint">
                {Math.round(((media.vol ?? 0) / (media.volMax || 1)) * 100)}%
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
