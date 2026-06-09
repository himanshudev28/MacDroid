import { Smartphone, QrCode, Usb } from 'lucide-react'

export default function WaitingState({ unauthorized, onLinkApp, onAdvanced }) {
  return (
    <div className="flex h-full items-center justify-center p-8">
      <div className="w-full max-w-md border border-line bg-panel p-8">
        <div className="relative mx-auto flex h-16 w-16 items-center justify-center">
          <span className="ping-soft absolute inset-0 rounded-full border border-amber/50" />
          <span className="absolute inset-2 rounded-full border border-amber/20" />
          <Smartphone size={22} className="relative text-amber" strokeWidth={1.5} />
        </div>

        <p className="mt-6 text-center font-display text-sm font-semibold tracking-[0.25em] text-fg">
          CONNECT YOUR PHONE
        </p>

        {unauthorized && (
          <p className="mt-4 border border-amber/30 bg-amber/10 px-3 py-2 text-center font-mono text-[11px] leading-relaxed text-amber">
            ADB device detected but unauthorized — tap “Allow USB debugging” on the phone.
          </p>
        )}

        <p className="mt-5 text-center text-[13px] leading-relaxed text-dim">
          No cable or Developer Options needed — link the companion app over Wi-Fi for files,
          photos, screenshots and more.
        </p>

        <button
          onClick={onLinkApp}
          className="mt-5 flex w-full items-center justify-center gap-2 border border-amber/40 bg-amber/10 py-3 font-display text-[11px] font-semibold tracking-[0.2em] text-amber transition-colors hover:bg-amber/20"
        >
          <QrCode size={14} />
          LINK THE PHONE APP (QR — RECOMMENDED)
        </button>

        <button
          onClick={onAdvanced}
          className="mt-2 flex w-full items-center justify-center gap-2 border border-line py-2.5 font-display text-[10px] font-semibold tracking-[0.2em] text-dim transition-colors hover:border-amber/40 hover:text-amber"
        >
          <Usb size={13} />
          ADVANCED: USB / ADB FOR MAX SPEED + MIRROR
        </button>
      </div>
    </div>
  )
}
