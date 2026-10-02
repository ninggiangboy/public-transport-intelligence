import { CopyButton } from '@/components/CopyButton';
import { en } from '@/i18n/en';
import { diffLines, prettyJson, tokenizeLine, type DiffLine, type TokenKind } from '@/lib/json-lines';
import { cn } from '@/lib/utils';

interface JsonViewerProps {
  value: string | object;
  /** Height in px at which the block starts to scroll. */
  maxHeight?: number;
  /** Wrap long lines instead of scrolling sideways. */
  wrap?: boolean;
  /** Shows a line diff against this value ("Edited" against "Original" at Dead letters). */
  compareTo?: string | object;
  fileName?: string;
}

const TOKEN_CLASS: Record<TokenKind, string> = {
  key: 'text-code-key',
  string: 'text-code-string',
  number: 'text-code-number',
  literal: 'text-code-literal',
  punctuation: 'text-code-punctuation',
};

function Line({ line, number }: { line: DiffLine; number: number | null }) {
  const marker = line.kind === 'add' ? '+' : line.kind === 'del' ? '−' : ' ';
  return (
    <div
      className={cn(
        'flex min-w-full',
        line.kind === 'add' && 'bg-code-add-bg',
        line.kind === 'del' && 'bg-code-del-bg',
      )}
    >
      {/* Generated content: the numbers are decoration (DOC-35 §5.6 gives them a low-contrast colour), so they stay out of
          the text that is read, selected, copied and checked for contrast. */}
      <span
        aria-hidden="true"
        {...(number === null ? {} : { 'data-line': number })}
        className="w-10 shrink-0 pr-3 text-right select-none before:text-code-line-number before:content-[attr(data-line)]"
      />
      <span aria-hidden="true" className="w-4 shrink-0 text-code-punctuation select-none">
        {marker}
      </span>
      <code className="flex-1">
        {line.kind === 'del' ? <span className="sr-only">{en.json.original}: </span> : null}
        {line.kind === 'add' ? <span className="sr-only">{en.json.edited}: </span> : null}
        {tokenizeLine(line.text).map((token, index) => (
          <span key={index} className={TOKEN_CLASS[token.kind]}>
            {token.text}
          </span>
        ))}
      </code>
    </div>
  );
}

/** Read-only pretty-printed JSON in a dark code block in both themes (DOC-35 §5.6). */
export function JsonViewer({
  value,
  maxHeight = 360,
  wrap = false,
  compareTo,
  fileName = en.json.fileName,
}: JsonViewerProps) {
  const current = prettyJson(value);
  const lines: DiffLine[] =
    compareTo === undefined
      ? current.text.split('\n').map((text) => ({ kind: 'same', text }))
      : diffLines(prettyJson(compareTo).text, current.text);
  // Line numbers count the lines of the edited text, so removed lines have none.
  const numbers = lines.reduce<(number | null)[]>((all, line) => {
    const previous = all.findLast((n) => n !== null) ?? 0;
    all.push(line.kind === 'del' ? null : previous + 1);
    return all;
  }, []);
  return (
    <div className="overflow-hidden rounded-lg border border-code-border bg-code-bg font-mono text-xs text-code-fg">
      <div className="flex items-center justify-between border-b border-code-border px-3 py-1.5 text-code-bar-fg">
        <span>{fileName}</span>
        <CopyButton
          value={current.text}
          label={en.json.label(fileName)}
          className="text-code-bar-fg hover:bg-code-border hover:text-code-fg"
        >
          {en.json.copy}
        </CopyButton>
      </div>
      {current.valid ? null : <p className="px-3 pt-2 text-code-number">{en.json.invalid}</p>}
      <div
        role="region"
        aria-label={en.json.label(fileName)}
        // Scrollable regions must be reachable by keyboard (WCAG 2.1.1).
        tabIndex={0}
        style={{ maxHeight }}
        className="overflow-auto py-2 leading-5"
      >
        <div className={cn(wrap ? 'break-all whitespace-pre-wrap' : 'w-max min-w-full whitespace-pre')}>
          {lines.map((line, index) => (
            <Line key={index} line={line} number={numbers[index] ?? null} />
          ))}
        </div>
      </div>
    </div>
  );
}
