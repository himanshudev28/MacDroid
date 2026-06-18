export default function ClipboardView({ linked, onToast }) {
  return (
    <div className="flex h-full items-center justify-center p-8">
      <div className="w-full max-w-sm space-y-4">

        <div className="rounded-2xl border border-line bg-panel2 p-6 luminous-sm text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-amber/20 bg-amber/8">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-amber" aria-hidden="true">
              <path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
            </svg>
          </div>
          <p className="text-[14px] font-semibold text-fg/90">Clipboard Sync</p>
          <p className="mt-2 text-[12px] leading-relaxed text-dim/70">
            Text and files copied on your phone arrive instantly on your Mac. Enable clipboard sync in Settings to activate this feature.
          </p>
        </div>

        {linked ? (
          <div className="rounded-2xl border border-ok/20 bg-ok/5 px-4 py-3">
            <div className="flex items-center gap-2.5">
              <span className="h-2 w-2 shrink-0 rounded-full bg-ok led" />
              <p className="text-[12px] text-fg/80">Phone app connected — clipboard sync is active</p>
            </div>
          </div>
        ) : (
          <div className="rounded-2xl border border-line bg-panel2 px-4 py-3">
            <div className="flex items-center gap-2.5">
              <span className="h-2 w-2 shrink-0 rounded-full bg-dim/25" />
              <p className="text-[12px] text-dim/60">Link the phone app to enable clipboard sync</p>
            </div>
          </div>
        )}

      </div>
    </div>
  )
}
