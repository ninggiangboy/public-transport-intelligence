import { type ClassValue, clsx } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * tailwind-merge with the type scale of tokens.css (DOC-35 §3.4): without it `text-kpi` reads as a colour, and
 * `cn('text-kpi', 'text-tone-warning-fg')` drops the size.
 */
const twMerge = extendTailwindMerge({
  extend: { classGroups: { 'font-size': [{ text: ['label', 'nav', 'panel', 'page', 'kpi', 'display'] }] } },
});

/** Joins class names and lets later Tailwind utilities override earlier ones (shadcn/ui convention). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
