import type { ReactNode } from "react";
import { AlertTriangle, Check, ChevronDown, CheckCheck, HandHelping, MessageSquare, Route, Siren, SquareDashed, UserSearch, HelpCircle } from "lucide-react";
import type { MessageType, Priority, TaskState } from "../api/types";
import { useT } from "../state/hooks";
import { useUi } from "../state/ui";
import { prioName } from "../lib/format";

export const TYPE_ICON: Record<MessageType, typeof Siren> = {
  found: UserSearch, track: Route, sector: SquareDashed, request: HandHelping, hazard: AlertTriangle,
  mayday: Siren, ack: CheckCheck, text: MessageSquare, unknown: HelpCircle,
};

export function TypeTile({ type, size = 22 }: { type: MessageType; size?: number }) {
  const Icon = TYPE_ICON[type];
  return (
    <span className={`type-tile type-${type}`} aria-hidden="true">
      <Icon size={size} strokeWidth={2} />
    </span>
  );
}

export function PriorityChip({ priority }: { priority: Priority }) {
  const { lang } = useUi();
  if (priority === "routine" || priority === "normal") return null;
  return <span className={`chip prio-${priority}`}>{prioName(lang, priority)}</span>;
}

/** Marks a number as read from the radio or calculated. Never colour alone: the word is always there. */
export function SourceTag({ source, always = false }: { source: "measured" | "estimated"; always?: boolean }) {
  const t = useT();
  // Measured is the default, so only the guesses carry a badge (the tooltip on the tile name says the rest).
  if (source === "measured" && !always) return null;
  return (
    <span className={`source source-${source}`} title={source === "measured" ? t("measuredTip") : t("estimatedTip")}>
      {source === "measured" ? <Check size={11} strokeWidth={3} aria-hidden="true" /> : <span className="tilde" aria-hidden="true">~</span>}
      {t(source)}
    </span>
  );
}

export function StateChip({ state, children }: { state: TaskState; children: ReactNode }) {
  return <span className={`chip state-${state}`}>{children}</span>;
}

export function Panel({ id, title, count, actions, children, className = "", collapse }: {
  id: string; title: string; count?: number | string; actions?: ReactNode; children?: ReactNode; className?: string;
  /** Makes the header a button that folds the body away. */
  collapse?: { collapsed: boolean; onToggle: () => void };
}) {
  return (
    <section className={`panel ${className}`} aria-labelledby={`${id}-h`}>
      <header className="panel-head">
        {collapse ? (
          <button className="panel-toggle" aria-expanded={!collapse.collapsed} onClick={collapse.onToggle}>
            <h2 id={`${id}-h`}>{title}</h2>
            {count !== undefined && <span className="count num">{count}</span>}
            <ChevronDown className="chev" size={20} aria-hidden="true" />
          </button>
        ) : (
          <>
            <h2 id={`${id}-h`}>{title}</h2>
            {count !== undefined && <span className="count num">{count}</span>}
          </>
        )}
        {actions && <div className="panel-head-actions">{actions}</div>}
      </header>
      {children}
    </section>
  );
}
