import { cn } from '@/lib/utils';

/** 30 px mark of the prototype: a line with two stops on a `--foreground` tile. */
export function Logo({ className }: { className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn('grid size-7.5 shrink-0 place-items-center rounded-[9px] bg-foreground text-card', className)}
    >
      <svg
        viewBox="0 0 24 24"
        className="size-4.5"
        fill="none"
        stroke="currentColor"
        strokeWidth={2.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M5 17.5h4l6-11h4" />
        <circle cx="5" cy="17.5" r="2.2" fill="currentColor" />
        <circle cx="19" cy="6.5" r="2.2" fill="currentColor" />
      </svg>
    </span>
  );
}
