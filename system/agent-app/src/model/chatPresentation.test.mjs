import test from 'node:test';
import assert from 'node:assert/strict';
import {chatPresentation} from './chatPresentation.ts';
import {projectSession} from './session.ts';

const request = {id: 'turn/u', turnId: 'turn', role: 'user', text: 'Check Clock', attachments: ['image'], activity: [], status: 'stopped', active: false};
const reply = {id: 'turn/after/u', turnId: 'turn', role: 'assistant', text: '', attachments: [], activity: [], status: 'stopped', active: false};

test('an empty native interruption stays with its request; task history remains intact', () => {
  const session = projectSession({thread: {id: 'clock', turns: [{id: 'turn', status: 'interrupted', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Check Clock'}]},
  ]}]}, native: {}, historyComplete: true});
  const nativeSnapshot = structuredClone(session.blocks);
  const chat = chatPresentation(session.blocks);
  assert.equal(chat.length, 1);
  assert.equal(chat[0].requestStopped, true);
  assert.equal(chat[0].text, 'Check Clock');
  assert.equal(chat[0].id, session.blocks[0].id);
  assert.deepEqual(session.blocks, nativeSnapshot);
  assert.equal(session.blocks.length, 2);
});

test('partial answers, tool history and attachments are never folded away', () => {
  for (const payload of [{text: 'Found Clock'}, {activity: [{id: 'tool'}]}, {attachments: ['image']},
    {parts: [{id: 'turn/reply', text: '', active: false, replyState: {phase: 'interrupted', code: 'TURN_INTERRUPTED'}}]}]) {
    const blocks = [request, {...reply, ...payload}];
    assert.deepEqual(chatPresentation(blocks), blocks);
  }
});

test('missing, unrelated or intervening requests retain a visible interruption', () => {
  for (const blocks of [[reply], [{...request, turnId: 'other'}, reply], [request, {...reply, id: 'partial', text: 'Checking'}, reply]]) {
    assert.deepEqual(chatPresentation(blocks), blocks);
  }
});

test('active, failed, completed and inconsistent states are not treated as empty interruptions', () => {
  for (const patch of [{active: true}, {status: 'failed'}, {status: 'completed'}, {status: 'running'}, {status: 'waiting'}]) {
    const blocks = [request, {...reply, ...patch}];
    assert.deepEqual(chatPresentation(blocks), blocks);
  }
  const mismatched = [{...request, status: 'completed'}, reply];
  assert.deepEqual(chatPresentation(mismatched), mismatched);
});

test('presentation does not persist a stopped caption when later native history supplies output', () => {
  const input = [request, reply];
  assert.equal(chatPresentation(input)[0].requestStopped, true);
  const refreshed = [request, {...reply, text: 'Partial response recovered'}];
  const chat = chatPresentation(refreshed);
  assert.equal(chat.length, 2);
  assert.equal(chat[0].requestStopped, undefined);
  assert.deepEqual(input, [request, reply]);
});

test('an accepted steer says it was delivered until the agent answers it', () => {
  const running = {id: 'turn/a', turnId: 'turn', role: 'assistant', text: 'Working on it', attachments: [], activity: [], status: 'running', active: true};
  const steer = {threadId: 't', requestId: 'r1', text: 'Also check Wi-Fi', action: 'steer', expectedTurnId: 'turn',
    startedAt: 0, baselineBlockIds: ['turn/a'], baselineTurnIds: ['turn'], state: 'accepted', turnId: 'turn'};
  const user = blocks => blocks.find(block => block.role === 'user' && block.text === 'Also check Wi-Fi');
  assert.equal(user(chatPresentation([running], [steer])).steerDelivered, true);
  for (const state of ['sending', 'failed', 'settled', 'stopped'])
    assert.notEqual(user(chatPresentation([running], [{...steer, state}]))?.steerDelivered, true, state);
  assert.notEqual(user(chatPresentation([running], [{...steer, action: 'sendMessage'}])).steerDelivered, true, 'a plain send');
  const answered = [running, {id: 'turn/u2', turnId: 'turn', role: 'user', text: 'Also check Wi-Fi', attachments: [], activity: [], status: 'running', active: false},
    {id: 'turn/a2', turnId: 'turn', role: 'assistant', text: 'Wi-Fi is on', attachments: [], activity: [], status: 'running', active: true}];
  assert.notEqual(user(chatPresentation(answered, [{...steer, userBlockId: 'turn/u2', assistantBlockId: 'turn/a2'}])).steerDelivered, true, 'answered');
});
