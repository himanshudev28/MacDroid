import { invoke } from "@tauri-apps/api/core";

export type DroidDockConfig = {
  token: string;
  port: number;
  notifications: boolean;
  native_notifs: boolean;
};

export function getConfig(): Promise<DroidDockConfig> {
  return invoke("get_config");
}

export type SystemAppearance = {
  accent_color: string;
  reduce_transparency: boolean;
};

export function getAppearance(): Promise<SystemAppearance> {
  return invoke("get_appearance");
}
