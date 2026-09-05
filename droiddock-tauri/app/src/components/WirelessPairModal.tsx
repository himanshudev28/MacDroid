import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import Icon from "./Icon";
import { adbPairWireless, adbQrPairStart, adbQrPairCancel, onQrPairStatus, type QrPairStatus } from "../lib/bridge";
import { t } from "../lib/i18n";

const HOST_RE = /^(\d{1,3}\.){3}\d{1,3}:\d{2,5}$/;
const CODE_RE = /^\d{6}$/;
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

// Alphanumeric only — the WIFI: QR format uses ; : , as delimiters.
function randStr(n: number): string {
  const bytes = new Uint8Array(n);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => ALPHABET[b % ALPHABET.length]).join("");
}

/// Phase 13 — port of WirelessPairModal.jsx: Android-11+ Wireless Debugging
/// pairing, either by scanning a QR code or entering the pairing code shown
/// on the phone.
export default function WirelessPairModal({
  onClose,
  onPaired,
  onToast,
}: {
  onClose: () => void;
  onPaired: () => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [tab, setTab] = useState<"qr" | "code">("qr");

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-ink/60 backdrop-blur-sm" onClick={onClose}>
      <div className="rise card-raised float-lg w-full max-w-md p-6" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h2 className="font-display text-[17px] font-semibold text-fg">{t("Pair wirelessly")}</h2>
            <p className="mt-1 text-[12px] text-dim">{t("Cable-free ADB over Wi-Fi — Android 11 or newer.")}</p>
          </div>
          <button onClick={onClose} title={t("Close")} className="btn-icon shrink-0">
            <Icon name="x" size={15} />
          </button>
        </div>

        <div className="mt-5 flex gap-1 border-b border-line">
          <Tab id="qr" tab={tab} setTab={setTab} icon="qrcode" label={t("Pair via QR")} />
          <Tab id="code" tab={tab} setTab={setTab} icon="key" label={t("Enter code")} />
        </div>

        {tab === "qr" ? <QrTab onPaired={onPaired} onClose={onClose} /> : <CodeTab onPaired={onPaired} onClose={onClose} onToast={onToast} />}
      </div>
    </div>
  );
}

function Tab({
  id,
  tab,
  setTab,
  icon,
  label,
}: {
  id: "qr" | "code";
  tab: "qr" | "code";
  setTab: (t: "qr" | "code") => void;
  icon: string;
  label: string;
}) {
  const active = tab === id;
  return (
    <button
      onClick={() => setTab(id)}
      className={`flex items-center gap-1.5 border-b-2 px-3 py-2 text-[12.5px] font-medium transition-colors ${
        active ? "border-(--color-accent) text-fg" : "border-transparent text-dim hover:text-fg"
      }`}
    >
      <Icon name={icon} size={13} />
      {label}
    </button>
  );
}

function QrTab({ onPaired, onClose }: { onPaired: () => void; onClose: () => void }) {
  const [qr, setQr] = useState<string | null>(null);
  const [status, setStatus] = useState<QrPairStatus>({ state: "waiting", text: "Generating code…" });
  const offRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    const serviceName = "droiddock-" + randStr(6);
    const password = randStr(10);
    const payload = `WIFI:T:ADB;S:${serviceName};P:${password};;`;

    let alive = true;
    QRCode.toDataURL(payload, { margin: 1, width: 220, color: { dark: "#1d1c21", light: "#faf9f7" } }).then(
      (url) => alive && setQr(url)
    );

    setStatus({ state: "waiting", text: t("Waiting for scan…") });
    offRef.current = onQrPairStatus((s) => {
      setStatus(s);
      if (s.state === "connected") {
        onPaired();
        setTimeout(() => alive && onClose(), 1100);
      }
    });
    adbQrPairStart(serviceName, password);

    return () => {
      alive = false;
      offRef.current?.();
      adbQrPairCancel();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="pt-5">
      <ol className="space-y-1.5 text-[12px] leading-relaxed text-dim">
        <li>
          1. On the phone, open <b className="font-medium text-fg">{t("Settings → Developer options → Wireless debugging")}</b>
        </li>
        <li>
          2. Tap <b className="font-medium text-fg">{t("Pair device with QR code")}</b>{t("and scan this code")}
        </li>
        <li>
          3. Stay on the Wireless debugging screen until it connects
        </li>
      </ol>

      <div className="mt-4 flex justify-center">
        {qr ? (
          <div className="rounded-2xl bg-[#faf9f7] p-3 shadow-[inset_0_0_0_1px_rgba(0,0,0,0.06)]">
            <img src={qr} alt="ADB pairing QR" className="h-49 w-49 rounded-md" />
          </div>
        ) : (
          <div className="h-55 w-55 animate-pulse rounded-2xl bg-panel3" />
        )}
      </div>

      <StatusLine status={status} />
    </div>
  );
}

function StatusLine({ status }: { status: QrPairStatus }) {
  const map = {
    waiting: { icon: "reload", cls: "text-(--color-accent)", spin: true },
    connecting: { icon: "reload", cls: "text-(--color-accent)", spin: true },
    connected: { icon: "checkCircle", cls: "text-ok", spin: false },
    error: { icon: "alertTriangle", cls: "text-bad", spin: false },
  } as const;
  const { icon, cls, spin } = map[status.state] || map.waiting;
  return (
    <div className="mt-4 flex items-center justify-center gap-2 border-t border-line pt-3">
      <Icon name={icon} size={13} className={`${cls} ${spin ? "spinner" : ""}`} />
      <span className={`text-[12px] font-medium ${cls}`}>
        {status.text}
        {status.addr && <span className="data ms-1 text-[11px]">{status.addr}</span>}
      </span>
    </div>
  );
}

function CodeTab({
  onPaired,
  onClose,
  onToast,
}: {
  onPaired: () => void;
  onClose: () => void;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [hostPort, setHostPort] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);

  const hostOk = HOST_RE.test(hostPort.trim());
  const codeOk = CODE_RE.test(code.trim());
  const valid = hostOk && codeOk;

  const submit = async () => {
    if (!valid || busy) return;
    setBusy(true);
    try {
      const res = await adbPairWireless(hostPort.trim(), code.trim());
      setBusy(false);
      onPaired();
      onToast("ok", res.addr ? `Paired & connected over Wi-Fi — ${res.addr}. Cable not needed.` : t("Paired. Reconnecting over Wi-Fi…"));
      onClose();
    } catch (e) {
      setBusy(false);
      onToast("bad", String(e));
    }
  };

  return (
    <div className="pt-5">
      <ol className="space-y-1.5 text-[12px] leading-relaxed text-dim">
        <li>
          1. On the phone, open <b className="font-medium text-fg">{t("Wireless debugging → Pair device with pairing code")}</b>
        </li>
        <li>2. Type the address and 6-digit code it shows</li>
      </ol>

      <label className="label mt-4 block">{t("Pairing address")}</label>
      <input
        value={hostPort}
        onChange={(e) => setHostPort(e.target.value)}
        placeholder="192.168.0.100:41234"
        autoFocus
        className="field data mt-1.5 w-full"
        style={hostPort && !hostOk ? { borderColor: "var(--color-bad)" } : undefined}
      />

      <label className="label mt-4 block">{t("Pairing code")}</label>
      <input
        value={code}
        onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
        onKeyDown={(e) => e.key === "Enter" && submit()}
        placeholder="123456"
        inputMode="numeric"
        className="field data mt-1.5 w-full"
        style={code && !codeOk ? { borderColor: "var(--color-bad)" } : undefined}
      />

      <p className="mt-3 flex items-start gap-1.5 text-[11px] leading-relaxed text-dim">
        <Icon name="alert" size={12} className="mt-0.5 shrink-0 text-warn" />
        <span>
          Use the port from the <b className="font-medium text-fg">{t("pairing dialog")}</b> — it's different from the main
          port on the Wireless debugging screen.
        </span>
      </p>

      <button onClick={submit} disabled={!valid || busy} className="btn btn-primary mt-5 w-full">
        {busy ? "Pairing…" : t("Pair and connect")}
      </button>
    </div>
  );
}
