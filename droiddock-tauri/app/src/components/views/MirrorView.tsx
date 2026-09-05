import { useEffect, useState } from "react";
import Icon from "../Icon";
import type { ScrcpyCaps } from "../../lib/bridge";
import { mirrorPopout, mirrorFocus, mirrorStop, onMirrorStarted, onMirrorStopped, onMirrorError } from "../../lib/bridge";

/// The three virtual-display densities, in the order they're offered.
///
/// Android picks its layout from `px ÷ (dpi ÷ 160)`, so forcing a low density
/// is the entire difference between a desktop and a magnified phone. "Phone"
/// is kept because it is what this app did before the setting existed, and
/// because some apps genuinely behave better in their phone layout.
const UI_MODES = [
  ["desktop", "Desktop", "Large-screen layouts and freeform windows."],
  ["tablet", "Tablet", "Large-screen layouts, bigger touch targets."],
  ["phone", "Phone", "The phone's own layout, scaled up to the window."],
] as const;

/// Screen Mirror tab. Wi-Fi mirror is fully wired (Phase 11); ADB mirror
/// (Phase 13) spawns scrcpy directly against the connected ADB device — its
/// own OS window, not embedded, matching `adb.mirror()`/`ipcMain.handle('mirror', ...)`.
///
/// Note (ported as-is, not "fixed"): the reference Electron app's `forwardCb`
/// only ever sends `mirror-started`/`mirror-error` to the pop-out window, never
/// to the main window — so `onMirrorStarted` below never actually fires in the
/// shipped app either, and the busy state only clears via `onMirrorError` or by
/// the user closing the pop-out (which the Rust side forwards as
/// `mirror-stopped` to "main", matching `mirrorWin.on('closed', ...)`). This is
/// a pre-existing quirk of the reference app, preserved for parity rather than
/// silently "improved".
export default function MirrorView({
  linked,
  adbSerial,
  scrcpyReady,
  scrcpyVersion,
  caps,
  uiMode,
  onUiMode,
  onAdbMirror,
  onAdbEmbedded,
  onAdbDesktop,
  defaultMode,
  onToast,
}: {
  linked: boolean;
  adbSerial: string | null;
  scrcpyReady: boolean;
  /// e.g. "4.1" — shown so "needs 3.0+" is checkable rather than a guess.
  scrcpyVersion: string | null;
  caps: ScrcpyCaps | null;
  /// Which Android layout a virtual display asks for. Exposed here, not just
  /// in Settings, because it's the one knob people flip per-app rather than
  /// once — some apps are better in their phone layout.
  uiMode: "desktop" | "tablet" | "phone";
  onUiMode: (m: "desktop" | "tablet" | "phone") => void;
  onAdbMirror: () => void;
  /// The in-app ADB mirror — scrcpy's stream in our own pop-out window.
  onAdbEmbedded: () => void;
  onAdbDesktop: () => void;
  /// Which card is highlighted as the primary route (Settings › Mirroring).
  defaultMode: "wifi" | "adb" | "desktop";
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [active, setActive] = useState<"screen" | "camera" | null>(null);
  const [wifiBusy, setWifiBusy] = useState(false);

  useEffect(() => {
    const offStarted = onMirrorStarted((m) => {
      setActive(m.source || "screen");
      setWifiBusy(false);
    });
    const offStopped = onMirrorStopped(() => setActive(null));
    const offError = onMirrorError(() => {
      setActive(null);
      setWifiBusy(false);
    });
    return () => {
      offStarted();
      offStopped();
      offError();
    };
  }, []);

  const openWifi = async () => {
    setWifiBusy(true);
    try {
      await mirrorPopout("screen");
      onToast("info", "Approve screen capture on your phone…");
    } catch (e) {
      setWifiBusy(false);
      onToast("bad", String(e));
    }
  };

  if (active) {
    return (
      <div className="flex h-full flex-1 items-center justify-center p-8">
        <div className="rise card-raised w-full max-w-sm p-7 text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-panel3">
            <Icon name="monitor" size={20} strokeWidth={1.5} className="text-fg/80" />
          </div>
          <p className="flex items-center justify-center gap-2 font-display text-[15px] font-semibold text-fg">
            <span className="led h-1.5 w-1.5 rounded-full bg-(--color-link)" />
            Mirroring
          </p>
          <p className="mt-2 text-[12px] leading-relaxed text-dim">
            Your phone is streaming in its own window. Move it, resize it, or pin it on top.
          </p>
          <div className="mt-5 flex items-center justify-center gap-2">
            <button onClick={() => mirrorFocus()} className="btn btn-secondary">
              Bring to front
            </button>
            <button onClick={() => mirrorStop()} className="btn btn-danger">
              Stop
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-xl">
        <h1 className="font-display text-[17px] font-semibold text-fg">Mirror</h1>
        <p className="mt-0.5 text-[12px] text-dim">Put your phone's screen in a window on this Mac.</p>

        <div className="mt-4 space-y-3">
          <LaunchCard
            title="Wi-Fi mirror"
            subtitle="Opens a phone-shaped pop-out window over Wi-Fi. No ADB, scrcpy, or Developer Options needed."
            tag="Wi-Fi"
            primary={defaultMode === "wifi"}
            live={linked}
            requirement={linked ? null : "Phone app link required"}
            requirementHint="Pair the DroidDock phone app from the Dashboard to mirror over Wi-Fi."
            buttonLabel="Start mirroring"
            buttonBusy={wifiBusy}
            onClick={openWifi}
          />

          <LaunchCard
            title="ADB mirror"
            primary={defaultMode === "adb"}
            subtitle="Full-quality, low-latency mirroring over USB or wireless ADB via scrcpy — opens in its own window."
            tag="ADB"
            live={!!adbSerial}
            requirement={adbSerial ? null : "No ADB device connected"}
            requirementHint={
              scrcpyReady
                ? "Connect a phone via USB or wireless ADB from the Devices tab."
                : "Install scrcpy first (Devices tab → Tools)."
            }
            buttonLabel="Mirror via ADB"
            onClick={onAdbMirror}
          />

          <LaunchCard
            title="ADB mirror, in this app"
            subtitle="Same scrcpy stream, but it plays in DroidDock's own pop-out instead of a separate scrcpy window — and needs no “Allow screen capture” tap on the phone."
            tag="ADB"
            live={!!adbSerial}
            requirement={
              !scrcpyReady
                ? "scrcpy not installed"
                : adbSerial
                  ? null
                  : "No ADB device connected"
            }
            requirementHint={
              !scrcpyReady
                ? "Install scrcpy first (Devices tab → Tools)."
                : "Connect a phone via USB or wireless ADB from the Devices tab."
            }
            buttonLabel="Mirror in this app"
            onClick={onAdbEmbedded}
          />

          <LaunchCard
            title="Desktop mode"
            primary={defaultMode === "desktop"}
            subtitle="Mirrors a second, virtual Android display instead of the phone's own screen — the phone stays usable. Needs Android 11+ and scrcpy 3.0 or newer."
            tag="ADB"
            live={!!adbSerial}
            requirement={
              // Version first: with an old scrcpy this fails at spawn no matter
              // what is plugged in, and "no device" would be a misleading reason.
              !scrcpyReady
                ? "scrcpy not installed"
                : caps && !caps.virtualDisplay
                  ? `scrcpy ${scrcpyVersion ?? "(unknown version)"} is too old`
                  : adbSerial
                    ? null
                    : "No ADB device connected"
            }
            requirementHint={
              !scrcpyReady
                ? "Install scrcpy first (Devices tab → Tools)."
                : caps && !caps.virtualDisplay
                  ? "Virtual displays need scrcpy 3.0 or newer. Run `brew upgrade scrcpy`, then reopen DroidDock."
                  : "Connect a phone via USB or wireless ADB from the Devices tab."
            }
            buttonLabel="Start desktop"
            onClick={onAdbDesktop}
          />

          <ModeCard uiMode={uiMode} onUiMode={onUiMode} caps={caps} scrcpyVersion={scrcpyVersion} />
        </div>
      </div>
    </div>
  );
}

/// The layout picker for both virtual-display routes — desktop mode and
/// "open this app on the Mac" from the Apps grid.
///
/// It lives on the Mirror tab rather than only in Settings because it is the
/// one mirroring setting people change often: an app that looks wrong in the
/// desktop layout is fixed by flipping this and relaunching, and burying that
/// two screens away makes the fix undiscoverable.
function ModeCard({
  uiMode,
  onUiMode,
  caps,
  scrcpyVersion,
}: {
  uiMode: "desktop" | "tablet" | "phone";
  onUiMode: (m: "desktop" | "tablet" | "phone") => void;
  caps: ScrcpyCaps | null;
  scrcpyVersion: string | null;
}) {
  const unsupported = !!caps && !caps.virtualDisplay;
  return (
    <div className="card p-5">
      <div className="flex items-start gap-3.5">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-panel3">
          <Icon name="squareStack" size={17} className="text-fg/80" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-semibold text-fg">Window layout</p>
          <p className="mt-1 text-[12px] leading-relaxed text-dim">
            Which layout Android serves on the virtual display — used by Desktop mode and by
            opening a single app on this Mac.
          </p>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2">
        {UI_MODES.map(([value, label, hint]) => (
          <button
            key={value}
            onClick={() => onUiMode(value)}
            disabled={unsupported}
            aria-pressed={uiMode === value}
            title={hint}
            className={`rounded-xl px-3 py-2.5 text-left transition-colors disabled:opacity-40 ${
              uiMode === value ? "bg-panel3 ring-1 ring-(--color-link)" : "bg-panel3/50 hover:bg-panel3"
            }`}
          >
            <span className="block text-[12px] font-semibold text-fg">{label}</span>
            <span className="mt-0.5 block text-[10.5px] leading-snug text-dim">{hint}</span>
          </button>
        ))}
      </div>

      <p className="mt-3 text-[11px] leading-relaxed text-dim">
        {unsupported
          ? `scrcpy ${scrcpyVersion ?? "(unknown version)"} can't create virtual displays — this applies from 3.0 onward.`
          : "Takes effect the next time you start desktop mode or open an app on this Mac."}
      </p>
    </div>
  );
}

function LaunchCard({
  title,
  subtitle,
  tag,
  primary,
  live,
  requirement,
  requirementHint,
  buttonLabel,
  buttonBusy,
  onClick,
}: {
  title: string;
  subtitle: string;
  tag: string;
  /// The route chosen in Settings › Mirroring — badged so the intended one is
  /// obvious among three near-identical cards.
  primary?: boolean;
  live?: boolean;
  requirement: string | null;
  requirementHint: string;
  buttonLabel: string;
  buttonBusy?: boolean;
  onClick: () => void;
}) {
  return (
    <div className={`${primary ? "card-raised" : "card"} p-5`}>
      <div className="flex items-start gap-3.5">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-panel3">
          <Icon name="monitor" size={17} className="text-fg/80" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-[13px] font-semibold text-fg">{title}</p>
            <span className="flex shrink-0 items-center gap-1.5 rounded-md bg-panel3 px-1.5 py-0.5 text-[10px] font-medium text-dim">
              {live && <span className="h-1.5 w-1.5 rounded-full bg-(--color-link)" />}
              {tag}
            </span>
          </div>
          <p className="mt-1 text-[12px] leading-relaxed text-dim">{subtitle}</p>
        </div>
      </div>

      {requirement ? (
        <div className="mt-4 rounded-xl bg-panel3 px-3.5 py-2.5">
          <p className="text-[12px] font-medium text-fg/80">{requirement}</p>
          <p className="mt-0.5 text-[11px] text-dim">{requirementHint}</p>
        </div>
      ) : (
        <button
          onClick={onClick}
          disabled={buttonBusy}
          className={`btn ${primary ? "btn-primary" : "btn-secondary"} mt-4 w-full`}
        >
          {buttonBusy && <Icon name="reload" size={14} className="spinner" />}
          {buttonBusy ? "Waiting for the phone…" : buttonLabel}
        </button>
      )}
    </div>
  );
}
