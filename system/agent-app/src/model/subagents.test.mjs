import test from 'node:test';
import assert from 'node:assert/strict';
import {formatDuration, subagentElapsed, subagentGroup, subagentGroupTitle, subagentTask} from './subagents.ts';
import {projectSession} from './session.ts';
import {chatPresentation} from './chatPresentation.ts';
import {hasResponse} from './optimisticChat.ts';
import {translate} from '../i18n/index.ts';

const card = (over = {}) => ({id: 'subagent-1', title: 'Device Status Inspector', role: 'Device Status Inspector', type: 'self', source: 'local',
  status: 'running', startedAt: 1_000, completedAt: 0, elapsedMs: 0, model: 'Gemini 4 Neon', goal: 'Check battery', activity: '读取设备信息',
  summary: '', report: '', ...over});
const dispatch = (id, subagent, extra = {}) => ({id, type: 'mcpToolCall', server: 'agy', tool: 'invoke_subagent', toolName: 'invoke_subagent',
  title: '派发子任务', status: 'completed', arguments: {Role: subagent?.title ?? 'Legacy Role', Prompt: 'legacy goal', TypeName: 'self', Model: 'inherit'},
  ...(subagent ? {subagent} : {}), ...extra});
const turn = (status, items) => ({thread: {id: 't', turns: [{id: 'turn-1', status, items: [
  {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'do two things'}]}, ...items]}]}, native: {}, historyComplete: true});

test('structured cards become subagent tasks, not activity rows', () => {
  const view = projectSession(turn('inProgress', [
    dispatch('a', card()),
    dispatch('b', card({id: 'subagent-2', title: 'Taku App Scanner', role: 'Taku App Scanner', status: 'completed', completedAt: 19_000, elapsedMs: 18_000,
      summary: 'Found 14 apps', report: '## Apps\n- Taku Agent', activity: ''})),
    {id: 'm', type: 'agentMessage', text: 'Dispatched both.'},
  ]));
  const reply = view.blocks.find(block => block.role === 'assistant');
  assert.equal(reply.activity.length, 0);
  assert.deepEqual(reply.subagents.map(task => [task.id, task.title, task.status]), [
    ['turn-1/subagent-1', 'Device Status Inspector', 'running'], ['turn-1/subagent-2', 'Taku App Scanner', 'completed']]);
  assert.equal(reply.subagents[0].activity, '读取设备信息');
  assert.equal(reply.subagents[1].elapsedMs, 18_000);
  assert.equal(reply.subagents[1].summary, 'Found 14 apps');
  assert.equal(reply.text, 'Dispatched both.');
});

test('legacy dispatches without a card still render, from the Role and the turn status', () => {
  const done = projectSession(turn('completed', [dispatch('a'), {id: 'f', type: 'agentMessage', phase: 'final_answer', text: 'All done'}]));
  const task = done.blocks.at(-1).subagents[0];
  assert.equal(task.title, 'Legacy Role');
  assert.equal(task.goal, 'legacy goal');
  assert.equal(task.status, 'completed');
  assert.equal(task.model, '', 'an inherited model is not shown as "inherit"');
  assert.equal(subagentElapsed(task, 99_000), null, 'no clock without times');
  assert.equal(projectSession(turn('inProgress', [dispatch('a')])).blocks.at(-1).subagents[0].status, 'running');
  assert.equal(projectSession(turn('interrupted', [dispatch('a')])).blocks.at(-1).subagents[0].status, 'stopped');
});

test('a card the turn outlived is not shown as running; ordinary Codex turns are unchanged', () => {
  const stale = projectSession(turn('completed', [dispatch('a', card())])).blocks.at(-1).subagents[0];
  assert.equal(stale.status, 'stopped');
  assert.equal(stale.activity, '');
  const codex = projectSession(turn('completed', [
    {id: 'x', type: 'mcpToolCall', server: 'taku_phone', tool: 'device_info', status: 'completed'},
    {id: 'f', type: 'agentMessage', phase: 'final_answer', text: 'ok'}]));
  assert.equal(codex.blocks.at(-1).subagents, undefined);
  assert.equal(codex.blocks.at(-1).activity.length, 1);
});

test('a reply with only subagent cards counts as a response and is never folded away', () => {
  const view = projectSession(turn('interrupted', [dispatch('a', card({status: 'interrupted', completedAt: 5_000, elapsedMs: 4_000}))]));
  const reply = view.blocks.at(-1);
  assert.ok(hasResponse(reply));
  assert.equal(chatPresentation(view.blocks).length, 2);
});

test('durations, live elapsed time and the group header', () => {
  assert.equal(formatDuration(8_400), '8s');
  assert.equal(formatDuration(65_000), '1m 05s');
  assert.equal(formatDuration(3_720_000), '1h 2m');
  const running = subagentTask(dispatch('a', card()), 'turn', 0, 'running');
  const done = subagentTask(dispatch('b', card({id: 'subagent-2', status: 'completed', completedAt: 19_000, elapsedMs: 18_000})), 'turn', 1, 'running');
  assert.equal(subagentElapsed(running, 13_000), 12_000);
  assert.equal(subagentElapsed(done, 99_000), 18_000);
  const zh = key => translate('zh', key), en = key => translate('en', key);
  const mixed = subagentGroup([running, done], 21_000);
  assert.deepEqual([mixed.running, mixed.completed, mixed.spanMs], [1, 1, 20_000]);
  assert.equal(subagentGroupTitle(mixed, zh), '2 个子任务 · 1 项进行中');
  assert.equal(subagentGroupTitle(subagentGroup([running, {...running, id: 'x'}], 2_000), zh), '正在并行推进 2 项子任务');
  const allDone = subagentGroup([done, {...done, id: 'y', completedAt: 25_000}], 99_000);
  assert.equal(allDone.spanMs, 24_000);
  assert.equal(subagentGroupTitle(allDone, zh), '2 个子任务 · 全部完成');
  assert.equal(subagentGroupTitle(allDone, en), '2 sub-tasks · all done');
  assert.equal(subagentGroupTitle(subagentGroup([done, {...done, id: 'z', status: 'failed'}], 0), zh), '2 个子任务 · 1 项未完成');
});
