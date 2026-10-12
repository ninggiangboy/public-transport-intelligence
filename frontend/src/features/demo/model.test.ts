import { describe, expect, it } from 'vitest';

import type { Scenario, ScenarioRun } from '@/api/sim';
import {
  checkScenarioForm,
  defaultChips,
  followOf,
  formatClock,
  formatOffset,
  initialValues,
  paramsSummary,
  pausedStreams,
  plannedFromForm,
  rateChoice,
  requesterName,
  runProgress,
  runsForDefault,
  splitDuration,
  toIsoDuration,
  watchedAlerts,
} from '@/features/demo/model';
import { demoCopy } from '@/i18n/demo';
import { badData, bunching, disruption, loadRamp, ticketSpike } from '@/test/sim';

const rules = demoCopy.rules;

function run(overrides: Partial<ScenarioRun>): ScenarioRun {
  return {
    runId: 'r1',
    scenario: 'bunching',
    status: 'RUNNING',
    params: {},
    requestedBy: 'user:operator',
    startedAt: '2026-10-12T10:00:00Z',
    plannedEndAt: '2026-10-12T10:20:00Z',
    ...overrides,
  };
}

describe('durations', () => {
  it('writes ISO-8601 in whole units and reads the largest unit back', () => {
    expect(toIsoDuration(20 * 60_000)).toBe('PT20M');
    expect(toIsoDuration(90 * 60_000)).toBe('PT1H30M');
    expect(toIsoDuration(45_000)).toBe('PT45S');
    expect(toIsoDuration(0)).toBe('PT0S');
    expect(splitDuration(2 * 3_600_000)).toEqual({ amount: '2', unit: 'h' });
    expect(splitDuration(90_000)).toEqual({ amount: '90', unit: 'sec' });
    expect(splitDuration(Number.NaN)).toEqual({ amount: '', unit: 'min' });
  });

  it('formats the clock offset with its sign, and none for zero', () => {
    expect(formatOffset('PT14H30M')).toBe('+14 h 30 min');
    expect(formatOffset('PT-2H')).toBe('−2 h');
    expect(formatOffset('PT0S')).toBeUndefined();
    expect(formatClock(12 * 60_000 + 40_000)).toBe('12:40');
    expect(formatClock(3_900_000)).toBe('1:05:00');
  });
});

describe('the start form (§6.1)', () => {
  it('fills the defaults, or the parameters of an earlier run', () => {
    expect(initialValues(bunching)).toEqual({
      routeId: '',
      directionId: '0',
      pairs: '1',
      targetGapRatio: '0.2',
      duration: { amount: '20', unit: 'min' },
    });
    expect(initialValues(disruption).directionId).toBe('');
    expect(initialValues(loadRamp).steps).toBe('1, 2, 5, 10');
    const again = initialValues(bunching, { routeId: '18', pairs: 2, duration: 'PT5M' });
    expect(again).toMatchObject({ routeId: '18', pairs: '2', duration: { amount: '5', unit: 'min' } });
  });

  it('keeps the button off without a message while a required field is empty', () => {
    const result = checkScenarioForm(bunching, initialValues(bunching));
    expect(result).toEqual({ ok: false, errors: {}, missing: true });
  });

  it('says "Must be at most 3." for pairs = 5 (AC-5)', () => {
    const values = { ...initialValues(bunching), routeId: '18', pairs: '5' };
    const result = checkScenarioForm(bunching, values);
    expect(result.ok).toBe(false);
    expect(!result.ok && result.errors).toEqual({ pairs: rules.atMost('3') });
  });

  it('checks numbers, durations and lists against the catalog', () => {
    const base = { ...initialValues(bunching), routeId: '18' };
    const errorsOf = (scenario: Scenario, values: Record<string, unknown>) => {
      const result = checkScenarioForm(scenario, values as never);
      return result.ok ? {} : result.errors;
    };
    expect(errorsOf(bunching, { ...base, pairs: '1.5' })).toEqual({ pairs: rules.integer });
    expect(errorsOf(bunching, { ...base, targetGapRatio: '0.01' })).toEqual({ targetGapRatio: rules.atLeast('0.05') });
    expect(errorsOf(bunching, { ...base, duration: { amount: '3', unit: 'h' } })).toEqual({
      duration: rules.atMost('2 h'),
    });
    expect(errorsOf(bunching, { ...base, duration: { amount: '30', unit: 'sec' } })).toEqual({
      duration: rules.atLeast('1 min'),
    });
    expect(errorsOf(badData, { ...initialValues(badData), kinds: [] })).toEqual({ kinds: rules.pickOne });
    expect(errorsOf(loadRamp, { ...initialValues(loadRamp), steps: '1, x' })).toEqual({ steps: rules.numbers });
    expect(errorsOf(loadRamp, { ...initialValues(loadRamp), steps: '1, 25' })).toEqual({ steps: rules.atMost('20') });
  });

  it('builds the body: numbers for numeric options, ISO durations, null for "Any", nothing for an empty optional', () => {
    const bunchingBody = checkScenarioForm(bunching, {
      ...initialValues(bunching),
      routeId: '18',
      duration: { amount: '1.5', unit: 'h' },
    });
    expect(bunchingBody).toEqual({
      ok: true,
      body: { routeId: '18', directionId: 0, pairs: 1, targetGapRatio: 0.2, duration: 'PT1H30M' },
    });
    const disruptionBody = checkScenarioForm(disruption, { ...initialValues(disruption), routeId: '18' });
    expect(disruptionBody.ok && disruptionBody.body).toEqual({
      routeId: '18',
      directionId: null,
      skipStops: false,
      duration: 'PT20M',
    });
    const spike = checkScenarioForm(ticketSpike, { ...initialValues(ticketSpike), salePointId: '  ' });
    expect(spike.ok && spike.body).toEqual({ duration: 'PT30M' });
    const ramp = checkScenarioForm(loadRamp, initialValues(loadRamp));
    expect(ramp.ok && ramp.body).toEqual({ steps: [1, 2, 5, 10], stepDuration: 'PT5M', rampDown: true });
  });

  it('gives the total duration of a load ramp from its steps (DOC-25 §7.8)', () => {
    const values = { ...initialValues(loadRamp), steps: '1, 2', stepDuration: { amount: '1', unit: 'min' as const } };
    expect(plannedFromForm(loadRamp, values)).toBe(3 * 60_000);
    expect(plannedFromForm(loadRamp, { ...values, rampDown: false })).toBe(2 * 60_000);
    expect(plannedFromForm(bunching, { ...initialValues(bunching), pairs: '9' })).toBe(20 * 60_000);
  });
});

describe('runs', () => {
  it('names who asked (§8)', () => {
    expect(requesterName('user:linh')).toBe('linh');
    expect(requesterName('experiment:EXP-03/20261002T101500Z-r07')).toBe('Experiment EXP-03');
    expect(requesterName('cli')).toBe('Command line');
  });

  it('summarises the parameters in catalog order with their labels', () => {
    const params = { duration: 'PT20M', routeId: '18', pairs: 1, directionId: 0 };
    expect(paramsSummary(bunching, params, ['routeId'])).toBe('Direction 0 · Vehicle pairs 1 · Duration 20 min');
    expect(paramsSummary(undefined, { ratio: 0.05, kinds: ['a', 'b'] })).toBe('ratio 0.05 · kinds a, b');
  });

  it('measures the time left on the real clock', () => {
    const progress = runProgress(run({}), Date.parse('2026-10-12T10:05:00Z'));
    expect(progress).toEqual({ share: 0.25, leftMs: 15 * 60_000 });
    expect(runProgress(run({ plannedEndAt: undefined }), 0)).toBeUndefined();
  });

  it('marks a card "Running" for a single scenario, or for the default target only', () => {
    expect(runsForDefault(badData, [run({ scenario: 'bad-data' })])).toBe(true);
    expect(runsForDefault(bunching, [run({ params: { routeId: '18' } })])).toBe(false);
    expect(runsForDefault(ticketSpike, [run({ scenario: 'ticket-spike', params: { salePointId: 'KIOSK-001' } })])).toBe(
      true,
    );
    expect(runsForDefault(ticketSpike, [run({ scenario: 'ticket-spike', params: { salePointId: 'KIOSK-009' } })])).toBe(
      false,
    );
  });

  it('knows where each scenario shows and which alerts answer it', () => {
    expect(followOf('bunching', { routeId: '18' })).toEqual({ kind: 'map', href: '/map?route=18' });
    expect(followOf('bad-data', {})?.kind).toBe('deadLetters');
    expect(followOf('refund-burst', {})?.kind).toBe('ticketing');
    expect(followOf('load-ramp', {})?.kind).toBe('pipeline');
    expect(followOf('something-new', {})).toBeUndefined();
    expect(watchedAlerts({ scenario: 'bunching', params: { routeId: '18' } })).toEqual({
      routeId: '18',
      type: 'BUNCHING',
    });
    expect(watchedAlerts({ scenario: 'ticket-spike', params: { salePointId: 'KIOSK-001' } })).toEqual({
      salePointId: 'KIOSK-001',
      type: 'TICKETING_ANOMALY',
    });
    expect(watchedAlerts({ scenario: 'duplicates', params: {} })).toBeUndefined();
  });

  it('shows the defaults on the card', () => {
    expect(defaultChips(bunching)).toEqual(['Direction 0', 'Vehicle pairs 1', 'Target gap (share of headway) 0.2']);
  });
});

describe('speed (§4)', () => {
  it('reads the preset both multipliers sit on, else Custom', () => {
    expect(rateChoice({ gtfsRt: 1, ticketing: 1 })).toBe('1');
    expect(rateChoice({ gtfsRt: 0, ticketing: 0 })).toBe('0');
    expect(rateChoice({ gtfsRt: 2, ticketing: 1 })).toBe('custom');
    expect(rateChoice({ gtfsRt: 3, ticketing: 3 })).toBe('custom');
  });

  it('asks only when a stream goes to 0', () => {
    expect(pausedStreams({ gtfsRt: 1, ticketing: 1 }, { gtfsRt: 0, ticketing: 0 })).toBe('both');
    expect(pausedStreams({ gtfsRt: 1, ticketing: 0 }, { gtfsRt: 0, ticketing: 0 })).toBe('gtfsRt');
    expect(pausedStreams({ gtfsRt: 1, ticketing: 1 }, { gtfsRt: 1, ticketing: 0 })).toBe('ticketing');
    expect(pausedStreams({ gtfsRt: 1, ticketing: 1 }, { gtfsRt: 2, ticketing: 2 })).toBeUndefined();
  });
});
