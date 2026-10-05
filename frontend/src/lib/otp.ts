import type { components } from '@/api/generated/schema';

// On-time performance from E-14 items (DOC-23 §8): counters are added up first and divided last, never averaged as
// percentages (DOC-32 E-14). Shared by the Overview and the Route scorecard.

type OtpItem = components['schemas']['Item'];

/** Network OTP: Σ onTime × 100 / Σ observations, the formula of E-14; `undefined` without observations. */
export function networkOtp(items: readonly OtpItem[]): number | undefined {
  const observations = items.reduce((sum, item) => sum + item.observationCount, 0);
  if (observations === 0) return undefined;
  return (items.reduce((sum, item) => sum + item.onTimeCount, 0) * 100) / observations;
}

/** Network OTP per service date, weighting each route by its observations. */
export function otpByDay(items: readonly OtpItem[]): { date: string; otp: number }[] {
  const days = new Map<string, { onTime: number; observations: number }>();
  for (const item of items) {
    for (const day of item.daily) {
      const entry = days.get(day.serviceDate) ?? { onTime: 0, observations: 0 };
      entry.onTime += (day.otpPercentage * day.observationCount) / 100;
      entry.observations += day.observationCount;
      days.set(day.serviceDate, entry);
    }
  }
  return [...days.entries()]
    .filter(([, entry]) => entry.observations > 0)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, entry]) => ({ date, otp: (entry.onTime * 100) / entry.observations }));
}

/** < 70 % danger, < 80 % warning, else success (screens/overview §4, screens/route-scorecard §4). */
export function otpTone(otp: number): 'danger' | 'warning' | 'success' {
  return otp < 70 ? 'danger' : otp < 80 ? 'warning' : 'success';
}
