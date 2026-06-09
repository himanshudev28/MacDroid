// All interaction with adb / scrcpy lives here (main process only).
import { execFile, spawn } from 'node:child_process'
import { existsSync, createWriteStream } from 'node:fs'
import { join, basename, extname } from 'node:path'
import { homedir } from 'node:os'

const CANDIDATES = {
  adb: [
    '/opt/homebrew/bin/adb',
    '/usr/local/bin/adb',
    join(homedir(), 'Library/Android/sdk/platform-tools/adb')
  ],
  scrcpy: ['/opt/homebrew/bin/scrcpy', '/usr/local/bin/scrcpy']
}

function which(cmd) {
  return new Promise((resolve) => {
    execFile('/usr/bin/which', [cmd], (err, stdout) => {
      const p = (stdout || '').trim()
      resolve(!err && p ? p : null)
    })
  })
}

export async function resolveTool(name) {
  const found = await which(name)
  if (found) return found
  for (const p of CANDIDATES[name] || []) {
    if (existsSync(p)) return p
  }
  return null
}

function run(bin, args, { timeout = 20000 } = {}) {
  return new Promise((resolve, reject) => {
    execFile(bin, args, { timeout, maxBuffer: 32 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) reject(new Error((stderr || err.message || '').trim() || 'command failed'))
      else resolve(stdout)
    })
  })
}

// POSIX single-quote escaping for paths sent through `adb shell`
const shq = (s) => `'` + String(s).replace(/'/g, `'\\''`) + `'`

export async function startServer(adb) {
  try {
    await run(adb, ['start-server'])
  } catch {
    /* adb prints noise to stderr on first start; ignore */
  }
}

function parseDeviceLine(line) {
  const cols = line.split(/\s+/)
  const serial = cols[0]
  const state = cols[1] || 'unknown' // device | unauthorized | offline
  const m = line.match(/model:(\S+)/)
  const model = m ? m[1].replace(/_/g, ' ') : serial
  // Wireless adb shows up either as ip:port OR as an mDNS instance name
  // (e.g. adb-<serial>-xxxx._adb-tls-connect._tcp) when adb auto-connects.
  const wireless = /:\d+$/.test(serial) || /_adb-tls|\._tcp/.test(serial)
  return { serial, state, model, wireless }
}

/** Parse a device-list block (no header) into device objects. */
function parseDeviceBlock(text) {
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map(parseDeviceLine)
}

export async function listDevices(adb) {
  const out = await run(adb, ['devices', '-l'])
  return parseDeviceBlock(out.split('\n').slice(1).join('\n'))
}

/** Event-driven device tracking via `adb track-devices -l` (Phase 6a C1).
 *  Calls onList(devices[]) on every change. Auto-respawns on exit (2s backoff).
 *  Output is a stream of 4-hex-length-prefixed device-list snapshots. */
export function trackDevices(adb, onList) {
  let child = null
  let buf = Buffer.alloc(0)
  let stopped = false
  let backoffTimer = null

  const spawnChild = () => {
    child = spawn(adb, ['track-devices', '-l'])
    buf = Buffer.alloc(0)
    child.stdout.on('data', (d) => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = parseInt(buf.subarray(0, 4).toString('ascii'), 16)
        if (Number.isNaN(len)) {
          buf = Buffer.alloc(0)
          break
        }
        if (buf.length < 4 + len) break
        const payload = buf.subarray(4, 4 + len).toString('utf8')
        buf = buf.subarray(4 + len)
        onList(parseDeviceBlock(payload))
      }
    })
    child.on('exit', () => {
      if (!stopped) backoffTimer = setTimeout(spawnChild, 2000)
    })
    child.on('error', () => {})
  }

  spawnChild()
  return {
    stop() {
      stopped = true
      if (backoffTimer) clearTimeout(backoffTimer)
      try {
        child?.kill()
      } catch {
        /* noop */
      }
    }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** The phone's stable hardware serial (ro.serialno) — same over USB and Wi-Fi.
 *  Used to recognise that a USB serial and an ip:port are the same physical phone. */
export async function serialNo(adb, serial) {
  const out = await run(adb, ['-s', serial, 'shell', 'getprop', 'ro.serialno'], { timeout: 5000 })
  return out.trim()
}

/** Android 11+ Wireless Debugging: pair with the code shown under
 *  Developer options → Wireless debugging → "Pair device with pairing code".
 *  `hostPort` is the PAIRING endpoint (ip:port) from that dialog — note its port
 *  differs from the main Wireless-debugging port. Returns the device guid. */
export async function pairWireless(adb, hostPort, code) {
  const out = await run(adb, ['pair', hostPort, code], { timeout: 15000 })
  const m = out.match(/guid=([^\]\s]+)/)
  if (!/Successfully paired/i.test(out) || !m) {
    throw new Error(out.trim() || 'Pairing failed')
  }
  return m[1]
}

/** Parse `adb mdns services`, keeping only the given service type.
 *  Returns [{ name, addr: "ip:port" }] (name is the mDNS instance name). */
async function mdnsServices(adb, type) {
  const out = await run(adb, ['mdns', 'services'], { timeout: 6000 }).catch(() => '')
  const res = []
  for (const line of out.split('\n').slice(1)) {
    if (!line.includes(type)) continue
    const name = line.trim().split(/\s+/)[0]
    const m = line.match(/(\d+\.\d+\.\d+\.\d+)[\s:](\d+)/)
    if (name && m) res.push({ name, addr: `${m[1]}:${m[2]}` })
  }
  return res
}

/** tls-connect endpoints (a paired phone announcing it's reachable). */
export const mdnsConnectServices = (adb) => mdnsServices(adb, '_adb-tls-connect')

/** tls-pairing endpoints (a phone sitting on its QR/code pairing screen). */
export const mdnsPairingServices = (adb) => mdnsServices(adb, '_adb-tls-pairing')

/** Reconnect a previously paired phone by discovering its current ip:port over
 *  mDNS (the wireless-debugging port rotates) and matching the stored guid. */
export async function connectByGuid(adb, guid) {
  const services = await mdnsConnectServices(adb)
  const match = services.find((s) => s.name.startsWith(guid))
  if (!match) throw new Error('Phone not announcing on the network yet')
  const out = await run(adb, ['connect', match.addr], { timeout: 5000 })
  if (!/connected/i.test(out)) throw new Error(out.trim() || 'connect failed')
  return match.addr
}

/** Drop all wireless adb connections (used by Unpair / forget). */
export function disconnectAll(adb) {
  return run(adb, ['disconnect']).catch(() => {})
}

/** LEGACY (<Android 11): flip adbd to TCP over an existing USB link and connect.
 *  Needs the cable once and resets on reboot. Returns "ip:5555". */
export async function goWireless(adb, serial) {
  const route = await run(adb, ['-s', serial, 'shell', 'ip route'])
  const m = route.match(/src (\d+\.\d+\.\d+\.\d+)/)
  if (!m) throw new Error("Couldn't read the phone's Wi-Fi IP — is the phone on Wi-Fi?")
  const addr = `${m[1]}:5555`
  await run(adb, ['-s', serial, 'tcpip', '5555'])
  for (let i = 0; i < 5; i++) {
    await sleep(1200)
    try {
      const out = await run(adb, ['connect', addr])
      if (/connected/.test(out)) return addr
    } catch {
      /* adbd still restarting — retry */
    }
  }
  throw new Error(`Couldn't reach ${addr} — phone and Mac on the same network?`)
}

/** Quiet reconnect attempt for a previously used wireless address. */
export function connectTcp(adb, addr) {
  return run(adb, ['connect', addr], { timeout: 4000 }).catch(() => {})
}

// scrcpy shells out to `adb`. In a GUI-launched app the system PATH often doesn't
// include the SDK platform-tools, so scrcpy can't find adb and fails to start. Point
// it at our resolved absolute adb via the ADB env var that scrcpy honors.
const scrcpyEnv = (adb) => (adb ? { ...process.env, ADB: adb } : process.env)

export function camera(scrcpy, serial, adb) {
  const child = spawn(
    scrcpy,
    [
      '-s',
      serial,
      '--video-source=camera',
      '--camera-facing=back',
      '--no-audio',
      '--window-title',
      'DroidDock — Camera'
    ],
    { detached: true, stdio: 'ignore', env: scrcpyEnv(adb) }
  )
  child.unref()
}

export async function deviceInfo(adb, serial) {
  const prop = (k) =>
    run(adb, ['-s', serial, 'shell', `getprop ${k}`]).then((s) => s.trim()).catch(() => '')
  const [model, android, sdk, batteryRaw] = await Promise.all([
    prop('ro.product.model'),
    prop('ro.build.version.release'),
    prop('ro.build.version.sdk'),
    run(adb, ['-s', serial, 'shell', 'dumpsys battery']).catch(() => '')
  ])
  const lvl = batteryRaw.match(/level:\s*(\d+)/)
  const charging = /(AC|USB|Wireless) powered: true/.test(batteryRaw)
  return {
    model: model || serial,
    android,
    sdk,
    battery: lvl ? Number(lvl[1]) : null,
    charging
  }
}

export async function listDir(adb, serial, dirPath) {
  const out = await run(adb, ['-s', serial, 'shell', `ls -1p ${shq(dirPath)}`])
  const entries = out
    .split('\n')
    .map((l) => l.replace(/\r$/, ''))
    .filter(Boolean)
    .map((name) =>
      name.endsWith('/') ? { name: name.slice(0, -1), dir: true } : { name, dir: false }
    )
  entries.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1))
  return entries
}

/** Delete a file or directory on the device (recursive). */
export async function rm(adb, serial, remotePath) {
  const out = await run(adb, ['-s', serial, 'shell', `rm -rf ${shq(remotePath)} && echo OK`])
  if (!out.includes('OK')) throw new Error(out.trim() || 'delete failed')
}

/** Rename a file/folder in place (same directory). Returns the new remote path. */
export async function rename(adb, serial, remotePath, newName) {
  const clean = String(newName).trim()
  if (!clean || clean.includes('/') || clean === '.' || clean === '..')
    throw new Error('Invalid name')
  const dir = remotePath.slice(0, remotePath.lastIndexOf('/')) || ''
  const dest = `${dir}/${clean}`
  // -n: never overwrite an existing destination.
  const out = await run(adb, ['-s', serial, 'shell', `mv -n ${shq(remotePath)} ${shq(dest)} && echo OK`])
  if (!out.includes('OK')) throw new Error(out.trim() || 'rename failed')
  return dest
}

function uniqueDest(destDir, fileName) {
  const ext = extname(fileName)
  const stem = basename(fileName, ext)
  let candidate = join(destDir, fileName)
  let i = 2
  while (existsSync(candidate)) {
    candidate = join(destDir, `${stem} (${i})${ext}`)
    i++
  }
  return candidate
}

export function pull(adb, serial, remotePath, destDir) {
  const dest = uniqueDest(destDir, basename(remotePath))
  return new Promise((resolve, reject) => {
    const child = spawn(adb, ['-s', serial, 'pull', remotePath, dest])
    let err = ''
    child.stderr.on('data', (d) => (err += d))
    child.on('error', reject)
    child.on('close', (code) =>
      code === 0 ? resolve(dest) : reject(new Error(err.trim() || `pull failed (${code})`))
    )
  })
}

export async function pushPaths(adb, serial, localPaths, remoteDir = '/sdcard/Download/') {
  for (const p of localPaths) {
    await new Promise((resolve, reject) => {
      const child = spawn(adb, ['-s', serial, 'push', p, remoteDir])
      let err = ''
      child.stderr.on('data', (d) => (err += d))
      child.on('error', reject)
      child.on('close', (code) =>
        code === 0 ? resolve() : reject(new Error(err.trim() || `push failed (${code})`))
      )
    })
  }
  return { count: localPaths.length, remoteDir }
}

export function screenshot(adb, serial, destDir) {
  const ts = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19)
  const dest = join(destDir, `droiddock-${ts}.png`)
  return new Promise((resolve, reject) => {
    const child = spawn(adb, ['-s', serial, 'exec-out', 'screencap', '-p'])
    const file = createWriteStream(dest)
    child.stdout.pipe(file)
    let err = ''
    child.stderr.on('data', (d) => (err += d))
    child.on('error', reject)
    child.on('close', (code) => {
      file.end()
      code === 0 ? resolve(dest) : reject(new Error(err.trim() || `screencap failed (${code})`))
    })
  })
}

export function mirror(scrcpy, serial, adb) {
  const child = spawn(scrcpy, ['-s', serial, '--window-title', 'DroidDock — Mirror'], {
    detached: true,
    stdio: 'ignore',
    env: scrcpyEnv(adb)
  })
  child.unref()
  return child
}

const IMG_RE = /\.(jpe?g|png|heic|heif|webp|gif|bmp)$/i

/** All images on the phone, newest-first. Uses MediaStore (covers every album,
 *  proper date order); falls back to scanning common folders if that fails. */
export async function listImages(adb, serial, max = 2000) {
  // MediaStore query — one arg so the device shell parses the quoted sort.
  const cmd =
    'content query --uri content://media/external/images/media' +
    ' --projection _id:_data:date_modified --sort "date_modified DESC"'
  const out = await run(adb, ['-s', serial, 'shell', cmd]).catch(() => '')
  const items = []
  for (const line of out.split('\n')) {
    const m = line.match(/_data=(.*?), date_modified=(\d+)/)
    if (!m) continue
    const path = m[1]
    if (!IMG_RE.test(path)) continue
    items.push({ path, name: basename(path), date: Number(m[2]) * 1000 })
    if (items.length >= max) break
  }
  if (items.length) return items

  // Fallback: scan the usual media folders directly.
  const found = await run(adb, [
    '-s',
    serial,
    'shell',
    'find /sdcard/DCIM /sdcard/Pictures /sdcard/Download -type f 2>/dev/null'
  ]).catch(() => '')
  const files = found
    .split('\n')
    .map((l) => l.replace(/\r$/, '').trim())
    .filter((p) => p && IMG_RE.test(p))
  files.sort((a, b) => (a < b ? 1 : a > b ? -1 : 0))
  return files.slice(0, max).map((path) => ({ path, name: basename(path) }))
}

const VID_RE = /\.(mp4|mkv|mov|webm|3gp|m4v|avi)$/i

/** All videos on the phone, newest-first, via MediaStore (mirrors listImages). */
export async function listVideos(adb, serial, max = 2000) {
  const cmd =
    'content query --uri content://media/external/video/media' +
    ' --projection _id:_data:date_modified:duration --sort "date_modified DESC"'
  const out = await run(adb, ['-s', serial, 'shell', cmd]).catch(() => '')
  const items = []
  for (const line of out.split('\n')) {
    const m = line.match(/_data=(.*?), date_modified=(\d+)/)
    if (!m) continue
    const path = m[1]
    if (!VID_RE.test(path)) continue
    const dur = line.match(/duration=(\d+)/)
    items.push({
      path,
      name: basename(path),
      date: Number(m[2]) * 1000,
      kind: 'video',
      duration: dur ? Number(dur[1]) : 0
    })
    if (items.length >= max) break
  }
  return items
}

/** Images + videos merged newest-first; each item carries a `kind`. The Mac's
 *  PhotosView badges videos and skips image-decoding their thumbnails over ADB. */
export async function listMedia(adb, serial, max = 2000) {
  const [imgs, vids] = await Promise.all([
    listImages(adb, serial, max).catch(() => []),
    listVideos(adb, serial, max).catch(() => [])
  ])
  const images = imgs.map((it) => ({ ...it, kind: 'image' }))
  return [...images, ...vids]
    .sort((a, b) => (b.date || 0) - (a.date || 0))
    .slice(0, max)
}

/** Raw bytes of a file on the device (used for thumbnailing). */
export function fileBytes(adb, serial, remotePath) {
  return new Promise((resolve, reject) => {
    const child = spawn(adb, ['-s', serial, 'exec-out', 'cat', remotePath])
    const chunks = []
    let err = ''
    child.stdout.on('data', (d) => chunks.push(d))
    child.stderr.on('data', (d) => (err += d))
    child.on('error', reject)
    child.on('close', (code) =>
      code === 0 ? resolve(Buffer.concat(chunks)) : reject(new Error(err.trim() || `cat failed (${code})`))
    )
  })
}

/** Directly initiate a phone call via Android intent (no dialer UI). */
export async function phoneCall(adb, serial, number) {
  const encoded = encodeURIComponent(number)
  await run(adb, [
    '-s', serial, 'shell', 'am', 'start',
    '-a', 'android.intent.action.CALL',
    '-d', `tel:${encoded}`
  ], { timeout: 8000 })
}

/** Open the default SMS app with a pre-filled number via Android intent. */
export async function phoneSms(adb, serial, number) {
  const encoded = encodeURIComponent(number)
  await run(adb, [
    '-s', serial, 'shell', 'am', 'start',
    '-a', 'android.intent.action.SENDTO',
    '-d', `sms:${encoded}`
  ], { timeout: 8000 })
}

/* ─── Device volume control (ADB media volume) ──────────────────────────── */

/**
 * Returns current media stream volume: { level: number, max: number }
 * Primary: `media volume --stream 3 --get`
 * Fallback: `settings get system volume_music`
 */
export async function getVolume(adb, serial) {
  try {
    const out = await run(adb, ['-s', serial, 'shell', 'media', 'volume', '--stream', '3', '--get'], { timeout: 4000 })
    const m = out.match(/is (\d+) \(min: \d+, max: (\d+)\)/)
    if (m) return { level: parseInt(m[1]), max: parseInt(m[2]) }
  } catch { /* fall through */ }
  try {
    const raw = (await run(adb, ['-s', serial, 'shell', 'settings', 'get', 'system', 'volume_music'], { timeout: 3000 })).trim()
    const level = parseInt(raw)
    if (!isNaN(level)) return { level, max: 15 }
  } catch { /* fall through */ }
  return { level: 8, max: 15 }
}

/**
 * Set media stream volume — tries 3 methods in order:
 * 1. `media volume --stream 3 --set <level>`  (Android 7–13, most ROMs)
 * 2. `cmd media_session volume ...`            (Android 9+)
 * 3. Repeated KEYCODE_VOLUME_UP/DOWN keyevents (universal fallback)
 */
export async function setVolume(adb, serial, level, currentLevel = 8) {
  // Method 1: media volume CLI
  try {
    const out = await run(adb, [
      '-s', serial, 'shell', 'media', 'volume',
      '--stream', '3', '--set', String(level), '--show'
    ], { timeout: 4000 })
    if (!out.toLowerCase().includes('error')) return 'media'
  } catch { /* fall through */ }

  // Method 2: cmd media_session (Android 9+, works on many Samsung / Pixel)
  try {
    await run(adb, [
      '-s', serial, 'shell', 'cmd', 'media_session', 'volume',
      '--set', String(level), '--stream', '3'
    ], { timeout: 3000 })
    return 'cmd'
  } catch { /* fall through */ }

  // Method 3: volume key events — calculate delta from current to target
  const delta = level - currentLevel
  if (delta === 0) return 'noop'
  const keycode = delta > 0 ? '24' : '25'          // VOLUME_UP / VOLUME_DOWN
  const times = Math.min(Math.abs(delta), 20)       // cap at 20 presses
  for (let i = 0; i < times; i++) {
    await run(adb, ['-s', serial, 'shell', 'input', 'keyevent', keycode], { timeout: 2000 })
  }
  return 'keyevents'
}

/* ─── Live call control (ADB keycodes) ─────────────────────────────────── */

const KEYCODES = {
  ENDCALL:     6,
  SPEAKERPHONE: 168,
  MUTE:        91,
  '0': 7,  '1': 8,  '2': 9,  '3': 10,
  '4': 11, '5': 12, '6': 13, '7': 14,
  '8': 15, '9': 16, '*': 17, '#': 18
}

function keyevent(adb, serial, code) {
  return run(adb, ['-s', serial, 'shell', 'input', 'keyevent', String(code)], { timeout: 5000 })
}

/**
 * Returns "IDLE" | "RINGING" | "ACTIVE"
 * Uses getprop gsm.call.state, falls back to telephony.registry dump.
 */
export async function getCallState(adb, serial) {
  try {
    const out = (await run(adb, ['-s', serial, 'shell', 'getprop', 'gsm.call.state'], { timeout: 3000 })).trim().toUpperCase()
    if (out === 'IDLE' || out === 'RINGING' || out === 'ACTIVE') return out
    // Fallback: parse mCallState from telephony.registry
    const dump = await run(adb, ['-s', serial, 'shell', 'dumpsys', 'telephony.registry'], { timeout: 4000 })
    const m = dump.match(/mCallState=(\d)/)
    if (m) {
      const n = parseInt(m[1])
      return n === 0 ? 'IDLE' : n === 1 ? 'RINGING' : 'ACTIVE'
    }
    return 'IDLE'
  } catch {
    return 'IDLE'
  }
}

/** End / reject the current call. */
export function callEnd(adb, serial) {
  return keyevent(adb, serial, KEYCODES.ENDCALL)
}

/** Wake screen + bring in-call UI to front, then toggle speakerphone. */
export async function callSpeaker(adb, serial) {
  // 224 = KEYCODE_WAKEUP — turn screen on so keyevent reaches in-call UI
  try { await run(adb, ['-s', serial, 'shell', 'input', 'keyevent', '224'], { timeout: 2000 }) } catch {}
  // Bring the in-call screen to foreground (works across manufacturers)
  try {
    await run(adb, [
      '-s', serial, 'shell', 'am', 'start',
      '-a', 'android.intent.action.CALL_BUTTON'
    ], { timeout: 3000 })
  } catch {}
  // Brief pause for UI to settle
  await new Promise((r) => setTimeout(r, 350))
  return keyevent(adb, serial, KEYCODES.SPEAKERPHONE)
}

/** Wake screen + bring in-call UI to front, then toggle mic mute. */
export async function callMute(adb, serial) {
  try { await run(adb, ['-s', serial, 'shell', 'input', 'keyevent', '224'], { timeout: 2000 }) } catch {}
  try {
    await run(adb, [
      '-s', serial, 'shell', 'am', 'start',
      '-a', 'android.intent.action.CALL_BUTTON'
    ], { timeout: 3000 })
  } catch {}
  await new Promise((r) => setTimeout(r, 350))
  return keyevent(adb, serial, KEYCODES.MUTE)
}

/** Send a DTMF digit (0-9, *, #). */
export function callDtmf(adb, serial, digit) {
  const code = KEYCODES[String(digit)]
  if (!code) throw new Error(`Unknown DTMF digit: ${digit}`)
  return keyevent(adb, serial, code)
}

