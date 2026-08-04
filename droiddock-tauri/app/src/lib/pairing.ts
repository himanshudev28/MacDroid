import { invoke, isTauri } from "@tauri-apps/api/core";

export type PairingInfo = {
  host: string;
  port: number;
  token: string;
  ips: string[];
};

export function getPairingInfo(): Promise<PairingInfo> {
  // Deliberately NOT mocked outside Tauri, unlike the read-only commands in
  // `bridge.ts`. `vite dev` also serves this bundle at localhost:1420 in a
  // plain browser, and a placeholder token here renders a QR that *looks*
  // real — scanning it stores a token the Mac will never accept, and the phone
  // then fails every handshake with no visible reason. Both callers already
  // handle a rejection by not drawing a QR at all, which is the honest result.
  if (!isTauri()) {
    return Promise.reject(new Error("Pairing is only available inside the DroidDock app"));
  }
  return invoke("get_pairing_info");
}

/** Exact same shape as wifi.js's `pairingPayload()` — same param order and encoding. */
export function pairingUrl(info: PairingInfo): string {
  const ips = info.ips.join(",");
  return `droiddock://pair?v=1&name=${encodeURIComponent(info.host)}&ips=${ips}&port=${info.port}&token=${info.token}`;
}
