import { useCallback, useEffect, useMemo, useState } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { avatarColor } from "../../lib/ui";
import { contactsList, actionCall, type Contact } from "../../lib/bridge";
import type { MessageTarget } from "./MessagesView";
import { t } from "../../lib/i18n";

/// Phase 8 — contacts list with STARRED / ALL sections and client-side search
/// (name OR number), matching ContactsView.jsx. The call button fires the Wi-Fi
/// `action-call` (dial on phone; the incoming/dialing overlay is Phase 9); the
/// SMS button deep-links into Messages for that number.
export default function ContactsView({
  linked,
  onCall,
  onOpenSms,
  onToast,
}: {
  linked: boolean;
  onCall: (c: MessageTarget) => void;
  onOpenSms: (c: MessageTarget) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setContacts(await contactsList());
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (linked) load();
  }, [linked, load]);

  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase();
    if (!t) return contacts;
    return contacts.filter((c) => c.name?.toLowerCase().includes(t) || (c.number || "").includes(t));
  }, [contacts, q]);

  const starred = filtered.filter((c) => c.starred);
  const rest = filtered.filter((c) => !c.starred);

  if (!linked) {
    return (
      <EmptyState
        icon="star"
        title={t("Phone not linked")}
        body={t("Contacts need the Wi-Fi link. Pair your phone from the Dashboard, then come back.")}
      />
    );
  }

  if (err) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="rise card-raised w-full max-w-sm p-7 text-center">
          <p className="font-display text-[15px] font-semibold text-fg">{t("Can't read contacts")}</p>
          <p className="mt-2 text-[12px] leading-relaxed text-dim">{err}</p>
          <button onClick={load} className="btn btn-secondary mt-4">{t("Try again")}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-14 shrink-0 items-center justify-between gap-3 px-5">
        <div className="flex items-baseline gap-2">
          <h1 className="font-display text-[17px] font-semibold text-fg">{t("Contacts")}</h1>
          <span className="text-[11px] text-faint">{contacts.length}</span>
        </div>
        <button onClick={load} title={t("Refresh")} className="btn-icon">
          <Icon name="reload" size={14} className={loading ? "spinner" : ""} />
        </button>
      </div>

      <div className="shrink-0 px-5 pb-3">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={t("Search contacts")}
          className="field w-full"
        />
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">
        {loading && contacts.length === 0 && (
          <p className="p-6 text-[12px] text-dim">{t("Reading contacts…")}</p>
        )}
        {starred.length > 0 && (
          <>
            <Header label={t("Starred")} count={starred.length} />
            {starred.map((c, i) => (
              <Row key={"s" + i} c={c} onCall={onCall} onOpenSms={onOpenSms} onToast={onToast} />
            ))}
          </>
        )}
        {rest.length > 0 && <Header label={t("All contacts")} count={rest.length} />}
        {rest.map((c, i) => (
          <Row key={i} c={c} onCall={onCall} onOpenSms={onOpenSms} onToast={onToast} />
        ))}
        {!loading && filtered.length === 0 && (
          <p className="p-6 text-[12px] text-dim">{t("No matches")}</p>
        )}
      </div>
    </div>
  );
}

function Header({ label, count }: { label: string; count: number }) {
  return (
    <div className="sticky top-0 z-10 flex items-baseline gap-1.5 bg-ink/90 px-2.5 py-1.5 backdrop-blur-sm">
      <span className="label">{label}</span>
      <span className="text-[10px] text-faint">{count}</span>
    </div>
  );
}

function Row({
  c,
  onCall,
  onOpenSms,
  onToast,
}: {
  c: Contact;
  onCall: (c: MessageTarget) => void;
  onOpenSms: (c: MessageTarget) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const handleCall = async () => {
    try {
      await actionCall(c.number);
      onToast("ok", `Calling ${c.name || c.number}…`);
      onCall({ number: c.number, name: c.name || c.number });
    } catch (e) {
      onToast("bad", String(e) || "Call failed — is the phone connected?");
    }
  };

  return (
    <div className="group row flex items-center gap-3 px-2.5 py-2">
      <div
        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full font-display text-[12px] font-semibold text-white"
        style={{ background: avatarColor(c.name) }}
      >
        {(c.name || "?").slice(0, 1).toUpperCase()}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <span className="truncate text-[13px] font-medium text-fg">{c.name || c.number}</span>
          {c.starred && (
            <Icon name="star" size={11} className="shrink-0 text-(--color-accent)" fill="currentColor" strokeWidth={0} />
          )}
        </div>
        {c.number && <p className="data truncate text-[10px] text-dim">{c.number}</p>}
      </div>
      {c.number && (
        <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
          <button onClick={handleCall} title={t("Call via phone")} className="btn-icon">
            <Icon name="phone" size={14} />
          </button>
          <button
            onClick={() => onOpenSms({ number: c.number, name: c.name || c.number })}
            title={t("Open messages")}
            className="btn-icon"
          >
            <Icon name="message" size={14} />
          </button>
        </div>
      )}
    </div>
  );
}
