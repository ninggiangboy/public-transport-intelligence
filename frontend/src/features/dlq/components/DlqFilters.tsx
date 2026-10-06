import { useId } from 'react';

import { FilterChip } from '@/components/FilterChip';
import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { SegmentedControl } from '@/components/SegmentedControl';
import { TimeRangePicker } from '@/components/TimeRangePicker';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ACTIONS, CATEGORIES, RULES, SOURCES, STAGES, tabStatuses } from '@/features/dlq/model';
import { ACTION_WINDOWS, ACTOR_TYPES, type DlqSearch, type DlqTab } from '@/features/dlq/search';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';

const copy = dlqCopy.dlq;
/** E-40's range has no maximum (§2); the picker still wants a number. */
const NO_LIMIT_SECONDS = 10 * 365 * 86_400;
const SEVERITIES = [2, 1, 0] as const;

interface DlqFiltersProps {
  tab: DlqTab;
  search: DlqSearch;
  onSearch: (change: Partial<DlqSearch>) => void;
}

function listOf<T extends string | number>(value: T[]): T[] | undefined {
  return value.length > 0 ? value : undefined;
}

function CheckGroup({
  title,
  options,
  value,
  onChange,
}: {
  title: string;
  options: { value: string; label: string }[];
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const baseId = useId();
  return (
    <fieldset className="min-w-0">
      <legend className="px-2 pb-1 text-label font-medium text-muted-foreground">{title}</legend>
      <ul className="flex max-h-56 flex-col overflow-y-auto">
        {options.map((option, index) => {
          const id = `${baseId}-${index}`;
          return (
            <li key={option.value} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-base hover:bg-muted">
              <Checkbox
                id={id}
                checked={value.includes(option.value)}
                onCheckedChange={(checked) => {
                  onChange(checked === true ? [...value, option.value] : value.filter((item) => item !== option.value));
                }}
              />
              <label htmlFor={id} className="flex-1 cursor-pointer">
                {option.label}
              </label>
            </li>
          );
        })}
      </ul>
    </fieldset>
  );
}

/** "Error": the stage and the rule in one popover (§4). */
function ErrorFilter({
  stage,
  rule,
  onChange,
}: {
  stage: string[];
  rule: string[];
  onChange: (change: { stage: string[]; rule: string[] }) => void;
}) {
  const count = stage.length + rule.length;
  return (
    <Popover>
      <PopoverTrigger asChild>
        <FilterChip
          active={count > 0}
          text={count > 0 ? en.filters.chip(copy.filters.error, en.filters.selected(count)) : copy.filters.error}
        />
      </PopoverTrigger>
      <PopoverContent aria-label={copy.filters.error} className="flex w-[22rem] gap-2">
        <CheckGroup
          title={copy.filters.stage}
          options={STAGES.map((value) => ({ value, label: copy.stage[value] ?? value }))}
          value={stage}
          onChange={(next) => {
            onChange({ stage: next, rule });
          }}
        />
        <CheckGroup
          title={copy.filters.rule}
          options={RULES.map((value) => ({ value, label: `${value} · ${copy.dq[value] ?? ''}` }))}
          value={rule}
          onChange={(next) => {
            onChange({ stage, rule: next });
          }}
        />
        {count > 0 ? (
          <div className="self-end">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                onChange({ stage: [], rule: [] });
              }}
            >
              {en.filters.clearOne(copy.filters.error)}
            </Button>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/** The chips of the tab (§4): which ones show depends on the tab, and each writes its own URL param. */
export function DlqFilters({ tab, search, onSearch }: DlqFiltersProps) {
  if (tab === 'actions') {
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        <MultiSelectFilter
          label={copy.filters.action}
          options={ACTIONS.map((value) => ({ value, label: copy.action[value] ?? value }))}
          value={search.action ?? []}
          onChange={(value) => {
            onSearch({ action: listOf(value) });
          }}
        />
        <SegmentedControl
          size="sm"
          label={copy.filters.actor}
          value={search.actor ?? 'all'}
          options={[
            { value: 'all', label: en.filters.all },
            ...ACTOR_TYPES.map((value) => ({ value, label: copy.actor[value] })),
          ]}
          onChange={(value) => {
            onSearch({ actor: value === 'all' ? undefined : value });
          }}
        />
        <SegmentedControl
          size="sm"
          label={copy.filters.window}
          value={search.window ?? '24h'}
          options={ACTION_WINDOWS.map((value) => ({ value, label: copy.window[value] }))}
          onChange={(value) => {
            onSearch({ window: value === '24h' ? undefined : value });
          }}
        />
      </div>
    );
  }

  const confirm = tab === 'confirm';
  const group = tabStatuses(tab);
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <MultiSelectFilter
        label={copy.filters.source}
        options={SOURCES.map((value) => ({ value, label: en.source[value] }))}
        value={search.source ?? []}
        onChange={(value) => {
          onSearch({ source: listOf(value) });
        }}
      />
      {confirm ? null : (
        <ErrorFilter
          stage={search.stage ?? []}
          rule={search.rule ?? []}
          onChange={({ stage, rule }) => {
            onSearch({ stage: listOf(stage), rule: listOf(rule) });
          }}
        />
      )}
      <MultiSelectFilter
        label={copy.filters.verdict}
        options={CATEGORIES.map((value) => ({ value, label: copy.category[value] ?? value }))}
        value={search.category ?? []}
        onChange={(value) => {
          onSearch({ category: listOf(value) });
        }}
      />
      {confirm ? null : (
        <>
          <MultiSelectFilter
            label={copy.filters.severity}
            options={SEVERITIES.map((value) => ({ value, label: en.severity[value] }))}
            value={search.severity ?? []}
            onChange={(value) => {
              onSearch({ severity: listOf(value) });
            }}
          />
          <MultiSelectFilter
            label={copy.filters.status}
            options={group.map((value) => ({ value, label: en.status.dlq[value as keyof typeof en.status.dlq] }))}
            value={(search.status ?? []).filter((status) => group.includes(status))}
            onChange={(value) => {
              onSearch({ status: listOf(value) });
            }}
          />
          <TimeRangePicker
            value={{ from: search.from, to: search.to }}
            presets={[]}
            maxRangeSeconds={NO_LIMIT_SECONDS}
            granularity="minute"
            anyTime={copy.filters.anyTime}
            onChange={(value) => {
              onSearch({ from: value.from, to: value.to });
            }}
          />
        </>
      )}
    </div>
  );
}
