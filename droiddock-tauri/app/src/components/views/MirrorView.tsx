import { useEffect, useState } from "react";
import Icon from "../Icon";
import { mirrorPopout, mirrorFocus, mirrorStop, onMirrorStarted, onMirrorStopped, onMirrorError } from "../../lib/bridge";

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
  onAdbMirror,
  onToast,
}: {
  linked: boolean;
  adbSerial: string | null;
  scrcpyReady: boolean;
  onAdbMirror: () => void;
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
            primary
            title="Wi-Fi mirror"
            subtitle="Opens a phone-shaped pop-out window over Wi-Fi. No ADB, scrcpy, or Developer Options needed."
            tag="Wi-Fi"
            live={linked}
            requirement={linked ? null : "Phone app link required"}
            requirementHint="Pair the DroidDock phone app from the Dashboard to mirror over Wi-Fi."
            buttonLabel="Start mirroring"
            buttonBusy={wifiBusy}
            onClick={openWifi}
          />

          <LaunchCard
            title="ADB mirror"
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
        </div>
      </div>
    </div>
  );
}

function LaunchCard({
  title,
  subtitle,
  tag,
  live,
  requirement,
  requirementHint,
  buttonLabel,
  buttonBusy,
  primary,
  onClick,
}: {
  title: string;
  subtitle: string;
  tag: string;
  live?: boolean;
  requirement: string | null;
  requirementHint: string;
  buttonLabel: string;
  buttonBusy?: boolean;
  primary?: boolean;
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
