import { invoke } from "@tauri-apps/api/core";

export type PairingInfo = {
  host: string;
  port: number;
  token: string;
  ips: string[];
};

export function getPairingInfo(): Promise<PairingInfo> {
  return invoke("get_pairing_info");
}

/** Exact same shape as wifi.js's `pairingPayload()` — same param order and encoding. */
export function pairingUrl(info: PairingInfo): string {
  const ips = info.ips.join(",");
  return `droiddock://pair?v=1&name=${encodeURIComponent(info.host)}&ips=${ips}&port=${info.port}&token=${info.token}`;
}
