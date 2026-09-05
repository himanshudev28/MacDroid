import { useEffect, useMemo, useRef, useState, memo } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { useAppIcon, launchApp } from "../../lib/appIcons";
import { appsList, adbMirrorApp, type PhoneApp } from "../../lib/bridge";

/// The phone's app drawer, on the Mac. Clicking an app either launches it on
/// the phone or opens it in its own Mac window — `openOnMac` decides which, and
/// holding Option always does the other one.
///
/// Search ranks prefix matches first, so typing "ma" puts Maps above Gmail.
function AppsView({
  linked,
  adbSerial,
  pinned,
  onPinnedChange,
  openOnMac,
  onOpenOnMacChange,
  onToast,
}: {
  linked: boolean;
  /// When an ADB device is present, apps can additionally be opened *into a
  /// Mac window* (scrcpy `--new-display`) instead of just on the phone.
  adbSerial: string | null;
  /// Packages pinned to the top, in user order. Persisted in config.
  pinned: string[];
  onPinnedChange: (next: string[]) => void;
  /// Persisted `openAppsOnMac`: whether a plain click opens the app here
  /// instead of on the phone. Also settable from Settings › Mirroring — this
  /// view just surfaces the same switch where the decision is actually made.
  openOnMac: boolean;
  onOpenOnMacChange: (next: boolean) => void;
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

  /// Pinned apps, in the order the user pinned them, resolved against the live
  /// list so a pin for an app that has since been uninstalled just disappears
  /// rather than rendering a broken tile.
  const pinnedApps = useMemo(() => {
    if (!apps) return [];
    const byPkg = new Map(apps.map((a) => [a.pkg, a]));
    return pinned.map((p) => byPkg.get(p)).filter((a): a is PhoneApp => !!a);
  }, [apps, pinned]);

  const pinnedSet = useMemo(() => new Set(pinned), [pinned]);

  const togglePin = (pkg: string) => {
    onPinnedChange(pinnedSet.has(pkg) ? pinned.filter((p) => p !== pkg) : [...pinned, pkg]);
  };

  /// Opening the app on a virtual display and mirroring that (Tier D) needs
  /// ADB — without a device the Mac route simply isn't reachable, whatever the
  /// setting says.
  const macAvailable = adbSerial !== null;

  /// One entry point for both destinations. `invert` is the Option key: it
  /// flips whichever destination the setting has chosen, so the other one is
  /// always one modifier away and never buried in Settings.
  ///
  /// A click that resolves to the Mac with no ADB device falls back to the
  /// phone rather than doing nothing — an app grid whose tiles are inert
  /// because a *setting* is on is worse than one that opens the app somewhere.
  const activate = async (app: PhoneApp, invert = false) => {
    const wantsMac = invert ? !openOnMac : openOnMac;
    const toMac = wantsMac && macAvailable;

    setLaunching(app.pkg);
    try {
      if (toMac) {
        await adbMirrorApp(adbSerial!, app.pkg, true);
        onToast("ok", `Opening ${app.label} in a Mac window…`);
      } else {
        await launchApp(app.pkg);
        onToast(
          wantsMac ? "info" : "ok",
          wantsMac
            ? `No ADB device — opened ${app.label} on your phone instead`
            : `Opened ${app.label} on your phone`
        );
      }
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
              if (e.key === "Enter" && shown[0]) activate(shown[0], e.altKey);
            }}
            placeholder="Search apps…"
            className="field w-full pl-7.5"
          />
        </div>
        <span className="shrink-0 text-[12px] text-dim">{apps ? `${shown.length}` : ""}</span>
        {/* The destination switch sits with the grid, not only in Settings:
            "phone or here?" is a decision people change between one app and the
            next, and a preference pane is the wrong distance from that. */}
        <OpenTargetSwitch
          value={openOnMac}
          onChange={onOpenOnMacChange}
          macAvailable={macAvailable}
        />
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
          {/* Pinned apps only lead while browsing — during a search the ranked
              results are the answer, and a pinned row above them would push the
              thing you just typed for off the top. */}
          {!query && pinnedApps.length > 0 && (
            <>
              <p className="pb-2 text-[11px] font-medium text-faint">Pinned</p>
              <div className="grid grid-cols-[repeat(auto-fill,minmax(88px,1fr))] gap-2">
                {pinnedApps.map((app) => (
                  <AppTile
                    key={app.pkg}
                    app={app}
                    launching={launching === app.pkg}
                    pinned
                    onTogglePin={() => togglePin(app.pkg)}
                    onOpen={(alt) => activate(app, alt)}
                    openOnMac={openOnMac && macAvailable}
                    macAvailable={macAvailable}
                  />
                ))}
              </div>
              <div className="my-4 h-px bg-line" />
            </>
          )}
          <div className="grid grid-cols-[repeat(auto-fill,minmax(88px,1fr))] gap-2">
            {shown.map((app) => (
              <AppTile
                key={app.pkg}
                app={app}
                launching={launching === app.pkg}
                pinned={pinnedSet.has(app.pkg)}
                onTogglePin={() => togglePin(app.pkg)}
                onOpen={(alt) => activate(app, alt)}
                openOnMac={openOnMac && macAvailable}
                macAvailable={macAvailable}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/// The Phone/Mac destination switch in the Apps header.
///
/// Shown even with no ADB device, disabled rather than hidden: a control that
/// vanishes reads as a bug, and the tooltip is where "you need ADB for this"
/// can actually be said.
function OpenTargetSwitch({
  value,
  onChange,
  macAvailable,
}: {
  value: boolean;
  onChange: (next: boolean) => void;
  macAvailable: boolean;
}) {
  const options: [boolean, string, string][] = [
    [false, "phone", "Phone"],
    [true, "monitor", "Mac"],
  ];

  return (
    <div
      role="radiogroup"
      aria-label="Where clicking an app opens it"
      title={
        macAvailable
          ? "Where a click opens an app. Hold Option to do the other one."
          : "Opening apps on this Mac needs an ADB device — connect one from the Devices tab."
      }
      className="flex shrink-0 gap-0.5 rounded-lg bg-panel3 p-0.5"
    >
      {options.map(([v, icon, label]) => (
        <button
          key={label}
          role="radio"
          aria-checked={value === v}
          disabled={v && !macAvailable}
          onClick={() => onChange(v)}
          className={`flex items-center gap-1 rounded-[7px] px-2 py-1 text-[11.5px] transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
            value === v
              ? "bg-(--color-accent) font-medium text-(--color-accent-ink)"
              : "text-dim hover:text-fg"
          }`}
        >
          <Icon name={icon} size={11} />
          {label}
        </button>
      ))}
    </div>
  );
}

function AppTile({
  app,
  launching,
  pinned,
  onTogglePin,
  onOpen,
  openOnMac,
  macAvailable,
}: {
  app: PhoneApp;
  launching: boolean;
  pinned: boolean;
  onTogglePin: () => void;
  /// Takes the Option key: true means "the destination I'm *not* set to".
  onOpen: (alt: boolean) => void;
  /// Where a plain click on this tile lands, already resolved against whether
  /// ADB is actually there — purely for the tooltip.
  openOnMac: boolean;
  macAvailable: boolean;
}) {
  const icon = useAppIcon(app.pkg);

  /* No `onDoubleClick` alternate any more. A double-click fires `onClick`
     first, so the old "click = phone, double-click = Mac window" pair really
     did both — it woke the phone's screen on the way to the Mac window. Option
     is a modifier the browser tells us about on the single click, so it can
     pick one destination instead of running two. */
  const hint = macAvailable
    ? openOnMac
      ? "Click: open in a Mac window · ⌥Click: open on phone"
      : "Click: open on phone · ⌥Click: open in a Mac window"
    : "Click: open on phone";

  return (
    <div
      className="group relative"
    ><button
      onClick={(e) => onOpen(e.altKey)}
      title={`${app.label}\n${app.pkg}\n\n${hint}`}
      className="flex w-full flex-col items-center gap-1.5 rounded-xl px-2 py-3 transition-colors hover:bg-panel2"
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
      {/* Outside the launch button, or clicking the pin would also launch the
          app. Hidden until hover unless already pinned, so the grid stays
          quiet. */}
      <button
        onClick={onTogglePin}
        title={pinned ? `Unpin ${app.label}` : `Pin ${app.label}`}
        aria-label={pinned ? `Unpin ${app.label}` : `Pin ${app.label}`}
        aria-pressed={pinned}
        className={`absolute right-1 top-1 rounded-lg p-1 transition-opacity hover:bg-panel3 hover:text-fg focus-visible:opacity-100 ${
          pinned
            ? "text-(--color-accent) opacity-100"
            : "text-faint opacity-0 group-hover:opacity-100"
        }`}
      >
        <Icon name="pin" size={12} />
      </button>
    </div>
  );
}

/* Memoised. This was originally defence against the phone's 1 Hz now-playing
   push re-rendering every view; that push no longer reaches `App` at all (it
   lives in `lib/mediaStore`, read only by the two components that show it). The
   memo stays because `App` still re-renders for its own reasons — an arriving
   notification, a toast appearing and expiring, a transfer's progress — and
   none of those change this view's props. All props here are primitives or
   stable useCallback refs, so the comparison is sound. */
export default memo(AppsView);
