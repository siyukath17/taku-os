import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';

const runtime = {current: null};
globalThis[Symbol.for('taku.controller.route-test')] = runtime;
const mock = source => `data:text/javascript,${encodeURIComponent(`const runtime = globalThis[Symbol.for('taku.controller.route-test')];\n${source}`)}`;
const mocks = new Map([
  ['react', mock(`export const useState = initial => runtime.current.state(initial);
    export const useRef = initial => runtime.current.ref(initial);
    export const useMemo = (compute, dependencies) => runtime.current.memo(compute, dependencies);
    export const useCallback = (callback, dependencies) => runtime.current.memo(() => callback, dependencies);
    export const useEffect = (effect, dependencies) => runtime.current.effect(effect, dependencies);`)],
  ['react-native', mock(`export const AppState = {currentState: 'active', addEventListener() {return {remove() {}};}};
    export const BackHandler = {addEventListener() {return {remove() {}};}};`)],
  ['expo-localization', mock(`export const useLocales = () => [{languageCode: 'en'}];`)],
  ['../native/composerPicker', mock(`export const pickComposerAttachment = async () => null;
    export const onComposerAttachment = listener => {
      runtime.attachmentListeners.add(listener);
      return {remove() {runtime.attachmentListeners.delete(listener);}};
    };`)],
  ['@react-native-async-storage/async-storage', mock(`export default {
    getItem: async key => runtime.storage.get(key) ?? null,
    getAllKeys: async () => [...runtime.storage.keys()],
    setItem: async (key, value) => {runtime.storage.set(key, value);},
    removeItem: async key => {runtime.events.push(['remove', key]); runtime.storage.delete(key);},
  };`)],
  ['../native/agent', mock(`export const callAgent = (...args) => runtime.callAgent(...args);
    export const newRequestId = () => 'request-' + ++runtime.sequence;
    export const launchThreadId = () => runtime.threadId;
    export const launchPanelTarget = async () => runtime.target && !runtime.consumed.has(runtime.target.launchId) ? {...runtime.target} : null;
    export const isPanelLaunchConsumed = id => runtime.consumed.has(id);
    export const consumePanelLaunch = async id => {
      if (runtime.failConsumption) throw new Error('disk full');
      runtime.consumed.add(id); runtime.events.push(['consume', id]);
    };
    export const onOpenThread = listener => {runtime.openListeners.add(listener); return {remove() {runtime.openListeners.delete(listener);}};};
    export const selectConversation = id => {runtime.target = null; runtime.threadId = id || '';};
    export const subscribeSession = () => ({remove() {}});
    export const watchConversationVisible = listener => {listener(true); return {remove() {}};};
    export const closeAgent = async () => {};
    export class AgentGatewayError extends Error {constructor(message, code = 'GATEWAY_UNAVAILABLE') {super(message); this.code = code;}};`)],
]);
const hookUrl = new URL('./useAgentController.ts', import.meta.url).href;
registerHooks({resolve(specifier, context, nextResolve) {
  if (mocks.has(specifier) && (specifier !== 'react' || context.parentURL === hookUrl))
    return {url: mocks.get(specifier), shortCircuit: true};
  if (specifier.startsWith('.') && !/\.[a-z]+$/.test(specifier)) return nextResolve(`${specifier}.ts`, context);
  return nextResolve(specifier, context);
}});
const {useAgentController} = await import('./useAgentController.ts');

const target = {panelId: 'panel-a', sourceVersion: 3, originThreadId: 'thread-a', launchId: 'launch-a'};
const pendingKey = id => `taku.agent.pending.v1.${id}`;
const draftKey = id => `taku.agent.draft.v1.${id}`;
const accepted = (args, panelId) => ({threadId: panelId === 'panel-b' ? 'thread-b' : 'thread-a',
  requestId: args.requestId, state: 'queued', ...(panelId ? {panelId} : {})});
const deferred = () => {let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve};};

function reset(t) {
  Object.assign(runtime, {sequence: 0, storage: new Map(), drafts: new Map(), receipts: new Map(), consumed: new Set(),
    events: [], calls: [], lookups: [], completed: [], openListeners: new Set(), attachmentListeners: new Set(), target: {...target}, threadId: 'thread-a',
    failConsumption: false, mounts: [], receipt: async (args, panelId) => accepted(args, panelId)});
  runtime.callAgent = async (action, params = {}) => {
    const threadId = params.threadId;
    const snapshot = () => runtime.drafts.get(threadId) || {threadId, revision: '', text: '', attachments: []};
    if (action === 'composer/read') return snapshot();
    if (action === 'composer/write' || action === 'composer/import') {
      const next = {...snapshot(), text: params.text, revision: `draft-${++runtime.sequence}`};
      runtime.drafts.set(threadId, next);
      return next;
    }
    if (action === 'composer/preserveDraft') {
      const current = snapshot();
      const recovery = current.recovery ?? [];
      const next = recovery.some(item => item.id === params.recoveryId) ? current : {...current, recovery: [...recovery,
        {id: params.recoveryId, text: params.text, status: 'saved-draft', attachments: []}]};
      runtime.drafts.set(threadId, next);
      return next;
    }
    if (action === 'composer/submit') {
      const args = {...params, text: snapshot().text};
      assert.equal(params.revision, snapshot().revision);
      runtime.calls.push(args);
      const receipt = await runtime.receipt(args, args.panelId);
      runtime.receipts.set(args.requestId, receipt);
      if (receipt.state === 'queued') {
        runtime.completed.push({...args, threadId: receipt.threadId});
        if (snapshot().revision === params.revision) runtime.drafts.set(threadId, {...snapshot(), text: '', revision: `draft-${++runtime.sequence}`});
      }
      return receipt;
    }
    if (action === 'composer/receipt') {
      runtime.lookups.push(params);
      if (params.action) return runtime.receipt(params, params.panelId);
      return runtime.receipts.get(params.requestId) || {threadId, requestId: params.requestId, state: 'missing'};
    }
    if (action === 'composer/settle') return runtime.receipts.get(params.requestId) || {threadId, requestId: params.requestId, state: 'missing'};
    if (action === 'listThreads') return {threads: [{id: 'thread-a'}, {id: 'thread-b'}]};
    if (action === 'readSession') return {native: {ready: true}, historyComplete: true,
      thread: {id: params.threadId, turns: runtime.completed.filter(item => item.threadId === params.threadId).map(item => ({
        id: `turn-${item.requestId}`, status: 'completed', items: [
          {id: `user-${item.requestId}`, type: 'userMessage', content: [{type: 'text', text: item.text}]},
          {id: `answer-${item.requestId}`, type: 'agentMessage', text: 'Completed'},
        ],
      }))}};
    return {};
  };
  t.after(() => {for (const mounted of runtime.mounts) mounted.unmount();});
}

function mount() {
  const host = {
    slots: [], queued: [], index: 0, dirty: true, active: true, result: null,
    state(initial) {
      const at = this.index++;
      if (!this.slots[at]) this.slots[at] = {value: typeof initial === 'function' ? initial() : initial};
      const slot = this.slots[at];
      return [slot.value, next => {
        const value = typeof next === 'function' ? next(slot.value) : next;
        if (!Object.is(value, slot.value)) {slot.value = value; this.dirty = true;}
      }];
    },
    ref(initial) {
      const at = this.index++;
      if (!this.slots[at]) this.slots[at] = {current: initial};
      return this.slots[at];
    },
    memo(compute, dependencies) {
      const at = this.index++, previous = this.slots[at];
      if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i])))
        this.slots[at] = {value: compute(), dependencies};
      return this.slots[at].value;
    },
    effect(effect, dependencies) {
      const at = this.index++, previous = this.slots[at];
      if (previous && dependencies.every((value, i) => Object.is(value, previous.dependencies[i]))) return;
      this.slots[at] = {dependencies, cleanup: previous?.cleanup};
      this.queued.push(() => {this.slots[at].cleanup?.(); this.slots[at].cleanup = effect();});
    },
    Render() {
      this.index = 0; this.dirty = false; runtime.current = this;
      this.result = useAgentController(); runtime.current = null;
      for (const effect of this.queued.splice(0)) effect();
    },
    unmount() {
      if (!this.active) return;
      this.active = false;
      for (const slot of this.slots) slot.cleanup?.();
    },
  };
  runtime.mounts.push(host);
  host.Render();
  return host;
}

async function flush(host) {
  for (let i = 0; i < 20; i++) {
    await new Promise(resolve => setImmediate(resolve));
    if (host.active && host.dirty) host.Render();
  }
}
async function send(host, text) {
  assert.equal(host.result.onDraftChange(text), true);
  await flush(host);
  host.result.onSend();
  await flush(host);
}
function open(next) {
  runtime.target = {...next}; runtime.threadId = next.originThreadId;
  for (const listener of runtime.openListeners) listener(next.originThreadId, {...next});
}

test('an accepted continuation consumes the restored launch before retiring its pending receipt', async t => {
  reset(t);
  const first = mount(); await flush(first);
  await send(first, 'Update the panel');
  assert.equal(runtime.calls[0].action, 'continue');
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.ok(runtime.events.findIndex(event => event[0] === 'consume') < runtime.events.findIndex(event => event[0] === 'remove'));
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  first.unmount();
  const restored = mount(); await flush(restored);
  await send(restored, 'An ordinary follow-up');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('a reconciled accepted pending continuation consumes its original launch after restart', async t => {
  reset(t);
  runtime.target.sourceVersion = 4;
  const pending = {threadId: 'thread-a', requestId: 'interrupted-request', text: 'Update before restart', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  runtime.storage.set(draftKey('thread-a'), pending.text);
  const restored = mount(); await flush(restored);
  assert.equal(runtime.calls.length, 0);
  assert.equal(runtime.lookups[0].requestId, pending.requestId);
  assert.equal(runtime.lookups[0].expectedSourceVersion, 3);
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'A later ordinary turn');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('a deliberate same-panel reopen remains a fresh continuation after acceptance', async t => {
  reset(t);
  const host = mount(); await flush(host);
  await send(host, 'First panel edit');
  open({...target, launchId: 'launch-reopened'}); await flush(host);
  await send(host, 'Second panel edit');
  assert.deepEqual(runtime.calls.map(call => call.action), ['continue', 'continue']);
  assert.deepEqual([...runtime.consumed], ['launch-a', 'launch-reopened']);
});

for (const next of [{...target, launchId: 'launch-same-panel'},
  {panelId: 'panel-b', sourceVersion: 0, originThreadId: 'thread-b', launchId: 'launch-other-panel'}]) {
  test(`an old acceptance preserves newer navigation ${next.launchId}`, async t => {
    reset(t);
    const gate = deferred(); runtime.receipt = () => gate.promise;
    const host = mount(); await flush(host);
    await send(host, 'The older panel edit');
    const sent = runtime.calls[0];
    assert.equal(JSON.parse(runtime.storage.get(pendingKey('thread-a'))).panel.launchId, 'launch-a');
    open(next); await flush(host);
    gate.resolve(accepted(sent, 'panel-a')); await flush(host);
    assert.deepEqual([...runtime.consumed], ['launch-a']);
    runtime.receipt = async (args, panelId) => accepted(args, panelId);
    await send(host, 'Edit the newly selected panel');
    assert.equal(runtime.calls.at(-1).action, 'continue');
    assert.equal(runtime.calls.at(-1).panelId, next.panelId);
    assert.equal(runtime.consumed.has(next.launchId), true);
  });
}

for (const state of ['notSent', 'deliveryUnknown']) {
  test(`${state} retains the continuation target through controller restoration`, async t => {
    reset(t);
    runtime.receipt = async (args, panelId) => ({...accepted(args, panelId), state, code: 'SOURCE_CHANGED'});
    const first = mount(); await flush(first);
    await send(first, 'Keep this panel edit');
    assert.equal(runtime.consumed.size, 0);
    assert.equal(first.result.draft, 'Keep this panel edit');
    assert.equal(runtime.storage.has(pendingKey('thread-a')), state !== 'notSent');
    first.unmount();
    if (state === 'deliveryUnknown') {
      const request = runtime.calls[0];
      runtime.receipts.set(request.requestId, accepted(request, request.panelId));
    }
    runtime.receipt = async (args, panelId) => accepted(args, panelId);
    const restored = mount(); await flush(restored);
    if (state === 'notSent') await send(restored, 'Retry the panel edit');
    assert.equal(runtime.calls.at(-1).action, 'continue');
    assert.equal(runtime.consumed.has('launch-a'), true);
  });
}

test('a thrown continuation request preserves the target and original pending identity', async t => {
  reset(t);
  runtime.receipt = async () => {throw new Error('gateway unavailable');};
  const host = mount(); await flush(host);
  await send(host, 'Keep after failure');
  assert.equal(runtime.consumed.size, 0);
  assert.equal(host.result.draft, 'Keep after failure');
  const stored = JSON.parse(runtime.storage.get(pendingKey('thread-a')));
  assert.equal(stored.panel.launchId, 'launch-a');
  assert.equal(runtime.calls.every(call => call.requestId === stored.requestId), true);
});

test('failed durable consumption keeps pending evidence for the accepted receipt', async t => {
  reset(t); runtime.failConsumption = true;
  const host = mount(); await flush(host);
  await send(host, 'Keep until consumption commits');
  assert.equal(runtime.consumed.size, 0);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), true);
  runtime.failConsumption = false;
  host.result.onRetrySubmission(); await flush(host);
  assert.equal(runtime.consumed.has('launch-a'), true);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
});

test('a controller that read its target before an older controller consumed it sends an ordinary turn', async t => {
  reset(t);
  const remounted = mount(); await flush(remounted);
  runtime.consumed.add('launch-a');
  await send(remounted, 'Send after the older controller retired its receipt');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('reconciling an old saved receipt preserves a newer same-panel launch after restart', async t => {
  reset(t);
  runtime.target = {...target, launchId: 'launch-new'};
  const pending = {threadId: 'thread-a', requestId: 'old-request', text: 'An earlier edit', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  const restored = mount(); await flush(restored);
  assert.deepEqual([...runtime.consumed], ['launch-a']);
  await send(restored, 'The explicitly reopened panel edit');
  assert.equal(runtime.calls.at(-1).action, 'continue');
  assert.equal(runtime.consumed.has('launch-new'), true);
});

test('a crash after consumption but before pending cleanup reconciles the same receipt', async t => {
  reset(t);
  runtime.consumed.add('launch-a');
  const pending = {threadId: 'thread-a', requestId: 'accepted-before-crash', text: 'Already accepted', action: 'continue',
    panel: {panelId: 'panel-a', expectedSourceVersion: 3, launchId: 'launch-a'}, baselineBlockIds: [], baselineTurnIds: []};
  runtime.storage.set(pendingKey('thread-a'), JSON.stringify(pending));
  runtime.storage.set(draftKey('thread-a'), pending.text);
  const restored = mount(); await flush(restored);
  assert.equal(runtime.calls.length, 0);
  assert.equal(runtime.lookups[0].requestId, pending.requestId);
  assert.equal(restored.result.draft, '');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  await send(restored, 'An ordinary message after cleanup');
  assert.equal(runtime.calls.at(-1).action, 'sendMessage');
});

test('an unresolved legacy draft stays out of the composer until its receipt confirms failure', async t => {
  reset(t);
  const pending = {threadId: 'thread-a', requestId: 'legacy-send', text: 'Unconfirmed draft', action: 'sendMessage'};
  runtime.storage.set(pendingKey(pending.threadId), JSON.stringify(pending));
  runtime.storage.set(draftKey(pending.threadId), pending.text);
  runtime.receipt = async () => ({...pending, state: 'missing'});
  const host = mount(); await flush(host);
  assert.equal(host.result.draft, '');
  runtime.receipt = async () => ({...pending, state: 'notSent', code: 'MODEL_UNAVAILABLE'});
  host.result.onRetrySubmission(); await flush(host);
  assert.equal(host.result.draft, pending.text);
  assert.equal(runtime.storage.has(pendingKey(pending.threadId)), false);
  assert.deepEqual(runtime.calls, []);
});

test('an accepted legacy receipt preserves a different follow-up draft', async t => {
  reset(t);
  const pending = {threadId: 'thread-a', requestId: 'legacy-send', text: 'Already delivered', action: 'sendMessage'};
  const followup = 'A new unsent edit';
  runtime.storage.set(pendingKey(pending.threadId), JSON.stringify(pending));
  runtime.storage.set(draftKey(pending.threadId), followup);
  const host = mount(); await flush(host);
  assert.equal(host.result.draft, followup);
  assert.deepEqual(runtime.calls, []);
});

test('an archived unconfirmed message survives an interrupted migration without a resend', async t => {
  reset(t);
  const requestId = '85036b24-6662-4b42-9da2-de71f7a30d04';
  const key = `taku.agent.unconfirmed.v1.thread-a.${requestId}`;
  const text = 'Keep the unconfirmed message after upgrading';
  runtime.storage.set(key, JSON.stringify({threadId: 'thread-a', requestId, text}));
  const gateway = runtime.callAgent;
  runtime.callAgent = async (action, params) => {
    const result = await gateway(action, params);
    if (action === 'composer/preserveDraft') throw new Error('Reply was lost after persistence');
    return result;
  };
  const first = mount(); await flush(first);
  assert.equal(runtime.storage.has(key), true);
  first.unmount();
  runtime.callAgent = gateway;
  const restored = mount(); await flush(restored);
  assert.equal(runtime.storage.has(key), false);
  assert.deepEqual(runtime.drafts.get('thread-a')?.recovery.map(item => [item.id, item.text]), [[requestId, text]]);
  assert.deepEqual(runtime.calls, []);
});

test('a recovered attachment event reloads the current saved draft and unsubscribes on unmount', async t => {
  reset(t);
  const host = mount(); await flush(host);
  const attachment = {id: 'photo-a', name: 'Photo.jpg', mime: 'image/jpeg', size: 1234};
  runtime.drafts.set('thread-a', {threadId: 'thread-a', revision: 'after-picker', text: 'Saved caption', attachments: [attachment]});
  assert.equal(runtime.attachmentListeners.size, 1);
  for (const listener of runtime.attachmentListeners) listener('thread-a');
  await flush(host);
  assert.equal(host.result.draft, 'Saved caption');
  assert.equal(host.result.draftRevision, 'after-picker');
  assert.deepEqual(host.result.attachments, [attachment]);
  host.unmount();
  assert.equal(runtime.attachmentListeners.size, 0);
});

test('an attachment event for another conversation does not reload the selected draft', async t => {
  reset(t);
  const host = mount(); await flush(host);
  const reads = [];
  const callAgent = runtime.callAgent;
  runtime.callAgent = async (action, params) => {
    if (action === 'composer/read') reads.push(params.threadId);
    return callAgent(action, params);
  };
  for (const listener of runtime.attachmentListeners) listener('thread-b');
  await flush(host);
  assert.deepEqual(reads, []);
  assert.equal(host.result.draft, '');
  assert.deepEqual(host.result.attachments, []);
});

test('a refusal before reservation reports not sent whatever its code and keeps the draft', async t => {
  reset(t); runtime.target = null;
  const {AgentGatewayError} = await import('../native/agent');
  runtime.receipt = async () => {throw new AgentGatewayError('Busy', 'BUSY');};
  const host = mount(); await flush(host);
  await send(host, 'Send while busy');
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  assert.equal(host.result.draft, 'Send while busy');
  runtime.receipt = async (args, panelId) => accepted(args, panelId);
  await send(host, 'Send while busy');
  assert.equal(runtime.completed.length, 1);
  assert.notEqual(runtime.calls[0].requestId, runtime.calls[1].requestId);
});

test('a transport failure before reservation stays unconfirmed and retry resubmits the same request', async t => {
  reset(t); runtime.target = null;
  const {AgentGatewayError} = await import('../native/agent');
  runtime.receipt = async () => {throw new AgentGatewayError('Timed out', 'GATEWAY_UNAVAILABLE');};
  const host = mount(); await flush(host);
  await send(host, 'Send over a flaky link');
  const stored = JSON.parse(runtime.storage.get(pendingKey('thread-a')));
  runtime.receipt = async (args, panelId) => accepted(args, panelId);
  host.result.onRetrySubmission(); await flush(host);
  assert.deepEqual(runtime.calls.map(call => call.requestId), [stored.requestId, stored.requestId]);
  assert.equal(runtime.completed.length, 1);
  assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
});

for (const [code, english] of [
  ['STEER_WAITING_ANSWER', 'The agent is waiting for an answer to its question or approval. Answer it first, then send this requirement again. Your draft is kept.'],
  ['STEER_CANCELLING', 'This turn is stopping, so the requirement was not added. Your draft is kept; send it again once it has stopped.'],
]) {
  test(`an ACP steer refused with ${code} explains why and keeps the draft`, async t => {
    reset(t); runtime.target = null;
    const base = runtime.callAgent;
    runtime.callAgent = async (action, params = {}) => action === 'readSession'
      ? {native: {ready: true, activeTurnId: 'acp-turn-1'}, historyComplete: true, thread: {id: params.threadId, turns: [{
        id: 'acp-turn-1', status: 'inProgress', items: [{id: 'acp-turn-1-user', type: 'userMessage', content: [{type: 'text', text: 'List fruits'}]}],
      }]}}
      : base(action, params);
    runtime.receipt = async args => ({threadId: args.threadId, requestId: args.requestId, state: 'notSent', code});
    const host = mount(); await flush(host);
    await send(host, 'Only red ones');
    assert.equal(runtime.calls.length, 1);
    assert.equal(runtime.calls[0].action, 'steer');
    assert.equal(runtime.calls[0].expectedTurnId, 'acp-turn-1');
    assert.equal(host.result.error, english);
    assert.equal(host.result.draft, 'Only red ones');
    assert.equal(runtime.storage.has(pendingKey('thread-a')), false);
  });
}
