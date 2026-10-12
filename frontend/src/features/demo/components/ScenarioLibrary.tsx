import type { UseQueryResult } from '@tanstack/react-query';

import type { Scenario, ScenarioRun } from '@/api/sim';
import { ErrorState } from '@/components/ErrorState';
import { ToneBadge } from '@/components/ToneBadge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ScenarioArt } from '@/features/demo/components/ScenarioArt';
import { defaultChips, runsForDefault } from '@/features/demo/model';
import { demoCopy } from '@/i18n/demo';

const copy = demoCopy.library;
const SKELETON_CARDS = 4;

interface ScenarioLibraryProps {
  catalog: UseQueryResult<Scenario[]>;
  running: ScenarioRun[];
  /** False while the simulator does not answer: "Start scenario" is disabled (§7). */
  canStart: boolean;
  onStart: (scenario: Scenario) => void;
}

/** The scenarios of the catalog in its order, four per row from 1440 px, two below (§4). */
export function ScenarioLibrary({ catalog, running, canStart, onStart }: ScenarioLibraryProps) {
  return (
    <section aria-labelledby="demo-scenarios" className="flex flex-col gap-2.5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="demo-scenarios" className="text-[17px] leading-tight font-semibold tracking-title">
          {copy.heading}
        </h2>
        <p className="text-sm text-muted-foreground">{copy.note}</p>
      </div>
      {catalog.isPending ? (
        <div aria-busy="true" className="grid grid-cols-1 gap-3 min-[1440px]:grid-cols-4 sm:grid-cols-2">
          {Array.from({ length: SKELETON_CARDS }, (_, index) => (
            <Skeleton key={index} className="h-60 rounded-lg" />
          ))}
        </div>
      ) : catalog.data ? (
        <ul className="grid grid-cols-1 gap-3 min-[1440px]:grid-cols-4 sm:grid-cols-2">
          {catalog.data.map((scenario) => (
            <li key={scenario.name} className="flex">
              <ScenarioCard
                scenario={scenario}
                running={runsForDefault(scenario, running)}
                canStart={canStart}
                onStart={() => {
                  onStart(scenario);
                }}
              />
            </li>
          ))}
        </ul>
      ) : (
        <div className="rounded-lg border border-border bg-card shadow-sm">
          <ErrorState
            error={catalog.error}
            variant="block"
            panel={copy.heading}
            onRetry={() => void catalog.refetch()}
          />
        </div>
      )}
    </section>
  );
}

function ScenarioCard({
  scenario,
  running,
  canStart,
  onStart,
}: {
  scenario: Scenario;
  running: boolean;
  canStart: boolean;
  onStart: () => void;
}) {
  const titleId = `scenario-${scenario.name}`;
  return (
    <article
      aria-labelledby={titleId}
      className="flex w-full flex-col overflow-hidden rounded-lg border border-border bg-card shadow-sm"
    >
      <div className="grid h-28 place-items-center border-b border-border bg-surface">
        <ScenarioArt name={scenario.name} />
      </div>
      <div className="flex flex-1 flex-col gap-2.5 px-4 pt-3.5 pb-4">
        <h3 id={titleId} className="text-base font-semibold tracking-title">
          {scenario.title}
        </h3>
        <p className="text-sm text-muted-foreground">{scenario.description}</p>
        <ul className="flex flex-wrap gap-1.5">
          {defaultChips(scenario).map((chip) => (
            <li
              key={chip}
              className="rounded-full border border-border bg-surface px-2 py-0.5 text-xs text-foreground-2"
            >
              {chip}
            </li>
          ))}
        </ul>
        <div className="mt-auto pt-1">
          {running ? (
            <ToneBadge tone="progress" label={copy.running} />
          ) : (
            <Button size="sm" disabled={!canStart} onClick={onStart} aria-describedby={titleId}>
              {copy.start}
            </Button>
          )}
        </div>
      </div>
    </article>
  );
}
