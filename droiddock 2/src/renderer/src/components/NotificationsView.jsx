import { useEffect, useState } from 'react'
import { Bell, Wifi, CornerUpLeft, X, Send, Monitor, Phone, AlertCircle } from 'lucide-react'

export default function NotificationsView({ linked, items, onClear, onDismiss, onToast }) {
  const [nativeOn, setNativeOn] = useState(true)
  const [perm, setPerm] = useState(null) // 'authorized'|'denied'|'not-determined'|null

  useEffect(() => {
    window.droid.notifsGetNative().then(setNativeOn)
    window.droid.notifsCheckPerm().then(setPerm)
  }, [])

  const toggleNative = async () => {
    const next = !nativeOn
    await window.droid.notifsSetNative(next)
    setNativeOn(next)
    if (next) {
      // re-check permission after enabling
      const p = await window.droid.notifsCheckPerm()
      setPerm(p)
    }
  }

  if (!linked) {
    return (
      <Empty
        icon={Wifi}
        title="PHONE NOT LINKED"
        body="Notifications need the Wi-Fi link. Pair your phone from the sidebar, then come back."
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* ── header ── */}
      <div className="flex h-12 shrink-0 items-center justify-between border-b border-line px-5">
        <span className="font-mono text-[10px] tracking-[0.25em] text-dim">
          NOTIFICATIONS{items.length ? ` · ${items.length}` : ''}
        </span>
        <div className="flex items-center gap-3">
          {/* Show on Mac toggle */}
          <button
            onClick={toggleNative}
            title={nativeOn ? 'Showing on Mac — click to disable' : 'Click to show on Mac'}
            className={`flex items-center gap-1.5 rounded-md border px-2.5 py-1 font-mono text-[10px] tracking-wider transition-colors ${
              nativeOn
                ? 'border-amber/40 bg-amber/8 text-amber'
                : 'border-line text-dim hover:border-amber/30 hover:text-amber'
            }`}
          >
            <Monitor size={11} />
            {nativeOn ? 'ON MAC' : 'SHOW ON MAC'}
          </button>
          <button
            onClick={onClear}
            className="border border-line px-3 py-1 font-display text-[10px] font-semibold tracking-[0.2em] text-dim transition-colors hover:border-amber/40 hover:text-amber"
          >
            CLEAR
          </button>
        </div>
      </div>

      {/* macOS permission denied warning */}
      {nativeOn && perm === 'denied' && (
        <div className="flex shrink-0 items-start gap-2.5 border-b border-line bg-amber/5 px-5 py-3">
          <AlertCircle size={14} className="mt-0.5 shrink-0 text-amber" />
          <p className="text-[11px] leading-relaxed text-dim">
            macOS notification permission is <b className="text-amber">denied</b> for DroidDock.
            Go to <b className="text-fg">System Settings → Notifications → DroidDock</b> and
            turn it on, then restart the app.
          </p>
        </div>
      )}

      {items.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-8">
          <div className="max-w-sm border border-line bg-panel p-8 text-center">
            <Bell size={22} strokeWidth={1.5} className="mx-auto text-amber" />
            <p className="mt-4 font-display text-sm font-semibold tracking-[0.25em]">
              NO NOTIFICATIONS YET
            </p>
            <p className="mt-2 text-[12px] leading-relaxed text-dim">
              Phone notifications appear here as they arrive.
              {nativeOn
                ? ' They also pop up as macOS notifications — reply without opening this app.'
                : ' Enable "Show on Mac" above to also get macOS pop-ups with reply.'}
            </p>
          </div>
        </div>
      ) : (
        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-4">
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

function CallCard({ n, onDismiss, index }) {
  return (
    <div
      className="rise group border border-line bg-panel2 p-3.5"
      style={{ animationDelay: `${Math.min(index, 15) * 20}ms` }}
    >
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-sm bg-ok/10">
          <Phone size={14} className="text-ok" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="truncate font-mono text-[9px] uppercase tracking-wider text-ok/80">
              Incoming Call
            </span>
            <span className="shrink-0 font-mono text-[9px] text-dim/70">{fmt(n.time)}</span>
          </div>
          <p className="mt-0.5 truncate text-[13px] font-semibold">{n.title}</p>
          {n.text && <p className="mt-0.5 text-[12px] text-dim">{n.text}</p>}
          <div className="mt-2 flex items-center gap-3 opacity-0 transition-opacity group-hover:opacity-100">
            <button
              onClick={() => onDismiss(n.key)}
              className="flex items-center gap-1 font-mono text-[10px] text-dim transition-colors hover:text-bad"
            >
              <X size={11} /> dismiss
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

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

  return (
    <div
      className="rise group border border-line bg-panel2 p-3.5"
      style={{ animationDelay: `${Math.min(index, 15) * 20}ms` }}
    >
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-sm bg-amber/10 font-display text-[11px] font-bold text-amber">
          {(n.app || '?').slice(0, 2).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="truncate font-mono text-[9px] uppercase tracking-wider text-amber/80">
              {n.app || 'app'}
            </span>
            <span className="shrink-0 font-mono text-[9px] text-dim/70">{fmt(n.time)}</span>
          </div>
          {n.title && <p className="mt-0.5 truncate text-[13px] font-semibold">{n.title}</p>}
          {n.text && <p className="mt-0.5 text-[12px] leading-relaxed text-dim">{n.text}</p>}

          {replying ? (
            <div className="mt-2 flex items-center gap-2">
              <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && send()}
                autoFocus
                placeholder="Reply…"
                className="min-w-0 flex-1 border border-line bg-ink px-2.5 py-1.5 text-[12px] text-fg outline-none focus:border-amber/50"
              />
              <button
                onClick={send}
                disabled={sending || !draft.trim()}
                className="border border-amber/40 p-1.5 text-amber transition-colors hover:bg-amber/10 disabled:opacity-35"
              >
                <Send size={12} className={sending ? 'spinner' : ''} />
              </button>
            </div>
          ) : (
            <div className="mt-2 flex items-center gap-3 opacity-0 transition-opacity group-hover:opacity-100">
              {n.replyable && (
                <button
                  onClick={() => setReplying(true)}
                  className="flex items-center gap-1 font-mono text-[10px] text-dim transition-colors hover:text-amber"
                >
                  <CornerUpLeft size={11} /> reply
                </button>
              )}
              <button
                onClick={() => onDismiss(n.key)}
                className="flex items-center gap-1 font-mono text-[10px] text-dim transition-colors hover:text-bad"
              >
                <X size={11} /> dismiss
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function Empty({ icon: Icon, title, body }) {
  return (
    <div className="flex h-full flex-1 items-center justify-center p-8">
      <div className="max-w-sm border border-line bg-panel p-8 text-center">
        <Icon size={22} strokeWidth={1.5} className="mx-auto text-amber" />
        <p className="mt-4 font-display text-sm font-semibold tracking-[0.25em]">{title}</p>
        <p className="mt-2 text-[12px] leading-relaxed text-dim">{body}</p>
      </div>
    </div>
  )
}

function fmt(d) {
  if (!d) return ''
  const date = new Date(Number(d))
  const now = new Date()
  return date.toDateString() === now.toDateString()
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { day: 'numeric', month: 'short' })
}
