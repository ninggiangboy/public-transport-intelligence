import { useId } from 'react';

import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Textarea } from '@/components/ui/textarea';
import { DISCARD_REASONS, REASON_MAX, REASON_MIN, type DiscardReason } from '@/features/dlq/model';
import { dlqCopy } from '@/i18n/dlq';

const copy = dlqCopy.dlq.dialogs;

interface DiscardFieldsProps {
  choice: DiscardReason;
  note: string;
  onChoice: (choice: DiscardReason) => void;
  onNote: (note: string) => void;
}

/** The reason and note of the discard dialog (§6): "Other" needs a note of 3 characters or more. */
export function DiscardFields({ choice, note, onChoice, onNote }: DiscardFieldsProps) {
  const base = useId();
  const noteId = `${base}-note`;
  const other = choice === 'other';
  const short = other && note.trim().length < REASON_MIN;
  return (
    <div className="flex flex-col gap-3">
      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1 text-label font-medium text-foreground-2">{copy.reason}</legend>
        <RadioGroup
          aria-label={copy.reason}
          value={choice}
          onValueChange={(value) => {
            onChoice(value as DiscardReason);
          }}
          className="flex flex-col gap-1.5"
        >
          {DISCARD_REASONS.map((value) => (
            <label key={value} className="flex cursor-pointer items-center gap-2 text-base">
              <RadioGroupItem
                value={value}
                className="grid size-4 place-items-center rounded-full border border-border-strong bg-card data-[state=checked]:border-primary data-[state=checked]:after:size-2 data-[state=checked]:after:rounded-full data-[state=checked]:after:bg-primary"
              />
              {copy.reasons[value]}
            </label>
          ))}
        </RadioGroup>
      </fieldset>
      <div className="flex flex-col gap-1.5">
        <label htmlFor={noteId} className="text-label font-medium text-foreground-2">
          {other ? copy.note : copy.noteOptional}
        </label>
        <Textarea
          id={noteId}
          value={note}
          maxLength={REASON_MAX}
          aria-invalid={short && note.length > 0}
          onChange={(event) => {
            onNote(event.target.value);
          }}
        />
        {short ? <p className="text-xs text-muted-foreground">{copy.noteHint(REASON_MIN)}</p> : null}
      </div>
    </div>
  );
}
