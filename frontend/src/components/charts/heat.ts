// Sequential heat scale of DOC-35 §3.5: `--heat-1` (low) … `--heat-7` (high).

export type HeatScale = 'delay' | 'otp';
export type HeatLevel = 1 | 2 | 3 | 4 | 5 | 6 | 7;

export const HEAT_LEVELS: readonly HeatLevel[] = [1, 2, 3, 4, 5, 6, 7];

/**
 * Where levels 2…7 start. Delay in seconds: early, then minute steps up to the 5-minute on-time window and past it, so
 * a late cell is red on every route. On-time percentage runs the other way, high is good (§3.5).
 */
const DELAY_STEPS = [0, 60, 120, 180, 300, 420];
const OTP_STEPS = [95, 90, 85, 80, 70, 60];

/** The value ranges of each level, for an ECharts piecewise visualMap: `[min, max)`, open at the ends. */
export function heatPieces(scale: HeatScale): { level: HeatLevel; gte?: number; lt?: number }[] {
  if (scale === 'delay') {
    return HEAT_LEVELS.map((level, index) => ({
      level,
      ...(index > 0 ? { gte: DELAY_STEPS[index - 1] } : {}),
      ...(index < DELAY_STEPS.length ? { lt: DELAY_STEPS[index] } : {}),
    }));
  }
  // OTP runs the other way: level 1 is 95 % and above.
  return HEAT_LEVELS.map((level, index) => ({
    level,
    ...(index < OTP_STEPS.length ? { gte: OTP_STEPS[index] } : {}),
    ...(index > 0 ? { lt: OTP_STEPS[index - 1] } : {}),
  }));
}
