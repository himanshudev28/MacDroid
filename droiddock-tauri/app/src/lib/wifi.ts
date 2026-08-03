import { listen } from "@tauri-apps/api/event";

export type WifiStatus = {
  connected: boolean;
  phoneName: string | null;
};

export function onWifiStatus(cb: (status: WifiStatus) => void): () => void {
  const unlisten = listen<WifiStatus>("wifi-status", (event) => cb(event.payload));
  return () => {
    unlisten.then((f) => f());
  };
}
