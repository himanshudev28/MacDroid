import { invoke, on } from "./bridge";

export type WifiStatus = {
  connected: boolean;
  phoneName: string | null;
};

export function onWifiStatus(cb: (status: WifiStatus) => void): () => void {
  return on<WifiStatus>("wifi-status", cb);
}

/// The link state right now.
///
/// `onWifiStatus` alone is not enough: the event fires only when the link
/// *changes*, so anything mounting later (the Dashboard on a second visit, the
/// menu-bar panel, the widget) would sit at its `connected: false` initial
/// state showing "pair your phone" while the phone is plainly connected. Every
/// subscriber pairs the listener with one call to this on mount.
export function wifiStatus(): Promise<WifiStatus> {
  return invoke<WifiStatus>("wifi_status");
}

