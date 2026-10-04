import { useFreshness } from '@/app/freshness';
import { en } from '@/i18n/en';
import { formatDateTime, parseIsoDuration } from '@/lib/time';

/** Data and map attribution; "Simulated clock" while the business clock runs off the wall clock (DOC-34 §8). */
export function ShellFooter() {
  const { data } = useFreshness();
  // A negative offset ("-PT13H") does not parse; it is a shift all the same.
  const shifted = data !== undefined && parseIsoDuration(data.clockOffset) !== 0;
  return (
    <footer className="mt-auto flex flex-wrap gap-x-4 gap-y-1 pt-8 text-xs text-muted-foreground">
      <p>{en.footer.attribution}</p>
      {shifted ? (
        <p className="tabular-nums">
          {en.footer.simulatedClock(formatDateTime(data.businessNow, { timeZone: data.activeFeed?.timezone }))}
        </p>
      ) : null}
    </footer>
  );
}
