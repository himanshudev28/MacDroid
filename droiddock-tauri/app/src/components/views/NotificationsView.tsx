import { useEffect, useMemo, useState } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { fmtTime } from "../../lib/ui";
import { notifReply, notifAction, type Notif, type DroidConfig } from "../../lib/bridge";
import { useAppIcon } from "../../lib/appIcons";

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
  onToggleMute,
  onToast,
}: {
  linked: boolean;
  items: Notif[];
  config: DroidConfig | null;
  onClear: () => void;
  onDismiss: (key: string) => void;
  onToggleNative: (on: boolean) => void;
  /// Mute/unmute macOS banners for one package. The in-app list is unaffected.
  onToggleMute: (pkg: string, muted: boolean) => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const nativeOn = config?.nativeNotifs ?? true;

  // Grouped-by-app view, AirSync-style. Display-only: every row is still the
  // same NotifRow/CallRow with the same reply/dismiss actions — one click on
  // the toolbar toggle returns the plain chronological list, and the choice
  // persists.
  const [stacked, setStacked] = useState(
    () => localStorage.getItem("notifStacks") !== "off"
  );
  const [openApps, setOpenApps] = useState<Record<string, boolean>>({});

  useEffect(() => {
    localStorage.setItem("notifStacks", stacked ? "on" : "off");
  }, [stacked]);

  // Newest-first order is preserved: groups appear in the order their newest
  // notification does, and items inside a group keep their incoming order.
  const groups = useMemo(() => {
    const byApp = new Map<string, Notif[]>();
    for (const n of items) {
      const app = n.app || "App";
      const list = byApp.get(app);
      if (list) list.push(n);
      else byApp.set(app, [n]);
    }
    return [...byApp.entries()].map(([app, list]) => ({
      app,
      list,
      // Every row in a group shares an app label; take the first package we see
      // so the mute button has something concrete to act on. Call cards have no
      // package, so those groups simply can't be muted.
      pkg: list.find((n) => n.pkg)?.pkg ?? null,
    }));
  }, [items]);

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
          {items.length > 0 && (
            <button
              onClick={() => setStacked((s) => !s)}
              title={stacked ? "Show as one list" : "Group by app"}
              aria-pressed={stacked}
              className={`btn-icon ${stacked ? "text-(--color-accent)" : ""}`}
            >
              <Icon name={stacked ? "squareStack" : "file"} size={14} />
            </button>
          )}
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
          {stacked ? (
            <div className="space-y-2.5">
              {groups.map(({ app, list, pkg }) => {
                const open = openApps[app] ?? false;
                const muted = !!pkg && (config?.mutedApps ?? []).includes(pkg);
                const shown = open ? list : list.slice(0, 1);
                const hidden = list.length - shown.length;
                return (
                  <div key={app} className="card rise-fast overflow-hidden">
                    {(list.length > 1 || pkg) && (
                      <div className="group/hdr flex w-full items-center gap-2 border-b border-line px-4 py-2 transition-colors hover:bg-panel2">
                        <button
                          onClick={() => setOpenApps((o) => ({ ...o, [app]: !open }))}
                          disabled={list.length <= 1}
                          className="flex min-w-0 flex-1 items-center gap-2 text-left disabled:cursor-default"
                        >
                          <span className="label flex-1 truncate">{app}</span>
                          {muted && (
                            <span className="shrink-0 text-[10px] font-medium text-faint">muted</span>
                          )}
                          {list.length > 1 && (
                            <span className="shrink-0 rounded-full bg-panel3 px-1.5 py-0.5 text-[10px] font-semibold text-dim">
                              {list.length}
                            </span>
                          )}
                        </button>
                        {pkg && (
                          <button
                            onClick={() => onToggleMute(pkg, !muted)}
                            title={muted ? `Show ${app} banners again` : `Stop ${app} banners on this Mac`}
                            aria-pressed={muted}
                            className={`btn-icon shrink-0 ${muted ? "text-(--color-warn)" : "opacity-0 group-hover/hdr:opacity-100"}`}
                          >
                            <Icon name="bell" size={12} />
                          </button>
                        )}
                        {list.length > 1 && (
                          <Icon
                            name="chevronRight"
                            size={12}
                            className={`shrink-0 text-faint transition-transform ${open ? "rotate-90" : ""}`}
                          />
                        )}
                      </div>
                    )}
                    <div className="divide-y divide-line">
                      {shown.map((n, i) =>
                        n.type === "call" ? (
                          <CallRow key={n.key + i} n={n} onDismiss={onDismiss} />
                        ) : (
                          <NotifRow key={n.key + i} n={n} onDismiss={onDismiss} onToast={onToast} />
                        )
                      )}
                    </div>
                    {hidden > 0 && (
                      <button
                        onClick={() => setOpenApps((o) => ({ ...o, [app]: true }))}
                        className="w-full px-4 py-1.5 text-left text-[11px] font-medium text-faint transition-colors hover:bg-panel2 hover:text-dim"
                      >
                        {hidden} more from {app}
                      </button>
                    )}
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="card rise-fast divide-y divide-line overflow-hidden">
              {items.map((n, i) =>
                n.type === "call" ? (
                  <CallRow key={n.key + i} n={n} onDismiss={onDismiss} />
                ) : (
                  <NotifRow key={n.key + i} n={n} onDismiss={onDismiss} onToast={onToast} />
                )
              )}
            </div>
          )}
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
  const hasProgress =
    (n.progressMax ?? 0) > 0 || n.progressIndeterminate === true;
  const pct = Math.round(
    Math.min(100, Math.max(0, ((n.progress ?? 0) / (n.progressMax || 1)) * 100))
  );
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

  // Tier B: the phone now sends `pkg`, so this can be the real app icon.
  // Falls back to initials for an older phone build, or while the icon loads.
  const icon = useAppIcon(n.pkg);
  const badge = (n.app || "?").slice(0, 2).toUpperCase();

  return (
    <div className="group flex items-start gap-3 px-4 py-3">
      {icon ? (
        <img src={icon} alt="" className="mt-0.5 h-8 w-8 shrink-0 rounded-lg" />
      ) : (
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-panel3 text-[10px] font-semibold text-dim">
          {badge}
        </div>
      )}
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="label truncate">{n.app || "App"}</span>
          <span className="shrink-0 font-mono text-[10px] tabular-nums text-faint">{fmtTime(n.time)}</span>
        </div>
        {n.title && <p className="mt-0.5 truncate text-[13px] font-semibold text-fg">{n.title}</p>}
        {n.text && <p className="mt-0.5 text-[12px] leading-snug text-dim">{n.text}</p>}

        {hasProgress && (
          <div className="mt-2 flex items-center gap-2">
            <div className="h-1 min-w-0 flex-1 overflow-hidden rounded-full bg-panel3">
              <div
                className={`h-full rounded-full bg-(--color-accent) ${
                  n.progressIndeterminate ? "w-1/3 animate-pulse" : "transition-[width] duration-300"
                }`}
                style={n.progressIndeterminate ? undefined : { width: `${pct}%` }}
              />
            </div>
            {!n.progressIndeterminate && (
              <span className="data shrink-0 text-[10px] text-faint">{pct}%</span>
            )}
          </div>
        )}

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
          <div className="mt-1.5 flex flex-wrap items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
            {/* Real action buttons from the phone's own notification —
                "Mark as read", "Snooze", … fired back by index. */}
            {(n.actions ?? []).map((label, i) => (
              <button
                key={`${label}-${i}`}
                onClick={async () => {
                  try {
                    await notifAction(n.key, i);
                    onToast("ok", `${label} — sent to ${n.app || "app"}`);
                  } catch (e) {
                    onToast("bad", String(e));
                  }
                }}
                className="rounded-md bg-panel3 px-1.5 py-0.5 text-[11px] font-medium text-dim transition-colors hover:text-fg"
              >
                {label}
              </button>
            ))}
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
