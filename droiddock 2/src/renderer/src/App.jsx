import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Sidebar from './components/Sidebar.jsx'
import FileBrowser from './components/FileBrowser.jsx'
import WaitingState from './components/WaitingState.jsx'
import Toasts from './components/Toasts.jsx'
import PairingModal from './components/PairingModal.jsx'
import WirelessPairModal from './components/WirelessPairModal.jsx'
import MessagesView from './components/MessagesView.jsx'
import NotificationsView from './components/NotificationsView.jsx'
import ContactsView from './components/ContactsView.jsx'
import PhotosView from './components/PhotosView.jsx'
import SettingsView from './components/SettingsView.jsx'
import CallOverlay from './components/CallOverlay.jsx'
import SetupModal from './components/SetupModal.jsx'
import MirrorView from './components/MirrorView.jsx'

const ROOT = '/sdcard'

const TABS = [
  ['files',         'Files'],
  ['photos',        'Photos'],
  ['screen',        'Screen'],
  ['messages',      'Messages'],
  ['contacts',      'Contacts'],
  ['notifications', 'Notifs'],
  ['settings',      'Settings'],
]

export default function App() {
  const [tools, setTools] = useState(null)
  const [devices, setDevices] = useState([])
  const [serial, setSerial] = useState(null)
  const [info, setInfo] = useState(null)
  const [path, setPath] = useState(ROOT)
  const [entries, setEntries] = useState([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState({})
  const [toasts, setToasts] = useState([])
  const [wifi, setWifi] = useState(null)
  const [pairOpen, setPairOpen] = useState(false)
  const [setup, setSetup] = useState(null)
  const [wPairOpen, setWPairOpen] = useState(false)
  const [pairedGuid, setPairedGuid] = useState(null)
  const [view, setView] = useState('files')
  const [media, setMedia] = useState(null)
  const [notifs, setNotifs] = useState([])
  const [appInfo, setAppInfo] = useState(null)
  const [fsVia, setFsVia] = useState(null)
  const [prog, setProg] = useState(null)
  const [messageTarget, setMessageTarget] = useState(null)
  const [activeCall, setActiveCall] = useState(null)
  const toastId = useRef(0)

  const toast = useCallback((kind, text) => {
    const id = ++toastId.current
    setToasts((t) => [...t, { id, kind, text }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4000)
  }, [])

  const connected = useMemo(
    () => devices.find((d) => d.serial === serial && d.state === 'device') || null,
    [devices, serial]
  )
  const unauthorized = useMemo(() => devices.some((d) => d.state === 'unauthorized'), [devices])
  const fsLink = useMemo(() => !!wifi?.connected && (wifi?.caps || []).includes('fs'), [wifi])
  const fsAvailable = !!connected || fsLink

  useEffect(() => {
    window.droid.tools().then(setTools)
    const offTools = window.droid.onTools(setTools)
    window.droid.devices().then(setDevices)
    window.droid.wifiStatus().then(setWifi)
    window.droid.pairedInfo().then((r) => r.ok && setPairedGuid(r.data.guid))
    const offDevices = window.droid.onDevices(setDevices)
    const offWifi = window.droid.onWifi(setWifi)
    const offWifiEvent = window.droid.onWifiEvent((ev) => toast(ev.kind, ev.text))
    const offMedia = window.droid.onMedia(setMedia)
    const offNotif = window.droid.onNotification((n) =>
      setNotifs((list) => [n, ...list.filter((x) => x.key !== n.key)].slice(0, 100))
    )
    const offNotifGone = window.droid.onNotificationRemoved(({ key }) =>
      setNotifs((list) => list.filter((x) => x.key !== key))
    )
    const offInfo = window.droid.onDeviceInfo(setAppInfo)
    const offProg = window.droid.onTransferProgress((p) => {
      if (p.done) {
        setProg(null)
        if (p.dir === 'phone') toast('ok', `${p.name} received → saved to Downloads`)
      } else {
        setProg(p)
      }
    })
    const offCallState = window.droid.onCallState(({ state, serial }) => {
      setActiveCall((prev) => {
        if (state === 'IDLE') return null
        return { state, serial, number: prev?.number ?? '', name: prev?.name ?? '' }
      })
    })
    const offCallIncoming = window.droid.onCallIncoming((m) => {
      const item = {
        key: m.key || `call-${Date.now()}`,
        type: 'call',
        app: 'Phone',
        title: m.name || m.number || 'Unknown caller',
        text: m.number && m.name ? m.number : 'Incoming call on your phone',
        replyable: false,
        time: m.time || Date.now()
      }
      setNotifs((list) => [item, ...list.filter((x) => x.key !== item.key)].slice(0, 100))
    })
    return () => {
      offTools(); offDevices(); offWifi(); offWifiEvent(); offMedia()
      offNotif(); offNotifGone(); offInfo(); offProg(); offCallState(); offCallIncoming()
    }
  }, [toast])

  useEffect(() => { if (!wifi?.connected) setAppInfo(null) }, [wifi?.connected])

  useEffect(() => {
    const ready = devices.filter((d) => d.state === 'device')
    if (!ready.find((d) => d.serial === serial)) {
      setSerial(ready[0]?.serial ?? null)
      setInfo(null)
    }
  }, [devices, serial])

  const refreshList = useCallback(
    async (p = path) => {
      if (!connected && !fsLink) return
      setLoading(true)
      const res = await window.droid.list(serial, p)
      setLoading(false)
      if (res.ok) { setEntries(res.data.entries); setFsVia(res.data.transport) }
      else { setEntries([]); toast('bad', res.error) }
    },
    [serial, path, toast, connected, fsLink]
  )

  useEffect(() => {
    if (!serial) return
    setPath(ROOT)
    window.droid.info(serial).then((r) => r.ok && setInfo(r.data))
  }, [serial])

  useEffect(() => { if (!connected && fsLink) setPath(ROOT) }, [connected, fsLink])
  useEffect(() => { if (connected || fsLink) refreshList(path) }, [serial, path, connected, fsLink])

  const enter    = (name) => setPath((p) => (p === '/' ? `/${name}` : `${p}/${name}`))
  const up       = () => setPath((p) => p.split('/').slice(0, -1).join('/') || '/')
  const jumpTo   = (idx) => setPath('/' + path.split('/').filter(Boolean).slice(0, idx + 1).join('/'))

  const withFlag = async (key, fn) => {
    setBusy((b) => ({ ...b, [key]: true }))
    try { await fn() } finally { setBusy((b) => ({ ...b, [key]: false })) }
  }

  const download = (entry) =>
    withFlag(`dl:${entry.name}`, async () => {
      const remote = path === '/' ? `/${entry.name}` : `${path}/${entry.name}`
      const res = await window.droid.pull(serial, remote, entry.name)
      res.ok ? toast('ok', `Saved — ${entry.name}`) : toast('bad', res.error)
    })

  const upload = () =>
    withFlag('upload', async () => {
      const res = await window.droid.pushDialog(serial)
      if (!res.ok) return toast('bad', res.error)
      if (res.data) {
        toast('ok', `Sent ${res.data.count} file(s) to ${res.data.remoteDir}`)
        if (path.startsWith('/sdcard/Download')) refreshList()
      }
    })

  const dropFiles = (fileList) =>
    withFlag('upload', async () => {
      const paths = [...fileList].map((f) => window.droid.filePath(f)).filter(Boolean)
      if (paths.length === 0) return
      const res = await window.droid.pushPaths(serial, paths)
      if (!res.ok) return toast('bad', res.error)
      toast('ok', `Sent ${res.data.count} file(s) to ${res.data.remoteDir}`)
      if (path.startsWith('/sdcard/Download')) refreshList()
    })

  const remotePathOf = (entry) => path === '/' ? `/${entry.name}` : `${path}/${entry.name}`

  const renameEntry = async (entry, newName) => {
    const res = await window.droid.fsRename(remotePathOf(entry), newName)
    if (res.ok) { toast('ok', `Renamed to ${newName}`); refreshList() }
    else toast('bad', res.error)
  }

  const deleteEntry = (entry) =>
    withFlag(`dl:${entry.name}`, async () => {
      const what = entry.dir ? 'folder' : 'file'
      if (!window.confirm(`Delete this ${what} from your phone?\n\n${entry.name}`)) return
      const res = await window.droid.fsDelete(remotePathOf(entry))
      if (res.ok) { toast('ok', `Deleted ${entry.name}`); refreshList() }
      else toast('bad', res.error)
    })

  const screenshot = () =>
    withFlag('shot', async () => {
      const res = await window.droid.screenshot(serial)
      res.ok ? toast('ok', 'Screenshot saved to Downloads') : toast('bad', res.error)
    })

  const mirror = async () => {
    if (!tools?.scrcpy) return setSetup({ reason: 'Screen mirroring needs scrcpy.' })
    const res = await window.droid.mirror(serial)
    if (!res.ok) toast('bad', res.error)
  }

  const goWireless = () =>
    withFlag('wireless', async () => {
      const res = await window.droid.goWireless(serial)
      res.ok
        ? toast('ok', `Wireless ADB on — ${res.data}. You can unplug the cable.`)
        : toast('bad', res.error)
    })

  const reconnect = () =>
    withFlag('reconnect', async () => {
      const res = await window.droid.reconnectNow()
      res.ok ? toast('ok', `Reconnected — ${res.data}`) : toast('bad', res.error)
    })

  const unpair = () =>
    withFlag('unpair', async () => {
      const res = await window.droid.unpair()
      if (res.ok) { setPairedGuid(null); toast('ok', 'Forgot this phone.') }
      else toast('bad', res.error)
    })

  const camera = async () => {
    if (!tools?.scrcpy) return setSetup({ reason: 'Phone camera needs scrcpy.' })
    const res = await window.droid.camera(serial)
    if (!res.ok) toast('bad', res.error)
  }

  const notifCount = notifs.length
  const tabLabel = (id, base) =>
    id === 'notifications' && notifCount > 0 ? `${base} ${notifCount}` : base

  return (
    <div className="flex h-screen flex-col overflow-hidden">
      {/* ── Titlebar ──────────────────────────────────────────────── */}
      <header
        className="drag relative flex h-11 shrink-0 items-center justify-between border-b border-line bg-panel pl-[76px] pr-4"
        style={{ boxShadow: 'inset 0 -1px 0 rgba(0,0,0,0.18), inset 0 1px 0 rgba(255,255,255,0.04)' }}
      >
        {/* Left: brand */}
        <div className="flex items-center gap-2">
          <span
            className={`h-[7px] w-[7px] rounded-full transition-all duration-500 ${
              connected ? 'bg-amber led' : wifi?.connected ? 'bg-ok/70' : 'bg-dim/35'
            }`}
          />
          <span className="font-display text-[13px] font-semibold tracking-[0.04em] text-fg/90">
            DroidDock
          </span>
          {(connected || wifi?.connected) && (
            <span className="font-mono text-[10px] text-dim/60">
              {connected
                ? (info?.model || connected.model)
                : wifi?.phoneName || 'linked'}
            </span>
          )}
        </div>

        {/* Right: tool chips */}
        {tools && (
          <div className="no-drag flex items-center gap-1.5">
            <ToolChip label="adb"    on={tools.adb}    onClick={() => setSetup({ reason: null })} />
            <ToolChip label="scrcpy" on={tools.scrcpy} onClick={() => setSetup({ reason: null })} />
          </div>
        )}
      </header>

      <div className="flex min-h-0 flex-1">
        {/* ── Sidebar ─────────────────────────────────────────────── */}
        <Sidebar
          device={connected}
          info={info}
          appInfo={appInfo}
          busy={busy}
          scrcpy={tools?.scrcpy}
          wifi={wifi}
          media={media}
          onMediaCmd={(cmd, value) => window.droid.mediaCmd(cmd, value)}
          onPair={() => setPairOpen(true)}
          onToggleNotif={async () => setWifi(await window.droid.wifiToggleNotif())}
          onMirror={mirror}
          onWireless={goWireless}
          onPairWireless={() => setWPairOpen(true)}
          onUnpair={unpair}
          onReconnect={reconnect}
          paired={!!pairedGuid}
          onCamera={camera}
          onScreenshot={screenshot}
          onUpload={upload}
          onOpenDownloads={() => window.droid.openDownloads()}
        />

        {/* ── Main area ───────────────────────────────────────────── */}
        <main className="gridbg relative flex min-w-0 flex-1 flex-col overflow-hidden">
          {/* Tab bar */}
          <div
            className="flex h-10 shrink-0 items-stretch gap-0 overflow-x-auto border-b border-line bg-ink/70 px-3"
            style={{ scrollbarWidth: 'none' }}
          >
            {TABS.map(([id, label]) => {
              const active = view === id
              return (
                <button
                  key={id}
                  onClick={() => setView(id)}
                  className={`no-drag relative shrink-0 px-3.5 py-0 font-mono text-[10.5px] font-medium tracking-[0.05em] transition-colors duration-150 ${
                    active ? 'text-amber' : 'text-dim hover:text-fg/80'
                  }`}
                >
                  {tabLabel(id, label)}
                  {active && (
                    <span
                      className="tab-line absolute bottom-0 left-2 right-2 h-[2px] rounded-t-full bg-amber"
                    />
                  )}
                  {id === 'notifications' && notifCount > 0 && !active && (
                    <span className="absolute right-1.5 top-2 h-1.5 w-1.5 rounded-full bg-amber/70" />
                  )}
                </button>
              )
            })}
          </div>

          {/* View content */}
          <div className="min-h-0 flex-1">
            {view === 'messages' ? (
              <MessagesView linked={!!wifi?.connected} onToast={toast} target={messageTarget} />
            ) : view === 'contacts' ? (
              <ContactsView
                linked={!!wifi?.connected}
                onToast={toast}
                onCall={(contact) => {
                  setActiveCall((prev) => ({
                    ...prev,
                    state: 'RINGING',
                    number: contact.number,
                    name: contact.name || contact.number,
                  }))
                }}
                onOpenSms={(contact) => { setMessageTarget(contact); setView('messages') }}
              />
            ) : view === 'notifications' ? (
              <NotificationsView
                linked={!!wifi?.connected}
                items={notifs}
                onClear={() => setNotifs([])}
                onDismiss={(key) => {
                  window.droid.notifDismiss(key)
                  setNotifs((l) => l.filter((x) => x.key !== key))
                }}
                onToast={toast}
              />
            ) : view === 'settings' ? (
              <SettingsView onToast={toast} />
            ) : view === 'photos' ? (
              <PhotosView available={fsAvailable} onToast={toast} />
            ) : view === 'screen' ? (
              <MirrorView linked={!!wifi?.connected} onToast={toast} />
            ) : fsAvailable ? (
              <FileBrowser
                path={path}
                entries={entries}
                loading={loading}
                busy={busy}
                transport={fsVia}
                prog={prog}
                onCancel={(tid) => window.droid.fsCancel(tid)}
                onEnter={enter}
                onUp={up}
                onJump={jumpTo}
                onRefresh={() => refreshList()}
                onDownload={download}
                onUpload={upload}
                onDrop={dropFiles}
                onRename={renameEntry}
                onDelete={deleteEntry}
              />
            ) : (
              <WaitingState
                unauthorized={unauthorized}
                onLinkApp={() => setPairOpen(true)}
                onAdvanced={() => setWPairOpen(true)}
              />
            )}
          </div>
        </main>
      </div>

      {/* ── Overlays ──────────────────────────────────────────────── */}
      <Toasts items={toasts} />

      {setup && (
        <SetupModal
          tools={tools}
          reason={setup.reason}
          onClose={() => setSetup(null)}
          onInstallScrcpy={() => window.droid.installScrcpy()}
        />
      )}
      {pairOpen && wifi && <PairingModal status={wifi} onClose={() => setPairOpen(false)} />}
      {wPairOpen && (
        <WirelessPairModal
          onClose={() => setWPairOpen(false)}
          onPaired={() => window.droid.pairedInfo().then((r) => r.ok && setPairedGuid(r.data.guid))}
          onToast={toast}
        />
      )}
      {activeCall && (
        <CallOverlay call={activeCall} onDismiss={() => setActiveCall(null)} onToast={toast} />
      )}
      <div className="grain" />
    </div>
  )
}

function ToolChip({ label, on, onClick }) {
  return (
    <button
      onClick={onClick}
      title={on ? `${label} detected` : `${label} not found — click to install`}
      className={`flex items-center gap-1.5 rounded-md px-2 py-1 font-mono text-[9px] tracking-[0.06em] transition-all duration-150 ${
        on
          ? 'bg-amber/8 text-amber/80 hover:bg-amber/15 hover:text-amber'
          : 'text-dim/60 hover:bg-panel2 hover:text-dim'
      }`}
    >
      <span className={`h-1 w-1 rounded-full ${on ? 'bg-amber' : 'bg-dim/40'}`} />
      {label}
    </button>
  )
}
