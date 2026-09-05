import { useEffect, useState } from "react";
import Icon from "../Icon";
import { mirrorPopout, mirrorFocus, mirrorStop, onMirrorStarted, onMirrorStopped, onMirrorError } from "../../lib/bridge";
import { t } from "../../lib/i18n";

/// Camera tab (Phase 12/13). Reuses Phase 11's mirror pipeline end-to-end —
/// `mirror.rs`'s `mirror_popout("camera")` already sends `camera-start{facing:
/// "back"}` and opens the same pop-out window; `MirrorWindow.tsx` already
/// renders the front/back flip control and mirrors the front-facing preview.
/// This view is just the launcher card, ported from `CameraView.jsx` (which
/// itself renders no video — it's props-driven nav buttons only). ADB Camera
/// (Phase 13) spawns scrcpy in camera mode against the connected ADB device,
/// its own OS window — matches `adb.camera()`/`ipcMain.handle('camera', ...)`.
export default function CameraView({
  linked,
  adbSerial,
  scrcpyReady,
  onAdbCamera,
  onToast,
}: {
  linked: boolean;
  adbSerial: string | null;
  scrcpyReady: boolean;
  onAdbCamera: () => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [active, setActive] = useState<"screen" | "camera" | null>(null);
  const [wifiBusy, setWifiBusy] = useState(false);

  useEffect(() => {
    const offStarted = onMirrorStarted((m) => {
      setActive(m.source || "camera");
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
      await mirrorPopout("camera");
      onToast("info", t("Approve the camera on your phone…"));
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
            <Icon name="camera" size={20} strokeWidth={1.5} className="text-fg/80" />
          </div>
          <p className="flex items-center justify-center gap-2 font-display text-[15px] font-semibold text-fg">
            <span className="led h-1.5 w-1.5 rounded-full bg-(--color-link)" />{t("Camera live")}
          </p>
          <p className="mt-2 text-[12px] leading-relaxed text-dim">{t("Your phone's camera is streaming in its own window. Flip front/back from there.")}
          </p>
          <div className="mt-5 flex items-center justify-center gap-2">
            <button onClick={() => mirrorFocus()} className="btn btn-secondary">{t("Bring to front")}
            </button>
            <button onClick={() => mirrorStop()} className="btn btn-danger">{t("Stop")}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mx-auto max-w-xl">
        <h1 className="font-display text-[17px] font-semibold text-fg">{t("Camera")}</h1>
        <p className="mt-0.5 text-[12px] text-dim">{t("Use your phone's camera as a live feed on this Mac.")}</p>

        <div className="mt-4 space-y-3">
          <LaunchCard
            primary
            title={t("Wi-Fi camera")}
            subtitle={t("Streams the phone's back camera into a pop-out window over Wi-Fi. Flip to the front camera once it's running.")}
            tag="Wi-Fi"
            live={linked}
            requirement={linked ? null : t("Phone app link required")}
            requirementHint="Pair the DroidDock phone app from the Dashboard to stream over Wi-Fi."
            buttonLabel="Start camera"
            buttonBusy={wifiBusy}
            onClick={openWifi}
          />

          <LaunchCard
            title={t("ADB camera")}
            subtitle={t("Full-quality camera preview over USB or wireless ADB via scrcpy — opens in its own window.")}
            tag="ADB"
            live={!!adbSerial}
            requirement={adbSerial ? null : t("No ADB device connected")}
            requirementHint={
              scrcpyReady
                ? t("Connect a phone via USB or wireless ADB from the Devices tab.")
                : t("Install scrcpy first (Devices tab → Tools).")
            }
            buttonLabel="Stream via ADB"
            onClick={onAdbCamera}
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
          <Icon name="camera" size={17} className="text-fg/80" />
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
          {buttonBusy ? t("Waiting for the phone…") : buttonLabel}
        </button>
      )}
    </div>
  );
}
