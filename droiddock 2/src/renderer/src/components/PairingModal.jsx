import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import QRCode from 'qrcode'

export default function PairingModal({ status, onClose }) {
  const [qr, setQr] = useState(null)

  useEffect(() => {
    let alive = true
    window.droid.wifiPayload().then((payload) =>
      QRCode.toDataURL(payload, {
        margin: 1,
        width: 232,
        color: { dark: '#0b0d10', light: '#f4efe6' }
      }).then((url) => alive && setQr(url))
    )
    return () => {
      alive = false
    }
  }, [])

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-ink/80 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="rise w-[420px] border border-line bg-panel p-7"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <div>
            <p className="font-display text-sm font-semibold tracking-[0.22em]">LINK PHONE APP</p>
            <p className="mt-1 text-[12px] text-dim">
              Clipboard + notifications. Open the DroidDock app on Android →{' '}
              <b className="text-fg">Pair with Mac</b> → scan
            </p>
          </div>
          <button onClick={onClose} className="p-1 text-dim transition-colors hover:text-amber">
            <X size={16} />
          </button>
        </div>

        <div className="mt-5 flex justify-center">
          {qr ? (
            <img src={qr} alt="pairing QR" className="border-4 border-[#f4efe6]" />
          ) : (
            <div className="h-[240px] w-[240px] animate-pulse bg-panel2" />
          )}
        </div>

        <div className="mt-5 border-t border-line pt-4 font-mono text-[10px] leading-relaxed text-dim">
          <p className="tracking-[0.2em] text-dim/70">MANUAL FALLBACK</p>
          <p className="mt-1.5">
            address&nbsp;&nbsp;
            <span className="text-fg">
              {status.ips.join('  ·  ') || '—'} : {status.port}
            </span>
          </p>
          <p className="mt-1 break-all">
            token&nbsp;&nbsp;&nbsp;&nbsp;<span className="text-amber">{status.token}</span>
          </p>
          <p className="mt-3 text-dim/60">
            Phone and Mac must be on the same Wi-Fi network. If macOS asks about incoming
            connections — allow.
          </p>
        </div>
      </div>
    </div>
  )
}
