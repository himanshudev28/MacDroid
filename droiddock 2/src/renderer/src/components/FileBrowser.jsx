import { useRef, useState } from 'react'
import {
  Folder,
  FileText,
  Download,
  RefreshCw,
  Upload,
  ChevronRight,
  ArrowUp,
  X,
  Search,
  Pencil,
  Trash2,
  Check
} from 'lucide-react'

const fmtBytes = (n) => {
  if (n == null) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export default function FileBrowser({
  path,
  entries,
  loading,
  busy,
  transport,
  prog,
  onCancel,
  onEnter,
  onUp,
  onJump,
  onRefresh,
  onDownload,
  onUpload,
  onDrop,
  onRename,
  onDelete
}) {
  const [hover, setHover] = useState(false)
  const [query, setQuery] = useState('')
  const crumbs = path.split('/').filter(Boolean)
  const q = query.trim().toLowerCase()
  const shown = q ? entries.filter((e) => e.name.toLowerCase().includes(q)) : entries

  return (
    <div
      className="flex h-full flex-col"
      onDragOver={(e) => {
        e.preventDefault()
        setHover(true)
      }}
      onDragLeave={() => setHover(false)}
      onDrop={(e) => {
        e.preventDefault()
        setHover(false)
        if (e.dataTransfer.files?.length) onDrop(e.dataTransfer.files)
      }}
    >
      {/* path bar */}
      <div className="flex h-12 shrink-0 items-center gap-1 border-b border-line bg-ink/70 px-4 backdrop-blur">
        <button
          onClick={onUp}
          disabled={crumbs.length <= 1}
          title="Up one level"
          className="mr-1 border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber disabled:opacity-30"
        >
          <ArrowUp size={13} />
        </button>

        <div className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden font-mono text-[11px]">
          {crumbs.map((c, i) => (
            <span key={i} className="flex items-center gap-1">
              {i > 0 && <ChevronRight size={11} className="shrink-0 text-dim/50" />}
              <button
                onClick={() => onJump(i)}
                className={`truncate px-1 py-0.5 transition-colors hover:text-amber ${
                  i === crumbs.length - 1 ? 'text-amber' : 'text-dim'
                }`}
              >
                {i === 0 && c === 'sdcard' ? 'Internal storage' : c}
              </button>
            </span>
          ))}
        </div>

        {transport && (
          <span
            title={transport === 'adb' ? 'Over ADB (fast)' : 'Over the phone app link'}
            className={`mr-1 border px-1.5 py-0.5 font-mono text-[8px] tracking-[0.2em] ${
              transport === 'adb' ? 'border-amber/40 text-amber' : 'border-ok/40 text-ok'
            }`}
          >
            {transport === 'adb' ? 'ADB' : 'APP LINK'}
          </span>
        )}
        <button
          onClick={onRefresh}
          title="Refresh"
          className="border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
        >
          <RefreshCw size={13} className={loading ? 'spinner' : ''} />
        </button>
        <button
          onClick={onUpload}
          className="ml-1 flex items-center gap-2 border border-amber/40 px-3 py-1.5 font-display text-[11px] font-semibold tracking-wider text-amber transition-colors hover:bg-amber/10"
        >
          <Upload size={12} />
          SEND TO PHONE
        </button>
      </div>

      {/* search filter (client-side, current folder) */}
      <div className="flex h-9 shrink-0 items-center gap-2 border-b border-line bg-ink/40 px-4">
        <Search size={12} className="shrink-0 text-dim" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Filter this folder…"
          className="min-w-0 flex-1 bg-transparent font-mono text-[11px] text-fg placeholder:text-dim/60 focus:outline-none"
        />
        {query && (
          <button
            onClick={() => setQuery('')}
            title="Clear filter"
            className="shrink-0 text-dim transition-colors hover:text-amber"
          >
            <X size={12} />
          </button>
        )}
      </div>

      {prog && (
        <div className="flex shrink-0 items-center gap-3 border-b border-line bg-panel2 px-4 py-2">
          <span className="font-mono text-[10px] uppercase tracking-wider text-ok">
            {prog.dir === 'push' ? 'Sending' : 'Receiving'}
          </span>
          <span className="min-w-0 flex-1 truncate text-[12px]">{prog.name}</span>
          <span className="shrink-0 font-mono text-[10px] text-dim">
            {fmtBytes(prog.sent)} / {fmtBytes(prog.total)}
          </span>
          <div className="h-1 w-28 shrink-0 bg-ink">
            <div
              className="h-full bg-ok transition-all"
              style={{ width: `${prog.total ? Math.round((prog.sent / prog.total) * 100) : 0}%` }}
            />
          </div>
          {prog.tid != null && onCancel && (
            <button
              onClick={() => onCancel(prog.tid)}
              title="Cancel transfer"
              className="shrink-0 border border-line p-1 text-dim transition-colors hover:border-bad/50 hover:text-bad"
            >
              <X size={12} />
            </button>
          )}
        </div>
      )}

      {/* listing */}
      <div
        className={`relative min-h-0 flex-1 overflow-y-auto transition-shadow ${
          hover ? 'shadow-[inset_0_0_0_2px_var(--color-amber)]' : ''
        }`}
      >
        {hover && (
          <div className="pointer-events-none sticky top-0 z-10 bg-amber/15 px-4 py-2 text-center font-display text-[11px] font-semibold tracking-[0.2em] text-amber">
            DROP TO SEND → /sdcard/Download
          </div>
        )}

        {entries.length === 0 && !loading ? (
          <p className="p-8 font-mono text-xs text-dim">empty directory</p>
        ) : shown.length === 0 ? (
          <p className="p-8 font-mono text-xs text-dim">no matches for “{query}”</p>
        ) : (
          <ul className="px-2 py-2">
            {shown.map((e, i) => (
              <li
                key={e.name}
                className="rise"
                style={{ animationDelay: `${Math.min(i, 18) * 22}ms` }}
              >
                <Row
                  entry={e}
                  busy={busy[`dl:${e.name}`]}
                  onOpen={() => e.dir && onEnter(e.name)}
                  onDownload={() => onDownload(e)}
                  onRename={onRename ? (newName) => onRename(e, newName) : null}
                  onDelete={onDelete ? () => onDelete(e) : null}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function Row({ entry, busy, onOpen, onDownload, onRename, onDelete }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(entry.name)
  const committed = useRef(false)

  const startEdit = (ev) => {
    ev?.stopPropagation()
    committed.current = false
    setName(entry.name)
    setEditing(true)
  }

  const commitRename = (ev) => {
    ev?.stopPropagation()
    if (committed.current) return // blur + click can both fire — run once
    committed.current = true
    const next = name.trim()
    setEditing(false)
    if (next && next !== entry.name) onRename(next)
    else setName(entry.name)
  }

  const cancelEdit = (ev) => {
    ev?.stopPropagation()
    committed.current = true
    setName(entry.name)
    setEditing(false)
  }

  return (
    <div
      onDoubleClick={editing ? undefined : onOpen}
      onClick={entry.dir && !editing ? onOpen : undefined}
      className={`group flex items-center gap-3 border-b border-line/50 px-3 py-2 transition-colors hover:bg-panel2 ${
        entry.dir && !editing ? 'cursor-pointer' : ''
      }`}
    >
      {entry.dir ? (
        <Folder size={15} strokeWidth={1.75} className="shrink-0 text-amber/80" />
      ) : (
        <FileText size={15} strokeWidth={1.5} className="shrink-0 text-dim" />
      )}

      {editing ? (
        <input
          autoFocus
          value={name}
          onClick={(ev) => ev.stopPropagation()}
          onChange={(ev) => setName(ev.target.value)}
          onKeyDown={(ev) => {
            if (ev.key === 'Enter') commitRename(ev)
            if (ev.key === 'Escape') cancelEdit(ev)
          }}
          onBlur={commitRename}
          className="min-w-0 flex-1 border border-amber/40 bg-ink px-2 py-0.5 font-mono text-[12px] text-fg focus:outline-none"
        />
      ) : (
        <span className="min-w-0 flex-1 truncate text-[13px]">{entry.name}</span>
      )}

      <div className="flex shrink-0 items-center gap-1">
        {editing ? (
          <button
            onClick={commitRename}
            title="Rename"
            className="border border-transparent p-1.5 text-ok transition-all hover:border-ok/40"
          >
            <Check size={13} />
          </button>
        ) : (
          <>
            {!entry.dir && (
              <button
                onClick={(ev) => {
                  ev.stopPropagation()
                  onDownload()
                }}
                disabled={busy}
                title="Save to Mac Downloads"
                className="border border-transparent p-1.5 text-dim opacity-0 transition-all hover:border-amber/40 hover:text-amber group-hover:opacity-100 disabled:opacity-100"
              >
                <Download size={13} className={busy ? 'spinner text-amber' : ''} />
              </button>
            )}
            {onRename && (
              <button
                onClick={startEdit}
                title="Rename"
                className="border border-transparent p-1.5 text-dim opacity-0 transition-all hover:border-amber/40 hover:text-amber group-hover:opacity-100"
              >
                <Pencil size={13} />
              </button>
            )}
            {onDelete && (
              <button
                onClick={(ev) => {
                  ev.stopPropagation()
                  onDelete()
                }}
                title="Delete from phone"
                className="border border-transparent p-1.5 text-dim opacity-0 transition-all hover:border-bad/50 hover:text-bad group-hover:opacity-100"
              >
                <Trash2 size={13} />
              </button>
            )}
          </>
        )}
      </div>
    </div>
  )
}
