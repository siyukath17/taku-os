import test from 'node:test';
import assert from 'node:assert/strict';
import {projectSession} from './session.ts';
import {emptySessionStream, reduceSessionEvent, reduceSessionBatch, streamCursor, streamSessionRoot, sessionRootForDisplay, retainSessionBlocks, reconcileStreamHistory} from './sessionStream.ts';

const event = (seq, method, params = {}, brokerEpoch = 'epoch-a', threadId = 'thread') =>
  ({schema: 'taku.session-events/1', brokerEpoch, threadId, seq, method, params});
const snapshot = (seq = 0, turns = [], epoch = 'epoch-a') => event(seq, 'session/snapshot', {thread: {id: 'thread', turns}}, epoch);
const initial = () => reduceSessionEvent(emptySessionStream('thread'), snapshot());
const item = (state, id = 'answer', turnId = 'turn') => state.turns[turnId].items[id];
const delta = (seq, text, id = 'answer') => event(seq, 'item/agentMessage/delta', {turnId: 'turn', itemId: id, delta: text});
const completed = (seq, text, id = 'answer') => event(seq, 'item/completed', {turnId: 'turn', item: {id, type: 'agentMessage', text}});
const view = state => projectSession(streamSessionRoot(state, {native: {ready: true}}));

test('official deltas append verbatim; started keeps prefix; completed corrects, does not append', () => {
  let state = reduceSessionBatch(initial(), [delta(1, 'Hello '), delta(2, '中\ud83d'),
    event(3, 'item/started', {turnId: 'turn', item: {id: 'answer', type: 'agentMessage', text: '', phase: 'final_answer'}}),
    delta(4, '\ude00\n\n```ts\nconst n = 1;')]);
  assert.equal(item(state).text, 'Hello 中😀\n\n```ts\nconst n = 1;');
  assert.equal(view(state).blocks[0].parts[0].active, true);
  const key = view(state).blocks[0].parts[0].id;
  state = reduceSessionEvent(state, completed(5, 'Corrected final answer.'));
  assert.equal(item(state).text, 'Corrected final answer.');
  assert.equal(view(state).blocks[0].parts[0].active, false);
  assert.equal(view(state).blocks[0].parts[0].id, key);
  state = reduceSessionEvent(state, delta(6, ' late stale text'));
  assert.equal(item(state).text, 'Corrected final answer.');
  assert.equal(state.seq, 6);
});

test('completed without any delta is visible; each item finishes independently of its turn', () => {
  const state = reduceSessionBatch(initial(), [completed(1, 'First answer.', 'a'), delta(2, 'Second answer.', 'b')]);
  assert.deepEqual(view(state).blocks[0].parts, [
    {id: 'turn/a', text: 'First answer.', active: false}, {id: 'turn/b', text: 'Second answer.', active: true},
  ]);
  assert.equal(view(state).running, true);
});

test('terminal summary merges by item ID without erasing user, commentary, or tool records', () => {
  const state = reduceSessionBatch(initial(), [
    event(1, 'item/completed', {turnId: 'turn', item: {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Question'}]}}),
    event(2, 'item/completed', {turnId: 'turn', item: {id: 'c', type: 'agentMessage', phase: 'commentary', text: 'Checking'}}),
    event(3, 'item/completed', {turnId: 'turn', item: {id: 'tool', type: 'commandExecution', status: 'completed', command: 'pwd'}}),
    delta(4, 'Done'), event(5, 'turn/completed', {turn: {id: 'turn', status: 'completed', items: [{id: 'answer', type: 'agentMessage', text: 'Done.'}]}}),
  ]);
  assert.deepEqual(state.turns.turn.itemIds, ['u', 'c', 'tool', 'answer']);
  assert.equal(view(state).blocks[1].text, 'Done.');
  assert.deepEqual(view(state).blocks[1].activity.map(activity => activity.id), ['turn/c', 'turn/tool']);
  assert.equal(view(state).blocks[1].activity[0].status, 'completed');
  assert.equal(view(state).running, false);
});

test('duplicates are ignored, gaps freeze increments and force an authoritative snapshot', () => {
  const one = reduceSessionEvent(initial(), delta(1, 'A'));
  assert.equal(reduceSessionEvent(one, delta(1, 'A')), one);
  let state = reduceSessionEvent(one, delta(3, 'C'));
  assert.equal(state.needsResync, true);
  assert.equal(streamCursor(state), undefined);
  state = reduceSessionEvent(state, delta(2, 'B'));
  assert.equal(item(state).text, 'A');
  state = reduceSessionEvent(state, snapshot(3, [{id: 'turn', status: 'inProgress', items: [{id: 'answer', type: 'agentMessage', text: 'ABC'}]}]));
  assert.equal(item(state).text, 'ABC');
  assert.equal(state.needsResync, false);
  assert.deepEqual(streamCursor(state), {brokerEpoch: 'epoch-a', afterSeq: 3});
});

test('reconnect ready is a handshake; heartbeat does not advance cursor; repeated replay cannot double text', () => {
  const one = reduceSessionEvent(initial(), delta(1, 'A'));
  const resumed = reduceSessionBatch(one, [event(1, 'session/ready'), event(99, 'session/heartbeat'), delta(1, 'A'), delta(2, 'B')]);
  assert.equal(item(resumed).text, 'AB');
  assert.equal(resumed.seq, 2);
  assert.equal(reduceSessionEvent(resumed, event(1, 'session/ready')).needsResync, true);
});

test('new epoch replaces all rollout IDs and never merges old live text', () => {
  let state = reduceSessionEvent(initial(), delta(1, 'Live text'));
  state = reduceSessionEvent(state, event(2, 'item/agentMessage/delta', {turnId: 'turn', itemId: 'answer', delta: 'BAD'}, 'epoch-b'));
  assert.equal(state.needsResync, true);
  state = reduceSessionEvent(state, snapshot(0, [{id: 'turn', status: 'completed', items: [{id: 'item-0', type: 'agentMessage', text: 'Live text final'}]}], 'epoch-b'));
  assert.deepEqual(state.turns.turn.itemIds, ['item-0']);
  assert.equal(view(state).blocks[0].text, 'Live text final');
  assert.equal(state.brokerEpoch, 'epoch-b');
});

test('same-epoch atomic snapshot replaces IDs; delayed older snapshots and other threads are ignored', () => {
  let state = reduceSessionEvent(initial(), delta(1, 'A'));
  const other = {...delta(2, 'private'), threadId: 'other'};
  assert.equal(reduceSessionEvent(state, other), state);
  assert.equal(reduceSessionEvent(state, snapshot(0)), state);
  state = reduceSessionEvent(state, snapshot(2, [{id: 'turn', items: [{id: 'item-0', type: 'agentMessage', text: 'A final'}]}]));
  assert.deepEqual(state.turns.turn.itemIds, ['item-0']);
  assert.equal(view(state).blocks[0].parts[0].active, false);
});

test('cancel/error flush every part and sideband cannot revive a finished turn or overwrite text', () => {
  for (const status of ['interrupted', 'failed']) {
    const state = reduceSessionBatch(initial(), [delta(1, 'Partial'), event(2, 'turn/completed', {turn: {id: 'turn', status}})]);
    const projected = projectSession(streamSessionRoot(state, {thread: {id: 'thread', turns: []}, native: {ready: true, activeTurnId: 'turn', requests: [{key: 'q', params: {threadId: 'thread'}}]}}));
    assert.equal(projected.blocks[0].text, 'Partial');
    assert.equal(projected.blocks[0].parts[0].active, false);
    assert.equal(projected.running, false);
    assert.equal(projected.questions.length, 1);
  }
});

test('unchanged history and completed item objects retain React identities across a later delta', () => {
  let state = reduceSessionBatch(initial(), [completed(1, 'Finished', 'a'), delta(2, 'B', 'b')]);
  const before = view(state);
  state = reduceSessionEvent(state, delta(3, 'C', 'b'));
  const after = retainSessionBlocks(view(state), before);
  assert.equal(after.blocks[0].parts[0], before.blocks[0].parts[0]);
  assert.notEqual(after.blocks[0].parts[1], before.blocks[0].parts[1]);
  assert.equal(retainSessionBlocks(view(state), after).blocks[0], after.blocks[0]);
});

test('slow-consumer resync does not acknowledge a discarded sequence', () => {
  const state = reduceSessionBatch(initial(), [delta(1, 'A'), event(100, 'session/resync'), delta(101, 'unusable')]);
  assert.equal(state.seq, 1);
  assert.equal(item(state).text, 'A');
  assert.equal(state.needsResync, true);
});


test('a disk poll during disconnect cannot roll back streamed text; a fresh UI can still use polling fallback', () => {
  const fallback = {thread: {id: 'thread', turns: [{id: 'turn', status: 'inProgress', items: [
    {id: 'item-0', type: 'agentMessage', text: 'Old disk prefix'},
  ]}]}, native: {ready: true}};
  assert.equal(sessionRootForDisplay(emptySessionStream('thread'), fallback), fallback);
  const streamed = reduceSessionEvent(initial(), delta(1, 'New live prefix'));
  const disconnected = {...streamed, ready: false, needsResync: true};
  assert.equal(projectSession(sessionRootForDisplay(disconnected, fallback)).blocks[0].text, 'New live prefix');
  const recovered = reduceSessionEvent(disconnected, snapshot(4, [{id: 'turn', status: 'completed', items: [
    {id: 'item-0', type: 'agentMessage', text: 'Authoritative complete'},
  ]}]));
  assert.equal(projectSession(sessionRootForDisplay(recovered, fallback)).blocks[0].text, 'Authoritative complete');
});

test('history hydration metadata survives a later status-only poll while relay snapshot replaces turns', () => {
  const hydrated = {native: {ready: true}, historyComplete: true, historyStatus: 'complete', totalTurns: 1};
  const state = reduceSessionEvent(initial(), snapshot(1, [{id: 'historical', status: 'completed', items: [{id: 'item-0', type: 'agentMessage', text: 'Earlier answer'}]}]));
  const projected = projectSession(sessionRootForDisplay(state, {...hydrated, native: {ready: true, requests: []}, statusOnly: true}));
  assert.equal(projected.historyComplete, true);
  assert.equal(projected.blocks[0].text, 'Earlier answer');
});


test('new-epoch empty snapshots invalidate completed and known-empty history proofs', () => {
  const before = reduceSessionEvent(initial(), snapshot(3, [{id: 'turn', status: 'completed', items: []}]));
  const after = reduceSessionEvent(before, snapshot(0, [], 'epoch-b'));
  for (const sideband of [
    {thread: {id: 'thread', turns: [{id: 'turn'}]}, historyComplete: true, knownEmpty: false},
    {thread: {id: 'thread', turns: []}, historyComplete: true, knownEmpty: true},
  ]) {
    const invalidated = reconcileStreamHistory(before, after, sideband);
    assert.equal(invalidated.historyComplete, false);
    assert.equal(invalidated.knownEmpty, false);
    const display = projectSession(sessionRootForDisplay(after, invalidated));
    assert.equal(display.historyComplete, false);
    assert.equal(display.knownEmpty, false);
  }
});

test('same-epoch partial snapshots cannot retain a proof for omitted historical turns', () => {
  const before = reduceSessionEvent(initial(), snapshot(3, [{id: 'older', items: []}, {id: 'latest', items: []}]));
  const after = reduceSessionEvent(before, snapshot(4, []));
  const sideband = {thread: {id: 'thread', turns: [{id: 'older'}, {id: 'latest'}]}, historyComplete: true};
  assert.equal(reconcileStreamHistory(before, after, sideband).historyComplete, false);
  assert.equal(reconcileStreamHistory(before, before, sideband), sideband);
});


test('polling-only fallback keeps valid history proof before any stream has attached', () => {
  const empty = emptySessionStream('thread');
  const sideband = {thread: {id: 'thread', turns: [{id: 'turn'}]}, historyComplete: true};
  assert.equal(reconcileStreamHistory(empty, empty, sideband), sideband);
});

test('a late reaction lands on its user message in place, without advancing or breaking the sequence', () => {
  const user = {id: 'ask', type: 'userMessage', content: [{type: 'text', text: 'Thanks so much!'}]};
  let state = reduceSessionBatch(initial(), [event(1, 'item/started', {turnId: 'turn', item: user}), delta(2, 'You are welcome.')]);
  assert.equal(view(state).blocks.find(block => block.role === 'user').reaction, undefined);
  state = reduceSessionEvent(state, event(2, 'session/reaction', {turnId: 'turn', itemId: 'ask', reaction: '❤️'}));
  assert.equal(state.seq, 2, 'out of band: the sequence is unchanged');
  assert.equal(state.needsResync, false);
  assert.equal(view(state).blocks.find(block => block.role === 'user').reaction, '❤️');
  state = reduceSessionEvent(state, delta(3, ' Anytime.'));
  assert.equal(state.needsResync, false, 'the next native event still follows');
  assert.equal(view(state).blocks.find(block => block.role === 'user').reaction, '❤️');
  // Unknown messages, assistant items and another epoch are left alone.
  for (const stray of [event(3, 'session/reaction', {turnId: 'turn', itemId: 'missing', reaction: '👍'}),
    event(3, 'session/reaction', {turnId: 'turn', itemId: 'answer', reaction: '👍'}),
    event(3, 'session/reaction', {turnId: 'turn', itemId: 'ask', reaction: '👍'}, 'epoch-b')])
    assert.equal(reduceSessionEvent(state, stray), state);
});

test('a snapshot carries a stored reaction onto the user message; none shows no badge', () => {
  const state = reduceSessionEvent(emptySessionStream('thread'), snapshot(0, [{id: 'turn', status: 'completed', items: [
    {id: 'hi', type: 'userMessage', content: [{type: 'text', text: 'Hi Taku'}], reaction: '👋'},
    {id: 'plain', type: 'userMessage', content: [{type: 'text', text: 'What time is it?'}]},
  ]}]));
  const users = view(state).blocks.filter(block => block.role === 'user');
  assert.deepEqual(users.map(block => block.reaction), ['👋', undefined]);
});

test('before the first text, a dispatched subagent and its status changes reach the screen at once', () => {
  // The turn has started and the agent has only called tools so far: no text yet.
  const subagent = (status, activity = '') => ({id: 'acp-1-tool-c1', type: 'mcpToolCall', server: 'agy', tool: 'invoke_subagent', toolName: 'invoke_subagent',
    status: 'inProgress', subagent: {id: 'subagent-1', title: 'Research', provider: 'agy', status, startedAt: 1000, completedAt: 0, activity}});
  const turn = items => snapshot(0, [{id: 'turn', status: 'inProgress', items: [{id: 'u', type: 'userMessage', content: [{type: 'text', text: 'go'}]}, ...items]}]);
  const empty = view(reduceSessionEvent(emptySessionStream('thread'), turn([])));
  const dispatched = retainSessionBlocks(view(reduceSessionEvent(emptySessionStream('thread'), turn([subagent('running', 'Reading')]))), empty);
  assert.equal(dispatched.blocks[1].subagents?.length, 1);
  assert.equal(dispatched.blocks[1].subagents[0].provider, 'agy');
  const done = retainSessionBlocks(view(reduceSessionEvent(emptySessionStream('thread'), turn([subagent('completed')]))), dispatched);
  assert.equal(done.blocks[1].subagents[0].status, 'completed');
  // Nothing changed: the same block is kept, so the list does not re-render.
  const again = retainSessionBlocks(view(reduceSessionEvent(emptySessionStream('thread'), turn([subagent('completed')]))), done);
  assert.equal(again.blocks[1], done.blocks[1]);
});

test('tool steps keep their recorded start and end times for the tool stream', () => {
  const state = reduceSessionEvent(emptySessionStream('thread'), snapshot(0, [{id: 'turn', status: 'inProgress', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'go'}]},
    {id: 't1', type: 'mcpToolCall', server: 'agy', tool: 'read_file', status: 'completed', startedAt: 1000, completedAt: 4200},
    {id: 't2', type: 'mcpToolCall', server: 'agy', tool: 'run_command', status: 'inProgress', startedAt: 4300},
  ]}]));
  const [first, second] = view(state).blocks[1].activity;
  assert.deepEqual([first.startedAt, first.completedAt, first.titleKey], [1000, 4200, 'readFile']);
  assert.deepEqual([second.startedAt, second.completedAt, second.status], [4300, undefined, 'running']);
  assert.equal(view(state).provider, 'agy');
});
