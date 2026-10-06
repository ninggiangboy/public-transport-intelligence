import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { json, jsonParseLinter } from '@codemirror/lang-json';
import { bracketMatching, HighlightStyle, indentOnInput, syntaxHighlighting } from '@codemirror/language';
import { forceLinting, linter, lintGutter, type Diagnostic } from '@codemirror/lint';
import { EditorState } from '@codemirror/state';
import { drawSelection, EditorView, gutter, GutterMarker, highlightActiveLine, keymap } from '@codemirror/view';
import { tags } from '@lezer/highlight';
import { useEffect, useRef } from 'react';

// The CodeMirror 6 side of JsonEditor, loaded on its own (DOC-34 §7: 130 KB budget). It carries everything of the
// library, so nothing else may import it statically; the name of this file is how scripts/check-bundle.mjs finds it.

export interface CodeMirrorJsonProps {
  value: string;
  onChange: (text: string) => void;
  /** Errors the API pinned to a line (JsonEditor turns a JSON Pointer into a line, DS-08), drawn like the linter's own. */
  diagnostics?: { line: number; message: string }[];
  ariaLabel: string;
  /** Height in px of the editor; the text scrolls inside. */
  height?: number;
  readOnly?: boolean;
}

// Colours come from the tokens of the dark code block (DOC-35 §5.6), which both themes share.
const theme = EditorView.theme(
  {
    '&': { color: 'var(--code-fg)', backgroundColor: 'var(--code-bg)', fontSize: '12px' },
    '.cm-content': { fontFamily: 'var(--font-mono)', caretColor: 'var(--code-fg)', padding: '8px 0' },
    '&.cm-focused': { outline: '2px solid var(--ring)', outlineOffset: '-2px' },
    '.cm-gutters': { backgroundColor: 'var(--code-bg)', color: 'var(--code-line-number)', border: 'none' },
    '.cm-lineNumbers .cm-gutterElement': { minWidth: '2.5em', paddingRight: '12px', textAlign: 'right' },
    '.cm-lineNumbers .cm-gutterElement span::before': { content: 'attr(data-line)' },
    '.cm-activeLine': { backgroundColor: 'rgb(255 255 255 / 4%)' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--code-bar-fg)' },
    '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'rgb(159 165 247 / 28%)' },
    '.cm-cursor': { borderLeftColor: 'var(--code-fg)' },
    '.cm-scroller': { lineHeight: '20px', overflow: 'auto' },
    '.cm-lintRange-error': { backgroundImage: 'none', textDecoration: 'underline wavy var(--code-literal)' },
    '.cm-tooltip': {
      backgroundColor: 'var(--code-bg)',
      color: 'var(--code-fg)',
      border: '1px solid var(--code-border)',
    },
  },
  { dark: true },
);

/**
 * A line number as generated content, the way JsonViewer draws it: the colour of the prototype is 2.4:1 on the code
 * block, and passes the contrast check only because it is not text that is read, selected or copied (DOC-35 §5.6).
 */
class LineNumber extends GutterMarker {
  constructor(private readonly line: number) {
    super();
  }
  override eq(other: LineNumber) {
    return other.line === this.line;
  }
  override toDOM() {
    const mark = document.createElement('span');
    mark.dataset.line = String(this.line);
    return mark;
  }
}
const lineNumbers = gutter({
  class: 'cm-lineNumbers',
  lineMarker: (view, line) => new LineNumber(view.state.doc.lineAt(line.from).number),
  lineMarkerChange: (update) => update.docChanged,
  initialSpacer: () => new LineNumber(0),
});

const highlight = HighlightStyle.define([
  { tag: tags.propertyName, color: 'var(--code-key)' },
  { tag: tags.string, color: 'var(--code-string)' },
  { tag: [tags.number, tags.integer, tags.float], color: 'var(--code-number)' },
  { tag: [tags.bool, tags.null, tags.keyword], color: 'var(--code-literal)' },
  { tag: [tags.punctuation, tags.separator, tags.squareBracket, tags.brace], color: 'var(--code-punctuation)' },
]);

/** A JSON editor with line numbers, a client-side lint, undo and the bracket helpers. */
export default function CodeMirrorJson({
  value,
  onChange,
  diagnostics,
  ariaLabel,
  height = 360,
  readOnly = false,
}: CodeMirrorJsonProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | undefined>(undefined);
  const onChangeRef = useRef(onChange);
  // What the API said about the text it was sent: shown with the syntax errors for as long as the text is the same.
  const remote = useRef<{ doc: string; marks: Diagnostic[] }>({ doc: '', marks: [] });
  useEffect(() => {
    onChangeRef.current = onChange;
  });

  useEffect(() => {
    if (!host.current) return;
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers,
          history(),
          drawSelection(),
          indentOnInput(),
          bracketMatching(),
          highlightActiveLine(),
          json(),
          linter(
            (editor) => {
              const { doc, marks } = remote.current;
              return [...jsonParseLinter()(editor), ...(editor.state.doc.toString() === doc ? marks : [])];
            },
            { delay: 200 },
          ),
          lintGutter(),
          syntaxHighlighting(highlight),
          theme,
          keymap.of([...defaultKeymap, ...historyKeymap]),
          EditorView.contentAttributes.of({ 'aria-label': ariaLabel, 'aria-multiline': 'true', tabindex: '0' }),
          EditorState.readOnly.of(readOnly),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onChangeRef.current(update.state.doc.toString());
          }),
        ],
      }),
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = undefined;
    };
    // The editor is created once with the text it is given; `value` is followed by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A `value` that is not what the editor holds (the parent set it) replaces the text; typing echoes back unchanged.
  useEffect(() => {
    const editor = view.current;
    if (editor && editor.state.doc.toString() !== value) {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
    }
  }, [value]);

  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const lines = editor.state.doc.lines;
    const marks: Diagnostic[] = (diagnostics ?? []).map(({ line, message }) => {
      const at = editor.state.doc.line(Math.min(Math.max(line, 1), lines));
      return { from: at.from, to: at.to, severity: 'error', message };
    });
    remote.current = { doc: editor.state.doc.toString(), marks };
    forceLinting(editor);
  }, [diagnostics]);

  return <div ref={host} style={{ height }} className="overflow-hidden [&_.cm-editor]:h-full" />;
}
