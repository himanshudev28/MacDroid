import { useEffect, useRef, useState } from 'react'
import MediaCard from './MediaCard.jsx'
import {
  Cast,
  Camera,
  Upload,
  FolderDown,
  Smartphone,
  Zap,
  Wifi,
  Bell,
  BellOff,
  Video,
  QrCode,
  Unlink,
  RefreshCw,
  Lock,
  Volume2,
  Volume1,
  VolumeX,
  ChevronRight,
} from 'lucide-react'

export default function Sidebar({
  device,
  info,
  appInfo,
  busy,
  scrcpy,
  wifi,
  media,
  paired,
  onMediaCmd,
  onPair,
  onToggleNotif,
  onMirror,
  onWireless,
  onPairWireless,
  onUnpair,
  onReconnect,
  onCamera,
  onScreenshot,
  onUpload,
  onOpenDownloads,
}) {
  return (
    <aside
      className="flex w-[268px] shrink-0 flex-col border-r border-line bg-panel"
      style={{ boxShadow: 'inset -1px 0 0 rgba(255,255,255,0.03)' }}
    >
      <div className="min-h-0 flex-1 overflow-y-auto">

        {/* ── Device section ──────────────────────────────────────── */}
        <section className="px-3 pt-4 pb-2">
          <SectionHeader label="Device">
            <button
              onClick={onReconnect}
              title="Reconnect — scan for the paired phone"
              className="rounded p-1 text-dim/60 transition-colors hover:bg-panel2 hover:text-amber"
            >
              <RefreshCw size={11} className={busy['reconnect'] ? 'spinner' : ''} />
            </button>
          </SectionHeader>

          {device ? (
            <DeviceCard device={device} info={info} />
          ) : appInfo ? (
            <DeviceCard device={null} info={appInfo} isAppLink />
          ) : (
            <EmptyDevice onPair={onPair} />
          )}
        </section>

        {/* ── Phone app link ──────────────────────────────────────── */}
        <section className="px-3 pb-2">
          <SectionHeader label="App Link" />
          {wifi?.connected ? (
            <LinkCard wifi={wifi} onToggle={onToggleNotif} />
          ) : (
            <UnlinkedCard onPair={onPair} />
          )}
        </section>

        {/* ── Media now-playing ───────────────────────────────────── */}
        <MediaCard media={media} onCmd={onMediaCmd} />

        {/* ── Volume ──────────────────────────────────────────────── */}
        <VolumeSlider deviceSerial={device?.serial} />

        {/* ── Actions ─────────────────────────────────────────────── */}
        <section className="px-3 pb-3">
          <SectionHeader label="Actions" />
          <div className="overflow-hidden rounded-xl border border-line bg-panel2 luminous-sm">
            <Action
              icon={Cast}
              label="Mirror screen"
              hint={device ? (scrcpy ? '' : 'install scrcpy') : 'needs ADB'}
              locked={!device}
              lockedTip="Connect a device to enable mirroring."
              onClick={device ? onMirror : onPairWireless}
            />
            <Action
              icon={QrCode}
              label="Connect via QR"
              hint="A11+"
              onClick={onPairWireless}
            />
            {paired && (
              <Action
                icon={Unlink}
                label="Unpair device"
                hint=""
                spinning={busy['unpair']}
                onClick={onUnpair}
                danger
              />
            )}
            {device && device.transport === 'usb' && (
              <Action
                icon={Wifi}
                label="Go wireless"
                hint="adb tcpip"
                spinning={busy['wireless']}
                onClick={onWireless}
              />
            )}
            <Action
              icon={Video}
              label="Phone camera"
              hint="OBS webcam"
              locked={!device}
              lockedTip="Connect a device to use phone camera."
              onClick={device ? onCamera : onPairWireless}
            />
            <Action
              icon={Camera}
              label="Screenshot"
              hint="→ Downloads"
              disabled={!device}
              spinning={busy['shot']}
              onClick={onScreenshot}
            />
            <Action
              icon={Upload}
              label="Send files to phone"
              hint=""
              disabled={!device}
              spinning={busy['upload']}
              onClick={onUpload}
            />
            <Action
              icon={FolderDown}
              label="Open Downloads"
              hint=""
              onClick={onOpenDownloads}
              last
            />
          </div>
        </section>

      </div>

      {/* ── Footer ──────────────────────────────────────────────────── */}
      <div
        className="shrink-0 border-t border-line px-4 py-2.5"
        style={{ boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.04)' }}
      >
        <p className="font-mono text-[9px] leading-relaxed tracking-[0.06em] text-dim/40">
          clipboard · files · notifs · sms · media · calls
        </p>
      </div>
    </aside>
  )
}

/* ── Sub-components ─────────────────────────────────────────────────── */

function SectionHeader({ label, children }) {
  return (
    <div className="mb-1.5 flex items-center justify-between">
      <span className="font-mono text-[9.5px] font-medium tracking-[0.10em] text-dim/60 uppercase">
        {label}
      </span>
      {children}
    </div>
  )
}

function DeviceCard({ device, info, isAppLink }) {
  const model    = info?.model || device?.model || '—'
  const android  = info?.android
  const battery  = info?.battery
  const charging = info?.charging
  const serial   = device?.serial
  const transport = device?.transport

  const badgeColor = isAppLink
    ? 'bg-ok/15 text-ok border-ok/20'
    : transport === 'wifi'
      ? 'bg-ok/15 text-ok border-ok/20'
      : 'bg-panel3 text-dim border-line'

  const badgeLabel = isAppLink
    ? 'App Link'
    : transport === 'wifi' ? 'Wi-Fi' : 'USB'

  const dotColor = isAppLink || transport === 'wifi' ? 'bg-ok led' : 'bg-amber led'

  return (
    <div
      className="rounded-xl border border-line bg-panel2 p-3.5 luminous-sm"
    >
      <div className="flex items-start gap-3">
        <div className="relative mt-0.5 shrink-0">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-amber/10 border border-amber/15">
            <Smartphone size={18} strokeWidth={1.75} className="text-amber" />
          </div>
          <span className={`absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full border border-panel2 ${dotColor}`} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-1">
            <p className="truncate text-[13px] font-semibold leading-tight text-fg/95">{model}</p>
            <span className={`shrink-0 rounded-md border px-1.5 py-0.5 font-mono text-[8px] tracking-[0.06em] ${badgeColor}`}>
              {badgeLabel}
            </span>
          </div>
          {android && (
            <p className="mt-0.5 font-mono text-[9.5px] text-dim">
              Android {android}
              {info?.sdk ? ` · SDK ${info.sdk}` : ''}
            </p>
          )}
          {serial && !isAppLink && (
            <p className="mt-1 truncate font-mono text-[9px] text-dim/45">{serial}</p>
          )}
        </div>
      </div>

      {battery != null && (
        <div className="mt-3">
          <div className="mb-1 flex items-center justify-between">
            <span className="font-mono text-[9px] text-dim/60">Battery</span>
            <span className="flex items-center gap-1 font-mono text-[10px] text-fg/70">
              {charging && <Zap size={9} className="text-amber" />}
              {battery}%
            </span>
          </div>
          <div className="h-[3px] w-full overflow-hidden rounded-full bg-panel3">
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

function EmptyDevice({ onPair }) {
  return (
    <button
      onClick={onPair}
      className="group w-full rounded-xl border border-dashed border-line bg-panel2/50 p-3.5 text-left transition-all hover:border-amber/30 hover:bg-panel2"
    >
      <div className="flex items-center gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-dashed border-line">
          <Smartphone size={16} strokeWidth={1.5} className="text-dim/40 group-hover:text-amber/60 transition-colors" />
        </div>
        <div>
          <p className="text-[12px] font-medium text-dim/70 group-hover:text-fg/70 transition-colors">No device</p>
          <p className="font-mono text-[9px] text-dim/40">Tap to pair your phone →</p>
        </div>
      </div>
    </button>
  )
}

function LinkCard({ wifi, onToggle }) {
  return (
    <div className="rounded-xl border border-line bg-panel2 p-3 luminous-sm">
      <div className="flex items-center gap-2.5">
        <div className="relative shrink-0">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-ok/10 border border-ok/20">
            <Wifi size={14} strokeWidth={2} className="text-ok" />
          </div>
          <span className="absolute -right-0.5 -top-0.5 h-1.5 w-1.5 rounded-full bg-ok border border-panel2" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[12px] font-medium text-fg/90">{wifi.phoneName}</p>
          <p className="font-mono text-[9px] text-dim/60">port {wifi.port}</p>
        </div>
        <button
          onClick={onToggle}
          title={wifi.notifications ? 'Mute phone notifications' : 'Unmute notifications'}
          className={`rounded-lg border p-1.5 transition-all duration-150 ${
            wifi.notifications
              ? 'border-amber/25 bg-amber/8 text-amber hover:bg-amber/15'
              : 'border-line text-dim/50 hover:border-line hover:bg-panel3 hover:text-dim'
          }`}
        >
          {wifi.notifications ? <Bell size={12} /> : <BellOff size={12} />}
        </button>
      </div>
    </div>
  )
}

function UnlinkedCard({ onPair }) {
  return (
    <div className="flex items-center justify-between rounded-xl border border-dashed border-line bg-panel2/50 px-3.5 py-2.5">
      <div className="flex items-center gap-2.5">
        <Wifi size={14} strokeWidth={1.5} className="text-dim/35" />
        <span className="text-[11.5px] text-dim/55">Not paired</span>
      </div>
      <button
        onClick={onPair}
        className="rounded-lg border border-amber/35 bg-amber/8 px-2.5 py-1 font-mono text-[9.5px] font-medium tracking-[0.04em] text-amber transition-all hover:bg-amber/15 hover:border-amber/50"
      >
        Pair
      </button>
    </div>
  )
}

function VolumeSlider({ deviceSerial }) {
  const [vol, setVol] = useState(null)
  const [err, setErr] = useState(false)
  const debounceRef  = useRef(null)
  const prevLevelRef = useRef(null)

  useEffect(() => {
    if (!deviceSerial) { setVol(null); return }
    window.droid.volumeGet().then((r) => {
      if (r.ok) { setVol(r.data); prevLevelRef.current = r.data.level }
      else setErr(true)
    })
  }, [deviceSerial])

  if (!deviceSerial || err || !vol) return null

  const pct  = Math.round((vol.level / vol.max) * 100)
  const Icon = pct === 0 ? VolumeX : pct < 50 ? Volume1 : Volume2

  const handleChange = async (e) => {
    const level    = Number(e.target.value)
    const prevLevel = prevLevelRef.current ?? vol.level
    setVol((v) => ({ ...v, level }))
    clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(async () => {
      const r = await window.droid.volumeSet(level, prevLevel)
      if (r.ok) prevLevelRef.current = level
    }, 80)
  }

  return (
    <section className="px-3 pb-2">
      <SectionHeader label="Volume" />
      <div className="rounded-xl border border-line bg-panel2 px-3.5 py-3 luminous-sm">
        <div className="flex items-center gap-2.5">
          <Icon size={12} className="shrink-0 text-dim/60" />
          <input
            type="range"
            min={0}
            max={vol.max}
            value={vol.level}
            onChange={handleChange}
            className="vol-slider min-w-0 flex-1"
          />
          <span className="w-7 shrink-0 text-right font-mono text-[9.5px] text-dim/70">{pct}%</span>
        </div>
      </div>
    </section>
  )
}

function Action({ icon: Icon, label, hint, disabled, locked, lockedTip, spinning, danger, onClick, last }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled || spinning}
      title={locked ? lockedTip : undefined}
      className={`group flex w-full items-center gap-3 border-b border-line/60 px-3.5 py-2.5 text-left transition-all duration-100 last:border-b-0 hover:bg-panel3 disabled:cursor-not-allowed disabled:opacity-30 ${
        locked ? 'opacity-50 hover:opacity-90' : ''
      } ${danger ? 'hover:bg-bad/5' : ''}`}
    >
      <Icon
        size={14}
        strokeWidth={1.75}
        className={`shrink-0 transition-colors ${
          spinning
            ? 'spinner text-amber'
            : danger
              ? 'text-dim/50 group-hover:text-bad/80'
              : 'text-dim/50 group-hover:text-amber'
        }`}
      />
      <span className={`flex-1 text-[12.5px] font-medium transition-colors ${danger ? 'group-hover:text-bad/80' : 'text-fg/80 group-hover:text-fg'}`}>
        {label}
      </span>
      {locked ? (
        <Lock size={10} className="text-dim/30 group-hover:text-amber/50 transition-colors" />
      ) : hint ? (
        <span className="font-mono text-[9px] text-dim/40">{hint}</span>
      ) : (
        <ChevronRight size={10} className="text-dim/20 group-hover:text-dim/50 transition-colors" />
      )}
    </button>
  )
}
