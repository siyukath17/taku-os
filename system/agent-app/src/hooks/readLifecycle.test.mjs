import test from 'node:test';
import assert from 'node:assert/strict';
import {restoreConversation, readCurrent} from './readLifecycle.ts';

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};

test('an explicit conversation and local draft restore without waiting for the history index', async () => {
  const index = deferred(), selected = [], failures = [];
  await restoreConversation({preferred: 'known-thread', list: () => index.promise,
    select: async id => { selected.push(id); }, isCurrent: () => true, onListError: error => failures.push(error)});
  assert.deepEqual(selected, ['known-thread']);
  index.reject(new Error('index unavailable'));
  await Promise.resolve();
  assert.equal(failures.length, 1);
  assert.deepEqual(selected, ['known-thread']);
});

test('a failed index still restores the unsent local new-conversation draft', async () => {
  const selected = [], failures = [];
  await restoreConversation({preferred: '', list: async () => { throw new Error('offline'); },
    select: async id => { selected.push(id); }, isCurrent: () => true, onListError: error => failures.push(error)});
  assert.deepEqual(selected, ['']);
  assert.equal(failures.length, 1);
});

test('an index completing after explicit navigation does not select an older route', async () => {
  const index = deferred(), selected = [];
  let current = true;
  const restoring = restoreConversation({preferred: '', list: () => index.promise,
    select: async id => { selected.push(id); }, isCurrent: () => current, onListError: () => {}});
  current = false;
  index.resolve([{id: 'old-thread'}]);
  await restoring;
  assert.deepEqual(selected, []);
});

test('an already superseded startup route neither reloads history nor selects a conversation', async () => {
  const calls = [];
  await restoreConversation({preferred: 'old-route', list: async () => { calls.push('list'); return []; },
    select: async id => { calls.push(id); }, isCurrent: () => false, onListError: () => {}});
  assert.deepEqual(calls, []);
});

test('a local receipt read failure releases restoration without accepting a draft or enabling Send', async () => {
  let restoring = true, draftReady = false;
  const errors = [];
  await readCurrent({read: async () => { throw new Error('storage unavailable'); }, isCurrent: () => true,
    accept: () => { draftReady = true; }, reject: error => errors.push(error), settled: () => { restoring = false; }});
  assert.equal(restoring, false);
  assert.equal(draftReady, false);
  assert.equal(errors.length, 1);
});

test('a stalled workspace read neither blocks chat nor changes its connection/error state', async () => {
  const catalog = deferred(), messages = [], chatErrors = [], workspaceErrors = [];
  const preview = readCurrent({read: () => catalog.promise, isCurrent: () => true,
    accept: () => {}, reject: error => workspaceErrors.push(error)});
  await readCurrent({read: async () => 'new reply', isCurrent: () => true,
    accept: value => messages.push(value), reject: error => chatErrors.push(error)});
  assert.deepEqual(messages, ['new reply']);
  catalog.reject(new Error('preview unavailable'));
  await preview;
  assert.equal(workspaceErrors.length, 1);
  assert.deepEqual(chatErrors, []);
});

test('late success and failure from a previous thread are both ignored', async () => {
  for (const fail of [false, true]) {
    const pending = deferred(), calls = [];
    let current = true;
    const reading = readCurrent({read: () => pending.promise, isCurrent: () => current,
      accept: value => calls.push(value), reject: error => calls.push(error), settled: () => calls.push('settled')});
    current = false;
    if (fail) pending.reject(new Error('old error')); else pending.resolve('old history');
    await reading;
    assert.deepEqual(calls, []);
  }
});

test('a removed conversation is left for the newest remaining one, else a new conversation', async () => {
  const {selectionAfterRemoval, isThreadUnavailable, notifyConversationRemoved, onConversationRemoved} = await import('./readLifecycle.ts');
  assert.equal(selectionAfterRemoval('gone', [{id: 'newest'}, {id: 'older'}], false), 'newest');
  assert.equal(selectionAfterRemoval('gone', [{id: 'gone'}, {id: 'kept'}], true), 'kept');
  assert.equal(selectionAfterRemoval('gone', [], false), '');
  // An unconfirmed THREAD_UNAVAILABLE for a thread the fresh index still lists is transient.
  assert.equal(selectionAfterRemoval('listed', [{id: 'listed'}, {id: 'other'}], false), null);
  assert.equal(selectionAfterRemoval('', [{id: 'other'}], true), null);
  assert.equal(isThreadUnavailable({code: 'THREAD_UNAVAILABLE'}), true);
  assert.equal(isThreadUnavailable({code: 'RUNTIME_UNAVAILABLE'}), false);
  assert.equal(isThreadUnavailable(null), false);
  const seen = [], subscription = onConversationRemoved(id => seen.push(id));
  notifyConversationRemoved('gone'); notifyConversationRemoved('');
  subscription.remove(); notifyConversationRemoved('later');
  assert.deepEqual(seen, ['gone']);
});
