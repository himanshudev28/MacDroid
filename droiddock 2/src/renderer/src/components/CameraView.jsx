export default function CameraView({ linked, connected, scrcpy, busy, onCameraWifi, onCameraAdb, onToast }) {
  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-xl space-y-4">

        <div className="mb-2">
          <span className="font-mono text-[10px] tracking-[0.12em] text-dim/60 uppercase">Camera</span>
        </div>

        <CameraSection
          title="Wi-Fi Camera"
          subtitle="Streams phone camera over local Wi-Fi — no USB or Developer Options required."
          badge="Wi-Fi"
          badgeColor="ok"
          requirement={linked ? null : 'Phone app link required'}
          requirementHint="Pair the DroidDock phone app to use Wi-Fi camera."
          iconPath="M15 10l4.553-2.069A1 1 0 0121 8.882v6.236a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"
          buttonLabel="Start Camera (Wi-Fi)"
          buttonDisabled={!linked}
          buttonBusy={false}
          onClick={onCameraWifi}
        />

        <CameraSection
          title="ADB Camera"
          subtitle="Uses scrcpy to stream phone camera over USB or ADB. Works without the phone app."
          badge="ADB"
          badgeColor="amber"
          requirement={!connected ? 'ADB device required' : !scrcpy ? 'scrcpy not installed' : null}
          requirementHint={
            !connected
              ? 'Connect your phone via USB with ADB enabled.'
              : 'Install scrcpy to use ADB camera streaming.'
          }
          iconPath="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z M15 13a3 3 0 11-6 0 3 3 0 016 0z"
          buttonLabel="Start Camera (ADB)"
          buttonDisabled={!connected || !scrcpy}
          buttonBusy={!!busy?.camera}
          onClick={onCameraAdb}
        />

      </div>
    </div>
  )
}

function CameraSection({
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
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3.5">
          <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border ${buttonDisabled ? 'border-line bg-panel3' : 'border-amber/20 bg-amber/8'}`}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className={`h-5 w-5 ${buttonDisabled ? 'text-dim/40' : 'text-amber'}`} aria-hidden="true">
              <path d={iconPath} />
            </svg>
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <p className="text-[13px] font-semibold text-fg/90">{title}</p>
              <span className={`rounded border px-1.5 py-0.5 font-mono text-[8px] tracking-[0.04em] ${badgeStyles[badgeColor]}`}>
                {badge}
              </span>
            </div>
            <p className="mt-1 text-[11.5px] leading-relaxed text-dim/70">{subtitle}</p>
          </div>
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
              Starting…
            </>
          ) : buttonLabel}
        </button>
      )}
    </div>
  )
}
