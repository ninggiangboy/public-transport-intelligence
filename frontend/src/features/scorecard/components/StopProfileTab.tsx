import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';

import type { components } from '@/api/generated/schema';
import { Card } from '@/components/Card';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { SegmentedControl } from '@/components/SegmentedControl';
import { SelectField, StopDelayBar } from '@/features/scorecard/components/parts';
import { directionName } from '@/features/scorecard/display';
import { profileScale } from '@/features/scorecard/model';
import { delayProfileQuery } from '@/features/scorecard/queries';
import type { RouteScorecardSearch } from '@/features/scorecard/search';
import { scorecardCopy } from '@/i18n/scorecard';
import { useBusinessClock } from '@/lib/business-clock';
import { formatDelaySeconds } from '@/lib/format';
import { formatDate, formatHourOfDay, formatWeekdayLong, zonedWeekdayHour } from '@/lib/time';

type RouteDetail = components['schemas']['RouteDetailResponse'];
type ProfileItem = components['schemas']['DelayProfileItemResponse'];

const copy = scorecardCopy.scorecard;
const profile = copy.profile;
const WEEKDAYS = Array.from({ length: 7 }, (_, index) => ({ value: index + 1, label: formatWeekdayLong(index + 1) }));
const HOURS = Array.from({ length: 24 }, (_, hour) => ({ value: hour, label: formatHourOfDay(hour) }));

type Level = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

function levelOf(confidence: string): Level {
  return confidence === 'HIGH' || confidence === 'MEDIUM' || confidence === 'LOW' ? confidence : 'NONE';
}

function isNotFound(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 404;
}

function useColumns(max: number) {
  return useMemo(() => {
    const col = columnHelper<ProfileItem>();
    return [
      col.accessor('name', {
        header: profile.columns.stop,
        cell: (info) => (
          <span className="flex items-center gap-2.5">
            <span className="w-6 text-right text-xs text-muted-foreground tabular-nums">
              {info.row.original.stopSequence}
            </span>
            <span className="font-medium">{info.getValue()}</span>
          </span>
        ),
      }),
      col.display({
        id: 'bar',
        header: () => <span className="sr-only">{copy.drawer.legend}</span>,
        cell: (info) =>
          info.row.original.avgDelaySeconds === undefined ? null : (
            <StopDelayBar
              label={info.row.original.name}
              average={info.row.original.avgDelaySeconds}
              p90={info.row.original.p90DelaySeconds}
              max={max}
            />
          ),
        meta: { className: 'w-[30%] min-w-32' },
      }),
      col.accessor('avgDelaySeconds', {
        header: profile.columns.average,
        cell: (info) => {
          const value = info.getValue();
          return value === undefined ? null : formatDelaySeconds(value);
        },
        meta: { align: 'right' },
      }),
      col.accessor('p90DelaySeconds', {
        header: profile.columns.p90,
        cell: (info) => {
          const value = info.getValue();
          return value === undefined ? null : formatDelaySeconds(value);
        },
        meta: { align: 'right' },
      }),
      col.accessor('confidence', {
        header: profile.columns.confidence,
        cell: (info) => <ConfidenceChip level={levelOf(info.getValue())} sampleCount={info.row.original.sampleCount} />,
      }),
    ];
  }, [max]);
}

/**
 * Tab "Stop profile" (§4, §6): E-04 for a direction, weekday and hour (business clock by default), a row per stop
 * with its bar, numbers and confidence; stops without history read "Schedule only" and show no numbers (AC-4).
 */
export function StopProfileTab({
  route,
  search,
  onSearch,
}: {
  route: RouteDetail;
  search: RouteScorecardSearch;
  onSearch: (change: Partial<RouteScorecardSearch>) => void;
}) {
  const clock = useBusinessClock();
  const now = zonedWeekdayHour(clock.now(), clock.timezone);
  const directions = route.directions;
  const first = directions[0]?.directionId ?? 0;
  const directionId =
    search.dir !== undefined && directions.some((d) => d.directionId === search.dir) ? search.dir : first;
  const dayOfWeek = search.dow ?? now.dayOfWeek;
  const hourOfDay = search.hour ?? now.hourOfDay;
  const query = useQuery(delayProfileQuery(route.routeId, { directionId, dayOfWeek, hourOfDay }));
  const items = query.data?.data.items;
  const columns = useColumns(profileScale(items ?? []));

  // A direction the feed no longer has (404): fall back to its first direction (§6).
  const missingDirection = query.isError && isNotFound(query.error) && directionId !== first;
  useEffect(() => {
    if (missingDirection) onSearch({ dir: first });
  }, [missingDirection, first, onSearch]);

  const direction = directions.find((d) => d.directionId === directionId);
  const when = copy.drawer.when(formatWeekdayLong(dayOfWeek), formatHourOfDay(hourOfDay));
  const windowStart = query.data?.data.windowStart;
  const windowEnd = query.data?.data.windowEnd;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        {directions.length > 1 ? (
          <SegmentedControl
            label={copy.direction.label}
            value={String(directionId)}
            options={directions.map((d) => ({ value: String(d.directionId), label: directionName(d, d.directionId) }))}
            onChange={(value) => {
              onSearch({ dir: Number(value) });
            }}
          />
        ) : null}
        <SelectField
          label={profile.weekday}
          value={dayOfWeek}
          options={WEEKDAYS}
          onChange={(value) => {
            onSearch({ dow: value });
          }}
        />
        <SelectField
          label={profile.hour}
          value={hourOfDay}
          options={HOURS}
          onChange={(value) => {
            onSearch({ hour: value });
          }}
        />
      </div>

      <Card
        title={profile.title}
        meta={
          windowStart && windowEnd
            ? profile.window(formatDate(`${windowStart}T12:00:00Z`, 'UTC'), formatDate(`${windowEnd}T12:00:00Z`, 'UTC'))
            : undefined
        }
      >
        {query.isError && !query.data && !missingDirection ? (
          <ErrorState
            error={query.error}
            variant="block"
            panel={copy.panels.profile}
            onRetry={() => void query.refetch()}
          />
        ) : (
          <div className="-mx-4 -mb-3.5">
            <DataTable
              columns={columns}
              data={items ?? []}
              getRowId={(item) => `${item.stopSequence}:${item.stopId}`}
              isLoading={!items}
              dimmed={query.isPlaceholderData}
              density="compact"
              caption={profile.caption(directionName(direction, directionId), when)}
              empty={<EmptyState title={copy.chartEmpty} />}
            />
          </div>
        )}
      </Card>
    </div>
  );
}
