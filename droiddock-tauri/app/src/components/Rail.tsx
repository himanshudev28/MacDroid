import { useEffect, useRef, useState } from "react";
import { GROUPS, HOME, SETTINGS, type NavItem, type ViewId } from "../lib/nav";
import { t } from "../lib/i18n";

/// The navigation rail — fifteen destinations in two widths.
///
/// **Collapsed (56px)** is icon-only, the way Mail and Xcode's utility rails
/// work. **Expanded (184px)** shows the label beside each icon, plus the group
/// headings that the collapsed rail can only imply with a divider.
///
/// Icons alone were not enough. Half these destinations have no conventional
/// glyph — Devices vs Mirror vs Camera are three screen-with-something shapes,
/// and a tooltip only helps the user who already suspects there is something
/// to hover. Expanded is therefore the default on a window with room for it;
/// `App` collapses it automatically when there isn't.
export default function Rail({
  view,
  setView,
  notifCount,
  updateReady,
  phoneOpen,
  onTogglePhone,
  expanded,
  onToggleExpanded,
}: {
  view: ViewId;
  setView: (v: ViewId) => void;
  notifCount: number;
  /// A new release is waiting in Settings → About. A dot rather than a count:
  /// there is only ever one update, and "1" beside Settings reads as an unread
  /// message.
  updateReady: boolean;
  phoneOpen: boolean;
  onTogglePhone: () => void;
  expanded: boolean;
  onToggleExpanded: () => void;
}) {
  // Fifteen buttons need more height than a short window has. With the
  // scrollbar hidden there was nothing at all to say so — destinations simply
  // weren't there, which reads as "the Contacts tab is missing" rather than
  // "scroll the rail".
  const scrollRef = useRef<HTMLDivElement>(null);
  const [more, setMore] = useState(false);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return undefined;
    const check = () => setMore(el.scrollHeight - el.scrollTop - el.clientHeight > 2);
    check();
    const ro = new ResizeObserver(check);
    ro.observe(el);
    el.addEventListener("scroll", check, { passive: true });
    return () => {
      ro.disconnect();
      el.removeEventListener("scroll", check);
    };
  }, [expanded]);

  return (
    <nav
      className={`glass-chrome relative z-20 flex shrink-0 flex-col border-e border-line transition-[width] duration-200 ease-out ${
        expanded ? "w-46 items-stretch" : "w-14 items-center"
      }`}
      aria-label={t("Sections")}
    >
      <div data-tauri-drag-region className="h-7 w-full shrink-0" />

      {/* App mark — the Link motif in miniature. Gains the wordmark once
          there's a column wide enough to hold it. */}
      <div
        className={`mb-1 flex h-9 shrink-0 items-center gap-2 ${expanded ? "px-3" : "justify-center"}`}
        title={t("DroidDock")}
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5 shrink-0" fill="none" aria-hidden="true">
          <rect x="3.5" y="6" width="6.5" height="12" rx="1.8" stroke="var(--color-link)" strokeWidth="1.7" />
          <rect x="14" y="6" width="6.5" height="9" rx="1.8" stroke="currentColor" strokeWidth="1.7" className="text-fg/70" />
          <path d="M17.2 15v2.6M15.8 17.6h2.9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="text-fg/70" />
          <path d="M10.5 12H13" stroke="var(--color-link)" strokeWidth="1.7" strokeLinecap="round" />
        </svg>
        {expanded && (
          <span className="font-display truncate text-[13px] font-semibold text-fg/90">{t("DroidDock")}</span>
        )}
      </div>

      <div className="relative flex min-h-0 flex-1 flex-col">
        <div
          ref={scrollRef}
          // Extra bottom padding while the fade is showing, so the last
          // destination can scroll clear of it. Without it the fade sits on top
          // of a real row and half-erases its label, which reads as a rendering
          // bug rather than a scroll hint.
          className={`flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto ${more ? "pb-7" : "pb-2"} ${
            expanded ? "px-2" : "items-center"
          }`}
        >
          <RailButton
            item={HOME}
            active={view === HOME.id}
            expanded={expanded}
            onClick={() => setView(HOME.id)}
          />

          {GROUPS.map(({ title, items }) => (
            <div key={title} className={`flex flex-col gap-0.5 ${expanded ? "" : "items-center"}`}>
              {expanded ? (
                <h2 className="label mt-3 mb-0.5 px-2.5 text-faint">{t(title)}</h2>
              ) : (
                <span className="my-1.5 h-px w-5 self-center bg-line" aria-hidden="true" />
              )}
              {items.map((item) => (
                <RailButton
                  key={item.id}
                  item={item}
                  active={view === item.id}
                  expanded={expanded}
                  badge={item.id === "notifications" ? notifCount : undefined}
                  onClick={() => setView(item.id)}
                />
              ))}
            </div>
          ))}
        </div>

        {/* Only while something is actually cut off below. */}
        {more && (
          <span
            className="pointer-events-none absolute inset-x-0 bottom-0 flex h-7 items-end justify-center bg-linear-to-t from-panel to-transparent pb-0.5 text-faint"
            aria-hidden="true"
          >
            <svg viewBox="0 0 24 24" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth={2.4}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 9l6 6 6-6" />
            </svg>
          </span>
        )}
      </div>

      <div
        className={`flex shrink-0 flex-col gap-0.5 border-t border-line py-2 ${
          expanded ? "px-2" : "items-center"
        }`}
      >
        <RailAction
          expanded={expanded}
          label={expanded ? t("Collapse sidebar") : t("Expand sidebar")}
          hint={expanded ? t("Collapse sidebar") : t("Expand sidebar")}
          onClick={onToggleExpanded}
          icon={
            <svg viewBox="0 0 24 24" className="h-4.5 w-4.5" fill="none" stroke="currentColor" strokeWidth={1.6} aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" strokeLinejoin="round" />
              <path d="M9 4v16" strokeLinecap="round" />
              <path
                d={expanded ? "M13.6 10.2L11.8 12l1.8 1.8" : "M11.8 10.2l1.8 1.8-1.8 1.8"}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          }
        />
        <RailAction
          expanded={expanded}
          label={phoneOpen ? t("Hide phone") : t("Show phone")}
          hint={`${phoneOpen ? "Hide" : "Show"} phone panel  ⌘⌥S`}
          pressed={phoneOpen}
          onClick={onTogglePhone}
          icon={
            <svg viewBox="0 0 24 24" className="h-4.5 w-4.5" fill="none" stroke="currentColor" strokeWidth={1.6} aria-hidden="true">
              <rect x="7" y="3" width="10" height="18" rx="2.2" strokeLinejoin="round" />
              <path d="M10.5 5.6h3" strokeLinecap="round" />
              {phoneOpen && <circle cx="12" cy="17.6" r="0.9" fill="currentColor" stroke="none" />}
            </svg>
          }
        />
        <RailButton
          item={SETTINGS}
          active={view === SETTINGS.id}
          expanded={expanded}
          dot={updateReady}
          onClick={() => setView(SETTINGS.id)}
        />
      </div>
    </nav>
  );
}

/// Shared shell for everything in the rail, so a destination and an action
/// can't drift into looking like two different kinds of control.
const shellClass = (expanded: boolean, active: boolean) =>
  [
    "group relative flex h-9 items-center rounded-[10px] transition-colors duration-100",
    expanded ? "w-full gap-2.5 px-2.5" : "w-9 justify-center",
    active
      ? "bg-[color-mix(in_srgb,var(--color-accent)_16%,transparent)] text-(--color-accent)"
      : "text-dim hover:bg-[color-mix(in_srgb,var(--color-fg)_8%,transparent)] hover:text-fg",
  ].join(" ");

function RailButton({
  item,
  active,
  expanded,
  badge,
  dot,
  onClick,
}: {
  item: NavItem;
  active: boolean;
  expanded: boolean;
  badge?: number;
  /// Countless attention marker, for "there is something here" without a
  /// number to justify. Ignored while `badge` is showing — two markers on one
  /// button is noise.
  dot?: boolean;
  onClick: () => void;
}) {
  const label = t(item.label);
  const hint = item.key ? `${label}  ⌘${item.key}` : label;
  const showDot = dot && !(badge != null && badge > 0);
  return (
    <button
      onClick={onClick}
      // The tooltip stays useful when expanded: it's where the ⌘ accelerator
      // lives, and the label alone doesn't carry it.
      title={showDot ? `${hint} — update available` : hint}
      aria-label={showDot ? t("{name} (update available)", { name: label }) : label}
      aria-current={active ? "page" : undefined}
      className={shellClass(expanded, active)}
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        strokeWidth={1.6}
        stroke="currentColor"
        className="h-[18px] w-[18px] shrink-0"
        aria-hidden="true"
      >
        <path strokeLinecap="round" strokeLinejoin="round" d={item.path} />
      </svg>

      {expanded && (
        <span className="min-w-0 flex-1 truncate text-start text-[13px] font-medium">{label}</span>
      )}

      {badge != null && badge > 0 && (
        <span
          className={
            expanded
              ? "shrink-0 rounded-full bg-(--color-accent) px-1.5 text-[10px] font-semibold leading-4 text-(--color-accent-ink)"
              : "absolute end-1 top-1 min-w-3.5 rounded-full bg-(--color-accent) px-1 text-[9px] font-semibold leading-3.5 text-(--color-accent-ink)"
          }
        >
          {badge > 99 ? "99+" : badge}
        </span>
      )}

      {showDot && (
        <span
          // Sits where the badge would, so the two never fight for the corner.
          className={
            expanded
              ? "size-1.5 shrink-0 rounded-full bg-(--color-accent)"
              : "absolute end-1.5 top-1.5 size-1.5 rounded-full bg-(--color-accent) ring-2 ring-panel"
          }
          aria-hidden="true"
        />
      )}

      {/* Active marker on the rail edge — the "you are here" cue a collapsed
          rail has no label to carry. */}
      {active && !expanded && (
        <span className="absolute -start-2 h-4 w-[2.5px] rounded-full bg-(--color-accent)" aria-hidden="true" />
      )}
    </button>
  );
}

function RailAction({
  icon,
  label,
  hint,
  expanded,
  pressed,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  hint: string;
  expanded: boolean;
  pressed?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      title={hint}
      aria-label={label}
      aria-pressed={pressed}
      className={shellClass(expanded, false)}
    >
      <span className="shrink-0">{icon}</span>
      {expanded && (
        <span className="min-w-0 flex-1 truncate text-start text-[13px] font-medium">{label}</span>
      )}
    </button>
  );
}
