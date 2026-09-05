import { useCallback, useEffect, useState } from "react";
import Icon from "./Icon";
import { healthCheck, healthFix, type HealthItem } from "../lib/bridge";
import { t } from "../lib/i18n";

/// The setup check: every grant both devices need, what breaks without each,
/// and a button that goes and fixes it.
///
/// # Why this is a panel and not more toasts
///
/// Almost everything that goes wrong with DroidDock goes wrong *quietly*. The
/// mirror keeps streaming with the phone's accessibility service off while
/// every tap is dropped; the Notifications tab is empty whether access is
/// missing or the phone is just quiet; remote control no-ops when this Mac's
/// Accessibility grant went stale, which happens on every single update. Each
/// of those had a toast, fired at the moment you tried the broken thing — by
/// which point you had already formed a theory, and the theory was wrong.
///
/// So the ordering here is by *what it costs you*, not by device: things that
/// kill a headline feature first, then things that degrade one, then context.
/// Rows that are fine collapse into a count, because a wall of green is how a
/// panel like this teaches people to stop reading it.
export default function HealthModal({
  onClose,
  onToast,
}: {
  onClose: () => void;
  onToast?: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [items, setItems] = useState<HealthItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fixing, setFixing] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setItems(await healthCheck());
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  }, []);

  // Re-probe while the panel is open, so granting something on the phone shows
  // up here without a manual recheck — the phone's own settings screen is
  // usually what you are looking at when it happens. 4s rather than the 2s the
  // Android app polls itself at: each round is a request across the link.
  useEffect(() => {
    void refresh();
    const id = setInterval(() => void refresh(), 4000);
    return () => clearInterval(id);
  }, [refresh]);

  const runFix = async (item: HealthItem) => {
    if (!item.fix) return;
    setFixing(item.id);
    try {
      onToast?.("info", await healthFix(item.fix));
      // The grant usually lands a moment after the screen opens; pull once
      // rather than waiting out the poll.
      setTimeout(() => void refresh(), 1200);
    } catch (e) {
      onToast?.("bad", String(e));
    } finally {
      setFixing(null);
    }
  };

  const problems = (items ?? []).filter((i) => !i.ok && i.severity !== "info");
  const notes = (items ?? []).filter((i) => !i.ok && i.severity === "info");
  const fine = (items ?? []).filter((i) => i.ok);
  // Errors above warnings inside the problem list; the phone's rows keep the
  // order PermissionHealth sent them in, which is already most-costly-first.
  problems.sort((a, b) => (a.severity === b.severity ? 0 : a.severity === "error" ? -1 : 1));

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-ink/60 backdrop-blur-sm" onClick={onClose} />
      <div className="rise card-raised float-lg relative flex max-h-[80vh] w-full max-w-xl flex-col overflow-hidden">
        <div className="flex h-11 shrink-0 items-center justify-between border-b border-line ps-4 pe-2">
          <span className="label">{t("Setup check")}</span>
          <div className="flex items-center gap-1">
            <button onClick={() => void refresh()} title={t("Check again")} className="btn-icon">
              <Icon name="reload" size={14} />
            </button>
            <button onClick={onClose} title={t("Close")} className="btn-icon">
              <Icon name="x" size={14} />
            </button>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          {error && (
            <p className="rounded-lg bg-bad/10 px-3 py-2 text-[12px] text-bad">{error}</p>
          )}

          {items === null && !error && (
            <p className="py-8 text-center text-[12px] text-dim">{t("Checking both devices…")}</p>
          )}

          {items !== null && problems.length === 0 && (
            <div className="flex items-start gap-3 rounded-lg bg-ok/10 px-3.5 py-3">
              <Icon name="checkCircle" size={16} className="mt-px shrink-0 text-ok" />
              <div>
                <p className="font-display text-[13.5px] font-semibold text-fg">{t("Everything DroidDock needs is granted")}
                </p>
                <p className="mt-0.5 text-[11.5px] leading-relaxed text-dim">
                  {fine.length} check{fine.length === 1 ? "" : "s"} passed across both devices.
                </p>
              </div>
            </div>
          )}

          {problems.length > 0 && (
            <Group
              title={`${problems.length} thing${problems.length === 1 ? "" : "s"} need${
                problems.length === 1 ? "s" : ""
              } attention`}
            >
              {problems.map((i) => (
                <Row key={i.id} item={i} fixing={fixing === i.id} onFix={() => void runFix(i)} />
              ))}
            </Group>
          )}

          {notes.length > 0 && (
            <Group title={t("Worth knowing")}>
              {notes.map((i) => (
                <Row key={i.id} item={i} fixing={fixing === i.id} onFix={() => void runFix(i)} />
              ))}
            </Group>
          )}

          {fine.length > 0 && problems.length > 0 && (
            <Group title={`${fine.length} fine`}>
              <ul className="space-y-1">
                {fine.map((i) => (
                  <li key={i.id} className="flex items-center gap-2 text-[12px] text-dim">
                    <Icon name="check" size={12} className="shrink-0 text-ok" />
                    <span className="truncate">{i.title}</span>
                    <SideTag side={i.side} />
                  </li>
                ))}
              </ul>
            </Group>
          )}
        </div>
      </div>
    </div>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-4 first:mt-0">
      <p className="label mb-2">{title}</p>
      {children}
    </section>
  );
}

/// The device a row is about. Which one you have to walk over to is the first
/// thing you need from a row that says something is broken, so it sits beside
/// the title rather than buried in the detail text.
function SideTag({ side }: { side: string }) {
  return (
    <span className="shrink-0 rounded px-1.5 py-px text-[9.5px] font-medium uppercase tracking-wide text-faint">
      {side === "mac" ? "Mac" : "Phone"}
    </span>
  );
}

function Row({
  item,
  fixing,
  onFix,
}: {
  item: HealthItem;
  fixing: boolean;
  onFix: () => void;
}) {
  const tone =
    item.severity === "error" ? "text-bad" : item.severity === "warn" ? "text-(--color-accent)" : "text-dim";
  const icon = item.severity === "info" ? "info" : "alertTriangle";

  return (
    <div className="mb-2 rounded-lg border border-line/60 bg-surface/40 px-3.5 py-3 last:mb-0">
      <div className="flex items-start gap-3">
        <Icon name={icon} size={15} className={`mt-px shrink-0 ${tone}`} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1">
            <p className="truncate font-display text-[13px] font-semibold text-fg">{item.title}</p>
            <SideTag side={item.side} />
          </div>
          {/* `detail` is written to describe the broken state, so it is shown
              when something is wrong — and on info rows, where the text *is*
              the point and there is no fault to describe. */}
          <p className="mt-1 text-[11.5px] leading-relaxed text-dim">{item.detail}</p>
        </div>
        {item.fix && (
          <button onClick={onFix} disabled={fixing} className="btn btn-secondary shrink-0 disabled:opacity-50">
            {fixing ? "Opening…" : "Fix"}
          </button>
        )}
      </div>
    </div>
  );
}
