import { useState } from "react";
import Icon from "./Icon";
import LinkPulse from "./LinkPulse";
import { t } from "../lib/i18n";

/// First-run walkthrough. Shown once, dismissible at any point, and it never
/// gates anything — every screen it describes is reachable without it. Modelled
/// on AirSync's `OnboardingView` (welcome → install → set up mirroring), minus
/// the paywall step, which has no equivalent here.
const STEPS = [
  {
    id: "welcome",
    title: t("Welcome to DroidDock"),
    body: t("Your Android phone and this Mac, on one Wi-Fi network, acting like one machine. Notifications, clipboard, files, photos, messages, calls, screen mirroring — all local, nothing leaves your network."),
  },
  {
    id: "install",
    title: t("Install DroidDock on your phone"),
    body: t("Sideload the DroidDock APK on your Android, open it, and grant the permissions it asks for. Notification access is what unlocks notifications and media control; the rest are per-feature and can wait."),
  },
  {
    id: "pair",
    title: t("Pair by scanning a code"),
    body: t("The Dashboard shows a QR code. In the phone app, tap “Pair with Mac” and scan it. Both devices must be on the same Wi-Fi network — if the code won't scan, there's an IP-and-token fallback on the same screen."),
  },
  {
    id: "tools",
    title: t("Optional: adb and scrcpy"),
    body: t("Screen mirroring works over Wi-Fi with no extra setup. Installing scrcpy (via Homebrew, one click in Devices) additionally unlocks the lower-latency USB/wireless-ADB path, phone camera, and in-call controls."),
  },
] as const;

export default function Onboarding({
  onClose,
  onGoToDashboard,
}: {
  onClose: () => void;
  onGoToDashboard: () => void;
}) {
  const [i, setI] = useState(0);
  const step = STEPS[i];
  const last = i === STEPS.length - 1;

  const finish = () => {
    onClose();
    onGoToDashboard();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-8 backdrop-blur-sm">
      <div className="rise card-raised w-full max-w-115 p-8" role="dialog" aria-modal="true" aria-label={t("Welcome")}>
        <div className="flex flex-col items-center text-center">
          <LinkPulse linked={i > 1} width={120} />

          <h1 className="mt-5 font-display text-[20px] font-semibold text-fg">{step.title}</h1>
          <p className="mt-2.5 max-w-85 text-[13px] leading-relaxed text-dim">{step.body}</p>
        </div>

        <div className="mt-7 flex items-center justify-center gap-1.5">
          {STEPS.map((s, n) => (
            <button
              key={s.id}
              onClick={() => setI(n)}
              aria-label={`Step ${n + 1}: ${s.title}`}
              aria-current={n === i}
              className={`h-1.5 rounded-full transition-all ${
                n === i ? "w-5 bg-(--color-accent)" : "w-1.5 bg-panel3 hover:bg-faint"
              }`}
            />
          ))}
        </div>

        <div className="mt-6 flex items-center gap-2">
          <button onClick={onClose} className="btn btn-ghost">{t("Skip")}
          </button>
          <div className="flex-1" />
          {i > 0 && (
            <button onClick={() => setI((n) => n - 1)} className="btn btn-secondary">{t("Back")}
            </button>
          )}
          <button onClick={() => (last ? finish() : setI((n) => n + 1))} className="btn btn-primary">
            {last ? (
              <>
                <Icon name="qrcode" size={13} />{t("Show pairing code")}
              </>
            ) : (
              "Next"
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
