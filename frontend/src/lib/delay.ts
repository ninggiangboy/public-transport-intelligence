// Delay classes of DOC-35 §3.4. ±300 s matches the default OTP tolerance (earlyToleranceSeconds / lateToleranceSeconds).

export type DelayClass = 'early' | 'on-time' | 'late' | 'very-late' | 'unknown';

export const DELAY_CLASSES: readonly DelayClass[] = ['early', 'on-time', 'late', 'very-late', 'unknown'];

const EARLY_TOLERANCE_SECONDS = 300;
const LATE_TOLERANCE_SECONDS = 300;
const VERY_LATE_SECONDS = 600;

/** Class of a delay in seconds (positive = late); `null`/`undefined` = no delay data (DS-04). */
export function delayClass(delaySeconds: number | null | undefined): DelayClass {
  if (delaySeconds === null || delaySeconds === undefined || Number.isNaN(delaySeconds)) return 'unknown';
  if (delaySeconds < -EARLY_TOLERANCE_SECONDS) return 'early';
  if (delaySeconds <= LATE_TOLERANCE_SECONDS) return 'on-time';
  if (delaySeconds <= VERY_LATE_SECONDS) return 'late';
  return 'very-late';
}
