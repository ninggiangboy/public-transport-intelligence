import { Lock } from 'lucide-react';
import type { ReactNode } from 'react';

import { ToneBadge } from '@/components/ToneBadge';
import { Switch } from '@/components/ui/switch';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { controlsCopy } from '@/i18n/controls';
import { cn } from '@/lib/utils';

const copy = controlsCopy;

/** Viewers see every block and none of the actions (§1, AC-4); DOC-37 §2.5 gives the tooltip. */
export function ReadOnlyBadge() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex rounded-sm">
          <ToneBadge tone="neutral" icon={Lock} label={copy.readOnly} />
        </span>
      </TooltipTrigger>
      <TooltipContent>{copy.readOnlyHelp}</TooltipContent>
    </Tooltip>
  );
}

interface FlagSwitchProps {
  checked: boolean;
  operator: boolean;
  label: string;
  describedBy: string;
  onChange: (checked: boolean) => void;
}

/** The switch of a flag row; disabled for a viewer, with "Requires the operator role." on hover and focus (AC-4). */
export function FlagSwitch({ checked, operator, label, describedBy, onChange }: FlagSwitchProps) {
  const control = (
    <Switch
      checked={checked}
      disabled={!operator}
      aria-label={label}
      aria-describedby={describedBy}
      onCheckedChange={onChange}
    />
  );
  if (operator) return control;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex rounded-full">
          {control}
        </span>
      </TooltipTrigger>
      <TooltipContent>{copy.requiresOperator}</TooltipContent>
    </Tooltip>
  );
}

/** One anchored block of the page (§2): its id is the anchor runbooks link to. */
export function Anchored({ id, children }: { id: string; children: ReactNode }) {
  return (
    <div id={id} data-section={id} className="scroll-mt-4">
      {children}
    </div>
  );
}

/** A row of a flag card; it flashes `danger` for 2 s after a change that failed (DOC-37 §2.7). */
export function FlagRowFrame({ flash, children }: { flash: boolean; children: ReactNode }) {
  return (
    <div
      className={cn(
        '-mx-4 flex flex-col gap-2 border-t border-border px-4 py-3 transition-colors first:border-t-0',
        flash && 'bg-tone-danger-bg',
      )}
    >
      {children}
    </div>
  );
}
