import { app, BrowserWindow, ipcMain, dialog, shell, nativeImage } from 'electron'
import { join } from 'node:path'
import { mkdirSync } from 'node:fs'
import * as adb from './adb'
import * as wifi from './wifi'

let win = null
const tools = { adb: null, scrcpy: null }
let devices = []
let tracker = null // adb track-devices controller

const ok = (data) => ({ ok: true, data })
const fail = (e) => ({ ok: false, error: String((e && e.message) || e) })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const serialIdCache = new Map() // adb serial -> ro.serialno (stable hardware id)

/** Stable hardware id for grouping; falls back to the adb serial if unreadable. */
async function groupId(d) {
  if (d.state !== 'device') return d.serial
  if (serialIdCache.has(d.serial)) return serialIdCache.get(d.serial)
  const id = await adb.serialNo(tools.adb, d.serial).catch(() => d.serial)
  serialIdCache.set(d.serial, id)
  return id
}

/** Collapse a USB serial + its wireless ip:port into one card per physical phone.
 *  Auto transport: prefer the USB serial when present, else the wireless one. */
async function buildDeviceList(raw) {
  const groups = new Map()
  for (const d of raw) {
    const id = await groupId(d)
    const g = groups.get(id) || []
    g.push(d)
    groups.set(id, g)
  }
  return [...groups.entries()].map(([id, g]) => {
    const usb = g.find((d) => !d.wireless)
    // prefer a clean ip:port wireless serial over the mDNS-instance-name form
    const wl = g.find((d) => d.wireless && /:\d+$/.test(d.serial)) || g.find((d) => d.wireless)
    const active = usb || wl
    return {
      id, // stable hardware id (ro.serialno) — same phone across transports
      serial: active.serial,
      model: active.model,
      state: active.state,
      transport: active.wireless ? 'wifi' : 'usb',
      usbSerial: usb?.serial ?? null,
      wifiSerial: wl?.serial ?? null
    }
  })
}

// A scrcpy mirror is bound to one adb serial; it can't follow a USB↔Wi-Fi
// switch, so we respawn it on the new serial when the active transport flips.
let mirrorState = null // { child, serial, id, diedAt }
const MIRROR_GRACE_MS = 10000

function startMirror(serial, id) {
  const child = adb.mirror(tools.scrcpy, serial, tools.adb)
  mirrorState = { child, serial, id, diedAt: 0 }
  child.on('exit', () => {
    if (mirrorState && mirrorState.child === child) {
      mirrorState.child = null
      mirrorState.diedAt = Date.now()
    }
  })
}

function syncMirrorTransport(next) {
  if (!mirrorState || !mirrorState.id || !tools.scrcpy) return
  const card = next.find((d) => d.id === mirrorState.id && d.state === 'device')
  const alive = mirrorState.child && !mirrorState.child.killed
  const recentlyDied =
    !alive && mirrorState.diedAt && Date.now() - mirrorState.diedAt < MIRROR_GRACE_MS
  if (card && card.serial !== mirrorState.serial && (alive || recentlyDied)) {
    if (alive) {
      try {
        mirrorState.child.kill()
      } catch {
        /* already gone */
      }
    }
    startMirror(card.serial, mirrorState.id)
    if (win && !win.isDestroyed()) {
      const where = card.transport === 'wifi' ? 'Wi-Fi' : 'USB'
      win.webContents.send('wifi-event', { kind: 'info', text: `Mirror restarted over ${where}` })
    }
  } else if (!alive && !recentlyDied) {
    mirrorState = null
  }
}

const hasLiveDevice = () => devices.some((d) => d.state === 'device')

let prevHasAdb = null // null = first snapshot (no toast)

/** Handle a device-list snapshot from `adb track-devices` (event-driven, C1). */
async function onDeviceList(raw0) {
  // Drop 'offline' transports — dead adb leftovers that can't be grouped by
  // ro.serialno (getprop needs a live device).
  const raw = raw0.filter((d) => d.state !== 'offline')
  for (const s of [...serialIdCache.keys()]) {
    if (!raw.some((d) => d.serial === s)) serialIdCache.delete(s)
  }
  const next = await buildDeviceList(raw)
  if (JSON.stringify(next) !== JSON.stringify(devices)) {
    devices = next
    if (win && !win.isDestroyed()) win.webContents.send('devices', devices)
  }
  syncMirrorTransport(next)

  // B5: toast once when ADB availability flips (not on the first snapshot).
  const adbNow = next.some((d) => d.state === 'device')
  if (prevHasAdb !== null && adbNow !== prevHasAdb && win && !win.isDestroyed()) {
    win.webContents.send('wifi-event', {
      kind: adbNow ? 'ok' : 'info',
      text: adbNow
        ? 'ADB connected — full features enabled'
        : 'ADB disconnected — running over app link'
    })
  }
  prevHasAdb = adbNow

  // Any device/network event resets the mDNS scan cadence and re-evaluates it.
  mdnsInterval = MDNS_MIN
  idleSince = Date.now()
  scheduleMdns()
}

/* ---- mDNS reconnect scheduler (C2): only while zero devices, 10s→30s backoff ---- */
const MDNS_MIN = 10000
const MDNS_MAX = 30000
let mdnsTimer = null
let mdnsInterval = MDNS_MIN
let idleSince = 0
let appLinkPaused = false // phone tapped "Pause" → stop reconnect attempts until it returns

function scheduleMdns() {
  if (mdnsTimer) clearTimeout(mdnsTimer)
  mdnsTimer = null
  if (appLinkPaused) return // phone asked us to stop trying
  if (hasLiveDevice()) return // never scan while a device is connected
  mdnsTimer = setTimeout(runMdnsScan, mdnsInterval)
}

async function runMdnsScan() {
  if (!tools.adb) return scheduleMdns()
  if (!hasLiveDevice() && wifi.getCfg('autoReconnect') !== false) {
    const guid = wifi.getCfg('deviceGuid')
    const tcpAddr = wifi.getCfg('tcpAddr')
    if (guid) await adb.connectByGuid(tools.adb, guid).catch(() => {})
    else if (tcpAddr) await adb.connectTcp(tools.adb, tcpAddr)
  }
  // Back off to 30s after 5 idle minutes; a device/network event resets it.
  if (Date.now() - idleSince > 5 * 60 * 1000) mdnsInterval = MDNS_MAX
  scheduleMdns()
}

function createWindow() {
  win = new BrowserWindow({
    width: 1120,
    height: 720,
    minWidth: 920,
    minHeight: 600,
    show: false,
    backgroundColor: '#0b0d10',
    titleBarStyle: 'hiddenInset',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js')
    }
  })

  win.once('ready-to-show', () => win.show())

  if (process.env['ELECTRON_RENDERER_URL']) {
    win.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

/** Bring adb online: start its server + device tracker, and tell the renderer. */
async function activateAdb() {
  if (!tools.adb) return
  await adb.startServer(tools.adb)
  if (!tracker) tracker = adb.trackDevices(tools.adb, onDeviceList)
  scheduleMdns()
  if (win && !win.isDestroyed()) {
    win.webContents.send('tools', { adb: !!tools.adb, scrcpy: !!tools.scrcpy })
  }
}

/** Resolve adb, falling back to a cached/auto-downloaded copy so the user never has
 *  to install platform-tools by hand. Runs in the background; non-blocking. */
async function ensureAdb() {
  if (tools.adb) return activateAdb()
  const toolsDir = join(app.getPath('userData'), 'tools')
  tools.adb = adb.bundledAdb(toolsDir) // previously downloaded?
  if (tools.adb) return activateAdb()
  const emit = (kind, text) =>
    win && !win.isDestroyed() && win.webContents.send('wifi-event', { kind, text })
  try {
    emit('info', 'Setting up adb (one-time, ~5 MB)…')
    tools.adb = await adb.downloadAdb(toolsDir, () => {})
    emit('ok', 'adb ready — full features enabled')
    await activateAdb()
  } catch (e) {
    emit('bad', `Couldn't auto-install adb: ${String(e.message || e)}. Wi-Fi features still work.`)
  }
}

app.whenReady().then(async () => {
  tools.adb = await adb.resolveTool('adb')
  tools.scrcpy = await adb.resolveTool('scrcpy')

  createWindow()
  // Wi-Fi app-link features work with no adb at all; bring adb online in the
  // background (resolve → cached → auto-download) for the ADB-only extras.
  ensureAdb()
  scheduleMdns()

  wifi.start({
    statusCb: (s) => {
      // phone reconnected after a pause → clear the pause and resume scanning
      if (s.connected && appLinkPaused) {
        appLinkPaused = false
        scheduleMdns()
      }
      if (win && !win.isDestroyed()) win.webContents.send('wifi', s)
    },
    eventCb: (ev) => {
      if (win && !win.isDestroyed()) win.webContents.send('wifi-event', ev)
    },
    forwardCb: (channel, payload) => {
      if (channel === 'pause') {
        appLinkPaused = true
        if (mdnsTimer) {
          clearTimeout(mdnsTimer)
          mdnsTimer = null
        }
        if (win && !win.isDestroyed())
          win.webContents.send('wifi-event', {
            kind: 'info',
            text: 'Phone paused — reconnect attempts stopped'
          })
        return
      }
      if (channel === 'resume') {
        appLinkPaused = false
        scheduleMdns()
        return
      }
      if (win && !win.isDestroyed()) win.webContents.send(channel, payload)
    }
  })

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  tracker?.stop()
  if (mdnsTimer) clearTimeout(mdnsTimer)
  wifi.stop()
  app.quit()
})

/* ---------------- IPC ---------------- */

ipcMain.handle('tools', () => ({
  adb: !!tools.adb,
  scrcpy: !!tools.scrcpy,
  adbPath: tools.adb,
  scrcpyPath: tools.scrcpy
}))

ipcMain.handle('devices:get', () => devices)

ipcMain.handle('device:info', async (_e, serial) => {
  try {
    return ok(await adb.deviceInfo(tools.adb, serial))
  } catch (e) {
    return fail(e)
  }
})

// B1 transport resolver — evaluated LIVE per action: ADB device → ADB, else app link.
function fsTransport() {
  const dev = devices.find((d) => d.state === 'device')
  if (dev) return { kind: 'adb', serial: dev.serial }
  if (wifi.hasCap('fs')) return { kind: 'link' }
  return { kind: 'none' }
}
const emitProgress = (p) => {
  if (win && !win.isDestroyed()) win.webContents.send('transfer-progress', p)
}
const fname = (p) => p.split('/').pop()

async function pushViaLink(paths) {
  const dest = '/sdcard/Download/'
  for (const p of paths) {
    const name = fname(p)
    await wifi.fsPush(p, dest, (sent, total, tid) =>
      emitProgress({ tid, name, sent, total, dir: 'push' })
    )
    emitProgress({ name, done: true, dir: 'push' })
  }
  return { count: paths.length, remoteDir: dest }
}

ipcMain.handle('fs:list', async (_e, serial, dirPath) => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb')
      return ok({ entries: await adb.listDir(tools.adb, t.serial, dirPath), transport: 'adb' })
    if (t.kind === 'link') return ok({ entries: await wifi.fsList(dirPath), transport: 'link' })
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('fs:pull', async (_e, serial, remotePath, name) => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb') {
      const dest = await adb.pull(tools.adb, t.serial, remotePath, app.getPath('downloads'))
      shell.showItemInFolder(dest)
      return ok({ dest, transport: 'adb' })
    }
    if (t.kind === 'link') {
      const label = name || fname(remotePath)
      const dest = await wifi.fsPull(remotePath, app.getPath('downloads'), (sent, total, tid) =>
        emitProgress({ tid, name: label, sent, total, dir: 'pull' })
      )
      emitProgress({ name: label, done: true, dir: 'pull' })
      shell.showItemInFolder(dest)
      return ok({ dest, transport: 'link' })
    }
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('fs:pushDialog', async (_e, serial) => {
  try {
    const res = await dialog.showOpenDialog(win, {
      title: 'Send to phone',
      properties: ['openFile', 'multiSelections']
    })
    if (res.canceled || res.filePaths.length === 0) return ok(null)
    const t = fsTransport()
    if (t.kind === 'adb')
      return ok({ ...(await adb.pushPaths(tools.adb, t.serial, res.filePaths)), transport: 'adb' })
    if (t.kind === 'link') return ok({ ...(await pushViaLink(res.filePaths)), transport: 'link' })
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('fs:pushPaths', async (_e, serial, paths) => {
  try {
    if (!paths || paths.length === 0) return ok(null)
    const t = fsTransport()
    if (t.kind === 'adb')
      return ok({ ...(await adb.pushPaths(tools.adb, t.serial, paths)), transport: 'adb' })
    if (t.kind === 'link') return ok({ ...(await pushViaLink(paths)), transport: 'link' })
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('fs:cancel', (_e, transferId) => {
  wifi.fsCancel(transferId)
  return ok(true)
})

ipcMain.handle('fs:delete', async (_e, remotePath) => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb') {
      await adb.rm(tools.adb, t.serial, remotePath)
      return ok(true)
    }
    if (t.kind === 'link') {
      await wifi.fsDelete(remotePath)
      return ok(true)
    }
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('fs:rename', async (_e, remotePath, newName) => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb') return ok({ newPath: await adb.rename(tools.adb, t.serial, remotePath, newName) })
    if (t.kind === 'link') return ok({ newPath: await wifi.fsRename(remotePath, newName) })
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('shot', async (_e, serial) => {
  try {
    const dest = await adb.screenshot(tools.adb, serial, app.getPath('downloads'))
    shell.showItemInFolder(dest)
    return ok(dest)
  } catch (e) {
    return fail(e)
  }
})

// Android 11+ Wireless Debugging: pair with a code, then connect cable-free.
ipcMain.handle('adb:pair', async (_e, hostPort, code) => {
  try {
    const guid = await adb.pairWireless(tools.adb, hostPort, code)
    wifi.setCfg('deviceGuid', guid)
    let addr = null
    for (let i = 0; i < 6 && !addr; i++) {
      addr = await adb.connectByGuid(tools.adb, guid).catch(() => null)
      if (!addr) await sleep(1000)
    }
    return ok({ guid, addr })
  } catch (e) {
    return fail(e)
  }
})

// Android-Studio-style QR pairing: the renderer shows a QR encoding our
// serviceName + password; the phone scans it and broadcasts an _adb-tls-pairing
// mDNS service we then `adb pair` against. Drive the whole flow here and stream
// status back to the modal. A bumping token cancels any in-flight attempt.
let qrPairToken = 0

ipcMain.handle('adb:qrPairStart', async (_e, serviceName, password) => {
  const token = ++qrPairToken
  const emit = (state, text, addr) => {
    if (win && !win.isDestroyed()) win.webContents.send('adb-qr-status', { state, text, addr })
  }
  ;(async () => {
    emit('waiting', 'Waiting for scan…')
    const deadline = Date.now() + 120000
    try {
      // 1. Wait for the phone's pairing service to appear (only while its QR screen is open).
      let pairAddr = null
      while (Date.now() < deadline && token === qrPairToken) {
        const svcs = await adb.mdnsPairingServices(tools.adb)
        const match = svcs.find((s) => s.name.startsWith(serviceName)) || svcs[0]
        if (match) {
          pairAddr = match.addr
          break
        }
        await sleep(1000)
      }
      if (token !== qrPairToken) return
      if (!pairAddr) return emit('error', 'Timed out — phone never reached the scan screen')

      // 2. Pair using the QR password as the pairing code.
      const guid = await adb.pairWireless(tools.adb, pairAddr, password)
      if (token !== qrPairToken) return
      wifi.setCfg('deviceGuid', guid)

      // 3. Discover the connect endpoint and attach. The connect service often
      //    only advertises over mDNS while the Wireless-debugging screen is open
      //    (a Samsung quirk), so keep trying for a while and prompt the user.
      emit('connecting', 'Paired — keep the Wireless debugging screen open…')
      let addr = null
      for (let i = 0; i < 20 && !addr && token === qrPairToken; i++) {
        addr = await adb.connectByGuid(tools.adb, guid).catch(() => null)
        if (!addr) await sleep(1500)
      }
      if (token !== qrPairToken) return
      if (addr) emit('connected', 'Connected', addr)
      else emit('error', 'Paired ✓ — open the Wireless debugging screen to finish connecting')
    } catch (e) {
      if (token === qrPairToken) emit('error', String((e && e.message) || e))
    }
  })()
  return ok(true)
})

ipcMain.handle('adb:qrPairCancel', () => {
  qrPairToken++
  return ok(true)
})

ipcMain.handle('adb:unpair', async () => {
  wifi.setCfg('deviceGuid', null)
  wifi.setCfg('tcpAddr', null)
  await adb.disconnectAll(tools.adb)
  serialIdCache.clear()
  return ok(true)
})

ipcMain.handle('adb:pairedInfo', () => ok({ guid: wifi.getCfg('deviceGuid') ?? null }))

// C3: manual "Reconnect" — immediate mDNS scan + connect attempt.
ipcMain.handle('adb:reconnectNow', async () => {
  mdnsInterval = MDNS_MIN
  idleSince = Date.now()
  try {
    if (!tools.adb) throw new Error('ADB not found')
    const guid = wifi.getCfg('deviceGuid')
    const tcpAddr = wifi.getCfg('tcpAddr')
    if (guid) return ok(await adb.connectByGuid(tools.adb, guid))
    if (tcpAddr) {
      await adb.connectTcp(tools.adb, tcpAddr)
      return ok(tcpAddr)
    }
    throw new Error('No paired phone yet — pair ADB first')
  } catch (e) {
    return fail(e)
  } finally {
    scheduleMdns()
  }
})

// LEGACY (<Android 11): flip adbd to TCP over an existing USB link.
ipcMain.handle('adb:wireless', async (_e, serial) => {
  try {
    const addr = await adb.goWireless(tools.adb, serial)
    wifi.setCfg('tcpAddr', addr)
    return ok(addr)
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('camera', async (_e, serial) => {
  try {
    if (!tools.scrcpy) throw new Error('scrcpy not found — brew install scrcpy')
    adb.camera(tools.scrcpy, serial, tools.adb)
    return ok(true)
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('mirror', async (_e, serial) => {
  try {
    if (!tools.scrcpy) throw new Error('scrcpy not found — brew install scrcpy')
    const card = devices.find((d) => d.serial === serial)
    startMirror(serial, card?.id ?? null)
    return ok(true)
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('open:downloads', () => {
  shell.openPath(app.getPath('downloads'))
  return ok(true)
})

ipcMain.handle('wifi:status', () => wifi.status())

ipcMain.handle('wifi:payload', () => wifi.pairingPayload())

ipcMain.handle('wifi:toggleNotif', () => wifi.toggleNotifications())

ipcMain.handle('sms:threads', async () => {
  try {
    const r = await wifi.request({ type: 'sms-threads' })
    if (r.error) throw new Error(r.error)
    return ok(r.threads || [])
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('sms:messages', async (_e, threadId) => {
  try {
    const r = await wifi.request({ type: 'sms-messages', threadId })
    if (r.error) throw new Error(r.error)
    return ok({ messages: r.messages || [], address: r.address || '' })
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('sms:send', async (_e, address, text) => {
  try {
    const r = await wifi.request({ type: 'sms-send', address, text }, 15000)
    if (r.error) throw new Error(r.error)
    return ok(true)
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('media:cmd', (_e, cmd, value) => ok(wifi.push({ type: 'media-cmd', cmd, value })))

ipcMain.handle('wifi:sendClip', () => {
  try {
    return ok(wifi.sendClipboardNow())
  } catch (e) {
    return fail(e)
  }
})

/* ---- Notifications (data already streams in via the 'notification' channel) ---- */

ipcMain.handle('notif:reply', (_e, key, text) => {
  const sent = wifi.push({ type: 'reply', key, text })
  return sent ? ok(true) : fail('Phone not connected over Wi-Fi')
})

ipcMain.handle('notif:dismiss', (_e, key) => ok(wifi.push({ type: 'dismiss', key })))

/* ---- Settings ---- */

ipcMain.handle('settings:get', () => {
  const s = wifi.status()
  return ok({
    deviceName: wifi.getCfg('deviceName') || s.host,
    autoReconnect: wifi.getCfg('autoReconnect') !== false,
    clipboardSync: wifi.getCfg('clipboardSync') !== false,
    notifications: s.notifications,
    largeFileWarning: wifi.getCfg('largeFileWarning') ?? 100,
    downloads: app.getPath('downloads'),
    host: s.host,
    ips: s.ips,
    port: s.port,
    connected: s.connected,
    phoneName: s.phoneName,
    version: app.getVersion(),
    adb: !!tools.adb,
    scrcpy: !!tools.scrcpy
  })
})

ipcMain.handle('settings:set', (_e, key, value) => {
  const allowed = ['deviceName', 'autoReconnect', 'clipboardSync', 'largeFileWarning']
  if (key === 'notifications') {
    wifi.setCfg('notifications', !!value)
    const s = wifi.status()
    if (win && !win.isDestroyed()) win.webContents.send('wifi', s)
    return ok(true)
  }
  if (!allowed.includes(key)) return fail('unknown setting')
  wifi.setCfg(key, value)
  return ok(true)
})

/* ---- Contacts (served by the Android companion over Wi-Fi) ---- */

ipcMain.handle('contacts:list', async () => {
  try {
    const r = await wifi.request({ type: 'contacts' }, 15000)
    if (r.error) throw new Error(r.error)
    return ok(r.contacts || [])
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('contact:call', async (_e, number) => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (dev && tools.adb) {
      await adb.phoneCall(tools.adb, dev.serial, number)
      // Start polling call state so the overlay can track live status
      startCallPolling(dev.serial)
      return ok(true)
    }
    const sent = wifi.push({ type: 'action-call', number })
    if (sent) return ok(true)
    return fail('No connection — connect via ADB or link the phone app first')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('contact:sms', async () => {
  return ok(true)
})

/* ---- Live call control (ADB keycodes) ---- */

let callPollTimer = null

function emitCallState(state, serial) {
  if (win && !win.isDestroyed()) win.webContents.send('call-state', { state, serial })
}

function startCallPolling(serial) {
  if (callPollTimer) clearInterval(callPollTimer)
  // Brief delay to allow the call to actually connect before first poll
  setTimeout(async () => {
    const state = await adb.getCallState(tools.adb, serial)
    emitCallState(state, serial)
    if (state === 'IDLE') return
    callPollTimer = setInterval(async () => {
      try {
        const s = await adb.getCallState(tools.adb, serial)
        emitCallState(s, serial)
        if (s === 'IDLE') {
          clearInterval(callPollTimer)
          callPollTimer = null
        }
      } catch { /* device disconnected */ }
    }, 1000)
  }, 1500)
}

ipcMain.handle('call:end', async () => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (!dev || !tools.adb) return fail('No ADB device')
    await adb.callEnd(tools.adb, dev.serial)
    return ok(true)
  } catch (e) { return fail(e) }
})

ipcMain.handle('call:speaker', async () => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (!dev || !tools.adb) return fail('No ADB device')
    await adb.callSpeaker(tools.adb, dev.serial)
    return ok(true)
  } catch (e) { return fail(e) }
})

ipcMain.handle('call:mute', async () => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (!dev || !tools.adb) return fail('No ADB device')
    await adb.callMute(tools.adb, dev.serial)
    return ok(true)
  } catch (e) { return fail(e) }
})

ipcMain.handle('call:dtmf', async (_e, digit) => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (!dev || !tools.adb) return fail('No ADB device')
    await adb.callDtmf(tools.adb, dev.serial, digit)
    return ok(true)
  } catch (e) { return fail(e) }
})

ipcMain.handle('call:startPolling', async (_e, serial) => {
  const s = serial || devices.find((d) => d.state === 'device')?.serial
  if (s && tools.adb) startCallPolling(s)
  return ok(true)
})

/* ---- Device volume (ADB media volume stream 3) ---- */

ipcMain.handle('volume:get', async () => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (!dev || !tools.adb) return fail('No ADB device')
    return ok(await adb.getVolume(tools.adb, dev.serial))
  } catch (e) { return fail(e) }
})

ipcMain.handle('volume:set', async (_e, level, currentLevel) => {
  try {
    const dev = devices.find((d) => d.state === 'device')
    if (!dev || !tools.adb) return fail('No ADB device')
    const method = await adb.setVolume(tools.adb, dev.serial, level, currentLevel ?? 8)
    return ok(method)
  } catch (e) { return fail(e) }
})

/* ---- Photos (browsed over ADB; thumbnails downscaled on the Mac) ---- */

// Photos work over ADB OR the app link — same resolver as files (A4).
ipcMain.handle('photos:list', async () => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb') return ok({ items: await adb.listMedia(tools.adb, t.serial), transport: 'adb' })
    if (t.kind === 'link') return ok({ items: await wifi.photosList(0, 500), transport: 'link' })
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('photos:thumb', async (_e, item) => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb') {
      // Video thumbnails need ffmpeg; over ADB we let the tile show its video
      // placeholder rather than decoding the file as an image.
      if (item.kind === 'video') return fail('video preview unavailable over ADB')
      const buf = await adb.fileBytes(tools.adb, t.serial, item.path)
      const img = nativeImage.createFromBuffer(buf)
      if (img.isEmpty()) throw new Error('not an image')
      return ok(img.resize({ width: 240, quality: 'good' }).toDataURL())
    }
    if (t.kind === 'link') {
      const buf = await wifi.photoThumb(item.id, item.kind)
      return ok(`data:image/jpeg;base64,${buf.toString('base64')}`)
    }
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})

// Open a photo/video at full resolution: fetch it to a temp folder, then hand it
// to the OS default viewer (Preview / QuickTime). Works over ADB and the app link.
ipcMain.handle('photos:open', async (_e, item) => {
  const t = fsTransport()
  const dir = join(app.getPath('temp'), 'DroidDock')
  try {
    mkdirSync(dir, { recursive: true })
  } catch {
    /* already exists */
  }
  try {
    let dest
    if (t.kind === 'adb') {
      dest = await adb.pull(tools.adb, t.serial, item.path, dir)
    } else if (t.kind === 'link') {
      dest = await wifi.fsPull(item.path, dir, (sent, total, tid) =>
        emitProgress({ tid, name: item.name, sent, total, dir: 'pull' })
      )
      emitProgress({ name: item.name, done: true, dir: 'pull' })
    } else {
      return fail('No device connected')
    }
    const err = await shell.openPath(dest)
    if (err) throw new Error(err)
    return ok(dest)
  } catch (e) {
    return fail(e)
  }
})

ipcMain.handle('photos:pull', async (_e, item) => {
  const t = fsTransport()
  try {
    if (t.kind === 'adb') {
      const dest = await adb.pull(tools.adb, t.serial, item.path, app.getPath('downloads'))
      shell.showItemInFolder(dest)
      return ok(dest)
    }
    if (t.kind === 'link') {
      const dest = await wifi.fsPull(item.path, app.getPath('downloads'), (sent, total, tid) =>
        emitProgress({ tid, name: item.name, sent, total, dir: 'pull' })
      )
      emitProgress({ name: item.name, done: true, dir: 'pull' })
      shell.showItemInFolder(dest)
      return ok(dest)
    }
    return fail('No device connected')
  } catch (e) {
    return fail(e)
  }
})
