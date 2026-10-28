import { useEffect, useState } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import {
  transferCancel,
  transferOpen,
  transferReveal,
  transfersClear,
  type TransferEntry,
} from "../../lib/bridge";
import { useT, t } from "../../lib/i18n";
import { useTransfers } from "../../lib/transferStore";
import { fmtBytes, fmtTime } from "../../lib/ui";

/// Every file moving between the phone and this Mac, whichever feature moved
/// it: live speed and time left while it runs, then how it went.
///
/// The list itself comes from `transfer_log.rs`; this page only formats it.
export default function TransfersView({
  onToast,
}: {
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  useT();
  const entries = useTransfers();
  const active = entries.filter((e) => e.status === "active");
  // Newest first. Rust keeps them oldest first.
  const finished = entries.filter((e) => e.status !== "active").reverse();

  // A clock for "stalled" and "2 min ago". Ticks every second while anything
  // is moving (stalls show quickly), every 30 s otherwise (only ages change).
  const now = useNow(active.length > 0 ? 1000 : 30_000);

  const run = (p: Promise<unknown>) =>
    p.catch((e) => onToast("bad", errorText(String(e))));

  if (entries.length === 0) {
    return (
      <EmptyState
        icon="upload"
        title={t("No transfers yet")}
        body={t("Files moving between your phone and this Mac show up here, with live speed and time left.")}
      />
    );
  }

  const totalSpeed = active
    .filter((e) => e.unit === "bytes" && now - e.updatedMs < STALL_MS)
    .reduce((sum, e) => sum + e.speed, 0);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 items-center justify-between gap-3 px-6 pt-5 pb-4">
        <div className="flex items-baseline gap-2.5">
          <h1 className="font-display text-[17px] font-semibold text-fg">{t("Transfers")}</h1>
          {active.length > 0 && (
            <span className="data text-dim">
              {t("{n} active", { n: active.length })}
              {totalSpeed > 0 && ` · ${fmtSpeed(totalSpeed)}`}
            </span>
          )}
        </div>
        {finished.length > 0 && (
          <button onClick={() => run(transfersClear())} className="btn btn-ghost">
            {t("Clear")}
          </button>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
        {active.length > 0 && (
          <section>
            <h2 className="label mb-2 text-faint">{t("Active")}</h2>
            <div className="flex flex-col gap-2">
              {active.map((e) => (
                <ActiveRow key={e.key} e={e} now={now} onCancel={() => run(transferCancel(e.key))} />
              ))}
            </div>
          </section>
        )}

        {finished.length > 0 && (
          <section className={active.length > 0 ? "mt-6" : ""}>
            <h2 className="label mb-2 text-faint">{t("Finished")}</h2>
            <div className="card divide-y divide-line overflow-hidden">
              {finished.map((e) => (
                <FinishedRow
                  key={e.key}
                  e={e}
                  now={now}
                  onReveal={() => run(transferReveal(e.key))}
                  onOpen={() => run(transferOpen(e.key))}
                />
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

/// No progress for this long reads as "waiting", not as a speed.
const STALL_MS = 3000;

function ActiveRow({ e, now, onCancel }: { e: TransferEntry; now: number; onCancel: () => void }) {
  const pct = e.total > 0 ? Math.min(100, (e.done / e.total) * 100) : 0;
  // Bytes only: photo sync reports once per photo, so a long video would
  // otherwise read as stuck while it's downloading fine.
  const stalled = e.unit === "bytes" && now - e.updatedMs > STALL_MS;

  const parts: string[] = [];
  if (e.unit === "items") {
    parts.push(t("{done} of {total} photos", { done: e.done, total: e.total }));
  } else if (e.total > 0) {
    parts.push(t("{done} of {total}", { done: fmtBytes(e.done), total: fmtBytes(e.total) }));
  } else {
    parts.push(t("{done} received", { done: fmtBytes(e.done) }));
  }
  if (stalled) {
    parts.push(t("Waiting for the phone…"));
  } else {
    if (e.unit === "bytes" && e.speed > 0) parts.push(fmtSpeed(e.speed));
    if (e.etaSecs != null) parts.push(t("{time} left", { time: fmtSecs(e.etaSecs) }));
  }

  return (
    <div className="card rise-fast px-4 py-3">
      <div className="flex items-center gap-3">
        <FileBadge e={e} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="min-w-0 truncate text-[13px] font-medium text-fg">{displayName(e)}</span>
            <DirChip e={e} />
          </div>
        </div>
        <span className="data shrink-0 text-dim">{e.total > 0 ? `${Math.floor(pct)}%` : ""}</span>
        {e.cancellable && (
          <button onClick={onCancel} title={t("Cancel transfer")} aria-label={t("Cancel transfer")} className="btn-icon shrink-0">
            <Icon name="x" size={13} />
          </button>
        )}
      </div>

      <div
        className="mt-2.5 h-1 overflow-hidden rounded-full bg-line"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.floor(pct)}
        aria-label={displayName(e)}
      >
        <div
          className={`h-full rounded-full transition-[width] duration-300 ease-out ${
            stalled ? "bg-(--color-faint)" : "bg-(--color-accent)"
          }`}
          style={{ width: `${pct}%` }}
        />
      </div>

      <p className="data mt-1.5 truncate text-faint">{parts.join(" · ")}</p>
    </div>
  );
}

function FinishedRow({
  e,
  now,
  onReveal,
  onOpen,
}: {
  e: TransferEntry;
  now: number;
  onReveal: () => void;
  onOpen: () => void;
}) {
  const took = e.endedMs != null ? (e.endedMs - e.startedMs) / 1000 : 0;

  let detail: string;
  let tone = "text-faint";
  if (e.status === "done") {
    const bits = [fmtAgo(e.endedMs ?? e.startedMs, now), t("took {time}", { time: fmtSecs(took) })];
    if (e.unit === "bytes" && e.speed > 0) bits.push(fmtSpeed(e.speed));
    detail = bits.join(" · ");
  } else if (e.status === "cancelled") {
    detail = e.cancelledBy === "phone" ? t("Cancelled on phone") : t("Cancelled");
  } else {
    detail = errorText(e.error ?? "Transfer failed");
    tone = "text-(--color-bad)";
  }
  // A finished photo sync can still have skipped some photos.
  const note = e.status === "done" ? e.error : null;

  const size =
    e.unit === "items"
      ? t("{done} of {total} photos", { done: e.done, total: e.total })
      : e.total > 0
        ? fmtBytes(e.total)
        : "";

  // Finder-volume copies land in a hidden cache, not somewhere worth showing.
  const canShow = e.status === "done" && e.path != null && e.source !== "finder";

  return (
    <div className="flex items-center gap-3 px-4 py-2.5">
      <StatusMark status={e.status} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="min-w-0 truncate text-[13px] text-fg">{displayName(e)}</span>
          <DirChip e={e} />
          {size && <span className="data shrink-0 text-dim">{size}</span>}
        </div>
        <p className={`mt-0.5 truncate text-[11.5px] ${tone}`} title={detail}>
          {detail}
        </p>
        {note && (
          <p className="mt-0.5 truncate text-[11.5px] text-(--color-warn)" title={note}>
            {note}
          </p>
        )}
      </div>
      {canShow && (
        <div className="flex shrink-0 items-center gap-1">
          <button onClick={onReveal} className="btn btn-ghost h-7 px-2.5 text-[12px]">
            {t("Show in Finder")}
          </button>
          {e.unit === "bytes" && !RUNNABLE.test(e.path ?? "") && (
            <button onClick={onOpen} className="btn btn-ghost h-7 px-2.5 text-[12px]">
              {t("Open")}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/// "Mac → Phone" plus, for anything not started from the Files page, which
/// feature moved it.
function DirChip({ e }: { e: TransferEntry }) {
  const source = sourceLabel(e.source);
  return (
    <span className="flex shrink-0 items-center gap-1.5">
      <span className="rounded-full bg-panel3 px-1.5 py-0.5 text-[10.5px] font-medium text-dim">
        {e.dir === "toPhone" ? t("Mac → Phone") : t("Phone → Mac")}
      </span>
      {source && <span className="text-[10.5px] text-faint">{source}</span>}
    </span>
  );
}

/// Written as literal `t()` calls so the i18n checker can see each string.
function sourceLabel(source: TransferEntry["source"]): string | null {
  switch (source) {
    case "share":
      return t("Share menu");
    case "macFiles":
      return t("Mac Files");
    case "quickShare":
      return t("Quick Share");
    case "finder":
      return t("Finder");
    // Files is the default and needs no label; a photo sync's name already
    // says what it is.
    default:
      return null;
  }
}

/// The errors the transfer code reports in its own words, translated. Anything
/// else (an OS error, a message from the phone) is shown as it came.
function errorText(error: string): string {
  switch (error) {
    case "Connection to the phone was lost":
      return t("Connection to the phone was lost");
    case "Connection to the sender was lost":
      return t("Connection to the sender was lost");
    case "Interrupted":
      return t("Interrupted");
    case "This file can run code, so it isn't opened from here. Use Show in Finder.":
      return t("This file can run code, so it isn't opened from here. Use Show in Finder.");
    case "Phone disconnected mid-transfer":
      return t("Phone disconnected mid-transfer");
    case "Transfer failed":
      return t("Transfer failed");
    case "The file has been moved or deleted":
      return t("The file has been moved or deleted");
    default:
      return error;
  }
}

function displayName(e: TransferEntry): string {
  return e.source === "photoSync" ? t("Photo sync") : e.name;
}

function FileBadge({ e }: { e: TransferEntry }) {
  return (
    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-panel3">
      <Icon name={iconFor(e)} size={15} strokeWidth={1.6} className="text-dim" />
    </div>
  );
}

function StatusMark({ status }: { status: TransferEntry["status"] }) {
  if (status === "done") {
    return <Icon name="checkCircle" size={16} className="shrink-0 text-(--color-ok)" />;
  }
  if (status === "failed") {
    return <Icon name="alertTriangle" size={16} className="shrink-0 text-(--color-bad)" />;
  }
  return <Icon name="x" size={16} className="shrink-0 text-faint" />;
}

/// File types that run code when opened — no Open button for these (Show in
/// Finder still works). Kept in step with `RUNNABLE` in transfer_log.rs, which
/// also refuses them, along with anything marked executable.
const RUNNABLE =
  /\.(app|command|terminal|tool|sh|zsh|bash|csh|py|rb|pl|pkg|mpkg|dmg|jar|scptd?|applescript|workflow|action|prefpane|kext|dylib|webloc|inetloc|fileloc|url)$/i;

const IMAGE = /\.(jpe?g|png|gif|heic|heif|webp|bmp|tiff?|avif|dng|raw)$/i;
const VIDEO = /\.(mp4|mov|m4v|mkv|webm|avi|3gp|hevc)$/i;
const AUDIO = /\.(mp3|m4a|aac|wav|flac|ogg|opus|amr)$/i;

function iconFor(e: TransferEntry): string {
  if (e.source === "photoSync") return "image";
  if (IMAGE.test(e.name)) return "image";
  if (VIDEO.test(e.name)) return "film";
  if (AUDIO.test(e.name)) return "volume";
  return e.dir === "toPhone" ? "upload" : "download";
}

function fmtSpeed(bytesPerSec: number): string {
  return `${fmtBytes(Math.round(bytesPerSec))}/s`;
}

/// "8 s", "3 min 5 s", "1 h 12 min".
function fmtSecs(secs: number): string {
  const s = Math.max(1, Math.round(secs));
  if (s < 60) return t("{n} s", { n: s });
  if (s < 3600) return t("{m} min {s} s", { m: Math.floor(s / 60), s: s % 60 });
  return t("{h} h {m} min", { h: Math.floor(s / 3600), m: Math.floor((s % 3600) / 60) });
}

function fmtAgo(ms: number, now: number): string {
  const mins = Math.floor((now - ms) / 60_000);
  if (mins < 1) return t("just now");
  if (mins < 60) return t("{n} min ago", { n: mins });
  return fmtTime(ms);
}

function useNow(every: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), every);
    return () => window.clearInterval(id);
  }, [every]);
  return now;
}
