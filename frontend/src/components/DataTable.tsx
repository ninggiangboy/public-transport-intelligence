import { useTable, type RowData } from '@tanstack/react-table';
import type { KeyboardEvent, ReactNode } from 'react';

import { dataTableFeatures as features, type DataTableColumn } from '@/components/data-table-columns';
import { Skeleton } from '@/components/ui/skeleton';
import { en } from '@/i18n/en';
import { cn } from '@/lib/utils';

interface DataTableProps<T extends RowData> {
  /** Stable (module scope or memoised): a new array rebuilds the row model. */
  columns: DataTableColumn<T>[];
  data: T[];
  getRowId: (row: T) => string;
  /** The row whose detail is open: tinted. */
  selectedId?: string;
  /** Click or Enter on a row. */
  onRowOpen?: (row: T) => void;
  /** Skeleton rows instead of data. */
  isLoading?: boolean;
  /** Old data while new data loads: dimmed, still readable (DOC-34 P-3). */
  dimmed?: boolean;
  /** Shown instead of the body when there are no rows. */
  empty?: ReactNode;
  /** Visually hidden <caption>. */
  caption: string;
  density?: 'compact' | 'comfortable';
}

const SKELETON_ROWS = 8;

/**
 * A real <table> on TanStack Table (DOC-35 §5.3): header on `--surface`, rows tinted on hover and when selected,
 * numbers right-aligned. Rows open with a click or Enter.
 */
export function DataTable<T extends RowData>({
  columns,
  data,
  getRowId,
  selectedId,
  onRowOpen,
  isLoading = false,
  dimmed = false,
  empty,
  caption,
  density = 'comfortable',
}: DataTableProps<T>) {
  const table = useTable({ features, columns, data, getRowId: (row) => getRowId(row) });
  const cellPadding = density === 'compact' ? 'px-3 py-1.5' : 'px-3 py-2.5';
  const rows = table.getRowModel().rows;
  const columnCount = columns.length;

  const onKeyDown = (event: KeyboardEvent<HTMLTableRowElement>, row: T) => {
    if (event.key === 'Enter' && event.target === event.currentTarget) {
      event.preventDefault();
      onRowOpen?.(row);
    }
  };

  return (
    <div className="overflow-x-auto">
      <table
        className={cn('w-full border-collapse text-sm motion-safe:transition-opacity', dimmed && 'opacity-60')}
        aria-busy={isLoading || dimmed ? true : undefined}
      >
        <caption className="sr-only">{caption}</caption>
        <thead className="bg-surface">
          {table.getHeaderGroups().map((group) => (
            <tr key={group.id} className="border-b border-border">
              {group.headers.map((header) => {
                const meta = header.column.columnDef.meta;
                return (
                  <th
                    key={header.id}
                    scope="col"
                    className={cn(
                      'px-3 py-2 text-left text-xs font-medium whitespace-nowrap text-muted-foreground',
                      meta?.align === 'right' && 'text-right',
                      meta?.className,
                    )}
                  >
                    {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {isLoading ? (
            Array.from({ length: SKELETON_ROWS }, (_, index) => (
              <tr key={index} className="border-b border-border last:border-0">
                <td colSpan={columnCount} className={cellPadding}>
                  {index === 0 ? (
                    <span role="status" className="sr-only">
                      {en.states.loading}
                    </span>
                  ) : null}
                  <Skeleton className="h-5 w-full" />
                </td>
              </tr>
            ))
          ) : rows.length === 0 && empty ? (
            <tr>
              <td colSpan={columnCount}>{empty}</td>
            </tr>
          ) : (
            rows.map((row) => {
              const selected = row.id === selectedId;
              return (
                <tr
                  key={row.id}
                  tabIndex={onRowOpen ? 0 : undefined}
                  aria-current={selected ? 'true' : undefined}
                  onClick={
                    onRowOpen
                      ? () => {
                          onRowOpen(row.original);
                        }
                      : undefined
                  }
                  onKeyDown={
                    onRowOpen
                      ? (event) => {
                          onKeyDown(event, row.original);
                        }
                      : undefined
                  }
                  className={cn(
                    'border-b border-border last:border-0',
                    onRowOpen && 'cursor-pointer hover:bg-surface focus-visible:bg-surface',
                    selected && 'bg-primary-soft hover:bg-primary-soft',
                  )}
                >
                  {row.getAllCells().map((cell) => {
                    const meta = cell.column.columnDef.meta;
                    return (
                      <td
                        key={cell.id}
                        className={cn(
                          cellPadding,
                          'align-middle',
                          meta?.align === 'right' && 'text-right tabular-nums',
                          meta?.className,
                        )}
                      >
                        <table.FlexRender cell={cell} />
                      </td>
                    );
                  })}
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
