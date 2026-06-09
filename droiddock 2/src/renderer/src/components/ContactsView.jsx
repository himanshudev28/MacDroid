import { useCallback, useEffect, useMemo, useState } from 'react'
import { Users, Wifi, RefreshCw, Star, Phone, MessageSquare, Search } from 'lucide-react'

const COLORS = ['#FFB454', '#79D68B', '#6FA8FF', '#C97FEB', '#FF8FB0', '#5FD0C8']
const colorFor = (s) => COLORS[[...(s || '?')].reduce((a, c) => a + c.charCodeAt(0), 0) % COLORS.length]

export default function ContactsView({ linked, onToast, onOpenSms, onCall }) {
  const [contacts, setContacts] = useState([])
  const [q, setQ] = useState('')
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    setErr(null)
    const r = await window.droid.contactsList()
    setLoading(false)
    if (r.ok) setContacts(r.data)
    else setErr(r.error)
  }, [])

  useEffect(() => {
    if (linked) load()
  }, [linked, load])

  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase()
    if (!t) return contacts
    return contacts.filter(
      (c) => c.name?.toLowerCase().includes(t) || (c.number || '').includes(t)
    )
  }, [contacts, q])

  const starred = filtered.filter((c) => c.starred)
  const rest = filtered.filter((c) => !c.starred)

  if (!linked) {
    return (
      <Empty
        icon={Wifi}
        title="PHONE NOT LINKED"
        body="Contacts need the Wi-Fi link. Pair your phone from the sidebar, then come back."
      />
    )
  }

  if (err) {
    return (
      <Empty icon={Users} title="CAN'T READ CONTACTS" body={err}>
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
      <div className="flex h-12 shrink-0 items-center justify-between gap-3 border-b border-line px-5">
        <span className="font-mono text-[10px] tracking-[0.25em] text-dim">
          CONTACTS · {contacts.length}
        </span>
        <button
          onClick={load}
          className="border border-line p-1.5 text-dim transition-colors hover:border-amber/40 hover:text-amber"
        >
          <RefreshCw size={12} className={loading ? 'spinner' : ''} />
        </button>
      </div>

      <div className="shrink-0 border-b border-line p-3">
        <div className="flex items-center gap-2 border border-line bg-ink px-3 py-2">
          <Search size={13} className="text-dim/60" />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search contacts…"
            className="min-w-0 flex-1 bg-transparent text-[13px] text-fg outline-none placeholder:text-dim/50"
          />
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading && contacts.length === 0 && (
          <p className="p-6 font-mono text-xs text-dim">reading contacts…</p>
        )}
        {starred.length > 0 && (
          <>
            <Header label="STARRED" count={starred.length} />
            {starred.map((c, i) => (
              <Row key={'s' + i} c={c} onOpenSms={onOpenSms} onCall={onCall} onToast={onToast} />
            ))}
          </>
        )}
        {rest.length > 0 && <Header label="ALL CONTACTS" count={rest.length} />}
        {rest.map((c, i) => (
          <Row key={i} c={c} onOpenSms={onOpenSms} onCall={onCall} onToast={onToast} />
        ))}
        {!loading && filtered.length === 0 && (
          <p className="p-6 font-mono text-xs text-dim">no matches</p>
        )}
      </div>
    </div>
  )
}

function Header({ label, count }) {
  return (
    <div className="sticky top-0 flex items-center gap-2 bg-ink/90 px-5 py-1.5 backdrop-blur">
      <span className="font-mono text-[9px] tracking-[0.25em] text-dim/70">{label}</span>
      <span className="font-mono text-[9px] text-dim/50">{count}</span>
    </div>
  )
}

function Row({ c, onOpenSms, onCall, onToast }) {
  const handleCall = async () => {
    const r = await window.droid.callContact(c.number)
    if (r.ok) {
      onToast?.('ok', `Calling ${c.name || c.number}…`)
      onCall?.({ number: c.number, name: c.name || c.number })
    } else {
      onToast?.('bad', r.error || 'Call failed — is the phone connected?')
    }
  }

  const handleSms = () => {
    // Navigate to Messages view in the Mac app and open this contact's thread
    onOpenSms?.({ number: c.number, name: c.name || c.number })
  }

  return (
    <div className="group flex items-center gap-3 border-b border-line/50 px-5 py-2.5 hover:bg-panel2">
      <div
        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full font-display text-[12px] font-bold text-ink"
        style={{ background: colorFor(c.name) }}
      >
        {(c.name || '?').slice(0, 1).toUpperCase()}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <span className="truncate text-[13px] font-medium">{c.name || c.number}</span>
          {c.starred && <Star size={11} className="shrink-0 fill-amber text-amber" />}
        </div>
        {c.number && <p className="truncate font-mono text-[10px] text-dim">{c.number}</p>}
      </div>
      {c.number && (
        <div className="flex items-center gap-1.5">
          <button
            onClick={handleCall}
            title="Call via phone"
            className="border border-line p-1.5 text-dim opacity-0 transition-all hover:border-ok/40 hover:text-ok group-hover:opacity-100"
          >
            <Phone size={13} />
          </button>
          <button
            onClick={handleSms}
            title="Open messages"
            className="border border-line p-1.5 text-dim opacity-0 transition-all hover:border-amber/40 hover:text-amber group-hover:opacity-100"
          >
            <MessageSquare size={13} />
          </button>
        </div>
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
