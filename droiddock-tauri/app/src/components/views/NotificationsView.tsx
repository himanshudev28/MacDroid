import { useState } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { fmtTime } from "../../lib/ui";
import { notifReply, type Notif, type DroidConfig } from "../../lib/bridge";

/// Phase 4 — the in-app notifications panel. The list itself is fed by App
/// (off the `notification` / `call` Tauri events, populated regardless of the
/// native banner gates). The "Show on Mac" button toggles `nativeNotifs`,
/// exactly like the Electron NotificationsView's native toggle.
export default function NotificationsView({
  linked,
  items,
  config,
  onClear,
  onDismiss,
  onToggleNative,
  onToast,
}: {
  linked: boolean;
  items: Notif[];
  config: DroidConfig | null;
  onClear: () => void;
  onDismiss: (key: string) => void;
  onToggleNative: (on: boolean) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const nativeOn = config?.nativeNotifs ?? true;

  if (!linked) {
    return (
      <EmptyState
        icon="wifi"
        title="No phone linked"
        body="Link your phone from the Dashboard to see its notifications here."
      />
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 items-center justify-between gap-3 px-6 pt-5 pb-4">
        <div className="flex items-baseline gap-2.5">
          <h1 className="font-display text-[17px] font-semibold text-fg">Notifications</h1>
          {items.length > 0 && <span className="text-[12px] text-dim">{items.length}</span>}
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => onToggleNative(!nativeOn)}
            title={nativeOn ? "Showing on Mac — click to disable" : "Click to show on Mac"}
            className={`flex h-8 items-center gap-1.5 rounded-lg px-3 text-[12.5px] font-medium transition-colors ${
              nativeOn
                ? "bg-[color-mix(in_srgb,var(--color-accent)_14%,transparent)] text-(--color-accent)"
                : "text-dim hover:bg-panel2 hover:text-fg"
            }`}
          >
            <Icon name="monitor" size={14} />
            Show on Mac
          </button>
          <button onClick={onClear} className="btn btn-ghost">
            Clear
          </button>
        </div>
      </div>

      {items.length === 0 ? (
        <EmptyState
          icon="bell"
          title="No notifications yet"
          body={
            nativeOn
              ? "Phone notifications appear here and as macOS banners with inline reply."
              : 'Phone notifications appear here. Turn on "Show on Mac" for macOS banners too.'
          }
        />
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
          <div className="card rise-fast divide-y divide-line overflow-hidden">
            {items.map((n, i) =>
              n.type === "call" ? (
                <CallRow key={n.key + i} n={n} onDismiss={onDismiss} />
              ) : (
                <NotifRow key={n.key + i} n={n} onDismiss={onDismiss} onToast={onToast} />
              )
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function CallRow({ n, onDismiss }: { n: Notif; onDismiss: (k: string) => void }) {
  return (
    <div className="group flex items-start gap-3 px-4 py-3">
      <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[color-mix(in_srgb,var(--color-ok)_14%,transparent)]">
        <Icon name="phone" size={14} className="text-ok" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="text-[11px] font-medium text-ok">Incoming call</span>
          <span className="shrink-0 font-mono text-[10px] tabular-nums text-faint">{fmtTime(n.time)}</span>
        </div>
        <p className="mt-0.5 truncate text-[13px] font-semibold text-fg">{n.title}</p>
        {n.text && <p className="mt-0.5 text-[12px] leading-snug text-dim">{n.text}</p>}
        <div className="mt-1.5 flex items-center opacity-0 transition-opacity group-hover:opacity-100">
          <button
            onClick={() => onDismiss(n.key)}
            className="-ml-1.5 flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium text-dim transition-colors hover:bg-panel3 hover:text-bad"
          >
            <Icon name="x" size={11} /> Dismiss
          </button>
        </div>
      </div>
    </div>
  );
}

function NotifRow({
  n,
  onDismiss,
  onToast,
}: {
  n: Notif;
  onDismiss: (k: string) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [replying, setReplying] = useState(false);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);

  const send = async () => {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    try {
      await notifReply(n.key, text);
      onToast("ok", `Reply sent to ${n.app || "app"}`);
      setDraft("");
      setReplying(false);
    } catch (e) {
      onToast("bad", String(e) || "Reply failed — phone not connected");
    } finally {
      setSending(false);
    }
  };

  const badge = (n.app || "?").slice(0, 2).toUpperCase();

  return (
    <div className="group flex items-start gap-3 px-4 py-3">
      <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-panel3 text-[10px] font-semibold text-dim">
        {badge}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="label truncate">{n.app || "App"}</span>
          <span className="shrink-0 font-mono text-[10px] tabular-nums text-faint">{fmtTime(n.time)}</span>
        </div>
        {n.title && <p className="mt-0.5 truncate text-[13px] font-semibold text-fg">{n.title}</p>}
        {n.text && <p className="mt-0.5 text-[12px] leading-snug text-dim">{n.text}</p>}

        {replying ? (
          <div className="mt-2.5 flex items-center gap-2">
            <input
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && send()}
              autoFocus
              placeholder="Reply…"
              className="field min-w-0 flex-1"
            />
            <button
              onClick={send}
              disabled={sending || !draft.trim()}
              title="Send reply"
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-(--color-accent) text-white transition-opacity hover:opacity-90 disabled:opacity-40"
            >
              <Icon name="send" size={13} className={sending ? "spinner" : ""} />
            </button>
          </div>
        ) : (
          <div className="mt-1.5 flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
            {n.replyable && (
              <button
                onClick={() => setReplying(true)}
                className="-ml-1.5 flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium text-dim transition-colors hover:bg-panel3 hover:text-fg"
              >
                <Icon name="cornerUpLeft" size={11} /> Reply
              </button>
            )}
            <button
              onClick={() => onDismiss(n.key)}
              className={`flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium text-dim transition-colors hover:bg-panel3 hover:text-bad ${
                n.replyable ? "" : "-ml-1.5"
              }`}
            >
              <Icon name="x" size={11} /> Dismiss
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
