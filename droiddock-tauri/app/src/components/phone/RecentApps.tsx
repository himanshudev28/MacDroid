import { useAppIcon, useRecentApps, launchApp } from "../../lib/appIcons";

/// The five most recently opened apps, on the phone card — one click reopens
/// one on the phone. "Recent" means recently launched *from the Mac*, which is
/// tracked locally and needs no protocol support.
///
/// Renders nothing until you've launched something, so the card doesn't carry
/// an empty row on a fresh install.
export default function RecentApps({ onError }: { onError: (msg: string) => void }) {
  const recents = useRecentApps().slice(0, 5);
  if (recents.length === 0) return null;

  return (
    <div className="flex items-center justify-center gap-1.5">
      {recents.map((pkg) => (
        <RecentIcon key={pkg} pkg={pkg} onError={onError} />
      ))}
    </div>
  );
}

function RecentIcon({ pkg, onError }: { pkg: string; onError: (msg: string) => void }) {
  const icon = useAppIcon(pkg);

  return (
    <button
      onClick={() => launchApp(pkg).catch((e) => onError(String(e)))}
      title={`Open ${pkg} on your phone`}
      aria-label={`Open ${pkg}`}
      className="transition-transform hover:scale-110 active:scale-95"
    >
      {icon ? (
        <img src={icon} alt="" className="h-8 w-8 rounded-[9px] shadow-sm" />
      ) : (
        <span className="flex h-8 w-8 items-center justify-center rounded-[9px] border border-white/12 bg-black/30 text-[11px] font-semibold text-white/60">
          {pkg.split(".").pop()?.slice(0, 1).toUpperCase() ?? "?"}
        </span>
      )}
    </button>
  );
}
