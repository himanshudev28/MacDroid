// Phase 6a A3 — binary file-transfer manager for the app link.
// Fully isolated from wifi.js's JSON path: wifi.js routes binary frames here
// (onBinary) and fs-* control messages here (onControl), and injects an `io`
// with send + backpressure + caps hooks. Nothing here touches the JSON handlers.
import {
  createWriteStream,
  createReadStream,
  statSync,
  existsSync,
  renameSync,
  copyFileSync,
  rmSync
} from 'node:fs'
import { join, basename, extname } from 'node:path'
import { tmpdir } from 'node:os'

const KIND_DATA = 1
const KIND_THUMB = 2 // small photo thumbnail in a single frame (A4)
const HEADER = 9 // [1B kind][4B transferId][4B seq]
const CHUNK = 256 * 1024
const MAX_INFLIGHT = 4 * 1024 * 1024 // ≤4MB on the wire (backpressure)
const STALL_MS = 30000

let io = null // { sendJson(obj), sendBinary(buf), bufferedAmount(), hasCap(cap), downloadsDir, notifyProgress }
let reqSeq = 1
let phoneTid = 1_000_000 // Mac-generated transferIds for phone-initiated pushes
const recv = new Map() // transferId -> incoming (pull) state
const out = new Map() // transferId -> outgoing (push) state
const pendingPull = new Map() // reqId -> { resolve, reject, destDir, name, onProgress, timer }
const pendingPush = new Map() // reqId -> { resolve, reject, localPath, size, onProgress, timer }
const pendingThumb = new Map() // reqId -> { resolve, reject, timer }
let thumbSeq = 1

export function attach(_io) {
  io = _io
}

/** Link dropped: error every in-flight transfer; existing JSON features untouched. */
export function detach() {
  const err = new Error('App link dropped')
  for (const r of [...recv.values()]) r.fail(err)
  for (const s of [...out.values()]) s.abort(err)
  for (const p of [...pendingPull.values()]) {
    clearTimeout(p.timer)
    p.reject(err)
  }
  for (const p of [...pendingPush.values()]) {
    clearTimeout(p.timer)
    p.reject(err)
  }
  for (const p of [...pendingThumb.values()]) {
    clearTimeout(p.timer)
    p.reject(err)
  }
  recv.clear()
  out.clear()
  pendingPull.clear()
  pendingPush.clear()
  pendingThumb.clear()
  io = null
}

/** Ask the phone to capture its screen; resolves with the saved PNG path.
 *  Reuses the pull receive machinery (app-shot-begin/done feed beginReceive). */
export function shot(destDir, stamp) {
  return new Promise((resolve, reject) => {
    if (!ready()) return reject(new Error('Phone app not linked'))
    const reqId = `s${reqSeq++}`
    const name = `droiddock-shot-${stamp}.png`
    const timer = setTimeout(() => {
      if (pendingPull.delete(reqId)) reject(new Error('Phone did not capture the screen'))
    }, STALL_MS)
    pendingPull.set(reqId, { resolve, reject, destDir, name, onProgress: null, timer })
    emitJson({ type: 'app-shot', reqId })
  })
}

/** Request a photo/video thumbnail; resolves with a Buffer (single binary frame).
 *  `kind` ("image" | "video") tells the phone which MediaStore table to read;
 *  it defaults to "image" so older companions keep working. */
export function thumb(id, kind = 'image', timeout = 12000) {
  return new Promise((resolve, reject) => {
    if (!ready()) return reject(new Error('Phone app not linked'))
    const reqId = thumbSeq++ & 0x7fffffff
    const timer = setTimeout(() => {
      if (pendingThumb.delete(reqId)) reject(new Error('Thumbnail timed out'))
    }, timeout)
    pendingThumb.set(reqId, { resolve, reject, timer })
    emitJson({ type: 'photo-thumb', reqId, id, kind })
  })
}

const ready = () => !!io && io.hasCap('fs')

/** Caps-gated sends — enforced here, not at call sites (spec add #2). */
function emitJson(obj) {
  if (ready()) io.sendJson(obj)
}
function emitBinary(kind, tid, seq, payload) {
  if (!ready()) return false
  const head = Buffer.allocUnsafe(HEADER)
  head.writeUInt8(kind, 0)
  head.writeUInt32BE(tid >>> 0, 1)
  head.writeUInt32BE(seq >>> 0, 5)
  io.sendBinary(Buffer.concat([head, payload], HEADER + payload.length))
  return true
}

function uniqueDest(destDir, fileName) {
  const ext = extname(fileName)
  const stem = basename(fileName, ext)
  let candidate = join(destDir, fileName)
  let i = 2
  while (existsSync(candidate)) candidate = join(destDir, `${stem} (${i++})${ext}`)
  return candidate
}

/* ───────────────────── pull: phone → Mac ───────────────────── */

export function pull(path, destDir, onProgress) {
  return new Promise((resolve, reject) => {
    if (!ready()) return reject(new Error('Phone app not linked'))
    const reqId = `p${reqSeq++}`
    const timer = setTimeout(() => {
      if (pendingPull.delete(reqId)) reject(new Error('Phone did not start the transfer'))
    }, STALL_MS)
    pendingPull.set(reqId, { resolve, reject, destDir, name: basename(path), onProgress, timer })
    emitJson({ type: 'fs-pull', reqId, path })
  })
}

function beginReceive(reqId, transferId, size) {
  const p = pendingPull.get(reqId)
  if (!p) return
  clearTimeout(p.timer)
  pendingPull.delete(reqId)
  const tmp = join(tmpdir(), `droiddock-${transferId}-${Date.now()}.part`)
  const r = {
    transferId,
    size,
    received: 0,
    tmp,
    stream: createWriteStream(tmp),
    destDir: p.destDir,
    name: p.name,
    onProgress: p.onProgress,
    resolve: p.resolve,
    reject: p.reject,
    stallTimer: null,
    touch() {
      clearTimeout(r.stallTimer)
      r.stallTimer = setTimeout(() => r.fail(new Error('Transfer stalled')), STALL_MS)
    },
    fail(err) {
      clearTimeout(r.stallTimer)
      try {
        r.stream.destroy()
      } catch {
        /* noop */
      }
      try {
        if (existsSync(r.tmp)) rmSync(r.tmp)
      } catch {
        /* noop */
      }
      recv.delete(transferId)
      r.reject(err)
    }
  }
  recv.set(transferId, r)
  r.touch()
}

function onChunk(transferId, payload) {
  const r = recv.get(transferId)
  if (!r) return // unknown / cancelled — drop
  r.received += payload.length
  r.stream.write(payload)
  r.touch()
  if (r.onProgress) r.onProgress(r.received, r.size, transferId)
}

function finishReceive(transferId, declaredSize) {
  const r = recv.get(transferId)
  if (!r) return
  clearTimeout(r.stallTimer)
  const expected = declaredSize ?? r.size
  r.stream.end(() => {
    if (r.received !== expected) {
      try { rmSync(r.tmp) } catch { /* noop */ }
      recv.delete(transferId)
      if (r.fromPhone) {
        io?.sendJson({ type: 'phone-push-result', transferId, ok: false, error: `size mismatch (${r.received}/${expected})` })
      } else {
        r.reject(new Error(`Transfer corrupt — ${r.received}/${expected} bytes`))
      }
      return
    }
    const dest = uniqueDest(r.destDir, r.name)
    try {
      renameSync(r.tmp, dest)
    } catch {
      try {
        copyFileSync(r.tmp, dest)
        rmSync(r.tmp)
      } catch (e) {
        recv.delete(transferId)
        if (r.fromPhone) {
          io?.sendJson({ type: 'phone-push-result', transferId, ok: false, error: e.message })
        } else {
          r.reject(e)
        }
        return
      }
    }
    recv.delete(transferId)
    if (r.fromPhone) {
      io?.sendJson({ type: 'phone-push-result', transferId, ok: true })
      io?.notifyProgress?.({ name: r.name, done: true, dir: 'phone' })
    } else {
      r.resolve(dest)
    }
  })
}

/* ───────────────────── phone-initiated push: phone → Mac ───────────────────── */

function beginPhoneReceive(reqId, name, size) {
  if (!io) return
  const tid = phoneTid++
  const destDir = io.downloadsDir
  const tmp = join(tmpdir(), `droiddock-ph-${tid}-${Date.now()}.part`)
  const r = {
    transferId: tid,
    size,
    received: 0,
    tmp,
    stream: createWriteStream(tmp),
    destDir,
    name,
    onProgress: null,
    fromPhone: true,
    resolve: null,
    reject: null,
    stallTimer: null,
  }
  r.onProgress = (received, size) => {
    io?.notifyProgress?.({ name: r.name, sent: received, total: size, dir: 'phone' })
  }
  r.touch = () => {
    clearTimeout(r.stallTimer)
    r.stallTimer = setTimeout(() => r.fail(new Error('Transfer stalled')), STALL_MS)
  }
  r.fail = (err) => {
    clearTimeout(r.stallTimer)
    try { r.stream.destroy() } catch { /* noop */ }
    try { if (existsSync(r.tmp)) rmSync(r.tmp) } catch { /* noop */ }
    recv.delete(tid)
    io?.sendJson({ type: 'phone-push-result', transferId: tid, ok: false, error: err.message })
  }
  recv.set(tid, r)
  r.touch()
  io.sendJson({ type: 'phone-push', reqId, transferId: tid })
}

/* ───────────────────── push: Mac → phone ───────────────────── */

export function push(localPath, dest, onProgress) {
  return new Promise((resolve, reject) => {
    if (!ready()) return reject(new Error('Phone app not linked'))
    let size
    try {
      size = statSync(localPath).size
    } catch (e) {
      return reject(e)
    }
    const reqId = `u${reqSeq++}`
    const timer = setTimeout(() => {
      if (pendingPush.delete(reqId)) reject(new Error('Phone did not accept the transfer'))
    }, STALL_MS)
    pendingPush.set(reqId, { resolve, reject, localPath, size, onProgress, timer })
    emitJson({ type: 'fs-push-begin', reqId, name: basename(localPath), size, dest })
  })
}

function startSend(reqId, transferId) {
  const p = pendingPush.get(reqId)
  if (!p) return
  clearTimeout(p.timer)
  pendingPush.delete(reqId)
  const stream = createReadStream(p.localPath, { highWaterMark: CHUNK })
  let seq = 0
  let sent = 0
  const s = {
    transferId,
    resolve: p.resolve,
    reject: p.reject,
    onProgress: p.onProgress,
    size: p.size,
    stream,
    stallTimer: null,
    touch() {
      clearTimeout(s.stallTimer)
      s.stallTimer = setTimeout(() => s.abort(new Error('Transfer stalled')), STALL_MS)
    },
    abort(err) {
      clearTimeout(s.stallTimer)
      try {
        stream.destroy()
      } catch {
        /* noop */
      }
      out.delete(transferId)
      s.reject(err)
    }
  }
  out.set(transferId, s)
  s.touch()

  stream.on('data', (chunk) => {
    emitBinary(KIND_DATA, transferId, seq++, chunk)
    sent += chunk.length
    s.touch()
    if (s.onProgress) s.onProgress(sent, p.size, transferId)
    // backpressure (spec add #1): keep ≤4MB on the wire
    if (io && io.bufferedAmount() > MAX_INFLIGHT) {
      stream.pause()
      const resume = () => {
        if (!io || stream.destroyed) return
        if (io.bufferedAmount() < MAX_INFLIGHT) stream.resume()
        else setTimeout(resume, 25)
      }
      setTimeout(resume, 25)
    }
  })
  stream.on('end', () => {
    emitJson({ type: 'fs-push-done', transferId, size: p.size })
    // keep `s` registered until the phone acks fs-push-result (integrity verdict)
  })
  stream.on('error', (e) => s.abort(e))
}

/* ───────────────────── cancel (both directions) ───────────────────── */

export function cancel(transferId) {
  emitJson({ type: 'fs-cancel', transferId })
  const r = recv.get(transferId)
  if (r) r.fail(new Error('Cancelled'))
  const s = out.get(transferId)
  if (s) s.abort(new Error('Cancelled'))
}

/* ───────────────────── routing from wifi.js ───────────────────── */

export function onBinary(buf) {
  if (buf.length < HEADER) return
  const kind = buf.readUInt8(0)
  const idField = buf.readUInt32BE(1)
  if (kind === KIND_THUMB) {
    const p = pendingThumb.get(idField)
    if (p) {
      clearTimeout(p.timer)
      pendingThumb.delete(idField)
      p.resolve(buf.subarray(HEADER))
    }
    return
  }
  if (kind !== KIND_DATA) return
  onChunk(idField, buf.subarray(HEADER))
}

export function onControl(msg) {
  switch (msg.type) {
    case 'fs-pull-begin':
      beginReceive(msg.reqId, msg.transferId, msg.size)
      break
    case 'fs-pull-done':
      finishReceive(msg.transferId, msg.size)
      break
    case 'fs-pull-error': {
      const r = recv.get(msg.transferId)
      if (r) r.fail(new Error(msg.error || 'phone error'))
      const p = pendingPull.get(msg.reqId)
      if (p) {
        clearTimeout(p.timer)
        pendingPull.delete(msg.reqId)
        p.reject(new Error(msg.error || 'phone error'))
      }
      break
    }
    case 'fs-push': // phone allocated a transferId for our push
      startSend(msg.reqId, msg.transferId)
      break
    case 'fs-push-result': {
      const s = out.get(msg.transferId)
      if (!s) break
      clearTimeout(s.stallTimer)
      out.delete(msg.transferId)
      if (msg.ok) s.resolve({ ok: true })
      else s.reject(new Error(msg.error || 'phone rejected the file'))
      break
    }
    case 'fs-push-error': {
      const p = pendingPush.get(msg.reqId)
      if (p) {
        clearTimeout(p.timer)
        pendingPush.delete(msg.reqId)
        p.reject(new Error(msg.error || 'phone error'))
      }
      break
    }
    case 'fs-cancel': {
      const r = recv.get(msg.transferId)
      if (r) r.fail(new Error('Cancelled by phone'))
      const s = out.get(msg.transferId)
      if (s) s.abort(new Error('Cancelled by phone'))
      break
    }
    case 'phone-push-begin':
      beginPhoneReceive(msg.reqId, msg.name || 'file', msg.size || 0)
      break
    case 'phone-push-done':
      finishReceive(msg.transferId, msg.size)
      break
    case 'photo-thumb-error': {
      const p = pendingThumb.get(msg.reqId)
      if (p) {
        clearTimeout(p.timer)
        pendingThumb.delete(msg.reqId)
        p.reject(new Error(msg.error || 'thumb failed'))
      }
      break
    }
  }
}
