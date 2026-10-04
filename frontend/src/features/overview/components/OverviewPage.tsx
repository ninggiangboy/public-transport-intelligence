import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { Map as MapIcon } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import type { components } from '@/api/generated/schema';
import { useFreshness } from '@/app/freshness';
import { AppLink } from '@/components/AppLink';
import { Card } from '@/components/Card';
import { TimeSeriesChart } from '@/components/charts/TimeSeriesChart';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { KpiCard } from '@/components/KpiCard';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { SegmentedControl } from '@/components/SegmentedControl';
import { ToneBadge } from '@/components/ToneBadge';
import { toneClasses } from '@/components/tone';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { NetworkPulse } from '@/features/overview/components/NetworkPulse';
import {
  networkOtp,
  otpByDay,
  otpTone,
  PERIODS,
  periodRanges,
  pulseRoutes,
  ratesBySource,
  routesToWatch,
  type Period,
} from '@/features/overview/model';
import {
  dayOfJobsQuery,
  dlqSummaryQuery,
  liveVehiclesQuery,
  openAlertsQuery,
  otpQuery,
  recentThroughputQuery,
  routesQuery,
} from '@/features/overview/queries';
import { en } from '@/i18n/en';
import { alertVisual, summaryLine } from '@/lib/alert-display';
import { useDocumentTitle } from '@/lib/browser';
import { useBusinessClock } from '@/lib/business-clock';
import { formatCount, formatDuration, formatPercent } from '@/lib/format';
import { formatDate, formatServiceDate, formatTime, zonedDate } from '@/lib/time';
import { useRelative } from '@/lib/use-now';
import { cn } from '@/lib/utils';
import { useRealtime, useRealtimeState } from '@/realtime/useRealtime';

type Alert = components['schemas']['AlertResponse'];
type LiveVehicle = components['schemas']['LiveVehicleResponse'];
type RouteItem = components['schemas']['RouteItemResponse'];

/** The pulse redraws from the cache at most this often, not on every `vehicles.batch` (§5). */
const PULSE_EVERY_MS = 5_000;
/** A new alert in "Needs attention" stays tinted this long (§6). */
const HIGHLIGHT_MS = 2_000;
const ATTENTION_ROWS = 4;
const CURRENT_SERIES_COLOR = 'var(--chart-1)';

/** Where each block leads (screens/overview §4). */
const HREF = {
  map: '/map',
  scorecard: '/scorecard',
  alerts: '/alerts',
  deadLetters: '/ops/dlq',
  pipeline: '/ops/jobs',
  streams: '/ops/jobs?kind=STREAM',
  batchJobs: '/ops/jobs?kind=BATCH_JOB',
} as const;

/** `value`, re-read at most every `ms`: the pulse does not redraw for every `vehicles.batch` (§5). */
function useThrottled<T>(value: T, ms: number): T {
  const [shown, setShown] = useState(value);
  const latest = useRef(value);
  useEffect(() => {
    latest.current = value;
  }, [value]);
  useEffect(() => {
    const timer = setInterval(() => {
      setShown(latest.current);
    }, ms);
    return () => {
      clearInterval(timer);
    };
  }, [ms]);
  return shown ?? value;
}

function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Card title={title} actions={action}>
      {children}
    </Card>
  );
}

function KpiSkeleton({ label }: { label: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4 shadow-sm" aria-busy="true">
      <p className="text-label font-medium text-muted-foreground">{label}</p>
      <Skeleton className="mt-2 h-7 w-24" />
      <Skeleton className="mt-2 h-3 w-32" />
    </div>
  );
}

function LiveLine() {
  const clock = useBusinessClock();
  const realtime = useRealtimeState();
  const now = clock.now();
  const weekday = new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: clock.timezone }).format(now);
  const live = realtime.status === 'open' || realtime.status === 'connecting';
  return (
    <span className="inline-flex items-center gap-2 text-sm">
      <span
        aria-hidden="true"
        className={cn(
          'size-[7px] rounded-full ring-[3px]',
          live ? 'bg-tone-success-solid ring-tone-success-bg' : 'bg-tone-warning-solid ring-tone-warning-bg',
        )}
      />
      {en.overview.live(weekday, formatDate(now, clock.timezone), formatTime(now, { timeZone: clock.timezone }))}
    </span>
  );
}

function AttentionRow({ alert, fresh, route }: { alert: Alert; fresh: boolean; route?: RouteItem }) {
  const age = useRelative(alert.createdAt, 'audit');
  const visual = alertVisual(alert);
  const tone = alert.severity >= 2 ? 'danger' : alert.severity === 1 ? 'warning' : 'neutral';
  return (
    <li>
      <AppLink
        href={`/alerts?alert=${alert.id}`}
        className={cn('flex items-center gap-3 rounded-md px-2 py-2 hover:bg-surface', fresh && 'bg-tone-info-bg')}
      >
        <span
          className={cn('grid size-8 shrink-0 place-items-center rounded-[9px]', toneClasses(visual.tone).surface)}
          aria-hidden="true"
        >
          <visual.icon className="size-4" strokeWidth={1.75} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">{alert.title}</span>
          <span className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
            {route && alert.routeId ? (
              <RouteBadge
                routeId={alert.routeId}
                displayName={route.displayName}
                color={route.color}
                textColor={route.textColor}
                size="sm"
              />
            ) : null}
            <span className="truncate">{en.overview.attention.meta(summaryLine(alert) ?? '', age)}</span>
          </span>
        </span>
        <span title={en.severity[alert.severity as 0 | 1 | 2]}>
          <ToneBadge tone={tone} size="sm" label={en.alerts.severityShort[alert.severity] ?? String(alert.severity)} />
        </span>
      </AppLink>
    </li>
  );
}

function NeedsAttention({ alerts, routes }: { alerts: Alert[]; routes: Map<string, RouteItem> }) {
  const top = [...alerts]
    .sort((a, b) => b.severity - a.severity || b.createdAt.localeCompare(a.createdAt))
    .slice(0, ATTENTION_ROWS);
  const [seen, setSeen] = useState<Set<string> | undefined>(undefined);
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const ids = top.map((alert) => alert.id).join();
  const [lastIds, setLastIds] = useState(ids);
  if (ids !== lastIds) {
    setLastIds(ids);
    if (seen) setFresh(new Set(top.filter((alert) => !seen.has(alert.id)).map((alert) => alert.id)));
    setSeen(new Set([...(seen ?? []), ...top.map((alert) => alert.id)]));
  } else if (!seen && top.length > 0) {
    setSeen(new Set(top.map((alert) => alert.id)));
  }
  useEffect(() => {
    if (fresh.size === 0) return;
    const timer = setTimeout(() => {
      setFresh(new Set());
    }, HIGHLIGHT_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [fresh]);
  if (top.length === 0)
    return <EmptyState title={en.overview.attention.emptyTitle} description={en.overview.attention.emptyBody} />;
  return (
    <ul className="-mx-2 flex flex-col">
      {top.map((alert) => (
        <AttentionRow
          key={alert.id}
          alert={alert}
          fresh={fresh.has(alert.id)}
          route={alert.routeId ? routes.get(alert.routeId) : undefined}
        />
      ))}
    </ul>
  );
}

function PipelineRow({
  label,
  value,
  warning,
  href,
}: {
  label: string;
  value: string;
  warning?: boolean;
  href: string;
}) {
  return (
    <li>
      <AppLink
        href={href}
        className="flex items-center justify-between gap-3 rounded-md px-2 py-2 text-sm hover:bg-surface"
      >
        <span className="font-medium">{label}</span>
        <span
          className={cn(
            'text-right text-xs tabular-nums',
            warning ? toneClasses('warning').text : 'text-muted-foreground',
          )}
        >
          {value}
        </span>
      </AppLink>
    </li>
  );
}

/** /overview: is the network fine? (DOC-36 screens/overview). Every block has its own query and error state. */
export function OverviewPage({ period }: { period: Period }) {
  useDocumentTitle(en.overview.crumb);
  const navigate = useNavigate({ from: '/overview' });
  const clock = useBusinessClock();
  const { data: freshness } = useFreshness();
  useRealtime({ channels: ['vehicles', 'alerts', 'dlq'] });

  // "Yesterday" is the business clock's (DOC-34 §8): wait for /system/freshness rather than guess from the wall clock.
  const today = freshness
    ? zonedDate(freshness.businessNow, freshness.activeFeed?.timezone ?? clock.timezone)
    : undefined;
  const ranges = periodRanges(period, today ?? '1970-01-02');
  const ready = today !== undefined;
  const current = useQuery({ ...otpQuery(ranges.current), enabled: ready });
  const previous = useQuery({ ...otpQuery(ranges.previous), enabled: ready });
  const chartCurrent = useQuery({ ...otpQuery(ranges.chart), enabled: ready });
  const chartPrevious = useQuery({ ...otpQuery(ranges.chartPrevious), enabled: ready });
  const vehicles = useQuery(liveVehiclesQuery());
  const alerts = useQuery(openAlertsQuery());
  const dlq = useQuery(dlqSummaryQuery());
  const throughput = useQuery(recentThroughputQuery());
  const jobs = useQuery(dayOfJobsQuery());
  const routes = useQuery(routesQuery());
  const routeById = new Map((routes.data?.data.items ?? []).map((route) => [route.routeId, route]));

  const otpNow = current.data ? networkOtp(current.data.data.items) : undefined;
  const otpBefore = previous.data ? networkOtp(previous.data.data.items) : undefined;
  const delta = otpNow !== undefined && otpBefore !== undefined ? otpNow - otpBefore : undefined;

  const liveVehicles: LiveVehicle[] = useThrottled(vehicles.data?.data.items, PULSE_EVERY_MS) ?? [];
  const openAlerts = alerts.data?.data.items.filter((alert) => !alert.resolvedAt) ?? [];
  const bySeverity = (level: number) => openAlerts.filter((alert) => alert.severity === level).length;
  const disruptedStops = new Map<string, string[]>();
  for (const alert of openAlerts) {
    if (alert.type !== 'DISRUPTION' || !alert.routeId) continue;
    const stops = Array.isArray(alert.body.affectedStopIds)
      ? alert.body.affectedStopIds.filter((id): id is string => typeof id === 'string')
      : [];
    disruptedStops.set(alert.routeId, [...(disruptedStops.get(alert.routeId) ?? []), ...stops]);
  }
  const pulse = pulseRoutes(liveVehicles, [...disruptedStops.keys()]);

  const chartSeries = (query: typeof chartCurrent, shift: number) =>
    otpByDay(query.data?.data.items ?? []).map((day) => ({
      // The previous period is drawn over the current one, day for day.
      t: new Date(Date.parse(`${day.date}T12:00:00Z`) + shift).toISOString(),
      v: Math.round(day.otp * 10) / 10,
    }));
  const chartShift =
    Date.parse(`${ranges.chart.from}T00:00:00Z`) - Date.parse(`${ranges.chartPrevious.from}T00:00:00Z`);
  const days = chartSeries(chartCurrent, 0);
  const watch = current.data ? routesToWatch(current.data.data.items) : [];

  const rates = throughput.data ? ratesBySource(throughput.data.data) : undefined;
  const source = (name: string) => freshness?.sources.find((s) => s.source === name);
  const pipelineValue = (name: string, gtfs: boolean) => {
    const fresh = source(name);
    if (fresh?.stale) {
      return {
        value: en.overview.pipeline.noData(
          fresh.ageSeconds === undefined ? '—' : formatDuration(fresh.ageSeconds * 1000),
        ),
        warning: true,
      };
    }
    const rate = rates?.get(name);
    const rateText = rate === undefined ? '—' : formatCount(Math.round(rate));
    const age = fresh?.ageSeconds === undefined ? '' : ` · ${formatDuration(fresh.ageSeconds * 1000)}`;
    return {
      value: `${gtfs ? en.overview.pipeline.gtfsRate(rateText) : en.overview.pipeline.rate(rateText)}${age}`,
      warning: false,
    };
  };
  const failed = jobs.data?.data.batchJobs.failed;
  const routeCount = new Set(vehicles.data?.data.items.map((v) => v.routeId)).size;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[{ label: en.overview.agency }, { label: en.overview.crumb }]}
        title={en.overview.title}
        subtitle={<LiveLine />}
        actions={
          <>
            <SegmentedControl
              label={en.overview.period.label}
              value={period}
              options={PERIODS.map((value) => ({ value, label: en.overview.period[value] }))}
              onChange={(value) => {
                void navigate({ search: value === '1d' ? {} : { period: value }, replace: true });
              }}
            />
            <Button asChild>
              <Link to="/map">
                <MapIcon aria-hidden="true" />
                {en.overview.openMap}
              </Link>
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-2 gap-3.5 xl:grid-cols-4">
        {vehicles.data ? (
          <KpiCard
            label={en.overview.kpi.vehicles}
            value={formatCount(vehicles.data.data.count)}
            hint={en.overview.kpi.onRoutes(routeCount, formatCount(routeCount))}
            href={HREF.map}
          />
        ) : vehicles.isError ? (
          <ErrorState
            error={vehicles.error}
            variant="block"
            panel={en.overview.panels.vehicles}
            onRetry={() => void vehicles.refetch()}
          />
        ) : (
          <KpiSkeleton label={en.overview.kpi.vehicles} />
        )}
        {current.data ? (
          <KpiCard
            label={en.overview.kpi.otp}
            value={otpNow === undefined ? en.kv.empty : formatPercent(otpNow / 100)}
            delta={
              delta === undefined
                ? undefined
                : {
                    value: en.overview.kpi.points(Math.abs(delta).toFixed(1)),
                    direction: Math.abs(delta) < 0.05 ? 'flat' : delta > 0 ? 'up' : 'down',
                    good: 'up',
                    caption: en.overview.kpi.vsPrevious,
                  }
            }
            href={HREF.scorecard}
          />
        ) : current.isError ? (
          <ErrorState
            error={current.error}
            variant="block"
            panel={en.overview.panels.otp}
            onRetry={() => void current.refetch()}
          />
        ) : (
          <KpiSkeleton label={en.overview.kpi.otp} />
        )}
        {alerts.data ? (
          <KpiCard
            label={en.overview.kpi.alerts}
            value={formatCount(openAlerts.length)}
            hint={en.overview.kpi.bySeverity(bySeverity(2), bySeverity(1), bySeverity(0))}
            href={HREF.alerts}
          />
        ) : alerts.isError ? (
          <ErrorState
            error={alerts.error}
            variant="block"
            panel={en.overview.panels.alerts}
            onRetry={() => void alerts.refetch()}
          />
        ) : (
          <KpiSkeleton label={en.overview.kpi.alerts} />
        )}
        {dlq.data ? (
          <KpiCard
            label={en.overview.kpi.deadLetters}
            value={formatCount(dlq.data.data.open)}
            hint={en.overview.kpi.lastHour(formatCount(dlq.data.data.createdLastHour))}
            href={HREF.deadLetters}
          />
        ) : dlq.isError ? (
          <ErrorState
            error={dlq.error}
            variant="block"
            panel={en.overview.panels.deadLetters}
            onRetry={() => void dlq.refetch()}
          />
        ) : (
          <KpiSkeleton label={en.overview.kpi.deadLetters} />
        )}
      </div>

      <div className="grid gap-3.5 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Panel
          title={en.overview.pulse.title}
          action={<span className="text-xs text-muted-foreground">{en.overview.pulse.subtitle}</span>}
        >
          {vehicles.isPending ? (
            <PanelSkeleton variant="list" rows={6} />
          ) : vehicles.isError && !vehicles.data ? (
            <ErrorState
              error={vehicles.error}
              variant="block"
              panel={en.overview.panels.vehicles}
              onRetry={() => void vehicles.refetch()}
            />
          ) : pulse.length === 0 ? (
            <EmptyState title={en.overview.pulse.empty} />
          ) : (
            <NetworkPulse routeIds={pulse} vehicles={liveVehicles} routes={routeById} disruptedStops={disruptedStops} />
          )}
        </Panel>
        <Panel
          title={en.overview.attention.title}
          action={
            <AppLink href={HREF.alerts} className="text-sm text-primary hover:underline">
              {en.overview.attention.all}
            </AppLink>
          }
        >
          {alerts.isPending ? (
            <PanelSkeleton variant="list" rows={4} />
          ) : alerts.isError && !alerts.data ? (
            <ErrorState
              error={alerts.error}
              variant="block"
              panel={en.overview.panels.alerts}
              onRetry={() => void alerts.refetch()}
            />
          ) : (
            <NeedsAttention alerts={openAlerts} routes={routeById} />
          )}
        </Panel>
      </div>

      <div className="grid gap-3.5 xl:grid-cols-3">
        <Panel title={en.overview.otpChart.title}>
          {chartCurrent.isPending ? (
            <PanelSkeleton variant="chart" />
          ) : chartCurrent.isError && !chartCurrent.data ? (
            <ErrorState
              error={chartCurrent.error}
              variant="block"
              panel={en.overview.panels.otp}
              onRetry={() => void chartCurrent.refetch()}
            />
          ) : days.length === 0 ? (
            <EmptyState title={en.overview.otpChart.emptyTitle} description={en.overview.otpChart.emptyBody} />
          ) : (
            <TimeSeriesChart
              series={[
                { name: en.overview.otpChart.current, color: CURRENT_SERIES_COLOR, points: days },
                { name: en.overview.otpChart.previous, dashed: true, points: chartSeries(chartPrevious, chartShift) },
              ]}
              yLabel={en.overview.otpChart.yLabel}
              yRange={{ max: 100 }}
              caption={en.overview.otpChart.caption(
                ranges.chart.from,
                ranges.chart.to,
                otpNow === undefined ? en.kv.empty : formatPercent(otpNow / 100),
              )}
              formatTime={(t) => formatServiceDate(t.slice(0, 10))}
              formatValue={(v) => formatPercent(v / 100)}
            />
          )}
        </Panel>
        <Panel
          title={en.overview.watch.title}
          action={
            <AppLink href={HREF.scorecard} className="text-sm text-primary hover:underline">
              {en.overview.watch.scorecard}
            </AppLink>
          }
        >
          {current.isPending ? (
            <PanelSkeleton variant="list" rows={5} />
          ) : current.isError && !current.data ? (
            <ErrorState
              error={current.error}
              variant="block"
              panel={en.overview.panels.otp}
              onRetry={() => void current.refetch()}
            />
          ) : watch.length === 0 ? (
            <EmptyState title={en.overview.otpChart.emptyTitle} description={en.overview.otpChart.emptyBody} />
          ) : (
            <ul className="-mx-2 flex flex-col">
              {watch.map((item) => {
                const route = routeById.get(item.routeId);
                const tone = otpTone(item.otpPercentage);
                return (
                  <li key={item.routeId}>
                    <AppLink
                      href={`/scorecard?route=${encodeURIComponent(item.routeId)}`}
                      className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 rounded-md px-2 py-2 hover:bg-surface"
                    >
                      <RouteBadge
                        routeId={item.routeId}
                        displayName={route?.displayName ?? item.routeId}
                        color={route?.color}
                        textColor={route?.textColor}
                      />
                      <span className="min-w-0">
                        <span className="block truncate text-sm">
                          {route?.longName ?? route?.displayName ?? item.routeId}
                        </span>
                        <span className="mt-1 block h-1.5 rounded-full bg-muted">
                          <span
                            className={cn('block h-full rounded-full', toneClasses(tone).solid)}
                            style={{ width: `${Math.min(100, Math.max(0, item.otpPercentage))}%` }}
                          />
                        </span>
                      </span>
                      <span className={cn('text-sm font-semibold tabular-nums', toneClasses(tone).text)}>
                        {formatPercent(item.otpPercentage / 100)}
                      </span>
                    </AppLink>
                  </li>
                );
              })}
            </ul>
          )}
        </Panel>
        <Panel
          title={en.overview.pipeline.title}
          action={
            <AppLink href={HREF.pipeline} className="text-sm text-primary hover:underline">
              {en.overview.pipeline.details}
            </AppLink>
          }
        >
          {throughput.isError && jobs.isError ? (
            <ErrorState
              error={throughput.error}
              variant="block"
              panel={en.overview.panels.pipeline}
              onRetry={() => void throughput.refetch()}
            />
          ) : (
            <ul className="-mx-2 flex flex-col">
              <PipelineRow
                label={en.overview.pipeline.vehicles}
                href={HREF.streams}
                {...pipelineValue('GTFS_RT_VEHICLE_POSITION', true)}
              />
              <PipelineRow
                label={en.overview.pipeline.tripUpdates}
                href={HREF.streams}
                {...pipelineValue('GTFS_RT_TRIP_UPDATE', true)}
              />
              <PipelineRow
                label={en.overview.pipeline.sales}
                href={HREF.streams}
                {...pipelineValue('TICKETING_SALES', false)}
              />
              <li>
                <AppLink
                  href={HREF.batchJobs}
                  className="flex items-center justify-between gap-3 rounded-md px-2 py-2 text-sm hover:bg-surface"
                >
                  <span className="font-medium">{en.overview.pipeline.batch}</span>
                  {failed === undefined ? (
                    <Skeleton className="h-3 w-20" />
                  ) : (
                    <span className={cn('text-xs', failed > 0 ? toneClasses('danger').text : 'text-muted-foreground')}>
                      {failed > 0 ? en.overview.pipeline.failed(failed) : en.overview.pipeline.allSucceeded}
                    </span>
                  )}
                </AppLink>
              </li>
            </ul>
          )}
        </Panel>
      </div>
    </div>
  );
}
