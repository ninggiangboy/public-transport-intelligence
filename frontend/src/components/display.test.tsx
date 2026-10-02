import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ActivityTimeline } from '@/components/ActivityTimeline';
import { Callout } from '@/components/Callout';
import { Card } from '@/components/Card';
import { CopyButton } from '@/components/CopyButton';
import { DetailDrawer } from '@/components/DetailDrawer';
import { EmptyState } from '@/components/EmptyState';
import { IdText } from '@/components/IdText';
import { JsonViewer } from '@/components/JsonViewer';
import { KeyValueList } from '@/components/KeyValueList';
import { KpiCard } from '@/components/KpiCard';
import { LineStrip } from '@/components/LineStrip';
import { NoAccessState } from '@/components/NoAccessState';
import { PageHeader } from '@/components/PageHeader';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { Sparkline } from '@/components/Sparkline';
import { SplitView } from '@/components/SplitView';
import { diffLines, prettyJson, tokenizeLine } from '@/lib/json-lines';

describe('CopyButton and IdText', () => {
  it('copies the value and says so', async () => {
    const user = userEvent.setup();
    render(<CopyButton value="abc-123" label="Copy trace ID" />);
    await user.click(screen.getByRole('button', { name: 'Copy trace ID' }));
    expect(await navigator.clipboard.readText()).toBe('abc-123');
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('survives a refused clipboard', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));
    render(<CopyButton value="abc" label="Copy" />);
    await user.click(screen.getByRole('button', { name: 'Copy' }));
    expect(screen.queryByText('Copied')).not.toBeInTheDocument();
  });

  it('shows the first 8 characters, the whole id as title and copies it', async () => {
    const user = userEvent.setup();
    render(<IdText id="7f3a9c21-0d4e-4b1a-9c55-2f5a1d3e8b90" />);
    expect(screen.getByText('7f3a9c21')).toHaveAttribute('title', '7f3a9c21-0d4e-4b1a-9c55-2f5a1d3e8b90');
    await user.click(screen.getByRole('button', { name: 'Copy ID' }));
    expect(await navigator.clipboard.readText()).toBe('7f3a9c21-0d4e-4b1a-9c55-2f5a1d3e8b90');
  });

  it('can be shorter and without the button', () => {
    render(<IdText id="7f3a9c21-0d4e" length={4} copy={false} />);
    expect(screen.getByText('7f3a')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('KpiCard', () => {
  it('shows value, unit, hint and a delta that says which way it went', () => {
    render(
      <KpiCard
        label="On-time performance"
        value="78.4"
        unit="%"
        hint="Across 112 routes"
        delta={{ value: '2.4 pts', direction: 'up', good: 'up', caption: 'vs last Sunday' }}
        sparkline={[1, 2, 3]}
      />,
    );
    expect(screen.getByText('78.4')).toBeInTheDocument();
    expect(screen.getByText('Across 112 routes')).toBeInTheDocument();
    expect(screen.getByText('up')).toBeInTheDocument();
    expect(screen.getByText('2.4 pts').className).toContain('text-tone-success-fg');
    expect(screen.getByRole('img', { name: 'On-time performance' })).toBeInTheDocument();
  });

  it('colours a delta red when it goes the wrong way and grey when flat', () => {
    const { rerender } = render(
      <KpiCard label="Delay" value="6" delta={{ value: '1 min', direction: 'up', good: 'down' }} />,
    );
    expect(screen.getByText('1 min').className).toContain('text-tone-danger-fg');
    rerender(<KpiCard label="Delay" value="6" delta={{ value: '0', direction: 'flat', good: 'down' }} />);
    expect(screen.getByText('0').className).toContain('text-muted-foreground');
  });

  it('colours the value with its tone and links when given an href', () => {
    render(<KpiCard label="Dead letters" value="14" tone="danger" href="/ops/dlq" />);
    expect(screen.getByText('14').className).toContain('text-tone-danger-fg');
    expect(screen.getByRole('link')).toHaveAttribute('href', '/ops/dlq');
  });
});

describe('KeyValueList, Card, PageHeader', () => {
  it('lists pairs as a description list', () => {
    render(
      <KeyValueList
        columns={2}
        items={[
          { label: 'Agency', value: 'Metro Transit' },
          { label: 'Zone', value: 'Chicago' },
        ]}
      />,
    );
    expect(screen.getByText('Agency').tagName).toBe('DT');
    expect(screen.getByText('Metro Transit').tagName).toBe('DD');
  });

  it('names a card by its title and shows meta, actions and footer', () => {
    render(
      <Card title="Open items" meta="Updated now" actions={<button type="button">Export</button>} footer="Footer band">
        Body
      </Card>,
    );
    const card = screen.getByRole('region', { name: 'Open items' });
    expect(within(card).getByText('Updated now')).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Export' })).toBeInTheDocument();
    expect(within(card).getByText('Footer band')).toBeInTheDocument();
  });

  it('puts one h1, a breadcrumb and the actions in the page header', () => {
    render(
      <PageHeader
        crumbs={[{ label: 'Operations', href: '/ops' }, { label: 'Dead letters' }]}
        title="Dead letters"
        subtitle="Records the pipeline could not process."
        actions={<button type="button">Export</button>}
      />,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Dead letters' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(nav).getByRole('link', { name: 'Operations' })).toHaveAttribute('href', '/ops');
    expect(within(nav).getByText('Dead letters')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument();
  });
});

describe('Callout and ActivityTimeline', () => {
  it('shows title, body and action in the tone', () => {
    render(
      <Callout tone="primary" title="AI analysis" action={<button type="button">See why</button>}>
        Likely a data issue.
      </Callout>,
    );
    expect(screen.getByText('AI analysis')).toBeInTheDocument();
    expect(screen.getByText('Likely a data issue.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'See why' })).toBeInTheDocument();
  });

  it('lists events in order with their age', () => {
    render(
      <ActivityTimeline
        items={[
          {
            id: '1',
            text: 'Alert raised',
            at: new Date(Date.now() - 600_000).toISOString(),
            axis: 'audit',
            tone: 'danger',
          },
          {
            id: '2',
            text: 'Resolved',
            at: new Date(Date.now() - 30_000).toISOString(),
            axis: 'audit',
            tone: 'success',
          },
        ]}
      />,
    );
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Alert raised');
    expect(items[0]).toHaveTextContent('10 min ago');
    expect(items[1]).toHaveTextContent('30 s ago');
  });
});

describe('states', () => {
  it('EmptyState shows title, description and an action', async () => {
    const onClick = vi.fn();
    render(
      <EmptyState
        title="No results match these filters"
        description="Try a wider range."
        action={{ label: 'Clear filters', onClick }}
      />,
    );
    expect(screen.getByRole('heading', { name: 'No results match these filters' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('NoAccessState asks an anonymous visitor to sign in and tells a viewer what is missing', async () => {
    const onSignIn = vi.fn();
    const { rerender } = render(<NoAccessState requiredRole="operator" signedIn={false} onSignIn={onSignIn} />);
    expect(screen.getByText('Sign in to view this page')).toBeInTheDocument();
    expect(screen.getByText('This page requires the operator role.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(onSignIn).toHaveBeenCalledTimes(1);

    rerender(<NoAccessState requiredRole="viewer" signedIn={false} />);
    expect(screen.getByText('This page is for operations staff.')).toBeInTheDocument();

    rerender(<NoAccessState requiredRole="operator" signedIn onGoToOverview={() => undefined} />);
    expect(screen.getByText("You don't have access to this page")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Go to overview' })).toBeInTheDocument();
  });
});

describe('PanelSkeleton (CP-10)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows nothing for the first 150 ms and then a busy placeholder', () => {
    const { container } = render(<PanelSkeleton variant="table" />);
    expect(container).toBeEmptyDOMElement();
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(container).toBeEmptyDOMElement();
    act(() => {
      vi.advanceTimersByTime(60);
    });
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
  });

  it.each([
    ['table', 9],
    ['list', 5],
    ['chart', 8],
    ['detail', 6],
  ] as const)('the %s variant has the shape of its content', (variant, bars) => {
    const { container } = render(<PanelSkeleton variant={variant} delayMs={0} />);
    expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(bars);
  });

  it('takes the number of rows', () => {
    const { container } = render(<PanelSkeleton variant="list" rows={2} delayMs={0} />);
    expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(2);
  });
});

describe('DetailDrawer', () => {
  it('opens as a labelled dialog with a footer and closes on the button and on Escape', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(
      <DetailDrawer title="Dead letter 7f3a9c21" open onClose={onClose} footer={<button type="button">Done</button>}>
        <p>Body</p>
      </DetailDrawer>,
    );
    const dialog = screen.getByRole('dialog', { name: 'Dead letter 7f3a9c21' });
    expect(within(dialog).getByText('Body')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Done' })).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Close panel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('renders nothing while closed', () => {
    render(
      <DetailDrawer title="Hidden" open={false} onClose={() => undefined}>
        Body
      </DetailDrawer>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('SplitView', () => {
  it('shows the empty detail until a record is open', () => {
    const { rerender } = render(<SplitView list={<p>List</p>} detail={null} emptyDetail={<p>Select a record</p>} />);
    expect(screen.getByText('Select a record')).toBeInTheDocument();
    rerender(<SplitView list={<p>List</p>} detail={<p>Record 7</p>} emptyDetail={<p>Select a record</p>} />);
    expect(screen.getByText('Record 7')).toBeInTheDocument();
    expect(screen.queryByText('Select a record')).not.toBeInTheDocument();
  });
});

describe('Sparkline', () => {
  it('draws a line and names the chart', () => {
    const { container } = render(<Sparkline points={[1, 3, 2, 5]} label="Trend" />);
    expect(screen.getByRole('img', { name: 'Trend' })).toBeInTheDocument();
    expect(container.querySelector('polyline')?.getAttribute('points')?.split(' ')).toHaveLength(4);
  });

  it('draws a flat line for equal points, a dot for one point and a blank for none', () => {
    const { container, rerender } = render(<Sparkline points={[2, 2, 2]} label="Flat" area />);
    expect(container.querySelector('polyline')).not.toBeNull();
    expect(container.querySelector('path')).not.toBeNull();
    rerender(<Sparkline points={[2]} label="One" />);
    expect(container.querySelector('polyline')?.getAttribute('points')).toMatch(/^50\./);
    rerender(<Sparkline points={[]} label="None" />);
    expect(screen.getByRole('img', { name: 'None: No data' })).toBeInTheDocument();
  });
});

describe('LineStrip', () => {
  const stops = [
    { id: 'a', name: 'Nicollet Mall', state: 'passed', major: true },
    { id: 'b', name: 'Lake St', eta: '+3 min', state: 'current' },
    { id: 'c', name: 'Southtown', meta: 'Transfer to 21', state: 'upcoming' },
  ] as const;

  it('lists the stops with their state for screen readers and the marker between them', () => {
    render(<LineStrip color="0053A0" stops={[...stops]} marker={{ atStopId: 'b', label: 'Bus 4021' }} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
    expect(screen.getByText('Nicollet Mall').parentElement).toHaveTextContent('Passed');
    expect(screen.getByText('Lake St').parentElement).toHaveTextContent('Current stop');
    expect(screen.getByText('Bus 4021')).toBeInTheDocument();
    expect(screen.getByText('Transfer to 21')).toBeInTheDocument();
  });

  it('describes each coloured run of the horizontal strip by its delay class', () => {
    render(
      <LineStrip
        orientation="horizontal"
        stops={[...stops]}
        segments={[
          { from: 'a', to: 'b', delayClass: 'late' },
          { from: 'b', to: 'c', delayClass: 'on-time' },
        ]}
      />,
    );
    expect(screen.getByRole('img', { name: 'Nicollet Mall to Lake St: Late' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Lake St to Southtown: On time' })).toBeInTheDocument();
  });
});

describe('JsonViewer', () => {
  it('pretty-prints an object or a JSON string and numbers the lines', () => {
    render(<JsonViewer value='{"routeId":"901","delay":213}' fileName="payload.json" />);
    const region = screen.getByRole('region', { name: 'JSON: payload.json' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(region).toHaveTextContent('"routeId": "901"');
    expect(region).toHaveTextContent('"delay": 213');
    expect(region.querySelectorAll('[data-line]')).toHaveLength(4);
    expect(region.querySelector('[data-line="1"]')).not.toBeNull();
  });

  it('copies the pretty-printed text', async () => {
    const user = userEvent.setup();
    render(<JsonViewer value={{ a: 1 }} />);
    await user.click(screen.getByRole('button', { name: /JSON: payload\.json/ }));
    expect(await navigator.clipboard.readText()).toBe('{\n  "a": 1\n}');
  });

  it('marks removed and added lines against compareTo', () => {
    render(<JsonViewer value={{ a: 1, b: 3 }} compareTo={{ a: 1, b: 2 }} />);
    const region = screen.getByRole('region');
    expect(region).toHaveTextContent('Original: "b": 2');
    expect(region).toHaveTextContent('Edited: "b": 3');
  });

  it('shows text that is not JSON as it is, with a note', () => {
    render(<JsonViewer value="not json" />);
    expect(screen.getByText('Not valid JSON, showing the text as is.')).toBeInTheDocument();
    expect(screen.getByRole('region')).toHaveTextContent('not json');
  });
});

describe('json-lines', () => {
  it('prettyJson reports validity', () => {
    expect(prettyJson({ a: [1] })).toEqual({ text: '{\n  "a": [\n    1\n  ]\n}', valid: true });
    expect(prettyJson('{"a":1}')).toEqual({ text: '{\n  "a": 1\n}', valid: true });
    expect(prettyJson('nope')).toEqual({ text: 'nope', valid: false });
  });

  it('tokenizeLine separates keys, strings, numbers and literals', () => {
    expect(tokenizeLine('  "id": "x", "n": -1.5e3, "ok": true,')).toEqual([
      { kind: 'punctuation', text: '  ' },
      { kind: 'key', text: '"id"' },
      { kind: 'punctuation', text: ':' },
      { kind: 'punctuation', text: ' ' },
      { kind: 'string', text: '"x"' },
      { kind: 'punctuation', text: ', ' },
      { kind: 'key', text: '"n"' },
      { kind: 'punctuation', text: ':' },
      { kind: 'punctuation', text: ' ' },
      { kind: 'number', text: '-1.5e3' },
      { kind: 'punctuation', text: ', ' },
      { kind: 'key', text: '"ok"' },
      { kind: 'punctuation', text: ':' },
      { kind: 'punctuation', text: ' ' },
      { kind: 'literal', text: 'true' },
      { kind: 'punctuation', text: ',' },
    ]);
  });

  it('diffLines keeps common lines and marks the rest', () => {
    expect(diffLines('a\nb\nc', 'a\nx\nc\nd')).toEqual([
      { kind: 'same', text: 'a' },
      { kind: 'del', text: 'b' },
      { kind: 'add', text: 'x' },
      { kind: 'same', text: 'c' },
      { kind: 'add', text: 'd' },
    ]);
    expect(diffLines('same', 'same')).toEqual([{ kind: 'same', text: 'same' }]);
  });
});
