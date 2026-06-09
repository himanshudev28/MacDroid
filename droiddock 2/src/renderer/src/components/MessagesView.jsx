import { useCallback, useEffect, useRef, useState } from 'react'
import { MessageSquare, RefreshCw, Send, Wifi, Plus } from 'lucide-react'

export default function MessagesView({ linked, onToast, target }) {
  const [threads, setThreads] = useState([])
  const [sel, setSel] = useState(null)
  const [msgs, setMsgs] = useState([])
  const [draft, setDraft] = useState('')
  const [err, setErr] = useState(null)
  const [loading, setLoading] = useState(false)
  const [sending, setSending] = useState(false)
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
      {/* threads sidebar */}
      <div className="flex w-72 shrink-0 flex-col border-r border-line bg-ink/40">
        <div className="flex h-12 shrink-0 items-center justify-between border-b border-line px-4">
          <span className="font-mono text-[10px] tracking-[0.25em] text-dim">THREADS</span>
          <button
            onClick={loadThreads}
            className="border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
          >
            <RefreshCw size={12} className={loading ? 'spinner' : ''} />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {/* New compose entry (pinned at top when from Contacts) */}
          {compose && (
            <button
              onClick={() => { setSel(null); setCompose(compose) }}
              className="rise block w-full border-b border-amber/30 bg-amber/5 px-4 py-3 text-left"
            >
              <div className="flex items-center gap-2">
                <Plus size={11} className="shrink-0 text-amber" />
                <span className="truncate text-[13px] font-medium text-amber">{compose.name}</span>
              </div>
              <p className="mt-0.5 truncate font-mono text-[10px] text-dim">{compose.number}</p>
            </button>
          )}
          {threads.map((t, i) => (
            <button
              key={t.threadId}
              onClick={() => open(t)}
              className={`rise block w-full border-b border-line/50 px-4 py-3 text-left transition-colors hover:bg-panel2 ${
                activeId === t.threadId ? 'border-l-2 border-l-amber bg-panel2' : ''
              }`}
              style={{ animationDelay: `${Math.min(i, 15) * 20}ms` }}
            >
              <div className="flex items-baseline justify-between gap-2">
                <span className="truncate text-[13px] font-medium">{t.name}</span>
                <span className="shrink-0 font-mono text-[9px] text-dim/70">{fmt(t.date)}</span>
              </div>
              <p className="mt-0.5 truncate text-[11px] text-dim">{t.snippet}</p>
            </button>
          ))}
          {threads.length === 0 && !loading && !compose && (
            <p className="p-6 font-mono text-xs text-dim">no conversations</p>
          )}
        </div>
      </div>

      {/* conversation / compose pane */}
      {sel || compose ? (
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex h-12 shrink-0 items-center border-b border-line bg-ink/70 px-5 backdrop-blur">
            <span className="font-display text-[13px] font-semibold">{sendName}</span>
            <span className="ml-3 font-mono text-[10px] text-dim">{sendAddress}</span>
            {compose && (
              <span className="ml-auto rounded border border-amber/30 px-1.5 py-0.5 font-mono text-[9px] text-amber">
                NEW
              </span>
            )}
          </div>
          <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-5 py-4">
            {compose && msgs.length === 0 && (
              <p className="pt-8 text-center font-mono text-xs text-dim/60">
                Start a new conversation with {sendName}
              </p>
            )}
            {msgs.map((m) => (
              <div key={m.id} className={`flex ${m.out ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[70%] border px-3 py-2 text-[13px] leading-relaxed ${
                    m.out
                      ? 'border-amber/30 bg-amber/10 text-fg'
                      : 'border-line bg-panel2 text-fg'
                  }`}
                >
                  {m.body}
                  <div
                    className={`mt-1 font-mono text-[8px] tracking-wider text-dim/70 ${
                      m.out ? 'text-right' : ''
                    }`}
                  >
                    {fmt(m.date)}
                  </div>
                </div>
              </div>
            ))}
            <div ref={endRef} />
          </div>
          <div className="flex shrink-0 items-center gap-2 border-t border-line bg-panel px-4 py-3">
            <input
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && sendNow()}
              placeholder={`Text ${sendName}…`}
              className="min-w-0 flex-1 border border-line bg-ink px-3 py-2 text-[13px] text-fg outline-none transition-colors placeholder:text-dim/50 focus:border-amber/50"
            />
            <button
              onClick={sendNow}
              disabled={sending || !draft.trim()}
              className="flex items-center gap-2 border border-amber/40 px-4 py-2 font-display text-[11px] font-semibold tracking-wider text-amber transition-colors hover:bg-amber/10 disabled:opacity-35"
            >
              <Send size={12} className={sending ? 'spinner' : ''} />
              SEND
            </button>
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

function fmt(d) {
  const date = new Date(Number(d))
  const now = new Date()
  return date.toDateString() === now.toDateString()
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { day: 'numeric', month: 'short' })
}
