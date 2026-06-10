import { useCallback, useEffect, useRef, useState } from 'react'
import { MonitorSmartphone, Usb, Square, ArrowLeft, Circle, SquareStack, Camera } from 'lucide-react'

/**
 * Screen mirroring over the Wi-Fi app link (no ADB/scrcpy). The phone captures its
 * screen with MediaProjection, encodes H.264, and streams access units here; we decode
 * with WebCodecs and paint to a canvas. View-only for now.
 */
export default function MirrorView({ linked, onToast }) {
  const canvasRef = useRef(null)
  const decoderRef = useRef(null)
  const tsRef = useRef(0)
  const waitingKeyRef = useRef(true)
  const downRef = useRef(null) // pointer-down origin for tap-vs-swipe
  const [state, setState] = useState('idle') // idle | requesting | live
  const [source, setSource] = useState('screen') // screen | camera

  const teardown = useCallback(() => {
    const d = decoderRef.current
    if (d && d.state !== 'closed') {
      try {
        d.close()
      } catch {
        /* already closing */
      }
    }
    decoderRef.current = null
  }, [])

  const setupDecoder = useCallback(
    (codec) => {
      teardown()
      const dec = new VideoDecoder({
        output: (frame) => {
          // Read the canvas at draw time — it isn't mounted yet when we configure.
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
      // Annex-B stream (no description) — keyframes carry SPS/PPS inline.
      try {
        dec.configure({ codec: codec || 'avc1.42E01E', optimizeForLatency: true })
      } catch (e) {
        onToast('bad', `Can't decode this stream: ${e.message}`)
      }
      decoderRef.current = dec
      tsRef.current = 0
      waitingKeyRef.current = true
    },
    [onToast, teardown]
  )

  useEffect(() => {
    const offStarted = window.droid.onMirrorStarted((m) => {
      setSource(m.source || 'screen')
      setState('live')
      setupDecoder(m.codec)
    })
    const offFrame = window.droid.onMirrorFrame(({ key, data }) => {
      const dec = decoderRef.current
      if (!dec || dec.state !== 'configured') return
      if (waitingKeyRef.current && !key) return // start clean on a keyframe
      waitingKeyRef.current = false
      try {
        dec.decode(
          new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: tsRef.current++, data })
        )
      } catch {
        /* drop a bad chunk; the next keyframe recovers */
      }
    })
    const offStopped = window.droid.onMirrorStopped(() => {
      teardown()
      setState('idle')
    })
    const offError = window.droid.onMirrorError((m) => {
      onToast('bad', m.error || 'Mirror failed')
      teardown()
      setState('idle')
    })
    return () => {
      offStarted()
      offFrame()
      offStopped()
      offError()
      teardown()
    }
  }, [onToast, setupDecoder, teardown])

  const start = async () => {
    setState('requesting')
    const r = await window.droid.mirrorStart()
    if (!r.ok) {
      setState('idle')
      onToast('bad', r.error)
    } else {
      onToast('info', 'Approve "Start screen capture" on your phone…')
    }
  }

  const startCamera = async () => {
    setState('requesting')
    const r = await window.droid.cameraStart('back')
    if (!r.ok) {
      setState('idle')
      onToast('bad', r.error)
    } else {
      onToast('info', 'Allow the camera on your phone…')
    }
  }

  const stop = () => {
    window.droid.mirrorStop()
    teardown()
    setState('idle')
  }

  // --- control: map canvas pointer position → 0..1 fractions of the phone screen ---
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
    if (dist < 0.02) {
      window.droid.mirrorInput({ type: 'mirror-tap', x: p.x, y: p.y })
    } else {
      window.droid.mirrorInput({
        type: 'mirror-swipe',
        x1: d.x, y1: d.y, x2: p.x, y2: p.y,
        dur: Math.min(800, Math.max(60, Date.now() - d.t))
      })
    }
  }
  const onWheel = (e) => {
    const dy = e.deltaY > 0 ? -0.3 : 0.3 // content moves opposite to the wheel
    window.droid.mirrorInput({ type: 'mirror-swipe', x1: 0.5, y1: 0.5, x2: 0.5, y2: 0.5 + dy, dur: 120 })
  }
  const key = (k) => window.droid.mirrorInput({ type: 'mirror-key', key: k })

  if (!linked) {
    return (
      <Empty
        icon={Usb}
        title="NO PHONE LINK"
        body="Screen mirroring streams over the Wi-Fi app link. Pair the phone app first."
      />
    )
  }

  if (state !== 'live') {
    return (
      <Empty
        icon={MonitorSmartphone}
        title="MIRROR YOUR PHONE"
        body="Streams your phone screen or camera here over Wi-Fi — no ADB, scrcpy or Developer Options. You'll approve a one-time prompt on the phone."
      >
        <div className="mt-5 flex items-center justify-center gap-3">
          <button
            onClick={start}
            disabled={state === 'requesting'}
            className="flex items-center gap-2 border border-amber/50 bg-amber/10 px-5 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/20 disabled:opacity-60"
          >
            <MonitorSmartphone size={13} />
            {state === 'requesting' ? 'WAITING…' : 'SCREEN'}
          </button>
          <button
            onClick={startCamera}
            disabled={state === 'requesting'}
            className="flex items-center gap-2 border border-line px-5 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-dim transition-colors hover:border-amber/40 hover:text-amber disabled:opacity-60"
          >
            <Camera size={13} />
            CAMERA
          </button>
        </div>
      </Empty>
    )
  }

  const isCam = source === 'camera'

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-line px-5">
        <span className="font-mono text-[10px] tracking-[0.25em] text-ok">
          ● {isCam ? 'CAMERA' : 'MIRRORING'} · WI-FI
        </span>
        <div className="flex items-center gap-1.5">
          {!isCam && (
            <>
              <NavBtn title="Back" onClick={() => key('back')}>
                <ArrowLeft size={14} />
              </NavBtn>
              <NavBtn title="Home" onClick={() => key('home')}>
                <Circle size={13} />
              </NavBtn>
              <NavBtn title="Recents" onClick={() => key('recents')}>
                <SquareStack size={13} />
              </NavBtn>
            </>
          )}
          <button
            onClick={stop}
            className="ml-2 flex items-center gap-1.5 border border-line px-3 py-1.5 font-display text-[11px] font-semibold tracking-wider text-dim transition-colors hover:border-bad/50 hover:text-bad"
          >
            <Square size={11} />
            STOP
          </button>
        </div>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center bg-ink p-4">
        <canvas
          ref={canvasRef}
          onPointerDown={isCam ? undefined : onDown}
          onPointerUp={isCam ? undefined : onUp}
          onWheel={isCam ? undefined : onWheel}
          className={`max-h-full max-w-full touch-none border border-line ${isCam ? '' : 'cursor-pointer'}`}
        />
      </div>
    </div>
  )
}

function NavBtn({ title, onClick, children }) {
  return (
    <button
      onClick={onClick}
      title={title}
      className="border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
    >
      {children}
    </button>
  )
}

function Empty({ icon: Icon, title, body, children }) {
  return (
    <div className="flex h-full flex-1 items-center justify-center p-8">
      <div className="max-w-sm border border-line bg-panel p-8 text-center">
        <Icon size={22} strokeWidth={1.5} className="mx-auto text-amber" />
        <p className="mt-4 font-display text-sm font-semibold tracking-[0.25em]">{title}</p>
        <p className="mt-2 text-[12px] leading-relaxed text-dim">{body}</p>
        {children}
      </div>
    </div>
  )
}
