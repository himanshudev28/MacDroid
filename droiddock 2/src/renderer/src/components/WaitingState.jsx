import { Smartphone, QrCode, Usb } from 'lucide-react'

export default function WaitingState({ unauthorized, onLinkApp, onAdvanced }) {
  return (
    <div className="flex h-full items-center justify-center p-8">
      <div
        className="rise w-full max-w-90 rounded-2xl border border-line bg-panel2 p-8 luminous-sm float-md text-center"
      >
        {/* Phone icon with radar rings */}
        <div className="relative mx-auto mb-6 flex h-16 w-16 items-center justify-center">
          <span className="ping-soft absolute inset-0 rounded-full bg-amber/10" />
          <span className="absolute inset-0 rounded-full border border-amber/15" />
          <div className="relative flex h-full w-full items-center justify-center rounded-full bg-amber/8 border border-amber/20">
            <Smartphone size={24} strokeWidth={1.5} className="text-amber" />
          </div>
        </div>

        <h2 className="text-[16px] font-semibold tracking-tight text-fg/95">
          Connect your phone
        </h2>

        <p className="mt-2 text-[12.5px] leading-relaxed text-dim">
          No cable or Developer Options needed. Link the companion app over Wi-Fi.
        </p>

        {unauthorized && (
          <div className="mt-4 rounded-lg border border-amber/25 bg-amber/8 px-3.5 py-2.5 text-left">
            <p className="text-[11.5px] leading-relaxed text-amber/90">
              ADB detected but unauthorized — tap <b>Allow USB debugging</b> on the phone.
            </p>
          </div>
        )}

        <div className="mt-6 flex flex-col gap-2">
          <button
            onClick={onLinkApp}
            className="flex items-center justify-center gap-2 rounded-xl border border-amber/35 bg-amber/10 px-4 py-2.5 text-[12px] font-semibold text-amber transition-all hover:bg-amber/18 hover:border-amber/50 active:scale-[0.98]"
          >
            <QrCode size={14} />
            Link with QR code
          </button>

          <button
            onClick={onAdvanced}
            className="flex items-center justify-center gap-2 rounded-xl border border-line px-4 py-2 text-[11.5px] font-medium text-dim/70 transition-all hover:border-line hover:bg-panel3 hover:text-fg/70 active:scale-[0.98]"
          >
            <Usb size={13} strokeWidth={1.75} />
            Advanced: USB / ADB
          </button>
        </div>
      </div>
    </div>
  )
}
