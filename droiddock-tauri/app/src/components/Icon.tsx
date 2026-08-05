// Small inline-SVG icon set (Feather-style, 24×24 stroke paths) so feature
// views need no icon-font dependency — consistent with the Sidebar's approach.

const PATHS: Record<string, string> = {
  bell: "M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 10-12 0v3.2a2 2 0 01-.6 1.4L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9",
  wifi: "M5 12.55a11 11 0 0114 0M8.5 16.11a6 6 0 016.99 0M12 20h.01M2 8.82a15 15 0 0120 0",
  phone:
    "M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z",
  send: "M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z",
  x: "M18 6L6 18M6 6l12 12",
  search: "M21 21l-4.35-4.35M11 19a8 8 0 100-16 8 8 0 000 16z",
  folder: "M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z",
  file: "M13 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V9zM13 2v7h7",
  download: "M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3",
  upload: "M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M17 8l-5-5-5 5M12 3v12",
  trash: "M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2",
  edit: "M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z",
  play: "M5 3l14 9-14 9V3z",
  pause: "M6 4h4v16H6zM14 4h4v16h-4z",
  skipBack: "M19 20L9 12l10-8v16zM5 19V5",
  skipForward: "M5 4l10 8-10 8V4zM19 5v14",
  image: "M19 3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V5a2 2 0 00-2-2zM8.5 10a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM21 15l-5-5L5 21",
  film: "M19 3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V5a2 2 0 00-2-2zM7 3v18M17 3v18M3 12h18M3 7.5h4M3 16.5h4M17 7.5h4M17 16.5h4",
  volume: "M11 5L6 9H2v6h4l5 4V5zM15.54 8.46a5 5 0 010 7.07M19.07 4.93a10 10 0 010 14.14",
  // Closed padlock — the phone-card action that locks the phone's screen.
  lock: "M6 10.5h12a1.5 1.5 0 011.5 1.5v7a1.5 1.5 0 01-1.5 1.5H6A1.5 1.5 0 014.5 19v-7A1.5 1.5 0 016 10.5zM8 10.5V7a4 4 0 118 0v3.5",
  reply: "M9 17l-5-5 5-5M4 12h11a5 5 0 015 5v1",
  reload: "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15",
  "alert-triangle":
    "M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0zM12 9v4M12 17h.01",
  monitor: "M20 3H4a1 1 0 00-1 1v12a1 1 0 001 1h16a1 1 0 001-1V4a1 1 0 00-1-1zM8 21h8M12 17v4",
  chevronRight: "M9 18l6-6-6-6",
  chevronUp: "M18 15l-6-6-6 6",
  message:
    "M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z",
  star: "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
  clipboard:
    "M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 2h6a1 1 0 011 1v2a1 1 0 01-1 1H9a1 1 0 01-1-1V3a1 1 0 011-1z",
  check: "M20 6L9 17l-5-5",
  info: "M12 22a10 10 0 100-20 10 10 0 000 20zM12 16v-4M12 8h.01",
  alert: "M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z",
  cornerUpLeft: "M9 14L4 9l5-5M4 9h11a4 4 0 014 4v7",
  arrowLeft: "M19 12H5m0 0l7 7m-7-7l7-7",
  circle: "M12 22a10 10 0 100-20 10 10 0 000 20z",
  squareStack:
    "M4 5a2 2 0 012-2h6a2 2 0 012 2v6a2 2 0 01-2 2H6a2 2 0 01-2-2V5z M8 12v6a2 2 0 002 2h8a2 2 0 002-2v-8a2 2 0 00-2-2h-3",
  pin: "M12 2a5 5 0 015 5c0 3.5-5 11-5 11S7 10.5 7 7a5 5 0 015-5zm0 7a2 2 0 100-4 2 2 0 000 4z",
  switchCamera:
    "M4 14a8 8 0 0013.32 5.66M20 10A8 8 0 006.68 4.34M20 4v6h-6M4 20v-6h6",
  camera:
    "M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z M12 17a4 4 0 100-8 4 4 0 000 8z",
  terminal: "M4 17l6-6-6-6M12 19h8",
  copy: "M20 9H11a2 2 0 00-2 2v9a2 2 0 002 2h9a2 2 0 002-2v-9a2 2 0 00-2-2z M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1",
  key: "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 11-7.778 7.778 5.5 5.5 0 017.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4",
  qrcode: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h3v3h-3zM20 14v3h-3M14 20h3M20 20h.01",
  alertTriangle: "M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z",
  checkCircle: "M22 11.08V12a10 10 0 11-5.93-9.14M22 4L12 14.01l-3-3",
  mic: "M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z M19 10v2a7 7 0 01-14 0v-2 M12 19v4 M8 23h8",
  micOff: "M1 1l22 22 M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6 M17 16.95A7 7 0 015 12v-2m14 0v2a7 7 0 01-.11 1.23 M12 19v4M8 23h8",
  hash: "M4 9h16M4 15h16M10 3L8 21M16 3l-2 18",
  phoneOff:
    "M10.68 13.31a16 16 0 003.41 2.6l1.27-1.27a2 2 0 012.11-.36 11.72 11.72 0 003.61 1.35 1 1 0 011 1V21a1 1 0 01-1 1 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 013 4a1 1 0 011-1h4a1 1 0 011 1 11.72 11.72 0 001.35 3.61 2 2 0 01-.36 2.11l-1.27 1.27M22 1L1 22",
};

export default function Icon({
  name,
  size = 16,
  className = "",
  strokeWidth = 1.75,
  fill = "none",
  title,
}: {
  name: keyof typeof PATHS | string;
  size?: number;
  className?: string;
  strokeWidth?: number;
  fill?: string;
  /// Renders an SVG <title>, which is both the tooltip and the accessible
  /// name. Without it the glyph stays `aria-hidden` — decorative by default.
  title?: string;
}) {
  const d = PATHS[name] ?? "";
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill={fill}
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
    >
      {title && <title>{title}</title>}
      <path d={d} />
    </svg>
  );
}
