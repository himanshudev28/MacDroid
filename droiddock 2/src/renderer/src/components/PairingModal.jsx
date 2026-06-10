import { useEffect, useState } from 'react'
import { X, Smartphone, Wifi, Copy, Check } from 'lucide-react'
import QRCode from 'qrcode'

export default function PairingModal({ status, onClose }) {
  const [qr, setQr] = useState(null)
  const [manual, setManual] = useState(false)
  const [copied, setCopied] = useState(null)

  useEffect(() => {
    let alive = true
    window.droid.wifiPayload().then((payload) =>
      QRCode.toDataURL(payload, {
        margin: 1,
        width: 220,
        color: { dark: '#0b0d10', light: '#f4efe6' }
      }).then((url) => alive && setQr(url))
    )
    return () => {
      alive = false
    }
  }, [])

  const copy = (key, val) => {
    navigator.clipboard?.writeText(val)
    setCopied(key)
    setTimeout(() => setCopied((c) => (c === key ? null : c)), 1200)
  }

  const address = `${status.ips[0] || '—'}:${status.port}`

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-ink/80 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="rise relative w-[400px] border border-line bg-panel p-8"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          onClick={onClose}
          className="absolute right-4 top-4 p-1 text-dim transition-colors hover:text-amber"
        >
          <X size={16} />
        </button>

        {/* ── header ── */}
        <div className="flex flex-col items-center text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-amber/30 bg-amber/10">
            <Smartphone size={20} className="text-amber" strokeWidth={1.75} />
          </div>
          <p className="mt-3 font-display text-[17px] font-semibold tracking-wide text-fg">
            Connect Your Android
          </p>
          <p className="mt-1.5 max-w-[18rem] text-[12px] leading-relaxed text-dim">
            Open <b className="text-fg">DroidDock</b> on your phone → tap{' '}
            <b className="text-fg">Pair with Mac</b> → scan the code below.
          </p>
        </div>

        {!manual ? (
          <>
            {/* ── QR with scanner brackets ── */}
            <div className="mt-6 flex justify-center">
              <div className="relative p-3">
                <Bracket className="left-0 top-0 border-l-2 border-t-2" />
                <Bracket className="right-0 top-0 border-r-2 border-t-2" />
                <Bracket className="bottom-0 left-0 border-b-2 border-l-2" />
                <Bracket className="bottom-0 right-0 border-b-2 border-r-2" />
                {qr ? (
                  <img src={qr} alt="pairing QR" className="rounded-md border-4 border-[#f4efe6]" />
                ) : (
                  <div className="h-[228px] w-[228px] animate-pulse rounded-md bg-panel2" />
                )}
              </div>
            </div>

            {/* ── waiting pill ── */}
            <div className="mt-5 flex justify-center">
              <div className="flex items-center gap-2 rounded-full border border-line bg-ink/60 px-3.5 py-1.5">
                <span className="relative flex h-2 w-2">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber opacity-75" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-amber" />
                </span>
                <span className="font-mono text-[10px] tracking-[0.18em] text-dim">
                  WAITING FOR CONNECTION…
                </span>
              </div>
            </div>

            <button
              onClick={() => setManual(true)}
              className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl border border-line py-2.5 font-display text-[12px] font-semibold tracking-wide text-fg transition-colors hover:border-amber/40 hover:text-amber"
            >
              <Wifi size={14} />
              Pair with IP Address
            </button>
          </>
        ) : (
          /* ── manual IP / token panel ── */
          <div className="mt-6">
            <Field
              label="ADDRESS"
              value={address}
              copied={copied === 'addr'}
              onCopy={() => copy('addr', address)}
            />
            <Field
              label="TOKEN"
              value={status.token}
              accent
              copied={copied === 'token'}
              onCopy={() => copy('token', status.token)}
            />
            {status.ips.length > 1 && (
              <p className="mt-2 font-mono text-[10px] text-dim/60">
                other IPs&nbsp;&nbsp;{status.ips.slice(1).join('  ·  ')}
              </p>
            )}
            <p className="mt-4 text-[11px] leading-relaxed text-dim/70">
              In the phone app, tap <b className="text-dim">Enter IP manually</b> and type the
              address and token above.
            </p>
            <button
              onClick={() => setManual(false)}
              className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl border border-line py-2.5 font-display text-[12px] font-semibold tracking-wide text-fg transition-colors hover:border-amber/40 hover:text-amber"
            >
              Back to QR code
            </button>
          </div>
        )}

        <p className="mt-5 text-center text-[10px] leading-relaxed text-dim/60">
          Phone and Mac must share the same Wi-Fi. If macOS asks about incoming connections — allow.
        </p>
      </div>
    </div>
  )
}

function Bracket({ className }) {
  return (
    <span
      className={`pointer-events-none absolute h-5 w-5 rounded-[3px] border-amber/70 ${className}`}
    />
  )
}

function Field({ label, value, accent, copied, onCopy }) {
  return (
    <div className="mt-2 flex items-center justify-between gap-3 rounded-lg border border-line bg-ink/60 px-3 py-2.5">
      <div className="min-w-0">
        <p className="font-mono text-[9px] tracking-[0.2em] text-dim/70">{label}</p>
        <p
          className={`mt-0.5 truncate font-mono text-[12px] ${accent ? 'text-amber' : 'text-fg'}`}
        >
          {value || '—'}
        </p>
      </div>
      <button
        onClick={onCopy}
        title="Copy"
        className="shrink-0 rounded-md border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
      >
        {copied ? <Check size={13} className="text-ok" /> : <Copy size={13} />}
      </button>
    </div>
  )
}
