import { useEffect, useRef, useState } from "react";
import Icon from "./Icon";
import { initials } from "../lib/ui";
import { adbCallDtmf, adbCallEnd, adbCallMute, adbCallSpeaker, callAction } from "../lib/bridge";
import { t } from "../lib/i18n";

/// Two transports produce a call overlay, and the case of `state` is what tells
/// them apart. **Lowercase** states come from the phone's own `call` push over
/// the Wi-Fi link; **uppercase** ones come from ADB's `call-state` polling. The
/// distinction is load-bearing rather than cosmetic: the two have different
/// controls available (only ADB can send DTMF) and different failure modes, so
/// they get different overlays.
export type ActiveCall = {
  state: "ringing" | "dialing" | "active" | "RINGING" | "ACTIVE";
  number?: string;
  name?: string;
  /// Wi-Fi only — what the phone said it can actually do, per push. Absent on
  /// a phone build that predates call control, which is why the buttons are
  /// hidden on `undefined` rather than shown by default.
  canAnswer?: boolean;
  canEnd?: boolean;
  canAudio?: boolean;
  muted?: boolean;
  speaker?: boolean;
};

const DTMF: string[][] = [
  ["1", "2", "3"],
  ["4", "5", "6"],
  ["7", "8", "9"],
  ["*", "0", "#"],
];
const SUB: Record<string, string> = {
  "1": "", "2": "ABC", "3": "DEF",
  "4": "GHI", "5": "JKL", "6": "MNO",
  "7": "PQRS", "8": "TUV", "9": "WXYZ",
  "*": "", "0": "+", "#": "",
};

/// Phase 9 incoming/dialing overlay — VIEW-ONLY over Wi-Fi (lowercase
/// `"ringing"`/`"dialing"` states) — upgraded in Phase 13 to the rich
/// ADB-driven overlay (uppercase `"RINGING"`/`"ACTIVE"`, ported from
/// CallOverlay.jsx) once `adb::start_call_polling` starts emitting real
/// `call-state` — App.tsx's `onCallState` merge-in is what flips a call from
/// lowercase to uppercase, exactly mirroring the reference: incoming calls
/// never get polling started (matching the shipped Electron app — dead
/// `call:startPolling` code path, confirmed unused in the renderer), so they
/// stay view-only; a Mac-initiated outbound dial upgrades automatically the
/// moment ADB polling reports back.
export default function CallOverlay({
  call,
  onDismiss,
  onToast,
}: {
  call: ActiveCall;
  onDismiss: () => void;
  onToast?: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  if (call.state === "RINGING" || call.state === "ACTIVE") {
    return <RichCallOverlay call={call} onDismiss={onDismiss} onToast={onToast} />;
  }
  return <WifiCallOverlay call={call} onDismiss={onDismiss} onToast={onToast} />;
}

/// The Wi-Fi overlay: ringing, dialing and in-call, driven by `call-action`
/// rather than ADB.
///
/// This used to be a card with a paragraph explaining that in-call controls
/// needed ADB. The controls are real now — `CallControl.kt` answers, hangs up
/// and moves the audio route through the public framework APIs — so the
/// paragraph is only shown in the case where it is still true: a phone that
/// hasn't granted the Calls permission, or is too old for the API.
///
/// **Everything renders from what the phone reported.** A button appears only
/// when the phone said that action is available, and the two toggles show the
/// state the phone read back *after* writing it, never the state we asked for.
/// The dialer owning the call can refuse a route change, and when it does the
/// toggle springs back with the phone's own explanation instead of lying.
function WifiCallOverlay({
  call,
  onDismiss,
  onToast,
}: {
  call: ActiveCall;
  onDismiss: () => void;
  onToast?: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const active = call.state === "active";
  const ringing = call.state === "ringing";

  const [muted, setMuted] = useState(!!call.muted);
  const [speaker, setSpeaker] = useState(!!call.speaker);
  const [busy, setBusy] = useState<string | null>(null);
  const [duration, setDuration] = useState(0);

  // Every push carries the phone's current read-back, so a change made on the
  // handset itself — the user hitting speaker on the phone mid-call — lands
  // here rather than leaving the Mac's toggles quietly wrong.
  useEffect(() => {
    if (call.muted !== undefined) setMuted(call.muted);
    if (call.speaker !== undefined) setSpeaker(call.speaker);
  }, [call.muted, call.speaker]);

  useEffect(() => {
    if (!active) return;
    setDuration(0);
    const id = setInterval(() => setDuration((d) => d + 1), 1000);
    return () => clearInterval(id);
  }, [active]);

  const fmt = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;

  const run = async (
    key: string,
    action: "answer" | "end" | "mute" | "speaker",
    on?: boolean,
  ): Promise<boolean> => {
    setBusy(key);
    try {
      const r = await callAction(action, on);
      // Trust the read-back over the request — see the component doc.
      if (r?.muted !== undefined) setMuted(r.muted);
      if (r?.speaker !== undefined) setSpeaker(r.speaker);
      return true;
    } catch (e) {
      onToast?.("bad", String(e));
      return false;
    } finally {
      setBusy(null);
    }
  };

  // Hanging up does not close the overlay: the phone's `idle` push does, which
  // is the only signal that the call really ended. Closing on the click would
  // hide a failed hang-up behind a dismissed window.
  const handleEnd = () => run("end", "end");
  const handleAnswer = () => run("answer", "answer");
  const handleMute = () => run("mute", "mute", !muted);
  const handleSpeaker = () => run("speaker", "speaker", !speaker);

  const who = call.name || call.number || "Unknown";
  const heading = ringing ? t("Incoming call") : active ? t("On a call") : t("Outgoing call");
  const label = ringing ? t("Incoming call") : active ? fmt(duration) : "Calling…";

  const canAnswer = ringing && call.canAnswer === true;
  const canEnd = call.canEnd === true;
  const canAudio = active && call.canAudio === true;
  // The old explanatory paragraph, now shown only when it is still accurate.
  const noControls = !canAnswer && !canEnd && !canAudio;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-ink/60 backdrop-blur-sm" onClick={onDismiss} />
      <div className="rise card-raised float-lg relative flex w-80 flex-col overflow-hidden">
        <div className="flex h-10 shrink-0 items-center justify-between border-b border-line ps-4 pe-2">
          <span className="label">{heading}</span>
          <button
            onClick={onDismiss}
            title={active ? t("Minimize (call stays active)") : "Dismiss"}
            className="btn-icon"
          >
            <Icon name="x" size={14} />
          </button>
        </div>

        <div className="flex flex-col items-center py-8">
          <div className="relative flex h-20 w-20 items-center justify-center">
            {ringing && (
              <div
                className="ping-soft absolute inset-0 rounded-full border border-(--color-accent)/20"
                style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--color-accent) 8%, transparent) 0%, transparent 70%)" }}
              />
            )}
            {active && <div className="absolute inset-0 rounded-full bg-ok/10" />}
            <div
              className={`flex h-20 w-20 items-center justify-center rounded-full font-display text-2xl font-semibold ${
                active ? "bg-ok/10 text-ok" : "bg-(--color-accent)/10 text-(--color-accent)"
              }`}
            >
              {initials(who)}
            </div>
            {active && <span className="absolute -bottom-1 -end-1 h-4 w-4 rounded-full border-2 border-panel2 bg-ok" />}
          </div>

          <p className="mt-4 max-w-64 truncate px-4 font-display text-[18px] font-semibold text-fg">{who}</p>
          {call.name && call.number && call.name !== call.number && (
            <p className="data mt-0.5 text-[11px] text-dim">{call.number}</p>
          )}
          {active ? (
            <p className="data mt-2 text-[15px] text-ok">{label}</p>
          ) : (
            <p className="mt-2 text-[13px] font-medium text-(--color-accent)">{label}</p>
          )}
        </div>

        {noControls ? (
          <div className="border-t border-line px-6 py-5 text-center">
            <p className="text-[11.5px] leading-relaxed text-dim">
              {call.canAnswer === undefined && call.canEnd === undefined
                ? t("Answer or decline on your phone. Update the DroidDock app on your phone to control calls from the Mac.")
                : t("Answer or decline on your phone. Controlling the call from here needs the Phone permissions (Calls) granted to DroidDock on your phone.")}
            </p>
            <button onClick={onDismiss} className="btn btn-secondary mt-4 w-full">{t("Dismiss")}
            </button>
          </div>
        ) : (
          <div className="border-t border-line px-6 pb-6 pt-4">
            <div className="flex items-center justify-center gap-4">
              {canAudio ? (
                <CtrlBtn
                  icon={muted ? "micOff" : "mic"}
                  label={muted ? "Unmute" : "Mute"}
                  active={muted}
                  activeColor="bad"
                  disabled={busy === "mute"}
                  onClick={handleMute}
                />
              ) : (
                <span className="h-12 w-12" />
              )}

              {canEnd ? (
                <button
                  onClick={handleEnd}
                  disabled={busy === "end"}
                  className="flex h-16 w-16 items-center justify-center rounded-full bg-bad transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
                  title={ringing ? "Decline" : t("End call")}
                >
                  <Icon name="phoneOff" size={24} strokeWidth={2} className="text-white" />
                </button>
              ) : (
                <span className="h-16 w-16" />
              )}

              {canAnswer ? (
                <button
                  onClick={handleAnswer}
                  disabled={busy === "answer"}
                  className="flex h-16 w-16 items-center justify-center rounded-full bg-ok transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
                  title={t("Answer")}
                >
                  <Icon name="phone" size={24} strokeWidth={2} className="text-white" />
                </button>
              ) : canAudio ? (
                <CtrlBtn
                  icon="volume"
                  label={t("Speaker")}
                  active={speaker}
                  activeColor="ok"
                  disabled={busy === "speaker"}
                  onClick={handleSpeaker}
                />
              ) : (
                <span className="h-12 w-12" />
              )}
            </div>

            {active && (
              /* No keypad here on purpose. Playing DTMF into a live call is
                 `Call.playDtmfTone`, which only the device's default dialer can
                 reach — so the pad exists on the ADB overlay and nowhere else,
                 rather than sitting here doing nothing. */
              <p className="mt-4 text-center text-[10.5px] leading-relaxed text-faint">{t("Keypad tones need the ADB connection")}
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/// The rich ADB-driven overlay (mute/speaker/DTMF/duration/end), ported from
/// CallOverlay.jsx almost verbatim.
function RichCallOverlay({
  call,
  onDismiss,
  onToast,
}: {
  call: ActiveCall;
  onDismiss: () => void;
  onToast?: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [muted, setMuted] = useState(false);
  const [speaker, setSpeaker] = useState(false);
  const [dialpad, setDialpad] = useState(false);
  const [dtmfInput, setDtmfInput] = useState("");
  const [duration, setDuration] = useState(0);
  const [ending, setEnding] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (call.state === "ACTIVE") {
      setDuration(0);
      timerRef.current = setInterval(() => setDuration((d) => d + 1), 1000);
    } else if (timerRef.current) {
      clearInterval(timerRef.current);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [call.state]);

  const fmt = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;

  const handleEnd = async () => {
    setEnding(true);
    await adbCallEnd().catch(() => {});
    // call-state polling will emit IDLE, which App.tsx maps to onDismiss.
    setTimeout(onDismiss, 1200);
  };

  const handleSpeaker = async () => {
    try {
      await adbCallSpeaker();
      setSpeaker((s) => !s);
    } catch {
      onToast?.("bad", t("Speaker toggle failed — ensure ADB is connected"));
    }
  };

  const handleMute = async () => {
    try {
      await adbCallMute();
      setMuted((m) => !m);
    } catch {
      onToast?.("bad", t("Mute toggle failed — ensure ADB is connected"));
    }
  };

  const handleDtmf = async (digit: string) => {
    await adbCallDtmf(digit).catch(() => {});
    setDtmfInput((d) => d + digit);
  };

  const isActive = call.state === "ACTIVE";
  const stateLabel = ending ? "Ending…" : call.state === "RINGING" ? "Calling…" : isActive ? fmt(duration) : "Connecting…";
  const who = call.name || call.number || "Unknown";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-ink/60 backdrop-blur-sm" />
      <div className="rise card-raised float-lg relative flex w-80 flex-col overflow-hidden">
        {call.state === "RINGING" && !ending && (
          <div
            className="ping-soft absolute left-1/2 top-17 h-24 w-24 -translate-x-1/2 rounded-full border border-(--color-accent)/20"
            style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--color-accent) 8%, transparent) 0%, transparent 70%)" }}
          />
        )}

        <div className="flex h-10 shrink-0 items-center justify-between border-b border-line ps-4 pe-2">
          <span className="label">{t("On a call")}</span>
          <button onClick={onDismiss} title={t("Minimize (call stays active)")} className="btn-icon">
            <Icon name="x" size={14} />
          </button>
        </div>

        <div className="flex flex-col items-center py-8">
          <div className="relative flex h-20 w-20 items-center justify-center">
            {isActive && <div className="absolute inset-0 rounded-full bg-ok/10" />}
            <div
              className={`flex h-20 w-20 items-center justify-center rounded-full font-display text-2xl font-semibold ${
                isActive ? "bg-ok/10 text-ok" : "bg-(--color-accent)/10 text-(--color-accent)"
              }`}
            >
              {initials(who)}
            </div>
            {isActive && <span className="absolute -bottom-1 -end-1 h-4 w-4 rounded-full border-2 border-panel2 bg-ok" />}
          </div>

          <p className="mt-4 max-w-64 truncate px-4 font-display text-[18px] font-semibold text-fg">{who}</p>
          {call.name && call.name !== call.number && <p className="data mt-0.5 text-[11px] text-dim">{call.number}</p>}

          {isActive && !ending ? (
            <p className="data mt-2 text-[15px] text-ok">{stateLabel}</p>
          ) : (
            <p className={`mt-2 text-[13px] font-medium ${isActive ? "text-ok" : "text-(--color-accent)"}`}>{stateLabel}</p>
          )}

          {dtmfInput && <p className="data mt-1 text-[12px] text-dim">{dtmfInput}</p>}
        </div>

        {dialpad && (
          <div className="border-t border-line px-6 pb-4 pt-3">
            <div className="grid grid-cols-3 gap-2">
              {DTMF.flat().map((digit) => (
                <button
                  key={digit}
                  onClick={() => handleDtmf(digit)}
                  className="flex flex-col items-center justify-center rounded-lg bg-panel2 py-2.5 transition-colors hover:bg-panel3 active:bg-panel3"
                >
                  <span className="font-display text-[16px] font-semibold leading-none text-fg">{digit}</span>
                  {SUB[digit] && <span className="mt-0.5 text-[8px] font-medium text-faint">{SUB[digit]}</span>}
                </button>
              ))}
            </div>
          </div>
        )}

        <div className="border-t border-line px-6 pb-6 pt-4">
          <div className="flex items-center justify-center gap-4">
            <CtrlBtn icon={muted ? "micOff" : "mic"} label={muted ? "Unmute" : "Mute"} active={muted} activeColor="bad" onClick={handleMute} />

            <button
              onClick={handleEnd}
              disabled={ending}
              className="flex h-16 w-16 items-center justify-center rounded-full bg-bad transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
              title={t("End call")}
            >
              <Icon name="phoneOff" size={24} strokeWidth={2} className="text-white" />
            </button>

            <CtrlBtn icon="volume" label={t("Speaker")} active={speaker} activeColor="ok" onClick={handleSpeaker} />
          </div>

          <div className="mt-4 flex justify-center">
            <button
              onClick={() => setDialpad((d) => !d)}
              className={
                dialpad
                  ? "flex h-8 items-center gap-1.5 rounded-lg bg-(--color-accent)/10 px-3.5 text-[13px] font-medium text-(--color-accent)"
                  : "btn btn-ghost"
              }
            >
              <Icon name="hash" size={12} />{t("Keypad")}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function CtrlBtn({
  icon,
  label,
  active,
  activeColor,
  disabled,
  onClick,
}: {
  icon: string;
  label: string;
  active: boolean;
  activeColor: "bad" | "ok";
  /// Only the Wi-Fi overlay sets this: its toggles are a round trip to the
  /// phone, so they have an in-flight state the ADB ones (fire-and-forget)
  /// don't have.
  disabled?: boolean;
  onClick: () => void;
}) {
  const colors = { bad: "bg-bad/15 text-bad", ok: "bg-ok/15 text-ok" };
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      className={`flex h-12 w-12 flex-col items-center justify-center rounded-full transition-colors disabled:opacity-50 ${
        active ? colors[activeColor] : "bg-panel2 text-dim hover:bg-panel3 hover:text-fg"
      }`}
    >
      <Icon name={icon} size={18} strokeWidth={1.75} />
      <span className="mt-0.5 text-[8px] font-medium">{label}</span>
    </button>
  );
}
