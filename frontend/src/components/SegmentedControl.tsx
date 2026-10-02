import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { cn } from '@/lib/utils';

interface SegmentedControlProps<V extends string> {
  options: { value: V; label: string }[];
  value: V;
  onChange: (value: V) => void;
  size?: 'sm' | 'md';
  /** Accessible name of the group. */
  label: string;
}

/** Two to four mutually exclusive options: period, colour mode, direction. A radio group with arrow-key navigation. */
export function SegmentedControl<V extends string>({
  options,
  value,
  onChange,
  size = 'md',
  label,
}: SegmentedControlProps<V>) {
  return (
    <RadioGroup
      aria-label={label}
      value={value}
      orientation="horizontal"
      onValueChange={(next) => {
        onChange(next as V);
      }}
      className={cn('inline-flex gap-0.5 rounded-md bg-muted p-0.5', size === 'sm' ? 'h-7' : 'h-8.5')}
    >
      {options.map((option) => (
        <RadioGroupItem
          key={option.value}
          value={option.value}
          className={cn(
            'rounded-[7px] px-3 text-label font-medium whitespace-nowrap text-muted-foreground motion-safe:transition-colors',
            'hover:text-foreground data-[state=checked]:bg-card data-[state=checked]:text-foreground data-[state=checked]:shadow-xs',
          )}
        >
          {option.label}
        </RadioGroupItem>
      ))}
    </RadioGroup>
  );
}
