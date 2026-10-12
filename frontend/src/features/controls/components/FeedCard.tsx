import type { UseQueryResult } from '@tanstack/react-query';
import { FileArchive } from 'lucide-react';
import { useState } from 'react';

import type { WithAsOf } from '@/api/client';
import { AppLink } from '@/components/AppLink';
import { Callout } from '@/components/Callout';
import { Card } from '@/components/Card';
import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { RunJobDialog } from '@/features/controls/components/RunJobDialog';
import { TextField } from '@/features/controls/components/RunJobCard';
import { GTFS_STATIC_LOAD } from '@/features/controls/jobs';
import { validSourceUri, type FeedVersion } from '@/features/controls/model';
import { controlsCopy } from '@/i18n/controls';
import { en } from '@/i18n/en';
import { formatServiceDate } from '@/lib/time';
import { cn } from '@/lib/utils';

const copy = controlsCopy.feed;
const runHref = (runId: string) => `/ops/jobs?run=${encodeURIComponent(runId)}`;
const runNumber = (runId: string) => runId.replace(/^job:/, '');

interface FeedCardProps {
  feeds: UseQueryResult<WithAsOf<{ items: FeedVersion[] }>>;
  operator: boolean;
}

/** "Schedule feed" (§4): the ACTIVE feed, loading the next one, and the last 50 versions. */
export function FeedCard({ feeds, operator }: FeedCardProps) {
  const items = feeds.data?.data.items;
  const active = items?.find((feed) => feed.status === 'ACTIVE');

  return (
    <Card title={controlsCopy.sections.feeds}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">{controlsCopy.intro.feeds}</p>
        {!items ? (
          feeds.isError ? (
            <ErrorState error={feeds.error} variant="block" panel={copy.panel} onRetry={() => void feeds.refetch()} />
          ) : (
            <PanelSkeleton variant="table" rows={3} />
          )
        ) : (
          <>
            {feeds.isError ? (
              <ErrorState error={feeds.error} variant="inline" axis="audit" onRetry={() => void feeds.refetch()} />
            ) : null}
            {items.length === 0 ? (
              <EmptyState title={copy.empty} description={copy.emptyBody} />
            ) : active ? (
              <ActiveFeed feed={active} />
            ) : (
              <p className="text-sm text-muted-foreground">{copy.noActive}</p>
            )}
          </>
        )}
        {operator ? <LoadFeed /> : null}
        {items && items.length > 0 ? <History items={items} /> : null}
      </div>
    </Card>
  );
}

function Checks({ errors, warnings, cell = false }: { errors: number; warnings: number; cell?: boolean }) {
  return (
    <span className={cn(errors > 0 && 'text-tone-danger-fg')}>
      {cell ? copy.checksCell(errors, warnings) : copy.checks(errors, warnings)}
    </span>
  );
}

function ActiveFeed({ feed }: { feed: FeedVersion }) {
  const valid = feed.validFrom && feed.validTo;
  return (
    <div className="flex items-start gap-3">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-muted">
        <FileArchive className="size-4.5 text-muted-foreground" aria-hidden="true" />
      </span>
      <div className="flex min-w-0 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-mono text-sm font-semibold">
            {feed.publisherFeedVersion ?? feed.feedHash.slice(0, 12)}
          </span>
          <StatusPill domain="feed" status={feed.status} size="sm" />
          <span className="text-sm">
            <Checks errors={feed.validation.errors} warnings={feed.validation.warnings} />
          </span>
        </div>
        <p className="text-sm text-muted-foreground">
          {/* "Valid {from} – {to} · loaded {time} by run #{id}" (§8). */}
          {feed.validFrom && feed.validTo
            ? `${copy.valid(formatServiceDate(feed.validFrom), formatServiceDate(feed.validTo))} · `
            : null}
          {valid ? copy.loaded : copy.loadedCapital} <Timestamp at={feed.loadedAt} />
          {feed.runId ? (
            <>
              {' '}
              <AppLink href={runHref(feed.runId)} className="text-primary hover:underline">
                {copy.byRun(runNumber(feed.runId))}
              </AppLink>
            </>
          ) : null}
        </p>
      </div>
    </div>
  );
}

function LoadFeed() {
  const [uri, setUri] = useState('');
  const [touched, setTouched] = useState(false);
  const [asking, setAsking] = useState(false);
  const [apiError, setApiError] = useState<string | undefined>(undefined);
  const [refused, setRefused] = useState<string | undefined>(undefined);
  const trimmed = uri.trim();
  const valid = validSourceUri(trimmed);
  const error = apiError ?? (touched && trimmed !== '' && !valid ? controlsCopy.job.error.uri : undefined);

  return (
    <form
      noValidate
      className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3"
      onSubmit={(event) => {
        event.preventDefault();
        setTouched(true);
        if (valid) setAsking(true);
      }}
    >
      <h4 className="text-sm font-semibold">{copy.loadNext}</h4>
      {refused ? (
        <Callout tone="danger" title={en.error.slug['job-not-allowed'].title()}>
          {refused}
        </Callout>
      ) : null}
      <TextField
        label={copy.urlLabel}
        hint={copy.urlHelp}
        value={uri}
        error={error}
        trailing={
          <Button type="submit" disabled={trimmed === ''}>
            {copy.load}
          </Button>
        }
        onChange={(value) => {
          setUri(value);
          setApiError(undefined);
          setRefused(undefined);
        }}
      />
      <RunJobDialog
        open={asking}
        job={GTFS_STATIC_LOAD}
        parameters={{ sourceUri: trimmed }}
        onOpenChange={(open) => {
          setAsking(open);
        }}
        onFieldErrors={(errors) => {
          setApiError(errors.sourceUri);
        }}
        onRefused={setRefused}
      />
    </form>
  );
}

const columns = (() => {
  const col = columnHelper<FeedVersion>();
  const c = copy.columns;
  const day = (value: string | undefined) => (value ? formatServiceDate(value) : en.kv.empty);
  return [
    col.accessor('feedVersionId', { header: c.id, meta: { align: 'right' } }),
    col.accessor('status', {
      header: c.status,
      cell: (info) => <StatusPill domain="feed" status={info.getValue()} size="sm" />,
    }),
    col.accessor('publisherFeedVersion', {
      header: c.version,
      cell: (info) => <span className="font-mono text-xs">{info.getValue() ?? en.kv.empty}</span>,
    }),
    col.accessor('publisherName', { header: c.publisher, cell: (info) => info.getValue() ?? en.kv.empty }),
    col.display({
      id: 'valid',
      header: c.valid,
      cell: (info) => (
        <span className="whitespace-nowrap">
          {day(info.row.original.validFrom)}
          {' – '}
          {day(info.row.original.validTo)}
        </span>
      ),
    }),
    col.accessor('loadedAt', {
      header: c.loaded,
      cell: (info) => <Timestamp at={info.getValue()} showZone={false} />,
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('activatedAt', {
      header: c.activated,
      cell: (info) => {
        const at = info.getValue();
        return at ? <Timestamp at={at} showZone={false} /> : en.kv.empty;
      },
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('validation', {
      header: c.checks,
      cell: (info) => <Checks errors={info.getValue().errors} warnings={info.getValue().warnings} cell />,
      meta: { className: 'whitespace-nowrap' },
    }),
    col.accessor('runId', {
      header: c.run,
      cell: (info) => {
        const runId = info.getValue();
        return runId ? (
          <AppLink href={runHref(runId)} className="font-mono text-xs text-primary hover:underline">
            {`#${runNumber(runId)}`}
          </AppLink>
        ) : (
          en.kv.empty
        );
      },
    }),
  ];
})();

function History({ items }: { items: FeedVersion[] }) {
  return (
    <section className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold">{copy.history}</h4>
      <div className="-mx-4 border-y border-border">
        <DataTable
          columns={columns}
          data={items.slice(0, 50)}
          getRowId={(feed) => String(feed.feedVersionId)}
          caption={copy.historyCaption(Math.min(items.length, 50))}
          density="compact"
        />
      </div>
    </section>
  );
}
