import { createColumnHelper, tableFeatures, type ColumnDef, type RowData } from '@tanstack/react-table';

// Column definitions of DataTable (DOC-35 §5.3), apart from the component so that both refresh on their own.

/** What a column tells the table about its cells. */
export interface DataTableColumnMeta {
  /** Numbers are right-aligned (DOC-35 §5.3). */
  align?: 'right';
  /** Classes of the header and body cells, e.g. a fixed width. */
  className?: string;
}

export const dataTableFeatures = tableFeatures<{ columnMeta: DataTableColumnMeta }>({ columnMeta: {} });

export type DataTableFeatures = typeof dataTableFeatures;
// The value type of each column is checked where the column is written (`columnHelper().accessor`).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type DataTableColumn<T extends RowData> = ColumnDef<DataTableFeatures, T, any>;

/** Column helper bound to the table's features: `const col = columnHelper<Row>()`. */
export function columnHelper<T extends RowData>() {
  return createColumnHelper<DataTableFeatures, T>();
}
