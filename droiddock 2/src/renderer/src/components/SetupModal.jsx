import { useState } from 'react'
import { X, Copy, Check, Terminal } from 'lucide-react'

const TOOLS = [
  {
    key: 'adb',
    label: 'Android Platform Tools',
    sub: 'adb — device control, files, screenshots, mirroring',
    cmd: 'brew install --cask android-platform-tools'
  },
  {
    key: 'scrcpy',
    label: 'scrcpy',
    sub: 'screen mirroring & phone camera',
    cmd: 'brew install scrcpy'
  }
]

function CmdRow({ tool, installed }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(tool.cmd)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable — user can still select the text */
    }
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
        <div className="mt-2.5 flex items-center gap-2">
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
      )}
    </div>
  )
}

export default function SetupModal({ tools, reason, onClose }) {
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
                {reason || 'DroidDock uses these free command-line tools.'}
              </p>
            </div>
          </div>
          <button onClick={onClose} className="p-1 text-dim transition-colors hover:text-amber">
            <X size={16} />
          </button>
        </div>

        <div className="mt-5 space-y-2.5">
          {TOOLS.map((t) => (
            <CmdRow key={t.key} tool={t} installed={!!tools?.[t.key]} />
          ))}
        </div>

        <p className="mt-5 border-t border-line pt-4 text-[11px] leading-relaxed text-dim">
          No Homebrew yet? Get it at <span className="text-amber">brew.sh</span>, run the
          command(s) above in Terminal, then restart DroidDock.
        </p>
      </div>
    </div>
  )
}
