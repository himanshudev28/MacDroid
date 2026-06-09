import { useEffect, useRef, useState } from 'react'
import { Music, Play, Pause, SkipBack, SkipForward } from 'lucide-react'

export default function MediaCard({ media, onCmd }) {
  if (!media?.active) return null

  return (
    <>
      <p className="px-5 pb-2 pt-6 font-mono text-[10px] tracking-[0.25em] text-dim">
        NOW PLAYING
      </p>
      <div className="mx-4 border border-line bg-panel2 p-4">
        {/* Track info */}
        <div className="flex items-start gap-3">
          <Music
            size={16}
            strokeWidth={1.75}
            className={
              media.playing
                ? 'led mt-0.5 shrink-0 rounded-full text-amber'
                : 'mt-0.5 shrink-0 text-dim'
            }
          />
          <div className="min-w-0">
            <p className="truncate text-[13px] font-medium">{media.title || 'Unknown track'}</p>
            <p className="truncate font-mono text-[10px] text-dim">
              {media.artist || media.app || ''}
            </p>
          </div>
        </div>

        {/* Seek slider (shown when duration is known from companion app) */}
        {media.dur > 0 && (
          <SeekSlider pos={media.pos ?? 0} dur={media.dur} onSeek={(ms) => onCmd('seek', ms)} />
        )}

        {/* Playback controls */}
        <div className="mt-3 flex items-center justify-center gap-2">
          <Btn onClick={() => onCmd('prev')}>
            <SkipBack size={14} />
          </Btn>
          <Btn primary onClick={() => onCmd(media.playing ? 'pause' : 'play')}>
            {media.playing ? <Pause size={15} /> : <Play size={15} />}
          </Btn>
          <Btn onClick={() => onCmd('next')}>
            <SkipForward size={14} />
          </Btn>
        </div>

        {/* App-reported volume (from companion app, stream-level) */}
        {media.volMax > 0 && (
          <div className="mt-3">
            <input
              type="range"
              min={0}
              max={media.volMax}
              value={media.vol}
              onChange={(e) => onCmd('setvol', Number(e.target.value))}
              className="vol-slider w-full"
            />
            <p className="text-center font-mono text-[8px] tracking-[0.2em] text-dim/60">
              VOLUME
            </p>
          </div>
        )}
      </div>
    </>
  )
}

/* ── Seek slider ─────────────────────────────────────────────────────────── */

function SeekSlider({ pos, dur, onSeek }) {
  const [drag, setDrag] = useState(null) // ms while dragging
  const dragging = useRef(false)

  // Sync display position with live pos when not dragging
  const display = dragging.current ? (drag ?? pos) : pos

  const pct = dur > 0 ? Math.round((display / dur) * 100) : 0

  const handleChange = (e) => {
    setDrag(Number(e.target.value))
  }

  const handleMouseDown = () => {
    dragging.current = true
  }

  const handleMouseUp = (e) => {
    const val = Number(e.target.value)
    dragging.current = false
    setDrag(null)
    onSeek(val)
  }

  // Also handle touch events for trackpad
  const handleTouchEnd = (e) => {
    const val = Number(e.target.value)
    dragging.current = false
    setDrag(null)
    onSeek(val)
  }

  return (
    <div className="mt-3">
      {/* Time labels */}
      <div className="mb-1 flex justify-between font-mono text-[9px] text-dim/60">
        <span>{fmtMs(display)}</span>
        <span>{fmtMs(dur)}</span>
      </div>

      {/* Track background + filled bar */}
      <div className="relative mb-1 h-1 bg-ink">
        <div
          className="pointer-events-none absolute inset-y-0 left-0 bg-amber/70"
          style={{ width: `${pct}%` }}
        />
      </div>

      {/* Range input (transparent, overlaid for interaction) */}
      <input
        type="range"
        min={0}
        max={dur}
        value={display}
        onChange={handleChange}
        onMouseDown={handleMouseDown}
        onMouseUp={handleMouseUp}
        onTouchEnd={handleTouchEnd}
        className="seek-slider w-full opacity-0"
        style={{ marginTop: '-8px', height: '16px' }}
      />
    </div>
  )
}

function fmtMs(ms) {
  const s = Math.floor((ms || 0) / 1000)
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

function Btn({ children, primary, onClick }) {
  return (
    <button
      onClick={onClick}
      className={`border p-2 transition-colors ${
        primary
          ? 'border-amber/50 text-amber hover:bg-amber/10'
          : 'border-line text-dim hover:border-amber/40 hover:text-amber'
      }`}
    >
      {children}
    </button>
  )
}
