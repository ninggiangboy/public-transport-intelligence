import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';

interface NewItemsPillProps {
  count: number;
  onShow: () => void;
  /** What the new rows are; default alerts. */
  noun?: { one: string; other: string };
}

/** "3 new alerts · show": realtime rows are held back above a list until the user asks (DOC-34 P-5). */
export function NewItemsPill({ count, onShow, noun = en.newItems.alerts }: NewItemsPillProps) {
  if (count <= 0) return null;
  return (
    <button
      type="button"
      onClick={onShow}
      className={cn(
        'inline-flex h-7 items-center rounded-full border border-primary/20 bg-primary-soft px-3 text-label font-medium text-primary-soft-fg shadow-xs',
        'hover:border-primary/40 motion-safe:transition-colors',
      )}
    >
      {en.newItems.pill(count, noun)}
    </button>
  );
}
