import assert from 'node:assert/strict';
import test from 'node:test';
import {redirectSystemPath} from './assistantRoute.ts';

const open = (path, initial = true) => redirectSystemPath({path, initial});

test('task links retain the original decimal string on cold and warm entry', () => {
  const id = '9223372036854775807';
  assert.equal(open(`taku-agent:///task-detail?taskId=${id}`), `/task-detail?taskId=${id}`);
  assert.equal(open('taku-agent:///task-detail?taskId=017', false), '/task-detail?taskId=017');
});

test('invalid task links land on the readable task error without an ID', () => {
  for (const query of ['', 'taskId=../17', 'taskId=4%ZZ', 'taskId=12&taskId=34', 'taskId=123456789012345678901', 'taskId=17%0a']) {
    assert.equal(open(`taku-agent:///task-detail?${query}`), '/task-detail');
  }
});

test('other assistant links keep their own destinations and validated parameters', () => {
  const threadId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const lease = `threadId=${threadId}&screenKey=task-1234567890abcdef1234&generation=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb`;
  assert.equal(open('taku-agent:///'), '/');
  assert.equal(open('taku-agent:///system-ai?section=usage'), '/system-ai?section=usage');
  assert.equal(open('taku-agent:///system-ai?section=constructor'), '/system-ai');
  assert.equal(open(`taku-agent:///workspaces?${lease}`), `/workspaces?${lease}`);
  assert.equal(open('taku-agent://plugin-market'), '/plugin-market');
  assert.equal(open('taku-agent://connect'), '/connect');
  assert.equal(open('taku-agent://connect?token=x'), '/connect', 'connect takes no parameters');
  assert.equal(open(`taku-agent:///assistant-suggestion?captureId=${threadId}`), `/assistant-suggestion?captureId=${threadId}`);
  const management = `section=queue&threadId=${threadId}&taskId=42`;
  assert.equal(open(`taku-agent:///assistant-management?${management}`), `/assistant-management?${management}`);
  assert.equal(open('taku-agent:///assistant-management?threadId=invalid&taskId=1%2f2'), '/assistant-management');
});
