import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { getPairingInfo, pairingUrl, type PairingInfo } from "../../lib/pairing";
import { onWifiStatus, wifiStatus, type WifiStatus } from "../../lib/wifi";
import { onAppDeviceInfo, type AppDeviceInfo } from "../../lib/bridge";
import type { ViewId } from "../../lib/nav";
import LinkPulse from "../LinkPulse";
import Icon from "../Icon";
import { t } from "../../lib/i18n";

/// Home. Two states, one motif: the Link. Unpaired, the broken Link frames
/// the pairing card; linked, the flowing Link crowns the phone's live card.
export default function DashboardView({ onNavigate }: { onNavigate?: (v: ViewId) => void }) {
  const [info, setInfo] = useState<PairingInfo | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [status, setStatus] = useState<WifiStatus>({ connected: false, phoneName: null });
  const [device, setDevice] = useState<AppDeviceInfo | null>(null);
  const [manual, setManual] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);

  useEffect(() => {
    getPairingInfo().then(setInfo).catch(console.error);
    // Seed from the live state: this view unmounts whenever you visit another
    // page, so on the way back it would otherwise sit at `connected: false` and
    // show the pairing QR for an already-linked phone until the link changed.
    wifiStatus().then(setStatus).catch(() => {});
    const offInfo = onAppDeviceInfo(setDevice);
    const offStatus = onWifiStatus((s) => {
      setStatus(s);
      if (!s.connected) setDevice(null);
    });
    return () => {
      offInfo();
      offStatus();
    };
  }, []);

  useEffect(() => {
    if (!info) return;
    QRCode.toDataURL(pairingUrl(info), {
      margin: 1,
      width: 220,
      color: { dark: "#1d1c21", light: "#faf9f7" },
    }).then(setQr);
  }, [info]);

  const copy = (key: string, value: string) => {
    navigator.clipboard?.writeText(value);
    setCopied(key);
    setTimeout(() => setCopied((c) => (c === key ? null : c)), 1200);
  };

  const address = info ? `${info.ips[0] ?? "—"}:${info.port}` : "—";

  return (
    <div className="flex h-full w-full items-center justify-center overflow-y-auto px-6 py-8">
      {status.connected ? (
        <LinkedCard status={status} device={device} onNavigate={onNavigate} />
      ) : (
        <div className="rise w-full max-w-100">
          <div className="card-raised p-7">
            <div className="flex flex-col items-center text-center">
              <LinkPulse linked={false} width={120} />
              <h1 className="mt-4 font-display text-[19px] font-semibold text-fg">{t("Dock your phone")}
              </h1>
              <p className="mt-1.5 max-w-68 text-[12.5px] leading-relaxed text-dim">
                Open DroidDock on your Android, tap <span className="font-medium text-fg/80">{t("Pair with Mac")}</span>,
                and scan this code. Both devices need the same Wi-Fi network.
              </p>
            </div>

            {!manual ? (
              <>
                <div className="mt-6 flex justify-center">
                  {qr ? (
                    <div className="rounded-2xl bg-[#faf9f7] p-3 shadow-[inset_0_0_0_1px_rgba(0,0,0,0.06)]">
                      <img src={qr} alt="Pairing QR code" className="h-49 w-49 rounded-md" />
                    </div>
                  ) : (
                    <div className="h-55 w-55 animate-pulse rounded-2xl bg-panel3" />
                  )}
                </div>
                <button onClick={() => setManual(true)} className="btn btn-ghost mt-5 w-full">{t("Pair with IP address instead")}
                </button>
              </>
            ) : (
              <div className="mt-6">
                <Field label={t("Address")} value={address} copied={copied === "addr"} onCopy={() => copy("addr", address)} />
                <Field
                  label={t("Token")}
                  value={info?.token ?? "—"}
                  copied={copied === "token"}
                  onCopy={() => copy("token", info?.token ?? "")}
                />
                {info && info.ips.length > 1 && (
                  <p className="data mt-2 text-[11px] text-faint">
                    Other IPs: {info.ips.slice(1).join("  ·  ")}
                  </p>
                )}
                <button onClick={() => setManual(false)} className="btn btn-ghost mt-4 w-full">{t("Back to QR code")}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function LinkedCard({
  status,
  device,
  onNavigate,
}: {
  status: WifiStatus;
  device: AppDeviceInfo | null;
  onNavigate?: (v: ViewId) => void;
}) {
  const battery = typeof device?.battery === "number" ? Math.round(device.battery) : null;

  return (
    <div className="rise w-full max-w-110">
      <div className="card-raised p-7">
        <div className="flex flex-col items-center text-center">
          <LinkPulse linked width={120} />
          <h1 className="mt-4 font-display text-[20px] font-semibold text-fg">
            {status.phoneName ?? "Phone"}
          </h1>
          <p className="mt-1 flex items-center gap-1.5 text-[12.5px] font-medium text-(--color-link)">
            <span className="led h-1.5 w-1.5 rounded-full bg-(--color-link)" />{t("Linked over Wi-Fi")}
          </p>
        </div>

        {(battery !== null || device?.android) && (
          <div className="mt-5 grid grid-cols-2 gap-2">
            {battery !== null && (
              <div className="card px-3.5 py-2.5">
                <p className="label">{t("Battery")}</p>
                <p className="mt-0.5 font-display text-[17px] font-semibold text-fg">
                  {battery}%
                  {device?.charging && <span className="ms-1.5 text-[11px] font-medium text-(--color-link)">charging</span>}
                </p>
              </div>
            )}
            {device?.android && (
              <div className="card px-3.5 py-2.5">
                <p className="label">{t("Android")}</p>
                <p className="mt-0.5 font-display text-[17px] font-semibold text-fg">{device.android}</p>
              </div>
            )}
          </div>
        )}

        <div className="mt-5 grid grid-cols-3 gap-2">
          <QuickAction icon="folder" label={t("Files")} onClick={() => onNavigate?.("files")} />
          <QuickAction icon="message" label={t("Messages")} onClick={() => onNavigate?.("messages")} />
          <QuickAction icon="monitor" label={t("Mirror")} onClick={() => onNavigate?.("mirror")} />
        </div>
      </div>
    </div>
  );
}

function QuickAction({ icon, label, onClick }: { icon: string; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="card group flex flex-col items-center gap-1.5 px-3 py-3.5 transition-colors hover:border-[color-mix(in_srgb,var(--color-accent)_35%,transparent)] hover:bg-panel2"
    >
      <Icon name={icon} size={17} className="text-dim transition-colors group-hover:text-(--color-accent)" />
      <span className="text-[11.5px] font-medium text-fg/85">{label}</span>
    </button>
  );
}

function Field({
  label,
  value,
  copied,
  onCopy,
}: {
  label: string;
  value: string;
  copied: boolean;
  onCopy: () => void;
}) {
  return (
    <div className="card mt-2 flex items-center justify-between gap-3 px-3.5 py-2.5">
      <div className="min-w-0">
        <p className="label">{label}</p>
        <p className="data mt-0.5 truncate text-[12.5px] text-fg">{value || "—"}</p>
      </div>
      <button onClick={onCopy} title={`Copy ${label.toLowerCase()}`} className="btn-icon shrink-0">
        {copied ? (
          <Icon name="check" size={14} className="text-ok" />
        ) : (
          <Icon name="copy" size={14} />
        )}
      </button>
    </div>
  );
}
