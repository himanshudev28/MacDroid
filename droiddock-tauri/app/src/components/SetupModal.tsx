import { useState } from "react";
import Icon from "./Icon";
import type { ToolsStatus } from "../lib/bridge";

const TOOLS = [
  {
    key: "adb" as const,
    label: "Android Platform Tools",
    sub: "adb — installed automatically on first launch",
    cmd: "brew install --cask android-platform-tools",
  },
  {
    key: "scrcpy" as const,
    label: "scrcpy",
    sub: "screen mirroring & phone camera",
    cmd: "brew install scrcpy",
  },
];

/// Phase 13 — port of SetupModal.jsx. Surfaced when a scrcpy-dependent action
/// (ADB Mirror / ADB Camera) is attempted without scrcpy installed. adb itself
/// never shows an install button here — it's silently auto-downloaded on
/// first launch (see `adb::ensure_adb`); only scrcpy gets a one-click
/// Homebrew installer, exactly like the reference.
export default function SetupModal({
  tools,
  reason,
  onClose,
  onInstallScrcpy,
}: {
  tools: ToolsStatus | null;
  reason?: string;
  onClose: () => void;
  onInstallScrcpy: () => Promise<{ ok: boolean; error?: string }>;
}) {
  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-ink/60 backdrop-blur-sm" onClick={onClose}>
      <div className="rise card-raised float-lg w-full max-w-md p-6" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h2 className="font-display text-[17px] font-semibold text-fg">Set up mirroring tools</h2>
            <p className="mt-1 text-[12px] leading-relaxed text-dim">
              {reason || "These optional tools power screen mirroring and the phone camera."}
            </p>
          </div>
          <button onClick={onClose} title="Close" className="btn-icon shrink-0">
            <Icon name="x" size={15} />
          </button>
        </div>

        <div className="mt-5 space-y-2.5">
          {TOOLS.map((t) => (
            <CmdRow
              key={t.key}
              tool={t}
              installed={!!tools?.[t.key]}
              brew={!!tools?.brew}
              onInstall={t.key === "scrcpy" ? onInstallScrcpy : null}
            />
          ))}
        </div>

        <p className="mt-5 border-t border-line pt-4 text-[11.5px] leading-relaxed text-dim">
          Messages, files, photos, clipboard and notifications all work over Wi-Fi without these —
          they're only needed for mirroring. No Homebrew? Get it at{" "}
          <span className="font-medium text-fg">brew.sh</span>.
        </p>
      </div>
    </div>
  );
}

function CmdRow({
  tool,
  installed,
  brew,
  onInstall,
}: {
  tool: (typeof TOOLS)[number];
  installed: boolean;
  brew: boolean;
  onInstall: (() => Promise<{ ok: boolean; error?: string }>) | null;
}) {
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(tool.cmd);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable — user can still select the text */
    }
  };

  const install = async () => {
    if (!onInstall) return;
    setBusy(true);
    setErr(null);
    const res = await onInstall();
    setBusy(false);
    if (!res?.ok) setErr(res?.error || "Install failed — try the command below in Terminal");
  };

  return (
    <div className="card p-3.5">
      <div className="flex items-center gap-2">
        <p className="text-[13px] font-semibold text-fg">{tool.label}</p>
        {installed ? (
          <span className="flex items-center gap-1 text-[11px] font-medium text-ok">
            <Icon name="check" size={11} />
            Installed
          </span>
        ) : (
          <span className="text-[11px] font-medium text-bad">Not installed</span>
        )}
      </div>
      <p className="mt-0.5 text-[11px] text-dim">{tool.sub}</p>

      {!installed && (
        <>
          {onInstall && brew && (
            <button onClick={install} disabled={busy} className="btn btn-primary mt-3 w-full">
              <Icon name={busy ? "reload" : "download"} size={13} className={busy ? "spinner" : ""} />
              {busy ? "Installing — this can take a minute…" : "Install with Homebrew"}
            </button>
          )}
          {err && <p className="mt-2 text-[11px] text-bad">{err}</p>}

          <div className="mt-2.5 flex items-center gap-1.5">
            <code className="data min-w-0 flex-1 truncate rounded-lg bg-ink px-2.5 py-1.5 text-[11px] text-fg/85">
              {tool.cmd}
            </code>
            <button onClick={copy} title="Copy command" className="btn-icon shrink-0">
              <Icon name={copied ? "check" : "copy"} size={13} className={copied ? "text-ok" : ""} />
            </button>
          </div>
        </>
      )}
    </div>
  );
}
