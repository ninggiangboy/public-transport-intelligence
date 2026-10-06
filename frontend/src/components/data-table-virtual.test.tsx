import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { columnHelper } from '@/components/data-table-columns';
import { DataTable } from '@/components/DataTable';

interface Row {
  id: string;
  label: string;
}

const col = columnHelper<Row>();
const columns = [col.accessor('label', { header: 'Label', cell: (info) => info.getValue() })];
const rows: Row[] = Array.from({ length: 10_000 }, (_, index) => ({ id: `r${index}`, label: `Row ${index}` }));

beforeEach(() => {
  // jsdom lays nothing out: the table is 600 px high and a row 48 px.
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(600);
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(400);
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    const height = this.tagName === 'TR' ? 48 : 0;
    return { x: 0, y: 0, top: 0, left: 0, right: 400, bottom: height, width: 400, height, toJSON: () => ({}) };
  });
});

function table(props: Partial<React.ComponentProps<typeof DataTable<Row>>> = {}) {
  return (
    <DataTable
      columns={columns}
      data={rows}
      getRowId={(row) => row.id}
      caption="Rows"
      virtual={{ height: 600, rowHeight: 48 }}
      {...props}
    />
  );
}

describe('DS-06 DataTable virtual', () => {
  it('AC-1 keeps at most 60 rows in the DOM for 10,000, whatever the scroll', () => {
    render(table());
    const bodyRows = () => screen.getAllByRole('row').filter((row) => row.hasAttribute('aria-rowindex')).length;
    expect(bodyRows()).toBeLessThanOrEqual(60);
    expect(screen.getByRole('table', { name: 'Rows' })).toHaveAttribute('aria-rowcount', '10001');
    expect(screen.getByText('Row 0')).toBeInTheDocument();

    const scroller = screen.getByRole('table').closest('div') ?? document.body;
    for (const top of [48 * 5_000, 48 * 9_990]) {
      scroller.scrollTop = top;
      fireEvent.scroll(scroller);
      expect(bodyRows()).toBeLessThanOrEqual(60);
    }
    // The window moved with the scroll: the head is gone, the tail is near.
    expect(screen.queryByText('Row 0')).not.toBeInTheDocument();
    const indexes = screen.getAllByRole('row').flatMap((row) => Number(row.getAttribute('aria-rowindex') ?? 0));
    expect(Math.max(...indexes)).toBeGreaterThan(9_000);
  });

  it('numbers the rows for assistive technology from the real position', () => {
    render(table());
    expect(screen.getByText('Row 0').closest('tr')).toHaveAttribute('aria-rowindex', '2');
  });

  it('asks for the next page when the loaded rows are nearly used up', () => {
    const fetchNextPage = vi.fn();
    render(table({ data: rows.slice(0, 20), hasNextPage: true, fetchNextPage }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('does not ask while a page is on its way or when there is none', () => {
    const fetchNextPage = vi.fn();
    const { rerender } = render(
      table({ data: rows.slice(0, 20), hasNextPage: true, isFetchingNextPage: true, fetchNextPage }),
    );
    rerender(table({ data: rows.slice(0, 20), hasNextPage: false, fetchNextPage }));
    expect(fetchNextPage).not.toHaveBeenCalled();
  });

  it('scrolls to the row of scrollToId and applies rowClassName', () => {
    // jsdom has no scrolling of elements; the virtualiser asks the element to scroll to the row.
    const scrollTo = vi.fn();
    Element.prototype.scrollTo = scrollTo;
    render(table({ scrollToId: 'r4000', rowClassName: (row) => (row.id === 'r0' ? 'is-picked' : undefined) }));
    expect(scrollTo).toHaveBeenCalledWith(expect.objectContaining({ top: expect.any(Number) as number }));
    expect(screen.getByText('Row 0').closest('tr')).toHaveClass('is-picked');
  });
});
