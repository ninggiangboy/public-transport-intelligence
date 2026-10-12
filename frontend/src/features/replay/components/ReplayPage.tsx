import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Lock } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { useAccess } from '@/app/access';
import { PageHeader } from '@/components/PageHeader';
import { ToneBadge } from '@/components/ToneBadge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { RequestWatcher } from '@/features/ops-jobs/components/RequestWatcher';
import { useRunRequests } from '@/features/ops-jobs/use-run-requests';
import { NewReplayForm } from '@/features/replay/components/NewReplayForm';
import { ReplayDrawer } from '@/features/replay/components/ReplayDrawer';
import { ReplayHistory } from '@/features/replay/components/ReplayHistory';
import { HREF, replayAgainSearch, type Replay } from '@/features/replay/model';
import { activeQuery } from '@/features/replay/queries';
import { replaySearch, type ReplaySearch, type ReplayTab } from '@/features/replay/search';
import { en } from '@/i18n/en';
import { replayCopy as copy } from '@/i18n/replay';
import { useDocumentTitle } from '@/lib/browser';
import { cn } from '@/lib/utils';

/** A viewer follows replays but cannot start one (§1). */
function ReadOnly() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex rounded-sm">
          <ToneBadge tone="neutral" icon={Lock} label={copy.readOnly} />
        </span>
      </TooltipTrigger>
      <TooltipContent>{copy.readOnlyHelp}</TooltipContent>
    </Tooltip>
  );
}

/** /ops/replay: replay a range of the raw zone, and follow every replay (DOC-36 screens/ops-console-replay). */
export function ReplayPage({ raw }: { raw: Record<string, unknown> }) {
  useDocumentTitle(copy.title);
  const navigate = useNavigate({ from: '/ops/replay' });
  const access = useAccess();
  const operator = access.role === 'operator';
  const requests = useRunRequests();
  // Bad values are dropped here, not in the route, so that the schema is not part of the first paint (DR-110).
  const search = useMemo(() => replaySearch.parse(raw), [raw]);
  // Viewers have no "New replay" (§1): `tab=new` falls back to the history.
  const tab: ReplayTab = operator ? (search.tab ?? 'new') : 'history';
  const active = useQuery({ ...activeQuery(), enabled: !access.pending && access.role !== undefined });
  const running = active.data?.data.items.length ?? 0;
  // "Replay again" opens a fresh form, which reads the URL once (§2).
  const [formKey, setFormKey] = useState(0);

  const setSearch = useCallback(
    (change: Partial<ReplaySearch>, replace = true) => {
      void navigate({ search: (previous) => ({ ...replaySearch.parse(previous), ...change }), replace });
    },
    [navigate],
  );

  useEffect(() => {
    if (!access.pending && !operator && search.tab === 'new') setSearch({ tab: 'history' });
  }, [access.pending, operator, search.tab, setSearch]);

  const setTab = (next: ReplayTab) => {
    if (next === tab) return;
    setSearch({ tab: next }, false);
  };
  const openReplay = useCallback(
    (id: string) => {
      setSearch({ tab: 'history', replay: id }, false);
    },
    [setSearch],
  );
  const replayAgain = (replay: Replay) => {
    setFormKey((key) => key + 1);
    setSearch(replayAgainSearch(replay), false);
  };
  const openRun = useCallback(
    (runId: string) => {
      void navigate({ href: HREF.run(runId) });
    },
    [navigate],
  );

  const tabs: ReplayTab[] = operator ? ['new', 'history'] : ['history'];

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[{ label: en.nav.groups.operations }, { label: copy.crumb }]}
        title={copy.heading}
        subtitle={copy.subtitle}
        actions={access.role === 'viewer' ? <ReadOnly /> : undefined}
      />
      <div role="tablist" aria-label={copy.tabs.label} className="flex border-b border-border">
        {tabs.map((value) => {
          const selected = tab === value;
          return (
            <button
              key={value}
              type="button"
              role="tab"
              id={`replay-tab-${value}`}
              aria-selected={selected}
              aria-controls="replay-panel"
              tabIndex={selected ? 0 : -1}
              onClick={() => {
                setTab(value);
              }}
              onKeyDown={(event) => {
                const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
                if (step === 0) return;
                event.preventDefault();
                const next = tabs[(tabs.indexOf(value) + step + tabs.length) % tabs.length] ?? value;
                setTab(next);
                document.getElementById(`replay-tab-${next}`)?.focus();
              }}
              className={cn(
                '-mb-px inline-flex h-10 items-center gap-1.5 border-b-2 px-2.5 text-sm font-medium whitespace-nowrap',
                selected
                  ? 'border-foreground text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground',
              )}
            >
              {copy.tabs[value]}
              {value === 'history' && running > 0 ? (
                <span className="inline-flex items-center gap-1 rounded-full bg-tone-progress-bg px-1.5 text-xs text-tone-progress-fg tabular-nums">
                  <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
                  {copy.running(running)}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      <div id="replay-panel" role="tabpanel" aria-labelledby={`replay-tab-${tab}`}>
        {tab === 'new' ? (
          <NewReplayForm key={formKey} search={search} onSent={openReplay} />
        ) : (
          <ReplayHistory
            kind={search.kind}
            status={search.status}
            selectedId={search.replay}
            onSearch={setSearch}
            onOpen={(id) => {
              setSearch({ replay: id }, false);
            }}
          />
        )}
      </div>
      {search.replay ? (
        <ReplayDrawer
          key={search.replay}
          id={search.replay}
          operator={operator}
          requests={requests}
          onReplayAgain={replayAgain}
          onClose={() => {
            setSearch({ replay: undefined }, false);
          }}
        />
      ) : null}
      {requests.tracked.map((request) => (
        <RequestWatcher key={request.id} request={request} onOpenRun={openRun} onDone={requests.done} />
      ))}
    </div>
  );
}
