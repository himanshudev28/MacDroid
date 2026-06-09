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
  VolumeX
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
  onOpenDownloads
}) {
  return (
    <aside className="flex w-72 shrink-0 flex-col border-r border-line bg-panel">
      <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="flex items-center justify-between px-5 pb-2 pt-5">
        <p className="font-mono text-[10px] tracking-[0.25em] text-dim">DEVICE</p>
        <button
          onClick={onReconnect}
          title="Reconnect — scan for the paired phone now"
          className="text-dim transition-colors hover:text-amber"
        >
          <RefreshCw size={12} className={busy['reconnect'] ? 'spinner' : ''} />
        </button>
      </div>

      {device ? (
        <div className="relative mx-4 border border-line bg-panel2 p-4">
          <span
            className={`absolute right-3 top-3 border px-1.5 py-0.5 font-mono text-[8px] tracking-[0.2em] ${
              device.transport === 'wifi'
                ? 'border-ok/40 text-ok'
                : 'border-line text-dim'
            }`}
          >
            {device.transport === 'wifi' ? 'WI-FI' : 'USB'}
          </span>
          <div className="flex items-start gap-3">
            <div className="relative mt-0.5">
              <Smartphone size={26} strokeWidth={1.5} className="text-amber" />
              <span className="led absolute -right-0.5 -top-0.5 h-1.5 w-1.5 rounded-full bg-amber" />
            </div>
            <div className="min-w-0">
              <p className="truncate font-display text-[15px] font-semibold leading-tight">
                {info?.model || device.model}
              </p>
              <p className="mt-0.5 font-mono text-[10px] text-dim">
                {info ? `Android ${info.android} · SDK ${info.sdk}` : 'reading…'}
              </p>
              <p className="mt-2 truncate font-mono text-[10px] tracking-wider text-dim/70">
                {device.serial}
              </p>
            </div>
          </div>

          {info?.battery != null && (
            <div className="mt-4">
              <div className="flex items-center justify-between font-mono text-[10px] text-dim">
                <span className="tracking-[0.2em]">BATTERY</span>
                <span className="flex items-center gap-1 text-fg">
                  {info.charging && <Zap size={10} className="text-amber" />}
                  {info.battery}%
                </span>
              </div>
              <div className="mt-1.5 h-1 w-full bg-ink">
                <div
                  className={`h-full transition-all duration-700 ${
                    info.battery > 20 ? 'bg-ok' : 'bg-bad'
                  }`}
                  style={{ width: `${info.battery}%` }}
                />
              </div>
            </div>
          )}
        </div>
      ) : appInfo ? (
        <div className="relative mx-4 border border-line bg-panel2 p-4">
          <span className="absolute right-3 top-3 border border-ok/40 px-1.5 py-0.5 font-mono text-[8px] tracking-[0.2em] text-ok">
            APP LINK
          </span>
          <div className="flex items-start gap-3">
            <div className="relative mt-0.5">
              <Smartphone size={26} strokeWidth={1.5} className="text-ok" />
              <span className="led absolute -right-0.5 -top-0.5 h-1.5 w-1.5 rounded-full bg-ok" />
            </div>
            <div className="min-w-0">
              <p className="truncate font-display text-[15px] font-semibold leading-tight">
                {appInfo.model}
              </p>
              <p className="mt-0.5 font-mono text-[10px] text-dim">
                Android {appInfo.android} · SDK {appInfo.sdk}
              </p>
              <p className="mt-2 font-mono text-[10px] tracking-wider text-dim/70">
                over the app link
              </p>
            </div>
          </div>
          {appInfo.battery != null && (
            <div className="mt-4">
              <div className="flex items-center justify-between font-mono text-[10px] text-dim">
                <span className="tracking-[0.2em]">BATTERY</span>
                <span className="flex items-center gap-1 text-fg">
                  {appInfo.charging && <Zap size={10} className="text-amber" />}
                  {appInfo.battery}%
                </span>
              </div>
              <div className="mt-1.5 h-1 w-full bg-ink">
                <div
                  className={`h-full transition-all duration-700 ${
                    appInfo.battery > 20 ? 'bg-ok' : 'bg-bad'
                  }`}
                  style={{ width: `${appInfo.battery}%` }}
                />
              </div>
            </div>
          )}
        </div>
      ) : (
        <div className="mx-4 border border-dashed border-line p-4">
          <p className="font-mono text-[11px] leading-relaxed text-dim">
            NO DEVICE
            <br />
            <span className="text-dim/60">link the phone app, or connect ADB…</span>
          </p>
        </div>
      )}

      <p className="px-5 pb-2 pt-6 font-mono text-[10px] tracking-[0.25em] text-dim">
        PHONE APP LINK
      </p>
      {wifi?.connected ? (
        <div className="mx-4 border border-line bg-panel2 p-4">
          <div className="flex items-center gap-3">
            <div className="relative">
              <Wifi size={18} strokeWidth={1.75} className="text-ok" />
              <span className="absolute -right-1 -top-1 h-1.5 w-1.5 rounded-full bg-ok" />
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-medium">{wifi.phoneName}</p>
              <p className="font-mono text-[9px] tracking-wider text-dim">
                CLIP + NOTIFS · :{wifi.port}
              </p>
            </div>
            <button
              onClick={onToggleNotif}
              title={wifi.notifications ? 'Mute phone notifications' : 'Unmute notifications'}
              className={`border p-1.5 transition-colors ${
                wifi.notifications
                  ? 'border-amber/40 text-amber hover:bg-amber/10'
                  : 'border-line text-dim hover:text-fg'
              }`}
            >
              {wifi.notifications ? <Bell size={13} /> : <BellOff size={13} />}
            </button>
          </div>
        </div>
      ) : (
        <div className="mx-4 flex items-center justify-between border border-dashed border-line p-4">
          <div className="flex items-center gap-3">
            <Wifi size={18} strokeWidth={1.5} className="text-dim/50" />
            <p className="font-mono text-[11px] text-dim">not paired</p>
          </div>
          <button
            onClick={onPair}
            className="border border-amber/40 px-3 py-1 font-display text-[10px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/10"
          >
            PAIR
          </button>
        </div>
      )}

      <MediaCard media={media} onCmd={onMediaCmd} />

      {/* Device volume slider — only visible when ADB connected */}
      <VolumeSlider deviceSerial={device?.serial} />

      <p className="px-5 pb-2 pt-6 font-mono text-[10px] tracking-[0.25em] text-dim">ACTIONS</p>
      <nav className="flex flex-col gap-1 px-4">
        <Action
          icon={Cast}
          label="Mirror screen"
          hint={device ? (scrcpy ? 'via scrcpy' : 'install scrcpy') : 'needs ADB'}
          locked={!device}
          lockedTip="One-time setup unlocks screen mirroring + USB speed."
          onClick={device ? onMirror : onPairWireless}
        />
        <Action
          icon={QrCode}
          label="Connect ADB (QR)"
          hint="cable-free · A11+"
          onClick={onPairWireless}
        />
        {paired && (
          <Action
            icon={Unlink}
            label="Unpair / forget"
            hint="clear Wi-Fi pairing"
            spinning={busy['unpair']}
            onClick={onUnpair}
          />
        )}
        {device && device.transport === 'usb' && (
          <Action
            icon={Wifi}
            label="Go wireless"
            hint="legacy · adb tcpip"
            spinning={busy['wireless']}
            onClick={onWireless}
          />
        )}
        <Action
          icon={Video}
          label="Phone camera"
          hint={device ? 'for OBS webcam' : 'needs ADB'}
          locked={!device}
          lockedTip="One-time setup unlocks screen mirroring + USB speed."
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
          hint="→ /sdcard/Download"
          disabled={!device}
          spinning={busy['upload']}
          onClick={onUpload}
        />
        <Action icon={FolderDown} label="Open Mac Downloads" hint="" onClick={onOpenDownloads} />
      </nav>

      </div>
      <div className="shrink-0 border-t border-line px-5 py-3">
        <p className="font-mono text-[9px] leading-relaxed tracking-wider text-dim/60">
          ALL PHASES SHIPPED<br />
          clipboard · files · notifs · sms · media · calls
        </p>
      </div>
    </aside>
  )
}

function VolumeSlider({ deviceSerial }) {
  const [vol, setVol] = useState(null) // { level, max } | null
  const [err, setErr] = useState(false)
  const debounceRef = useRef(null)
  const prevLevelRef = useRef(null) // track prev level for keyevent delta

  useEffect(() => {
    if (!deviceSerial) { setVol(null); return }
    window.droid.volumeGet().then((r) => {
      if (r.ok) { setVol(r.data); prevLevelRef.current = r.data.level }
      else setErr(true)
    })
  }, [deviceSerial])

  if (!deviceSerial) return null
  if (err || !vol) return null

  const pct = Math.round((vol.level / vol.max) * 100)
  const Icon = pct === 0 ? VolumeX : pct < 50 ? Volume1 : Volume2

  const handleChange = async (e) => {
    const level = Number(e.target.value)
    const prevLevel = prevLevelRef.current ?? vol.level
    setVol((v) => ({ ...v, level }))
    clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(async () => {
      const r = await window.droid.volumeSet(level, prevLevel)
      if (r.ok) {
        prevLevelRef.current = level // update baseline for next keyevent delta
      }
      // silently ignore errors — slider already shows optimistic value
    }, 80)
  }

  return (
    <>
      <p className="px-5 pb-2 pt-6 font-mono text-[10px] tracking-[0.25em] text-dim">
        DEVICE VOLUME
      </p>
      <div className="mx-4 border border-line bg-panel2 px-4 pb-4 pt-3">
        <div className="flex items-center gap-2">
          <Icon size={13} className="shrink-0 text-dim" />
          <input
            type="range"
            min={0}
            max={vol.max}
            value={vol.level}
            onChange={handleChange}
            className="vol-slider min-w-0 flex-1"
          />
          <span className="w-8 shrink-0 text-right font-mono text-[10px] text-dim">{pct}%</span>
        </div>
      </div>
    </>
  )
}

function Action({ icon: Icon, label, hint, disabled, locked, lockedTip, spinning, onClick }) {
  // `locked` = feature needs ADB; looks dimmed but stays clickable to open pairing.
  return (
    <button
      onClick={onClick}
      disabled={disabled || spinning}
      title={locked ? lockedTip : undefined}
      className={`group flex items-center gap-3 border border-transparent px-3 py-2.5 text-left transition-colors hover:border-line hover:bg-panel2 disabled:cursor-not-allowed disabled:opacity-35 ${
        locked ? 'opacity-45 hover:opacity-100' : ''
      }`}
    >
      <Icon
        size={15}
        strokeWidth={1.75}
        className={`shrink-0 text-dim transition-colors group-hover:text-amber ${
          spinning ? 'spinner text-amber' : ''
        }`}
      />
      <span className="flex-1 text-[13px] font-medium">{label}</span>
      {locked ? (
        <Lock size={11} className="text-dim/50 group-hover:text-amber" />
      ) : (
        hint && <span className="font-mono text-[9px] text-dim/60">{hint}</span>
      )}
    </button>
  )
}
