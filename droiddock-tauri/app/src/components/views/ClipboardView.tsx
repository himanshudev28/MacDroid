import { memo } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import type { DroidConfig } from "../../lib/bridge";
import { t, useT } from "../../lib/i18n";

/// Clipboard status page. The sync itself runs entirely in Rust (a 1s
/// NSPasteboard watcher + the inbound write path) — this view is just a status
/// display + a shortcut to the toggle, mirroring the Electron ClipboardView
/// (which was also status-only, with the real switch living in Settings).
function ClipboardView({
  linked,
  config,
  onToggle,
}: {
  linked: boolean;
  config: DroidConfig | null;
  onToggle: (on: boolean) => void;
}) {
  // Memoised: its props do not change when only the language does, so without
  // its own subscription it would keep rendering the old strings.
  useT();
  if (!linked) {
    return (
      <EmptyState
        icon="wifi"
        title={t("No phone linked")}
        body={t("Link your phone from the Dashboard to keep both clipboards in sync.")}
      />
    );
  }

  const on = config?.clipboardSync ?? true;

  return (
    <div className="flex h-full w-full items-center justify-center overflow-y-auto px-6 py-8">
      <div className="rise w-full max-w-90">
        <div className="card-raised p-7 text-center">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-panel3">
            <Icon name="clipboard" size={20} strokeWidth={1.5} className="text-dim" />
          </div>
          <h1 className="mt-4 font-display text-[17px] font-semibold text-fg">{t("Clipboard sync")}</h1>
          <p className="mx-auto mt-1.5 max-w-60 text-[12.5px] leading-relaxed text-dim">
            Copy text on either device and it appears on the other automatically — checked once
            a second, both directions.
          </p>

          <p
            className={`mt-4 flex items-center justify-center gap-1.5 text-[12.5px] font-medium ${
              on ? "text-(--color-link)" : "text-dim"
            }`}
          >
            <span className={`h-1.5 w-1.5 rounded-full ${on ? "led bg-(--color-link)" : "bg-faint"}`} />
            {on ? t("Sync is on") : t("Sync is off")}
          </p>

          <button
            onClick={() => onToggle(!on)}
            className={`btn mt-5 w-full ${on ? "btn-secondary" : "btn-primary"}`}
          >
            {on ? t("Turn off sync") : t("Turn on sync")}
          </button>
        </div>
      </div>
    </div>
  );
}

/* Memoised. This was originally defence against the phone's 1 Hz now-playing
   push re-rendering every view; that push no longer reaches `App` at all (it
   lives in `lib/mediaStore`, read only by the two components that show it). The
   memo stays because `App` still re-renders for its own reasons — an arriving
   notification, a toast appearing and expiring, a transfer's progress — and
   none of those change this view's props. All props here are primitives or
   stable useCallback refs, so the comparison is sound. */
export default memo(ClipboardView);
