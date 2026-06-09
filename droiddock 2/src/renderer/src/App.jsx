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

export default function App() {
  const [tools, setTools] = useState(null)
  const [devices, setDevices] = useState([])
  const [serial, setSerial] = useState(null)
  const [info, setInfo] = useState(null)
  const [path, setPath] = useState(ROOT)
  const [entries, setEntries] = useState([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState({}) // per-action flags
  const [toasts, setToasts] = useState([])
  const [wifi, setWifi] = useState(null)
  const [pairOpen, setPairOpen] = useState(false)
  const [setup, setSetup] = useState(null) // { reason } when a needed tool is missing
  const [wPairOpen, setWPairOpen] = useState(false)
  const [pairedGuid, setPairedGuid] = useState(null)
  const [view, setView] = useState('files')
  const [media, setMedia] = useState(null)
  const [notifs, setNotifs] = useState([])
  const [appInfo, setAppInfo] = useState(null) // device-info via app link
  const [fsVia, setFsVia] = useState(null) // 'adb' | 'link' — transport that served the last fs op
  const [prog, setProg] = useState(null) // live transfer progress
  const [messageTarget, setMessageTarget] = useState(null) // contact to open in Messages
  const [activeCall, setActiveCall] = useState(null)       // { state, number, name } | null
  const toastId = useRef(0)

  const toast = useCallback((kind, text) => {
    const id = ++toastId.current
    setToasts((t) => [...t, { id, kind, text }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4200)
  }, [])

  const connected = useMemo(
    () => devices.find((d) => d.serial === serial && d.state === 'device') || null,
    [devices, serial]
  )
  const unauthorized = useMemo(() => devices.some((d) => d.state === 'unauthorized'), [devices])
  const fsLink = useMemo(() => !!wifi?.connected && (wifi?.caps || []).includes('fs'), [wifi])
  const fsAvailable = !!connected || fsLink

  /* boot: tool detection + device + wifi subscriptions */
  useEffect(() => {
    window.droid.tools().then(setTools)
    const offTools = window.droid.onTools(setTools) // adb may come online after auto-download
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
    const offProg = window.droid.onTransferProgress((p) =>
      setProg(p.done ? null : p)
    )
    const offCallState = window.droid.onCallState(({ state, serial }) => {
      setActiveCall((prev) => {
        if (state === 'IDLE') return null
        return { state, serial, number: prev?.number ?? '', name: prev?.name ?? '' }
      })
    })
    return () => {
      offTools()
      offDevices()
      offWifi()
      offWifiEvent()
      offMedia()
      offNotif()
      offNotifGone()
      offInfo()
      offProg()
      offCallState()
    }
  }, [toast])

  /* drop the app-link device info when the phone link drops */
  useEffect(() => {
    if (!wifi?.connected) setAppInfo(null)
  }, [wifi?.connected])

  /* auto-select first ready device */
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
      if (res.ok) {
        setEntries(res.data.entries)
        setFsVia(res.data.transport)
      } else {
        setEntries([])
        toast('bad', res.error)
      }
    },
    [serial, path, toast, connected, fsLink]
  )

  /* on device select: load info + reset browser to root */
  useEffect(() => {
    if (!serial) return
    setPath(ROOT)
    window.droid.info(serial).then((r) => r.ok && setInfo(r.data))
  }, [serial])

  /* reset to root when the available transport changes (ADB ↔ app link) */
  useEffect(() => {
    if (!connected && fsLink) setPath(ROOT)
  }, [connected, fsLink])

  useEffect(() => {
    if (connected || fsLink) refreshList(path)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serial, path, connected, fsLink])

  /* actions */
  const enter = (name) => setPath((p) => (p === '/' ? `/${name}` : `${p}/${name}`))
  const up = () => setPath((p) => p.split('/').slice(0, -1).join('/') || '/')
  const jumpTo = (idx) =>
    setPath('/' + path.split('/').filter(Boolean).slice(0, idx + 1).join('/'))

  const withFlag = async (key, fn) => {
    setBusy((b) => ({ ...b, [key]: true }))
    try {
      await fn()
    } finally {
      setBusy((b) => ({ ...b, [key]: false }))
    }
  }

  const download = (entry) =>
    withFlag(`dl:${entry.name}`, async () => {
      const remote = path === '/' ? `/${entry.name}` : `${path}/${entry.name}`
      const res = await window.droid.pull(serial, remote, entry.name)
      res.ok ? toast('ok', `Saved to Downloads — ${entry.name}`) : toast('bad', res.error)
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

  const remotePathOf = (entry) =>
    path === '/' ? `/${entry.name}` : `${path}/${entry.name}`

  const renameEntry = async (entry, newName) => {
    const res = await window.droid.fsRename(remotePathOf(entry), newName)
    if (res.ok) {
      toast('ok', `Renamed to ${newName}`)
      refreshList()
    } else {
      toast('bad', res.error)
    }
  }

  const deleteEntry = (entry) =>
    withFlag(`dl:${entry.name}`, async () => {
      const what = entry.dir ? 'folder' : 'file'
      if (!window.confirm(`Delete this ${what} from your phone?\n\n${entry.name}`)) return
      const res = await window.droid.fsDelete(remotePathOf(entry))
      if (res.ok) {
        toast('ok', `Deleted ${entry.name}`)
        refreshList()
      } else {
        toast('bad', res.error)
      }
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
      res.ok
        ? toast('ok', `Reconnected — ${res.data}`)
        : toast('bad', res.error)
    })

  const unpair = () =>
    withFlag('unpair', async () => {
      const res = await window.droid.unpair()
      if (res.ok) {
        setPairedGuid(null)
        toast('ok', 'Forgot this phone. It will no longer auto-connect over Wi-Fi.')
      } else {
        toast('bad', res.error)
      }
    })

  const camera = async () => {
    if (!tools?.scrcpy) return setSetup({ reason: 'Phone camera needs scrcpy.' })
    const res = await window.droid.camera(serial)
    if (!res.ok) toast('bad', res.error)
  }

  return (
    <div className="flex h-screen flex-col">
      {/* titlebar */}
      <header className="drag flex h-11 shrink-0 items-center justify-between border-b border-line bg-panel pl-20 pr-4">
        <div className="flex items-center gap-2.5">
          <span
            className={`h-2 w-2 rounded-full ${connected ? 'led bg-amber' : 'bg-dim/40'}`}
          />
          <span className="font-display text-[13px] font-semibold tracking-[0.22em] text-fg">
            DROIDDOCK
          </span>
          <span className="font-mono text-[10px] tracking-widest text-dim">PHASE 5 / FULL SUITE</span>
        </div>
        {tools && (
          <div className="no-drag flex items-center gap-2 font-mono text-[10px] tracking-wider">
            <Chip label="ADB" on={tools.adb} onClick={() => setSetup({ reason: null })} />
            <Chip label="SCRCPY" on={tools.scrcpy} onClick={() => setSetup({ reason: null })} />
          </div>
        )}
      </header>

      <div className="flex min-h-0 flex-1">
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

        <main className="gridbg relative flex min-w-0 flex-1 flex-col overflow-hidden">
          <div className="flex h-10 shrink-0 items-end overflow-x-auto border-b border-line bg-ink/60 px-4">
            {[
              ['files', 'FILES'],
              ['photos', 'PHOTOS'],
              ['screen', 'SCREEN'],
              ['messages', 'MESSAGES'],
              ['contacts', 'CONTACTS'],
              ['notifications', `NOTIFS${notifs.length ? ` · ${notifs.length}` : ''}`],
              ['settings', 'SETTINGS']
            ].map(([id, label]) => (
              <button
                key={id}
                onClick={() => setView(id)}
                className={`shrink-0 border-b-2 px-4 py-2 font-display text-[11px] font-semibold tracking-[0.2em] transition-colors ${
                  view === id
                    ? 'border-amber text-amber'
                    : 'border-transparent text-dim hover:text-fg'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="min-h-0 flex-1">
            {view === 'messages' ? (
              <MessagesView linked={!!wifi?.connected} onToast={toast} target={messageTarget} />
            ) : view === 'contacts' ? (
              <ContactsView
                linked={!!wifi?.connected}
                onToast={toast}
                onCall={(contact) => {
                  // Capture contact info so the overlay shows the name
                  setActiveCall((prev) => ({
                    ...prev,
                    state: 'RINGING',
                    number: contact.number,
                    name: contact.name || contact.number,
                  }))
                }}
                onOpenSms={(contact) => {
                  setMessageTarget(contact)
                  setView('messages')
                }}
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
        <CallOverlay
          call={activeCall}
          onDismiss={() => setActiveCall(null)}
          onToast={toast}
        />
      )}
      <div className="grain" />
    </div>
  )
}

function Chip({ label, on, onClick }) {
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-1.5 rounded-sm border px-2 py-0.5 transition-colors hover:border-amber/50 ${
        on ? 'border-amber/30 text-amber' : 'border-line text-dim'
      }`}
      title={on ? `${label} detected — click for setup` : `${label} not found — click to install`}
    >
      <span className={`h-1 w-1 rounded-full ${on ? 'bg-amber' : 'bg-dim/50'}`} />
      {label}
    </button>
  )
}

function AdbMissing() {
  return (
    <div className="flex h-full items-center justify-center p-8">
      <div className="w-full max-w-md border border-line bg-panel p-8">
        <p className="font-display text-sm font-semibold tracking-[0.2em] text-bad">
          ADB NOT FOUND
        </p>
        <p className="mt-3 text-sm leading-relaxed text-dim">
          DroidDock drives your phone through Android platform tools. Install them, then
          relaunch the app:
        </p>
        <code className="mt-4 block border border-line bg-ink px-3 py-2 font-mono text-xs text-amber">
          brew install --cask android-platform-tools
        </code>
      </div>
    </div>
  )
}
