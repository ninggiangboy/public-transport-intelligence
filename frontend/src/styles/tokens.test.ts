/// <reference types="node" />
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

import { describe, expect, it } from 'vitest';

// Vitest runs in frontend/, the folder of package.json.
const SRC = join(process.cwd(), 'src');
const DOC = join(process.cwd(), '../docs/08-ux-ui/design-system.md');
const TOKENS = readFileSync(join(SRC, 'styles/tokens.css'), 'utf8');

/** Declarations of the rule that ends its selector list with `selector`; `.light` is the second selector of :root. */
function block(selector: string): string {
  const start = TOKENS.indexOf(`${selector} {`);
  expect(start, `${selector} block`).toBeGreaterThanOrEqual(0);
  const open = TOKENS.indexOf('{', start);
  return TOKENS.slice(open + 1, TOKENS.indexOf('\n}', open));
}

function declared(css: string): Set<string> {
  return new Set([...css.matchAll(/^\s*(--[a-z0-9-]+):/gm)].map((m) => m[1] ?? ''));
}

/** Every token of DOC-35 §3, read from the document so that the test follows it. */
function tokensOfSection3(): string[] {
  const doc = readFileSync(DOC, 'utf8');
  const section = doc.slice(doc.indexOf('\n## 3. '), doc.indexOf('\n## 4. '));
  const names = new Set<string>();
  for (const match of section.matchAll(/`(--[a-z0-9-]+)`/g)) {
    const name = match[1] ?? '';
    if (!name.endsWith('-')) names.add(name);
  }
  for (const tone of ['neutral', 'info', 'success', 'teal', 'warning', 'danger', 'progress']) {
    for (const part of ['bg', 'fg', 'border', 'solid']) names.add(`--tone-${tone}-${part}`);
  }
  for (let i = 1; i <= 8; i++) names.add(`--chart-${i}`);
  for (let i = 1; i <= 7; i++) names.add(`--heat-${i}`);
  for (const map of ['land', 'water', 'park', 'block', 'road', 'casing', 'label']) names.add(`--map-${map}`);
  return [...names];
}

describe('DS-11 design tokens', () => {
  const light = declared(block('.light'));
  const dark = declared(block('.dark'));
  const tokens = tokensOfSection3();

  it('reads a plausible number of tokens from the document', () => {
    expect(tokens.length).toBeGreaterThan(70);
    expect(tokens).toContain('--background');
    expect(tokens).toContain('--tone-warning-fg');
    expect(tokens).toContain('--bunching-soft');
  });

  it.each(tokens)('%s has a value in :root and in .dark', (token) => {
    expect(light.has(token), `${token} in :root`).toBe(true);
    expect(dark.has(token), `${token} in .dark`).toBe(true);
  });

  it('has the static tokens of DOC-35 §4', () => {
    for (const token of [
      '--radius-sm',
      '--radius-md',
      '--radius-lg',
      '--radius-xl',
      '--shadow-xs',
      '--shadow-lg',
      '--font-sans',
      '--font-mono',
    ]) {
      expect(declared(TOKENS).has(token), token).toBe(true);
    }
    expect(declared(block('.dark')).has('--shadow-md')).toBe(true);
  });

  it('respects prefers-reduced-motion (DS-09)', () => {
    const globals = readFileSync(join(SRC, 'styles/globals.css'), 'utf8');
    expect(globals).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*transition-duration/);
  });

  it('has no hex colour outside tokens.css', () => {
    const files: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir)) {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) walk(path);
        else if (/\.(ts|tsx|css|html)$/.test(entry)) files.push(path);
      }
    };
    walk(SRC);
    const skipped = ['styles/tokens.css', 'routeTree.gen.ts', 'api/generated/'];
    const offenders = files
      .filter((file) => !skipped.some((s) => relative(SRC, file).replaceAll('\\', '/').includes(s)))
      .flatMap((file) =>
        readFileSync(file, 'utf8')
          .split('\n')
          .flatMap((line, index) =>
            /(?<![\w&])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b/.test(line)
              ? [`${relative(SRC, file)}:${index + 1}`]
              : [],
          ),
      );
    expect(offenders).toEqual([]);
  });
});
