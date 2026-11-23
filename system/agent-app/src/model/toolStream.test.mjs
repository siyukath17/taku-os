import test from 'node:test';
import assert from 'node:assert/strict';
import {cleanToolDetail, humanizeToolNames, phoneToolResultText, isStatusNote, statusNoteLine, toolStepElapsed, toolStreamSummary, visibleToolSteps} from './toolStream.ts';
import {firstLine, formatClock} from './subagents.ts';
import {runtimeFamily} from './provider.ts';

const step = (status, startedAt, completedAt) => ({id: `s${startedAt}`, type: 'mcpToolCall', titleKey: 'readFile', name: '', text: '', status,
  ...(startedAt ? {startedAt} : {}), ...(completedAt ? {completedAt} : {})});

test('the stream summary counts steps and times the span', () => {
  const items = [step('completed', 1000, 3000), step('failed', 3000, 3500), step('running', 4000)];
  assert.deepEqual(toolStreamSummary(items, 9000), {total: 3, running: 1, failed: 1, stopped: 0, current: 3, spanMs: 8000});
  const done = [step('completed', 1000, 3000), step('completed', 3000, 6500)];
  assert.equal(toolStreamSummary(done, 99_000).spanMs, 5500);
  assert.equal(toolStreamSummary([step('completed')], 1).spanMs, null);
  assert.equal(toolStepElapsed(step('running', 1000), 4500), 3500);
  assert.equal(toolStepElapsed(step('completed', 1000, 2200), 9999), 1200);
  assert.equal(toolStepElapsed(step('completed'), 9999), null);
});

test('a running stream shows its latest steps; a settled one folds until opened', () => {
  const items = [1, 2, 3, 4, 5];
  assert.deepEqual(visibleToolSteps(items, false, true), {shown: [3, 4, 5], hidden: 2});
  assert.deepEqual(visibleToolSteps(items, false, false), {shown: [], hidden: 5});
  assert.deepEqual(visibleToolSteps(items, true, false), {shown: items, hidden: 0});
});

test('tool details never show on-device paths', () => {
  assert.equal(cleanToolDetail('File ../acp/home/.gemini/tmp/abc/notes.md read'), 'File …/notes.md read');
  assert.equal(cleanToolDetail('cat /data/local/agentos/acp/home/x/report.txt'), 'cat …/report.txt');
  assert.equal(cleanToolDetail('/data/user/0/org.agentos.assistant/files/a.png ok'), '…/a.png ok');
  assert.equal(cleanToolDetail('plain output'), 'plain output');
});

test('clocks, report lines and runtime families', () => {
  assert.equal(formatClock(42_000), '0:42');
  assert.equal(formatClock(125_000), '2:05');
  assert.equal(formatClock(3_725_000), '1:02:05');
  assert.equal(firstLine('## 结论\n- **电量** 82%'), '电量 82%');
  assert.equal(firstLine('## Only heading'), 'Only heading');
  assert.equal(runtimeFamily('agy', ''), 'agy');
  assert.equal(runtimeFamily('', 'Gemini 3 Pro'), 'agy');
  assert.equal(runtimeFamily('', 'claude-opus-5-5'), 'claude');
  assert.equal(runtimeFamily('openai', 'gpt-5'), 'codex');
  assert.equal(runtimeFamily('', 'Claude Sonnet', true), 'agy');
  assert.equal(runtimeFamily('', ''), '');
});

test('the agent\'s words between steps are status notes, not "Update" steps', async () => {
  const {projectSession} = await import('./session.ts');
  const view = projectSession({thread: {id: 't', turns: [{id: 'turn', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'check'}]},
    {id: 'c', type: 'agentMessage', phase: 'commentary', text: '## Plan\n\nDispatched **two** sub-tasks; waiting for their reports.\nMore detail.'},
    {id: 'tool', type: 'mcpToolCall', server: 'agy', tool: 'read_file', status: 'completed', startedAt: 1000, completedAt: 2000},
    {id: 'a', type: 'agentMessage', phase: 'final_answer', text: 'Done.'},
  ]}]}, native: {ready: true}});
  const activity = view.blocks.flatMap(block => block.activity);
  const note = activity.find(item => item.type === 'agentMessage');
  assert.equal(note.titleKey, 'statusNote');
  assert.equal(note.name, 'Dispatched two sub-tasks; waiting for their reports.');
  assert.ok(isStatusNote(note) && !isStatusNote(activity.find(item => item.type === 'mcpToolCall')));
  // Notes are not counted as steps.
  assert.deepEqual(toolStreamSummary(activity, 9000), {total: 1, running: 0, failed: 0, stopped: 0, current: 0, spanMs: 1000});
  assert.equal(statusNoteLine('x'.repeat(200)).length, 140);
  assert.equal(statusNoteLine(''), '');
});

test('status note labels are bilingual', async () => {
  const {textFor} = await import('../i18n/index.ts');
  assert.equal(textFor('en')('statusNote'), 'Status update');
  assert.equal(textFor('zh')('statusNote'), '进度同步');
});

test('BUG-0033: phone tool results drop JSON and tool identifiers read as labels', () => {
  assert.equal(phoneToolResultText('{"released":["com.xingin.xhs"],"held":[]}'), '');
  assert.equal(phoneToolResultText('[1,2]'), '');
  assert.equal(phoneToolResultText('{"error":{"code":"X","message":"Screen is busy"}}'), 'Screen is busy');
  assert.equal(phoneToolResultText('{"error":"SCREEN_BUSY"}'), 'SCREEN_BUSY');
  assert.equal(phoneToolResultText('{not json'), '{not json');
  assert.equal(phoneToolResultText('Tapped'), 'Tapped');
  const zh = {releaseApps: '释放后台专屏', requestApps: '申请后台专屏', openApp: '打开应用', tap: '点击屏幕', screenStatus: '查看专屏状态'};
  const t = key => zh[key] || key;
  assert.equal(humanizeToolNames('or release_apps it to close it', t), 'or 释放后台专屏 it to close it');
  assert.equal(humanizeToolNames('call `taku_phone__open_app` then mcp_taku_phone_3013b55d_tap', t), 'call 打开应用 then 点击屏幕');
  assert.equal(humanizeToolNames('taku_phone.screen_status() says busy', t), '查看专屏状态 says busy');
  // Plain prose and unrelated identifiers stay.
  assert.equal(humanizeToolNames('tap the apps icon; my_release_apps_v2 and foo_bar stay', t), 'tap the apps icon; my_release_apps_v2 and foo_bar stay');
});
