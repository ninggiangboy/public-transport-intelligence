import { Plus, X } from 'lucide-react';
import { useId, useState, type SyntheticEvent } from 'react';

import { FilterChip } from '@/components/FilterChip';
import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { CATEGORIES, TRIGGERS, type TicketingSearch, type Trigger } from '@/features/ticketing/search';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';
import { ticketingCopy } from '@/i18n/ticketing';
import { cn } from '@/lib/utils';

const copy = ticketingCopy.filters;
const ANY = 'any';

interface TicketingFiltersProps {
  search: TicketingSearch;
  /** Sale points of the loaded rows, offered as suggestions (§4). */
  salePoints: { id: string; name?: string }[];
  onSearch: (change: Partial<TicketingSearch>) => void;
}

/** Category, Severity, Trigger and "+ Sale point" (§4); every change goes to the URL. */
export function TicketingFilters({ search, salePoints, onSearch }: TicketingFiltersProps) {
  const trigger = search.trigger;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <MultiSelectFilter
        label={copy.category}
        options={CATEGORIES.map((value) => ({
          value,
          label: alertsCopy.ticketingCategory[value] ?? value,
        }))}
        value={search.category ?? []}
        onChange={(value) => {
          onSearch({ category: value.length > 0 ? value : undefined });
        }}
      />
      <MultiSelectFilter
        label={copy.severity}
        options={[2, 1, 0].map((value) => ({ value, label: en.severity[value as 0 | 1 | 2] }))}
        value={search.severity ?? []}
        onChange={(value) => {
          onSearch({ severity: value.length > 0 ? value : undefined });
        }}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <FilterChip
            active={trigger !== undefined}
            text={
              trigger === undefined
                ? copy.trigger
                : en.filters.chip(copy.trigger, alertsCopy.ticketingTrigger[trigger] ?? trigger)
            }
          />
        </DropdownMenuTrigger>
        <DropdownMenuContent aria-label={copy.trigger}>
          <DropdownMenuRadioGroup
            value={trigger ?? ANY}
            onValueChange={(value) => {
              onSearch({ trigger: value === ANY ? undefined : (value as Trigger) });
            }}
          >
            <DropdownMenuRadioItem value={ANY}>{copy.anyTrigger}</DropdownMenuRadioItem>
            {TRIGGERS.map((value) => (
              <DropdownMenuRadioItem key={value} value={value}>
                {alertsCopy.ticketingTrigger[value] ?? value}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <SalePointFilter
        value={search.salePoint}
        salePoints={salePoints}
        onChange={(salePoint) => {
          onSearch({ salePoint });
        }}
      />
    </div>
  );
}

/** A dashed "+ Sale point" chip; its popover suggests the loaded sale points and takes any id typed (§4). */
function SalePointFilter({
  value,
  salePoints,
  onChange,
}: {
  value?: string;
  salePoints: { id: string; name?: string }[];
  onChange: (salePoint: string | undefined) => void;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const inputId = useId();

  if (value !== undefined) {
    return (
      <span className="inline-flex h-7 items-center gap-1 rounded-full border border-foreground bg-foreground pr-1 pl-3 text-label font-medium text-card">
        <span className="max-w-48 truncate">{copy.salePointActive(value)}</span>
        <button
          type="button"
          aria-label={en.filters.clearOne(copy.salePointLabel)}
          onClick={() => {
            onChange(undefined);
          }}
          className="grid size-5 place-items-center rounded-full hover:bg-card/20"
        >
          <X className="size-3.5" aria-hidden="true" />
        </button>
      </span>
    );
  }

  const query = text.trim().toLowerCase();
  const suggestions = salePoints
    .filter((point) => !query || point.id.toLowerCase().includes(query) || point.name?.toLowerCase().includes(query))
    .slice(0, 8);
  const pick = (id: string) => {
    setOpen(false);
    setText('');
    onChange(id);
  };
  const submit = (event: SyntheticEvent) => {
    event.preventDefault();
    if (text.trim()) pick(text.trim());
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="inline-flex h-7 items-center gap-1 rounded-full border border-dashed border-border-strong bg-card px-3 text-label font-medium text-foreground-2 hover:bg-muted"
        >
          <Plus className="size-3.5" aria-hidden="true" />
          {copy.salePointLabel}
        </button>
      </PopoverTrigger>
      <PopoverContent aria-label={copy.salePointLabel} className="w-72">
        <form onSubmit={submit} className="flex gap-1.5">
          <label htmlFor={inputId} className="sr-only">
            {copy.salePointLabel}
          </label>
          <Input
            id={inputId}
            value={text}
            placeholder={copy.salePointPlaceholder}
            autoComplete="off"
            onChange={(event) => {
              setText(event.target.value);
            }}
          />
          <Button type="submit" size="sm" disabled={!text.trim()}>
            {copy.apply}
          </Button>
        </form>
        {suggestions.length > 0 ? (
          <ul className="mt-1.5 flex max-h-64 flex-col overflow-y-auto">
            {suggestions.map((point) => (
              <li key={point.id}>
                <button
                  type="button"
                  onClick={() => {
                    pick(point.id);
                  }}
                  className={cn(
                    'flex w-full items-baseline gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted',
                  )}
                >
                  <span className="min-w-0 flex-1 truncate">{point.name ?? point.id}</span>
                  <span className="font-mono text-xs text-muted-foreground">{point.id}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}
