import { useEffect, useRef, useState } from 'react'
import { PhoneOff, Volume2, Mic, MicOff, Hash, X } from 'lucide-react'

const DTMF = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
  ['*', '0', '#'],
]

const SUB = {
  '1': '', '2': 'ABC', '3': 'DEF',
  '4': 'GHI', '5': 'JKL', '6': 'MNO',
  '7': 'PQRS', '8': 'TUV', '9': 'WXYZ',
  '*': '', '0': '+', '#': '',
}

export default function CallOverlay({ call, onDismiss, onToast }) {
  const [muted, setMuted] = useState(false)
  const [speaker, setSpeaker] = useState(false)
  const [dialpad, setDialpad] = useState(false)
  const [dtmfInput, setDtmfInput] = useState('')
  const [duration, setDuration] = useState(0)
  const [ending, setEnding] = useState(false)
  const timerRef = useRef(null)

  // Duration timer — starts ticking once ACTIVE
  useEffect(() => {
    if (call.state === 'ACTIVE') {
      setDuration(0)
      timerRef.current = setInterval(() => setDuration((d) => d + 1), 1000)
    } else {
      clearInterval(timerRef.current)
    }
    return () => clearInterval(timerRef.current)
  }, [call.state])

  const fmt = (s) => {
    const m = Math.floor(s / 60)
    const sec = String(s % 60).padStart(2, '0')
    return `${m}:${sec}`
  }

  const handleEnd = async () => {
    setEnding(true)
    await window.droid.callEnd()
    // The call-state polling will emit IDLE which triggers onDismiss
    setTimeout(onDismiss, 1200)
  }

  const handleSpeaker = async () => {
    const r = await window.droid.callSpeaker()
    if (r?.ok) setSpeaker((s) => !s)
    else onToast?.('bad', 'Speaker toggle failed — ensure ADB is connected')
  }

  const handleMute = async () => {
    const r = await window.droid.callMute()
    if (r?.ok) setMuted((m) => !m)
    else onToast?.('bad', 'Mute toggle failed — ensure ADB is connected')
  }

  const handleDtmf = async (digit) => {
    await window.droid.callDtmf(digit)
    setDtmfInput((d) => d + digit)
  }

  const stateLabel = ending
    ? 'Ending…'
    : call.state === 'RINGING'
    ? 'Calling…'
    : call.state === 'ACTIVE'
    ? fmt(duration)
    : 'Connecting…'

  const stateColor =
    call.state === 'ACTIVE' ? 'text-ok' : 'text-amber'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Blurred backdrop */}
      <div className="absolute inset-0 bg-ink/70 backdrop-blur-md" />

      {/* Main overlay card */}
      <div
        className="rise relative flex w-80 flex-col overflow-hidden border border-line bg-panel"
        style={{
          boxShadow: '0 0 0 1px rgba(255,180,84,0.08), 0 32px 64px rgba(0,0,0,0.7)',
        }}
      >
        {/* Pulsing ring behind avatar when ringing */}
        {call.state === 'RINGING' && !ending && (
          <div
            className="ping-soft absolute left-1/2 top-[68px] h-24 w-24 -translate-x-1/2 rounded-full border border-amber/20"
            style={{ background: 'radial-gradient(circle, rgba(255,180,84,0.08) 0%, transparent 70%)' }}
          />
        )}

        {/* Header bar */}
        <div className="flex h-10 items-center justify-between border-b border-line px-4">
          <span className="font-mono text-[10px] tracking-[0.25em] text-dim">CALL IN PROGRESS</span>
          <button
            onClick={onDismiss}
            title="Minimize (call stays active)"
            className="p-1 text-dim/50 transition-colors hover:text-dim"
          >
            <X size={13} />
          </button>
        </div>

        {/* Avatar + name + state */}
        <div className="flex flex-col items-center py-8">
          {/* Avatar */}
          <div className="relative flex h-20 w-20 items-center justify-center">
            {call.state === 'ACTIVE' && (
              <div className="absolute inset-0 rounded-full bg-ok/10" />
            )}
            <div
              className={`flex h-20 w-20 items-center justify-center rounded-full border-2 font-display text-2xl font-bold ${
                call.state === 'ACTIVE'
                  ? 'border-ok/40 bg-ok/10 text-ok'
                  : 'border-amber/40 bg-amber/10 text-amber'
              }`}
            >
              {(call.name || call.number || '?').slice(0, 2).toUpperCase()}
            </div>
            {call.state === 'ACTIVE' && (
              <span className="absolute -bottom-1 -right-1 h-4 w-4 rounded-full border-2 border-panel bg-ok" />
            )}
          </div>

          {/* Name */}
          <p className="mt-4 font-display text-[18px] font-semibold tracking-wide">
            {call.name || call.number}
          </p>
          {call.name && call.name !== call.number && (
            <p className="mt-0.5 font-mono text-[11px] text-dim">{call.number}</p>
          )}

          {/* Status / timer */}
          <p className={`mt-2 font-mono text-[13px] ${stateColor}`}>
            {stateLabel}
          </p>

          {/* DTMF input display */}
          {dtmfInput && (
            <p className="mt-1 font-mono text-[12px] tracking-[0.3em] text-dim">
              {dtmfInput}
            </p>
          )}
        </div>

        {/* DTMF Keypad (toggleable) */}
        {dialpad && (
          <div className="border-t border-line px-6 pb-4 pt-3">
            <div className="grid grid-cols-3 gap-2">
              {DTMF.map((row) =>
                row.map((digit) => (
                  <button
                    key={digit}
                    onClick={() => handleDtmf(digit)}
                    className="flex flex-col items-center justify-center border border-line/70 py-2.5 font-display transition-colors hover:border-amber/30 hover:bg-amber/5 active:bg-amber/10"
                  >
                    <span className="text-[16px] font-semibold leading-none">{digit}</span>
                    {SUB[digit] && (
                      <span className="mt-0.5 font-mono text-[7px] tracking-widest text-dim/60">
                        {SUB[digit]}
                      </span>
                    )}
                  </button>
                ))
              )}
            </div>
          </div>
        )}

        {/* Controls row */}
        <div className="border-t border-line px-6 pb-6 pt-4">
          <div className="flex items-center justify-center gap-4">
            {/* Mute */}
            <CtrlBtn
              icon={muted ? MicOff : Mic}
              label={muted ? 'Unmute' : 'Mute'}
              active={muted}
              activeColor="bad"
              onClick={handleMute}
            />

            {/* End call — big red centre button */}
            <button
              onClick={handleEnd}
              disabled={ending}
              className="flex h-16 w-16 items-center justify-center rounded-full bg-bad/90 transition-all hover:scale-105 hover:bg-bad active:scale-95 disabled:opacity-50"
              title="End call"
            >
              <PhoneOff size={24} strokeWidth={2} className="text-white" />
            </button>

            {/* Speaker */}
            <CtrlBtn
              icon={Volume2}
              label="Speaker"
              active={speaker}
              activeColor="ok"
              onClick={handleSpeaker}
            />
          </div>

          {/* Keypad toggle */}
          <div className="mt-4 flex justify-center">
            <button
              onClick={() => setDialpad((d) => !d)}
              className={`flex items-center gap-1.5 border px-3 py-1.5 font-mono text-[10px] tracking-widest transition-colors ${
                dialpad
                  ? 'border-amber/40 text-amber'
                  : 'border-line text-dim hover:border-line/80 hover:text-fg'
              }`}
            >
              <Hash size={11} />
              KEYPAD
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function CtrlBtn({ icon: Icon, label, active, activeColor, onClick }) {
  const colors = {
    bad: 'border-bad/40 bg-bad/10 text-bad',
    ok:  'border-ok/40 bg-ok/10 text-ok',
  }
  return (
    <button
      onClick={onClick}
      title={label}
      className={`flex h-12 w-12 flex-col items-center justify-center rounded-full border transition-all hover:scale-105 active:scale-95 ${
        active ? colors[activeColor] : 'border-line bg-panel2 text-dim hover:border-line/60 hover:text-fg'
      }`}
    >
      <Icon size={18} strokeWidth={1.75} />
      <span className="mt-0.5 font-mono text-[8px] tracking-wider">{label}</span>
    </button>
  )
}
