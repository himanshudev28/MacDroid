import { useEffect, useState } from 'react'
import { Bell, Wifi, CornerUpLeft, X, Send, Monitor, Phone, AlertCircle } from 'lucide-react'

export default function NotificationsView({ linked, items, onClear, onDismiss, onToast }) {
  const [nativeOn, setNativeOn] = useState(true)
  const [perm, setPerm] = useState(null)

  useEffect(() => {
    window.droid.notifsGetNative().then(setNativeOn)
    window.droid.notifsCheckPerm().then(setPerm)
  }, [])

  const toggleNative = async () => {
    const next = !nativeOn
    await window.droid.notifsSetNative(next)
    setNativeOn(next)
    if (next) {
      const p = await window.droid.notifsCheckPerm()
      setPerm(p)
    }
  }

  if (!linked) {
    return (
      <EmptyState
        icon={Wifi}
        title="Phone not linked"
        body="Notifications need the Wi-Fi link. Pair your phone from the sidebar, then come back."
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* ── Header ── */}
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-line px-4">
        <span className="font-mono text-[10px] tracking-[0.12em] text-dim/70">
          Notifications{items.length ? ` · ${items.length}` : ''}
        </span>
        <div className="flex items-center gap-2">
          <button
            onClick={toggleNative}
            title={nativeOn ? 'Showing on Mac — click to disable' : 'Click to show on Mac'}
            className={`flex items-center gap-1.5 rounded-lg border px-2.5 py-1 font-mono text-[9.5px] tracking-[0.04em] transition-all duration-150 ${
              nativeOn
                ? 'border-amber/30 bg-amber/8 text-amber hover:bg-amber/14'
                : 'border-line text-dim/60 hover:border-amber/25 hover:text-amber/80'
            }`}
          >
            <Monitor size={11} />
            {nativeOn ? 'On Mac' : 'Show on Mac'}
          </button>
          <button
            onClick={onClear}
            className="rounded-lg border border-line px-2.5 py-1 font-mono text-[9.5px] text-dim/60 transition-all hover:border-line hover:bg-panel2 hover:text-fg/70"
          >
            Clear
          </button>
        </div>
      </div>

      {/* ── Permission warning ── */}
      {nativeOn && perm === 'denied' && (
        <div className="flex shrink-0 items-start gap-2.5 border-b border-line bg-amber/5 px-4 py-3">
          <AlertCircle size={13} className="mt-0.5 shrink-0 text-amber" />
          <p className="text-[11px] leading-relaxed text-dim/80">
            macOS notifications are <span className="font-medium text-amber">denied</span> for DroidDock.
            Go to <span className="font-medium text-fg/80">System Settings → Notifications → DroidDock</span> and turn it on, then restart.
          </p>
        </div>
      )}

      {/* ── Content ── */}
      {items.length === 0 ? (
        <EmptyState
          icon={Bell}
          title="No notifications yet"
          body={
            nativeOn
              ? 'Phone notifications appear here and as macOS pop-ups with inline reply.'
              : 'Phone notifications appear here. Enable "Show on Mac" to also get macOS pop-ups.'
          }
        />
      ) : (
        <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-3">
          {items.map((n, i) =>
            n.type === 'call' ? (
              <CallCard key={n.key + i} n={n} onDismiss={onDismiss} index={i} />
            ) : (
              <NotifCard key={n.key + i} n={n} onDismiss={onDismiss} onToast={onToast} index={i} />
            )
          )}
        </div>
      )}
    </div>
  )
}

/* ── Call card ─────────────────────────────────────────────────────── */
function CallCard({ n, onDismiss, index }) {
  return (
    <div
      className="rise group rounded-xl border border-ok/15 bg-ok/5 p-3.5 luminous-sm"
      style={{ animationDelay: `${Math.min(index, 15) * 18}ms` }}
    >
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-ok/12 border border-ok/20">
          <Phone size={14} className="text-ok" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="font-mono text-[9.5px] tracking-[0.06em] text-ok/70 uppercase">
              Incoming Call
            </span>
            <span className="shrink-0 font-mono text-[9px] text-dim/50">{fmt(n.time)}</span>
          </div>
          <p className="mt-0.5 truncate text-[13px] font-semibold text-fg/90">{n.title}</p>
          {n.text && <p className="mt-0.5 text-[11.5px] text-dim/70">{n.text}</p>}
          <div className="mt-2 flex items-center opacity-0 transition-opacity group-hover:opacity-100">
            <button
              onClick={() => onDismiss(n.key)}
              className="flex items-center gap-1 font-mono text-[9.5px] text-dim/50 transition-colors hover:text-bad"
            >
              <X size={10} /> dismiss
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

/* ── Notification card ─────────────────────────────────────────────── */
function NotifCard({ n, onDismiss, onToast, index }) {
  const [replying, setReplying] = useState(false)
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)

  const send = async () => {
    const text = draft.trim()
    if (!text || sending) return
    setSending(true)
    const r = await window.droid.notifReply(n.key, text)
    setSending(false)
    if (r.ok) {
      onToast('ok', `Reply sent to ${n.app || 'app'}`)
      setDraft('')
      setReplying(false)
    } else {
      onToast('bad', r.error || 'Reply failed — phone not connected')
    }
  }

  const initials = (n.app || '?').slice(0, 2).toUpperCase()

  return (
    <div
      className="rise group rounded-xl border border-line bg-panel2 p-3.5 luminous-sm transition-colors hover:border-line"
      style={{ animationDelay: `${Math.min(index, 15) * 18}ms` }}
    >
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-amber/10 border border-amber/15 font-mono text-[10px] font-bold text-amber">
          {initials}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="font-mono text-[9.5px] tracking-[0.06em] text-amber/70 uppercase">
              {n.app || 'app'}
            </span>
            <span className="shrink-0 font-mono text-[9px] text-dim/50">{fmt(n.time)}</span>
          </div>
          {n.title && (
            <p className="mt-0.5 truncate text-[13px] font-semibold text-fg/90">{n.title}</p>
          )}
          {n.text && (
            <p className="mt-0.5 text-[11.5px] leading-snug text-dim/80">{n.text}</p>
          )}

          {replying ? (
            <div className="mt-2.5 flex items-center gap-2">
              <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && send()}
                autoFocus
                placeholder="Reply…"
                className="min-w-0 flex-1 rounded-lg border border-line bg-panel3 px-2.5 py-1.5 text-[12px] text-fg/90 outline-none placeholder:text-dim/40 focus:border-amber/35 transition-colors"
              />
              <button
                onClick={send}
                disabled={sending || !draft.trim()}
                className="rounded-lg border border-amber/35 bg-amber/8 p-1.5 text-amber transition-all hover:bg-amber/15 disabled:opacity-30"
              >
                <Send size={12} className={sending ? 'spinner' : ''} />
              </button>
            </div>
          ) : (
            <div className="mt-2 flex items-center gap-3 opacity-0 transition-opacity group-hover:opacity-100">
              {n.replyable && (
                <button
                  onClick={() => setReplying(true)}
                  className="flex items-center gap-1 font-mono text-[9.5px] text-dim/50 transition-colors hover:text-amber"
                >
                  <CornerUpLeft size={10} /> reply
                </button>
              )}
              <button
                onClick={() => onDismiss(n.key)}
                className="flex items-center gap-1 font-mono text-[9.5px] text-dim/50 transition-colors hover:text-bad"
              >
                <X size={10} /> dismiss
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/* ── Empty state ───────────────────────────────────────────────────── */
function EmptyState({ icon: Icon, title, body }) {
  return (
    <div className="flex h-full flex-1 items-center justify-center p-8">
      <div className="rise max-w-72 rounded-2xl border border-line bg-panel2 p-8 luminous-sm text-center">
        <div className="mx-auto mb-4 flex h-10 w-10 items-center justify-center rounded-xl bg-amber/8 border border-amber/15">
          <Icon size={18} strokeWidth={1.5} className="text-amber" />
        </div>
        <p className="text-[13px] font-semibold text-fg/80">{title}</p>
        <p className="mt-2 text-[11.5px] leading-relaxed text-dim/70">{body}</p>
      </div>
    </div>
  )
}

/* ── Time formatter ────────────────────────────────────────────────── */
function fmt(d) {
  if (!d) return ''
  const date = new Date(Number(d))
  const now  = new Date()
  return date.toDateString() === now.toDateString()
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { day: 'numeric', month: 'short' })
}
