import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { MultiSelectFilter } from '@/components/MultiSelectFilter';
import { NewItemsPill } from '@/components/NewItemsPill';
import { RouteSelect, type RouteOption } from '@/components/RouteSelect';
import { SegmentedControl } from '@/components/SegmentedControl';
import { TimeRangePicker, type TimeRangeValue } from '@/components/TimeRangePicker';
import { BusinessClockProvider } from '@/lib/business-clock';

describe('NewItemsPill', () => {
  it('says how many rows are held back and shows them on click', async () => {
    const onShow = vi.fn();
    render(<NewItemsPill count={3} onShow={onShow} />);
    await userEvent.click(screen.getByRole('button', { name: '3 new alerts · show' }));
    expect(onShow).toHaveBeenCalledTimes(1);
  });

  it('uses the singular for one and renders nothing for none', () => {
    const { rerender } = render(<NewItemsPill count={1} onShow={() => undefined} />);
    expect(screen.getByRole('button', { name: '1 new alert · show' })).toBeInTheDocument();
    rerender(<NewItemsPill count={0} onShow={() => undefined} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('SegmentedControl', () => {
  function Harness({ onChange }: { onChange: (v: string) => void }) {
    const [value, setValue] = useState('day');
    return (
      <SegmentedControl
        label="Period"
        value={value}
        onChange={(next) => {
          setValue(next);
          onChange(next);
        }}
        options={[
          { value: 'day', label: 'Day' },
          { value: 'week', label: 'Week' },
          { value: 'month', label: 'Month' },
        ]}
      />
    );
  }

  it('is a labelled radio group with arrow-key navigation', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness onChange={onChange} />);
    expect(screen.getByRole('radiogroup', { name: 'Period' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Day' })).toBeChecked();

    await user.click(screen.getByRole('radio', { name: 'Week' }));
    expect(onChange).toHaveBeenLastCalledWith('week');
    expect(screen.getByRole('radio', { name: 'Week' })).toBeChecked();

    await user.keyboard('{ArrowRight}');
    // Arrow keys move focus along the group (Radix roving focus).
    expect(screen.getByRole('radio', { name: 'Month' })).toHaveFocus();
  });
});

describe('MultiSelectFilter', () => {
  const options = [
    { value: 0, label: 'Informational', count: 1200 },
    { value: 1, label: 'Needs attention', count: 14 },
    { value: 2, label: 'Urgent', count: 2 },
  ];

  function Harness({ initial = [] as number[] }) {
    const [value, setValue] = useState(initial);
    return <MultiSelectFilter label="Severity" options={options} value={value} onChange={setValue} />;
  }

  it('shows the label alone, then the chosen values', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    expect(screen.getByRole('button', { name: 'Severity' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Severity' }));
    await user.click(screen.getByRole('checkbox', { name: /Urgent/ }));
    expect(screen.getByRole('button', { name: 'Severity: Urgent' })).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: /Needs attention/ }));
    expect(screen.getByRole('button', { name: 'Severity: Needs attention, Urgent' })).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: /Informational/ }));
    expect(screen.getByRole('button', { name: 'Severity: 3 selected' })).toBeInTheDocument();
  });

  it('lists counts and clears the selection', async () => {
    const user = userEvent.setup();
    render(<Harness initial={[1]} />);
    await user.click(screen.getByRole('button', { name: 'Severity: Needs attention' }));
    expect(screen.getByText('1,200')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear Severity' }));
    expect(screen.getByRole('button', { name: 'Severity' })).toBeInTheDocument();
  });
});

describe('RouteSelect', () => {
  const routes: RouteOption[] = [
    { routeId: '901', shortName: 'Blue', longName: 'Blue Line', routeType: 0, color: '0053A0' },
    { routeId: '6', shortName: '6', longName: 'Xerxes Av', routeType: 3 },
    { routeId: '21', shortName: '21', longName: 'Uptown / Lake St', routeType: 3 },
  ];

  function Harness({
    multiple = true,
    max = 20,
    routeTypes,
  }: {
    multiple?: boolean;
    max?: number;
    routeTypes?: number[];
  }) {
    const [value, setValue] = useState<string[]>([]);
    return (
      <RouteSelect
        routes={routes}
        value={value}
        onChange={setValue}
        multiple={multiple}
        max={max}
        {...(routeTypes ? { routeTypes } : {})}
      />
    );
  }

  it('searches by short and long name', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Routes' }));
    expect(screen.getAllByRole('checkbox')).toHaveLength(3);

    await user.type(screen.getByRole('searchbox', { name: 'Search routes' }), 'lake');
    expect(screen.getAllByRole('checkbox')).toHaveLength(1);
    expect(screen.getByRole('checkbox', { name: /Uptown/ })).toBeInTheDocument();

    await user.clear(screen.getByRole('searchbox', { name: 'Search routes' }));
    await user.type(screen.getByRole('searchbox', { name: 'Search routes' }), 'nothing');
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('stops at the maximum', async () => {
    const user = userEvent.setup();
    render(<Harness max={2} />);
    await user.click(screen.getByRole('button', { name: 'Routes' }));
    await user.click(screen.getByRole('checkbox', { name: /Blue/ }));
    await user.click(screen.getByRole('checkbox', { name: /Xerxes/ }));
    expect(screen.getByRole('checkbox', { name: /Uptown/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Routes' })).toHaveTextContent('Blue, 6');
  });

  it('picks one and closes when it is not multiple', async () => {
    const user = userEvent.setup();
    render(<Harness multiple={false} />);
    await user.click(screen.getByRole('button', { name: 'Routes' }));
    await user.click(screen.getByRole('checkbox', { name: /Blue/ }));
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Routes' })).toHaveTextContent('Blue');
  });

  it('offers only the route types asked for', async () => {
    const user = userEvent.setup();
    render(<Harness routeTypes={[3]} />);
    await user.click(screen.getByRole('button', { name: 'Routes' }));
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
  });
});

describe('TimeRangePicker', () => {
  function setup(initial: TimeRangeValue = { window: '1h' }, maxRangeSeconds = 7 * 86_400) {
    const onChange = vi.fn();
    const user = userEvent.setup();
    function Harness() {
      const [value, setValue] = useState(initial);
      return (
        <BusinessClockProvider timezone="America/Chicago">
          <TimeRangePicker
            value={value}
            presets={['15m', '1h', '6h', '24h']}
            maxRangeSeconds={maxRangeSeconds}
            granularity="minute"
            onChange={(next) => {
              setValue(next);
              onChange(next);
            }}
          />
        </BusinessClockProvider>
      );
    }
    render(<Harness />);
    return { onChange, user };
  }

  it('selects a preset', async () => {
    const { onChange, user } = setup();
    expect(screen.getByRole('radio', { name: '1h' })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: '6h' }));
    expect(onChange).toHaveBeenCalledWith({ window: '6h' });
  });

  it('hides presets longer than the API limit', () => {
    setup({ window: '15m' }, 2 * 3600);
    expect(screen.queryByRole('radio', { name: '6h' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '1h' })).toBeInTheDocument();
  });

  it('applies a custom range read in the agency time zone', async () => {
    const { onChange, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Custom' }));
    const dialog = screen.getByRole('dialog', { name: 'Custom' });
    await user.type(within(dialog).getByLabelText('From'), '2026-09-29T16:00');
    await user.type(within(dialog).getByLabelText('To'), '2026-09-29T18:30');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(onChange).toHaveBeenCalledWith({ from: '2026-09-29T21:00:00.000Z', to: '2026-09-29T23:30:00.000Z' });
  });

  it('refuses a range that is backwards, empty or longer than the limit', async () => {
    const { onChange, user } = setup({ window: '1h' }, 3600);
    await user.click(screen.getByRole('button', { name: 'Custom' }));
    const dialog = screen.getByRole('dialog', { name: 'Custom' });

    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Enter both times.');

    await user.type(within(dialog).getByLabelText('From'), '2026-09-29T18:00');
    await user.type(within(dialog).getByLabelText('To'), '2026-09-29T16:00');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('The start must be before the end.');

    await user.clear(within(dialog).getByLabelText('To'));
    await user.type(within(dialog).getByLabelText('To'), '2026-09-29T20:00');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('The range can be at most 1 h.');
    expect(onChange).not.toHaveBeenCalled();
  });
});
