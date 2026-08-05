import { useEffect, useState } from "react";
import ConnectionPill from "./ConnectionPill";
import PhoneClock from "./PhoneClock";
import MiniPlayer from "./MiniPlayer";
import StatusStrip from "./StatusStrip";
import QuickActions, { type QuickAction } from "./QuickActions";
import RecentApps from "./RecentApps";
import Icon from "../Icon";
import LinkPulse from "../LinkPulse";
import type { WifiStatus } from "../../lib/wifi";
import type { AdbDevice, AppDeviceInfo, DroidConfig, LinkQuality, MediaState } from "../../lib/bridge";

/// The phone, as a persistent object you glance at — the app's centre of
/// gravity, always on screen regardless of which view is open.
///
/// `artwork` is the backdrop: the current album art while something is playing,
/// otherwise the phone's own wallpaper (both Tier B). It falls back to a
/// generated aurora keyed off the live macOS accent whenever neither is
/// available — an older phone build, or one that never granted storage access.
export default function PhoneCard({
  status,
  info,
  media,
  config,
  adb,
  quality,
  ip,
  port,
  actions,
  wallpaper = null,
  albumArt = null,
  onRecentError,
  onPair,
}: {
  status: WifiStatus;
  info: AppDeviceInfo | null;
  media: MediaState | null;
  config: DroidConfig | null;
  adb: AdbDevice | null;
  quality: LinkQuality | null;
  ip: string | null;
  port: number | null;
  actions: QuickAction[];
  /// The phone's own wallpaper — the card's resting backdrop.
  wallpaper?: string | null;
  /// The current track's cover. Takes over *only while the player is showing*,
  /// so the chevron means one coherent thing: show what's playing, or don't.
  /// Passed separately from `wallpaper` rather than pre-resolved by the caller,
  /// because the state that decides between them (`playerOpen`) lives here.
  albumArt?: string | null;
  onRecentError: (msg: string) => void;
  onPair: () => void;
}) {
  const [playerOpen, setPlayerOpen] = useState(true);
  // An active media session is enough. Requiring a title *or* artist as well
  // hid the player for sessions that report neither — a browser tab, or an app
  // that publishes artwork and position but no metadata — even though the
  // transport controls and seek bar work perfectly for those. `MiniPlayer`
  // already falls back to "Unknown track" and the app name.
  const hasTrack = !!media?.active;
  const showPlayer = hasTrack && playerOpen;

  // Auto-reopen when a new track starts, so pausing the panel once doesn't
  // hide it forever — matches AirSync's auto-collapse/expand behaviour.
  useEffect(() => {
    if (hasTrack) setPlayerOpen(true);
  }, [media?.title, media?.artist, hasTrack]);

  return (
    // Takes the full height it's given, capped at 720px.
    //
    // The cap and the panel width are set together, and the pair is the whole
    // point: 332 × 720 is 1:2.17, which is a ~6.5" handset. Capping height
    // alone (or widening alone) gives a frame that is either letterboxed or
    // thinner than any real phone — the first version of this was 1:2.5 and
    // still read as stretched even though it filled the column.
    <div className="phone-card relative flex h-full max-h-[720px] w-full flex-col overflow-hidden rounded-[28px] border border-white/10">
      {/* Backdrop, three layers deep: generated aurora at the bottom (the
          fallback when the phone gave us neither image), the wallpaper over it,
          and the album art on top.

          Both images stay mounted and cross-fade on opacity rather than one
          being swapped out. Swapping `src` flashes — the browser tears down the
          old texture before the new one decodes — and a full-bleed flash on a
          toggle reads as a glitch, not a transition. 450ms is deliberately
          unhurried: this is a whole-backdrop change, and anything quicker looks
          like a rendering fault rather than an intentional one. */}
      <div className="absolute inset-0 -z-10">
        <div className="phone-aurora absolute inset-0" />
        {wallpaper && (
          <img src={wallpaper} alt="" className="absolute inset-0 h-full w-full object-cover" />
        )}
        {albumArt && (
          <img
            src={albumArt}
            alt=""
            className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-450 ease-out motion-reduce:transition-none ${
              showPlayer ? "opacity-100" : "opacity-0"
            }`}
          />
        )}
        {/* Scrim. Two layers, because one gradient can't do both jobs:
            · the vertical pass darkens the top and bottom bands, where the
              connection pill, quick actions and status strip live;
            · the radial pass sits under the clock specifically.
            The old single gradient thinned to 10% black across the middle,
            which is exactly where the 68px clock sits — over busy album art
            (text-heavy covers especially) it became unreadable. Album art is
            arbitrary imagery; the only safe assumption is that it competes. */}
        <div className="absolute inset-0 bg-linear-to-b from-black/55 via-black/25 to-black/70" />
        <div className="phone-clock-scrim absolute inset-0" />
      </div>

      {status.connected ? (
        <div className="flex min-h-0 flex-1 flex-col px-3 py-3.5">
          <div className="flex shrink-0 justify-center">
            <ConnectionPill status={status} adb={adb} config={config} quality={quality} ip={ip} port={port} />
          </div>

          <p className="mt-2 shrink-0 truncate text-center text-[12.5px] font-medium text-white/90">
            {status.phoneName ?? "Phone"}
          </p>

          <div className="flex min-h-0 flex-1 items-center justify-center">
            <PhoneClock compact={showPlayer} />
          </div>

          <div className="shrink-0">
            <RecentApps onError={onRecentError} />

            {/* Collapses to nothing without anyone having to know its height.
                `grid-rows-[1fr] → [0fr]` on a wrapper whose child is
                `overflow-hidden` is the one way to animate to *auto* height in
                CSS; a fixed max-height would either clip a two-line title or
                leave dead space under a one-line one.

                The margin animates with it — left as a static `mt-2.5` the
                collapsed player would leave a 10px ghost gap above the quick
                actions, which is exactly the kind of residue that makes a
                collapse look broken rather than finished. */}
            <div
              className={`grid transition-all duration-300 ease-out motion-reduce:transition-none ${
                showPlayer ? "mt-2.5 grid-rows-[1fr] opacity-100" : "mt-0 grid-rows-[0fr] opacity-0"
              }`}
              aria-hidden={!showPlayer}
            >
              <div className="min-h-0 overflow-hidden">{media && <MiniPlayer media={media} />}</div>
            </div>

            <div className="mt-2.5">
              <QuickActions actions={actions} />
            </div>
            <StatusStrip
              className="mt-2.5"
              info={info}
              media={media}
              status={status}
              playerOpen={playerOpen}
              onTogglePlayer={() => setPlayerOpen((o) => !o)}
            />
          </div>
        </div>
      ) : (
        <Unlinked onPair={onPair} />
      )}
    </div>
  );
}

function Unlinked({ onPair }: { onPair: () => void }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 px-5 text-center">
      <LinkPulse linked={false} width={104} />
      <div>
        <p className="font-display text-[14px] font-semibold text-white/90">No phone linked</p>
        <p className="mx-auto mt-1 max-w-45 text-[11.5px] leading-relaxed text-white/55">
          Scan the pairing code from the DroidDock app on your Android.
        </p>
      </div>
      <button
        onClick={onPair}
        className="on-glass-active flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12px] font-medium text-white transition-colors"
      >
        <Icon name="qrcode" size={13} strokeWidth={1.9} />
        Pair a phone
      </button>
    </div>
  );
}
