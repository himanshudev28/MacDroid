import { useEffect, useMemo, useRef, useState, memo } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { useAppIcon, launchApp } from "../../lib/appIcons";
import { appsList, adbMirrorApp, type PhoneApp } from "../../lib/bridge";

/// The phone's app drawer, on the Mac. Click an app and it opens on the phone —
/// pair it with the Mirror tab and you have the app on screen in two clicks.
///
/// Search ranks prefix matches first, so typing "ma" puts Maps above Gmail.
function AppsView({
  linked,
  adbSerial,
  onToast,
}: {
  linked: boolean;
  /// When an ADB device is present, apps can additionally be opened *into a
  /// Mac window* (scrcpy `--new-display`) instead of just on the phone.
  adbSerial: string | null;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [apps, setApps] = useState<PhoneApp[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [launching, setLaunching] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const load = () => {
    setError(null);
    setApps(null);
    appsList()
      .then(setApps)
      .catch((e) => {
        setError(String(e));
        setApps([]);
      });
  };

  useEffect(() => {
    if (!linked) {
      setApps(null);
      return;
    }
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linked]);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!apps) return [];
    if (!q) return apps;
    const matches = apps.filter((a) => a.label.toLowerCase().includes(q));
    return matches.sort((a, b) => {
      const aStarts = a.label.toLowerCase().startsWith(q);
      const bStarts = b.label.toLowerCase().startsWith(q);
      if (aStarts !== bStarts) return aStarts ? -1 : 1;
      return a.label.localeCompare(b.label);
    });
  }, [apps, query]);

  const open = async (app: PhoneApp) => {
    setLaunching(app.pkg);
    try {
      await launchApp(app.pkg);
      onToast("ok", `Opened ${app.label} on your phone`);
    } catch (e) {
      onToast("bad", String(e));
    } finally {
      setLaunching(null);
    }
  };

  /// Tier D: open the app on a virtual display and mirror that, so it lands in
  /// its own Mac window and the phone screen is untouched.
  const openHere = async (app: PhoneApp) => {
    if (!adbSerial) return;
    setLaunching(app.pkg);
    try {
      await adbMirrorApp(adbSerial, app.pkg, true);
      onToast("ok", `Opening ${app.label} in a Mac window…`);
    } catch (e) {
      onToast("bad", String(e));
    } finally {
      setLaunching(null);
    }
  };

  if (!linked) {
    return (
      <EmptyState
        icon="wifi"
        title="No phone linked"
        body="Link your phone from the Dashboard to browse and open its apps from here."
      />
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 items-center gap-2.5 px-6 pb-4 pt-5">
        <div className="relative min-w-0 flex-1">
          <Icon
            name="search"
            size={13}
            className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-faint"
          />
          <input
            ref={searchRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") setQuery("");
              // Enter opens the top hit — the whole point of ranking prefixes first.
              if (e.key === "Enter" && shown[0]) open(shown[0]);
            }}
            placeholder="Search apps…"
            className="field w-full pl-7.5"
          />
        </div>
        <span className="shrink-0 text-[12px] text-dim">{apps ? `${shown.length}` : ""}</span>
        <button onClick={load} className="btn-icon shrink-0" title="Refresh app list">
          <Icon name="reload" size={14} />
        </button>
      </div>

      {apps === null ? (
        <div className="flex flex-1 items-center justify-center gap-2 text-[12.5px] text-dim">
          <Icon name="reload" size={14} className="spinner" />
          Reading your phone's app list…
        </div>
      ) : error ? (
        <EmptyState icon="alert" title="Couldn't list apps" body={error} />
      ) : shown.length === 0 ? (
        <EmptyState
          icon="search"
          title={query ? "No matches" : "No apps found"}
          body={query ? `Nothing matching “${query}”.` : "The phone reported no launchable apps."}
        />
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
          <div className="grid grid-cols-[repeat(auto-fill,minmax(88px,1fr))] gap-2">
            {shown.map((app) => (
              <AppTile
                key={app.pkg}
                app={app}
                launching={launching === app.pkg}
                onOpen={() => open(app)}
                onOpenHere={adbSerial ? () => openHere(app) : null}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function AppTile({
  app,
  launching,
  onOpen,
  onOpenHere,
}: {
  app: PhoneApp;
  launching: boolean;
  onOpen: () => void;
  /// Null when no ADB device is connected — opening into a Mac window needs
  /// scrcpy, which needs ADB.
  onOpenHere: (() => void) | null;
}) {
  const icon = useAppIcon(app.pkg);

  return (
    <button
      onClick={onOpen}
      onDoubleClick={onOpenHere ?? undefined}
      title={
        onOpenHere
          ? `${app.label}\n${app.pkg}\n\nClick: open on phone · Double-click: open in a Mac window`
          : `${app.label}\n${app.pkg}`
      }
      className="group relative flex flex-col items-center gap-1.5 rounded-xl px-2 py-3 transition-colors hover:bg-panel2"
    >
      <div className="relative h-12 w-12 shrink-0">
        {icon ? (
          <img
            src={icon}
            alt=""
            className="h-12 w-12 rounded-[11px] transition-transform group-hover:scale-105"
          />
        ) : (
          <div className="flex h-12 w-12 items-center justify-center rounded-[11px] bg-panel3 text-[13px] font-semibold text-faint">
            {(app.label || "?").slice(0, 1).toUpperCase()}
          </div>
        )}
        {launching && (
          <div className="absolute inset-0 flex items-center justify-center rounded-[11px] bg-black/55">
            <Icon name="reload" size={15} className="spinner text-white" />
          </div>
        )}
      </div>
      <span className="line-clamp-2 text-center text-[11px] leading-tight text-fg/85">
        {app.label}
      </span>
    </button>
  );
}

/* Memoised: App holds `media`, which the phone pushes once a second while
   something is playing. Without this, every one of those ticks re-rendered this
   whole view (thumbnail grids, file lists) even though none of its props
   changed. All props here are primitives or stable useCallback refs, so the
   comparison is sound. */
export default memo(AppsView);
