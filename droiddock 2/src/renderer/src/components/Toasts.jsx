const ICONS = { ok: '✓', bad: '✕', info: 'i' }

const STYLES = {
  ok:   'border-ok/25 bg-ok/8 text-ok',
  bad:  'border-bad/30 bg-bad/8 text-bad',
  info: 'border-amber/25 bg-amber/8 text-amber',
}

export default function Toasts({ items }) {
  return (
    <div className="pointer-events-none fixed bottom-5 right-5 z-50 flex w-72 flex-col-reverse gap-2">
      {items.map((t, i) => (
        <div
          key={t.id}
          className={`rise rounded-xl border px-3.5 py-2.5 float-md ${STYLES[t.kind] || STYLES.info}`}
          style={{
            animationDelay: `${i * 30}ms`,
            backdropFilter: 'blur(20px) saturate(160%)',
            WebkitBackdropFilter: 'blur(20px) saturate(160%)',
          }}
        >
          <div className="flex items-start gap-2.5">
            <span
              className={`mt-px flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[9px] font-bold ${
                t.kind === 'ok'  ? 'bg-ok/20'  :
                t.kind === 'bad' ? 'bg-bad/20' :
                'bg-amber/20'
              }`}
            >
              {ICONS[t.kind] || ICONS.info}
            </span>
            <p className="text-[12px] leading-snug">{t.text}</p>
          </div>
        </div>
      ))}
    </div>
  )
}
