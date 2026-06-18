import { useEffect, useState } from 'react'

export default function MirrorView({ linked, connected, scrcpy, busy, onMirrorAdb, onToast }) {
  const [active, setActive] = useState(null)
  const [wifiBusy, setWifiBusy] = useState(false)

  useEffect(() => {
    const offStarted = window.droid.onMirrorStarted((m) => {
      setActive(m.source || 'screen')
      setWifiBusy(false)
    })
    const offStopped = window.droid.onMirrorStopped(() => setActive(null))
    const offError = window.droid.onMirrorError((m) => {
      onToast('bad', m.error || 'Mirror failed')
      setActive(null)
      setWifiBusy(false)
    })
    return () => {
      offStarted()
      offStopped()
      offError()
    }
  }, [onToast])

  const openWifi = async () => {
    setWifiBusy(true)
    const r = await window.droid.mirrorPopout('screen')
    if (!r.ok) {
      setWifiBusy(false)
      onToast('bad', r.error)
    } else {
      onToast('info', 'Approve screen capture on your phone…')
    }
  }

  if (active) {
    return (
      <EmptyLayout
        iconPath="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
        title="MIRRORING IN A WINDOW"
        body="Your phone is streaming in its own pop-out window. Move it, resize it, or pin it on top."
      >
        <div className="mt-5 flex items-center justify-center gap-3">
          <button
            onClick={() => window.droid.mirrorFocus()}
            className="flex items-center gap-2 rounded-xl border border-amber/40 bg-amber/8 px-4 py-2 font-mono text-[11px] font-semibold tracking-[0.1em] text-amber transition-colors hover:bg-amber/16"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5" aria-hidden="true">
              <path d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
            </svg>
            BRING TO FRONT
          </button>
          <button
            onClick={() => window.droid.mirrorStop()}
            className="flex items-center gap-2 rounded-xl border border-line px-4 py-2 font-mono text-[11px] font-semibold tracking-[0.1em] text-dim transition-colors hover:border-bad/40 hover:text-bad"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5" aria-hidden="true">
              <rect x="3" y="3" width="18" height="18" rx="2" />
            </svg>
            STOP
          </button>
        </div>
      </EmptyLayout>
    )
  }

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-xl space-y-4">

        <div className="mb-2">
          <span className="font-mono text-[10px] tracking-[0.12em] text-dim/60 uppercase">Screen Mirror</span>
        </div>

        <MirrorSection
          title="Wi-Fi Mirror"
          subtitle="Opens a phone-shaped pop-out window over Wi-Fi. No ADB, scrcpy, or Developer Options needed."
          badge="Wi-Fi"
          badgeColor="ok"
          requirement={linked ? null : 'Phone app link required'}
          requirementHint="Pair the DroidDock phone app to use Wi-Fi mirroring."
          iconPath="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
          buttonLabel={wifiBusy ? 'Waiting…' : 'Mirror via Wi-Fi'}
          buttonDisabled={!linked}
          buttonBusy={wifiBusy}
          onClick={openWifi}
        />

        <MirrorSection
          title="ADB Mirror"
          subtitle="Uses scrcpy to mirror over USB or wireless ADB. Full-quality, low latency — requires Developer Options."
          badge="ADB"
          badgeColor="amber"
          requirement={!connected ? 'ADB device required' : !scrcpy ? 'scrcpy not installed' : null}
          requirementHint={
            !connected
              ? 'Connect your phone via USB with ADB enabled.'
              : 'Install scrcpy to enable ADB mirroring.'
          }
          iconPath="M9 3H5a2 2 0 00-2 2v4m6-6h10a2 2 0 012 2v4M9 3v18m0 0h10a2 2 0 002-2V9M9 21H5a2 2 0 01-2-2V9m0 0h18"
          buttonLabel="Mirror via ADB"
          buttonDisabled={!connected || !scrcpy}
          buttonBusy={!!busy?.mirror}
          onClick={onMirrorAdb}
        />

      </div>
    </div>
  )
}

function MirrorSection({
  title,
  subtitle,
  badge,
  badgeColor,
  requirement,
  requirementHint,
  iconPath,
  buttonLabel,
  buttonDisabled,
  buttonBusy,
  onClick,
}) {
  const badgeStyles = {
    ok: 'border-ok/25 bg-ok/10 text-ok',
    amber: 'border-amber/25 bg-amber/10 text-amber',
  }

  return (
    <div className={`rounded-2xl border bg-panel2 p-5 luminous-sm transition-colors ${buttonDisabled ? 'border-line opacity-70' : 'border-line hover:border-amber/20'}`}>
      <div className="flex items-start gap-3.5">
        <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border ${buttonDisabled ? 'border-line bg-panel3' : 'border-amber/20 bg-amber/8'}`}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className={`h-5 w-5 ${buttonDisabled ? 'text-dim/40' : 'text-amber'}`} aria-hidden="true">
            <path d={iconPath} />
          </svg>
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="text-[13px] font-semibold text-fg/90">{title}</p>
            <span className={`rounded border px-1.5 py-0.5 font-mono text-[8px] tracking-[0.04em] ${badgeStyles[badgeColor]}`}>
              {badge}
            </span>
          </div>
          <p className="mt-1 text-[11.5px] leading-relaxed text-dim/70">{subtitle}</p>
        </div>
      </div>

      {requirement ? (
        <div className="mt-4 rounded-xl border border-line bg-panel3 px-3.5 py-2.5">
          <p className="text-[11px] font-medium text-dim/70">{requirement}</p>
          <p className="mt-0.5 text-[10.5px] text-dim/50">{requirementHint}</p>
        </div>
      ) : (
        <button
          onClick={onClick}
          disabled={buttonDisabled || buttonBusy}
          className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-amber/35 bg-amber/10 px-4 py-2.5 font-mono text-[11px] font-semibold tracking-[0.04em] text-amber transition-all hover:bg-amber/18 hover:border-amber/50 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {buttonBusy ? (
            <>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} className="h-3.5 w-3.5 spinner" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
              Waiting…
            </>
          ) : buttonLabel}
        </button>
      )}
    </div>
  )
}

function EmptyLayout({ iconPath, title, body, children }) {
  return (
    <div className="flex h-full flex-1 items-center justify-center p-8">
      <div className="max-w-sm rounded-2xl border border-line bg-panel2 p-8 text-center luminous-sm">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-amber/20 bg-amber/8">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-amber" aria-hidden="true">
            <path d={iconPath} />
          </svg>
        </div>
        <p className="font-mono text-[11px] font-semibold tracking-[0.2em] text-fg/80">{title}</p>
        <p className="mt-2 text-[12px] leading-relaxed text-dim/70">{body}</p>
        {children}
      </div>
    </div>
  )
}
