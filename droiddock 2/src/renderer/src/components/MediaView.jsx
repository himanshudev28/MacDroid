import { useRef, useState } from 'react'

export default function MediaView({ media, onCmd }) {
  if (!media?.active) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="rise max-w-xs rounded-2xl border border-line bg-panel2 p-8 luminous-sm text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-amber/20 bg-amber/8">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-amber" aria-hidden="true">
              <path d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
            </svg>
          </div>
          <p className="text-[13px] font-semibold text-fg/80">Nothing playing</p>
          <p className="mt-2 text-[11.5px] leading-relaxed text-dim/70">
            Play music or a video on your phone and it will appear here.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col items-center justify-center p-8">
      <div className="w-full max-w-sm space-y-5">

        <div className="rounded-2xl border border-line bg-panel2 p-6 luminous-sm">
          <div className="flex items-start gap-4">
            <div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border ${media.playing ? 'border-amber/20 bg-amber/8' : 'border-line bg-panel3'}`}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className={`h-6 w-6 ${media.playing ? 'text-amber led' : 'text-dim/50'}`} aria-hidden="true">
                <path d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
              </svg>
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[14px] font-semibold text-fg/95">
                {media.title || 'Unknown track'}
              </p>
              <p className="mt-0.5 truncate font-mono text-[11px] text-dim/60">
                {media.artist || media.app || ''}
              </p>
              <div className="mt-1.5 flex items-center gap-1.5">
                <span className={`h-1.5 w-1.5 rounded-full ${media.playing ? 'bg-ok led' : 'bg-dim/30'}`} />
                <span className="font-mono text-[9px] text-dim/50">
                  {media.playing ? 'Playing' : 'Paused'}{media.app ? ` · ${media.app}` : ''}
                </span>
              </div>
            </div>
          </div>

          {media.dur > 0 && (
            <SeekSlider pos={media.pos ?? 0} dur={media.dur} onSeek={(ms) => onCmd('seek', ms)} />
          )}

          <div className="mt-5 flex items-center justify-center gap-3">
            <CtrlBtn onClick={() => onCmd('prev')} title="Previous">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4" aria-hidden="true">
                <path d="M19 20L9 12l10-8v16zM5 19V5" />
              </svg>
            </CtrlBtn>
            <CtrlBtn primary onClick={() => onCmd(media.playing ? 'pause' : 'play')} title={media.playing ? 'Pause' : 'Play'}>
              {media.playing ? (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
                  <path d="M10 9v6m4-6v6" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
                  <path d="M5 3l14 9-14 9V3z" />
                </svg>
              )}
            </CtrlBtn>
            <CtrlBtn onClick={() => onCmd('next')} title="Next">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4" aria-hidden="true">
                <path d="M5 4l10 8-10 8V4zM19 5v14" />
              </svg>
            </CtrlBtn>
          </div>

          {media.volMax > 0 && (
            <div className="mt-5">
              <div className="mb-1.5 flex items-center justify-between">
                <span className="font-mono text-[9px] text-dim/55">Volume</span>
                <span className="font-mono text-[9px] text-dim/55">
                  {Math.round((media.vol / media.volMax) * 100)}%
                </span>
              </div>
              <input
                type="range"
                min={0}
                max={media.volMax}
                value={media.vol}
                onChange={(e) => onCmd('setvol', Number(e.target.value))}
                className="vol-slider w-full"
              />
            </div>
          )}
        </div>

      </div>
    </div>
  )
}

function SeekSlider({ pos, dur, onSeek }) {
  const [drag, setDrag] = useState(null)
  const dragging = useRef(false)
  const display = dragging.current ? (drag ?? pos) : pos
  const pct = dur > 0 ? Math.round((display / dur) * 100) : 0

  return (
    <div className="mt-4">
      <div className="mb-1 flex justify-between font-mono text-[9px] text-dim/50">
        <span>{fmtMs(display)}</span>
        <span>{fmtMs(dur)}</span>
      </div>
      <div className="relative mb-1 h-1 overflow-hidden rounded-full bg-panel3">
        <div className="pointer-events-none absolute inset-y-0 left-0 rounded-full bg-amber/70" style={{ width: `${pct}%` }} />
      </div>
      <input
        type="range"
        min={0}
        max={dur}
        value={display}
        onChange={(e) => setDrag(Number(e.target.value))}
        onMouseDown={() => { dragging.current = true }}
        onMouseUp={(e) => { dragging.current = false; setDrag(null); onSeek(Number(e.target.value)) }}
        onTouchEnd={(e) => { dragging.current = false; setDrag(null); onSeek(Number(e.target.value)) }}
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

function CtrlBtn({ children, primary, onClick, title }) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`rounded-xl border p-2.5 transition-colors ${
        primary
          ? 'border-amber/40 bg-amber/10 text-amber hover:bg-amber/18'
          : 'border-line text-dim/60 hover:border-amber/30 hover:text-amber'
      }`}
    >
      {children}
    </button>
  )
}
