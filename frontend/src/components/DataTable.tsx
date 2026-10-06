import { useTable, type RowData } from '@tanstack/react-table';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react';

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
  /**
   * Virtualised body (TanStack Virtual): the table scrolls inside `height` and only the rows in view, plus a few more,
   * are in the DOM, so 10,000 rows stay smooth (DS-06). `rowHeight` is the estimate before a row is measured.
   */
  virtual?: { height: number | string; rowHeight: number; overscan?: number };
  /** More pages exist; `fetchNextPage` is called when the last loaded rows come into view (virtual only). */
  hasNextPage?: boolean;
  fetchNextPage?: () => void;
  isFetchingNextPage?: boolean;
  /** Extra classes of a body row, e.g. to dim a row that left its filter (DOC-34 P-5). */
  rowClassName?: (row: T) => string | undefined;
  /** Scrolls this row into view when it changes: the keyboard moves the selection (virtual only). */
  scrollToId?: string;
}

const SKELETON_ROWS = 8;
/** Rows from the end of the loaded ones at which the next page is asked for. */
const PREFETCH_ROWS = 30;

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
  virtual,
  hasNextPage = false,
  fetchNextPage,
  isFetchingNextPage = false,
  rowClassName,
  scrollToId,
}: DataTableProps<T>) {
  const table = useTable({ features, columns, data, getRowId: (row) => getRowId(row) });
  const cellPadding = density === 'compact' ? 'px-3 py-1.5' : 'px-3 py-2.5';
  const rows = table.getRowModel().rows;
  const columnCount = columns.length;

  const scroller = useRef<HTMLDivElement>(null);
  // TanStack Virtual's functions are not memoisable; nothing here is passed on to a memoised child.
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: virtual && !isLoading ? rows.length : 0,
    getScrollElement: () => scroller.current,
    estimateSize: () => virtual?.rowHeight ?? 0,
    overscan: virtual?.overscan ?? 8,
    initialRect: { width: 0, height: typeof virtual?.height === 'number' ? virtual.height : 600 },
    getItemKey: (index) => rows[index]?.id ?? index,
  });
  const items = virtual ? virtualizer.getVirtualItems() : [];
  const lastIndex = items.at(-1)?.index ?? -1;
  useEffect(() => {
    if (virtual && hasNextPage && !isFetchingNextPage && lastIndex >= rows.length - PREFETCH_ROWS) fetchNextPage?.();
  }, [virtual, hasNextPage, isFetchingNextPage, lastIndex, rows.length, fetchNextPage]);
  const selectedIndex = scrollToId === undefined ? -1 : rows.findIndex((row) => row.id === scrollToId);
  useEffect(() => {
    if (virtual && selectedIndex >= 0) virtualizer.scrollToIndex(selectedIndex, { align: 'auto' });
    // The virtualizer object changes on every render; the index alone says when to scroll.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedIndex]);
  const padTop = items[0]?.start ?? 0;
  const padBottom = items.length > 0 ? virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0) : 0;
  const bodyRows = virtual
    ? items.flatMap((item) => (rows[item.index] ? [{ row: rows[item.index], item }] : []))
    : null;

  const onKeyDown = (event: KeyboardEvent<HTMLTableRowElement>, row: T) => {
    if (event.key === 'Enter' && event.target === event.currentTarget) {
      event.preventDefault();
      onRowOpen?.(row);
    }
  };

  const renderRow = (
    row: (typeof rows)[number],
    position?: { index: number; measure: (node: Element | null) => void },
  ) => {
    const selected = row.id === selectedId;
    return (
      <tr
        key={row.id}
        ref={position?.measure}
        data-index={position?.index}
        aria-rowindex={position ? position.index + 2 : undefined}
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
          rowClassName?.(row.original),
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
  };

  const spacer = (height: number, key: string) =>
    height > 0 ? (
      <tr key={`pad-${key}`} aria-hidden="true" style={{ height }}>
        <td colSpan={columnCount} className="p-0" />
      </tr>
    ) : null;

  const top = spacer(padTop, 'top');
  const bottom = spacer(padBottom, 'bottom');

  return (
    <div
      ref={scroller}
      className={virtual ? 'overflow-auto' : 'overflow-x-auto'}
      style={virtual ? { height: virtual.height } : undefined}
      // A region that scrolls must be reachable by keyboard (WCAG 2.1.1), as the code block of JsonViewer is.
      {...(virtual ? { role: 'region', 'aria-label': caption, tabIndex: 0 } : {})}
    >
      <table
        className={cn('w-full border-collapse text-sm motion-safe:transition-opacity', dimmed && 'opacity-60')}
        aria-busy={isLoading || dimmed || isFetchingNextPage ? true : undefined}
        // The rows loaded and the header, and one more while a page is still to come (DOC-35 §5.3).
        aria-rowcount={virtual && !isLoading ? rows.length + 1 + (hasNextPage ? 1 : 0) : undefined}
      >
        <caption className="sr-only">{caption}</caption>
        <thead className={cn('bg-surface', virtual && 'sticky top-0 z-10 shadow-[0_1px_0_var(--border)]')}>
          {table.getHeaderGroups().map((group) => (
            <tr key={group.id} className="border-b border-border" aria-rowindex={virtual ? 1 : undefined}>
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
          ) : bodyRows ? (
            [
              top,
              ...bodyRows.map(({ row, item }) =>
                row ? renderRow(row, { index: item.index, measure: virtualizer.measureElement }) : null,
              ),
              bottom,
            ]
          ) : (
            rows.map((row) => renderRow(row))
          )}
        </tbody>
      </table>
    </div>
  );
}
