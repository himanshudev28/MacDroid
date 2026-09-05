import { memo } from "react";
import EmptyState from "../EmptyState";
import Icon from "../Icon";
import { t, useT } from "../../lib/i18n";

/// CallsView is purely informational — there is no dialer or call log here.
///
/// What it says depends on what the phone can actually do, which is now two
/// different stories. A phone advertising `callctl` can be answered, hung up
/// and re-routed from the Mac over the plain Wi-Fi link (`CallControl.kt`);
/// one that can't still gets the caller-ID alert and nothing more. DTMF is the
/// one control that stays ADB-only in both cases, because playing tones into a
/// live call is reachable only from the device's default dialer.
function CallsView({ linked, canControl }: { linked: boolean; canControl: boolean }) {
  // Memoised: its props do not change when the language does, so without its
  // own subscription it would keep the old strings. Same for every memo() view.
  useT();

  if (!linked) {
    return (
      <EmptyState
        icon="phone"
        title={t("Connect phone to see calls")}
        body={t("Pair the DroidDock phone app over Wi-Fi to see incoming calls with caller ID here.")}
      />
    );
  }

  return (
    <div className="flex h-full items-center justify-center p-8">
      <div className="rise w-full max-w-sm space-y-3">
        <div className="card-raised p-6">
          <div className="flex items-start gap-3.5">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-panel3">
              <Icon name="phone" size={17} className="text-fg/80" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <p className="font-display text-[15px] font-semibold text-fg">{t("Calls ring through")}</p>
                <span className="led h-1.5 w-1.5 shrink-0 rounded-full bg-(--color-link)" />
              </div>
              <p className="mt-1 text-[12px] leading-relaxed text-dim">
                {canControl
                  ? t("Incoming calls show a Mac alert with the caller's name and number — answer or decline it right there, and manage the call without picking up the phone.")
                  : t("Incoming calls show a Mac alert with the caller's name and number, and you can dial any contact from the Contacts tab.")}
              </p>
            </div>
          </div>
        </div>

        <div className="card p-4">
          <p className="label">{t("How it works")}</p>
          <ul className="mt-2 space-y-1.5 text-[12px] leading-relaxed text-dim">
            {(canControl
              ? [
                  "Incoming calls raise a Mac alert with name and number",
                  "Answer or decline from the Mac, then mute and switch to speaker mid-call",
                  "Dial a contact from the Contacts tab to ring your phone",
                  "Keypad tones still need an ADB connection — Android only lets the default dialer send them",
                ]
              : [
                  "Incoming calls raise a Mac alert with name and number",
                  "Dial a contact from the Contacts tab to ring your phone",
                  "Answering, hanging up, mute and speaker need the Phone permissions (Calls) granted to DroidDock on your phone",
                  "Keypad tones need an ADB connection",
                ]
            ).map((t) => (
              <li key={t} className="flex items-start gap-2">
                <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-faint" />
                {t}
              </li>
            ))}
          </ul>
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
export default memo(CallsView);
