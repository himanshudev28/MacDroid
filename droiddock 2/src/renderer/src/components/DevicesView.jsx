import { useEffect, useRef, useState } from 'react'

export default function DevicesView({
  connected,
  info,
  appInfo,
  wifi,
  tools,
  busy,
  paired,
  onPair,
  onWireless,
  onPairWireless,
  onUnpair,
  onReconnect,
  onScreenshot,
  onToast,
}) {
  const linked = !!wifi?.connected

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-xl space-y-5">

        <div className="flex items-center justify-between">
          <span className="font-mono text-[10px] tracking-[0.12em] text-dim/60 uppercase">Devices</span>
          <button
            onClick={onReconnect}
            disabled={busy?.reconnect}
            className="flex items-center gap-1.5 rounded-lg border border-line px-2.5 py-1.5 font-mono text-[9.5px] text-dim/70 transition-all hover:border-amber/30 hover:text-amber disabled:opacity-40"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className={`h-3 w-3 ${busy?.reconnect ? 'spinner' : ''}`} aria-hidden="true">
              <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            Reconnect
          </button>
        </div>

        {connected ? (
          <AdbDeviceCard device={connected} info={info} appInfo={appInfo} />
        ) : (
          <EmptyDeviceCard onPair={onPair} />
        )}

        <AppLinkCard wifi={wifi} onPair={onPair} />

        <ToolsCard tools={tools} onSetup={onPair} />

        {connected && (
          <VolumeCard deviceSerial={connected.serial} />
        )}

        <ActionsCard
          connected={connected}
          tools={tools}
          busy={busy}
          paired={paired}
          onWireless={onWireless}
          onPairWireless={onPairWireless}
          onUnpair={onUnpair}
          onScreenshot={onScreenshot}
        />

      </div>
    </div>
  )
}

function AdbDeviceCard({ device, info, appInfo }) {
  const model = appInfo?.model || info?.model || device?.model || device?.serial
  const android = info?.android || appInfo?.android
  const battery = appInfo?.battery ?? info?.battery
  const charging = appInfo?.charging ?? info?.charging
  const isWifi = device?.transport === 'wifi'

  return (
    <div className="rounded-2xl border border-line bg-panel2 p-5 luminous-sm">
      <div className="mb-3 flex items-center justify-between">
        <span className="font-mono text-[9.5px] tracking-[0.10em] text-dim/60 uppercase">ADB Device</span>
        <span className={`rounded-lg border px-2 py-0.5 font-mono text-[8px] tracking-[0.05em] ${isWifi ? 'border-ok/25 bg-ok/10 text-ok' : 'border-amber/25 bg-amber/10 text-amber'}`}>
          {isWifi ? 'Wi-Fi ADB' : 'USB'}
        </span>
      </div>
      <div className="flex items-center gap-4">
        <div className="relative shrink-0">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl border border-amber/20 bg-amber/8">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-amber" aria-hidden="true">
              <path d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
            </svg>
          </div>
          <span className="absolute -right-0.5 -top-0.5 h-3 w-3 rounded-full border-2 border-panel2 bg-amber led" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[14px] font-semibold text-fg/95">{model}</p>
          {android && <p className="mt-0.5 font-mono text-[10px] text-dim/60">Android {android}</p>}
          <p className="mt-0.5 truncate font-mono text-[9px] text-dim/40">{device?.serial}</p>
        </div>
      </div>
      {battery != null && (
        <div className="mt-4">
          <div className="mb-1.5 flex items-center justify-between">
            <span className="font-mono text-[9px] text-dim/60">Battery</span>
            <span className="flex items-center gap-1 font-mono text-[10px] text-fg/70">
              {charging && (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} className="h-2.5 w-2.5 text-amber" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              )}
              {battery}%
            </span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-panel3">
            <div
              className={`h-full rounded-full transition-all duration-700 ${battery > 20 ? 'bg-ok' : 'bg-bad'}`}
              style={{ width: `${battery}%` }}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function EmptyDeviceCard({ onPair }) {
  return (
    <button
      onClick={onPair}
      className="group w-full rounded-2xl border border-dashed border-line bg-panel2/50 p-5 text-left transition-all hover:border-amber/30 hover:bg-panel2"
    >
      <div className="flex items-center gap-4">
        <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-dashed border-line">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-dim/40 transition-colors group-hover:text-amber/60" aria-hidden="true">
            <path d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
          </svg>
        </div>
        <div>
          <p className="text-[13px] font-medium text-dim/70 transition-colors group-hover:text-fg/80">No ADB device</p>
          <p className="mt-0.5 font-mono text-[10px] text-dim/40">Connect via USB or pair wirelessly →</p>
        </div>
      </div>
    </button>
  )
}

function AppLinkCard({ wifi, onPair }) {
  const linked = !!wifi?.connected
  return (
    <div className="rounded-2xl border border-line bg-panel2 p-5 luminous-sm">
      <div className="mb-3 flex items-center justify-between">
        <span className="font-mono text-[9.5px] tracking-[0.10em] text-dim/60 uppercase">App Link</span>
        {linked ? (
          <span className="rounded-lg border border-ok/25 bg-ok/10 px-2 py-0.5 font-mono text-[8px] text-ok">Connected</span>
        ) : (
          <span className="rounded-lg border border-line px-2 py-0.5 font-mono text-[8px] text-dim/50">Not linked</span>
        )}
      </div>
      <div className="flex items-center gap-4">
        <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border ${linked ? 'border-ok/20 bg-ok/8' : 'border-line bg-panel3'}`}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className={`h-5 w-5 ${linked ? 'text-ok' : 'text-dim/40'}`} aria-hidden="true">
            <path d="M8.111 16.404a5.5 5.5 0 017.778 0M12 20h.01m-7.08-7.071c3.904-3.905 10.236-3.905 14.141 0M1.394 9.393c5.857-5.857 15.355-5.857 21.213 0" />
          </svg>
        </div>
        <div className="min-w-0 flex-1">
          {linked ? (
            <>
              <p className="text-[13px] font-medium text-fg/90">{wifi.phoneName || 'Phone'}</p>
              <p className="mt-0.5 font-mono text-[10px] text-dim/55">port {wifi.port}</p>
            </>
          ) : (
            <>
              <p className="text-[13px] font-medium text-dim/60">Phone app not paired</p>
              <p className="mt-0.5 font-mono text-[10px] text-dim/40">Install DroidDock on your phone</p>
            </>
          )}
        </div>
        {!linked && (
          <button
            onClick={onPair}
            className="shrink-0 rounded-lg border border-amber/35 bg-amber/10 px-3 py-1.5 font-mono text-[9.5px] font-medium text-amber transition-all hover:bg-amber/18"
          >
            Pair
          </button>
        )}
      </div>
    </div>
  )
}

function ToolsCard({ tools, onSetup }) {
  if (!tools) return null
  return (
    <div className="rounded-2xl border border-line bg-panel2 p-5 luminous-sm">
      <div className="mb-3">
        <span className="font-mono text-[9.5px] tracking-[0.10em] text-dim/60 uppercase">Tools</span>
      </div>
      <div className="flex gap-3">
        {[
          { label: 'ADB', on: tools.adb, hint: 'Android Debug Bridge' },
          { label: 'scrcpy', on: tools.scrcpy, hint: 'Screen mirroring engine' },
        ].map(({ label, on, hint }) => (
          <button
            key={label}
            onClick={onSetup}
            className={`flex flex-1 items-center gap-2.5 rounded-xl border px-3 py-2.5 transition-colors ${on ? 'border-ok/20 bg-ok/5 hover:bg-ok/8' : 'border-bad/20 bg-bad/5 hover:bg-bad/8'}`}
          >
            <span className={`h-2 w-2 shrink-0 rounded-full ${on ? 'bg-ok' : 'bg-bad'}`} />
            <div className="min-w-0 text-left">
              <p className={`text-[12px] font-semibold ${on ? 'text-ok' : 'text-bad'}`}>{label}</p>
              <p className="font-mono text-[9px] text-dim/50">{hint}</p>
            </div>
          </button>
        ))}
      </div>
    </div>
  )
}

function VolumeCard({ deviceSerial }) {
  const [vol, setVol] = useState(null)
  const [err, setErr] = useState(false)
  const debounceRef = useRef(null)
  const prevLevelRef = useRef(null)

  useEffect(() => {
    if (!deviceSerial) { setVol(null); return }
    window.droid.volumeGet().then((r) => {
      if (r.ok) { setVol(r.data); prevLevelRef.current = r.data.level }
      else setErr(true)
    })
  }, [deviceSerial])

  if (!deviceSerial || err || !vol) return null

  const pct = Math.round((vol.level / vol.max) * 100)

  const handleChange = async (e) => {
    const level = Number(e.target.value)
    const prevLevel = prevLevelRef.current ?? vol.level
    setVol((v) => ({ ...v, level }))
    clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(async () => {
      const r = await window.droid.volumeSet(level, prevLevel)
      if (r.ok) prevLevelRef.current = level
    }, 80)
  }

  return (
    <div className="rounded-2xl border border-line bg-panel2 p-5 luminous-sm">
      <div className="mb-3 flex items-center justify-between">
        <span className="font-mono text-[9.5px] tracking-[0.10em] text-dim/60 uppercase">Volume</span>
        <span className="font-mono text-[10px] text-dim/70">{pct}%</span>
      </div>
      <div className="flex items-center gap-3">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5 shrink-0 text-dim/50" aria-hidden="true">
          {pct === 0
            ? <path d="M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z M17 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2" />
            : pct < 50
              ? <path d="M15.536 8.464a5 5 0 010 7.072M12 6v12m-3-3H4a1 1 0 01-1-1v-4a1 1 0 011-1h5l4-4v12l-4-4z" />
              : <path d="M15.536 8.464a5 5 0 010 7.072M18.364 5.636a9 9 0 010 12.728M12 6v12m-3-3H4a1 1 0 01-1-1v-4a1 1 0 011-1h5l4-4v12l-4-4z" />
          }
        </svg>
        <input
          type="range"
          min={0}
          max={vol.max}
          value={vol.level}
          onChange={handleChange}
          className="vol-slider min-w-0 flex-1"
        />
      </div>
    </div>
  )
}

function ActionsCard({ connected, tools, busy, paired, onWireless, onPairWireless, onUnpair, onScreenshot }) {
  const actions = [
    connected && connected.transport === 'usb' && {
      label: 'Go Wireless',
      hint: 'Switch to Wi-Fi ADB',
      spinning: busy?.wireless,
      onClick: onWireless,
      iconPath: 'M8.111 16.404a5.5 5.5 0 017.778 0M12 20h.01m-7.08-7.071c3.904-3.905 10.236-3.905 14.141 0M1.394 9.393c5.857-5.857 15.355-5.857 21.213 0',
    },
    {
      label: 'Connect via QR',
      hint: 'Wireless pair (A11+)',
      spinning: false,
      onClick: onPairWireless,
      iconPath: 'M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 4h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z',
    },
    connected && {
      label: 'Screenshot',
      hint: 'Save to Downloads',
      spinning: busy?.shot,
      onClick: onScreenshot,
      iconPath: 'M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z M15 13a3 3 0 11-6 0 3 3 0 016 0z',
    },
    paired && {
      label: 'Unpair device',
      hint: 'Forget this phone',
      spinning: busy?.unpair,
      onClick: onUnpair,
      danger: true,
      iconPath: 'M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21',
    },
  ].filter(Boolean)

  if (actions.length === 0) return null

  return (
    <div className="overflow-hidden rounded-2xl border border-line bg-panel2 luminous-sm">
      {actions.map(({ label, hint, spinning, onClick, iconPath, danger }, i) => (
        <button
          key={label}
          onClick={onClick}
          disabled={spinning}
          className={`group flex w-full items-center gap-3 border-b border-line/60 px-4 py-3 text-left transition-all last:border-b-0 hover:bg-panel3 disabled:opacity-40 ${danger ? 'hover:bg-bad/5' : ''}`}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className={`h-4 w-4 shrink-0 transition-colors ${spinning ? 'spinner text-amber' : danger ? 'text-dim/50 group-hover:text-bad/70' : 'text-dim/50 group-hover:text-amber'}`} aria-hidden="true">
            <path d={iconPath} />
          </svg>
          <span className={`flex-1 text-[12.5px] font-medium transition-colors ${danger ? 'group-hover:text-bad/80' : 'text-fg/80 group-hover:text-fg'}`}>{label}</span>
          <span className="font-mono text-[9px] text-dim/40">{hint}</span>
        </button>
      ))}
    </div>
  )
}
