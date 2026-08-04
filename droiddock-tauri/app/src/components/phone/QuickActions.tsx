import Icon from "../Icon";

export type QuickAction = {
  id: string;
  icon: string;
  label: string;
  /// Shown in the tooltip after the label, e.g. "⌘F".
  shortcut?: string;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
};

/// The row of round glass buttons on the phone card — the four things you do
/// often enough that hunting for a tab is friction. Mirrors AirSync's
/// `ScreenView` action row (send / browse / mute / clipboard).
export default function QuickActions({ actions }: { actions: QuickAction[] }) {
  return (
    <div className="flex items-center justify-center gap-2">
      {actions.map((a) => (
        <button
          key={a.id}
          onClick={a.onClick}
          disabled={a.disabled}
          title={a.shortcut ? `${a.label}  ${a.shortcut}` : a.label}
          aria-label={a.label}
          className={`flex h-8.5 w-8.5 items-center justify-center rounded-full transition-[background-color,color,transform] active:scale-95 disabled:opacity-35 disabled:pointer-events-none ${
            a.active ? "on-glass-active text-white" : "on-glass text-white/80 hover:text-white"
          }`}
        >
          <Icon name={a.icon} size={15} strokeWidth={1.9} />
        </button>
      ))}
    </div>
  );
}
