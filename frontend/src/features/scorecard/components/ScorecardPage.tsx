import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ChevronDown } from 'lucide-react';
import { useMemo } from 'react';

import type { components } from '@/api/generated/schema';
import { Card } from '@/components/Card';
import { TimeSeriesChart } from '@/components/charts/TimeSeriesChart';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { KpiCard } from '@/components/KpiCard';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Sparkline } from '@/components/Sparkline';
import { toneClasses } from '@/components/tone';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Skeleton } from '@/components/ui/skeleton';
import { ClampedNotice, RangeControls, ThresholdNote } from '@/features/scorecard/components/parts';
import { RouteDrawer } from '@/features/scorecard/components/RouteDrawer';
import { countDelta, formatDays, pointsDelta, useServiceDays, vsPrevious } from '@/features/scorecard/display';
import {
  countByMode,
  dailySeries,
  earlyPercent,
  filterByType,
  latePercent,
  MODE_TYPES,
  modeOf,
  otpByDayAndMode,
  previousRange,
  resolveRange,
  scorecardCsv,
  sortItems,
  totals,
  type DayRange,
  type Mode,
} from '@/features/scorecard/model';
import { otpQuery, routesQuery } from '@/features/scorecard/queries';
import { SORTS, type ScorecardSearch, type Sort } from '@/features/scorecard/search';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { downloadText, useDocumentTitle } from '@/lib/browser';
import { formatCompact, formatCount, formatPercent, formatPercentWhole } from '@/lib/format';
import { otpByDay, otpTone } from '@/lib/otp';
import { formatServiceDate } from '@/lib/time';
import { cn } from '@/lib/utils';

type OtpItem = components['schemas']['Item'];
type RouteItem = components['schemas']['RouteItemResponse'];

const DAY_MS = 86_400_000;
const copy = scorecardCopy.scorecard;
const MODE_COLORS = { bus: 'var(--chart-1)', rail: 'var(--chart-2)' } as const;
const NO_ITEMS: OtpItem[] = [];
const formatOtpValue = (v: number) => formatPercent(v / 100);
const formatDayTick = (t: string) => formatServiceDate(t.slice(0, 10));

function KpiSkeleton({ label }: { label: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4 shadow-sm" aria-busy="true">
      <p className="text-label font-medium text-muted-foreground">{label}</p>
      <Skeleton className="mt-2 h-7 w-24" />
      <Skeleton className="mt-2 h-3 w-32" />
    </div>
  );
}

/** "64.8%" and a bar toned by the score (§4). */
function OtpCell({ otp }: { otp: number }) {
  const tone = otpTone(otp);
  return (
    <span className="flex items-center gap-3">
      <span className="block h-1.5 w-24 shrink-0 rounded-full bg-muted" aria-hidden="true">
        <span
          className={cn('block h-full rounded-full', toneClasses(tone).solid)}
          style={{ width: `${Math.min(100, Math.max(0, otp))}%` }}
        />
      </span>
      <span className="font-semibold tabular-nums">{formatPercent(otp / 100)}</span>
    </span>
  );
}

function useColumns(routes: ReadonlyMap<string, RouteItem>) {
  return useMemo(() => {
    const col = columnHelper<OtpItem>();
    return [
      col.display({
        id: 'rank',
        header: copy.columns.rank,
        cell: (info) => <span className="text-muted-foreground tabular-nums">{info.row.index + 1}</span>,
        meta: { className: 'w-10' },
      }),
      col.accessor('routeId', {
        header: copy.columns.route,
        cell: (info) => {
          const route = routes.get(info.getValue());
          return (
            <span className="flex min-w-0 items-center gap-2.5">
              <RouteBadge
                routeId={info.getValue()}
                displayName={route?.displayName ?? info.getValue()}
                color={route?.color}
                textColor={route?.textColor}
              />
              <span className="truncate font-medium">{route?.longName ?? route?.displayName ?? info.getValue()}</span>
            </span>
          );
        },
      }),
      col.accessor('otpPercentage', {
        header: copy.columns.onTime,
        cell: (info) => <OtpCell otp={info.getValue()} />,
      }),
      col.display({
        id: 'trend',
        header: copy.columns.trend,
        cell: (info) => (
          <span className="block w-20">
            <Sparkline
              points={dailySeries(info.row.original)}
              tone={otpTone(info.row.original.otpPercentage)}
              label={copy.trend(routes.get(info.row.original.routeId)?.displayName ?? info.row.original.routeId)}
            />
          </span>
        ),
        meta: { className: 'w-24' },
      }),
      col.display({
        id: 'early',
        header: copy.columns.early,
        cell: (info) => formatPercent(earlyPercent(info.row.original) / 100),
        meta: { align: 'right' },
      }),
      col.display({
        id: 'late',
        header: copy.columns.late,
        cell: (info) => formatPercent(latePercent(info.row.original) / 100),
        meta: { align: 'right' },
      }),
      col.accessor('observationCount', {
        header: copy.columns.observations,
        cell: (info) => formatCompact(info.getValue()),
        meta: { align: 'right' },
      }),
      col.accessor('tripCount', {
        header: copy.columns.trips,
        cell: (info) => formatCount(info.getValue()),
        meta: { align: 'right' },
      }),
    ];
  }, [routes]);
}

/** /scorecard: which routes run late, ranked worst first (DOC-36 screens/route-scorecard). */
export function ScorecardPage({ search }: { search: ScorecardSearch }) {
  useDocumentTitle(copy.title);
  const navigate = useNavigate({ from: '/scorecard/' });
  const days = useServiceDays();
  const range = resolveRange(search, days.yesterday);
  const previous = previousRange(range);
  const current = useQuery({ ...otpQuery(range), enabled: days.ready });
  const before = useQuery({ ...otpQuery(previous), enabled: days.ready });
  const routes = useQuery(routesQuery());
  const routeById = useMemo(
    () => new Map((routes.data?.data.items ?? []).map((route) => [route.routeId, route])),
    [routes.data],
  );
  const columns = useColumns(routeById);

  const setSearch = (change: Partial<ScorecardSearch>, replace = true) => {
    void navigate({ search: (prev: ScorecardSearch) => ({ ...prev, ...change }), replace });
  };
  const setRange = (next: DayRange) => {
    setSearch({ from: next.from, to: next.to });
  };

  const sort: Sort = search.sort ?? 'otp';
  const mode = modeOf(search.routeType);
  const all = current.data?.data.items ?? NO_ITEMS;
  const items = useMemo(
    () => sortItems(filterByType(all, search.routeType, routeById), sort, routeById),
    [all, search.routeType, routeById, sort],
  );
  const counts = countByMode(all, routeById);
  const now = totals(items);
  const then = totals(filterByType(before.data?.data.items ?? NO_ITEMS, search.routeType, routeById));
  const caption = vsPrevious(range, days.yesterday);
  const daily = otpByDay(items);
  const byMode = otpByDayAndMode(items, routeById);

  const exportCsv = () => {
    const header = Object.values(copy.columns);
    downloadText(copy.fileName(range.from, range.to), scorecardCsv(header, items, routeById));
  };

  const modes: Mode[] = [
    'all',
    ...(Object.keys(MODE_TYPES) as (keyof typeof MODE_TYPES)[]).filter((m) => counts[m] > 0),
  ];
  const modeCount = (value: Mode) => (value === 'all' ? all.length : counts[value]);
  const kpisReady = current.data !== undefined;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[{ label: en.nav.groups.analytics }, { label: copy.crumb }]}
        title={copy.title}
        subtitle={current.data ? <ThresholdNote items={all} asOf={current.data.asOf} /> : undefined}
        actions={
          <RangeControls
            range={range}
            yesterday={days.yesterday}
            onChange={setRange}
            {...(items.length > 0 ? { onExport: exportCsv } : {})}
          />
        }
      />

      {range.clamped ? <ClampedNotice range={range} /> : null}

      <div className="grid grid-cols-2 gap-3.5 xl:grid-cols-4">
        {kpisReady ? (
          <>
            <KpiCard
              label={copy.kpi.systemOtp}
              value={now.otp === undefined ? en.kv.empty : formatPercent(now.otp / 100)}
              delta={pointsDelta(now.otp, then.otp, 'up', caption)}
              sparkline={daily.map((day) => day.otp)}
            />
            <KpiCard
              label={copy.kpi.early}
              value={now.early === undefined ? en.kv.empty : formatPercent(now.early / 100)}
              delta={pointsDelta(now.early, then.early, 'down', caption)}
            />
            <KpiCard
              label={copy.kpi.late}
              value={now.late === undefined ? en.kv.empty : formatPercent(now.late / 100)}
              delta={pointsDelta(now.late, then.late, 'down', caption)}
            />
            <KpiCard
              label={copy.kpi.trips}
              value={formatCount(now.trips)}
              delta={before.data ? countDelta(now.trips, then.trips, caption, formatPercentWhole) : undefined}
            />
          </>
        ) : current.isError ? (
          <div className="col-span-full">
            <ErrorState
              error={current.error}
              variant="block"
              panel={copy.panels.otp}
              onRetry={() => void current.refetch()}
            />
          </div>
        ) : (
          [copy.kpi.systemOtp, copy.kpi.early, copy.kpi.late, copy.kpi.trips].map((label) => (
            <KpiSkeleton key={label} label={label} />
          ))
        )}
      </div>

      <Card title={copy.dailyChart.title}>
        {!current.data && !current.isError ? (
          <PanelSkeleton variant="chart" />
        ) : current.isError && !current.data ? (
          <ErrorState
            error={current.error}
            variant="block"
            panel={copy.panels.otp}
            onRetry={() => void current.refetch()}
          />
        ) : byMode.length === 0 ? (
          <EmptyState title={copy.chartEmpty} />
        ) : (
          <TimeSeriesChart
            series={byMode.map(({ mode: group, days: points }) => ({
              name: copy.mode[group],
              color: MODE_COLORS[group],
              points: points.map((day) => ({ t: `${day.date}T12:00:00Z`, v: Math.round(day.otp * 10) / 10 })),
            }))}
            yLabel={copy.dailyChart.yLabel}
            yRange={{ max: 100 }}
            caption={copy.dailyChart.caption(
              range.from,
              range.to,
              now.otp === undefined ? en.kv.empty : formatPercent(now.otp / 100),
            )}
            formatTime={formatDayTick}
            minIntervalMs={DAY_MS}
            formatValue={formatOtpValue}
          />
        )}
      </Card>

      <section className="overflow-hidden rounded-lg border border-border bg-card shadow-sm" aria-label={copy.title}>
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
          {modes.length > 1 ? (
            <SegmentedControl<Mode>
              label={copy.mode.label}
              value={mode}
              options={modes.map((value) => ({
                value,
                label: copy.modeOption(copy.mode[value], formatCount(modeCount(value))),
              }))}
              onChange={(value) => {
                setSearch({ routeType: value === 'all' ? undefined : [...MODE_TYPES[value]], route: undefined });
              }}
            />
          ) : (
            <span />
          )}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm" aria-label={`${copy.sort.label}: ${copy.sort[sort]}`}>
                {copy.sort[sort]}
                <ChevronDown aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuRadioGroup
                value={sort}
                onValueChange={(value) => {
                  const next = SORTS.find((s) => s === value);
                  if (next) setSearch({ sort: next === 'otp' ? undefined : next });
                }}
              >
                {SORTS.map((value) => (
                  <DropdownMenuRadioItem key={value} value={value}>
                    {copy.sort[value]}
                  </DropdownMenuRadioItem>
                ))}
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
        {current.isError && !current.data ? (
          <ErrorState
            error={current.error}
            variant="block"
            panel={copy.panels.otp}
            onRetry={() => void current.refetch()}
          />
        ) : (
          <DataTable
            columns={columns}
            data={items}
            getRowId={(item) => item.routeId}
            selectedId={search.route}
            onRowOpen={(item) => {
              setSearch({ route: item.routeId }, false);
            }}
            isLoading={!current.data}
            dimmed={current.isPlaceholderData}
            caption={copy.tableCaption(range.from, range.to)}
            empty={<EmptyState title={copy.empty.title} description={copy.empty.body} />}
          />
        )}
      </section>

      {search.route ? (
        <RouteDrawer
          routeId={search.route}
          range={range}
          rangeLabel={formatDays(range)}
          item={all.find((item) => item.routeId === search.route)}
          route={routeById.get(search.route)}
          timezone={days.timezone}
          linkSearch={{ from: search.from, to: search.to }}
          onClose={() => {
            setSearch({ route: undefined }, false);
          }}
        />
      ) : null}
    </div>
  );
}
