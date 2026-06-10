import { useEffect, useState } from 'react'
import { MonitorSmartphone, Usb, Camera, ExternalLink, Square } from 'lucide-react'

/**
 * SCREEN tab — a launcher for the pop-out, phone-shaped mirror window. The actual
 * decoding/rendering and control happen in that separate window (MirrorWindow); this
 * just starts it and shows status. Screen mirror and phone camera, both over Wi-Fi.
 */
export default function MirrorView({ linked, onToast }) {
  const [active, setActive] = useState(null) // null | 'screen' | 'camera'
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const offStarted = window.droid.onMirrorStarted((m) => {
      setActive(m.source || 'screen')
      setBusy(false)
    })
    const offStopped = window.droid.onMirrorStopped(() => setActive(null))
    const offError = window.droid.onMirrorError((m) => {
      onToast('bad', m.error || 'Mirror failed')
      setActive(null)
      setBusy(false)
    })
    return () => {
      offStarted()
      offStopped()
      offError()
    }
  }, [onToast])

  const open = async (source) => {
    setBusy(true)
    const r = await window.droid.mirrorPopout(source)
    if (!r.ok) {
      setBusy(false)
      onToast('bad', r.error)
    } else {
      onToast(
        'info',
        source === 'camera' ? 'Allow the camera on your phone…' : 'Approve screen capture on your phone…'
      )
    }
  }

  if (!linked) {
    return (
      <Empty
        icon={Usb}
        title="NO PHONE LINK"
        body="Screen mirroring & camera stream over the Wi-Fi app link. Pair the phone app first."
      />
    )
  }

  if (active) {
    return (
      <Empty
        icon={active === 'camera' ? Camera : MonitorSmartphone}
        title={active === 'camera' ? 'CAMERA IN A WINDOW' : 'MIRRORING IN A WINDOW'}
        body="Your phone is streaming in its own pop-out window. Move it, resize it, or pin it on top."
      >
        <div className="mt-5 flex items-center justify-center gap-3">
          <button
            onClick={() => window.droid.mirrorFocus()}
            className="flex items-center gap-2 border border-amber/40 px-4 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/10"
          >
            <ExternalLink size={13} />
            BRING TO FRONT
          </button>
          <button
            onClick={() => window.droid.mirrorStop()}
            className="flex items-center gap-2 border border-line px-4 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-dim transition-colors hover:border-bad/50 hover:text-bad"
          >
            <Square size={12} />
            STOP
          </button>
        </div>
      </Empty>
    )
  }

  return (
    <Empty
      icon={MonitorSmartphone}
      title="MIRROR YOUR PHONE"
      body="Opens a separate, phone-shaped window over Wi-Fi — no ADB, scrcpy or Developer Options. You'll approve a one-time prompt on the phone."
    >
      <div className="mt-5 flex items-center justify-center gap-3">
        <button
          onClick={() => open('screen')}
          disabled={busy}
          className="flex items-center gap-2 border border-amber/50 bg-amber/10 px-5 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/20 disabled:opacity-60"
        >
          <MonitorSmartphone size={13} />
          {busy ? 'WAITING…' : 'SCREEN'}
        </button>
        <button
          onClick={() => open('camera')}
          disabled={busy}
          className="flex items-center gap-2 border border-line px-5 py-2 font-display text-[11px] font-semibold tracking-[0.2em] text-dim transition-colors hover:border-amber/40 hover:text-amber disabled:opacity-60"
        >
          <Camera size={13} />
          CAMERA
        </button>
      </div>
    </Empty>
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
