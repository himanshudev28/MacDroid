import { useEffect, useRef, useState } from "react";
import Icon from "./Icon";
import { initials } from "../lib/ui";
import { adbCallDtmf, adbCallEnd, adbCallMute, adbCallSpeaker } from "../lib/bridge";

export type ActiveCall = { state: "ringing" | "dialing" | "RINGING" | "ACTIVE"; number?: string; name?: string };

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

  const label = call.state === "dialing" ? "Calling…" : "Incoming call";
  const who = call.name || call.number || "Unknown";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-ink/60 backdrop-blur-sm" onClick={onDismiss} />
      <div className="rise card-raised float-lg relative flex w-80 flex-col overflow-hidden">
        <div className="flex h-10 shrink-0 items-center justify-between border-b border-line pl-4 pr-2">
          <span className="label">{call.state === "dialing" ? "Outgoing call" : "Incoming call"}</span>
          <button onClick={onDismiss} title="Dismiss" className="btn-icon">
            <Icon name="x" size={14} />
          </button>
        </div>

        <div className="flex flex-col items-center py-8">
          <div className="relative flex h-20 w-20 items-center justify-center">
            {call.state === "ringing" && (
              <div
                className="ping-soft absolute inset-0 rounded-full border border-(--color-accent)/20"
                style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--color-accent) 8%, transparent) 0%, transparent 70%)" }}
              />
            )}
            <div className="flex h-20 w-20 items-center justify-center rounded-full bg-(--color-accent)/10 font-display text-2xl font-semibold text-(--color-accent)">
              {initials(who)}
            </div>
          </div>

          <p className="mt-4 max-w-64 truncate px-4 font-display text-[18px] font-semibold text-fg">{who}</p>
          {call.name && call.number && call.name !== call.number && (
            <p className="data mt-0.5 text-[11px] text-dim">{call.number}</p>
          )}
          <p className="mt-2 text-[13px] font-medium text-(--color-accent)">{label}</p>
        </div>

        <div className="border-t border-line px-6 py-5 text-center">
          <p className="text-[11.5px] leading-relaxed text-dim">
            {call.state === "dialing"
              ? "Your phone is placing the call. Answer and manage it on the device."
              : "Answer or decline on your phone. In-call controls from the Mac need the ADB connection."}
          </p>
          <button onClick={onDismiss} className="btn btn-secondary mt-4 w-full">
            Dismiss
          </button>
        </div>
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
      onToast?.("bad", "Speaker toggle failed — ensure ADB is connected");
    }
  };

  const handleMute = async () => {
    try {
      await adbCallMute();
      setMuted((m) => !m);
    } catch {
      onToast?.("bad", "Mute toggle failed — ensure ADB is connected");
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

        <div className="flex h-10 shrink-0 items-center justify-between border-b border-line pl-4 pr-2">
          <span className="label">On a call</span>
          <button onClick={onDismiss} title="Minimize (call stays active)" className="btn-icon">
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
            {isActive && <span className="absolute -bottom-1 -right-1 h-4 w-4 rounded-full border-2 border-panel2 bg-ok" />}
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
              title="End call"
            >
              <Icon name="phoneOff" size={24} strokeWidth={2} className="text-white" />
            </button>

            <CtrlBtn icon="volume" label="Speaker" active={speaker} activeColor="ok" onClick={handleSpeaker} />
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
              <Icon name="hash" size={12} />
              Keypad
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
  onClick,
}: {
  icon: string;
  label: string;
  active: boolean;
  activeColor: "bad" | "ok";
  onClick: () => void;
}) {
  const colors = { bad: "bg-bad/15 text-bad", ok: "bg-ok/15 text-ok" };
  return (
    <button
      onClick={onClick}
      title={label}
      className={`flex h-12 w-12 flex-col items-center justify-center rounded-full transition-colors ${
        active ? colors[activeColor] : "bg-panel2 text-dim hover:bg-panel3 hover:text-fg"
      }`}
    >
      <Icon name={icon} size={18} strokeWidth={1.75} />
      <span className="mt-0.5 text-[8px] font-medium">{label}</span>
    </button>
  );
}
