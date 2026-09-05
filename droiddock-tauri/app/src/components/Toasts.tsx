import Icon from "./Icon";

export type Toast = { id: number; kind: "ok" | "bad" | "info"; text: string };

const ICONS: Record<string, string> = { ok: "checkCircle", bad: "alert", info: "info" };
const COLORS: Record<string, string> = {
  ok: "text-ok",
  bad: "text-bad",
  info: "text-(--color-accent)",
};

export default function Toasts({ items }: { items: Toast[] }) {
  return (
    <div className="pointer-events-none fixed bottom-5 end-5 z-50 flex w-80 flex-col-reverse gap-2">
      {items.map((t, i) => (
        <div
          key={t.id}
          className="rise card-raised flex items-start gap-2.5 px-3.5 py-2.5"
          style={{ animationDelay: `${i * 30}ms` }}
        >
          <Icon
            name={ICONS[t.kind] ?? ICONS.info}
            size={15}
            className={`mt-px shrink-0 ${COLORS[t.kind] ?? COLORS.info}`}
          />
          <p className="min-w-0 wrap-break-word text-[12.5px] leading-snug text-fg">{t.text}</p>
        </div>
      ))}
    </div>
  );
}
