import type { WifiStatus } from "../lib/wifi";
import LinkPulse from "./LinkPulse";

export type ViewId =
  | "dashboard"
  | "files"
  | "photos"
  | "messages"
  | "contacts"
  | "calls"
  | "notifications"
  | "clipboard"
  | "media"
  | "mirror"
  | "camera"
  | "devices"
  | "settings";

type NavItem = { id: ViewId; label: string; path: string };

/* Grouped like a native Mac sidebar (Finder/Mail): the groups encode what
   the tabs actually are — ways to reach the phone, its conversations, and
   its library — not just a flat list of thirteen. */
const HOME: NavItem = {
  id: "dashboard",
  label: "Dashboard",
  path: "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6",
};

const GROUPS: { title: string; items: NavItem[] }[] = [
  {
    title: "Connect",
    items: [
      {
        id: "devices",
        label: "Devices",
        path: "M9 2v2M15 2v2M9 20v2M15 20v2M2 9h2M2 15h2M20 9h2M20 15h2M7 5h10a2 2 0 012 2v10a2 2 0 01-2 2H7a2 2 0 01-2-2V7a2 2 0 012-2z M9 9h6v6H9z",
      },
      {
        id: "mirror",
        label: "Mirror",
        path: "M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z",
      },
      {
        id: "camera",
        label: "Camera",
        path: "M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z M12 17a4 4 0 100-8 4 4 0 000 8z",
      },
    ],
  },
  {
    title: "Conversations",
    items: [
      {
        id: "messages",
        label: "Messages",
        path: "M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z",
      },
      {
        id: "calls",
        label: "Calls",
        path: "M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z",
      },
      {
        id: "contacts",
        label: "Contacts",
        path: "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z",
      },
      {
        id: "notifications",
        label: "Notifications",
        path: "M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 10-12 0v3.2a2 2 0 01-.6 1.4L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9",
      },
    ],
  },
  {
    title: "Library",
    items: [
      {
        id: "files",
        label: "Files",
        path: "M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z",
      },
      {
        id: "photos",
        label: "Photos",
        path: "M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z",
      },
      {
        id: "media",
        label: "Media",
        path: "M9 19V6l12-2v13M9 19a3 3 0 11-6 0 3 3 0 016 0zM21 17a3 3 0 11-6 0 3 3 0 016 0z",
      },
      {
        id: "clipboard",
        label: "Clipboard",
        path: "M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 2h6a1 1 0 011 1v2a1 1 0 01-1 1H9a1 1 0 01-1-1V3a1 1 0 011-1z",
      },
    ],
  },
];

const SETTINGS: NavItem = {
  id: "settings",
  label: "Settings",
  path: "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z",
};

function NavButton({
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
  return (
    <button
      onClick={onClick}
      className={`group flex w-full items-center gap-2.5 rounded-[9px] px-2.5 py-1.5 text-left transition-colors duration-100 ${
        active
          ? "bg-[color-mix(in_srgb,var(--color-accent)_14%,transparent)] text-fg"
          : "text-dim hover:bg-[color-mix(in_srgb,var(--color-fg)_6%,transparent)] hover:text-fg"
      }`}
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        strokeWidth={1.6}
        stroke="currentColor"
        className={`h-4 w-4 shrink-0 ${active ? "text-(--color-accent)" : "text-dim group-hover:text-fg/70"}`}
        aria-hidden="true"
      >
        <path strokeLinecap="round" strokeLinejoin="round" d={item.path} />
      </svg>
      <span className={`flex-1 text-[13px] leading-none ${active ? "font-medium" : "font-normal"}`}>
        {item.label}
      </span>
      {badge != null && badge > 0 && (
        <span className="shrink-0 rounded-full bg-(--color-accent) px-1.5 py-0.5 text-[10px] font-semibold leading-none text-white">
          {badge > 99 ? "99+" : badge}
        </span>
      )}
    </button>
  );
}

export default function Sidebar({
  view,
  setView,
  status,
  notifCount,
}: {
  view: ViewId;
  setView: (v: ViewId) => void;
  status: WifiStatus;
  notifCount: number;
}) {
  return (
    <aside className="glass-chrome flex w-56 shrink-0 flex-col border-r border-line">
      <div className="drag shrink-0 pt-7" style={{ WebkitAppRegion: "drag" } as React.CSSProperties} />

      {/* Wordmark: the Link motif in miniature as the app mark. */}
      <div className="no-drag flex shrink-0 items-center gap-2.5 px-4 pb-4 pt-2">
        <div className="luminous-sm flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-line bg-panel3">
          <svg viewBox="0 0 24 24" className="h-4.25 w-4.25" fill="none" aria-hidden="true">
            <rect x="3.5" y="6" width="6.5" height="12" rx="1.8" stroke="var(--color-link)" strokeWidth="1.7" />
            <rect x="14" y="6" width="6.5" height="9" rx="1.8" stroke="currentColor" strokeWidth="1.7" className="text-fg/80" />
            <path d="M17.2 15v2.6M15.8 17.6h2.9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="text-fg/80" />
            <path d="M10.5 12H13" stroke="var(--color-link)" strokeWidth="1.7" strokeLinecap="round" />
          </svg>
        </div>
        <span className="font-display text-[14px] font-semibold text-fg">DroidDock</span>
      </div>

      <nav className="no-drag min-h-0 flex-1 overflow-y-auto px-2.5 pb-2" style={{ scrollbarWidth: "none" }}>
        <NavButton item={HOME} active={view === HOME.id} onClick={() => setView(HOME.id)} />

        {GROUPS.map(({ title, items }) => (
          <div key={title} className="mt-4">
            <p className="px-2.5 pb-1 text-[10.5px] font-semibold text-faint">{title}</p>
            {items.map((item) => (
              <NavButton
                key={item.id}
                item={item}
                active={view === item.id}
                badge={item.id === "notifications" ? notifCount : undefined}
                onClick={() => setView(item.id)}
              />
            ))}
          </div>
        ))}

        <div className="mt-4">
          <NavButton item={SETTINGS} active={view === SETTINGS.id} onClick={() => setView(SETTINGS.id)} />
        </div>
      </nav>

      {/* The Link, small: live connection state at a glance. */}
      <div className="no-drag shrink-0 border-t border-line px-4 py-3">
        <LinkPulse linked={status.connected} width={88} />
        <p className="mt-1.5 truncate text-[12px] font-medium text-fg/85">
          {status.connected ? status.phoneName ?? "Phone" : "No phone linked"}
        </p>
        <p className={`text-[11px] ${status.connected ? "text-(--color-link)" : "text-faint"}`}>
          {status.connected ? "Linked over Wi-Fi" : "Open the Dashboard to pair"}
        </p>
      </div>
    </aside>
  );
}
