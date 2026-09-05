/// The Link — DroidDock's signature motif: the phone and the Mac as two
/// device nodes joined by a thread. While linked, the thread flows (dashed
/// stroke animating in Android-green, the app's "phone heartbeat" color);
/// while unlinked it sits broken and quiet. Rendered small in the sidebar
/// footer and large on the Dashboard pairing card — one motif, two scales.
///
/// # Why the flowing thread is its own <svg>
///
/// The obvious way to write this is one `<svg>` with an animated
/// `stroke-dashoffset` on the thread. That is what it used to be, and it cost
/// 13% of the GPU, continuously, for as long as the app was linked and on the
/// Dashboard — measured, not guessed.
///
/// `stroke-dashoffset` is SVG geometry, so animating it *repaints* every
/// frame, and WebKit does not give SVG child elements their own compositing
/// layer. Sixty repaints a second inside a transparent window stacked on an
/// `NSVisualEffectView` and two `backdrop-filter` surfaces is the single most
/// expensive thing this UI can do.
///
/// So the thread is a second, separate `<svg>` — an HTML-level replaced
/// element, which *can* be promoted — holding one over-long dashed path,
/// clipped by the span around it and slid left by exactly one dash period
/// (12 of its 68 user units, hence the -17.6471%). The dash pattern repeats,
/// so a shift of one period is indistinguishable from a dashoffset of one
/// period, and the geometry is arranged so both `<svg>`s scale identically
/// (`width/96` px per user unit) and the stroke weights still match.
///
/// The motion is now a `transform` on a promoted layer: the compositor
/// replays it and nothing repaints at all.
export default function LinkPulse({
  linked,
  width = 96,
  className = "",
}: {
  linked: boolean;
  width?: number;
  className?: string;
}) {
  const stroke = linked ? "var(--color-link)" : "var(--color-faint)";
  const height = (width / 96) * 24;
  return (
    <span
      className={`relative inline-block align-middle ${className}`}
      style={{ width, height }}
      aria-hidden="true"
    >
      <svg viewBox="0 0 96 24" width={width} height={height} fill="none" className="block">
        {/* Phone node (portrait) */}
        <rect x="3" y="3.5" width="12" height="17" rx="3" stroke={stroke} strokeWidth="1.5" />
        <circle cx="9" cy="17" r="0.9" fill={stroke} />

        {/* Mac node (display + stand) */}
        <rect x="79" y="5" width="14" height="10" rx="2" stroke={stroke} strokeWidth="1.5" />
        <path d="M84 15v3.5M82 18.5h6" stroke={stroke} strokeWidth="1.5" strokeLinecap="round" />

        {!linked && (
          // Broken thread: two stubs, a gap where the link should be. Static,
          // so it stays in the main <svg> — there is nothing to promote.
          <>
            <path d="M19 12h14" stroke="var(--color-faint)" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="3 6" opacity="0.7" />
            <path d="M61 12h14" stroke="var(--color-faint)" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="3 6" opacity="0.7" />
            <path d="M44.5 9.5l5 5M49.5 9.5l-5 5" stroke="var(--color-faint)" strokeWidth="1.3" strokeLinecap="round" opacity="0.8" />
          </>
        )}
      </svg>

      {linked && (
        // The window the thread runs through: user units 19..75 of the 96-unit
        // viewBox, i.e. exactly the span the old `M19 12h56` path occupied.
        <span
          className="absolute top-0 h-full overflow-hidden"
          style={{ left: `${(19 / 96) * 100}%`, width: `${(56 / 96) * 100}%` }}
        >
          {/* 68 units of path in a 56-unit window: 12 units of overhang, so the
              window stays covered across the whole -12 unit travel. */}
          <svg
            viewBox="0 0 68 24"
            fill="none"
            className="link-thread absolute left-0 top-0 h-full"
            style={{ width: `${(68 / 56) * 100}%` }}
          >
            <path
              d="M0 12h68"
              stroke="var(--color-link)"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeDasharray="3 9"
            />
          </svg>
        </span>
      )}
    </span>
  );
}
