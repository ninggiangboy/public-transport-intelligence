import { useQuery, useQueryClient } from '@tanstack/react-query';
import { LoaderCircle } from 'lucide-react';
import { useId, useRef, useState, type ReactNode } from 'react';

import { keys } from '@/api/keys';
import { simSlug, startScenario, type Scenario, type ScenarioParam, type ScenarioRun } from '@/api/sim';
import { RouteSelect } from '@/components/RouteSelect';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import {
  ANY,
  checkScenarioForm,
  initialValues,
  plannedFromForm,
  UNITS,
  watchesDeadLetters,
  type DurationUnit,
  type DurationValue,
  type FieldErrors,
  type FieldValue,
  type FormValues,
} from '@/features/demo/model';
import { deadLetterSummaryQuery, routesQuery } from '@/features/demo/queries';
import { deadLetterBaseline } from '@/features/demo/use-demo-actions';
import { demoCopy } from '@/i18n/demo';
import { en } from '@/i18n/en';
import { describeError } from '@/lib/problem-copy';
import { formatDuration } from '@/lib/format';
import { useReducedMotion } from '@/lib/use-reduced-motion';
import { cn } from '@/lib/utils';

const copy = demoCopy.dialog;
const NUMBER_STEP = { INT: 1, DOUBLE: 0.01 } as const;
/** A duration's amount takes any decimal ("1.5" h). */
const ANY_STEP = 'any';

interface StartDialogProps {
  scenario: Scenario;
  /** "Run again": the parameters of an earlier run (§6). */
  prefill?: Record<string, unknown>;
  onClose: () => void;
  onStarted: (run: ScenarioRun) => void;
}

/**
 * "Start {title}?" (§6.1): a form built from the catalog entry, checked as the catalog says before anything is sent.
 * A 4xx shows in the dialog, which stays open; the simulator still decides (DOC-25 §8).
 */
export function StartDialog({ scenario, prefill, onClose, onStarted }: StartDialogProps) {
  const queryClient = useQueryClient();
  const reducedMotion = useReducedMotion();
  const [values, setValues] = useState<FormValues>(() => initialValues(scenario, prefill));
  const [apiErrors, setApiErrors] = useState<FieldErrors>({});
  const [refusal, setRefusal] = useState<{ title: string; description?: string } | undefined>(undefined);
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  const descriptionId = useId();

  const check = checkScenarioForm(scenario, values);
  const errors: FieldErrors = { ...(check.ok ? {} : check.errors), ...apiErrors };
  const planned = plannedFromForm(scenario, values);

  const change = (name: string, value: FieldValue) => {
    setValues((current) => ({ ...current, [name]: value }));
    if (apiErrors[name]) setApiErrors((current) => ({ ...current, [name]: undefined }));
    setRefusal(undefined);
  };

  const submit = async () => {
    if (!check.ok || inFlight.current) return;
    inFlight.current = true;
    setPending(true);
    setRefusal(undefined);
    try {
      // "+{n} dead letters" counts from the open dead letters just before the start (§4.1).
      const before = watchesDeadLetters(scenario.name)
        ? await queryClient.query({ ...deadLetterSummaryQuery(), staleTime: 0 }).catch(() => undefined)
        : undefined;
      const run = await startScenario(scenario.name, check.body);
      if (before) deadLetterBaseline(run.runId, before.data.open);
      onStarted(run);
      onClose();
    } catch (error: unknown) {
      showError(error);
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  };

  const showError = (error: unknown) => {
    const slug = simSlug(error);
    const described = describeError(error);
    if (slug === 'invalid-param') {
      const fields: FieldErrors = {};
      for (const item of described.fieldErrors) {
        if (scenario.params.some((param) => param.name === item.field)) fields[item.field] = item.message;
      }
      if (Object.keys(fields).length > 0) {
        setApiErrors(fields);
        return;
      }
    }
    if (slug === 'scenario-conflict') setRefusal({ title: demoCopy.errors.conflict(scenario.title) });
    else if (slug === 'no-eligible-vehicles') setRefusal({ title: demoCopy.errors.noEligibleVehicles });
    else if (slug === 'unknown-scenario') {
      setRefusal({ title: demoCopy.errors.unknownScenario });
      void queryClient.invalidateQueries({ queryKey: keys.sim.scenarios() });
    } else {
      setRefusal({ title: described.title, ...(described.description ? { description: described.description } : {}) });
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !pending) onClose();
      }}
    >
      <DialogContent className="w-[min(480px,calc(100vw-2rem))]" aria-describedby={descriptionId}>
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <div className="flex max-h-[min(70vh,640px)] flex-col gap-4 overflow-y-auto p-5">
            <div className="flex flex-col gap-1.5">
              <DialogTitle className="text-panel font-semibold tracking-title">
                {copy.title(scenario.title)}
              </DialogTitle>
              <DialogDescription id={descriptionId} className="text-base text-muted-foreground">
                {scenario.description}
                {planned !== undefined && scenario.params.some((param) => param.name === 'duration')
                  ? ` ${copy.stopsAfter(formatDuration(planned))}`
                  : null}
              </DialogDescription>
            </div>
            {scenario.params.map((param) => (
              <Field
                key={param.name}
                param={param}
                value={values[param.name] ?? ''}
                error={errors[param.name]}
                onChange={(value) => {
                  change(param.name, value);
                }}
              />
            ))}
            {scenario.name === 'load-ramp' && planned !== undefined ? (
              <p className="text-sm text-muted-foreground">{copy.totalDuration(formatDuration(planned))}</p>
            ) : null}
            {refusal ? (
              <div
                role="alert"
                className="rounded-md border border-tone-danger-border bg-tone-danger-bg px-3 py-2 text-sm text-tone-danger-fg"
              >
                <p className="font-semibold">{refusal.title}</p>
                {refusal.description ? <p>{refusal.description}</p> : null}
              </div>
            ) : null}
          </div>
          <div className="flex items-center justify-end gap-2 rounded-b-dialog border-t border-border bg-surface px-5 py-3">
            <Button
              variant="ghost"
              disabled={pending}
              onClick={() => {
                setValues(initialValues(scenario));
                setApiErrors({});
                setRefusal(undefined);
              }}
            >
              {copy.reset}
            </Button>
            <Button variant="outline" disabled={pending} onClick={onClose}>
              {en.common.cancel}
            </Button>
            <Button
              type="submit"
              disabled={!check.ok || pending}
              aria-busy={pending}
              className={cn(pending && 'cursor-progress')}
            >
              {pending ? <LoaderCircle className={cn(!reducedMotion && 'animate-spin')} aria-hidden="true" /> : null}
              {copy.start}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

interface FieldProps {
  param: ScenarioParam;
  value: FieldValue;
  error: string | undefined;
  onChange: (value: FieldValue) => void;
}

/** One field of §6.1, by the parameter's `type`. */
function Field({ param, value, error, onChange }: FieldProps) {
  const id = useId();
  const errorId = useId();
  const listId = useId();
  const describedBy = error ? { 'aria-describedby': errorId } : {};
  const invalid = error !== undefined;
  const text = typeof value === 'string' ? value : '';

  let control: ReactNode;
  switch (param.type) {
    case 'BOOLEAN':
      return (
        <div className="flex items-center justify-between gap-3">
          <label htmlFor={id} className="text-label font-medium text-foreground-2">
            {param.label}
          </label>
          <Switch
            id={id}
            checked={value === true}
            onCheckedChange={(checked) => {
              onChange(checked);
            }}
          />
        </div>
      );
    case 'ENUM_LIST':
      return (
        <fieldset className="flex flex-col gap-1.5" {...describedBy}>
          <legend className="mb-1.5 text-label font-medium text-foreground-2">{param.label}</legend>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
            {(param.options ?? []).map((option) => (
              <CheckOption
                key={option}
                label={option}
                checked={Array.isArray(value) && value.includes(option)}
                onChange={(checked) => {
                  const list = Array.isArray(value) ? value : [];
                  onChange(checked ? [...list, option] : list.filter((item) => item !== option));
                }}
              />
            ))}
          </div>
          <FieldError id={errorId} error={error} />
        </fieldset>
      );
    case 'ROUTE':
      return <RouteField param={param} value={text} error={error} onChange={onChange} />;
    case 'DURATION':
      control = (
        <DurationInput
          id={id}
          label={param.label}
          value={typeof value === 'object' && !Array.isArray(value) ? value : { amount: '', unit: 'min' }}
          invalid={invalid}
          describedBy={describedBy}
          onChange={onChange}
        />
      );
      break;
    case 'ENUM':
      control = (
        <select
          id={id}
          value={text}
          aria-invalid={invalid}
          {...describedBy}
          onChange={(event) => {
            onChange(event.target.value);
          }}
          className="h-8.5 w-full rounded-md border border-input bg-card px-3 text-sm shadow-xs"
        >
          {param.nullable ? (
            <option value={ANY}>{copy.any}</option>
          ) : text === ANY ? (
            <option value={ANY} disabled />
          ) : null}
          {(param.options ?? []).map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      );
      break;
    case 'INT':
    case 'DOUBLE':
      control = (
        <Input
          id={id}
          type="number"
          inputMode={param.type === 'INT' ? 'numeric' : 'decimal'}
          step={NUMBER_STEP[param.type]}
          {...(typeof param.min === 'number' ? { min: param.min } : {})}
          {...(typeof param.max === 'number' ? { max: param.max } : {})}
          value={text}
          aria-invalid={invalid}
          {...describedBy}
          onChange={(event) => {
            onChange(event.target.value);
          }}
        />
      );
      break;
    case 'DOUBLE_LIST':
      control = (
        <Input
          id={id}
          value={text}
          placeholder={copy.listPlaceholder}
          inputMode="decimal"
          aria-invalid={invalid}
          {...describedBy}
          onChange={(event) => {
            onChange(event.target.value);
          }}
        />
      );
      break;
    default:
      control = (
        <>
          <Input
            id={id}
            value={text}
            aria-invalid={invalid}
            {...(param.suggestions?.length ? { list: listId, autoComplete: 'off' } : {})}
            {...describedBy}
            onChange={(event) => {
              onChange(event.target.value);
            }}
          />
          {param.suggestions?.length ? (
            <datalist id={listId}>
              {param.suggestions.map((suggestion) => (
                <option key={suggestion} value={suggestion} />
              ))}
            </datalist>
          ) : null}
        </>
      );
  }

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-label font-medium text-foreground-2">
        {param.label}
      </label>
      {control}
      <FieldError id={errorId} error={error} />
    </div>
  );
}

function FieldError({ id, error }: { id: string; error: string | undefined }) {
  if (!error) return null;
  return (
    <p id={id} className="text-xs text-tone-danger-fg">
      {error}
    </p>
  );
}

function CheckOption({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (c: boolean) => void;
}) {
  const id = useId();
  return (
    <div className="flex items-center gap-2">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(next) => {
          onChange(next === true);
        }}
      />
      <label htmlFor={id} className="font-mono text-xs">
        {label}
      </label>
    </div>
  );
}

/** A number and its unit ("sec", "min", "h"); sent as ISO-8601 (§6.1). */
function DurationInput({
  id,
  label,
  value,
  invalid,
  describedBy,
  onChange,
}: {
  id: string;
  label: string;
  value: DurationValue;
  invalid: boolean;
  describedBy: { 'aria-describedby'?: string };
  onChange: (value: DurationValue) => void;
}) {
  return (
    <div className="flex gap-2">
      <Input
        id={id}
        type="number"
        inputMode="decimal"
        min={0}
        step={ANY_STEP}
        value={value.amount}
        aria-invalid={invalid}
        {...describedBy}
        className="flex-1"
        onChange={(event) => {
          onChange({ ...value, amount: event.target.value });
        }}
      />
      <select
        aria-label={copy.unitLabel(label)}
        value={value.unit}
        onChange={(event) => {
          onChange({ ...value, unit: event.target.value as DurationUnit });
        }}
        className="h-8.5 w-20 rounded-md border border-input bg-card px-2 text-sm shadow-xs"
      >
        {UNITS.map((unit) => (
          <option key={unit} value={unit}>
            {copy.units[unit]}
          </option>
        ))}
      </select>
    </div>
  );
}

/** `ROUTE`: one route of E-01 (§6.1). */
function RouteField({
  param,
  value,
  error,
  onChange,
}: {
  param: ScenarioParam;
  value: string;
  error: string | undefined;
  onChange: (value: string) => void;
}) {
  const routes = useQuery(routesQuery());
  const errorId = useId();
  return (
    <div className="flex flex-col gap-1.5" {...(error ? { 'aria-describedby': errorId } : {})}>
      <span className="text-label font-medium text-foreground-2">{param.label}</span>
      <div>
        <RouteSelect
          routes={routes.data?.data.items ?? []}
          value={value ? [value] : []}
          multiple={false}
          label={param.label}
          placeholder={copy.pickRoute}
          onChange={(ids) => {
            onChange(ids[0] ?? '');
          }}
        />
      </div>
      <FieldError id={errorId} error={error} />
    </div>
  );
}
