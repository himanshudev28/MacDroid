/// The Link — DroidDock's signature motif: the phone and the Mac as two
/// device nodes joined by a thread. While linked, the thread flows (dashed
/// stroke animating in Android-green, the app's "phone heartbeat" color);
/// while unlinked it sits broken and quiet. Rendered small in the sidebar
/// footer and large on the Dashboard pairing card — one motif, two scales.
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
  return (
    <svg
      viewBox="0 0 96 24"
      width={width}
      height={(width / 96) * 24}
      fill="none"
      className={className}
      aria-hidden="true"
    >
      {/* Phone node (portrait) */}
      <rect x="3" y="3.5" width="12" height="17" rx="3" stroke={stroke} strokeWidth="1.5" />
      <circle cx="9" cy="17" r="0.9" fill={stroke} />

      {/* Mac node (display + stand) */}
      <rect x="79" y="5" width="14" height="10" rx="2" stroke={stroke} strokeWidth="1.5" />
      <path d="M84 15v3.5M82 18.5h6" stroke={stroke} strokeWidth="1.5" strokeLinecap="round" />

      {linked ? (
        // Flowing thread: green heartbeat between the two nodes.
        <path
          d="M19 12h56"
          stroke="var(--color-link)"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeDasharray="3 9"
          className="link-thread"
        />
      ) : (
        // Broken thread: two stubs, a gap where the link should be.
        <>
          <path d="M19 12h14" stroke="var(--color-faint)" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="3 6" opacity="0.7" />
          <path d="M61 12h14" stroke="var(--color-faint)" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="3 6" opacity="0.7" />
          <path d="M44.5 9.5l5 5M49.5 9.5l-5 5" stroke="var(--color-faint)" strokeWidth="1.3" strokeLinecap="round" opacity="0.8" />
        </>
      )}
    </svg>
  );
}
