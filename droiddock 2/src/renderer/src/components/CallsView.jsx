export default function CallsView({ linked }) {
  if (!linked) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="rise max-w-xs rounded-2xl border border-line bg-panel2 p-8 luminous-sm text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-amber/20 bg-amber/8">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 text-amber" aria-hidden="true">
              <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" />
            </svg>
          </div>
          <p className="text-[13px] font-semibold text-fg/80">Connect phone to see call log</p>
          <p className="mt-2 text-[11.5px] leading-relaxed text-dim/70">
            Pair the DroidDock phone app over Wi-Fi to see incoming calls and call history here.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-full items-center justify-center p-8">
      <div className="w-full max-w-sm space-y-4">

        <div className="rounded-2xl border border-ok/20 bg-ok/5 p-5 luminous-sm">
          <div className="flex items-start gap-3.5">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-ok/20 bg-ok/10">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 text-ok" aria-hidden="true">
                <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" />
              </svg>
            </div>
            <div>
              <div className="flex items-center gap-2">
                <p className="text-[13px] font-semibold text-fg/90">Call integration active</p>
                <span className="h-1.5 w-1.5 rounded-full bg-ok led" />
              </div>
              <p className="mt-1 text-[11.5px] leading-relaxed text-dim/70">
                Incoming calls appear as Mac notifications. You can see the caller's name and number without picking up your phone.
              </p>
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-line bg-panel2 px-4 py-3.5">
          <p className="font-mono text-[9.5px] tracking-[0.08em] text-dim/50 uppercase">How it works</p>
          <ul className="mt-2 space-y-1.5 text-[11.5px] leading-relaxed text-dim/70">
            <li className="flex items-start gap-2">
              <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-amber/60" />
              Incoming calls trigger a Mac notification with name &amp; number
            </li>
            <li className="flex items-start gap-2">
              <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-amber/60" />
              Requires the phone app to be paired over Wi-Fi
            </li>
            <li className="flex items-start gap-2">
              <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-amber/60" />
              Enable notifications in Settings to see them on your Mac
            </li>
          </ul>
        </div>

      </div>
    </div>
  )
}
