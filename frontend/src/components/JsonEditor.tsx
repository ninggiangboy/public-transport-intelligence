import { LoaderCircle } from 'lucide-react';
import { useEffect, useMemo, useState, type ComponentType, type ReactNode } from 'react';

import type { CodeMirrorJsonProps } from '@/components/codemirror-json';
import { Button } from '@/components/ui/button';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { lineOfPointer } from '@/lib/json-lines';
import { useReducedMotion } from '@/lib/use-reduced-motion';
import { cn } from '@/lib/utils';

// DOC-35 §5.6: the editable twin of JsonViewer. CodeMirror is 100+ KB, so it loads when the first editor opens and
// never with the first paint (DOC-34 §7). The chunk is asked for here, not through React.lazy, so that "Retry" can ask
// again after a failed load.

const copy = dlqCopy.dlq.editor;

export interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** From a 422 `invalid-payload`: `pointer` is the JSON Pointer of the field, marked on its line in the text. */
  errors?: { pointer: string; message: string }[];
  readOnly?: boolean;
  /** Height in px, 360 by default; the text scrolls inside. */
  height?: number;
  /** The accessible name of the text area. */
  ariaLabel: string;
  /** The name on the bar above the text. */
  fileName: string;
}

type Loaded =
  { state: 'loading' } | { state: 'failed' } | { state: 'ready'; Editor: ComponentType<CodeMirrorJsonProps> };

function Placeholder({ height, children }: { height: number; children: ReactNode }) {
  return (
    <div
      style={{ height }}
      className="flex flex-col items-center justify-center gap-2 bg-code-bg text-sm text-code-bar-fg"
    >
      {children}
    </div>
  );
}

function Loading({ height }: { height: number }) {
  const reducedMotion = useReducedMotion();
  return (
    <Placeholder height={height}>
      <span role="status" className="inline-flex items-center gap-2">
        <LoaderCircle className={cn('size-4', !reducedMotion && 'animate-spin')} aria-hidden="true" />
        {copy.loading}
      </span>
    </Placeholder>
  );
}

/** A dark code block with an editable JSON text (DOC-35 §5.6). */
export function JsonEditor({ fileName, errors, ...editor }: JsonEditorProps) {
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<Loaded>({ state: 'loading' });
  const height = editor.height ?? 360;
  // The lines are those of the text the errors were about, which is the text when they arrive; they go once it changes.
  const diagnostics = useMemo(
    () => errors?.map((error) => ({ line: lineOfPointer(editor.value, error.pointer), message: error.message })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [errors],
  );

  useEffect(() => {
    let current = true;
    import('@/components/codemirror-json').then(
      (module) => {
        if (current) setLoaded({ state: 'ready', Editor: module.default });
      },
      () => {
        if (current) setLoaded({ state: 'failed' });
      },
    );
    return () => {
      current = false;
    };
  }, [attempt]);

  return (
    <div className="overflow-hidden rounded-lg border border-code-border bg-code-bg font-mono text-xs text-code-fg">
      <div className="flex items-center justify-between border-b border-code-border px-3 py-1.5 text-code-bar-fg">
        <span>{fileName}</span>
      </div>
      {loaded.state === 'ready' ? (
        <loaded.Editor {...editor} {...(diagnostics ? { diagnostics } : {})} />
      ) : loaded.state === 'failed' ? (
        <Placeholder height={height}>
          <p>{copy.loadFailed}</p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setLoaded({ state: 'loading' });
              setAttempt((n) => n + 1);
            }}
          >
            {en.common.retry}
          </Button>
        </Placeholder>
      ) : (
        <Loading height={height} />
      )}
    </div>
  );
}
