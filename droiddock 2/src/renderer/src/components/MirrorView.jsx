import { useCallback, useEffect, useRef, useState } from 'react'
import { MonitorSmartphone, Usb, Square } from 'lucide-react'

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
  const [state, setState] = useState('idle') // idle | requesting | live

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
      const canvas = canvasRef.current
      const ctx = canvas?.getContext('2d')
      const dec = new VideoDecoder({
        output: (frame) => {
          if (canvas && ctx) {
            if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth
            if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight
            ctx.drawImage(frame, 0, 0)
          }
          frame.close()
        },
        error: (e) => onToast('bad', `Decode error: ${e.message}`)
      })
      // Annex-B stream (no description) — keyframes carry SPS/PPS inline.
      try {
        dec.configure({ codec, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' })
      } catch {
        dec.configure({ codec: codec || 'avc1.42E01E', optimizeForLatency: true })
      }
      decoderRef.current = dec
      tsRef.current = 0
      waitingKeyRef.current = true
    },
    [onToast, teardown]
  )

  useEffect(() => {
    const offStarted = window.droid.onMirrorStarted((m) => {
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
          new EncodedVideoChunk({
            type: key ? 'key' : 'delta',
            timestamp: tsRef.current++,
            data
          })
        )
      } catch {
        /* drop a bad chunk; next keyframe recovers */
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

  const stop = () => {
    window.droid.mirrorStop()
    teardown()
    setState('idle')
  }

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
        body="Streams your phone screen here over Wi-Fi — no ADB, scrcpy or Developer Options. You'll approve a one-time screen-capture prompt on the phone."
      >
        <button
          onClick={start}
          disabled={state === 'requesting'}
          className="mt-5 border border-amber/50 bg-amber/10 px-5 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/20 disabled:opacity-60"
        >
          {state === 'requesting' ? 'WAITING FOR PHONE…' : 'START MIRRORING'}
        </button>
      </Empty>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-line px-5">
        <span className="font-mono text-[10px] tracking-[0.25em] text-ok">● MIRRORING · WI-FI</span>
        <button
          onClick={stop}
          className="flex items-center gap-1.5 border border-line px-3 py-1.5 font-display text-[11px] font-semibold tracking-wider text-dim transition-colors hover:border-bad/50 hover:text-bad"
        >
          <Square size={11} />
          STOP
        </button>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center bg-ink p-4">
        <canvas ref={canvasRef} className="max-h-full max-w-full border border-line object-contain" />
      </div>
    </div>
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
