// Tones of DOC-35 §3.2. Class names are written out in full so that Tailwind finds them.

export type Tone = 'neutral' | 'info' | 'success' | 'teal' | 'warning' | 'danger' | 'progress';

/** Tones plus the two accents Callout and ActivityTimeline also take (AI blocks, bunching). */
export type ToneOrAccent = Tone | 'primary' | 'bunching';

export const TONES: readonly Tone[] = ['neutral', 'info', 'success', 'teal', 'warning', 'danger', 'progress'];

interface ToneClasses {
  /** Light background, border and dark text: badges, pills, callouts. */
  surface: string;
  /** Text only. */
  text: string;
  /** Solid fill: dots, bars. */
  solid: string;
}

const TONE_CLASSES: Record<ToneOrAccent, ToneClasses> = {
  neutral: {
    surface: 'border-tone-neutral-border bg-tone-neutral-bg text-tone-neutral-fg',
    text: 'text-tone-neutral-fg',
    solid: 'bg-tone-neutral-solid',
  },
  info: {
    surface: 'border-tone-info-border bg-tone-info-bg text-tone-info-fg',
    text: 'text-tone-info-fg',
    solid: 'bg-tone-info-solid',
  },
  success: {
    surface: 'border-tone-success-border bg-tone-success-bg text-tone-success-fg',
    text: 'text-tone-success-fg',
    solid: 'bg-tone-success-solid',
  },
  teal: {
    surface: 'border-tone-teal-border bg-tone-teal-bg text-tone-teal-fg',
    text: 'text-tone-teal-fg',
    solid: 'bg-tone-teal-solid',
  },
  warning: {
    surface: 'border-tone-warning-border bg-tone-warning-bg text-tone-warning-fg',
    text: 'text-tone-warning-fg',
    solid: 'bg-tone-warning-solid',
  },
  danger: {
    surface: 'border-tone-danger-border bg-tone-danger-bg text-tone-danger-fg',
    text: 'text-tone-danger-fg',
    solid: 'bg-tone-danger-solid',
  },
  progress: {
    surface: 'border-tone-progress-border bg-tone-progress-bg text-tone-progress-fg',
    text: 'text-tone-progress-fg',
    solid: 'bg-tone-progress-solid',
  },
  primary: {
    surface: 'border-primary/20 bg-primary-soft text-primary-soft-fg',
    text: 'text-primary-soft-fg',
    solid: 'bg-primary',
  },
  bunching: {
    surface: 'border-bunching-border bg-bunching-soft text-bunching-fg',
    text: 'text-bunching-fg',
    solid: 'bg-bunching',
  },
};

export function toneClasses(tone: ToneOrAccent): ToneClasses {
  return TONE_CLASSES[tone];
}
