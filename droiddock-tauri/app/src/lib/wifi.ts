import { invoke, on } from "./bridge";

export type WifiStatus = {
  connected: boolean;
  phoneName: string | null;
  /// What the connected phone advertised it can do (`hello.caps`). Absent on a
  /// Mac build older than this field — hence the `?? []` at every call site.
  ///
  /// Used to hide controls the phone wouldn't understand. A button that
  /// silently does nothing is worse than one that isn't there: the user can't
  /// tell a missing feature from a broken one.
  caps?: string[];
};

/// Does the linked phone support `cap`? False when nothing is linked.
export const phoneSupports = (status: WifiStatus, cap: string): boolean =>
  status.connected && (status.caps ?? []).includes(cap);

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

