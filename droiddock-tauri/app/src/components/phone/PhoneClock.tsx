import { useEffect, useState } from "react";
import { getClockStyle, type ClockStyle } from "../../lib/appearance";

/// Styles that paint the glyphs themselves rather than laying them out
/// differently. Each class in `index.css` owns its own colour *and* its own
/// shadow — see the note at the call site for why the base shadow can't just
/// be layered underneath them.
const PAINT: Partial<Record<ClockStyle, string>> = {
  neon: "clock-neon",
  outline: "clock-outline",
  bubble: "clock-bubble",
  gradient: "clock-gradient",
};

/// The phone card's clock. Reads the *Mac's* clock, not the phone's — the two
/// are on the same LAN and effectively always the same wall time, and putting a
/// clock on the wire would mean a message every minute for zero added truth.
/// (AirSync's `TimeView` does exactly the same thing: plain local `Date()`.)
///
/// Built once, at module scope — see the note at the call site.
const TIME_FMT = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
const TIME_WITH_SECONDS = new Intl.DateTimeFormat(undefined, {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});
const DATE_FMT = new Intl.DateTimeFormat(undefined, {
  weekday: "long",
  month: "short",
  day: "numeric",
});

/// `compact` shrinks it to make room for the mini player. Eight styles, chosen
/// in Settings › Appearance — see `ClockStyle`.
export default function PhoneClock({ compact = false }: { compact?: boolean }) {
  const [now, setNow] = useState(() => new Date());
  const style = useClockStyle();
  // Only `mono` shows seconds, so only `mono` pays for a 1s tick. Every other
  // style ticks on the minute boundary — the display has no seconds, so a 1s
  // interval would be 59 wasted renders out of 60.
  const seconds = style === "mono";

  useEffect(() => {
    let timer: number;
    const schedule = () => {
      const period = seconds ? 1_000 : 60_000;
      const ms = period - (Date.now() % period);
      timer = window.setTimeout(() => {
        setNow(new Date());
        schedule();
      }, ms + 50);
    };
    schedule();
    return () => window.clearTimeout(timer);
  }, [seconds]);

  // Respect the user's 12/24h system preference the same way macOS does.
  //
  // The two formatters are module-level singletons rather than fresh objects
  // per render: constructing an `Intl.DateTimeFormat` builds an ICU pattern and
  // is by far the most expensive thing this component does. It only ever needs
  // two shapes (with and without seconds), neither of which depends on `now`,
  // and the component re-renders far more often than it ticks — its parent
  // re-renders for reasons of its own.
  const parts = (seconds ? TIME_WITH_SECONDS : TIME_FMT).formatToParts(now);
  const at = (t: string) => parts.find((p) => p.type === t)?.value;
  const hour = at("hour") ?? "--";
  const minute = at("minute") ?? "--";
  const second = at("second");
  const dayPeriod = at("dayPeriod");

  const weekday = DATE_FMT.format(now);

  // Two shadows on every style, not one: the tight pass gives glyph edges
  // their own contrast against whatever pixel is behind them, the wide pass
  // separates the whole block from a busy backdrop. Album art is arbitrary
  // imagery — a single soft shadow does neither job over a text-heavy cover.
  const shadow = "0 1px 2px rgba(0,0,0,0.55), 0 2px 20px rgba(0,0,0,0.45)";
  const dateShadow = "0 1px 3px rgba(0,0,0,0.7)";

  const showDate = style !== "minimal";

  return (
    <div className="pointer-events-none flex flex-col items-center text-center select-none">
      {style === "stacked" || style === "bubble" ? (
        <div
          className={`font-display text-white transition-[font-size] duration-300 ease-out ${
            style === "bubble" ? "font-extrabold clock-bubble" : "font-semibold"
          }`}
          style={{
            // Bubble runs smaller: a 6px stroke grows every glyph outward, so
            // at 64px the two rows overflow a 300px-wide card.
            fontSize: style === "bubble" ? (compact ? 44 : 66) : compact ? 50 : 78,
            lineHeight: 0.86,
            letterSpacing: "-0.045em",
            fontVariantNumeric: "tabular-nums",
            // Bubble paints its own outline and drop-shadow; layering the base
            // text-shadow under a 6px stroke just muddies the edge.
            ...(style === "bubble" ? {} : { textShadow: shadow }),
          }}
        >
          <div>{hour}</div>
          <div className="opacity-80">{minute}</div>
          {dayPeriod && (
            <div className="mt-0.5 font-medium opacity-60" style={{ fontSize: compact ? 13 : 17 }}>
              {dayPeriod}
            </div>
          )}
        </div>
      ) : style === "mono" ? (
        <div
          className="font-mono font-medium text-white transition-[font-size] duration-300 ease-out"
          style={{
            fontSize: compact ? 30 : 46,
            letterSpacing: "-0.02em",
            fontVariantNumeric: "tabular-nums",
            textShadow: shadow,
          }}
        >
          {hour}:{minute}
          {second && <span className="opacity-55">:{second}</span>}
          {dayPeriod && <span className="ml-1.5 text-[0.5em] opacity-60">{dayPeriod}</span>}
        </div>
      ) : (
        // Everything else shares the one-line layout and differs only in how
        // the glyphs are painted — a CSS class, not a different tree. `neon`,
        // `outline` and `gradient` each replace the base text-shadow with their
        // own treatment (an outline has no fill to shadow; a gradient's fill is
        // transparent and needs a drop-shadow instead), so the shared shadow is
        // applied only where none of them is active.
        <div
          className={`font-display text-white transition-[font-size] duration-300 ease-out ${
            style === "minimal" ? "font-light" : "font-semibold"
          } ${PAINT[style] ?? ""}`}
          style={{
            fontSize: compact ? 54 : 82,
            lineHeight: 0.88,
            letterSpacing: style === "minimal" ? "-0.02em" : "-0.04em",
            fontVariantNumeric: "tabular-nums",
            ...(PAINT[style] ? {} : { textShadow: shadow }),
          }}
        >
          <span>{hour}</span>
          <span className="opacity-45">:</span>
          <span>{minute}</span>
          {dayPeriod && (
            <span
              className="ml-1 align-top font-medium opacity-60"
              style={{ fontSize: compact ? 15 : 21 }}
            >
              {dayPeriod}
            </span>
          )}
        </div>
      )}

      {showDate && (
        <p
          className="mt-1 font-medium text-white/90 transition-opacity duration-300"
          style={{
            fontSize: 12.5,
            opacity: compact ? 0 : 1,
            height: compact ? 0 : undefined,
            textShadow: dateShadow,
          }}
        >
          {weekday}
        </p>
      )}
    </div>
  );
}

/// Live view of the chosen style, so changing it in Settings repaints the card
/// immediately rather than on the next mount.
function useClockStyle(): ClockStyle {
  const [style, setStyle] = useState(getClockStyle);
  useEffect(() => {
    const refresh = () => setStyle(getClockStyle());
    window.addEventListener("droiddock:clock", refresh);
    return () => window.removeEventListener("droiddock:clock", refresh);
  }, []);
  return style;
}
