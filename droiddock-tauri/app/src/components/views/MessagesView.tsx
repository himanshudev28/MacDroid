import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { avatarColor, initials, fmtTime, dayLabel } from "../../lib/ui";
import {
  smsThreads,
  smsMessages,
  smsSend,
  on,
  type SmsThread,
  type SmsMessage,
} from "../../lib/bridge";

export type MessageTarget = { number: string; name: string };

/// Phase 7 — 2-pane SMS. Threads + open-thread messages refetch on the bare
/// `sms-changed` push (matching MessagesView.jsx), sends are optimistic, and a
/// `target` deep-link (from Contacts) opens or composes to that number.
export default function MessagesView({
  linked,
  target,
  onToast,
}: {
  linked: boolean;
  target: MessageTarget | null;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [threads, setThreads] = useState<SmsThread[]>([]);
  const [sel, setSel] = useState<SmsThread | null>(null);
  const [msgs, setMsgs] = useState<SmsMessage[]>([]);
  const [compose, setCompose] = useState<MessageTarget | null>(null);
  const [draft, setDraft] = useState("");
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const selRef = useRef<SmsThread | null>(null);
  selRef.current = sel;
  const targetRef = useRef<MessageTarget | null>(null);
  targetRef.current = target;

  const norm = (n?: string) => (n || "").replace(/\D/g, "").slice(-10);

  const loadThreads = useCallback(async (): Promise<SmsThread[]> => {
    setLoading(true);
    setErr(null);
    try {
      const t = await smsThreads();
      setThreads(t);
      return t;
    } catch (e) {
      setErr(String(e));
      return [];
    } finally {
      setLoading(false);
    }
  }, []);

  const loadMsgs = useCallback(
    async (t: SmsThread) => {
      try {
        const r = await smsMessages(t.threadId);
        setMsgs(r.messages);
      } catch (e) {
        onToast("bad", String(e));
      }
    },
    [onToast]
  );

  useEffect(() => {
    if (!linked) return;
    loadThreads().then((loaded) => {
      const t = targetRef.current;
      if (!t) return;
      const match = loaded.find((th) => norm(th.address) === norm(t.number));
      if (match) {
        setSel(match);
        setCompose(null);
        setMsgs([]);
        loadMsgs(match);
      } else {
        setSel(null);
        setCompose(t);
        setMsgs([]);
      }
    });
  }, [linked, target, loadThreads, loadMsgs]);

  useEffect(() => {
    if (!linked) return;
    return on("sms-changed", () => {
      loadThreads();
      if (selRef.current) loadMsgs(selRef.current);
    });
  }, [linked, loadThreads, loadMsgs]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [msgs]);

  const openThread = (t: SmsThread) => {
    setSel(t);
    setCompose(null);
    setMsgs([]);
    loadMsgs(t);
  };

  const sendAddress = sel?.address ?? compose?.number ?? null;
  const sendName = sel?.name ?? compose?.name ?? sendAddress ?? "";

  const sendNow = async () => {
    const text = draft.trim();
    if (!text || !sendAddress || sending) return;
    setSending(true);
    try {
      await smsSend(sendAddress, text);
      setDraft("");
      setMsgs((m) => [...m, { id: Date.now(), body: text, out: true, date: Date.now() }]);
      if (compose) {
        const addr = sendAddress;
        setCompose(null);
        const ts = await loadThreads();
        const match = ts.find((th) => norm(th.address) === norm(addr));
        if (match) {
          setSel(match);
          loadMsgs(match);
        }
      }
    } catch (e) {
      onToast("bad", String(e));
    } finally {
      setSending(false);
    }
  };

  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s) return threads;
    return threads.filter(
      (t) =>
        (t.name || "").toLowerCase().includes(s) ||
        (t.address || "").toLowerCase().includes(s) ||
        (t.snippet || "").toLowerCase().includes(s)
    );
  }, [threads, q]);

  if (!linked) {
    return (
      <EmptyState
        icon="message"
        title="Phone not linked"
        body="Messages need the Wi-Fi link. Pair your phone from the Dashboard, then come back."
      />
    );
  }

  const activeId = sel?.threadId ?? null;

  return (
    <div className="flex h-full min-h-0">
      {/* conversation list */}
      <div className="flex w-64 shrink-0 flex-col border-r border-line">
        <div className="flex h-14 shrink-0 items-center justify-between px-4">
          <h1 className="font-display text-[17px] font-semibold text-fg">Messages</h1>
          <button onClick={loadThreads} title="Refresh" className="btn-icon">
            <Icon name="reload" size={14} className={loading ? "spinner" : ""} />
          </button>
        </div>

        <div className="px-3 pb-3">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search"
            className="field w-full"
          />
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
          {compose && (
            <div className="rise row row-selected mb-0.5 flex w-full items-center gap-2.5 px-2.5 py-2 text-left">
              <Avatar name={compose.name} size={32} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-medium text-fg">{compose.name}</p>
                <p className="data mt-0.5 truncate text-[10px] text-dim">{compose.number}</p>
              </div>
            </div>
          )}
          {shown.map((t) => {
            const active = activeId === t.threadId;
            return (
              <button
                key={t.threadId}
                onClick={() => openThread(t)}
                className={`row ${active ? "row-selected" : ""} mb-0.5 flex w-full items-center gap-2.5 px-2.5 py-2 text-left`}
              >
                <Avatar name={t.name} size={32} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="truncate text-[13px] font-medium text-fg">{t.name}</span>
                    <span className="data shrink-0 text-[10px] text-faint">{fmtTime(t.date)}</span>
                  </div>
                  <p className="mt-0.5 truncate text-[11.5px] text-dim">{t.snippet}</p>
                </div>
              </button>
            );
          })}
          {shown.length === 0 && !loading && !compose && (
            <p className="p-6 text-center text-[12px] text-dim">
              {q ? "No matches" : "No conversations yet"}
            </p>
          )}
          {err && <p className="p-6 text-center text-[12px] text-bad">{err}</p>}
        </div>
      </div>

      {/* conversation pane */}
      {sel || compose ? (
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex h-14 shrink-0 items-center gap-3 border-b border-line px-4">
            <Avatar name={sendName} size={32} />
            <div className="min-w-0 flex-1">
              <p className="truncate font-display text-[14px] font-semibold text-fg">{sendName}</p>
              <p className="data truncate text-[10px] text-dim">{sendAddress}</p>
            </div>
            {compose && (
              <span className="shrink-0 rounded-full bg-(--color-accent)/10 px-2 py-0.5 text-[10px] font-medium text-(--color-accent)">
                New
              </span>
            )}
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
            {compose && msgs.length === 0 && (
              <p className="pt-10 text-center text-[12px] text-dim">
                New conversation with {sendName}
              </p>
            )}
            {withDividers(msgs).map((row) =>
              row.divider ? (
                <div key={row.key} className="my-3 flex items-center justify-center">
                  <span className="label rounded-full bg-panel2 px-2.5 py-1">{row.label}</span>
                </div>
              ) : (
                <Bubble key={row.m!.id} m={row.m!} />
              )
            )}
            <div ref={endRef} />
          </div>

          <div className="shrink-0 border-t border-line px-4 py-3">
            <div className="flex items-center gap-2">
              <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && sendNow()}
                placeholder={`Message ${sendName}`}
                className="field min-w-0 flex-1"
              />
              <button
                onClick={sendNow}
                disabled={sending || !draft.trim()}
                title="Send (carrier rates apply)"
                className="btn btn-primary shrink-0 px-3"
              >
                <Icon name="send" size={15} className={sending ? "spinner" : ""} />
              </button>
            </div>
          </div>
        </div>
      ) : (
        <EmptyState
          icon="message"
          title="Pick a conversation"
          body="SMS sends through your phone's SIM — carrier rates apply as usual."
        />
      )}
    </div>
  );
}

function Bubble({ m }: { m: SmsMessage }) {
  return (
    <div className={`mb-1.5 flex ${m.out ? "justify-end" : "justify-start"}`}>
      <div className={`flex max-w-[72%] flex-col ${m.out ? "items-end" : "items-start"}`}>
        <div
          className={`px-3.5 py-2 text-[13px] leading-relaxed ${
            m.out
              ? "rounded-2xl rounded-br-md bg-(--color-accent) text-white"
              : "rounded-2xl rounded-bl-md bg-panel2 text-fg"
          }`}
        >
          {m.body}
        </div>
        <div className={`data mt-0.5 flex items-center gap-1 px-1 text-[10px] text-faint ${m.out ? "flex-row-reverse" : ""}`}>
          <span>{fmtTime(m.date)}</span>
          {m.out && <Icon name="check" size={10} className="text-faint" />}
        </div>
      </div>
    </div>
  );
}

type Row = { divider: true; key: string; label: string } | { divider: false; m: SmsMessage };

function withDividers(msgs: SmsMessage[]): Row[] {
  const out: Row[] = [];
  let last: string | null = null;
  for (const m of msgs) {
    const label = dayLabel(m.date);
    if (label !== last) {
      out.push({ divider: true, key: `d-${label}-${m.id}`, label });
      last = label;
    }
    out.push({ divider: false, m });
  }
  return out;
}

function Avatar({ name, size = 36 }: { name: string; size?: number }) {
  const c = avatarColor(name);
  return (
    <div
      style={{ width: size, height: size, background: `${c}22`, color: c }}
      className="flex shrink-0 items-center justify-center rounded-full font-display text-[12px] font-semibold"
    >
      {initials(name)}
    </div>
  );
}
