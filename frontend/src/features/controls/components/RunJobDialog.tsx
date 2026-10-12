import { useState } from 'react';

import { newIdempotencyKey } from '@/api/client';
import { isApiError } from '@/api/problem';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import type { JobName } from '@/features/controls/jobs';
import { fieldOfApiError, parameterRows, type FieldErrors } from '@/features/controls/model';
import { requestJobRun } from '@/features/controls/queries';
import { recentRequests } from '@/features/controls/requests-store';
import { controlsCopy } from '@/i18n/controls';
import { notify } from '@/lib/notify';

const copy = controlsCopy;

interface RunJobDialogProps {
  open: boolean;
  job: JobName;
  parameters: Record<string, unknown>;
  onOpenChange: (open: boolean) => void;
  /** A 400 whose `errors[]` name fields of the form: shown under them (§6). */
  onFieldErrors: (errors: FieldErrors) => void;
  /** 422 `job-not-allowed`: the strip above the form, with the API's `detail`. */
  onRefused: (detail: string) => void;
}

/**
 * "Run {job}?" with what it does and the parameters it will send (§6), then E-33. The Idempotency-Key is made when
 * the dialog opens, so a second click after a lost answer is the same request (DOC-31 §8).
 */
export function RunJobDialog({ open, job, parameters, onOpenChange, onFieldErrors, onRefused }: RunJobDialogProps) {
  const [key, setKey] = useState(newIdempotencyKey);
  const [wasOpen, setWasOpen] = useState(open);
  // A new key each time the dialog opens (compared during render, not in an effect).
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) setKey(newIdempotencyKey());
  }
  const rows = parameterRows(parameters);

  const confirm = async () => {
    try {
      const request = await requestJobRun(job, parameters, key);
      recentRequests.add({ id: request.id, jobName: request.jobName, sentAt: Date.now() });
      notify.success(copy.feedback.requestSent);
    } catch (error: unknown) {
      if (isApiError(error) && error.status === 400) {
        const errors: FieldErrors = {};
        for (const entry of error.problem.errors ?? []) {
          const field = fieldOfApiError(entry.field ?? '');
          if (field && entry.message) errors[field] = entry.message;
        }
        if (Object.keys(errors).length > 0) {
          onFieldErrors(errors);
          return;
        }
      }
      if (isApiError(error) && error.slug === 'job-not-allowed') {
        onRefused(error.problem.detail ?? error.problem.title);
        return;
      }
      throw error;
    }
  };

  return (
    <ConfirmDialog
      open={open}
      title={copy.confirm.runTitle(job)}
      description={copy.job.description[job]}
      confirmLabel={copy.job.run}
      onConfirm={confirm}
      onOpenChange={onOpenChange}
    >
      <div className="flex flex-col gap-1.5">
        <p className="text-label font-medium text-foreground-2">{copy.confirm.parameters}</p>
        {rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{copy.confirm.noParameters}</p>
        ) : (
          <table className="w-full rounded-md border border-border text-sm">
            <caption className="sr-only">{copy.confirm.parameters}</caption>
            <tbody>
              {rows.map((row) => (
                <tr key={row.name} className="border-t border-border first:border-t-0">
                  <th scope="row" className="px-3 py-1.5 text-left font-mono text-xs font-normal whitespace-nowrap">
                    {row.name}
                  </th>
                  <td className="px-3 py-1.5 font-mono text-xs break-all">{row.value}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </ConfirmDialog>
  );
}
