/**
 * Subagent task cards: the work an agent hands off with `invoke_subagent`, one card per child.
 * The broker adds a structured `subagent` object to each such tool item (AcpSubagentCards); older
 * records carry only the call's arguments and still get a card, from the Role and the turn status.
 * Pure, so the tests read it without React.
 */
type Json = Record<string, unknown>;
const record = (v: unknown): Json => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Json : {};
const string = (v: unknown): string => typeof v === 'string' ? v : '';
const number = (v: unknown): number => typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : 0;

export type SubagentStatus = 'running' | 'completed' | 'failed' | 'stopped';
export interface SubagentTask {
  /** Unique in the conversation: `<turn>/<ordinal id>`. */
  id: string;
  title: string;
  type: string;
  model: string;
  /** `local` (on-device child) or `remote`. */
  source: string;
  status: SubagentStatus;
  /** Epoch ms; 0 when the record does not say (older turns). */
  startedAt: number;
  completedAt: number;
  elapsedMs: number;
  goal: string;
  activity: string;
  summary: string;
  report: string;
}

/** True for a tool item that dispatched a subagent. */
export function isSubagentItem(item: Json): boolean {
  if (item.type !== 'mcpToolCall' && item.type !== 'dynamicToolCall') return false;
  if (item.subagent && typeof item.subagent === 'object') return true;
  return item.server === 'agy' && (item.tool === 'invoke_subagent' || item.toolName === 'invoke_subagent');
}

function status(value: unknown): SubagentStatus | null {
  switch (value) {
    case 'running': case 'inProgress': case 'pending': return 'running';
    case 'completed': return 'completed';
    case 'failed': return 'failed';
    case 'interrupted': case 'cancelled': case 'stopped': return 'stopped';
    default: return null;
  }
}

/** Folds a turn's status into a card status for records without a structured card. */
function legacyStatus(item: Json, turnStatus: string): SubagentStatus {
  if (item.status === 'failed') return 'failed';
  if (turnStatus === 'running') return 'running';
  if (turnStatus === 'failed') return 'failed';
  if (turnStatus === 'stopped') return 'stopped';
  return 'completed';
}

const clip = (text: string, max: number) => text.length <= max ? text : `${text.slice(0, max - 1)}…`;

/** A card from a tool item, or null when the item is not a subagent dispatch. */
export function subagentTask(item: Json, turnId: string, index: number, turnStatus: string): SubagentTask | null {
  if (!isSubagentItem(item)) return null;
  const card = record(item.subagent), args = record(item.arguments);
  const ordinal = string(card.id) || string(item.id) || `subagent-${index}`;
  const title = string(card.title).trim() || string(args.Role).trim();
  const model = string(card.model).trim() || (string(args.Model) && string(args.Model) !== 'inherit' ? string(args.Model) : '');
  const resolved = status(card.status) ?? legacyStatus(item, turnStatus);
  // A turn that ended without the card saying so (an older broker) cannot still be running.
  const finalStatus = resolved === 'running' && ['completed', 'failed', 'stopped'].includes(turnStatus) ? 'stopped' : resolved;
  const startedAt = number(card.startedAt), completedAt = number(card.completedAt);
  return {
    id: `${turnId}/${ordinal}`, title: clip(title, 80), type: clip(string(card.type) || string(args.TypeName), 40),
    model: clip(model, 40), source: string(card.source) || 'local', status: finalStatus,
    startedAt, completedAt, elapsedMs: number(card.elapsedMs) || (startedAt && completedAt ? Math.max(0, completedAt - startedAt) : 0),
    goal: string(card.goal) || string(args.Prompt), activity: finalStatus === 'running' ? string(card.activity) : '',
    summary: string(card.summary), report: string(card.report),
  };
}

/** Milliseconds a card shows: the settled time, or the running time at `now`; null when unknown. */
export function subagentElapsed(task: SubagentTask, now: number): number | null {
  if (task.status !== 'running') return task.elapsedMs || (task.startedAt && task.completedAt ? task.completedAt - task.startedAt : null);
  return task.startedAt ? Math.max(0, now - task.startedAt) : null;
}

/** `8s`, `1m 05s`, `1h 2m`. */
export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
  if (h > 0) return m > 0 ? `${h}h ${m}m` : `${h}h`;
  if (m > 0) return `${m}m ${String(s).padStart(2, '0')}s`;
  return `${s}s`;
}

export interface SubagentGroupState {
  total: number;
  running: number;
  completed: number;
  failed: number;
  stopped: number;
  /** From the first start to the last end (or `now` while any runs); null when unknown. */
  spanMs: number | null;
}

export function subagentGroup(tasks: readonly SubagentTask[], now: number): SubagentGroupState {
  const count = (s: SubagentStatus) => tasks.filter(task => task.status === s).length;
  const starts = tasks.map(task => task.startedAt).filter(Boolean);
  const running = count('running');
  let spanMs: number | null = null;
  if (starts.length === tasks.length && starts.length) {
    const start = Math.min(...starts);
    const ends = tasks.map(task => task.completedAt).filter(Boolean);
    if (running) spanMs = Math.max(0, now - start);
    else if (ends.length) spanMs = Math.max(0, Math.max(...ends) - start);
  }
  return {total: tasks.length, running, completed: count('completed'), failed: count('failed'), stopped: count('stopped'), spanMs};
}

export type SubagentText = (key: SubagentTextKey) => string;
export type SubagentTextKey = 'subagentGroupRunning' | 'subagentGroupDone' | 'subagentGroupMixed' | 'subagentGroupPartial';

/** The group header: 「正在并行推进 2 项子任务」, 「2 个子任务 · 全部完成」, 「2 个子任务 · 1 项进行中」, 「2 个子任务 · 1 项未完成」. */
export function subagentGroupTitle(group: SubagentGroupState, t: SubagentText): string {
  const fill = (key: SubagentTextKey, n: number) => t(key).replace('{total}', String(group.total)).replace('{n}', String(n));
  if (group.running === group.total) return fill('subagentGroupRunning', group.total);
  if (group.running > 0) return fill('subagentGroupMixed', group.running);
  if (group.completed === group.total) return fill('subagentGroupDone', group.total);
  return fill('subagentGroupPartial', group.failed + group.stopped);
}
