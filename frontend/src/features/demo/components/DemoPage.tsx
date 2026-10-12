import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ChevronDown, CirclePlay, Square } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';

import { isApiError } from '@/api/problem';
import type { Scenario, ScenarioRun } from '@/api/sim';
import { useEnv } from '@/app/env-context';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { EmptyState } from '@/components/EmptyState';
import { PageHeader } from '@/components/PageHeader';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { RunCard } from '@/features/demo/components/RunCard';
import { RunDrawer } from '@/features/demo/components/RunDrawer';
import { RunHistory } from '@/features/demo/components/RunHistory';
import { ScenarioLibrary } from '@/features/demo/components/ScenarioLibrary';
import { SimulatorStrip } from '@/features/demo/components/SimulatorStrip';
import { StartDialog } from '@/features/demo/components/StartDialog';
import { catalogQuery, routesQuery, runsQuery, statusQuery } from '@/features/demo/queries';
import { demoSearch, type DemoSearch } from '@/features/demo/search';
import { useDemoActions } from '@/features/demo/use-demo-actions';
import { demoCopy as copy } from '@/i18n/demo';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';

function Header({ actions }: { actions?: React.ReactNode }) {
  return (
    <PageHeader
      crumbs={[{ label: en.nav.groups.operations }, { label: copy.crumb }]}
      title={copy.heading}
      subtitle={copy.subtitle}
      actions={actions}
    />
  );
}

/** Without the demo profile: `demoControl` off in env.js, or a 404 from the proxy (§7, AC-6). */
function NotEnabled() {
  return (
    <div className="flex flex-col gap-4">
      <Header />
      <div className="rounded-lg border border-border bg-card shadow-sm">
        <EmptyState icon={CirclePlay} title={copy.notEnabled} description={copy.notEnabledBody} />
      </div>
    </div>
  );
}

/** /ops/demo: run the simulator's scenarios and watch the platform react (DOC-36 screens/demo-control). */
export function DemoPage({ raw }: { raw: Record<string, unknown> }) {
  useDocumentTitle(copy.title);
  const appEnv = useEnv();
  // Nothing is asked of the proxy when env.js says the stack has no demo profile.
  if (!appEnv.demoControl) return <NotEnabled />;
  return <Demo raw={raw} />;
}

interface Starting {
  scenario: Scenario;
  prefill?: Record<string, unknown>;
}

function Demo({ raw }: { raw: Record<string, unknown> }) {
  const navigate = useNavigate({ from: '/ops/demo' });
  const search = useMemo(() => demoSearch.parse(raw), [raw]);
  const status = useQuery(statusQuery());
  const catalog = useQuery(catalogQuery());
  const runs = useQuery(runsQuery());
  const routes = useQuery(routesQuery());
  const actions = useDemoActions();
  const [starting, setStarting] = useState<Starting | undefined>(undefined);
  const [stopping, setStopping] = useState<ReadonlySet<string>>(new Set());
  const [stopAll, setStopAll] = useState<{ name: string; title: string; count: number } | undefined>(undefined);

  const setSearch = useCallback(
    (change: Partial<DemoSearch>) => {
      void navigate({ search: (previous) => ({ ...demoSearch.parse(previous), ...change }) });
    },
    [navigate],
  );

  const titleOf = useCallback(
    (name: string) => catalog.data?.find((scenario) => scenario.name === name)?.title ?? name,
    [catalog.data],
  );

  // A run shows as a card while the history says RUNNING and, once the status answers, it lists the run too: the
  // status (2 s) notices a run that ended before the history (5 s) does (§6).
  const running = useMemo(() => {
    const live = status.data ? new Set(status.data.runningScenarios.map((run) => run.runId)) : undefined;
    return (runs.data ?? []).filter((run) => run.status === 'RUNNING' && (!live || live.has(run.runId)));
  }, [runs.data, status.data]);

  const groups = useMemo(() => {
    const counts = new Map<string, number>();
    for (const run of running) counts.set(run.scenario, (counts.get(run.scenario) ?? 0) + 1);
    return [...counts].map(([name, count]) => ({ name, title: titleOf(name), count }));
  }, [running, titleOf]);

  const stop = (run: ScenarioRun) => {
    setStopping((current) => new Set(current).add(run.runId));
    void actions.stop(run, titleOf(run.scenario));
  };

  if (isApiError(status.error) && status.error.status === 404) return <NotEnabled />;

  const routeOf = (routeId: unknown) =>
    typeof routeId === 'string' ? routes.data?.data.items.find((route) => route.routeId === routeId) : undefined;

  return (
    <div className="flex flex-col gap-5">
      <Header
        actions={
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" disabled={groups.length === 0}>
                <Square aria-hidden="true" />
                {copy.running.stopAll}
                <ChevronDown aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {groups.map((group) => (
                <DropdownMenuItem
                  key={group.name}
                  onSelect={() => {
                    setStopAll(group);
                  }}
                >
                  {group.title}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />

      <SimulatorStrip status={status} />

      <section aria-label={copy.running.label} className="flex flex-col gap-3">
        {running.length === 0 ? (
          runs.isSuccess ? (
            <p className="text-sm text-muted-foreground">{copy.running.none}</p>
          ) : null
        ) : (
          running.map((run) => (
            <RunCard
              key={run.runId}
              run={run}
              scenario={catalog.data?.find((scenario) => scenario.name === run.scenario)}
              route={routeOf(run.params.routeId)}
              stopping={stopping.has(run.runId)}
              onStop={() => {
                stop(run);
              }}
            />
          ))
        )}
      </section>

      <ScenarioLibrary
        catalog={catalog}
        running={running}
        canStart={status.data !== undefined}
        onStart={(scenario) => {
          setStarting({ scenario });
        }}
      />

      <RunHistory
        runs={runs}
        catalog={catalog.data}
        selectedId={search.run}
        onOpen={(runId) => {
          setSearch({ run: runId });
        }}
      />

      {search.run ? (
        <RunDrawer
          key={search.run}
          runId={search.run}
          catalog={catalog.data}
          stopping={stopping.has(search.run)}
          onStop={stop}
          onRunAgain={(scenario, run) => {
            setStarting({ scenario, prefill: run.params });
          }}
          onClose={() => {
            setSearch({ run: undefined });
          }}
        />
      ) : null}

      {starting ? (
        <StartDialog
          key={starting.scenario.name}
          scenario={starting.scenario}
          {...(starting.prefill ? { prefill: starting.prefill } : {})}
          onClose={() => {
            setStarting(undefined);
          }}
          onStarted={(run) => {
            actions.started(starting.scenario, run);
          }}
        />
      ) : null}

      <ConfirmDialog
        open={stopAll !== undefined}
        title={copy.confirm.stopAllTitle(stopAll?.title ?? '')}
        description={copy.confirm.stopAllBody(stopAll?.count ?? 0)}
        confirmLabel={copy.confirm.stopAllConfirm}
        tone="danger"
        onConfirm={async () => {
          if (stopAll) await actions.stopAll(stopAll);
        }}
        onOpenChange={(open) => {
          if (!open) setStopAll(undefined);
        }}
      />
    </div>
  );
}
