import { useCallback, useEffect, useRef, useState } from 'react'
import { Image as ImageIcon, RefreshCw, Download, Usb, Play, Film, Maximize2 } from 'lucide-react'

const CONCURRENCY = 3

const fmtDuration = (ms) => {
  if (!ms) return ''
  const s = Math.round(ms / 1000)
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

export default function PhotosView({ available, onToast }) {
  const [items, setItems] = useState([])
  const [thumbs, setThumbs] = useState({}) // path -> dataURL | 'err'
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState(null)

  // lazy thumbnail loader: only fetch what scrolls into view, a few at a time
  const queueRef = useRef([])
  const activeRef = useRef(0)
  const requestedRef = useRef(new Set())
  const aliveRef = useRef(true)

  const pump = useCallback(() => {
    while (activeRef.current < CONCURRENCY && queueRef.current.length) {
      const item = queueRef.current.shift()
      activeRef.current++
      window.droid.photosThumb(item).then((r) => {
        if (!aliveRef.current) return
        setThumbs((t) => ({ ...t, [item.path]: r.ok ? r.data : 'err' }))
        activeRef.current--
        pump()
      })
    }
  }, [])

  const requestThumb = useCallback(
    (item) => {
      if (requestedRef.current.has(item.path)) return
      requestedRef.current.add(item.path)
      queueRef.current.push(item)
      pump()
    },
    [pump]
  )

  const load = useCallback(async () => {
    if (!available) return
    setLoading(true)
    setErr(null)
    setThumbs({})
    queueRef.current = []
    requestedRef.current = new Set()
    const r = await window.droid.photosList()
    setLoading(false)
    if (!r.ok) return setErr(r.error)
    setItems(r.data.items)
  }, [available])

  useEffect(() => {
    aliveRef.current = true
    if (available) load()
    return () => {
      aliveRef.current = false
      queueRef.current = []
    }
  }, [available, load])

  const download = async (item) => {
    const r = await window.droid.photosPull(item)
    r.ok ? onToast('ok', `Saved to Downloads — ${item.name}`) : onToast('bad', r.error)
  }

  const open = async (item) => {
    onToast('info', `Opening ${item.name}…`)
    const r = await window.droid.photosOpen(item)
    if (!r.ok) onToast('bad', r.error)
  }

  if (!available) {
    return (
      <Empty
        icon={Usb}
        title="NO DEVICE"
        body="Photos come from your phone — connect over ADB or link the phone app, and they'll appear here."
      />
    )
  }

  if (err) {
    return (
      <Empty icon={ImageIcon} title="CAN'T READ PHOTOS" body={err}>
        <button
          onClick={load}
          className="mt-4 border border-amber/40 px-4 py-1.5 font-display text-[11px] font-semibold tracking-[0.2em] text-amber hover:bg-amber/10"
        >
          RETRY
        </button>
      </Empty>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-line px-5">
        <span className="font-mono text-[10px] tracking-[0.25em] text-dim">
          PHOTOS &amp; VIDEOS · {items.length}
          {loading ? ' · scanning…' : ''}
        </span>
        <button
          onClick={load}
          className="border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
        >
          <RefreshCw size={12} className={loading ? 'spinner' : ''} />
        </button>
      </div>

      {items.length === 0 && !loading ? (
        <Empty
          icon={ImageIcon}
          title="NOTHING FOUND"
          body="No photos or videos found on this phone."
        />
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-2">
            {items.map((it) => (
              <Tile
                key={it.path}
                it={it}
                thumb={thumbs[it.path]}
                onVisible={requestThumb}
                onOpen={() => open(it)}
                onDownload={() => download(it)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function Tile({ it, thumb, onVisible, onOpen, onDownload }) {
  const ref = useRef(null)
  const isVideo = it.kind === 'video'

  useEffect(() => {
    if (thumb) return // already loaded/errored
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          onVisible(it)
          io.disconnect()
        }
      },
      { rootMargin: '300px' }
    )
    io.observe(el)
    return () => io.disconnect()
  }, [it.path, thumb, onVisible])

  return (
    <div
      ref={ref}
      onClick={onOpen}
      title={isVideo ? 'Open video' : 'Open photo'}
      className="group relative aspect-square cursor-pointer overflow-hidden border border-line bg-panel2"
    >
      {thumb && thumb !== 'err' ? (
        <img src={thumb} alt={it.name} className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full w-full items-center justify-center">
          {thumb === 'err' ? (
            // a video with no preview (e.g. over ADB) gets a film placeholder
            isVideo ? (
              <Film size={18} className="text-dim/50" />
            ) : (
              <ImageIcon size={18} className="text-dim/40" />
            )
          ) : (
            <div className="h-5 w-5 animate-pulse rounded-full bg-line" />
          )}
        </div>
      )}

      {isVideo && (
        <>
          {/* play affordance over the still/placeholder */}
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <span className="rounded-full bg-ink/55 p-2 backdrop-blur-sm">
              <Play size={16} className="text-fg" fill="currentColor" />
            </span>
          </div>
          {it.duration ? (
            <span className="pointer-events-none absolute bottom-1.5 left-1.5 rounded-sm bg-ink/75 px-1.5 py-0.5 font-mono text-[9px] text-fg backdrop-blur">
              {fmtDuration(it.duration)}
            </span>
          ) : null}
        </>
      )}

      {/* hover hint that the tile opens full-res (videos already show a ▶) */}
      {!isVideo && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center opacity-0 transition-opacity group-hover:opacity-100">
          <span className="rounded-full bg-ink/55 p-2 backdrop-blur-sm">
            <Maximize2 size={15} className="text-fg" />
          </span>
        </div>
      )}

      <button
        onClick={(e) => {
          e.stopPropagation()
          onDownload()
        }}
        title={isVideo ? 'Download video to Mac' : 'Download to Mac'}
        className="absolute bottom-1.5 right-1.5 border border-line bg-ink/80 p-1.5 text-fg opacity-0 backdrop-blur transition-opacity hover:border-amber/40 hover:text-amber group-hover:opacity-100"
      >
        <Download size={12} />
      </button>
    </div>
  )
}

function Empty({ icon: Icon, title, body, children }) {
  return (
    <div className="flex h-full flex-1 items-center justify-center p-8">
      <div className="max-w-sm border border-line bg-panel p-8 text-center">
        <Icon size={22} strokeWidth={1.5} className="mx-auto text-amber" />
        <p className="mt-4 font-display text-sm font-semibold tracking-[0.25em]">{title}</p>
        <p className="mt-2 text-[12px] leading-relaxed text-dim">{body}</p>
        {children}
      </div>
    </div>
  )
}
