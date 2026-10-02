import { CircleAlert, Info, Sparkles, TriangleAlert, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

import { toneClasses, type ToneOrAccent } from '@/components/tone';
import { cn } from '@/lib/utils';

interface CalloutProps {
  tone: ToneOrAccent;
  /** Replaces the default icon of the tone. */
  icon?: ReactNode;
  title?: ReactNode;
  children: ReactNode;
  action?: ReactNode;
}

const DEFAULT_ICON: Record<ToneOrAccent, LucideIcon> = {
  neutral: Info,
  info: Info,
  success: Info,
  teal: Info,
  warning: TriangleAlert,
  danger: CircleAlert,
  progress: Info,
  primary: Sparkles,
  bunching: Info,
};

/** Tinted block with an icon: warnings above content, AI analysis, paused-consumer notices (DOC-35 §5.9). */
export function Callout({ tone, icon, title, children, action }: CalloutProps) {
  const Icon = DEFAULT_ICON[tone];
  return (
    <div className={cn('flex items-start gap-3 rounded-lg border p-3 text-sm', toneClasses(tone).surface)}>
      <span className="mt-0.5 shrink-0" aria-hidden="true">
        {icon ?? <Icon className="size-4" strokeWidth={1.75} />}
      </span>
      <div className="min-w-0 flex-1">
        {title ? <p className="font-semibold">{title}</p> : null}
        <div className={cn(title && 'mt-0.5')}>{children}</div>
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  );
}
