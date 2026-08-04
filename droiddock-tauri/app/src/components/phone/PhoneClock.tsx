import { useEffect, useState } from "react";

/// The phone card's clock. Reads the *Mac's* clock, not the phone's — the two
/// are on the same LAN and effectively always the same wall time, and putting a
/// clock on the wire would mean a message every minute for zero added truth.
/// (AirSync's `TimeView` does exactly the same thing: plain local `Date()`.)
///
/// `compact` shrinks it to make room for the mini player; the two sizes share
/// one layout so the transition between them is a pure scale, never a reflow.
export default function PhoneClock({ compact = false }: { compact?: boolean }) {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    // Tick on the minute boundary rather than every second — the display has
    // no seconds, so a 1s interval would be 59 wasted renders out of 60.
    let timer: number;
    const schedule = () => {
      const ms = 60_000 - (Date.now() % 60_000);
      timer = window.setTimeout(() => {
        setNow(new Date());
        schedule();
      }, ms + 50);
    };
    schedule();
    return () => window.clearTimeout(timer);
  }, []);

  // Respect the user's 12/24h system preference the same way macOS does.
  const parts = new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(now);
  const hour = parts.find((p) => p.type === "hour")?.value ?? "--";
  const minute = parts.find((p) => p.type === "minute")?.value ?? "--";
  const dayPeriod = parts.find((p) => p.type === "dayPeriod")?.value;

  const weekday = new Intl.DateTimeFormat(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  }).format(now);

  return (
    <div className="pointer-events-none select-none text-center">
      <div
        className="font-display font-semibold leading-[0.88] text-white transition-[font-size] duration-300 ease-out"
        style={{
          fontSize: compact ? 46 : 68,
          letterSpacing: "-0.04em",
          fontVariantNumeric: "tabular-nums",
          textShadow: "0 1px 12px rgba(0,0,0,0.35)",
        }}
      >
        <span>{hour}</span>
        <span className="opacity-45">:</span>
        <span>{minute}</span>
        {dayPeriod && (
          <span
            className="ml-1 align-top font-medium opacity-60"
            style={{ fontSize: compact ? 14 : 18 }}
          >
            {dayPeriod}
          </span>
        )}
      </div>
      <p
        className="mt-1 font-medium text-white/60 transition-opacity duration-300"
        style={{ fontSize: 11.5, opacity: compact ? 0 : 1, height: compact ? 0 : undefined }}
      >
        {weekday}
      </p>
    </div>
  );
}
