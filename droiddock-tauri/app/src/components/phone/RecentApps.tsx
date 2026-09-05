import { useState } from "react";
import { useAppIcon, useRecentApps, launchApp, removeRecent } from "../../lib/appIcons";
import { t } from "../../lib/i18n";

/// Shortcuts to apps you've opened on the phone *from here*.
///
/// This is a Mac-side list, not a view of the phone's task stack — it fills up
/// as you launch things from the Apps grid, and clicking one reopens it on the
/// phone. That's why an app can appear here without you having touched the
/// phone at all, which is otherwise a small mystery.
///
/// Each entry can be removed on hover; removing changes nothing on the phone.
/// The row renders nothing at all until you've launched something, so a fresh
/// install doesn't carry an empty strip.
export default function RecentApps({ onError }: { onError: (msg: string) => void }) {
  const recents = useRecentApps().slice(0, 5);
  // Hover is tracked on the *row*, not per icon: a × that only appears once
  // you're already over the one icon you want is a control nobody finds. This
  // way the row reveals that it's editable the moment you approach it.
  const [editing, setEditing] = useState(false);

  if (recents.length === 0) return null;

  return (
    <div
      className="flex items-center justify-center gap-1.5"
      title={t("Apps you've opened on your phone from DroidDock — hover to remove")}
      onMouseEnter={() => setEditing(true)}
      onMouseLeave={() => setEditing(false)}
    >
      {recents.map((pkg) => (
        <RecentIcon key={pkg} pkg={pkg} editing={editing} onError={onError} />
      ))}
    </div>
  );
}

function RecentIcon({
  pkg,
  editing,
  onError,
}: {
  pkg: string;
  editing: boolean;
  onError: (msg: string) => void;
}) {
  const icon = useAppIcon(pkg);
  // Last path segment is the closest thing to a name we have without the icon
  // payload (`com.whatsapp` → "whatsapp") — used by the tooltip and the
  // placeholder initial alike.
  const short = pkg.split(".").pop() ?? pkg;

  return (
    <span className="relative">
      <button
        onClick={() => launchApp(pkg).catch((e) => onError(String(e)))}
        title={`Open ${short} on your phone`}
        aria-label={`Open ${short}`}
        className="block transition-transform hover:scale-110 active:scale-95"
      >
        {icon ? (
          <img src={icon} alt="" className="h-8 w-8 rounded-[9px] shadow-sm" />
        ) : (
          <span className="flex h-8 w-8 items-center justify-center rounded-[9px] border border-white/12 bg-black/30 text-[11px] font-semibold text-white/60">
            {short.slice(0, 1).toUpperCase()}
          </span>
        )}
      </button>

      {/* Only while the row is hovered — an always-on × on every icon would
          turn a quiet glanceable strip into a row of controls. */}
      {editing && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            removeRecent(pkg);
          }}
          title={`Remove ${short} from recents`}
          aria-label={`Remove ${short} from recents`}
          className="absolute -top-1 -end-1 flex h-4 w-4 items-center justify-center rounded-full border border-white/25 bg-black/80 text-white/90 shadow-sm transition-colors hover:bg-black hover:text-white"
        >
          <svg viewBox="0 0 24 24" className="h-2.5 w-2.5" fill="none" stroke="currentColor" strokeWidth={3.2} aria-hidden="true">
            <path strokeLinecap="round" d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      )}
    </span>
  );
}
