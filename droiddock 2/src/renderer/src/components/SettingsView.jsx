import { useEffect, useState } from 'react'
import { Wifi, Folder, Info, SlidersHorizontal } from 'lucide-react'

export default function SettingsView({ onToast }) {
  const [s, setS] = useState(null)
  const [name, setName] = useState('')

  const load = () => window.droid.settingsGet().then((r) => r.ok && (setS(r.data), setName(r.data.deviceName)))
  useEffect(() => {
    load()
  }, [])

  const set = async (key, value) => {
    const r = await window.droid.settingsSet(key, value)
    if (!r.ok) return onToast('bad', r.error)
    load()
  }

  if (!s) return <div className="p-8 font-mono text-xs text-dim">loading settings…</div>

  return (
    <div className="mx-auto h-full max-w-2xl overflow-y-auto p-8">
      <Section icon={Wifi} title="CONNECTION">
        <Field label="Device name" hint="Shown on your phone as the Mac's name">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={() => name.trim() !== s.deviceName && set('deviceName', name.trim())}
            onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
            className="w-48 border border-line bg-ink px-2.5 py-1.5 text-right text-[13px] text-fg outline-none focus:border-amber/50"
          />
        </Field>
        <Toggle
          label="Auto-reconnect"
          hint="Reconnect a paired phone over Wi-Fi automatically"
          on={s.autoReconnect}
          onChange={(v) => set('autoReconnect', v)}
        />
        <Toggle
          label="Clipboard sync"
          hint="Share copied text both ways. Off stops all clipboard traffic."
          on={s.clipboardSync}
          onChange={(v) => set('clipboardSync', v)}
        />
        <Toggle
          label="Phone notifications"
          hint="Show your phone's notifications on the Mac"
          on={s.notifications}
          onChange={(v) => set('notifications', v)}
        />
        <Field label="Link status" hint={s.connected ? `Linked · ${s.phoneName}` : 'Not linked'}>
          <span className={`font-mono text-[11px] ${s.connected ? 'text-ok' : 'text-dim'}`}>
            {s.ips?.[0] ? `${s.ips[0]}:${s.port}` : `:${s.port}`}
          </span>
        </Field>
      </Section>

      <Section icon={Folder} title="FILES">
        <Field label="Downloads folder" hint="Where pulled files & screenshots land">
          <button
            onClick={() => window.droid.openDownloads()}
            className="max-w-[15rem] truncate font-mono text-[11px] text-amber hover:underline"
            title={s.downloads}
          >
            {s.downloads}
          </button>
        </Field>
        <Field label="Large-file warning" hint="Warn before transfers above this size">
          <select
            value={s.largeFileWarning}
            onChange={(e) => set('largeFileWarning', Number(e.target.value))}
            className="border border-line bg-ink px-2.5 py-1.5 text-[13px] text-fg outline-none focus:border-amber/50"
          >
            {[50, 100, 250, 500, 1000].map((mb) => (
              <option key={mb} value={mb}>
                {mb} MB
              </option>
            ))}
          </select>
        </Field>
      </Section>

      <Section icon={Info} title="ABOUT">
        <Field label="App" hint="">
          <span className="text-[13px] text-dim">DroidDock</span>
        </Field>
        <Field label="Version" hint="">
          <span className="font-mono text-[12px] text-dim">{s.version}</span>
        </Field>
        <Field label="Tools" hint="adb · scrcpy availability">
          <span className="font-mono text-[11px]">
            <span className={s.adb ? 'text-ok' : 'text-bad'}>ADB</span>
            <span className="text-dim"> · </span>
            <span className={s.scrcpy ? 'text-ok' : 'text-bad'}>SCRCPY</span>
          </span>
        </Field>
      </Section>
    </div>
  )
}

function Section({ icon: Icon, title, children }) {
  return (
    <div className="mb-8">
      <div className="mb-2 flex items-center gap-2">
        <Icon size={13} className="text-amber" />
        <span className="font-mono text-[10px] tracking-[0.25em] text-dim">{title}</span>
      </div>
      <div className="border border-line bg-panel">{children}</div>
    </div>
  )
}

function Field({ label, hint, children }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-line/50 px-4 py-3 last:border-b-0">
      <div className="min-w-0">
        <p className="text-[13px] font-medium">{label}</p>
        {hint && <p className="mt-0.5 text-[11px] leading-snug text-dim">{hint}</p>}
      </div>
      <div className="shrink-0">{children}</div>
    </div>
  )
}

function Toggle({ label, hint, on, onChange }) {
  return (
    <Field label={label} hint={hint}>
      <button
        onClick={() => onChange(!on)}
        className={`relative h-5 w-9 rounded-full border transition-colors ${
          on ? 'border-amber/50 bg-amber/30' : 'border-line bg-ink'
        }`}
      >
        <span
          className={`absolute top-0.5 h-3.5 w-3.5 rounded-full transition-all ${
            on ? 'left-4 bg-amber' : 'left-0.5 bg-dim'
          }`}
        />
      </button>
    </Field>
  )
}
