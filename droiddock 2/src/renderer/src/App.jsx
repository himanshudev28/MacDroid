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
import DashboardView from './components/DashboardView.jsx'
import DevicesView from './components/DevicesView.jsx'
import CameraView from './components/CameraView.jsx'
import MediaView from './components/MediaView.jsx'
import ClipboardView from './components/ClipboardView.jsx'
import CallsView from './components/CallsView.jsx'

const ROOT = '/sdcard'

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
  const [view, setView] = useState('dashboard')
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
    const offWifi = window.droid.onWifi((s) => { setWifi(s); if (!s.connected) setProg(null) })
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
        if (p.dir === 'phone') toast('ok', `${p.name} saved to Downloads`)
      } else if (p.started && p.dir === 'phone') {
        toast('info', `Receiving ${p.name} from phone…`)
        setProg(p)
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

  const enter  = (name) => setPath((p) => (p === '/' ? `/${name}` : `${p}/${name}`))
  const up     = () => setPath((p) => p.split('/').slice(0, -1).join('/') || '/')
  const jumpTo = (idx) => setPath('/' + path.split('/').filter(Boolean).slice(0, idx + 1).join('/'))

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

  const mirrorWifi = async () => {
    const r = await window.droid.mirrorPopout('screen')
    if (!r.ok) toast('bad', r.error)
    else toast('info', 'Approve screen capture on your phone…')
  }

  const cameraWifi = async () => {
    const r = await window.droid.mirrorPopout('camera')
    if (!r.ok) toast('bad', r.error)
    else toast('info', 'Allow the camera on your phone…')
  }

  const notifCount = notifs.length

  const renderView = () => {
    switch (view) {
      case 'dashboard':
        return (
          <DashboardView
            connected={connected}
            wifi={wifi}
            appInfo={appInfo}
            media={media}
            notifs={notifs}
            prog={prog}
            onPair={() => setPairOpen(true)}
            onMirrorWifi={mirrorWifi}
            onCameraWifi={cameraWifi}
            onUpload={upload}
            onOpenDownloads={() => window.droid.openDownloads()}
          />
        )
      case 'devices':
        return (
          <DevicesView
            connected={connected}
            info={info}
            appInfo={appInfo}
            wifi={wifi}
            tools={tools}
            busy={busy}
            paired={!!pairedGuid}
            onPair={() => setPairOpen(true)}
            onWireless={goWireless}
            onPairWireless={() => setWPairOpen(true)}
            onUnpair={unpair}
            onReconnect={reconnect}
            onScreenshot={screenshot}
            onToast={toast}
          />
        )
      case 'notifications':
        return (
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
        )
      case 'messages':
        return (
          <MessagesView linked={!!wifi?.connected} onToast={toast} target={messageTarget} />
        )
      case 'calls':
        return <CallsView linked={!!wifi?.connected} />
      case 'contacts':
        return (
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
        )
      case 'clipboard':
        return <ClipboardView linked={!!wifi?.connected} onToast={toast} />
      case 'files':
        return fsAvailable ? (
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
        )
      case 'photos':
        return <PhotosView available={fsAvailable} onToast={toast} />
      case 'camera':
        return (
          <CameraView
            linked={!!wifi?.connected}
            connected={connected}
            scrcpy={tools?.scrcpy}
            busy={busy}
            onCameraWifi={cameraWifi}
            onCameraAdb={camera}
            onToast={toast}
          />
        )
      case 'media':
        return (
          <MediaView
            media={media}
            onCmd={(cmd, value) => window.droid.mediaCmd(cmd, value)}
          />
        )
      case 'screen':
        return (
          <MirrorView
            linked={!!wifi?.connected}
            connected={connected}
            scrcpy={tools?.scrcpy}
            busy={busy}
            onMirrorAdb={mirror}
            onToast={toast}
          />
        )
      case 'settings':
        return <SettingsView onToast={toast} />
      default:
        return null
    }
  }

  return (
    <div className="flex h-screen overflow-hidden bg-ink">
      <Sidebar
        view={view}
        setView={setView}
        connected={connected}
        wifi={wifi}
        appInfo={appInfo}
        notifCount={notifCount}
      />

      <main className="gridbg relative flex min-w-0 flex-1 flex-col overflow-hidden">
        {prog && prog.dir === 'phone' && (
          <div className="shrink-0 flex items-center gap-3 border-b border-amber/20 bg-amber/5 px-4 py-1.5">
            <span className="shrink-0 font-mono text-[10px] text-amber/80">
              Receiving {prog.name}
            </span>
            <div className="h-0.5 flex-1 overflow-hidden rounded-full bg-amber/15">
              <div
                className="h-full rounded-full bg-amber/70 transition-all duration-300"
                style={{ width: `${prog.total ? Math.round((prog.sent / prog.total) * 100) : 0}%` }}
              />
            </div>
            <span className="shrink-0 font-mono text-[10px] text-dim/60">
              {prog.total ? `${Math.round((prog.sent / prog.total) * 100)}%` : '…'}
            </span>
          </div>
        )}

        <div className="min-h-0 flex-1 overflow-hidden">
          {renderView()}
        </div>
      </main>

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
