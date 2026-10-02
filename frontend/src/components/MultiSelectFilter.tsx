import { useId } from 'react';

import { FilterChip } from '@/components/FilterChip';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { en } from '@/i18n/en';
import { formatCount } from '@/lib/format';

interface MultiSelectFilterProps<V extends string | number> {
  label: string;
  options: { value: V; label: string; count?: number }[];
  value: V[];
  onChange: (value: V[]) => void;
}

/** Filter chip that opens a list of checkboxes (DOC-35 §5.3). Empty selection means "no filter". */
export function MultiSelectFilter<V extends string | number>({
  label,
  options,
  value,
  onChange,
}: MultiSelectFilterProps<V>) {
  const baseId = useId();
  const selected = options.filter((option) => value.includes(option.value));
  const text =
    selected.length === 0
      ? label
      : en.filters.chip(
          label,
          selected.length <= 2
            ? selected.map((option) => option.label).join(', ')
            : en.filters.selected(selected.length),
        );

  const toggle = (option: V, checked: boolean) => {
    onChange(checked ? [...value, option] : value.filter((item) => item !== option));
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        <FilterChip active={selected.length > 0} text={text} />
      </PopoverTrigger>
      <PopoverContent aria-label={label}>
        <ul className="flex max-h-64 flex-col overflow-y-auto">
          {options.map((option, index) => {
            const id = `${baseId}-${index}`;
            return (
              <li
                key={option.value}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 text-base hover:bg-muted"
              >
                <Checkbox
                  id={id}
                  checked={value.includes(option.value)}
                  onCheckedChange={(checked) => {
                    toggle(option.value, checked === true);
                  }}
                />
                <label htmlFor={id} className="flex-1 cursor-pointer">
                  {option.label}
                </label>
                {option.count !== undefined ? (
                  <span className="text-xs text-muted-foreground tabular-nums">{formatCount(option.count)}</span>
                ) : null}
              </li>
            );
          })}
        </ul>
        {value.length > 0 ? (
          <div className="mt-1 border-t border-border pt-1.5">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                onChange([]);
              }}
            >
              {en.filters.clearOne(label)}
            </Button>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}
