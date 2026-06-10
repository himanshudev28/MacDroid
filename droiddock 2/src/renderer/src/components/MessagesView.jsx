import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MessageSquare, RefreshCw, Send, Wifi, Plus, Search, Check } from 'lucide-react'

export default function MessagesView({ linked, onToast, target }) {
  const [threads, setThreads] = useState([])
  const [sel, setSel] = useState(null)
  const [msgs, setMsgs] = useState([])
  const [draft, setDraft] = useState('')
  const [err, setErr] = useState(null)
  const [loading, setLoading] = useState(false)
  const [sending, setSending] = useState(false)
  const [q, setQ] = useState('')
  // compose = { number, name } for a new thread (no existing threadId)
  const [compose, setCompose] = useState(null)
  const endRef = useRef(null)
  const selRef = useRef(null)
  selRef.current = sel
  const targetRef = useRef(null)
  targetRef.current = target

  const loadThreads = useCallback(async () => {
    setLoading(true)
    setErr(null)
    const r = await window.droid.smsThreads()
    setLoading(false)
    if (r.ok) {
      setThreads(r.data)
      return r.data
    } else {
      setErr(r.error)
      return []
    }
  }, [])

  const loadMsgs = useCallback(
    async (t) => {
      const r = await window.droid.smsMessages(t.threadId)
      if (r.ok) setMsgs(r.data.messages)
      else onToast('bad', r.error)
    },
    [onToast]
  )

  // Load threads on mount/link, then auto-open target contact if set
  useEffect(() => {
    if (!linked) return
    loadThreads().then((loadedThreads) => {
      const t = targetRef.current
      if (!t) return
      // Normalize numbers for matching (strip spaces, dashes, +, etc.)
      const norm = (n) => (n || '').replace(/\D/g, '').slice(-10)
      const match = loadedThreads.find((th) => norm(th.address) === norm(t.number))
      if (match) {
        setSel(match)
        setCompose(null)
        setMsgs([])
        loadMsgs(match)
      } else {
        // No existing thread — open a compose window for this contact
        setSel(null)
        setCompose(t)
        setMsgs([])
      }
    })
  }, [linked, loadThreads, loadMsgs])

  useEffect(
    () =>
      window.droid.onSmsChanged(() => {
        if (!linked) return
        loadThreads()
        if (selRef.current) loadMsgs(selRef.current)
      }),
    [linked, loadThreads, loadMsgs]
  )

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [msgs])

  const open = (t) => {
    setSel(t)
    setCompose(null)
    setMsgs([])
    loadMsgs(t)
  }

  // Active address/name — either existing thread or compose target
  const sendAddress = sel?.address ?? compose?.number ?? null
  const sendName = sel?.name ?? compose?.name ?? sendAddress

  const sendNow = async () => {
    const text = draft.trim()
    if (!text || !sendAddress || sending) return
    setSending(true)
    const r = await window.droid.smsSend(sendAddress, text)
    setSending(false)
    if (r.ok) {
      setDraft('')
      setMsgs((m) => [...m, { id: `tmp-${Date.now()}`, body: text, out: true, date: Date.now() }])
      // After first send in compose mode, reload threads so the new one appears
      if (compose) {
        const addr = sendAddress
        setCompose(null)
        loadThreads().then((ts) => {
          const norm = (n) => (n || '').replace(/\D/g, '').slice(-10)
          const match = ts.find((th) => norm(th.address) === norm(addr))
          if (match) {
            setSel(match)
            loadMsgs(match)
          }
        })
      }
    } else onToast('bad', r.error)
  }

  const shown = useMemo(() => {
    const s = q.trim().toLowerCase()
    if (!s) return threads
    return threads.filter(
      (t) =>
        (t.name || '').toLowerCase().includes(s) ||
        (t.address || '').toLowerCase().includes(s) ||
        (t.snippet || '').toLowerCase().includes(s)
    )
  }, [threads, q])

  if (!linked) {
    return (
      <Empty
        icon={Wifi}
        title="PHONE NOT LINKED"
        body="Messages need the Wi-Fi link. Pair your phone from the sidebar, then come back."
      />
    )
  }

  if (err) {
    return (
      <Empty icon={MessageSquare} title="CAN'T READ MESSAGES" body={err}>
        <button
          onClick={loadThreads}
          className="mt-4 border border-amber/40 px-4 py-1.5 font-display text-[11px] font-semibold tracking-[0.2em] text-amber hover:bg-amber/10"
        >
          RETRY
        </button>
      </Empty>
    )
  }

  const activeId = sel?.threadId ?? null

  return (
    <div className="flex h-full min-h-0">
      {/* ── conversation list ── */}
      <div className="flex w-80 shrink-0 flex-col border-r border-line bg-ink/40">
        <div className="flex h-14 shrink-0 items-center justify-between px-5">
          <span className="font-display text-[15px] font-semibold tracking-wide text-fg">
            Messages
          </span>
          <button
            onClick={loadThreads}
            title="Refresh"
            className="rounded-md border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
          >
            <RefreshCw size={13} className={loading ? 'spinner' : ''} />
          </button>
        </div>

        <div className="px-4 pb-3">
          <div className="flex items-center gap-2 rounded-lg border border-line bg-panel2/60 px-3 py-2">
            <Search size={13} className="shrink-0 text-dim" />
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Search messages"
              className="min-w-0 flex-1 bg-transparent text-[12px] text-fg outline-none placeholder:text-dim/60"
            />
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
          {compose && (
            <button
              onClick={() => {
                setSel(null)
                setCompose(compose)
              }}
              className="rise mb-1 flex w-full items-center gap-3 rounded-xl border border-amber/30 bg-amber/5 px-3 py-2.5 text-left"
            >
              <Avatar name={compose.name} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <Plus size={11} className="shrink-0 text-amber" />
                  <span className="truncate text-[13px] font-medium text-amber">{compose.name}</span>
                </div>
                <p className="mt-0.5 truncate font-mono text-[10px] text-dim">{compose.number}</p>
              </div>
            </button>
          )}

          {shown.map((t, i) => {
            const active = activeId === t.threadId
            return (
              <button
                key={t.threadId}
                onClick={() => open(t)}
                className={`rise mb-0.5 flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                  active ? 'bg-amber/10 ring-1 ring-amber/30' : 'hover:bg-panel2'
                }`}
                style={{ animationDelay: `${Math.min(i, 15) * 20}ms` }}
              >
                <Avatar name={t.name} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <span
                      className={`truncate text-[13px] font-medium ${active ? 'text-amber' : 'text-fg'}`}
                    >
                      {t.name}
                    </span>
                    <span className="shrink-0 font-mono text-[9px] text-dim/70">{fmt(t.date)}</span>
                  </div>
                  <p className="mt-0.5 truncate text-[11px] text-dim">{t.snippet}</p>
                </div>
              </button>
            )
          })}

          {shown.length === 0 && !loading && !compose && (
            <p className="p-6 text-center font-mono text-xs text-dim">
              {q ? 'no matches' : 'no conversations'}
            </p>
          )}
        </div>
      </div>

      {/* ── conversation / compose pane ── */}
      {sel || compose ? (
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-ink/70 px-5 backdrop-blur">
            <Avatar name={sendName} size={34} />
            <div className="min-w-0">
              <p className="truncate font-display text-[14px] font-semibold text-fg">{sendName}</p>
              <p className="truncate font-mono text-[10px] text-dim">{sendAddress}</p>
            </div>
            {compose && (
              <span className="ml-auto rounded-full border border-amber/30 px-2 py-0.5 font-mono text-[9px] tracking-wider text-amber">
                NEW
              </span>
            )}
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
            {compose && msgs.length === 0 && (
              <p className="pt-10 text-center font-mono text-xs text-dim/60">
                Start a new conversation with {sendName}
              </p>
            )}
            {withDividers(msgs).map((row) =>
              row.divider ? (
                <div key={row.key} className="my-3 flex items-center justify-center">
                  <span className="rounded-full bg-panel2 px-3 py-1 font-mono text-[9px] tracking-[0.18em] text-dim">
                    {row.label}
                  </span>
                </div>
              ) : (
                <Bubble key={row.m.id} m={row.m} />
              )
            )}
            <div ref={endRef} />
          </div>

          <div className="shrink-0 border-t border-line bg-panel/60 px-4 py-3">
            <div className="flex items-center gap-2 rounded-2xl border border-line bg-ink px-3 py-1.5 focus-within:border-amber/50">
              <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && sendNow()}
                placeholder={`Message ${sendName}…`}
                className="min-w-0 flex-1 bg-transparent px-1 py-1.5 text-[13px] text-fg outline-none placeholder:text-dim/50"
              />
              <button
                onClick={sendNow}
                disabled={sending || !draft.trim()}
                title="Send (carrier rates apply)"
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-amber text-ink transition-opacity hover:opacity-90 disabled:opacity-30"
              >
                <Send size={15} className={sending ? 'spinner' : ''} />
              </button>
            </div>
          </div>
        </div>
      ) : (
        <Empty
          icon={MessageSquare}
          title="PICK A CONVERSATION"
          body="SMS sends through your phone's SIM — carrier rates apply as usual."
        />
      )}
    </div>
  )
}

function Bubble({ m }) {
  return (
    <div className={`mb-1.5 flex ${m.out ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[72%] ${m.out ? 'items-end' : 'items-start'} flex flex-col`}>
        <div
          className={`px-3.5 py-2 text-[13px] leading-relaxed ${
            m.out
              ? 'rounded-2xl rounded-br-md bg-amber text-ink'
              : 'rounded-2xl rounded-bl-md bg-panel2 text-fg'
          }`}
        >
          {m.body}
        </div>
        <div
          className={`mt-0.5 flex items-center gap-1 px-1 font-mono text-[8px] tracking-wider text-dim/70 ${
            m.out ? 'flex-row-reverse' : ''
          }`}
        >
          <span>{fmt(m.date)}</span>
          {m.out && <Check size={9} className="text-dim/60" />}
        </div>
      </div>
    </div>
  )
}

/** Interleave date-divider rows into the message list. */
function withDividers(msgs) {
  const out = []
  let last = null
  for (const m of msgs) {
    const label = dayLabel(m.date)
    if (label !== last) {
      out.push({ divider: true, key: `d-${label}-${m.id}`, label })
      last = label
    }
    out.push({ divider: false, m })
  }
  return out
}

const AVATAR_COLORS = ['#FFB454', '#5B9BFF', '#B78BFF', '#79D68B', '#F0A35E', '#E26D9C']

function avatarColor(s) {
  let h = 0
  for (const c of String(s || '')) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

function initials(name) {
  const p = String(name || '?').trim().split(/\s+/)
  const s = ((p[0]?.[0] || '') + (p[1]?.[0] || '')).toUpperCase()
  return s || '#'
}

function Avatar({ name, size = 40 }) {
  const c = avatarColor(name)
  return (
    <div
      style={{ width: size, height: size, background: `${c}22`, color: c }}
      className="flex shrink-0 items-center justify-center rounded-full font-display text-[12px] font-semibold"
    >
      {initials(name)}
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

function dayLabel(d) {
  const date = new Date(Number(d))
  const now = new Date()
  const day = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diff = Math.round((today - day) / 86400000)
  if (diff === 0) return 'TODAY'
  if (diff === 1) return 'YESTERDAY'
  return date.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' }).toUpperCase()
}

function fmt(d) {
  const date = new Date(Number(d))
  const now = new Date()
  return date.toDateString() === now.toDateString()
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { day: 'numeric', month: 'short' })
}
