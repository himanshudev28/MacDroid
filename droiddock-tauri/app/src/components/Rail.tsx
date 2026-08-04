import { useEffect, useRef, useState } from "react";
import { GROUPS, HOME, SETTINGS, type NavItem, type ViewId } from "../lib/nav";

/// The icon rail. Replaces the old 13-row labelled sidebar: same thirteen
/// destinations, same grouping, a fifth of the width — which is what buys the
/// phone card its column. Labels move into hover tooltips; the ⌘-accelerator
/// rides along in the tooltip so the shortcuts are discoverable rather than
/// documented somewhere nobody reads.
export default function Rail({
  view,
  setView,
  notifCount,
  phoneOpen,
  onTogglePhone,
}: {
  view: ViewId;
  setView: (v: ViewId) => void;
  notifCount: number;
  phoneOpen: boolean;
  onTogglePhone: () => void;
}) {
  // The fifteen rail buttons need ~700px of height. `minHeight` is 600, so
  // between those two sizes the list scrolls — and with the scrollbar hidden
  // there was nothing at all to say so: destinations simply weren't there, which
  // reads as "the Contacts tab is missing" rather than "scroll the rail".
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
  }, []);

  return (
    <nav className="glass-chrome flex w-14 shrink-0 flex-col items-center border-r border-line">
      <div className="drag h-7 w-full shrink-0" style={{ WebkitAppRegion: "drag" } as React.CSSProperties} />

      {/* App mark — the Link motif in miniature. */}
      <div className="no-drag mb-1 flex h-9 w-9 shrink-0 items-center justify-center" title="DroidDock">
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" aria-hidden="true">
          <rect x="3.5" y="6" width="6.5" height="12" rx="1.8" stroke="var(--color-link)" strokeWidth="1.7" />
          <rect x="14" y="6" width="6.5" height="9" rx="1.8" stroke="currentColor" strokeWidth="1.7" className="text-fg/70" />
          <path d="M17.2 15v2.6M15.8 17.6h2.9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="text-fg/70" />
          <path d="M10.5 12H13" stroke="var(--color-link)" strokeWidth="1.7" strokeLinecap="round" />
        </svg>
      </div>

      <div className="relative flex min-h-0 flex-1 flex-col">
        <div
          ref={scrollRef}
          className="no-drag flex min-h-0 flex-1 flex-col items-center gap-0.5 overflow-y-auto pb-2"
        >
          <RailButton item={HOME} active={view === HOME.id} onClick={() => setView(HOME.id)} />

          {GROUPS.map(({ title, items }) => (
            <div key={title} className="flex flex-col items-center gap-0.5">
              <span className="my-1.5 h-px w-5 bg-line" aria-hidden="true" />
              {items.map((item) => (
                <RailButton
                  key={item.id}
                  item={item}
                  active={view === item.id}
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

      <div className="no-drag flex shrink-0 flex-col items-center gap-0.5 border-t border-line py-2">
        <button
          onClick={onTogglePhone}
          title={`${phoneOpen ? "Hide" : "Show"} phone panel  ⌘⌥S`}
          aria-label={`${phoneOpen ? "Hide" : "Show"} phone panel`}
          aria-pressed={phoneOpen}
          className={`flex h-9 w-9 items-center justify-center rounded-[10px] transition-colors ${
            phoneOpen ? "text-fg/75 hover:bg-[color-mix(in_srgb,var(--color-fg)_8%,transparent)]" : "text-faint hover:text-fg/75"
          }`}
        >
          <svg viewBox="0 0 24 24" className="h-4.5 w-4.5" fill="none" stroke="currentColor" strokeWidth={1.6} aria-hidden="true">
            <rect x="3" y="4" width="18" height="16" rx="2" strokeLinejoin="round" />
            <path d="M9 4v16" strokeLinecap="round" />
            {phoneOpen && <path d="M5.6 8.5h1.8M5.6 12h1.8" strokeLinecap="round" opacity="0.6" />}
          </svg>
        </button>
        <RailButton item={SETTINGS} active={view === SETTINGS.id} onClick={() => setView(SETTINGS.id)} />
      </div>
    </nav>
  );
}

function RailButton({
  item,
  active,
  badge,
  onClick,
}: {
  item: NavItem;
  active: boolean;
  badge?: number;
  onClick: () => void;
}) {
  const hint = item.key ? `${item.label}  ⌘${item.key}` : item.label;
  return (
    <button
      onClick={onClick}
      title={hint}
      aria-label={item.label}
      aria-current={active ? "page" : undefined}
      className={`group relative flex h-9 w-9 items-center justify-center rounded-[10px] transition-colors duration-100 ${
        active
          ? "bg-[color-mix(in_srgb,var(--color-accent)_15%,transparent)] text-(--color-accent)"
          : "text-dim hover:bg-[color-mix(in_srgb,var(--color-fg)_7%,transparent)] hover:text-fg"
      }`}
    >
      <svg viewBox="0 0 24 24" fill="none" strokeWidth={1.6} stroke="currentColor" className="h-[18px] w-[18px]" aria-hidden="true">
        <path strokeLinecap="round" strokeLinejoin="round" d={item.path} />
      </svg>

      {badge != null && badge > 0 && (
        <span className="absolute right-1 top-1 min-w-3.5 rounded-full bg-(--color-accent) px-1 text-[9px] font-semibold leading-3.5 text-white">
          {badge > 99 ? "99+" : badge}
        </span>
      )}

      {/* Active marker on the rail edge — the "you are here" cue a label used
          to carry. */}
      {active && (
        <span className="absolute -left-2 h-4 w-[2.5px] rounded-full bg-(--color-accent)" aria-hidden="true" />
      )}
    </button>
  );
}
