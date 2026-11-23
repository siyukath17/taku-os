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

/** taku_phone tools by their bare name → the i18n key of their user-facing label. */
export const phoneToolKeys: Readonly<Record<string, string>> = {
  device_info: 'deviceInfo', observe_background: 'observeScreen', open_app: 'openApp', tap: 'tap', type_text: 'typeText', apps: 'apps',
  request_apps: 'requestApps', release_apps: 'releaseApps', screen_status: 'screenStatus', screenshot: 'screenshot',
  swipe: 'swipe', touch: 'touch',
};

/**
 * A taku_phone tool's text result as the stream shows it. The phone tools answer the agent in JSON
 * (`{"released":["com.xingin.xhs"],"held":[]}`, frame identities, app catalogues): machine data, not
 * something to read in the stream, so a JSON result keeps only its human `message`/`error` text and
 * is otherwise left out (the native thread keeps it whole). Plain text passes through.
 */
export function phoneToolResultText(text: string): string {
  const trimmed = text.trim();
  if (!/^[[{]/.test(trimmed)) return text;
  let value: unknown;
  try { value = JSON.parse(trimmed); } catch { return text; }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return '';
  const o = value as Record<string, unknown>;
  const error = o.error !== null && typeof o.error === 'object' ? (o.error as Record<string, unknown>).message : o.error;
  for (const v of [o.message, error]) if (typeof v === 'string' && v.trim()) return v.trim();
  return '';
}

/**
 * A tool's own words with taku_phone tool identifiers (`release_apps`, `taku_phone__open_app`,
 * `mcp_taku_phone_3013b55d_tap`, `taku_phone.screen_status()`) replaced by their labels, so an agent
 * hint like "release_apps it to close it" never shows an internal name.
 */
export function humanizeToolNames(text: string, label: (key: string) => string): string {
  return text.replace(/`?(?<![\w./-])(?:mcp_{0,2}taku_phone(?:__|_|\.|\/)(?:[0-9a-f]{8}_)?|taku_phone(?:__|_|\.|\/))?([a-z][a-z0-9]*(?:_[a-z0-9]+)*)(?![\w/-]|\.\w)(?:\(\))?`?/g,
    (found, tool: string) => {
      const key = phoneToolKeys[tool];
      const prefixed = /taku_phone/.test(found);
      // A bare word is a tool name only when it is a multi-word key (`open_app`), not plain prose (`tap`, `apps`).
      if (!key || (!prefixed && !tool.includes('_'))) return found;
      return label(key);
    });
}
