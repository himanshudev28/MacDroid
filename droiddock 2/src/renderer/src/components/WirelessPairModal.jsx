import { useEffect, useRef, useState } from 'react'
import { X, Wifi, QrCode, KeyRound, CheckCircle2, Loader2, AlertTriangle } from 'lucide-react'
import QRCode from 'qrcode'

const HOST_RE = /^(\d{1,3}\.){3}\d{1,3}:\d{2,5}$/
const CODE_RE = /^\d{6}$/
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

// Alphanumeric only — the WIFI: QR format uses ; : , as delimiters.
function randStr(n) {
  const bytes = new Uint8Array(n)
  crypto.getRandomValues(bytes)
  return [...bytes].map((b) => ALPHABET[b % ALPHABET.length]).join('')
}

export default function WirelessPairModal({ onClose, onPaired, onToast }) {
  const [tab, setTab] = useState('qr')

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-ink/80 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="rise w-[440px] border border-line bg-panel p-7"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-2.5">
            <Wifi size={18} className="text-amber" strokeWidth={1.75} />
            <div>
              <p className="font-display text-sm font-semibold tracking-[0.22em]">
                CONNECT ADB (QR)
              </p>
              <p className="mt-1 text-[12px] text-dim">Cable-free wireless ADB · Android 11+</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1 text-dim transition-colors hover:text-amber">
            <X size={16} />
          </button>
        </div>

        <div className="mt-5 flex gap-1 border-b border-line">
          <Tab id="qr" tab={tab} setTab={setTab} icon={QrCode} label="Pair via QR" />
          <Tab id="code" tab={tab} setTab={setTab} icon={KeyRound} label="Enter code" />
        </div>

        {tab === 'qr' ? (
          <QrTab onPaired={onPaired} onClose={onClose} />
        ) : (
          <CodeTab onPaired={onPaired} onClose={onClose} onToast={onToast} />
        )}
      </div>
    </div>
  )
}

function Tab({ id, tab, setTab, icon: Icon, label }) {
  const active = tab === id
  return (
    <button
      onClick={() => setTab(id)}
      className={`flex items-center gap-1.5 border-b-2 px-3 py-2 font-display text-[11px] font-semibold tracking-[0.15em] transition-colors ${
        active ? 'border-amber text-amber' : 'border-transparent text-dim hover:text-fg'
      }`}
    >
      <Icon size={13} />
      {label}
    </button>
  )
}

function QrTab({ onPaired, onClose }) {
  const [qr, setQr] = useState(null)
  const [status, setStatus] = useState({ state: 'waiting', text: 'Generating code…' })
  const offRef = useRef(null)

  useEffect(() => {
    const serviceName = 'droiddock-' + randStr(6)
    const password = randStr(10)
    const payload = `WIFI:T:ADB;S:${serviceName};P:${password};;`

    let alive = true
    QRCode.toDataURL(payload, {
      margin: 1,
      width: 220,
      color: { dark: '#0b0d10', light: '#f4efe6' }
    }).then((url) => alive && setQr(url))

    setStatus({ state: 'waiting', text: 'Waiting for scan…' })
    offRef.current = window.droid.onQrStatus((s) => {
      setStatus(s)
      if (s.state === 'connected') {
        onPaired()
        setTimeout(() => alive && onClose(), 1100)
      }
    })
    window.droid.qrPairStart(serviceName, password)

    return () => {
      alive = false
      offRef.current?.()
      window.droid.qrPairCancel()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="pt-5">
      <ol className="space-y-1.5 font-mono text-[11px] leading-relaxed text-dim">
        <li>
          1. Phone → <b className="text-fg">Settings → Developer options → Wireless debugging</b>
        </li>
        <li>
          2. Tap <b className="text-fg">Pair device with QR code</b> → scan this
        </li>
        <li>
          3. <b className="text-fg">Stay on the Wireless debugging screen</b> until it connects
        </li>
      </ol>

      <div className="mt-4 flex justify-center">
        {qr ? (
          <img src={qr} alt="ADB pairing QR" className="border-4 border-[#f4efe6]" />
        ) : (
          <div className="h-[228px] w-[228px] animate-pulse bg-panel2" />
        )}
      </div>

      <StatusLine status={status} />
    </div>
  )
}

function StatusLine({ status }) {
  const map = {
    waiting: { Icon: Loader2, cls: 'text-amber', spin: true },
    connecting: { Icon: Loader2, cls: 'text-amber', spin: true },
    connected: { Icon: CheckCircle2, cls: 'text-ok', spin: false },
    error: { Icon: AlertTriangle, cls: 'text-bad', spin: false }
  }
  const { Icon, cls, spin } = map[status.state] || map.waiting
  return (
    <div className="mt-4 flex items-center justify-center gap-2 border-t border-line pt-3">
      <Icon size={13} className={`${cls} ${spin ? 'spinner' : ''}`} />
      <span className={`font-mono text-[11px] ${cls}`}>
        {status.text}
        {status.addr ? ` — ${status.addr}` : ''}
      </span>
    </div>
  )
}

function CodeTab({ onPaired, onClose, onToast }) {
  const [hostPort, setHostPort] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)

  const hostOk = HOST_RE.test(hostPort.trim())
  const codeOk = CODE_RE.test(code.trim())
  const valid = hostOk && codeOk

  const submit = async () => {
    if (!valid || busy) return
    setBusy(true)
    const res = await window.droid.pairWireless(hostPort.trim(), code.trim())
    setBusy(false)
    if (!res.ok) return onToast('bad', res.error)
    onPaired()
    onToast(
      'ok',
      res.data.addr
        ? `Paired & connected over Wi-Fi — ${res.data.addr}. Cable not needed.`
        : 'Paired. Reconnecting over Wi-Fi…'
    )
    onClose()
  }

  return (
    <div className="pt-5">
      <ol className="space-y-1.5 font-mono text-[11px] leading-relaxed text-dim">
        <li>
          1. Phone → <b className="text-fg">Wireless debugging → Pair device with pairing code</b>
        </li>
        <li>2. Type the IP:PORT and 6-digit code it shows</li>
      </ol>

      <label className="mt-4 block font-mono text-[10px] tracking-[0.2em] text-dim/70">
        PAIRING IP:PORT
      </label>
      <input
        value={hostPort}
        onChange={(e) => setHostPort(e.target.value)}
        placeholder="192.168.0.100:41234"
        autoFocus
        className={`mt-1.5 w-full border bg-ink px-3 py-2 font-mono text-sm text-fg outline-none transition-colors ${
          hostPort && !hostOk ? 'border-bad' : 'border-line focus:border-amber/50'
        }`}
      />

      <label className="mt-4 block font-mono text-[10px] tracking-[0.2em] text-dim/70">
        PAIRING CODE
      </label>
      <input
        value={code}
        onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
        placeholder="123456"
        inputMode="numeric"
        className={`mt-1.5 w-full border bg-ink px-3 py-2 font-mono text-sm tracking-[0.4em] text-fg outline-none transition-colors ${
          code && !codeOk ? 'border-bad' : 'border-line focus:border-amber/50'
        }`}
      />

      <p className="mt-3 font-mono text-[10px] leading-relaxed text-dim/60">
        ⚠ Use the port from the <b className="text-dim">pairing dialog</b> — it is different from
        the main port shown on the Wireless-debugging screen.
      </p>

      <button
        onClick={submit}
        disabled={!valid || busy}
        className="mt-5 w-full border border-amber/40 bg-amber/10 py-2.5 font-display text-[11px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/20 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {busy ? 'PAIRING…' : 'PAIR & CONNECT'}
      </button>
    </div>
  )
}
