// CSV of RFC 4180: comma-separated, CRLF line ends, fields quoted when they hold a comma, a quote or a line break.

export type CsvValue = string | number | null | undefined;

function field(value: CsvValue): string {
  if (value === null || value === undefined) return '';
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

/** A header row and data rows as CSV text. Numbers are written as they are, without grouping or units. */
export function toCsv(header: readonly string[], rows: readonly (readonly CsvValue[])[]): string {
  return [header, ...rows].map((row) => row.map(field).join(',')).join('\r\n') + '\r\n';
}
