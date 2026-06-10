import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, Circle, SquareStack, Pin, X, SwitchCamera } from 'lucide-react'

/**
 * Pop-out, phone-shaped mirror window (the #mirror route). Decodes the H.264 stream with
 * WebCodecs, paints to a canvas, and forwards taps/swipes/nav back to the phone. The
 * window itself is aspect-locked by the main process so it always looks like a phone.
 */
export default function MirrorWindow() {
  const canvasRef = useRef(null)
  const decoderRef = useRef(null)
  const tsRef = useRef(0)
  const waitingKeyRef = useRef(true)
  const downRef = useRef(null)
  const [live, setLive] = useState(false)
  const [source, setSource] = useState('screen')
  const [facing, setFacing] = useState('back')
  const [onTop, setOnTop] = useState(false)
  const isCam = source === 'camera'

  useEffect(() => {
    const setupDecoder = (codec) => {
      const old = decoderRef.current
      if (old && old.state !== 'closed') {
        try {
          old.close()
        } catch {
          /* closing */
        }
      }
      const dec = new VideoDecoder({
        output: (frame) => {
          const canvas = canvasRef.current
          const ctx = canvas?.getContext('2d')
          if (canvas && ctx) {
            if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth
            if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight
            ctx.drawImage(frame, 0, 0)
          }
          frame.close()
        },
        error: (e) => console.warn('mirror decode:', e?.message || e)
      })
      try {
        dec.configure({ codec: codec || 'avc1.42E01E', optimizeForLatency: true })
      } catch {
        /* unsupported — the window will just stay blank */
      }
      decoderRef.current = dec
      tsRef.current = 0
      waitingKeyRef.current = true
    }

    const offStarted = window.droid.onMirrorStarted((m) => {
      setSource(m.source || 'screen')
      setFacing(m.facing || 'back')
      setLive(true)
      setupDecoder(m.codec)
    })
    const offFrame = window.droid.onMirrorFrame(({ key, data }) => {
      const dec = decoderRef.current
      if (!dec || dec.state !== 'configured') return
      if (waitingKeyRef.current && !key) return
      waitingKeyRef.current = false
      try {
        dec.decode(
          new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: tsRef.current++, data })
        )
      } catch {
        /* drop bad chunk */
      }
    })
    const offStopped = window.droid.onMirrorStopped(() => window.close())
    const offError = window.droid.onMirrorError(() => window.close())
    return () => {
      offStarted()
      offFrame()
      offStopped()
      offError()
      const d = decoderRef.current
      if (d && d.state !== 'closed') {
        try {
          d.close()
        } catch {
          /* closing */
        }
      }
    }
  }, [])

  // Type on the Mac keyboard → inject into the focused field on the phone (screen mode).
  useEffect(() => {
    if (!live || isCam) return undefined
    const onKey = (e) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return // leave shortcuts alone
      if (e.key === 'Backspace') {
        window.droid.mirrorInput({ type: 'mirror-text', op: 'backspace' })
        e.preventDefault()
      } else if (e.key === 'Enter') {
        window.droid.mirrorInput({ type: 'mirror-text', op: 'enter' })
        e.preventDefault()
      } else if (e.key.length === 1) {
        window.droid.mirrorInput({ type: 'mirror-text', text: e.key })
        e.preventDefault()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [live, isCam])

  // --- control: canvas pointer → 0..1 phone-screen fractions ---
  const frac = (e) => {
    const c = canvasRef.current
    if (!c) return null
    const r = c.getBoundingClientRect()
    return {
      x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))
    }
  }
  const onDown = (e) => {
    const p = frac(e)
    if (p) downRef.current = { ...p, t: Date.now() }
  }
  const onUp = (e) => {
    const d = downRef.current
    downRef.current = null
    const p = frac(e)
    if (!d || !p) return
    const dist = Math.hypot(p.x - d.x, p.y - d.y)
    if (dist < 0.02) window.droid.mirrorInput({ type: 'mirror-tap', x: p.x, y: p.y })
    else
      window.droid.mirrorInput({
        type: 'mirror-swipe',
        x1: d.x, y1: d.y, x2: p.x, y2: p.y,
        dur: Math.min(800, Math.max(60, Date.now() - d.t))
      })
  }
  const onWheel = (e) => {
    const dy = e.deltaY > 0 ? -0.3 : 0.3
    window.droid.mirrorInput({ type: 'mirror-swipe', x1: 0.5, y1: 0.5, x2: 0.5, y2: 0.5 + dy, dur: 120 })
  }
  const key = (k) => window.droid.mirrorInput({ type: 'mirror-key', key: k })
  const flipCamera = () =>
    window.droid.mirrorInput({ type: 'camera-flip', facing: facing === 'front' ? 'back' : 'front' })
  const toggleTop = () => {
    const next = !onTop
    setOnTop(next)
    window.droid.mirrorSetOnTop(next)
  }

  return (
    <div className="flex h-screen select-none flex-col bg-ink">
      <div className="drag flex h-9 shrink-0 items-center justify-between border-b border-line bg-panel px-2.5">
        <span className="font-mono text-[9px] tracking-[0.2em] text-ok">
          ● {isCam ? 'CAMERA' : 'MIRROR'}
        </span>
        <div className="no-drag flex items-center gap-1">
          {!isCam && live && (
            <>
              <Btn title="Back" onClick={() => key('back')}>
                <ArrowLeft size={13} />
              </Btn>
              <Btn title="Home" onClick={() => key('home')}>
                <Circle size={12} />
              </Btn>
              <Btn title="Recents" onClick={() => key('recents')}>
                <SquareStack size={12} />
              </Btn>
            </>
          )}
          {isCam && live && (
            <Btn title="Switch front/back camera" onClick={flipCamera}>
              <SwitchCamera size={13} />
            </Btn>
          )}
          <Btn title={onTop ? 'Unpin (on top)' : 'Keep on top'} onClick={toggleTop} active={onTop}>
            <Pin size={12} />
          </Btn>
          <Btn title="Close" onClick={() => window.close()} danger>
            <X size={13} />
          </Btn>
        </div>
      </div>

      <div className="flex min-h-0 flex-1 items-center justify-center">
        {live ? (
          <canvas
            ref={canvasRef}
            onPointerDown={isCam ? undefined : onDown}
            onPointerUp={isCam ? undefined : onUp}
            onWheel={isCam ? undefined : onWheel}
            onContextMenu={
              isCam
                ? undefined
                : (e) => {
                    e.preventDefault()
                    key('back')
                  }
            }
            style={isCam && facing === 'front' ? { transform: 'scaleX(-1)' } : undefined}
            className={`h-full w-full object-contain ${isCam ? '' : 'cursor-pointer'}`}
          />
        ) : (
          <p className="font-mono text-[11px] tracking-widest text-dim">
            APPROVE ON PHONE…
          </p>
        )}
      </div>
    </div>
  )
}

function Btn({ title, onClick, children, active, danger }) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`border border-transparent p-1 transition-colors hover:border-line ${
        danger
          ? 'text-dim hover:border-bad/50 hover:text-bad'
          : active
            ? 'text-amber'
            : 'text-dim hover:text-amber'
      }`}
    >
      {children}
    </button>
  )
}
