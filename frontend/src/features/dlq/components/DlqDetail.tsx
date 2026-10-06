import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';

import { newIdempotencyKey } from '@/api/client';
import { isApiError } from '@/api/problem';
import { ActivityTimeline } from '@/components/ActivityTimeline';
import { AppLink } from '@/components/AppLink';
import { Callout } from '@/components/Callout';
import { ConfidenceChip } from '@/components/ConfidenceChip';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { CopyButton } from '@/components/CopyButton';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { PanelSkeleton } from '@/components/PanelSkeleton';
import { SeverityBadge } from '@/components/SeverityBadge';
import { StatusPill } from '@/components/StatusPill';
import { Timestamp } from '@/components/Timestamp';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { DiscardFields } from '@/features/dlq/components/DiscardFields';
import { PayloadSection } from '@/features/dlq/components/PayloadSection';
import {
  actorLabel,
  allows,
  categoryLabel,
  detailsSummary,
  discardReasonText,
  discardValid,
  replayOpen,
  REASON_MAX,
  REASON_MIN,
  routingDecision,
  shortId,
  sourceLabel,
  statusLabel,
  type DeadLetterDetail,
  type DiscardReason,
  type ReplayRef,
} from '@/features/dlq/model';
import { detailQuery, replayQuery } from '@/features/dlq/queries';
import { useDlqActions } from '@/features/dlq/use-dlq-actions';
import { HREF } from '@/features/ops-jobs/model';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { formatCount, formatPercentWhole } from '@/lib/format';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';

const copy = dlqCopy.dlq;
const panel = copy.detailPanel;
/** Lines of `errorMessage` before "Show more". */
const MESSAGE_LINES = 10;

type Dialog = 'replay' | 'confirm' | 'discard' | 'resolve' | 'leave';

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h3 className="text-base font-semibold">{title}</h3>
      {children}
    </section>
  );
}

function ReplayRow({ replay }: { replay: ReplayRef }) {
  // The list of the detail says how the replay started; E-52 follows it until it ends (§5).
  const live = useQuery({ ...replayQuery(replay.id), enabled: replayOpen(replay.status) });
  const status = live.data?.data.status ?? replay.status;
  const finishedAt = live.data?.data.finishedAt ?? replay.finishedAt;
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
      <StatusPill domain="replay" status={status} size="sm" />
      <span className="text-muted-foreground">
        {panel.requestedBy(actorLabel(replay.requestedBy))}{' '}
        <Timestamp at={replay.requestedAt} seconds showZone={false} />
      </span>
      {finishedAt ? (
        <span className="text-muted-foreground">
          {panel.finished} <Timestamp at={finishedAt} seconds showZone={false} />
        </span>
      ) : null}
      <AppLink href={HREF.replay(replay.id)} className="text-primary hover:underline">
        {copy.replayLink}
      </AppLink>
    </li>
  );
}

function ErrorMessage({ message }: { message: string }) {
  const [more, setMore] = useState(false);
  const lines = message.split('\n');
  const long = lines.length > MESSAGE_LINES;
  return (
    <div>
      <p className="font-mono text-xs break-words whitespace-pre-wrap">
        {long && !more ? lines.slice(0, MESSAGE_LINES).join('\n') : message}
      </p>
      {long ? (
        <button
          type="button"
          className="mt-1 text-xs text-primary hover:underline"
          onClick={() => {
            setMore((value) => !value);
          }}
        >
          {more ? panel.showLess : panel.showMore}
        </button>
      ) : null}
    </div>
  );
}

function Triage({ detail }: { detail: DeadLetterDetail }) {
  const classified = detail.category !== undefined || detail.triagedAt !== undefined;
  const decision = routingDecision(detail.actions);
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div>
          <Callout
            tone="primary"
            title={
              <span className="flex flex-wrap items-center justify-between gap-2">
                <span>{panel.aiTriage(categoryLabel(detail.category))}</span>
                {detail.categoryConfidence !== undefined ? (
                  <ConfidenceChip value={detail.categoryConfidence} modelVersion={detail.modelVersion} />
                ) : null}
              </span>
            }
          >
            {classified ? (
              <div className="flex flex-col gap-0.5 text-sm">
                <p className="flex flex-wrap items-center gap-1.5">
                  {panel.severity}
                  <SeverityBadge severity={(detail.severity ?? null) as 0 | 1 | 2 | null} size="sm" />
                  {detail.severityConfidence !== undefined ? (
                    <span className="text-muted-foreground">({formatPercentWhole(detail.severityConfidence)})</span>
                  ) : null}
                  {decision ? <span>· {decision}</span> : null}
                </p>
                {detail.modelVersion && detail.triagedAt ? (
                  <p className="text-xs text-muted-foreground">
                    {panel.classified(detail.modelVersion)} <Timestamp at={detail.triagedAt} seconds /> ·{' '}
                    {copy.attempts(detail.triageAttempts)}
                  </p>
                ) : null}
              </div>
            ) : (
              <p>{panel.notClassified}</p>
            )}
          </Callout>
        </div>
      </TooltipTrigger>
      <TooltipContent>{copy.autoReplayHelp}</TooltipContent>
    </Tooltip>
  );
}

interface DlqDetailProps {
  id: string;
  operator: boolean;
  /** The editor holds changes: the page asks before another record opens (§6.2). */
  onDirtyChange: (dirty: boolean) => void;
  /** Wide screens show the record in place; the drawer of the Action log puts its own title around it. */
  onGone?: () => void;
}

/** One dead letter: what failed, what the AI made of it, its payload and its history (§6.1). */
export function DlqDetail({ id, operator, onDirtyChange, onGone }: DlqDetailProps) {
  const query = useQuery(detailQuery(id));
  const actions = useDlqActions();
  const detail = query.data?.data;

  const [editing, setEditing] = useState(false);
  const [session, setSession] = useState(0);
  const [dialog, setDialog] = useState<Dialog | undefined>(undefined);
  const [choice, setChoice] = useState<DiscardReason>('duplicate');
  const [note, setNote] = useState('');
  // One key per opening of a dialog: a retry of the same action reuses it (DOC-31 §8).
  const key = useRef(newIdempotencyKey());

  const open = (next: Dialog) => {
    key.current = newIdempotencyKey();
    if (next === 'discard') {
      setChoice('duplicate');
      setNote('');
    }
    setDialog(next);
  };
  const changeDirty = onDirtyChange;
  useEffect(
    () => () => {
      onDirtyChange(false);
    },
    [onDirtyChange],
  );

  // A replay that ends tells the person who watches it (§6): `dlq.changed` moves the status, the detail refetches.
  const previous = useRef<{ id: string; status: string } | undefined>(undefined);
  useEffect(() => {
    if (!detail) return;
    const before = previous.current;
    if (before?.id === detail.id && before.status === 'REPLAY_REQUESTED' && detail.status !== before.status) {
      if (detail.status === 'REPLAYED') notify.success(dlqCopy.dlq.toast.replayed);
      else if (detail.status === 'NEW') notify.warning(dlqCopy.dlq.toast.replayFailedAgain(detail.errorMessage));
    }
    previous.current = { id: detail.id, status: detail.status };
  }, [detail]);

  const history = useMemo(
    () => [...(detail?.actions ?? [])].sort((a, b) => b.at.localeCompare(a.at)),
    [detail?.actions],
  );

  const gone = isApiError(query.error) && query.error.status === 404;
  useEffect(() => {
    if (gone) onGone?.();
  }, [gone, onGone]);

  if (query.isPending) return <PanelSkeleton variant="detail" />;
  if (!detail) {
    if (gone) return <EmptyState title={copy.gone} />;
    return <ErrorState error={query.error} variant="block" panel={copy.detail} onRetry={() => void query.refetch()} />;
  }

  const canReplay = allows(detail, 'replay');
  const canConfirm = allows(detail, 'confirm');
  const canEdit = allows(detail, 'edit');
  const canDiscard = allows(detail, 'discard');
  const canResolve = allows(detail, 'resolve');
  const hasButtons = canDiscard || canResolve || canEdit || canConfirm || canReplay;
  const suggestion =
    detail.actions.findLast((entry) => entry.action === 'CONFIRM_REQUESTED')?.confidence ?? detail.categoryConfidence;
  const replayBody = detail.hasEditedPayload ? copy.dialogs.replayEdited : copy.dialogs.replayOriginal;

  const leaveEditor = () => {
    setEditing(false);
    changeDirty(false);
    setDialog(undefined);
  };

  return (
    <article
      aria-label={copy.detail}
      className={cn('flex flex-col gap-4 p-4', query.isPlaceholderData && 'opacity-60')}
    >
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center gap-0.5 font-mono text-base font-semibold" title={detail.id}>
              {shortId(detail.id)}
              <CopyButton value={detail.id} label={en.common.copyId} />
            </span>
            <span className="text-sm text-muted-foreground">
              {detail.ruleId ? (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span tabIndex={0} className="font-mono">
                      {detail.ruleId}
                    </span>
                  </TooltipTrigger>
                  <TooltipContent>{copy.dq[detail.ruleId] ?? detail.ruleId}</TooltipContent>
                </Tooltip>
              ) : null}
              {detail.ruleId ? ' · ' : null}
              {copy.stage[detail.stage] ?? detail.stage}
            </span>
            <StatusPill domain="dlq" status={detail.status} />
          </div>
          <p className="mt-1 text-sm text-muted-foreground">
            {sourceLabel(detail.source)} · {panel.received} <Timestamp at={detail.createdAt} seconds /> ·{' '}
            {panel.replayCount(detail.replayCount)}
          </p>
        </div>
        {!editing ? (
          <div className="flex flex-wrap items-center gap-2">
            {canDiscard ? (
              <Button
                variant="outline"
                size="sm"
                className="text-tone-danger-fg"
                onClick={() => {
                  open('discard');
                }}
              >
                {copy.buttons.discard}
              </Button>
            ) : null}
            {canResolve ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  open('resolve');
                }}
              >
                {copy.buttons.resolve}
              </Button>
            ) : null}
            {canEdit ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setSession((value) => value + 1);
                  setEditing(true);
                }}
              >
                {copy.buttons.edit}
              </Button>
            ) : null}
            {canConfirm ? (
              <Button
                size="sm"
                onClick={() => {
                  open('confirm');
                }}
              >
                {copy.buttons.confirmReplay}
              </Button>
            ) : canReplay ? (
              <Button
                size="sm"
                onClick={() => {
                  open('replay');
                }}
              >
                {copy.buttons.replay}
              </Button>
            ) : null}
            {!hasButtons && operator ? (
              <p className="text-sm text-muted-foreground">{panel.noActions(statusLabel(detail.status))}</p>
            ) : null}
          </div>
        ) : null}
      </header>

      <Triage detail={detail} />

      <PayloadSection
        key={`${detail.id}-${session}`}
        detail={detail}
        editing={editing}
        canReplay={canReplay}
        onDirty={changeDirty}
        onSave={(text) => actions.edit(detail.id, text)}
        onCancel={(changed) => {
          if (changed) open('leave');
          else leaveEditor();
        }}
        onClose={(_saved, thenReplay) => {
          setEditing(false);
          if (thenReplay) open('replay');
        }}
      />

      <Section title={panel.validation}>
        <div className="flex flex-col gap-1.5 rounded-lg border border-border p-3 text-sm">
          <p>
            <span className="font-mono font-medium">{detail.errorClass}</span>
            {detail.ruleId ? (
              <span className="text-muted-foreground">
                {' · '}
                {detail.ruleId} · {copy.dq[detail.ruleId] ?? ''}
              </span>
            ) : null}
          </p>
          <ErrorMessage message={detail.errorMessage} />
        </div>
      </Section>

      <Section title={panel.kafka}>
        {detail.kafka ? (
          <KeyValueList
            columns={2}
            items={[
              { label: panel.topic, value: <span className="font-mono">{detail.kafka.topic}</span> },
              { label: panel.partition, value: detail.kafka.partition },
              { label: panel.offset, value: formatCount(detail.kafka.offset) },
              {
                label: panel.timestamp,
                value: detail.kafka.timestamp ? <Timestamp at={detail.kafka.timestamp} seconds /> : en.kv.empty,
              },
              {
                label: panel.batch,
                value: (
                  <AppLink href={HREF.batch(detail.batchId)} className="font-mono text-primary hover:underline">
                    {shortId(detail.batchId)}
                  </AppLink>
                ),
              },
            ]}
          />
        ) : (
          <KeyValueList
            items={[
              {
                label: panel.batch,
                value: (
                  <AppLink href={HREF.batch(detail.batchId)} className="font-mono text-primary hover:underline">
                    {shortId(detail.batchId)}
                  </AppLink>
                ),
              },
            ]}
          />
        )}
      </Section>

      <Section title={panel.history}>
        <ActivityTimeline
          items={history.map((entry, index) => ({
            id: `${entry.at}-${entry.action}-${index}`,
            at: entry.at,
            axis: 'audit',
            tone: entry.action.includes('FAILED') ? 'danger' : entry.action === 'REPLAYED' ? 'success' : 'neutral',
            text: (
              <span>
                <span className="font-medium">{copy.action[entry.action] ?? entry.action}</span>
                <span className="text-muted-foreground">
                  {' · '}
                  {actorLabel(entry.actor)}
                  {entry.confidence === undefined ? '' : ` · ${formatPercentWhole(entry.confidence)}`}
                </span>
                {detailsSummary(entry.details) ? (
                  <span className="block text-xs text-muted-foreground">{detailsSummary(entry.details)}</span>
                ) : null}
              </span>
            ),
          }))}
        />
      </Section>

      <Section title={panel.replays}>
        {detail.replays.length === 0 ? (
          <p className="text-sm text-muted-foreground">{panel.noReplays}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {detail.replays.map((replay) => (
              <ReplayRow key={replay.id} replay={replay} />
            ))}
          </ul>
        )}
      </Section>

      <ConfirmDialog
        open={dialog === 'replay' || dialog === 'confirm'}
        title={copy.dialogs.replayTitle}
        description={
          <>
            <p>{replayBody}</p>
            {dialog === 'confirm' && suggestion !== undefined ? (
              <p className="mt-1">{copy.dialogs.suggested(formatPercentWhole(suggestion))}</p>
            ) : null}
          </>
        }
        confirmLabel={dialog === 'confirm' ? copy.buttons.confirmReplay : copy.buttons.replay}
        onConfirm={() =>
          actions.replay(detail.id, { kind: dialog === 'confirm' ? 'confirm' : 'replay', key: key.current })
        }
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
      />
      <ConfirmDialog
        open={dialog === 'discard'}
        tone="danger"
        title={copy.dialogs.discardTitle}
        description={copy.dialogs.discardBody}
        confirmLabel={copy.buttons.discardRecord}
        valid={discardValid(choice, note)}
        onConfirm={() => actions.discard(detail.id, discardReasonText(choice, note))}
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
      >
        <DiscardFields choice={choice} note={note} onChoice={setChoice} onNote={setNote} />
      </ConfirmDialog>
      <ConfirmDialog
        open={dialog === 'resolve'}
        title={copy.dialogs.resolveTitle}
        description={copy.dialogs.resolveBody}
        confirmLabel={copy.buttons.resolve}
        reason={{ label: copy.dialogs.note, min: REASON_MIN, max: REASON_MAX }}
        onConfirm={(reason) => actions.resolve(detail.id, reason ?? '')}
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
      />
      <ConfirmDialog
        open={dialog === 'leave'}
        title={copy.dialogs.discardChangesTitle}
        description={copy.dialogs.discardChangesBody}
        confirmLabel={copy.dialogs.discardChanges}
        cancelLabel={copy.dialogs.keepEditing}
        tone="danger"
        onConfirm={() => {
          leaveEditor();
          return Promise.resolve();
        }}
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
      />
    </article>
  );
}
