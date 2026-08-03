// Shared formatting/avatar helpers used across the feature views. The avatar
// palette + hash and the time/day formatting mirror the Electron components so
// contacts, threads and messages read the same as before.

const AVATAR_COLORS = [
  "#f5a623",
  "#0a84ff",
  "#34c759",
  "#ff453a",
  "#bf5af2",
  "#5ac8fa",
];

export function avatarColor(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) | 0;
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
}

export function initials(name: string): string {
  const parts = (name || "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 1).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

/// Time if today, else "D Mon" — matches the notification/message formatter.
export function fmtTime(d?: number | null): string {
  if (!d) return "";
  const date = new Date(Number(d));
  const now = new Date();
  return date.toDateString() === now.toDateString()
    ? date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : date.toLocaleDateString([], { day: "numeric", month: "short" });
}

/// Today / Yesterday / "D Mon YYYY" — MessagesView day dividers.
export function dayLabel(d: number): string {
  const date = new Date(Number(d));
  const now = new Date();
  const y = new Date(now);
  y.setDate(now.getDate() - 1);
  if (date.toDateString() === now.toDateString()) return "Today";
  if (date.toDateString() === y.toDateString()) return "Yesterday";
  return date.toLocaleDateString([], { day: "2-digit", month: "short", year: "numeric" });
}

export function fmtDuration(ms?: number): string {
  if (!ms) return "";
  const s = Math.round(ms / 1000);
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, "0")}`;
}

export function fmtBytes(n: number): string {
  if (!n) return "0 B";
  const u = ["B", "KB", "MB", "GB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${u[i]}`;
}
