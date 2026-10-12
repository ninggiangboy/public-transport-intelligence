import { Plus, X } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';

import { Callout } from '@/components/Callout';
import { Card } from '@/components/Card';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { RecentRequests } from '@/features/controls/components/RecentRequests';
import { RunJobDialog } from '@/features/controls/components/RunJobDialog';
import { DETECTORS, isJobName, JOBS, type Detector } from '@/features/controls/jobs';
import {
  checkJob,
  emptyForm,
  serviceDatesOf,
  type FieldErrors,
  type JobClock,
  type JobField,
  type JobForm,
} from '@/features/controls/model';
import { controlsCopy } from '@/i18n/controls';
import { en } from '@/i18n/en';
import { formatServiceDate } from '@/lib/time';

const copy = controlsCopy;
const field = copy.job.field;

interface RunJobCardProps {
  operator: boolean;
  clock: JobClock;
  /** `clockOffset` of E-60 is not PT0S: the times of the form are on the simulated clock (§6.1). */
  simulatedClock: boolean;
}

/** "Run a job" (§6.1): a form that follows the chosen job, checked as E-33 checks it, and the recent requests. */
export function RunJobCard({ operator, clock, simulatedClock }: RunJobCardProps) {
  return (
    <Card title={copy.sections.runJob}>
      <div className="flex flex-col gap-4">
        {operator ? <RunJobForm clock={clock} simulatedClock={simulatedClock} /> : null}
        <RecentRequests />
      </div>
    </Card>
  );
}

function RunJobForm({ clock, simulatedClock }: Omit<RunJobCardProps, 'operator'>) {
  const jobId = useId();
  const descriptionId = useId();
  const [form, setForm] = useState<JobForm>(() => emptyForm('OtpScorecardJob'));
  const [apiErrors, setApiErrors] = useState<FieldErrors>({});
  const [refused, setRefused] = useState<string | undefined>(undefined);
  const [asking, setAsking] = useState(false);
  const check = checkJob(form, clock);
  const errors: FieldErrors = { ...check.errors, ...apiErrors };

  const change = (patch: Partial<JobForm>, fields: JobField[]) => {
    setForm((current) => ({ ...current, ...patch }));
    if (fields.some((name) => apiErrors[name])) {
      setApiErrors((current) =>
        Object.fromEntries(Object.entries(current).filter(([name]) => !fields.includes(name as JobField))),
      );
    }
    setRefused(undefined);
  };

  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (check.parameters) setAsking(true);
      }}
    >
      {refused ? (
        <Callout tone="danger" title={en.error.slug['job-not-allowed'].title()}>
          {refused}
        </Callout>
      ) : null}
      <div className="flex flex-col gap-1.5">
        <label htmlFor={jobId} className="text-label font-medium text-foreground-2">
          {copy.job.label}
        </label>
        <select
          id={jobId}
          value={form.job}
          aria-describedby={descriptionId}
          onChange={(event) => {
            const job = event.target.value;
            if (!isJobName(job)) return;
            // Another job: its own fields, from scratch (§6).
            setForm(emptyForm(job));
            setApiErrors({});
            setRefused(undefined);
          }}
          className="h-8.5 w-full max-w-sm rounded-md border border-input bg-card px-3 font-mono text-sm shadow-xs"
        >
          {JOBS.map((job) => (
            <option key={job} value={job}>
              {job}
            </option>
          ))}
        </select>
        <p id={descriptionId} className="text-sm text-muted-foreground">
          {copy.job.description[form.job]}
        </p>
      </div>

      <JobFields form={form} clock={clock} errors={errors} simulatedClock={simulatedClock} onChange={change} />

      <div>
        <Button type="submit" disabled={!check.parameters}>
          {copy.job.run}
        </Button>
      </div>

      <RunJobDialog
        open={asking}
        job={form.job}
        parameters={check.parameters ?? {}}
        onOpenChange={setAsking}
        onFieldErrors={setApiErrors}
        onRefused={setRefused}
      />
    </form>
  );
}

interface JobFieldsProps {
  form: JobForm;
  clock: JobClock;
  errors: FieldErrors;
  simulatedClock: boolean;
  onChange: (patch: Partial<JobForm>, fields: JobField[]) => void;
}

function JobFields({ form, clock, errors, simulatedClock, onChange }: JobFieldsProps) {
  switch (form.job) {
    case 'GtfsStaticLoadJob':
      return (
        <>
          <TextField
            label={field.sourceUri}
            hint={field.sourceUriHint}
            value={form.sourceUri}
            error={errors.sourceUri}
            placeholder={field.sourceUriPlaceholder}
            onChange={(sourceUri) => {
              onChange({ sourceUri }, ['sourceUri']);
            }}
          />
          <CheckField
            label={field.allowReactivate}
            checked={form.allowReactivate}
            onChange={(allowReactivate) => {
              onChange({ allowReactivate }, []);
            }}
          />
        </>
      );
    case 'EtaAggregationJob':
      return (
        <>
          <TextField
            label={field.hour}
            hint={field.hourHint}
            type="datetime-local"
            step={3600}
            value={form.hour}
            error={errors.hour}
            onChange={(hour) => {
              onChange({ hour }, ['hour']);
            }}
          />
          {simulatedClock ? <BusinessTimeNote /> : null}
          <CheckField
            label={field.force}
            checked={form.force}
            onChange={(force) => {
              onChange({ force }, []);
            }}
          />
        </>
      );
    case 'OtpScorecardJob':
      return (
        <ServiceDates
          dates={serviceDatesOf(form, clock)}
          error={errors.serviceDates}
          onChange={(serviceDates) => {
            onChange({ serviceDates }, ['serviceDates']);
          }}
        />
      );
    case 'AnalyticsRecomputeJob':
      return (
        <>
          <Detectors
            value={form.detectors}
            error={errors.detectors}
            onChange={(detectors) => {
              onChange({ detectors }, ['detectors']);
            }}
          />
          <div className="grid max-w-xl gap-3 sm:grid-cols-2">
            <TextField
              label={field.from}
              type="datetime-local"
              value={form.from}
              error={errors.fromTs}
              onChange={(from) => {
                onChange({ from }, ['fromTs', 'toTs']);
              }}
            />
            <TextField
              label={field.to}
              type="datetime-local"
              value={form.to}
              error={errors.toTs}
              onChange={(to) => {
                onChange({ to }, ['fromTs', 'toTs']);
              }}
            />
          </div>
          {simulatedClock ? <BusinessTimeNote /> : null}
        </>
      );
    case 'PartitionMaintenanceJob':
      return null;
  }
}

function BusinessTimeNote() {
  return <p className="text-xs text-muted-foreground">{field.businessTime}</p>;
}

function FieldError({ id, error }: { id: string; error: string | undefined }) {
  if (!error) return null;
  return (
    <p id={id} className="text-xs text-tone-danger-fg">
      {error}
    </p>
  );
}

interface TextFieldProps {
  label: string;
  value: string;
  error: string | undefined;
  hint?: string;
  type?: 'text' | 'datetime-local';
  step?: number;
  placeholder?: string;
  /** Beside the input, e.g. the form's button. */
  trailing?: ReactNode;
  onChange: (value: string) => void;
}

export function TextField({
  label,
  value,
  error,
  hint,
  type = 'text',
  step,
  placeholder,
  trailing,
  onChange,
}: TextFieldProps) {
  const id = useId();
  const hintId = useId();
  const errorId = useId();
  const describedBy = [hint ? hintId : undefined, error ? errorId : undefined].filter(Boolean).join(' ');
  return (
    <div className="flex max-w-xl flex-col gap-1.5">
      <label htmlFor={id} className="text-label font-medium text-foreground-2">
        {label}
      </label>
      <div className="flex items-center gap-2">
        <Input
          id={id}
          type={type}
          value={value}
          {...(step === undefined ? {} : { step })}
          {...(placeholder === undefined ? {} : { placeholder })}
          aria-invalid={error !== undefined}
          {...(describedBy ? { 'aria-describedby': describedBy } : {})}
          onChange={(event) => {
            onChange(event.target.value);
          }}
        />
        {trailing}
      </div>
      {hint ? (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      <FieldError id={errorId} error={error} />
    </div>
  );
}

function CheckField({
  label,
  checked,
  onChange,
}: {
  label: ReactNode;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <div className="flex items-center gap-2">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(value) => {
          onChange(value === true);
        }}
      />
      <label htmlFor={id} className="text-sm">
        {label}
      </label>
    </div>
  );
}

function ServiceDates({
  dates,
  error,
  onChange,
}: {
  dates: string[];
  error: string | undefined;
  onChange: (dates: string[]) => void;
}) {
  const inputId = useId();
  const errorId = useId();
  const [draft, setDraft] = useState('');
  const add = () => {
    if (draft === '' || dates.includes(draft)) return;
    onChange([...dates, draft].sort());
    setDraft('');
  };
  return (
    <fieldset className="flex flex-col gap-1.5" {...(error ? { 'aria-describedby': errorId } : {})}>
      <legend className="mb-1.5 text-label font-medium text-foreground-2">{field.serviceDates}</legend>
      <div className="flex flex-wrap items-center gap-2">
        {dates.map((date) => (
          <span
            key={date}
            className="inline-flex h-7 items-center gap-1 rounded-full border border-border bg-surface pr-1 pl-2.5 text-sm"
          >
            <time dateTime={date}>{formatServiceDate(date)}</time>
            <button
              type="button"
              aria-label={field.removeDate(formatServiceDate(date))}
              className="inline-flex size-5 items-center justify-center rounded-full hover:bg-muted"
              onClick={() => {
                onChange(dates.filter((item) => item !== date));
              }}
            >
              <X className="size-3.5" aria-hidden="true" />
            </button>
          </span>
        ))}
        <label htmlFor={inputId} className="sr-only">
          {field.addDate}
        </label>
        <Input
          id={inputId}
          type="date"
          value={draft}
          className="w-40"
          onChange={(event) => {
            setDraft(event.target.value);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              add();
            }
          }}
        />
        <Button variant="outline" size="sm" disabled={draft === ''} onClick={add}>
          <Plus aria-hidden="true" />
          {field.addDate}
        </Button>
      </div>
      <FieldError id={errorId} error={error} />
    </fieldset>
  );
}

function Detectors({
  value,
  error,
  onChange,
}: {
  value: Detector[];
  error: string | undefined;
  onChange: (value: Detector[]) => void;
}) {
  const errorId = useId();
  return (
    <fieldset className="flex flex-col gap-1.5" {...(error ? { 'aria-describedby': errorId } : {})}>
      <legend className="mb-1.5 text-label font-medium text-foreground-2">{field.detectors}</legend>
      <div className="flex flex-wrap gap-4">
        {DETECTORS.map((detector) => (
          <CheckField
            key={detector}
            label={copy.job.detector[detector]}
            checked={value.includes(detector)}
            onChange={(checked) => {
              onChange(checked ? [...value, detector] : value.filter((item) => item !== detector));
            }}
          />
        ))}
      </div>
      <FieldError id={errorId} error={error} />
    </fieldset>
  );
}
