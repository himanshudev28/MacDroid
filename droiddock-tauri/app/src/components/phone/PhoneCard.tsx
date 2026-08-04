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
  artwork = null,
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
  artwork?: string | null;
  onRecentError: (msg: string) => void;
  onPair: () => void;
}) {
  const [playerOpen, setPlayerOpen] = useState(true);
  const hasTrack = !!media?.active && !!(media.title || media.artist);
  const showPlayer = hasTrack && playerOpen;

  // Auto-reopen when a new track starts, so pausing the panel once doesn't
  // hide it forever — matches AirSync's auto-collapse/expand behaviour.
  useEffect(() => {
    if (hasTrack) setPlayerOpen(true);
  }, [media?.title, media?.artist, hasTrack]);

  return (
    <div className="phone-card relative flex h-full w-full flex-col overflow-hidden rounded-[26px] border border-white/10">
      {/* Backdrop: artwork when we have it, generated aurora otherwise. */}
      <div className="absolute inset-0 -z-10">
        <div className="phone-aurora absolute inset-0" />
        {artwork && (
          <img
            src={artwork}
            alt=""
            className="rise absolute inset-0 h-full w-full object-cover"
          />
        )}
        {/* Scrim: keeps white text legible over any wallpaper. */}
        <div className="absolute inset-0 bg-gradient-to-b from-black/45 via-black/10 to-black/55" />
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

          <div className="shrink-0 space-y-2.5">
            <RecentApps onError={onRecentError} />
            {showPlayer && media && <MiniPlayer media={media} />}
            <QuickActions actions={actions} />
            <StatusStrip
              info={info}
              media={media}
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
