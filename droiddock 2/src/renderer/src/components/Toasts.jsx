export default function Toasts({ items }) {
  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2">
      {items.map((t) => (
        <div
          key={t.id}
          className={`rise border-l-2 bg-panel px-3.5 py-2.5 text-[12px] shadow-lg shadow-black/40 ${
            t.kind === 'ok'
              ? 'border-ok text-fg'
              : t.kind === 'bad'
                ? 'border-bad text-fg'
                : 'border-amber text-fg'
          }`}
        >
          <span className="mr-2 font-mono text-[9px] tracking-[0.2em] text-dim">
            {t.kind === 'ok' ? 'DONE' : t.kind === 'bad' ? 'ERROR' : 'INFO'}
          </span>
          {t.text}
        </div>
      ))}
    </div>
  )
}
