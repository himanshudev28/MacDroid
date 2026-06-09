import { useState } from 'react'
import { X, Copy, Check, Terminal, Download, Loader } from 'lucide-react'

const TOOLS = [
  {
    key: 'adb',
    label: 'Android Platform Tools',
    sub: 'adb — installed automatically on first launch',
    cmd: 'brew install --cask android-platform-tools'
  },
  {
    key: 'scrcpy',
    label: 'scrcpy',
    sub: 'screen mirroring & phone camera',
    cmd: 'brew install scrcpy'
  }
]

function CmdRow({ tool, installed, brew, onInstall }) {
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(tool.cmd)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable — user can still select the text */
    }
  }

  const install = async () => {
    setBusy(true)
    setErr(null)
    const res = await onInstall()
    setBusy(false)
    if (!res?.ok) setErr(res?.error || 'Install failed')
  }

  return (
    <div className="border border-line bg-ink/40 p-3">
      <div className="min-w-0">
        <p className="flex items-center gap-2 text-[13px] font-semibold text-fg">
          {tool.label}
          {installed ? (
            <span className="font-mono text-[9px] tracking-wider text-ok">✓ INSTALLED</span>
          ) : (
            <span className="font-mono text-[9px] tracking-wider text-bad">MISSING</span>
          )}
        </p>
        <p className="mt-0.5 text-[11px] text-dim">{tool.sub}</p>
      </div>

      {!installed && (
        <>
          {onInstall && brew && (
            <button
              onClick={install}
              disabled={busy}
              className="mt-2.5 flex w-full items-center justify-center gap-2 border border-amber/50 bg-amber/10 py-2 font-display text-[11px] font-semibold tracking-[0.15em] text-amber transition-colors hover:bg-amber/20 disabled:opacity-60"
            >
              {busy ? <Loader size={13} className="spinner" /> : <Download size={13} />}
              {busy ? 'INSTALLING — this can take a minute…' : 'INSTALL WITH HOMEBREW'}
            </button>
          )}
          {err && <p className="mt-2 text-[11px] text-bad">{err}</p>}

          <div className="mt-2 flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate border border-line bg-ink px-2.5 py-1.5 font-mono text-[11px] text-amber">
              {tool.cmd}
            </code>
            <button
              onClick={copy}
              title="Copy command"
              className="flex shrink-0 items-center gap-1 border border-amber/40 px-2.5 py-1.5 font-mono text-[10px] tracking-wider text-amber transition-colors hover:bg-amber/10"
            >
              {copied ? <Check size={12} /> : <Copy size={12} />}
              {copied ? 'COPIED' : 'COPY'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}

export default function SetupModal({ tools, reason, onClose, onInstallScrcpy }) {
  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-ink/80 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="rise w-[460px] border border-line bg-panel p-7"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-2.5">
            <Terminal size={18} className="text-amber" />
            <div>
              <p className="font-display text-sm font-semibold tracking-[0.22em]">SETUP REQUIRED</p>
              <p className="mt-1 text-[12px] text-dim">
                {reason || 'These optional tools power screen mirroring & camera.'}
              </p>
            </div>
          </div>
          <button onClick={onClose} className="p-1 text-dim transition-colors hover:text-amber">
            <X size={16} />
          </button>
        </div>

        <div className="mt-5 space-y-2.5">
          {TOOLS.map((t) => (
            <CmdRow
              key={t.key}
              tool={t}
              installed={!!tools?.[t.key]}
              brew={!!tools?.brew}
              onInstall={t.key === 'scrcpy' ? onInstallScrcpy : null}
            />
          ))}
        </div>

        <p className="mt-5 border-t border-line pt-4 text-[11px] leading-relaxed text-dim">
          Clipboard, notifications, messages, files and photos work over Wi-Fi with{' '}
          <b className="text-fg">none</b> of these — they're only for screen mirroring. No
          Homebrew? Get it at <span className="text-amber">brew.sh</span>.
        </p>
      </div>
    </div>
  )
}
