/**
 * The tool stream under a reply (Renma `TurnToolLogView`, T3 Code's work log): one compact row per tool
 * step, with its natural name, state and duration. Pure, so the tests read it without React.
 */
import type {Activity} from './session.ts';
import {firstLine} from './subagents.ts';

/**
 * What the agent says between its tool calls (a commentary `agentMessage`, e.g. "派发了两个子任务，等待回报"):
 * a progress note in the stream, not a step. It shows its own words instead of a generic "Update"
 * row, and is left out of the step count.
 */
export function isStatusNote(item: Pick<Activity, 'type'>): boolean {
  return item.type === 'agentMessage';
}

/** The note's one-line text: the first line of substance of its Markdown, clipped. */
export function statusNoteLine(text: string, max = 140): string {
  const line = firstLine(text).replace(/\s+/g, ' ').trim();
  return line.length <= max ? line : `${line.slice(0, max - 1)}…`;
}

export interface ToolStreamSummary {
  total: number;
  running: number;
  failed: number;
  stopped: number;
  /** 1-based step that is running now (the last running one), or 0. */
  current: number;
  /** From the first recorded start to the last recorded end (or `now` while running); null when unknown. */
  spanMs: number | null;
}

export function toolStreamSummary(all: readonly Activity[], now: number): ToolStreamSummary {
  const items = all.filter(item => !isStatusNote(item));
  const running = items.filter(item => item.status === 'running' || item.status === 'waiting').length;
  const failed = items.filter(item => item.status === 'failed').length;
  const stopped = items.filter(item => item.status === 'stopped').length;
  let current = 0;
  items.forEach((item, i) => { if (item.status === 'running' || item.status === 'waiting') current = i + 1; });
  const starts = items.map(item => item.startedAt).filter((v): v is number => typeof v === 'number');
  let spanMs: number | null = null;
  if (starts.length) {
    const start = Math.min(...starts);
    const ends = items.map(item => item.completedAt).filter((v): v is number => typeof v === 'number');
    if (running) spanMs = Math.max(0, now - start);
    else if (ends.length) spanMs = Math.max(0, Math.max(...ends) - start);
  }
  return {total: items.length, running, failed, stopped, current, spanMs};
}

/** One step's time: running from its start to `now`, settled from start to end; null when unrecorded. */
export function toolStepElapsed(item: Activity, now: number): number | null {
  if (!item.startedAt) return null;
  if (item.status === 'running' || item.status === 'waiting') return Math.max(0, now - item.startedAt);
  return item.completedAt ? Math.max(0, item.completedAt - item.startedAt) : null;
}

/** Rows a stream shows: all when open; while it runs and is not opened, the latest `tail` steps. */
export function visibleToolSteps<T>(items: readonly T[], open: boolean, live: boolean, tail = 3): {shown: readonly T[]; hidden: number} {
  if (open) return {shown: items, hidden: 0};
  if (!live) return {shown: [], hidden: items.length};
  const shown = items.slice(-tail);
  return {shown, hidden: items.length - shown.length};
}

/**
 * Detail text is the tool's own output. It can quote on-device paths (`/data/local/agentos/…`,
 * `/data/user/0/…`, `/sdcard/…`, `../acp/home/.gemini/…`): each becomes `…/<last name>`, so nothing
 * internal shows on the page.
 */
export function cleanToolDetail(text: string, max = 1200): string {
  const cleaned = text
    .replace(/(?:\.\.\/)*(?:\/?(?:data|sdcard|storage|system|mnt|acp|home|tmp|var|Users)\/|\.gemini\/)[^\s"'`)\]]*\/([^\s/"'`)\]]+)/g, '…/$1')
    .trim();
  return cleaned.length <= max ? cleaned : `${cleaned.slice(0, max - 1)}…`;
}
