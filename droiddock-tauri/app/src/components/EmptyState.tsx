import Icon from "./Icon";

export default function EmptyState({
  icon,
  title,
  body,
  action,
}: {
  icon: string;
  title: string;
  body: string;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex h-full flex-1 items-center justify-center p-8">
      <div className="rise flex max-w-72 flex-col items-center text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-panel3">
          <Icon name={icon} size={20} strokeWidth={1.5} className="text-dim" />
        </div>
        <p className="mt-4 font-display text-[15px] font-semibold text-fg">{title}</p>
        <p className="mt-1.5 text-[12.5px] leading-relaxed text-dim">{body}</p>
        {action && (
          <button onClick={action.onClick} className="btn btn-secondary mt-4">
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
}
