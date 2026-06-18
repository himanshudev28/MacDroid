import { useEffect, useRef, useState } from 'react'

const NAV = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    path: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
  },
  {
    id: 'devices',
    label: 'Devices',
    path: 'M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z',
  },
  {
    id: 'notifications',
    label: 'Notifications',
    path: 'M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9',
  },
  {
    id: 'messages',
    label: 'Messages',
    path: 'M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z',
  },
  {
    id: 'calls',
    label: 'Calls',
    path: 'M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z',
  },
  {
    id: 'contacts',
    label: 'Contacts',
    path: 'M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z',
  },
  {
    id: 'clipboard',
    label: 'Clipboard',
    path: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2',
  },
  {
    id: 'files',
    label: 'Files',
    path: 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z',
  },
  {
    id: 'photos',
    label: 'Photos',
    path: 'M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z',
  },
  {
    id: 'camera',
    label: 'Camera',
    path: 'M15 10l4.553-2.069A1 1 0 0121 8.882v6.236a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z',
  },
  {
    id: 'media',
    label: 'Media',
    path: 'M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3',
  },
  {
    id: 'screen',
    label: 'Screen Mirror',
    path: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  },
  {
    id: 'settings',
    label: 'Settings',
    path: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z',
  },
]

export default function Sidebar({ view, setView, connected, wifi, appInfo, notifCount }) {
  const linked = !!wifi?.connected

  const deviceName = connected
    ? (appInfo?.model || connected.model || connected.serial)
    : linked
      ? (wifi.phoneName || 'Linked phone')
      : null

  return (
    <aside
      className="flex w-[236px] shrink-0 flex-col border-r border-line bg-panel"
      style={{ boxShadow: 'inset -1px 0 0 rgba(255,255,255,0.03)' }}
    >
      <div
        className="drag shrink-0 pt-[28px]"
        style={{ WebkitAppRegion: 'drag' }}
      />

      <div className="no-drag flex shrink-0 items-center gap-2.5 px-4 pb-3 pt-2">
        <div
          className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg"
          style={{ background: 'linear-gradient(145deg,#f5a623,#ff8a3d)', boxShadow: '0 2px 8px rgba(245,166,35,0.35)' }}
        >
          <svg viewBox="0 0 20 20" className="h-4 w-4 fill-white/90" aria-hidden="true">
            <path d="M10 2a5 5 0 015 5v1h1a2 2 0 012 2v5a2 2 0 01-2 2H4a2 2 0 01-2-2V10a2 2 0 012-2h1V7a5 5 0 015-5zm0 2a3 3 0 00-3 3v1h6V7a3 3 0 00-3-3zm0 7a1.5 1.5 0 110 3 1.5 1.5 0 010-3z" />
          </svg>
        </div>
        <span className="font-display text-[13px] font-semibold tracking-[0.03em] text-fg/90">
          DroidDock
        </span>
      </div>

      <nav className="no-drag min-h-0 flex-1 overflow-y-auto px-2 pb-2" style={{ scrollbarWidth: 'none' }}>
        {NAV.map(({ id, label, path }) => {
          const active = view === id
          const badge = id === 'notifications' && notifCount > 0 ? notifCount : null
          return (
            <button
              key={id}
              onClick={() => setView(id)}
              className={`group flex w-full items-center gap-2.5 rounded-lg px-2.5 py-[7px] text-left transition-all duration-100 ${
                active
                  ? 'bg-amber/12 text-amber'
                  : 'text-dim/70 hover:bg-panel2 hover:text-fg/80'
              }`}
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                strokeWidth={active ? 2 : 1.75}
                stroke="currentColor"
                className="h-[15px] w-[15px] shrink-0"
                aria-hidden="true"
              >
                <path strokeLinecap="round" strokeLinejoin="round" d={path} />
              </svg>
              <span className={`flex-1 text-[12.5px] font-medium leading-none`}>
                {label}
              </span>
              {badge && (
                <span className="rounded-md bg-amber/20 px-1.5 py-0.5 font-mono text-[9px] font-semibold text-amber">
                  {badge > 99 ? '99+' : badge}
                </span>
              )}
              {active && (
                <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber" />
              )}
            </button>
          )
        })}
      </nav>

      <div className="no-drag shrink-0 border-t border-line p-3">
        <div className="rounded-xl border border-line bg-panel2 px-3 py-2.5">
          <div className="flex items-center gap-2.5">
            <span
              className={`h-2 w-2 shrink-0 rounded-full transition-colors duration-500 ${
                connected ? 'bg-amber led' : linked ? 'bg-ok led' : 'bg-dim/25'
              }`}
            />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[11.5px] font-medium text-fg/80">
                {deviceName || 'No device'}
              </p>
              <p className="font-mono text-[9px] text-dim/50">
                {connected
                  ? connected.transport === 'wifi' ? 'Wi-Fi ADB' : 'USB ADB'
                  : linked ? 'App Link' : 'Disconnected'}
              </p>
            </div>
            {(connected || linked) && (
              <span
                className={`shrink-0 rounded border px-1.5 py-0.5 font-mono text-[8px] tracking-[0.04em] ${
                  connected
                    ? connected.transport === 'wifi'
                      ? 'border-ok/25 bg-ok/10 text-ok'
                      : 'border-amber/25 bg-amber/10 text-amber'
                    : 'border-ok/25 bg-ok/10 text-ok'
                }`}
              >
                {connected ? (connected.transport === 'wifi' ? 'Wi-Fi' : 'USB') : 'WiFi'}
              </span>
            )}
          </div>
        </div>
      </div>
    </aside>
  )
}
