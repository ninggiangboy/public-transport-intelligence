import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';

import { ErrorState } from '@/components/ErrorState';
import { KpiCard } from '@/components/KpiCard';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RouteBadge } from '@/components/RouteBadge';
import { Skeleton } from '@/components/ui/skeleton';
import { DelaysTab } from '@/features/scorecard/components/DelaysTab';
import { DisruptionsTab } from '@/features/scorecard/components/DisruptionsTab';
import { ClampedNotice, RangeControls, ThresholdNote } from '@/features/scorecard/components/parts';
import { StopProfileTab } from '@/features/scorecard/components/StopProfileTab';
import { pointsDelta, useServiceDays, vsPrevious } from '@/features/scorecard/display';
import { previousRange, resolveRange, totals, type DayRange } from '@/features/scorecard/model';
import { routeDetailQuery, routeOtpQuery } from '@/features/scorecard/queries';
import { TABS, type RouteScorecardSearch, type Tab } from '@/features/scorecard/search';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { useDocumentTitle } from '@/lib/browser';
import { formatCompact, formatPercent } from '@/lib/format';
import { cn } from '@/lib/utils';

const copy = scorecardCopy.scorecard;

function KpiSkeleton({ label }: { label: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4 shadow-sm" aria-busy="true">
      <p className="text-label font-medium text-muted-foreground">{label}</p>
      <Skeleton className="mt-2 h-7 w-24" />
    </div>
  );
}

/** /scorecard/$routeId: how one route performs, by hour, by stop, and its disruptions (§3, §4). */
export function RouteDetailPage({ routeId, search }: { routeId: string; search: RouteScorecardSearch }) {
  const navigate = useNavigate({ from: '/scorecard/$routeId' });
  const days = useServiceDays();
  const range = resolveRange(search, days.yesterday);
  const detail = useQuery(routeDetailQuery(routeId));
  const current = useQuery({ ...routeOtpQuery(range, routeId), enabled: days.ready });
  const before = useQuery({ ...routeOtpQuery(previousRange(range), routeId), enabled: days.ready });
  const route = detail.data?.data;
  const name = route?.displayName ?? routeId;
  useDocumentTitle(route ? (route.longName ?? route.displayName) : copy.title);

  const setSearch = (change: Partial<RouteScorecardSearch>, replace = true) => {
    void navigate({ search: (prev: RouteScorecardSearch) => ({ ...prev, ...change }), replace });
  };
  const setRange = (next: DayRange) => {
    setSearch({ from: next.from, to: next.to, disruption: undefined });
  };
  const tab: Tab = search.tab ?? 'delays';

  if (detail.isError && !route) {
    return (
      <ErrorState
        error={detail.error}
        variant="block"
        thing={copy.panels.route}
        onRetry={() => void detail.refetch()}
        actions={{ goBack: () => void navigate({ to: '/scorecard', search: { from: search.from, to: search.to } }) }}
      />
    );
  }

  const items = current.data?.data.items ?? [];
  const now = totals(items);
  const then = totals(before.data?.data.items ?? []);
  const caption = vsPrevious(range, days.yesterday);
  const kpiLabels = [copy.kpi.otp, copy.kpi.early, copy.kpi.late, copy.kpi.observations];

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[
          { label: en.nav.groups.analytics },
          { label: copy.crumb, href: `/scorecard${linkQuery(search)}` },
          { label: name },
        ]}
        leading={
          route ? (
            <RouteBadge
              routeId={routeId}
              displayName={route.displayName}
              color={route.color}
              textColor={route.textColor}
              size="xl"
            />
          ) : undefined
        }
        title={route ? (route.longName ?? route.displayName) : routeId}
        subtitle={current.data ? <ThresholdNote items={items} asOf={current.data.asOf} /> : undefined}
        actions={<RangeControls range={range} yesterday={days.yesterday} onChange={setRange} />}
      />

      {range.clamped ? <ClampedNotice range={range} /> : null}

      <div className="grid grid-cols-2 gap-3.5 xl:grid-cols-4">
        {current.data ? (
          <>
            <KpiCard
              label={copy.kpi.otp}
              value={now.otp === undefined ? en.kv.empty : formatPercent(now.otp / 100)}
              delta={pointsDelta(now.otp, then.otp, 'up', caption)}
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
            <KpiCard label={copy.kpi.observations} value={formatCompact(now.observations)} />
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
          kpiLabels.map((label) => <KpiSkeleton key={label} label={label} />)
        )}
      </div>

      <div role="tablist" aria-label={copy.tabs.label} className="flex border-b border-border">
        {TABS.map((value) => {
          const active = tab === value;
          return (
            <button
              key={value}
              type="button"
              role="tab"
              id={`scorecard-tab-${value}`}
              aria-selected={active}
              aria-controls="scorecard-tabpanel"
              onClick={() => {
                if (!active) setSearch({ tab: value === 'delays' ? undefined : value, disruption: undefined }, false);
              }}
              className={cn(
                'relative inline-flex h-9.5 items-center px-2.5 text-sm font-medium text-muted-foreground hover:text-foreground',
                active &&
                  'text-foreground after:absolute after:inset-x-2 after:-bottom-px after:h-0.5 after:rounded-full after:bg-foreground',
              )}
            >
              {copy.tabs[value]}
            </button>
          );
        })}
      </div>

      <div id="scorecard-tabpanel" role="tabpanel" aria-labelledby={`scorecard-tab-${tab}`}>
        {!route || !days.ready ? (
          <PanelSkeleton variant="chart" />
        ) : tab === 'delays' ? (
          <DelaysTab
            route={route}
            range={range}
            timezone={days.timezone}
            daily={items[0]?.daily ?? []}
            otpLoading={!current.data}
            search={search}
            onSearch={setSearch}
          />
        ) : tab === 'profile' ? (
          <StopProfileTab route={route} search={search} onSearch={setSearch} />
        ) : (
          <DisruptionsTab route={route} range={range} timezone={days.timezone} search={search} onSearch={setSearch} />
        )}
      </div>
    </div>
  );
}

/** `?from=…&to=…` as they are on the URL, for the breadcrumb back to the ranking. */
function linkQuery(search: RouteScorecardSearch): string {
  const params = new URLSearchParams();
  if (search.from) params.set('from', search.from);
  if (search.to) params.set('to', search.to);
  const query = params.toString();
  return query ? `?${query}` : '';
}
