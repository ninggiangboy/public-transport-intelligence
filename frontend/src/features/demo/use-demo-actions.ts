import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useCallback, useMemo } from 'react';

import { keys } from '@/api/keys';
import { simSlug, stopRun, stopScenario, type Scenario, type ScenarioRun } from '@/api/sim';
import { followOf } from '@/features/demo/model';
import { demoCopy } from '@/i18n/demo';
import { describeError } from '@/lib/problem-copy';
import { notify } from '@/lib/notify';

const copy = demoCopy.feedback;

// Open dead letters (E-41) when a run was started here or first seen, by run: "+{n} dead letters" counts from there
// (§4.1). Kept for the tab's life, like the run cards themselves.
const deadLetterBaselines = new Map<string, number>();

/** The open count a run's "+{n} dead letters" counts from; the first count given for a run is kept. */
export function deadLetterBaseline(runId: string, open: number): number {
  const known = deadLetterBaselines.get(runId);
  if (known !== undefined) return known;
  deadLetterBaselines.set(runId, open);
  return open;
}

/** Start, stop and "Stop all" with their toasts (§6); every change refreshes the status and the history. */
export function useDemoActions() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const refresh = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: keys.sim.all() });
  }, [queryClient]);

  /** "{title} started" with the button to where its effects show. */
  const started = useCallback(
    (scenario: Scenario, run: ScenarioRun) => {
      refresh();
      const follow = followOf(scenario.name, run.params);
      notify.success(copy.started(scenario.title), {
        ...(follow
          ? {
              action: {
                label: copy.follow[follow.kind],
                onClick: () => void navigate({ href: follow.href }),
              },
            }
          : {}),
      });
    },
    [navigate, refresh],
  );

  /** "Stop" on a run card (§6): no question, since stopping is safe. A run already gone just leaves. */
  const stop = useCallback(
    async (run: ScenarioRun, title: string) => {
      try {
        await stopRun(run.runId);
        notify.success(copy.stopped(title));
      } catch (error: unknown) {
        if (simSlug(error) !== 'scenario-run-not-found') notify.error(describeError(error).title);
      } finally {
        refresh();
      }
    },
    [refresh],
  );

  /** "Stop all" → a scenario, after the question (§6); a failure stays in the dialog. */
  const stopAll = useCallback(
    async (scenario: Pick<Scenario, 'name' | 'title'>) => {
      try {
        await stopScenario(scenario.name);
        notify.success(copy.stopped(scenario.title));
      } finally {
        refresh();
      }
    },
    [refresh],
  );

  return useMemo(() => ({ started, stop, stopAll, refresh }), [started, stop, stopAll, refresh]);
}
