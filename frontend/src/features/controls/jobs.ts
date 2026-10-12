// The jobs an operator may start by hand: the allow-list of E-33 in its order (DOC-32 E-33, screens/ops-console-controls
// §6.1), and the detectors of AnalyticsRecomputeJob.

export const JOBS = [
  'GtfsStaticLoadJob',
  'EtaAggregationJob',
  'OtpScorecardJob',
  'AnalyticsRecomputeJob',
  'PartitionMaintenanceJob',
] as const;

export type JobName = (typeof JOBS)[number];

export const GTFS_STATIC_LOAD: JobName = 'GtfsStaticLoadJob';

export const DETECTORS = ['BUNCHING', 'DISRUPTION', 'TICKETING'] as const;

export type Detector = (typeof DETECTORS)[number];

/** The schemes E-33 accepts for `sourceUri` (DOC-21 §1.1). */
export const SOURCE_URI_SCHEMES = ['https', 's3', 'file'] as const;

/** E-33: `serviceDates` holds 1 to 31 days; AnalyticsRecomputeJob spans at most 7 days (DOC-23 §11). */
export const MAX_SERVICE_DATES = 31;
export const MAX_RECOMPUTE_MS = 7 * 86_400_000;

export function isJobName(value: string): value is JobName {
  return (JOBS as readonly string[]).includes(value);
}
