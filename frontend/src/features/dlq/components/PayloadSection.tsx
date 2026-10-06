import { useMemo, useState } from 'react';

import { isApiError } from '@/api/problem';
import { Callout } from '@/components/Callout';
import { JsonEditor } from '@/components/JsonEditor';
import { JsonViewer } from '@/components/JsonViewer';
import { SegmentedControl } from '@/components/SegmentedControl';
import { Button } from '@/components/ui/button';
import {
  byteLength,
  editorText,
  jsonError,
  MAX_PAYLOAD_BYTES,
  payloadFileName,
  sameJson,
  type DeadLetterDetail,
} from '@/features/dlq/model';
import { dlqCopy } from '@/i18n/dlq';
import { describeError } from '@/lib/problem-copy';
import { cn } from '@/lib/utils';

const copy = dlqCopy.dlq;
type Version = 'edited' | 'original';

function parsed(text: string): string | object {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === 'object' && value !== null ? value : text;
  } catch {
    return text;
  }
}

interface PayloadSectionProps {
  detail: DeadLetterDetail;
  editing: boolean;
  /** Whether the replay button follows a save ("Save & replay"). */
  canReplay: boolean;
  onDirty: (dirty: boolean) => void;
  /** E-43; rejects with the Problem. */
  onSave: (text: string) => Promise<unknown>;
  /** The editor closes: saved or cancelled. */
  onClose: (saved: boolean, thenReplay: boolean) => void;
  /** Cancel with changes asks first (§6.2). */
  onCancel: (dirty: boolean) => void;
}

/** The payload as a code block, or the editor in its place (§6.1, §6.2). */
export function PayloadSection({
  detail,
  editing,
  canReplay,
  onDirty,
  onSave,
  onClose,
  onCancel,
}: PayloadSectionProps) {
  const [view, setView] = useState<Version>(detail.hasEditedPayload ? 'edited' : 'original');
  const fileName = payloadFileName(detail.source);
  const initial = useMemo(() => editorText(detail), [detail]);
  const [text, setText] = useState(initial);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<{ error: unknown; errors: { pointer: string; message: string }[] }>();

  const syntax = jsonError(text);
  const tooLarge = byteLength(text) > MAX_PAYLOAD_BYTES;
  const changed = !sameJson(text, initial);
  const canSave = syntax === undefined && !tooLarge && changed && !saving;

  const change = (next: string) => {
    setText(next);
    onDirty(!sameJson(next, initial));
  };

  const save = async (thenReplay: boolean) => {
    setSaving(true);
    setFailure(undefined);
    try {
      await onSave(text);
      setView('edited');
      onDirty(false);
      onClose(true, thenReplay);
    } catch (error: unknown) {
      const described = describeError(error);
      setFailure({
        error,
        errors: described.fieldErrors.map((item) => ({ pointer: item.field, message: item.message })),
      });
    } finally {
      setSaving(false);
    }
  };

  if (!editing) {
    const edited = detail.hasEditedPayload && view === 'edited';
    return (
      <section aria-label={copy.detailPanel.payload} className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-base font-semibold">{copy.detailPanel.payload}</h3>
          {detail.hasEditedPayload ? (
            <SegmentedControl
              size="sm"
              label={copy.detailPanel.view}
              value={view}
              options={[
                { value: 'edited', label: copy.detailPanel.edited },
                { value: 'original', label: copy.detailPanel.original },
              ]}
              onChange={setView}
            />
          ) : null}
        </div>
        {edited && detail.editedPayload ? (
          <JsonViewer value={detail.editedPayload} compareTo={parsed(detail.rawPayload)} fileName={fileName} />
        ) : (
          <JsonViewer value={parsed(detail.rawPayload)} fileName={fileName} />
        )}
      </section>
    );
  }

  const described = failure ? describeError(failure.error) : undefined;
  const serverRefusal = described?.slug === 'pii-not-allowed' || described?.slug === 'business-key-changed';
  return (
    <section aria-label={copy.detailPanel.payload} className="flex flex-col gap-2">
      <h3 className="text-base font-semibold">{copy.detailPanel.payload}</h3>
      {described && serverRefusal ? (
        <Callout tone="danger" title={described.title}>
          {failure && isApiError(failure.error) ? failure.error.problem.detail : undefined}
        </Callout>
      ) : null}
      <JsonEditor
        fileName={fileName}
        value={text}
        ariaLabel={copy.editor.label}
        onChange={change}
        errors={failure?.errors}
      />
      <p
        role="status"
        className={cn(
          'flex items-center gap-1.5 text-xs',
          syntax || tooLarge ? 'text-tone-danger-fg' : 'text-muted-foreground',
        )}
      >
        <span
          aria-hidden="true"
          className={cn('size-2 rounded-full', syntax || tooLarge ? 'bg-tone-danger-solid' : 'bg-tone-success-solid')}
        />
        {tooLarge ? copy.editor.tooLarge : syntax ? copy.editor.invalid(syntax) : copy.editor.valid}
      </p>
      {described && !serverRefusal ? (
        <div
          role="alert"
          className="rounded-md border border-tone-danger-border bg-tone-danger-bg px-3 py-2 text-sm text-tone-danger-fg"
        >
          <p className="font-semibold">{described.slug === 'invalid-payload' ? copy.editor.errors : described.title}</p>
          {described.description && described.slug !== 'invalid-payload' ? <p>{described.description}</p> : null}
          {failure && failure.errors.length > 0 ? (
            <ul className="mt-1 list-disc pl-4">
              {failure.errors.map((item) => (
                <li key={`${item.pointer}-${item.message}`}>
                  <span className="font-mono">{item.pointer}</span>: {item.message}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
      <div className="flex items-center justify-end gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={saving}
          onClick={() => {
            onCancel(changed);
          }}
        >
          {copy.buttons.cancel}
        </Button>
        <Button
          variant={canReplay ? 'outline' : 'default'}
          size="sm"
          disabled={!canSave}
          onClick={() => void save(false)}
        >
          {copy.buttons.save}
        </Button>
        {canReplay ? (
          <Button size="sm" disabled={!canSave} onClick={() => void save(true)}>
            {copy.buttons.saveAndReplay}
          </Button>
        ) : null}
      </div>
    </section>
  );
}
