import { useMemo } from 'react';

import { DataTable } from '@/components/DataTable';
import { columnHelper } from '@/components/data-table-columns';
import { RouteBadge } from '@/components/RouteBadge';
import { Timestamp } from '@/components/Timestamp';
import { CategoryCell, SeverityCell } from '@/features/ticketing/components/AnomalyCells';
import { signalParts, type Anomaly } from '@/features/ticketing/model';
import { alertsCopy } from '@/i18n/alerts';
import { ticketingCopy } from '@/i18n/ticketing';
import { cn } from '@/lib/utils';

const copy = ticketingCopy.table;
const ROW_MOTION = cn('motion-safe:transition-colors motion-safe:duration-500');
const FRESH_ROW = cn('bg-tone-info-bg');

export interface RouteLook {
  displayName: string;
  color?: string;
  textColor?: string;
}

interface AnomalyTableProps {
  items: Anomaly[];
  isLoading: boolean;
  dimmed: boolean;
  selectedId?: string;
  /** Rows that just arrived: tinted for 3 s (§6). */
  fresh: ReadonlySet<string>;
  /** Detected as date and time when the period spans more than a day. */
  long: boolean;
  routes: ReadonlyMap<string, RouteLook>;
  empty: React.ReactNode;
  onOpen: (anomaly: Anomaly) => void;
  onSalePoint: (salePointId: string) => void;
}

/** Two-line rows: Detected, Sale point, Trigger, Signal, AI category, Severity (§4). */
export function AnomalyTable({
  items,
  isLoading,
  dimmed,
  selectedId,
  fresh,
  long,
  routes,
  empty,
  onOpen,
  onSalePoint,
}: AnomalyTableProps) {
  const columns = useMemo(() => {
    const col = columnHelper<Anomaly>();
    return [
      col.accessor('detectedAt', {
        header: copy.detected,
        cell: (info) => (
          <span className="whitespace-nowrap tabular-nums">
            <Timestamp at={info.getValue()} format={long ? 'datetime' : 'time'} showZone={false} />
          </span>
        ),
      }),
      col.accessor('salePointId', {
        header: copy.salePoint,
        cell: (info) => {
          const anomaly = info.row.original;
          const route = anomaly.routeId ? routes.get(anomaly.routeId) : undefined;
          return (
            <span className="flex min-w-0 flex-col items-start gap-0.5">
              <button
                type="button"
                title={copy.filterSalePoint(anomaly.salePointId)}
                onClick={(event) => {
                  event.stopPropagation();
                  onSalePoint(anomaly.salePointId);
                }}
                className="max-w-full truncate text-left font-medium hover:underline"
              >
                {anomaly.salePointName ?? anomaly.salePointId}
              </button>
              <span className="flex items-center gap-1.5">
                {anomaly.salePointName ? (
                  <span className="font-mono text-xs text-muted-foreground">{anomaly.salePointId}</span>
                ) : null}
                {anomaly.routeId ? (
                  <RouteBadge
                    routeId={anomaly.routeId}
                    displayName={route?.displayName ?? anomaly.routeId}
                    color={route?.color}
                    textColor={route?.textColor}
                    size="sm"
                  />
                ) : null}
              </span>
            </span>
          );
        },
      }),
      col.accessor('trigger', {
        header: copy.trigger,
        cell: (info) => (
          <span className="whitespace-nowrap">{alertsCopy.ticketingTrigger[info.getValue()] ?? info.getValue()}</span>
        ),
      }),
      col.display({
        id: 'signal',
        header: copy.signal,
        cell: (info) => (
          <span className="flex flex-col font-mono text-xs whitespace-nowrap tabular-nums">
            {signalParts(info.row.original).map((part) => (
              <span key={part}>{part}</span>
            ))}
          </span>
        ),
      }),
      col.accessor('category', {
        header: copy.category,
        cell: (info) => <CategoryCell anomaly={info.row.original} />,
      }),
      col.accessor('severity', {
        header: copy.severity,
        cell: (info) => <SeverityCell anomaly={info.row.original} />,
      }),
    ];
  }, [long, routes, onSalePoint]);

  return (
    <DataTable
      columns={columns}
      data={items}
      getRowId={(anomaly) => anomaly.id}
      caption={copy.caption}
      isLoading={isLoading}
      dimmed={dimmed}
      empty={empty}
      {...(selectedId ? { selectedId } : {})}
      onRowOpen={onOpen}
      rowClassName={(anomaly) => cn(ROW_MOTION, fresh.has(anomaly.id) && anomaly.id !== selectedId && FRESH_ROW)}
    />
  );
}
