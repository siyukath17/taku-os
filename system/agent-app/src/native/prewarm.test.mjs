import test from 'node:test';
import assert from 'node:assert/strict';
import {PREWARM_INTERVAL_MS, requestPrewarm, resetPrewarmThrottle} from './prewarm.ts';

test('foreground warm-up requests are throttled, fire-and-forget and never throw', async () => {
  resetPrewarmThrottle();
  const sent = [];
  const send = async surface => { sent.push(surface); };
  assert.equal(requestPrewarm('onResume', send, 1_000), true);
  assert.equal(requestPrewarm('focus', send, 1_000 + PREWARM_INTERVAL_MS - 1), false, 'within the interval');
  assert.equal(requestPrewarm('overlay', send, 1_000 + PREWARM_INTERVAL_MS), true, 'after the interval');
  assert.deepEqual(sent, ['onResume', 'overlay']);
  // A rejected or throwing send is swallowed: the UI never sees a warm-up failure.
  assert.equal(requestPrewarm('focus', async () => { throw new Error('GATEWAY_UNAVAILABLE'); }, 100_000), true);
  assert.equal(requestPrewarm('focus', () => { throw new Error('NATIVE_UNAVAILABLE'); }, 200_000), true);
  await new Promise(resolve => setTimeout(resolve, 0));
});
