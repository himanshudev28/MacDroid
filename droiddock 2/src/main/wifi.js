// Phase 2: Wi-Fi link. WebSocket server + pairing token + two-way clipboard.
import { WebSocketServer } from 'ws'
import { app, clipboard, Notification } from 'electron'
import * as transfer from './transfer'
import { randomUUID } from 'node:crypto'
import { networkInterfaces, hostname } from 'node:os'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { createSocket } from 'node:dgram'

const PORT = 48484

let config = null
let wss = null
let udp = null  // UDP discovery socket (port+1): phone broadcasts token → Mac replies HERE
let phone = null // { socket, name }
let onStatus = () => {}
let onEvent = () => {}
let lastClipFromPhone = null
let lastClipSeen = null
let watcher = null
const liveNotifs = new Map() // key -> { n: Notification, hash }
const pending = new Map() // reqId -> { resolve, timer }
let reqSeq = 0
let callNotif = null
let onForward = () => {}

function configPath() {
  return join(app.getPath('userData'), 'droiddock.json')
}

export function loadConfig() {
  const p = configPath()
  if (existsSync(p)) {
    try {
      config = JSON.parse(readFileSync(p, 'utf8'))
    } catch {
      config = null
    }
  }
  if (!config || !config.token) {
    config = { token: randomUUID(), port: PORT, notifications: true }
    writeFileSync(p, JSON.stringify(config, null, 2))
  }
  if (!config.port) config.port = PORT
  if (typeof config.notifications !== 'boolean') config.notifications = true
  if (typeof config.nativeNotifs !== 'boolean') config.nativeNotifs = true
  return config
}

function saveConfig() {
  writeFileSync(configPath(), JSON.stringify(config, null, 2))
}

export function getCfg(key) {
  return config?.[key]
}

export function setCfg(key, value) {
  if (!config) loadConfig()
  config[key] = value
  saveConfig()
}

export function toggleNotifications() {
  config.notifications = !config.notifications
  saveConfig()
  const s = status()
  onStatus(s)
  return s
}

export function lanIPs() {
  const out = []
  for (const list of Object.values(networkInterfaces())) {
    for (const ni of list || []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address)
    }
  }
  return out
}

function macName() {
  return (config?.deviceName || '').trim() || hostname().replace(/\.local$/, '')
}

export function status() {
  return {
    port: config?.port ?? PORT,
    token: config?.token ?? null,
    ips: lanIPs(),
    host: macName(),
    connected: !!phone,
    phoneName: phone?.name ?? null,
    notifications: config?.notifications ?? true,
    caps: phone?.caps ?? []
  }
}

/** True when a phone is linked AND advertised the given capability (Phase 6a). */
export function hasCap(cap) {
  return !!phone && Array.isArray(phone.caps) && phone.caps.includes(cap)
}

// Bridge the isolated transfer manager to the live socket (sends + backpressure + caps).
const transferIO = {
  sendJson: (obj) => send(obj),
  sendBinary: (buf) => {
    if (phone && phone.socket.readyState === 1) phone.socket.send(buf)
  },
  bufferedAmount: () => (phone ? phone.socket.bufferedAmount : 0),
  hasCap,
  get downloadsDir() { return app.getPath('downloads') },
  notifyProgress: null, // set by wifi.start() when phoneFileCb is provided
}

// Thin re-exports so index.js drives transfers without importing the module directly.
export const fsList = (path) => request({ type: 'fs-list', path }).then((r) => r.entries || [])
export const fsDelete = (path) =>
  request({ type: 'fs-delete', path }).then((r) => {
    if (r.error) throw new Error(r.error)
    return true
  })
export const fsRename = (path, newName) =>
  request({ type: 'fs-rename', path, newName }).then((r) => {
    if (r.error) throw new Error(r.error)
    return r.newPath || null
  })
export const fsPull = (path, destDir, onProgress) => transfer.pull(path, destDir, onProgress)
export const fsPush = (localPath, dest, onProgress) => transfer.push(localPath, dest, onProgress)
export const fsCancel = (transferId) => transfer.cancel(transferId)
export const photosList = (offset, limit) =>
  request({ type: 'photos-list', offset, limit }).then((r) => r.items || [])
export const photoThumb = (id, kind) => transfer.thumb(id, kind)

export function pairingPayload() {
  const s = status()
  // URL scheme so system cameras (Samsung/Google Lens) show "Open DroidDock" directly
  return `droiddock://pair?v=1&name=${encodeURIComponent(s.host)}&ips=${s.ips.join(',')}&port=${s.port}&token=${s.token}`
}

function showNotification(msg) {
  if (!config.notifications || !config.nativeNotifs) return
  const key = String(msg.key || randomUUID())
  const hash = `${msg.title}|${msg.text}`

  const existing = liveNotifs.get(key)
  if (existing) {
    if (existing.hash === hash) return // duplicate repost — ignore
    existing.n.close()
    liveNotifs.delete(key)
  }

  const n = new Notification({
    title: msg.title || msg.app || 'Notification',
    subtitle: msg.app || '',
    body: msg.text || '',
    silent: true, // the phone already buzzed
    hasReply: !!msg.replyable,
    replyPlaceholder: 'Reply from Mac…'
  })

  n.on('reply', (_e, text) => {
    if (text && send({ type: 'reply', key, text })) {
      onEvent({ kind: 'info', text: `Replying to ${msg.app}…` })
    } else {
      onEvent({ kind: 'bad', text: 'Reply failed — phone not connected' })
    }
  })
  n.on('close', () => liveNotifs.delete(key))

  liveNotifs.set(key, { n, hash })
  if (liveNotifs.size > 200) {
    const oldest = liveNotifs.keys().next().value
    liveNotifs.delete(oldest)
  }
  n.show()
}

function send(obj) {
  if (phone && phone.socket.readyState === 1) {
    phone.socket.send(JSON.stringify(obj))
    return true
  }
  return false
}

export function push(obj) {
  return send(obj)
}

/** Ask the phone something and await its reqId-tagged reply. */
export function request(obj, timeout = 8000) {
  return new Promise((resolve, reject) => {
    if (!phone) return reject(new Error('Phone not connected over Wi-Fi'))
    const reqId = ++reqSeq
    const timer = setTimeout(() => {
      pending.delete(reqId)
      reject(new Error('Phone did not respond'))
    }, timeout)
    pending.set(reqId, { resolve, timer })
    phone.socket.send(JSON.stringify({ ...obj, reqId }))
  })
}

function showCall(msg) {
  callNotif?.close()
  callNotif = null
  if (msg.state !== 'ringing') return
  const who = msg.name || msg.number || 'Unknown'

  // Always forward to the in-app panel (NOTIFS tab)
  onForward('call', { ...msg, key: `call-${Date.now()}`, time: Date.now() })

  if (config.nativeNotifs) {
    callNotif = new Notification({
      title: 'Incoming Call',
      subtitle: who,
      body: msg.number && msg.name ? msg.number : 'on your phone',
      silent: false
    })
    callNotif.show()
  }
  onEvent({ kind: 'info', text: `Incoming call — ${who}` })
}

export function sendClipboardNow() {
  const text = clipboard.readText()
  if (!text) return false
  lastClipSeen = text
  return send({ type: 'clipboard', text })
}

function clipboardEnabled() {
  return config?.clipboardSync !== false
}

function startClipboardWatcher() {
  lastClipSeen = clipboard.readText()
  watcher = setInterval(() => {
    if (!phone || !clipboardEnabled()) return
    const text = clipboard.readText()
    if (text && text !== lastClipSeen && text !== lastClipFromPhone) {
      lastClipSeen = text
      send({ type: 'clipboard', text })
      onEvent({ kind: 'info', text: 'Clipboard → phone' })
    } else {
      lastClipSeen = text
    }
  }, 1000)
}

export function start({ statusCb, eventCb, forwardCb, phoneFileCb }) {
  onStatus = statusCb
  onEvent = eventCb
  onForward = forwardCb || (() => {})
  if (phoneFileCb) transferIO.notifyProgress = phoneFileCb
  loadConfig()

  wss = new WebSocketServer({ port: config.port, host: '0.0.0.0' })

  // UDP discovery: phone broadcasts "DROIDDOCK:DISCOVER:<token>" on port+1 when
  // it can't reach known IPs (e.g. after both devices switched to a different WiFi).
  // We reply "DROIDDOCK:HERE" so the phone learns our current IP without re-pairing.
  udp = createSocket('udp4')
  udp.on('message', (msg, rinfo) => {
    if (msg.toString().trim() === `DROIDDOCK:DISCOVER:${config.token}`) {
      udp.send(Buffer.from('DROIDDOCK:HERE'), rinfo.port, rinfo.address)
    }
  })
  udp.on('error', () => {}) // ignore bind errors (port busy etc.)
  udp.bind(config.port + 1, '0.0.0.0')

  wss.on('connection', (socket) => {
    let authed = false
    let name = 'Android'

    const timeout = setTimeout(() => {
      if (!authed) socket.close()
    }, 5000)

    socket.on('message', (raw, isBinary) => {
      // Binary frames: kind byte 3 = mirror video frame → renderer; everything
      // else is a file-transfer chunk handled by the isolated transfer manager.
      if (isBinary) {
        if (authed && phone && phone.socket === socket) {
          if (raw.length > 2 && raw[0] === 3) {
            onForward('mirror-frame', { key: (raw[1] & 1) === 1, data: Buffer.from(raw.subarray(2)) })
          } else {
            transfer.onBinary(raw)
          }
        }
        return
      }
      let msg
      try {
        msg = JSON.parse(raw.toString())
      } catch {
        return
      }

      if (!authed) {
        if (msg.type === 'hello' && msg.token === config.token) {
          authed = true
          clearTimeout(timeout)
          name = msg.name || name
          if (phone) phone.socket.close() // single phone; newest wins
          // caps absent (old app v0.4) → empty list; we then never send new types
          phone = { socket, name, caps: Array.isArray(msg.caps) ? msg.caps : [] }
          socket.send(JSON.stringify({ type: 'welcome', name: macName() }))
          transfer.attach(transferIO)
          onStatus(status())
          onEvent({ kind: 'ok', text: `${name} connected over Wi-Fi` })
        } else {
          socket.close()
        }
        return
      }

      // request/response replies from the phone
      if (msg.reqId && pending.has(msg.reqId)) {
        const p = pending.get(msg.reqId)
        clearTimeout(p.timer)
        pending.delete(msg.reqId)
        p.resolve(msg)
        return
      }

      if (msg.type === 'clipboard' && typeof msg.text === 'string') {
        if (!clipboardEnabled()) return
        lastClipFromPhone = msg.text
        clipboard.writeText(msg.text)
        onEvent({ kind: 'ok', text: 'Clipboard ← phone' })
      } else if (msg.type === 'notification') {
        if (!msg.backfill) showNotification(msg)
        onForward('notification', { ...msg, time: msg.when || Date.now() })
      } else if (msg.type === 'device-info') {
        onForward('device-info', msg)
      } else if (msg.type === 'media') {
        onForward('media', msg)
      } else if (msg.type === 'sms-changed') {
        onForward('sms-changed', {})
      } else if (
        msg.type === 'mirror-started' ||
        msg.type === 'mirror-stopped' ||
        msg.type === 'mirror-error'
      ) {
        onForward(msg.type, msg)
      } else if (msg.type === 'call') {
        showCall(msg)
      } else if (msg.type === 'notification-removed') {
        const live = liveNotifs.get(msg.key)
        if (live) {
          live.n.close()
          liveNotifs.delete(msg.key)
        }
        onForward('notification-removed', { key: msg.key })
      } else if (msg.type === 'reply-result') {
        onEvent(
          msg.ok
            ? { kind: 'ok', text: 'Reply sent from Mac' }
            : { kind: 'bad', text: 'Reply failed on phone' }
        )
      } else if (msg.type === 'pause') {
        // phone asked us to stop reconnect attempts until it comes back
        onForward('pause', { until: msg.until })
      } else if (msg.type === 'resume') {
        onForward('resume', {})
      } else if (
        typeof msg.type === 'string' &&
        (msg.type.startsWith('fs-') || msg.type.startsWith('phone-push') || msg.type === 'photo-thumb-error')
      ) {
        // file-transfer + thumbnail control routed to the isolated transfer manager
        transfer.onControl(msg)
      } else if (msg.type === 'ping') {
        socket.send(JSON.stringify({ type: 'pong' }))
      }
    })

    socket.on('close', () => {
      clearTimeout(timeout)
      if (phone && phone.socket === socket) {
        phone = null
        transfer.detach() // error any in-flight transfer; JSON features unaffected
        onStatus(status())
        onEvent({ kind: 'info', text: `${name} disconnected` })
      }
    })
    socket.on('error', () => {})
  })

  wss.on('error', (e) => {
    onEvent({ kind: 'bad', text: `Wi-Fi server error: ${e.message}` })
  })

  startClipboardWatcher()
  return status()
}

export function stop() {
  clearInterval(watcher)
  try { wss?.close() } catch { /* noop */ }
  try { udp?.close() } catch { /* noop */ }
  udp = null
}
