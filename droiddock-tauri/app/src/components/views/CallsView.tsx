import EmptyState from "../EmptyState";
import Icon from "../Icon";

/// Phase 9 — CallsView is purely informational, exactly like CallsView.jsx.
/// There is no dialer or call log here: over Wi-Fi the phone only supports
/// outbound `action-call` (dial, triggered from Contacts) and inbound `call`
/// ringing alerts. Rich in-call control (hang up / mute / speaker / DTMF) is
/// ADB-only (Phase 13) — when a live ADB device is connected, dialing from
/// Contacts now upgrades the call overlay automatically; this tab itself
/// stays the same informational summary as the reference.
export default function CallsView({ linked }: { linked: boolean }) {
  if (!linked) {
    return (
      <EmptyState
        icon="phone"
        title="Connect phone to see calls"
        body="Pair the DroidDock phone app over Wi-Fi to see incoming calls with caller ID here."
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
                <p className="font-display text-[15px] font-semibold text-fg">Calls ring through</p>
                <span className="led h-1.5 w-1.5 shrink-0 rounded-full bg-(--color-link)" />
              </div>
              <p className="mt-1 text-[12px] leading-relaxed text-dim">
                Incoming calls show a Mac alert with the caller's name and number, and you can dial
                any contact from the Contacts tab.
              </p>
            </div>
          </div>
        </div>

        <div className="card p-4">
          <p className="label">How it works</p>
          <ul className="mt-2 space-y-1.5 text-[12px] leading-relaxed text-dim">
            {[
              "Incoming calls raise a Mac alert with name and number",
              "Dial a contact from the Contacts tab to ring your phone",
              "Mute, speaker and hang-up from the Mac need an ADB connection",
            ].map((t) => (
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
