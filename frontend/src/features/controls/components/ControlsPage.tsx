import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import { useAccess } from '@/app/access';
import { useFreshness } from '@/app/freshness';
import { PageHeader } from '@/components/PageHeader';
import { FeedCard } from '@/features/controls/components/FeedCard';
import { AiFeaturesCard, PipelineCard } from '@/features/controls/components/FlagCards';
import { Anchored, ReadOnlyBadge } from '@/features/controls/components/parts';
import { RunJobCard } from '@/features/controls/components/RunJobCard';
import { feedsQuery } from '@/features/controls/queries';
import { useFlagToggle } from '@/features/controls/use-flag-toggle';
import { flagsQuery, recentSummaryQuery } from '@/features/ops-jobs/queries';
import { controlsCopy } from '@/i18n/controls';
import { en } from '@/i18n/en';
import { useDocumentTitle } from '@/lib/browser';
import { useBusinessClock } from '@/lib/business-clock';
import { useAxisNow } from '@/lib/use-now';
import { cn } from '@/lib/utils';

const copy = controlsCopy;

/** The anchors of the four blocks, for links from runbooks (§2); `#flags` is the old name of `#pipeline`. */
const SECTIONS = [
  { id: 'pipeline', label: copy.sections.pipeline },
  { id: 'ai', label: copy.sections.ai },
  { id: 'feeds', label: copy.sections.feeds },
  { id: 'run-job', label: copy.sections.runJob },
] as const;
type SectionId = (typeof SECTIONS)[number]['id'];
const ALIASES: Record<string, SectionId> = { flags: 'pipeline' };

function sectionOf(hash: string): SectionId | undefined {
  const name = hash.replace(/^#/, '');
  return ALIASES[name] ?? SECTIONS.find((section) => section.id === name)?.id;
}

function scrollTo(id: SectionId) {
  document.getElementById(id)?.scrollIntoView({ block: 'start' });
}

/** /ops/controls: runtime flags, the schedule feed and jobs on demand (DOC-36 screens/ops-console-controls). */
export function ControlsPage() {
  useDocumentTitle(copy.title);
  const access = useAccess();
  const operator = access.role === 'operator';
  const freshness = useFreshness();
  const clock = useBusinessClock();
  const flags = useQuery(flagsQuery());
  const summary = useQuery(recentSummaryQuery());
  const feeds = useQuery(feedsQuery());
  const toggle = useFlagToggle();
  const [current, setCurrent] = useState<SectionId>(() => sectionOf(window.location.hash) ?? 'pipeline');
  // A block jumped to (anchor or contents) stays marked until the user scrolls, even when it can't reach the top.
  const pinned = useRef(false);
  const jump = (id: SectionId) => {
    pinned.current = true;
    setCurrent(id);
    scrollTo(id);
  };

  // A link with an anchor (#run-job from Pipeline, #flags from an old runbook) lands on its block, once the blocks above
  // it have their height (skeletons are shorter than the data). The hash is read from the location rather than the
  // router state, whose hook would leave the first paint as a chunk of its own (DR-112).
  const [anchor, setAnchor] = useState(() => sectionOf(window.location.hash));
  const settled = !flags.isPending && !summary.isPending && !feeds.isPending;
  useEffect(() => {
    if (!anchor || !settled) return;
    const frame = requestAnimationFrame(() => {
      jump(anchor);
      setAnchor(undefined);
    });
    return () => {
      cancelAnimationFrame(frame);
    };
  }, [anchor, settled]);

  // The contents mark the first block in the upper part of the view (§4). The observer reports only the blocks that
  // changed, so the ones in view are kept here; at the bottom of the page the last block, which can't scroll that far
  // up, is the one marked.
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    const inView = new Set<SectionId>();
    const pick = () => {
      if (pinned.current) return;
      const root = document.documentElement;
      const atBottom = root.scrollTop > 0 && window.innerHeight + root.scrollTop >= root.scrollHeight - 4;
      const id = atBottom ? SECTIONS.at(-1)?.id : SECTIONS.find((section) => inView.has(section.id))?.id;
      if (id) setCurrent(id);
    };
    const unpin = () => {
      pinned.current = false;
    };
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const id = sectionOf(entry.target.id);
          if (!id) continue;
          if (entry.isIntersecting) inView.add(id);
          else inView.delete(id);
        }
        pick();
      },
      { rootMargin: '0px 0px -60% 0px' },
    );
    for (const section of SECTIONS) {
      const element = document.getElementById(section.id);
      if (element) observer.observe(element);
    }
    window.addEventListener('scroll', pick, { passive: true });
    const userScroll = ['wheel', 'touchmove', 'keydown'] as const;
    for (const type of userScroll) window.addEventListener(type, unpin, { passive: true });
    return () => {
      observer.disconnect();
      window.removeEventListener('scroll', pick);
      for (const type of userScroll) window.removeEventListener(type, unpin);
    };
  }, []);

  const flagState = {
    flags: flags.data?.data.items,
    error: flags.error,
    onRetry: () => void flags.refetch(),
  };
  const offset = freshness.data?.clockOffset;
  // Re-read the business clock once E-60 has answered, and every 30 s: the job form checks against it (§6.1).
  const now = useAxisNow(freshness.data?.businessNow, 'event');
  const jobClock = { now, timeZone: clock.timezone };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        crumbs={[{ label: en.nav.groups.operations }, { label: copy.crumb }]}
        title={copy.title}
        subtitle={copy.subtitle}
        actions={access.role === 'viewer' ? <ReadOnlyBadge /> : undefined}
      />
      <div className="grid gap-6 lg:grid-cols-[180px_minmax(0,1fr)]">
        <nav aria-label={copy.toc} className="hidden lg:block">
          <ul className="sticky top-4 flex flex-col gap-0.5">
            {SECTIONS.map((section) => (
              <li key={section.id}>
                <a
                  href={`#${section.id}`}
                  aria-current={current === section.id ? 'location' : undefined}
                  onClick={(event) => {
                    event.preventDefault();
                    jump(section.id);
                    window.history.replaceState(window.history.state, '', `#${section.id}`);
                  }}
                  className={cn(
                    'block rounded-md px-2.5 py-1.5 text-sm',
                    current === section.id
                      ? 'bg-muted font-medium text-foreground'
                      : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                  )}
                >
                  {section.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
        <div className="flex min-w-0 flex-col gap-4">
          <Anchored id="pipeline">
            <PipelineCard
              state={flagState}
              summary={summary.data?.data}
              sources={freshness.data?.sources}
              operator={operator}
              toggle={toggle}
            />
          </Anchored>
          <Anchored id="ai">
            <AiFeaturesCard state={flagState} operator={operator} toggle={toggle} />
          </Anchored>
          <Anchored id="feeds">
            <FeedCard feeds={feeds} operator={operator} />
          </Anchored>
          <Anchored id="run-job">
            <RunJobCard
              operator={operator}
              clock={jobClock}
              simulatedClock={offset !== undefined && offset !== 'PT0S'}
            />
          </Anchored>
        </div>
      </div>
    </div>
  );
}
