import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ConfidenceChip } from '@/components/ConfidenceChip';
import { ConfidenceMeter } from '@/components/ConfidenceMeter';
import { DelayBadge } from '@/components/DelayBadge';
import { RouteBadge } from '@/components/RouteBadge';
import { contrastRatio } from '@/lib/color';

describe('DS-03 ConfidenceChip', () => {
  it('shows "Low confidence" for 0.59 without the API flag', () => {
    render(<ConfidenceChip value={0.59} />);
    expect(screen.getByText('Low confidence (59%)')).toBeInTheDocument();
  });

  it('shows "Low confidence" for 0.82 when the API says lowConfidence, which wins over the number', () => {
    render(<ConfidenceChip value={0.82} lowConfidence />);
    expect(screen.getByText('Low confidence (82%)')).toBeInTheDocument();
  });

  it('lets the API flag turn a low number into a normal one', () => {
    render(<ConfidenceChip value={0.5} lowConfidence={false} />);
    expect(screen.getByText('50% confidence')).toBeInTheDocument();
  });

  it.each([
    [0.92, '92% confidence', 'success'],
    [0.7, '70% confidence', 'teal'],
    [0.59, 'Low confidence (59%)', 'warning'],
  ])('AI confidence %s reads "%s" in the %s tone', (value, text, tone) => {
    render(<ConfidenceChip value={value} />);
    expect(screen.getByText(text).className).toContain(`text-tone-${tone}-fg`);
  });

  it.each([
    ['HIGH', 'High confidence', 'success'],
    ['MEDIUM', 'Medium confidence', 'teal'],
    ['LOW', 'Low confidence', 'warning'],
    ['NONE', 'Schedule only', 'neutral'],
  ] as const)('ETA level %s reads "%s" in the %s tone', (level, text, tone) => {
    render(<ConfidenceChip level={level} />);
    expect(screen.getByText(text).className).toContain(`text-tone-${tone}-fg`);
  });
});

describe('ConfidenceMeter', () => {
  it('is a progress bar with the number beside it', () => {
    render(<ConfidenceMeter value={0.74} label="Triage confidence" />);
    expect(screen.getByRole('progressbar', { name: 'Triage confidence' })).toHaveAttribute('aria-valuenow', '74');
    expect(screen.getByText('74%')).toBeInTheDocument();
  });
});

describe('DelayBadge', () => {
  it.each([
    [-400, 'Early'],
    [0, 'On time'],
    [450, 'Late'],
    [900, 'Very late'],
    [null, 'No delay data'],
    [undefined, 'No delay data'],
  ])('%s s reads "%s" as text and as a chip', (delay, label) => {
    const { unmount } = render(<DelayBadge delaySeconds={delay} />);
    expect(screen.getByText(label)).toBeInTheDocument();
    unmount();
    render(<DelayBadge delaySeconds={delay} variant="chip" />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});

describe('DS-05 RouteBadge', () => {
  it('turns the text black when the feed pairs white text with a yellow background', () => {
    render(<RouteBadge routeId="6" displayName="6" color="FFFF00" textColor="FFFFFF" />);
    const badge = screen.getByText('6');
    expect(badge).toHaveStyle({ backgroundColor: 'rgb(255, 255, 0)', color: 'rgb(0, 0, 0)' });
    expect(contrastRatio({ r: 255, g: 255, b: 0 }, { r: 0, g: 0, b: 0 })).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps the feed text colour when it reads well', () => {
    render(<RouteBadge routeId="901" displayName="Blue" color="0053A0" textColor="FFFFFF" />);
    expect(screen.getByText('Blue')).toHaveStyle({ backgroundColor: 'rgb(0, 83, 160)', color: 'rgb(255, 255, 255)' });
  });

  it('picks black or white when the feed gives no text colour', () => {
    render(<RouteBadge routeId="5" displayName="5" color="FFD700" />);
    expect(screen.getByText('5')).toHaveStyle({ color: 'rgb(0, 0, 0)' });
  });

  it('falls back to a chart colour, always the same one for a route, when the feed gives no colour', () => {
    const { rerender } = render(<RouteBadge routeId="21" displayName="21" />);
    const first = screen.getByText('21').getAttribute('style');
    expect(first).toContain('--chart-');
    rerender(<RouteBadge routeId="21" displayName="21" />);
    expect(screen.getByText('21').getAttribute('style')).toBe(first);
  });

  it('ignores a colour that is not hex', () => {
    render(<RouteBadge routeId="7" displayName="7" color="not-a-colour" />);
    expect(screen.getByText('7').getAttribute('style')).toContain('--chart-');
  });
});
