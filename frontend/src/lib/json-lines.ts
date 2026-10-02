// Pretty-printing, syntax tokens and line diff for JsonViewer (DOC-35 §5.6). No dependency: payloads are small.

/** Pretty-printed JSON of an object, or of a string that parses as JSON; any other string is returned as it is. */
export function prettyJson(value: string | object): { text: string; valid: boolean } {
  if (typeof value !== 'string') return { text: JSON.stringify(value, null, 2), valid: true };
  try {
    return { text: JSON.stringify(JSON.parse(value), null, 2), valid: true };
  } catch {
    return { text: value, valid: false };
  }
}

export type TokenKind = 'key' | 'string' | 'number' | 'literal' | 'punctuation';

export interface Token {
  kind: TokenKind;
  text: string;
}

const TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;

/** Splits one line of pretty-printed JSON into coloured tokens; everything between matches is punctuation. */
export function tokenizeLine(line: string): Token[] {
  const tokens: Token[] = [];
  let last = 0;
  for (const match of line.matchAll(TOKEN)) {
    if (match.index > last) tokens.push({ kind: 'punctuation', text: line.slice(last, match.index) });
    const [whole, quoted, colon, literal] = match;
    if (quoted !== undefined) {
      tokens.push({ kind: colon === undefined ? 'string' : 'key', text: quoted });
      if (colon !== undefined) tokens.push({ kind: 'punctuation', text: colon });
    } else if (literal !== undefined) {
      tokens.push({ kind: 'literal', text: whole });
    } else {
      tokens.push({ kind: 'number', text: whole });
    }
    last = match.index + whole.length;
  }
  if (last < line.length) tokens.push({ kind: 'punctuation', text: line.slice(last) });
  return tokens;
}

export interface DiffLine {
  kind: 'same' | 'add' | 'del';
  text: string;
}

/** Line diff by longest common subsequence: "del" lines exist only in `original`, "add" lines only in `edited`. */
export function diffLines(original: string, edited: string): DiffLine[] {
  const a = original.split('\n');
  const b = edited.split('\n');
  // lcs[i * width + j] = length of the longest common subsequence of a[i..] and b[j..].
  const width = b.length + 1;
  const lcs = new Array<number>((a.length + 1) * width).fill(0);
  const at = (i: number, j: number) => lcs[i * width + j] ?? 0;
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i * width + j] = a[i] === b[j] ? at(i + 1, j + 1) + 1 : Math.max(at(i + 1, j), at(i, j + 1));
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i] ?? '' });
      i++;
      j++;
    } else if (at(i + 1, j) >= at(i, j + 1)) {
      out.push({ kind: 'del', text: a[i] ?? '' });
      i++;
    } else {
      out.push({ kind: 'add', text: b[j] ?? '' });
      j++;
    }
  }
  for (; i < a.length; i++) out.push({ kind: 'del', text: a[i] ?? '' });
  for (; j < b.length; j++) out.push({ kind: 'add', text: b[j] ?? '' });
  return out;
}
