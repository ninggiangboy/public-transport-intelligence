import { useState } from 'react';
import { toast } from 'sonner';

import { useTheme, type ThemeChoice } from '@/app/theme-provider';
import { ActivityTimeline } from '@/components/ActivityTimeline';
import { Callout } from '@/components/Callout';
import { Card } from '@/components/Card';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { ConfidenceMeter } from '@/components/ConfidenceMeter';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { CopyButton } from '@/components/CopyButton';
import { DelayBadge } from '@/components/DelayBadge';
import { DetailDrawer } from '@/components/DetailDrawer';
import { Duration } from '@/components/Duration';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FreshnessIndicator } from '@/components/FreshnessIndicator';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { IdText } from '@/components/IdText';
import { JsonEditor } from '@/components/JsonEditor';
import { JsonViewer } from '@/components/JsonViewer';
import { KeyValueList } from '@/components/KeyValueList';
import { KpiCard } from '@/components/KpiCard';
import { LineStrip } from '@/components/LineStrip';
import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { NewItemsPill } from '@/components/NewItemsPill';
import { NoAccessState } from '@/components/NoAccessState';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { RelativeTime } from '@/components/RelativeTime';
import { RouteBadge } from '@/components/RouteBadge';
import { RouteSelect } from '@/components/RouteSelect';
import { SegmentedControl } from '@/components/SegmentedControl';
import { SeverityBadge } from '@/components/SeverityBadge';
import { SourceStaleNotice } from '@/components/SourceStaleNotice';
import { Sparkline } from '@/components/Sparkline';
import { SplitView } from '@/components/SplitView';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { TimeRangePicker, type TimeRangeValue } from '@/components/TimeRangePicker';
import { ToneBadge } from '@/components/ToneBadge';
import { Button } from '@/components/ui/button';
import { Toaster } from '@/components/ui/sonner';
import {
  DELAY_CLASSES,
  DLQ_CONFLICT,
  ERRORS,
  NAMES,
  PALETTE,
  ROUTES,
  SAMPLE_JSON,
  SAMPLE_JSON_EDITED,
  SAMPLE_ROWS,
  STATUS_DOMAINS,
  STOPS,
  TONES,
  TYPE_SCALE,
  ago,
  statusesOf,
} from '@/catalog/fixtures';
import { Row, Specimen, ThemePanel } from '@/catalog/Specimen';
import { catalogCopy } from '@/i18n/catalog';
import { en } from '@/i18n/en';
import { BusinessClockProvider } from '@/lib/business-clock';

const copy = catalogCopy;

// The agency zone, so times in the catalogue read the same whatever zone the browser is in (DOC-34 §8).
const AGENCY_TIME_ZONE = 'America/Chicago';
const sample = copy.sample;

function Swatch({ token }: { token: string }) {
  return (
    <li className="flex items-center gap-2 text-xs">
      <span
        className="size-8 shrink-0 rounded-md border border-border"
        style={{ background: `var(--${token})` }}
        aria-hidden="true"
      />
      <code className="font-mono text-foreground-2">{token}</code>
    </li>
  );
}

function SwatchGroup({ title, tokens }: { title: string; tokens: readonly string[] }) {
  return (
    <div>
      <h4 className="mb-2 text-label font-medium text-muted-foreground">{title}</h4>
      <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {tokens.map((token) => (
          <Swatch key={token} token={token} />
        ))}
      </ul>
    </div>
  );
}

function Palette() {
  const groups = PALETTE;
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {(['light', 'dark'] as const).map((theme) => (
        <ThemePanel key={theme} theme={theme} className="flex flex-col gap-5">
          <SwatchGroup title={copy.palette.surfaces} tokens={groups.surfaces} />
          <SwatchGroup title={copy.palette.text} tokens={groups.text} />
          <SwatchGroup title={copy.palette.accent} tokens={groups.accent} />
          <div>
            <h4 className="mb-2 text-label font-medium text-muted-foreground">{copy.palette.tones}</h4>
            <ul className="grid grid-cols-2 gap-2">
              {TONES.map((tone) => (
                <li key={tone} className="flex items-center gap-2">
                  <ToneBadge tone={tone} label={tone} />
                  <span
                    className="size-3 rounded-full"
                    style={{ background: `var(--tone-${tone}-solid)` }}
                    aria-hidden="true"
                  />
                </li>
              ))}
            </ul>
          </div>
          <SwatchGroup title={copy.palette.delay} tokens={groups.delay} />
          <SwatchGroup title={copy.palette.chart} tokens={groups.chart} />
          <SwatchGroup title={copy.palette.heat} tokens={groups.heat} />
          <SwatchGroup title={copy.palette.map} tokens={groups.map} />
        </ThemePanel>
      ))}
    </div>
  );
}

function TypeScale() {
  return (
    <Specimen name={NAMES.fonts}>
      <ul className="flex flex-col gap-2">
        {TYPE_SCALE.map((step) => (
          <li key={step.token} className="flex items-baseline gap-4">
            <code className="w-24 shrink-0 font-mono text-xs text-muted-foreground">{step.token}</code>
            <span className={step.className}>{sample.sentence}</span>
          </li>
        ))}
      </ul>
    </Specimen>
  );
}

function StatusSpecimens() {
  return (
    <>
      <Specimen name={NAMES.severityBadge}>
        <Row>
          {([0, 1, 2, null] as const).map((severity) => (
            <SeverityBadge key={String(severity)} severity={severity} />
          ))}
        </Row>
        <Row>
          {([0, 1, 2, null] as const).map((severity) => (
            <SeverityBadge key={String(severity)} severity={severity} size="sm" showLabel={false} />
          ))}
        </Row>
      </Specimen>

      <Specimen name={NAMES.statusPill}>
        {STATUS_DOMAINS.map((domain) => (
          <Row key={domain} label={sample.statusDomain[domain]}>
            {statusesOf(domain).map((status) => (
              <StatusPill key={status} domain={domain} status={status} />
            ))}
          </Row>
        ))}
      </Specimen>

      <Specimen name={NAMES.confidenceChip}>
        <Row label={sample.confidenceLevels}>
          <ConfidenceChip level="HIGH" sampleCount={42} />
          <ConfidenceChip level="MEDIUM" sampleCount={17} />
          <ConfidenceChip level="LOW" sampleCount={4} />
          <ConfidenceChip level="NONE" />
        </Row>
        <Row label={sample.aiConfidence}>
          <ConfidenceChip value={0.92} modelVersion="triage-2026-09" />
          <ConfidenceChip value={0.71} />
          <ConfidenceChip value={0.59} />
        </Row>
        <Row label={sample.aiLow}>
          <ConfidenceChip value={0.82} lowConfidence />
        </Row>
      </Specimen>

      <Specimen name={NAMES.confidenceMeter}>
        <Row>
          <ConfidenceMeter value={0.92} />
          <ConfidenceMeter value={0.74} />
          <ConfidenceMeter value={0.41} />
        </Row>
      </Specimen>

      <Specimen name={NAMES.delayBadge}>
        <Row label={sample.delayText}>
          {[-420, 0, 450, 900, null].map((delay) => (
            <DelayBadge key={String(delay)} delaySeconds={delay} />
          ))}
        </Row>
        <Row label={sample.delayChip}>
          {DELAY_CLASSES.map((cls, index) => (
            <DelayBadge key={cls} variant="chip" delaySeconds={[-420, 0, 450, 900, null][index]} />
          ))}
        </Row>
      </Specimen>

      <Specimen name={NAMES.routeBadge}>
        <Row label={sample.routeSizes}>
          {(['sm', 'md', 'lg', 'xl'] as const).map((size) => (
            <RouteBadge key={size} routeId="901" displayName="901" color="0053A0" textColor="FFFFFF" size={size} />
          ))}
        </Row>
        <Row label={sample.routeContrast}>
          {ROUTES.map((route) => (
            <RouteBadge
              key={route.routeId}
              routeId={route.routeId}
              displayName={route.shortName ?? route.routeId}
              size="lg"
              {...(route.color ? { color: route.color } : {})}
              {...(route.textColor ? { textColor: route.textColor } : {})}
            />
          ))}
        </Row>
      </Specimen>
    </>
  );
}

function TimeSpecimens() {
  const at = ago(95);
  return (
    <>
      <Specimen name={NAMES.timestamp}>
        <Row label={sample.timestampKinds}>
          <Timestamp at={at} format="time" />
          <Timestamp at={at} />
          <Timestamp at={at} format="date" />
          <Timestamp at={at} format="time" seconds />
        </Row>
        <Row label={sample.relativeEvent}>
          <RelativeTime at={ago(3)} axis="event" />
          <RelativeTime at={ago(42)} axis="event" />
          <RelativeTime at={ago(600)} axis="event" />
          <RelativeTime at={ago(7200)} axis="event" />
        </Row>
        <Row label={sample.relativeAudit}>
          <RelativeTime at={ago(130)} axis="audit" />
        </Row>
        <Row label={sample.durations}>
          <Duration ms={384} />
          <Duration ms={4200} />
          <Duration ms={252_000} />
          <Duration ms={3_900_000} />
          <Duration iso="PT20M" />
          <Duration iso="PT1H30M" />
        </Row>
      </Specimen>

      <Specimen name={NAMES.freshness}>
        <Row label={sample.freshFresh}>
          <FreshnessIndicator asOf={ago(5)} axis="event" staleAfterSeconds={90} />
        </Row>
        <Row label={sample.freshStale}>
          <FreshnessIndicator asOf={ago(600)} axis="event" staleAfterSeconds={90} />
        </Row>
        <Row label={sample.freshAbsolute}>
          <FreshnessIndicator asOf={ago(60)} axis="audit" mode="absolute" />
        </Row>
        <Row label={sample.freshNone}>
          <FreshnessIndicator axis="event" />
        </Row>
        <div className="flex flex-col gap-2">
          <SourceStaleNotice source="TICKETING_SALES" ageSeconds={720} />
          <SourceStaleNotice source="GTFS_RT_VEHICLE_POSITION" ageSeconds={185} />
        </div>
      </Specimen>
    </>
  );
}

function FilterSpecimens() {
  const [severity, setSeverity] = useState<number[]>([1]);
  const [range, setRange] = useState<TimeRangeValue>({ window: '1h' });
  const [routes, setRoutes] = useState<string[]>(['901']);
  const [period, setPeriod] = useState<'day' | 'week' | 'month'>('week');
  const [newItems, setNewItems] = useState(3);
  return (
    <>
      <Specimen name={NAMES.filters}>
        <Row>
          <MultiSelectFilter
            label={sample.filterLabel}
            options={[
              { value: 0, label: sample.filterOptions.low, count: 120 },
              { value: 1, label: sample.filterOptions.mid, count: 14 },
              { value: 2, label: sample.filterOptions.high, count: 2 },
            ]}
            value={severity}
            onChange={setSeverity}
          />
          <MultiSelectFilter label={sample.filterLabel} options={[]} value={[]} onChange={() => undefined} />
        </Row>
        <Row label={sample.timeRange}>
          <TimeRangePicker
            value={range}
            presets={['15m', '1h', '6h', '24h']}
            maxRangeSeconds={7 * 86_400}
            granularity="minute"
            onChange={setRange}
          />
        </Row>
        <Row label={sample.routeSelect}>
          <RouteSelect routes={ROUTES} value={routes} onChange={setRoutes} />
        </Row>
      </Specimen>

      <Specimen name={NAMES.controls}>
        <Row>
          <SegmentedControl
            label={sample.segmented}
            options={[
              { value: 'day', label: sample.segmentedOptions.day },
              { value: 'week', label: sample.segmentedOptions.week },
              { value: 'month', label: sample.segmentedOptions.month },
            ]}
            value={period}
            onChange={setPeriod}
          />
          <SegmentedControl
            label={sample.segmented}
            size="sm"
            options={[
              { value: 'day', label: sample.segmentedOptions.day },
              { value: 'week', label: sample.segmentedOptions.week },
            ]}
            value={period === 'month' ? 'week' : period}
            onChange={setPeriod}
          />
        </Row>
        <Row>
          <NewItemsPill
            count={newItems}
            onShow={() => {
              setNewItems(0);
            }}
          />
        </Row>
      </Specimen>
    </>
  );
}

function StateSpecimens() {
  return (
    <>
      <Specimen name={NAMES.states}>
        <Card flat>
          <EmptyState
            title={sample.emptyTitle}
            description={sample.emptyDescription}
            action={{ label: sample.emptyAction, onClick: () => undefined }}
          />
        </Card>
        <Row>
          <NoAccessState requiredRole="viewer" signedIn={false} onSignIn={() => undefined} />
          <NoAccessState requiredRole="operator" signedIn onGoToOverview={() => undefined} />
        </Row>
        <div className="grid gap-4 sm:grid-cols-2">
          {(['table', 'chart', 'list', 'detail'] as const).map((variant) => (
            <Card key={variant} flat title={sample.skeleton[variant]}>
              <PanelSkeleton variant={variant} delayMs={0} rows={4} />
            </Card>
          ))}
        </div>
      </Specimen>

      <section className="flex flex-col gap-3">
        <h3 className="text-panel font-semibold tracking-title">{NAMES.errorState}</h3>
        <div className="grid gap-4 lg:grid-cols-2">
          {(['light', 'dark'] as const).map((theme) => (
            <ThemePanel key={theme} theme={theme} className="flex flex-col gap-3">
              <ErrorState
                error={new TypeError('offline')}
                variant="inline"
                onRetry={() => undefined}
                dataAsOf={ago(120)}
              />
              {ERRORS.map(({ key, error }) => (
                <div key={key} className="rounded-lg border border-border bg-card">
                  <p className="border-b border-border px-3 py-1.5 font-mono text-xs text-muted-foreground">{key}</p>
                  <ErrorState
                    error={error}
                    variant="block"
                    thing={sample.pageTitle}
                    panel={sample.pageTitle}
                    onRetry={() => undefined}
                    actions={{ signIn: () => undefined, goBack: () => undefined, viewReplay: () => undefined }}
                  />
                </div>
              ))}
            </ThemePanel>
          ))}
        </div>
      </section>
    </>
  );
}

function ActionSpecimens() {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [failOpen, setFailOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  return (
    <Specimen name={NAMES.actions} single>
      <Row>
        <Button
          variant="outline"
          onClick={() => {
            setConfirmOpen(true);
          }}
        >
          {sample.confirmOpen}
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            setFailOpen(true);
          }}
        >
          {sample.confirmFail}
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            setDrawerOpen(true);
          }}
        >
          {sample.drawerOpen}
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            toast.success(sample.toastOkText);
          }}
        >
          {sample.toastOk}
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            toast.error(sample.toastFailText, { description: sample.toastTrace, duration: 8000 });
          }}
        >
          {sample.toastFail}
        </Button>
      </Row>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={sample.confirmTitle}
        description={sample.confirmDescription}
        confirmLabel={sample.confirmLabel}
        tone="danger"
        reason={{ label: sample.confirmReason, min: 3, max: 200, placeholder: sample.confirmPlaceholder }}
        onConfirm={() => Promise.resolve()}
      />
      <ConfirmDialog
        open={failOpen}
        onOpenChange={setFailOpen}
        title={sample.confirmTitle}
        description={sample.confirmDescription}
        confirmLabel={sample.confirmLabel}
        onConfirm={() => Promise.reject(DLQ_CONFLICT)}
      />
      <DetailDrawer
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
        }}
        title={sample.drawerTitle}
        footer={
          <Button
            onClick={() => {
              setDrawerOpen(false);
            }}
          >
            {sample.drawerDone}
          </Button>
        }
      >
        <p>{sample.drawerBody}</p>
      </DetailDrawer>
    </Specimen>
  );
}

const rowColumn = columnHelper<(typeof SAMPLE_ROWS)[number]>();
const tableColumns = [
  rowColumn.accessor('id', { header: sample.table.id, cell: (info) => <IdText id={info.getValue()} copy={false} /> }),
  rowColumn.accessor('rule', {
    header: sample.table.rule,
    cell: (info) => <span className="font-mono">{info.getValue()}</span>,
  }),
  rowColumn.accessor('message', { header: sample.table.message, meta: { className: 'w-full max-w-0 truncate' } }),
];
const SAMPLE_EDITOR_TEXT = JSON.stringify(SAMPLE_JSON, null, 2);

function DataSpecimens() {
  return (
    <>
      <Specimen name={NAMES.data}>
        <Row>
          <CopyButton value="7f3a9c21-0d4e-4b1a-9c55-2f5a1d3e8b90" label={sample.copyLabel} />
          <CopyButton value="7f3a9c21" label={sample.copyLabel}>
            {sample.copy}
          </CopyButton>
          <IdText id="7f3a9c21-0d4e-4b1a-9c55-2f5a1d3e8b90" />
        </Row>
        <Row>
          <KeyValueList
            columns={2}
            items={[
              { label: sample.kv.agency, value: sample.kvValues.agency },
              { label: sample.kv.feed, value: sample.kvValues.feed },
              { label: sample.kv.zone, value: sample.kvValues.zone },
              { label: sample.kv.rows, value: sample.kvValues.rows },
            ]}
          />
        </Row>
        <div className="grid gap-3 sm:grid-cols-2">
          <KpiCard
            label={sample.kpi.otp}
            value="78.4"
            unit="%"
            tone="success"
            delta={{ value: '2.4 pts', direction: 'up', good: 'up', caption: sample.kpi.vsLastSunday }}
            sparkline={[70, 72, 71, 75, 74, 78, 77, 79]}
          />
          <KpiCard
            label={sample.kpi.cause}
            value="6.2"
            unit="min"
            tone="danger"
            delta={{ value: '1.1 min', direction: 'up', good: 'down' }}
            sparkline={[3, 4, 4, 5, 7, 6, 8, 9]}
          />
          <KpiCard label={sample.kpi.vehicles} value="598" unit={sample.kpi.scheduled} hint={sample.kpi.hint} />
          <KpiCard
            label={sample.kpi.flat}
            value="14"
            hint={sample.kpi.flatHint}
            delta={{ value: '0', direction: 'flat', good: 'down' }}
            href="/"
          />
        </div>
        <Row label={sample.sparkline}>
          <div className="w-40">
            <Sparkline points={[3, 5, 4, 8, 6, 9, 7]} label={sample.sparkline} area tone="info" />
          </div>
          <div className="w-40">
            <Sparkline points={[3, 5, 4, 8, 6, 9, 7]} label={sample.sparkline} />
          </div>
        </Row>
      </Specimen>

      <Specimen name={NAMES.table}>
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          <DataTable
            columns={tableColumns}
            data={SAMPLE_ROWS}
            getRowId={(row) => row.id}
            caption={sample.table.caption}
            density="compact"
            virtual={{ height: 240, rowHeight: 37 }}
          />
        </div>
      </Specimen>

      <Specimen name={NAMES.json}>
        <JsonViewer value={SAMPLE_JSON} maxHeight={220} />
        <div className="mt-3">
          <JsonViewer value={SAMPLE_JSON_EDITED} compareTo={SAMPLE_JSON} maxHeight={260} />
        </div>
        <div className="mt-3">
          <JsonEditor
            value={SAMPLE_EDITOR_TEXT}
            onChange={() => undefined}
            errors={[{ pointer: '/vehicle/id', message: sample.table.error }]}
            ariaLabel={sample.editorLabel}
            fileName="vehicle_position.json"
            height={200}
          />
        </div>
      </Specimen>
    </>
  );
}

function LayoutSpecimens() {
  return (
    <>
      <Specimen name={NAMES.layout}>
        <PageHeader
          crumbs={[{ label: sample.crumbs.ops, href: '/' }, { label: sample.crumbs.dlq }]}
          title={sample.pageTitle}
          subtitle={sample.pageSubtitle}
          actions={<Button variant="outline">{sample.pageAction}</Button>}
        />
        <div className="mt-4 flex flex-col gap-3">
          <Card title={sample.cardTitle} meta={sample.cardMeta} footer={sample.cardFooter}>
            {sample.cardBody}
          </Card>
          <Callout
            tone="primary"
            title={sample.calloutAi}
            action={
              <Button size="sm" variant="outline">
                {sample.calloutAction}
              </Button>
            }
          >
            {sample.calloutAiBody}
          </Callout>
          <Callout tone="warning">{sample.sentence}</Callout>
          <Callout tone="bunching">{sample.sentence}</Callout>
          <Card title={sample.cardTitle}>
            <ActivityTimeline
              items={[
                { id: '1', text: sample.timeline.first, at: ago(900), axis: 'audit', tone: 'danger' },
                { id: '2', text: sample.timeline.second, at: ago(600), axis: 'audit', tone: 'info' },
                { id: '3', text: sample.timeline.third, at: ago(30), axis: 'audit', tone: 'success' },
              ]}
            />
          </Card>
        </div>
      </Specimen>

      <Specimen name={NAMES.lineStrip}>
        <Row label={sample.lineVertical}>
          <div className="w-full max-w-sm">
            <LineStrip color="0053A0" stops={[...STOPS]} marker={{ atStopId: 'b', label: sample.marker }} />
          </div>
        </Row>
        <Row label={sample.lineHorizontal}>
          <LineStrip
            orientation="horizontal"
            color="0053A0"
            stops={[...STOPS]}
            segments={[
              { from: 'a', to: 'b', delayClass: 'on-time' },
              { from: 'b', to: 'c', delayClass: 'late' },
              { from: 'c', to: 'd', delayClass: 'very-late' },
              { from: 'd', to: 'e', delayClass: 'unknown' },
            ]}
          />
        </Row>
        <Row label={catalogCopy.sample.splitOpen}>
          <div className="w-full">
            <SplitView
              listWidth={160}
              list={<Card flat>{sample.splitList}</Card>}
              detail={<p className="p-4">{sample.splitDetail}</p>}
              emptyDetail={<p className="p-4">{sample.splitEmpty}</p>}
            />
          </div>
        </Row>
        <Row label={catalogCopy.sample.splitClosed}>
          <div className="w-full">
            <SplitView
              listWidth={160}
              list={<Card flat>{sample.splitList}</Card>}
              detail={null}
              emptyDetail={<p className="p-4 text-muted-foreground">{sample.splitEmpty}</p>}
            />
          </div>
        </Row>
      </Specimen>
    </>
  );
}

const THEME_OPTIONS: { value: ThemeChoice; label: string }[] = [
  { value: 'light', label: en.theme.light },
  { value: 'dark', label: en.theme.dark },
  { value: 'system', label: en.theme.system },
];

/** The `/_ui` catalogue (DOC-35 §10): cover, palette, type scale and every component with every variant. */
export function UiCatalog() {
  const { theme, setTheme } = useTheme();
  return (
    <BusinessClockProvider timezone={AGENCY_TIME_ZONE}>
      <div className="min-h-svh bg-background text-foreground">
        <main className="mx-auto flex max-w-7xl flex-col gap-12 px-4 py-8 sm:px-7">
          <header className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <h1 className="text-page font-semibold tracking-title">{copy.cover.title}</h1>
              <p className="mt-1 text-base text-muted-foreground">{copy.cover.subtitle}</p>
            </div>
            <SegmentedControl label={en.theme.label} options={THEME_OPTIONS} value={theme} onChange={setTheme} />
          </header>

          <section className="flex flex-col gap-4">
            <h2 className="text-panel font-semibold tracking-title">{copy.sections.palette}</h2>
            <Palette />
          </section>

          <section className="flex flex-col gap-4">
            <h2 className="text-panel font-semibold tracking-title">{copy.sections.type}</h2>
            <TypeScale />
          </section>

          <section className="flex flex-col gap-8">
            <h2 className="text-panel font-semibold tracking-title">{copy.sections.components}</h2>
            <StatusSpecimens />
            <TimeSpecimens />
            <FilterSpecimens />
            <StateSpecimens />
            <ActionSpecimens />
            <DataSpecimens />
            <LayoutSpecimens />
          </section>
        </main>
        <Toaster />
      </div>
    </BusinessClockProvider>
  );
}
