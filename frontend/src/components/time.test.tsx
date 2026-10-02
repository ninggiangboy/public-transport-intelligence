import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Duration } from '@/components/Duration';
import { FreshnessIndicator } from '@/components/FreshnessIndicator';
import { RelativeTime } from '@/components/RelativeTime';
import { SourceStaleNotice } from '@/components/SourceStaleNotice';
import { Timestamp } from '@/components/Timestamp';
import { BusinessClockProvider } from '@/lib/business-clock';

const NOW = Date.parse('2026-09-29T21:10:00Z');
const iso = (offsetSeconds: number) => new Date(NOW + offsetSeconds * 1000).toISOString();

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
});
afterEach(() => {
  vi.useRealTimers();
});

function inChicago(ui: React.ReactNode, now?: () => number) {
  return (
    <BusinessClockProvider timezone="America/Chicago" {...(now ? { now } : {})}>
      {ui}
    </BusinessClockProvider>
  );
}

describe('Timestamp', () => {
  it('shows the agency time zone and puts the ISO UTC instant in the title', () => {
    render(inChicago(<Timestamp at="2026-09-29T21:05:12Z" format="time" seconds />));
    const time = screen.getByText('4:05:12 PM CDT');
    expect(time).toHaveAttribute('title', '2026-09-29T21:05:12Z');
    expect(time).toHaveAttribute('datetime', '2026-09-29T21:05:12Z');
  });

  it('formats dates and date-times, with the zone optional', () => {
    render(
      inChicago(
        <>
          <Timestamp at="2026-09-29T21:05:00Z" format="date" />
          <Timestamp at="2026-09-29T21:05:00Z" />
          <Timestamp at="2026-09-28T21:05:00Z" showZone={false} />
        </>,
      ),
    );
    expect(screen.getByText('Sep 29, 2026')).toBeInTheDocument();
    expect(screen.getByText('Sep 29, 4:05 PM CDT')).toBeInTheDocument();
    expect(screen.getByText('Sep 28, 4:05 PM')).toBeInTheDocument();
  });
});

describe('RelativeTime', () => {
  it('re-renders every second under a minute and every 30 s after', () => {
    render(inChicago(<RelativeTime at={iso(-3)} axis="audit" />));
    expect(screen.getByText('just now')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByText('5 s ago')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    // Past a minute the label only moves every 30 s.
    expect(screen.getByText('1 min ago')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(29_000);
    });
    expect(screen.getByText('1 min ago')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByText('2 min ago')).toBeInTheDocument();
  });

  it('UX-05 counts an event from businessNow and an audit time from the machine clock', () => {
    const businessNow = () => Date.now() - 13 * 3600_000;
    const position = new Date(businessNow() - 5000).toISOString();
    render(
      inChicago(
        <>
          <RelativeTime at={position} axis="event" />
          <RelativeTime at={position} axis="audit" />
        </>,
        businessNow,
      ),
    );
    expect(screen.getByText('5 s ago')).toBeInTheDocument();
    expect(screen.getByText('13 h ago')).toBeInTheDocument();
  });
});

describe('Duration', () => {
  it('formats milliseconds and ISO durations, and a dash for nothing', () => {
    render(
      <>
        <Duration ms={252_000} />
        <Duration iso="PT1H30M" />
        <Duration />
      </>,
    );
    expect(screen.getByText('4 min 12 s')).toBeInTheDocument();
    expect(screen.getByText('1 h 30 min')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});

describe('DS-10 FreshnessIndicator', () => {
  it('is calm while the data is fresh', () => {
    render(inChicago(<FreshnessIndicator asOf={iso(-5)} axis="audit" staleAfterSeconds={90} />));
    const label = screen.getByText('Updated 5 s ago');
    expect(label).toHaveAttribute('data-stale', 'false');
    expect(label.className).not.toContain('warning');
  });

  it('turns to the warning tone when older than staleAfterSeconds, and still says when', () => {
    render(inChicago(<FreshnessIndicator asOf={iso(-600)} axis="audit" staleAfterSeconds={90} />));
    const label = screen.getByText(/Updated 10 min ago/);
    expect(label).toHaveAttribute('data-stale', 'true');
    expect(label.className).toContain('text-tone-warning-fg');
    expect(label).toHaveTextContent('Stale');
  });

  it('becomes stale as time passes', () => {
    render(inChicago(<FreshnessIndicator asOf={iso(-80)} axis="audit" staleAfterSeconds={90} />));
    expect(screen.getByText(/Updated/)).toHaveAttribute('data-stale', 'false');
    act(() => {
      vi.advanceTimersByTime(15_000);
    });
    expect(screen.getByText(/Updated/)).toHaveAttribute('data-stale', 'true');
  });

  it('can show an absolute time, and copes with no data', () => {
    const { unmount } = render(
      inChicago(<FreshnessIndicator asOf="2026-09-29T21:05:00Z" axis="event" mode="absolute" />),
    );
    expect(screen.getByText('As of Sep 29, 4:05 PM CDT')).toBeInTheDocument();
    unmount();
    render(<FreshnessIndicator axis="event" />);
    expect(screen.getByText('No data yet')).toBeInTheDocument();
  });
});

describe('SourceStaleNotice', () => {
  it('says how long ticket sales have been missing', () => {
    render(<SourceStaleNotice source="TICKETING_SALES" ageSeconds={720} />);
    expect(
      screen.getByText('No ticket sales received for 12 min. Anomaly detection may be behind.'),
    ).toBeInTheDocument();
  });

  it('names the live source', () => {
    render(<SourceStaleNotice source="GTFS_RT_VEHICLE_POSITION" ageSeconds={185} />);
    expect(
      screen.getByText('Live data is delayed. Vehicle positions were last updated 3 min 5 s ago.'),
    ).toBeInTheDocument();
  });

  it('still reads without an age', () => {
    render(<SourceStaleNotice source="GTFS_RT_TRIP_UPDATE" />);
    expect(screen.getByText(/Trip updates/)).toBeInTheDocument();
  });
});
