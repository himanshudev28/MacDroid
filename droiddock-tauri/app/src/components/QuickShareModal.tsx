import type { QuickShareRequest } from "../lib/bridge";
import Icon from "./Icon";

/// Prompt for an incoming Quick Share transfer.
///
/// The PIN is the point of this dialog, not decoration: it is the only defence
/// against accepting from the wrong device. Anyone on the network can offer
/// files, and the sender's name is whatever it claims — the PIN is derived from
/// the UKEY2 handshake, so it matches on both screens only if the connection is
/// genuinely with the device the user is looking at. It gets the visual weight.
///
/// There is no dismiss-by-clicking-away and no default action: the sender is
/// blocked waiting on an answer, and silently closing would leave it hanging
/// until its own timeout.
export default function QuickShareModal({
  request,
  onRespond,
}: {
  request: QuickShareRequest;
  onRespond: (accept: boolean) => void;
}) {
  const total = request.files.reduce((sum, f) => sum + (f.size || 0), 0);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/60 backdrop-blur-sm">
      <div className="rise card-raised float-lg w-full max-w-md p-6">
        <div className="flex items-start gap-3">
          <Icon name="download" className="mt-0.5 h-5 w-5 shrink-0 text-accent" />
          <div className="min-w-0 flex-1">
            <h2 className="font-display text-[17px] font-semibold text-fg">
              {request.peer} wants to send you {request.files.length}{" "}
              {request.files.length === 1 ? "file" : "files"}
            </h2>
            <p className="mt-1 text-[12px] leading-relaxed text-dim">
              Accept only if this code matches the one on the sending device.
            </p>
          </div>
        </div>

        <div className="mt-4 flex items-center justify-center rounded-lg border border-line/60 bg-surface/60 py-4">
          <span className="data text-[30px] font-semibold tracking-[0.35em] text-fg">
            {request.pin}
          </span>
        </div>

        <ul className="mt-4 max-h-40 space-y-1 overflow-y-auto">
          {request.files.map((f, i) => (
            <li key={i} className="flex items-baseline justify-between gap-3 text-[12px]">
              <span className="truncate text-fg">{f.name}</span>
              <span className="data shrink-0 text-dim">{formatSize(f.size)}</span>
            </li>
          ))}
        </ul>
        {request.files.length > 1 && (
          <p className="mt-2 text-right text-[11px] text-dim">{formatSize(total)} total</p>
        )}

        <div className="mt-5 flex justify-end gap-2">
          <button className="btn-ghost" onClick={() => onRespond(false)}>
            Decline
          </button>
          <button className="btn-primary" onClick={() => onRespond(true)}>
            Accept
          </button>
        </div>
      </div>
    </div>
  );
}

function formatSize(bytes: number) {
  if (!bytes || bytes < 0) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let v = bytes;
  let u = 0;
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024;
    u++;
  }
  return `${u === 0 ? v : v.toFixed(v < 10 ? 1 : 0)} ${units[u]}`;
}
