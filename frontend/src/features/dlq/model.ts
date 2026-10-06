import type { components } from '@/api/generated/schema';
import type { ActionWindow, DlqSearch, DlqTab } from '@/features/dlq/search';
import { dlqCopy } from '@/i18n/dlq';
import { en } from '@/i18n/en';
import { actorName } from '@/lib/format';

// Pure logic of Dead letters (DOC-36 screens/ops-console-dlq): which statuses a tab shows, the E-40 filters of the URL,
// what a bulk action may touch and how an action of the log reads. Times are on the audit axis (DOC-34 §8).

type Schemas = components['schemas'];
export type DeadLetter = Schemas['DeadLetterItemResponse'];
export type DeadLetterDetail = Schemas['DeadLetterDetailResponse'];
export type DlqActionEntry = Schemas['ActionLogResponse'];
export type DlqAction = Schemas['ActionResponse'];
export type DlqSummary = Schemas['DeadLetterSummaryResponse'];
export type ReplayRef = Schemas['ReplayRefResponse'];

const copy = dlqCopy.dlq;
const SOURCE_LABELS: Record<string, string> = en.source;
const STATUS_LABELS: Record<string, string> = en.status.dlq;

/** E-40 and E-48 page size (§4). */
export const LIST_PAGE = 200;
/** Records a bulk action may touch at once (§6). */
export const MAX_SELECTED = 100;
/** Bulk requests in flight at once (§6, DOC-37 §2.7). */
export const BULK_CONCURRENCY = 4;
/** Longest payload E-43 takes (§6.2). */
export const MAX_PAYLOAD_BYTES = 1024 * 1024;
/** `reason` of E-46 and `note` of E-47 (3–500 characters). */
export const REASON_MIN = 3;
export const REASON_MAX = 500;

export const OPEN_STATUSES = [
  'NEW',
  'TRIAGING',
  'TRIAGED',
  'AUTO_REPLAY_SCHEDULED',
  'PENDING_CONFIRM',
  'MANUAL',
  'REPLAY_REQUESTED',
] as const;
export const CLOSED_STATUSES = ['REPLAYED', 'DISCARDED', 'RESOLVED'] as const;
export const AWAITING = 'PENDING_CONFIRM';
/** What E-44 and E-46 take (DOC-31 §7 table of the dead-letter state machine). */
const REPLAYABLE = new Set(['NEW', 'MANUAL']);
const DISCARDABLE = new Set(['NEW', 'MANUAL', 'PENDING_CONFIRM']);

export const SOURCES = [
  'GTFS_RT_VEHICLE_POSITION',
  'GTFS_RT_TRIP_UPDATE',
  'TICKETING_SALES',
  'TICKETING_SALE_POINTS',
] as const;
export const STAGES = ['DESERIALIZE', 'SCHEMA', 'BUSINESS', 'DEDUP', 'LOAD', 'QUALITY'] as const;
export const CATEGORIES = [
  'schema_violation',
  'referential_integrity',
  'upstream_api_error',
  'transient_network',
  'unknown',
  'unclassified',
] as const;
export const RULES = Object.keys(copy.dq);
export const ACTIONS = Object.keys(copy.action);

/** The statuses a tab lists, before the "Status" chip narrows them. */
export function tabStatuses(tab: DlqTab): readonly string[] {
  return tab === 'closed' ? CLOSED_STATUSES : tab === 'confirm' ? [AWAITING] : OPEN_STATUSES;
}

/** The statuses asked of E-40: the chip's choice inside the tab's group, or the whole group (§2). */
export function listStatuses(tab: DlqTab, chosen: readonly string[] | undefined): string[] {
  const group = tabStatuses(tab);
  if (tab === 'confirm') return [...group];
  const inGroup = (chosen ?? []).filter((status) => group.includes(status));
  return inGroup.length > 0 ? inGroup : [...group];
}

/** Filters of E-40 as the query key and the request carry them. `severity` is a list of strings for the API. */
export interface ListFilters {
  status: string[];
  source?: string[];
  stage?: string[];
  category?: string[];
  severity?: string[];
  ruleId?: string[];
  from?: string;
  to?: string;
}

function some<T>(list: readonly T[] | undefined): T[] | undefined {
  return list && list.length > 0 ? [...list] : undefined;
}

/** The tab's filters; "confirm" takes `source` and `category` only (§2). */
export function listFilters(tab: DlqTab, search: DlqSearch): ListFilters {
  const confirm = tab === 'confirm';
  return {
    status: listStatuses(tab, search.status),
    ...(some(search.source) ? { source: some(search.source) } : {}),
    ...(!confirm && some(search.stage) ? { stage: some(search.stage) } : {}),
    ...(some(search.category) ? { category: some(search.category) } : {}),
    ...(!confirm && some(search.severity) ? { severity: search.severity?.map(String) } : {}),
    ...(!confirm && some(search.rule) ? { ruleId: some(search.rule) } : {}),
    ...(!confirm && search.from ? { from: search.from } : {}),
    ...(!confirm && search.to ? { to: search.to } : {}),
  };
}

/** Whether a filter beyond the tab's own is on, for "No dead letters match these filters" and "Clear filters". */
export function isFiltered(tab: DlqTab, search: DlqSearch): boolean {
  if (tab === 'actions') return Boolean(search.action?.length) || search.actor !== undefined;
  const common = Boolean(search.source?.length) || Boolean(search.category?.length);
  if (tab === 'confirm') return common;
  const statuses = (search.status ?? []).filter((status) => tabStatuses(tab).includes(status));
  return (
    common ||
    statuses.length > 0 ||
    Boolean(search.stage?.length) ||
    Boolean(search.severity?.length) ||
    Boolean(search.rule?.length) ||
    search.from !== undefined ||
    search.to !== undefined
  );
}

/** The search params that "Clear filters" resets. */
export function clearedFilters(tab: DlqTab): Partial<DlqSearch> {
  return tab === 'actions'
    ? { action: undefined, actor: undefined }
    : {
        status: undefined,
        source: undefined,
        stage: undefined,
        category: undefined,
        severity: undefined,
        rule: undefined,
        from: undefined,
        to: undefined,
      };
}

export interface ActionFilters {
  action?: string[];
  actorType?: string;
  from: string;
  to: string;
}

const WINDOW_MS: Record<ActionWindow, number> = { '24h': 24 * 3_600_000, '7d': 7 * 24 * 3_600_000 };

/** E-48's filters; its window slides, so `now` is the machine clock of the fetch. */
export function actionFilters(search: DlqSearch, now: number): ActionFilters {
  const window = WINDOW_MS[search.window ?? '24h'];
  return {
    ...(some(search.action) ? { action: some(search.action) } : {}),
    ...(search.actor ? { actorType: search.actor } : {}),
    from: new Date(now - window).toISOString(),
    to: new Date(now).toISOString(),
  };
}

// ---------------------------------------------------------------------------------------------------------------------
// Rows

/** First characters of an id, as `IdText` shows it. */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** The code of a row: the rule, or the exception's short name for the stages without one (§4). */
export function errorCode(item: Pick<DeadLetter, 'ruleId' | 'errorClass'>): string {
  return item.ruleId ?? item.errorClass.split('.').at(-1) ?? item.errorClass;
}

export function sourceLabel(source: string): string {
  return SOURCE_LABELS[source] ?? source;
}

export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

export function categoryLabel(category: string | undefined): string {
  return copy.category[category ?? 'unclassified'] ?? category ?? copy.unclassified;
}

/** The pill on a row (§4): not on "Confirm", whose rows are all alike; on "Review" for anything but a plain `NEW`. */
export function showsStatus(tab: DlqTab, status: string): boolean {
  if (tab === 'confirm') return false;
  return tab === 'closed' || status !== 'NEW';
}

/** A row whose status left the tab's group stays, dimmed, until the next refetch (DOC-34 P-5). */
export function leftTab(tab: DlqTab, status: string): boolean {
  return !tabStatuses(tab).includes(status);
}

// ---------------------------------------------------------------------------------------------------------------------
// Selection and bulk actions

export type BulkKind = 'replay' | 'confirm' | 'discard';

/** Whether the status accepts the action (the table above E-43 in DOC-31 §7). */
export function accepts(kind: BulkKind, status: string): boolean {
  if (kind === 'confirm') return status === AWAITING;
  return (kind === 'replay' ? REPLAYABLE : DISCARDABLE).has(status);
}

/** Adds `ids` to the selection up to {@link MAX_SELECTED}; `capped` tells when some were left out. */
export function select(current: readonly string[], ids: readonly string[]): { selected: string[]; capped: boolean } {
  const selected = [...current];
  let capped = false;
  for (const id of ids) {
    if (selected.includes(id)) continue;
    if (selected.length >= MAX_SELECTED) {
      capped = true;
      break;
    }
    selected.push(id);
  }
  return { selected, capped };
}

export interface BulkOutcome {
  ok: string[];
  failed: { id: string; error: unknown }[];
}

/** Runs `task` over `ids`, at most `limit` at a time; `onProgress` gets the number done. Never rejects. */
export async function runBulk(
  ids: readonly string[],
  task: (id: string) => Promise<void>,
  onProgress: (done: number) => void,
  limit = BULK_CONCURRENCY,
): Promise<BulkOutcome> {
  const outcome: BulkOutcome = { ok: [], failed: [] };
  let next = 0;
  let done = 0;
  const worker = async () => {
    while (next < ids.length) {
      const id = ids[next++]!; // eslint-disable-line @typescript-eslint/no-non-null-assertion
      try {
        await task(id);
        outcome.ok.push(id);
      } catch (error: unknown) {
        outcome.failed.push({ id, error });
      }
      onProgress(++done);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, ids.length) }, worker));
  return outcome;
}

// ---------------------------------------------------------------------------------------------------------------------
// Discard and resolve

export const DISCARD_REASONS = ['duplicate', 'test', 'unrecoverable', 'other'] as const;
export type DiscardReason = (typeof DISCARD_REASONS)[number];

/** `reason` of E-46: the choice, then the note when there is one (§6). */
export function discardReasonText(choice: DiscardReason, note: string): string {
  const label = copy.dialogs.reasons[choice];
  const trimmed = note.trim();
  return trimmed ? `${label}: ${trimmed}` : label;
}

/** "Other" needs a note of 3 characters or more; the whole text stays within 500 (§6, AC-9). */
export function discardValid(choice: DiscardReason, note: string): boolean {
  const trimmed = note.trim();
  if (choice === 'other' && trimmed.length < REASON_MIN) return false;
  const text = discardReasonText(choice, note);
  return text.length >= REASON_MIN && text.length <= REASON_MAX;
}

// ---------------------------------------------------------------------------------------------------------------------
// Detail

/** The routing decision shown under the AI triage: the last triage action of the history (§6.1). */
export function routingDecision(actions: readonly Pick<DlqAction, 'action' | 'at'>[]): string | undefined {
  const routed = actions
    .filter((entry) => entry.action in copy.detailPanel.route)
    .sort((a, b) => b.at.localeCompare(a.at))[0];
  return routed ? copy.detailPanel.route[routed.action] : undefined;
}

/** Who did it (DOC-37 §3.1): `auto` is the triage worker, `system:<service>` a service, `user:<name>` a person. */
export function actorLabel(actor: string): string {
  if (actor === 'auto') return copy.autoTriage;
  if (actor.startsWith('system:')) return copy.systemService(actor.slice('system:'.length));
  if (actor === 'migration') return copy.system;
  if (actor.startsWith('experiment:')) return copy.experiment;
  return actorName(actor);
}

/** One line for `details` of an action: the reason, the note, the fields changed and the replay (§4). */
export function detailsSummary(details: Record<string, unknown>): string {
  const parts: string[] = [];
  for (const key of ['reason', 'note']) {
    const value = details[key];
    if (typeof value === 'string' && value) parts.push(value);
  }
  const paths = details.changed_paths;
  if (Array.isArray(paths) && paths.length > 0) parts.push(paths.map(String).join(', '));
  const replay = details.replay_request_id;
  if (typeof replay === 'string' && replay) parts.push(`replay ${shortId(replay)}`);
  return parts.join(' · ');
}

/** Whether a replay request has not ended: E-52 is polled until it has. */
export function replayOpen(status: string): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

/** `allowedActions` has the action (E-42; a viewer's list is always empty). */
export function allows(detail: Pick<DeadLetterDetail, 'allowedActions'>, action: string): boolean {
  return detail.allowedActions.includes(action);
}

/** The text the editor opens with: the edited payload, else the raw one, pretty-printed when it parses (§6.2). */
export function editorText(detail: Pick<DeadLetterDetail, 'editedPayload' | 'rawPayload'>): string {
  if (detail.editedPayload) return JSON.stringify(detail.editedPayload, null, 2);
  try {
    return JSON.stringify(JSON.parse(detail.rawPayload), null, 2);
  } catch {
    return detail.rawPayload;
  }
}

const FILE_NAMES: Record<string, string> = {
  GTFS_RT_VEHICLE_POSITION: 'vehicle_position',
  GTFS_RT_TRIP_UPDATE: 'trip_update',
  TICKETING_SALES: 'ticket_transaction',
  TICKETING_SALE_POINTS: 'sale_point',
};

/** "{entity_type}.json", the name of the code block (§6.1). */
export function payloadFileName(source: string): string {
  return `${FILE_NAMES[source] ?? source.toLowerCase()}.json`;
}

/** UTF-8 length, which is what the 1 MiB limit of E-43 counts. */
export function byteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

/** Message of `JSON.parse` for the editor's footer; `undefined` when `text` is valid JSON. */
export function jsonError(text: string): string | undefined {
  try {
    JSON.parse(text);
    return undefined;
  } catch (error: unknown) {
    return error instanceof Error ? error.message : String(error);
  }
}

/** Both texts are the same JSON value (formatting aside), so "Save" has nothing to save. */
export function sameJson(a: string, b: string): boolean {
  try {
    return JSON.stringify(JSON.parse(a)) === JSON.stringify(JSON.parse(b));
  } catch {
    return a === b;
  }
}
