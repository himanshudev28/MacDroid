export default function DashboardView({
  connected,
  wifi,
  appInfo,
  media,
  notifs,
  prog,
  onPair,
  onMirrorWifi,
  onCameraWifi,
  onUpload,
  onOpenDownloads,
}) {
  const linked = !!wifi?.connected
  const deviceName = connected
    ? (appInfo?.model || connected.model || 'Android Device')
    : linked
      ? (wifi.phoneName || 'Linked Phone')
      : null

  const battery = appInfo?.battery ?? null
  const charging = appInfo?.charging ?? false
  const recentNotifs = (notifs || []).slice(0, 3)

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-2xl space-y-5">

        <HeroCard
          connected={connected}
          linked={linked}
          deviceName={deviceName}
          battery={battery}
          charging={charging}
          onPair={onPair}
        />

        <QuickActions
          linked={linked}
          connected={connected}
          onMirrorWifi={onMirrorWifi}
          onCameraWifi={onCameraWifi}
          onUpload={onUpload}
          onOpenDownloads={onOpenDownloads}
        />

        {(connected || linked) && (
          <StatsRow connected={connected} wifi={wifi} appInfo={appInfo} />
        )}

        {recentNotifs.length > 0 && (
          <RecentNotifs items={recentNotifs} />
        )}

        {prog && prog.dir === 'phone' && (
          <TransferBar prog={prog} />
        )}

      </div>
    </div>
  )
}

function HeroCard({ connected, linked, deviceName, battery, charging, onPair }) {
  return (
    <div className="rounded-2xl border border-line bg-panel2 p-5 luminous-sm">
      <div className="flex items-start gap-4">
        <div className="relative shrink-0">
          <div
            className="flex h-12 w-12 items-center justify-center rounded-xl border border-amber/20"
            style={{ background: 'linear-gradient(145deg,rgba(245,166,35,0.12),rgba(255,138,61,0.06))' }}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-amber" aria-hidden="true">
              <path d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
            </svg>
          </div>
          <span
            className={`absolute -right-0.5 -top-0.5 h-3 w-3 rounded-full border-2 border-panel2 ${
              connected ? 'bg-amber led' : linked ? 'bg-ok led' : 'bg-dim/30'
            }`}
          />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <div>
              <p className="text-[15px] font-semibold text-fg/95">
                {deviceName || 'No device connected'}
              </p>
              <p className="mt-0.5 font-mono text-[10px] text-dim/60">
                {connected
                  ? connected.transport === 'wifi' ? 'Wi-Fi ADB' : 'USB ADB'
                  : linked ? 'App Link · Wi-Fi' : 'Tap to pair your phone'}
              </p>
            </div>
            {(connected || linked) ? (
              <span
                className={`shrink-0 rounded-lg border px-2 py-1 font-mono text-[9px] tracking-[0.05em] ${
                  connected
                    ? 'border-amber/25 bg-amber/10 text-amber'
                    : 'border-ok/25 bg-ok/10 text-ok'
                }`}
              >
                {connected ? (connected.transport === 'wifi' ? 'Wi-Fi' : 'USB') : 'Linked'}
              </span>
            ) : (
              <button
                onClick={onPair}
                className="shrink-0 rounded-lg border border-amber/35 bg-amber/10 px-3 py-1.5 font-mono text-[9.5px] font-medium text-amber transition-all hover:bg-amber/18 hover:border-amber/50"
              >
                Pair
              </button>
            )}
          </div>

          {battery != null && (
            <div className="mt-3">
              <div className="mb-1 flex items-center justify-between">
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
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-panel3">
                <div
                  className={`h-full rounded-full transition-all duration-700 ${battery > 20 ? 'bg-ok' : 'bg-bad'}`}
                  style={{ width: `${battery}%` }}
                />
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function QuickActions({ linked, connected, onMirrorWifi, onCameraWifi, onUpload, onOpenDownloads }) {
  const actions = [
    {
      label: 'Mirror Wi-Fi',
      hint: 'Screen to Mac',
      disabled: !linked,
      onClick: onMirrorWifi,
      iconPath: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
    },
    {
      label: 'Camera Wi-Fi',
      hint: 'Phone cam to Mac',
      disabled: !linked,
      onClick: onCameraWifi,
      iconPath: 'M15 10l4.553-2.069A1 1 0 0121 8.882v6.236a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z',
    },
    {
      label: 'Send File',
      hint: 'Push to phone',
      disabled: !connected,
      onClick: onUpload,
      iconPath: 'M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12',
    },
    {
      label: 'Downloads',
      hint: 'Open folder',
      disabled: false,
      onClick: onOpenDownloads,
      iconPath: 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z',
    },
  ]

  return (
    <div className="grid grid-cols-4 gap-3">
      {actions.map(({ label, hint, disabled, onClick, iconPath }) => (
        <button
          key={label}
          onClick={onClick}
          disabled={disabled}
          className="group flex flex-col items-center gap-2 rounded-xl border border-line bg-panel2 px-2 py-4 text-center transition-all duration-150 hover:border-amber/30 hover:bg-panel3 disabled:cursor-not-allowed disabled:opacity-35"
        >
          <div className={`flex h-9 w-9 items-center justify-center rounded-lg border transition-colors ${disabled ? 'border-line bg-panel3' : 'border-amber/20 bg-amber/8 group-hover:bg-amber/14'}`}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className={`h-4 w-4 transition-colors ${disabled ? 'text-dim/40' : 'text-amber'}`} aria-hidden="true">
              <path d={iconPath} />
            </svg>
          </div>
          <div>
            <p className={`text-[11px] font-semibold transition-colors ${disabled ? 'text-dim/40' : 'text-fg/80 group-hover:text-fg'}`}>{label}</p>
            <p className="font-mono text-[9px] text-dim/45">{hint}</p>
          </div>
        </button>
      ))}
    </div>
  )
}

function StatsRow({ connected, wifi, appInfo }) {
  const battery = appInfo?.battery
  const android = appInfo?.android
  const transport = connected
    ? (connected.transport === 'wifi' ? 'Wi-Fi ADB' : 'USB ADB')
    : 'App Link'

  const stats = [
    battery != null && { label: 'Battery', value: `${battery}%` },
    android && { label: 'Android', value: android },
    { label: 'Connection', value: transport },
    wifi?.port && { label: 'Port', value: String(wifi.port) },
  ].filter(Boolean)

  return (
    <div className="flex gap-3">
      {stats.map(({ label, value }) => (
        <div key={label} className="flex-1 rounded-xl border border-line bg-panel2 px-3 py-3 text-center">
          <p className="text-[13px] font-semibold text-fg/85">{value}</p>
          <p className="mt-0.5 font-mono text-[9px] text-dim/55">{label}</p>
        </div>
      ))}
    </div>
  )
}

function RecentNotifs({ items }) {
  return (
    <div className="rounded-xl border border-line bg-panel2">
      <div className="border-b border-line px-4 py-2.5">
        <span className="font-mono text-[9.5px] tracking-[0.10em] text-dim/60 uppercase">Recent Notifications</span>
      </div>
      <div className="divide-y divide-line/50">
        {items.map((n, i) => (
          <div key={n.key + i} className="flex items-start gap-3 px-4 py-3">
            <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-amber/10 border border-amber/15 font-mono text-[9px] font-bold text-amber">
              {(n.app || '?').slice(0, 2).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[12px] font-medium text-fg/85">{n.title || n.app}</p>
              {n.text && <p className="truncate font-mono text-[10px] text-dim/55">{n.text}</p>}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function TransferBar({ prog }) {
  const pct = prog.total ? Math.round((prog.sent / prog.total) * 100) : 0
  return (
    <div className="rounded-xl border border-amber/20 bg-amber/5 px-4 py-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="font-mono text-[10px] text-amber/80">Receiving {prog.name}</span>
        <span className="font-mono text-[10px] text-dim/60">{prog.total ? `${pct}%` : '…'}</span>
      </div>
      <div className="h-1 overflow-hidden rounded-full bg-amber/15">
        <div
          className="h-full rounded-full bg-amber/70 transition-all duration-300"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
