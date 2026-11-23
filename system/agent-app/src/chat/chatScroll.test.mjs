import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createChatScrollState,
  createScrollIntent,
  nextScrollIntent,
  distanceFromBottom,
  findPinnedTurn,
  findSentUser,
  knownUsers,
  pinnedSpacerHeight,
  reduceChatScroll,
  SCROLL_LEAVE_DISTANCE,
  SCROLL_REJOIN_DISTANCE,
} from './chatScroll.ts';

test('non-user scroll never breaks following mode, while user drag past leave threshold enters browsing', () => {
  let state = createChatScrollState();
  // Streaming / layout spike without a finger on screen keeps following.
  let step = reduceChatScroll(state, {type: 'scrolled', distance: 180, byUser: false, spacerActive: false});
  state = step.state;
  assert.equal(state.mode, 'following');
  assert.equal(step.effect, 'none');

  // Small user nudge within leave threshold keeps following.
  step = reduceChatScroll(state, {type: 'scrolled', distance: SCROLL_LEAVE_DISTANCE - 1, byUser: true, spacerActive: false});
  state = step.state;
  assert.equal(state.mode, 'following');

  // Deliberate user drag above leave threshold switches to browsing and shows scroll-to-bottom.
  step = reduceChatScroll(state, {type: 'scrolled', distance: SCROLL_LEAVE_DISTANCE + 1, byUser: true, spacerActive: false});
  state = step.state;
  assert.equal(state.mode, 'browsing');
  assert.equal(state.showToBottom, true);

  // Returning within rejoin threshold by hand rejoins following.
  step = reduceChatScroll(state, {type: 'scrolled', distance: SCROLL_REJOIN_DISTANCE, byUser: true, spacerActive: false});
  state = step.state;
  assert.equal(state.mode, 'following');
  assert.equal(state.showToBottom, false);
});

test('send pins user message to top, settles smoothly, then stays put when reply overflows one screen', () => {
  let state = createChatScrollState();
  let step = reduceChatScroll(state, {type: 'sent'});
  state = step.state;
  assert.equal(state.mode, 'pinned');
  assert.equal(state.pendingSend, true);

  step = reduceChatScroll(state, {type: 'anchorBound', userId: 'optimistic/r1/user', turnId: 't1'});
  state = step.state;
  assert.equal(state.mode, 'pinned');
  assert.equal(state.pendingSend, false);
  assert.deepEqual(state.anchor, {userId: 'optimistic/r1/user', turnId: 't1'});
  assert.equal(step.effect, 'toPinAnimated');

  // During initial settle window (IME dismissing), content resize keeps aligning smoothly.
  step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: true, distance: 120});
  assert.equal(step.effect, 'toPinAnimated');

  // After settle window, reply overflowing past one screen does NOT pull the viewport down,
  // and turns on the scroll-to-bottom button so the user can jump when ready.
  step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: false, distance: 240});
  state = step.state;
  assert.equal(state.mode, 'pinned');
  assert.equal(step.effect, 'none');
  assert.equal(state.showToBottom, true);
});

test('user drag during pinned mode yields immediately to browsing, and tapping to-bottom resumes following', () => {
  let state = reduceChatScroll(createChatScrollState(), {type: 'anchorBound', userId: 'u1', turnId: 't1'}).state;
  let step = reduceChatScroll(state, {type: 'scrolled', distance: 90, byUser: true, spacerActive: true});
  state = step.state;
  assert.equal(state.mode, 'browsing');
  assert.equal(state.showToBottom, true);

  // Content resize while browsing never moves the page.
  step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: false, distance: 200});
  assert.equal(step.effect, 'none');

  // Tapping scroll-to-bottom smoothly returns to following.
  step = reduceChatScroll(state, {type: 'tappedToBottom'});
  state = step.state;
  assert.equal(state.mode, 'following');
  assert.equal(state.showToBottom, false);
  assert.equal(step.effect, 'toBottomAnimated');
});

test('pinnedSpacerHeight reserves remaining viewport below pinned turn and shrinks to 0 as reply grows', () => {
  const initial = pinnedSpacerHeight({viewportHeight: 600, userHeight: 48, assistantHeight: 26, footerHeight: 0, itemGap: 20});
  assert.equal(initial, 486);

  const mid = pinnedSpacerHeight({viewportHeight: 600, userHeight: 48, assistantHeight: 300, footerHeight: 0, itemGap: 20});
  assert.equal(mid, 212);

  const overflow = pinnedSpacerHeight({viewportHeight: 600, userHeight: 48, assistantHeight: 700, footerHeight: 0, itemGap: 20});
  assert.equal(overflow, 0);
});

test('findPinnedTurn survives optimistic-to-native user block ID handoff via turnId', () => {
  const blocks = [
    {id: 'native-u1', role: 'user', turnId: 'turn-1'},
    {id: 'native-a1', role: 'assistant', turnId: 'turn-1'},
  ];
  const match = findPinnedTurn(blocks, {userId: 'optimistic/req-1/user', turnId: 'turn-1'});
  assert.deepEqual(match, {userIndex: 0, userId: 'native-u1', assistantId: 'native-a1'});
});

test('distanceFromBottom clamps at 0', () => {
  assert.equal(distanceFromBottom(500, 600, 0), 0);
  assert.equal(distanceFromBottom(1200, 600, 500), 100);
});

test('pinned: while the spacer is open late layout re-pins and never shows the to-bottom button', () => {
  let state = reduceChatScroll(createChatScrollState(), {type: 'anchorBound', userId: 'u1', turnId: 't1'}).state;
  // IME closing after the settle window grows the spacer: still re-aligns to the pin.
  let step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: false, distance: 400, spacerActive: true});
  assert.equal(step.effect, 'toPinAnimated');
  assert.equal(step.state.showToBottom, false);
  // The programmatic settle animation passing through large distances is not content below.
  step = reduceChatScroll(state, {type: 'scrolled', distance: 400, byUser: false, spacerActive: true});
  assert.equal(step.state.mode, 'pinned');
  assert.equal(step.state.showToBottom, false);
  // Once the reply fills the screen (spacer closed) the page stays put and offers the button.
  step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: false, distance: 240, spacerActive: false});
  state = step.state;
  assert.equal(step.effect, 'none');
  assert.equal(state.showToBottom, true);
});

test('scroll intent: Android momentum from a programmatic smooth scroll is not the user', () => {
  let intent = createScrollIntent();
  // scrollToEnd({animated: true}) on Android emits onMomentumScrollBegin with no drag.
  intent = nextScrollIntent(intent, 'programmatic');
  intent = nextScrollIntent(intent, 'momentumBegin');
  assert.equal(intent.byUser, false);
  intent = nextScrollIntent(intent, 'momentumEnd');

  // A drag, then its fling, is the user throughout.
  intent = nextScrollIntent(intent, 'dragBegin');
  assert.equal(intent.byUser, true);
  intent = nextScrollIntent(intent, 'dragEnd');
  assert.equal(intent.byUser, false);
  intent = nextScrollIntent(intent, 'momentumBegin');
  assert.equal(intent.byUser, true);
  intent = nextScrollIntent(intent, 'momentumEnd');
  assert.equal(intent.byUser, false);

  // A drag that ends without a fling leaves the fling armed only until the next programmatic scroll.
  intent = nextScrollIntent(nextScrollIntent(intent, 'dragBegin'), 'dragEnd');
  intent = nextScrollIntent(intent, 'programmatic');
  intent = nextScrollIntent(intent, 'momentumBegin');
  assert.equal(intent.byUser, false);
});

test('sent message binds to the new row, not an earlier row that changed id or gained neighbours', () => {
  const before = [
    {id: 'n1', role: 'user', turnId: 't1'}, {id: 'a1', role: 'assistant', turnId: 't1'},
    {id: 'optimistic/r2/user', role: 'user', turnId: 't2'}, {id: 'optimistic/r2/assistant', role: 'assistant', turnId: 't2'},
  ];
  const known = knownUsers(before);
  // Nothing new yet.
  assert.equal(findSentUser(before, known), null);
  // The previous request settles and is pruned: its row goes back to the native id, same turn.
  const pruned = [before[0], before[1], {id: 'n2', role: 'user', turnId: 't2'}, {id: 'a2', role: 'assistant', turnId: 't2'}];
  assert.equal(findSentUser(pruned, known), null);
  // A row appended after the last user row (status, reply) is not a sent message either.
  assert.equal(findSentUser([...before, {id: 'a2b', role: 'assistant', turnId: 't2'}], known), null);
  // The new optimistic row appears (turn is its request prefix until the native turn arrives).
  const sent = {id: 'optimistic/r3/user', role: 'user', turnId: 'optimistic/r3'};
  assert.equal(findSentUser([...pruned, sent, {id: 'optimistic/r3/assistant', role: 'assistant', turnId: 'optimistic/r3'}], known), sent);
});

// ---- boundary hardening: rapid sends, switching conversations, short content, keyboard ----

const bound = (userId, turnId) => reduceChatScroll(reduceChatScroll(createChatScrollState(), {type: 'sent'}).state, {type: 'anchorBound', userId, turnId}).state;

test('rapid second send drops the first anchor until the new row binds, then pins the new row', () => {
  let state = bound('optimistic/r1/user', 'optimistic/r1');
  state = reduceChatScroll(state, {type: 'sent'}).state;
  assert.deepEqual(state, {mode: 'pinned', pendingSend: true, anchor: null, showToBottom: false});
  const step = reduceChatScroll(state, {type: 'anchorBound', userId: 'optimistic/r2/user', turnId: 'optimistic/r2'});
  assert.deepEqual(step.state.anchor, {userId: 'optimistic/r2/user', turnId: 'optimistic/r2'});
  assert.equal(step.effect, 'toPinAnimated');

  // Both rows land in one render: the newest unknown row is the one just sent.
  const rows = [{id: 'n1', role: 'user', turnId: 't1'}, {id: 'a1', role: 'assistant', turnId: 't1'}];
  const known = knownUsers(rows);
  const first = {id: 'optimistic/r1/user', role: 'user', turnId: 'optimistic/r1'};
  const second = {id: 'optimistic/r2/user', role: 'user', turnId: 'optimistic/r2'};
  assert.equal(findSentUser([...rows, first, {id: 'optimistic/r1/assistant', role: 'assistant', turnId: 'optimistic/r1'}, second], known), second);
  // Known rows captured after the first row rendered: only the second counts as sent.
  assert.equal(findSentUser([...rows, first, second], knownUsers([...rows, first])), second);
});

test('switching conversations resets pin, pending send and the to-bottom button', () => {
  let state = reduceChatScroll(bound('u1', 't1'), {type: 'contentResized', withinPinSettle: false, distance: 400, spacerActive: false}).state;
  assert.equal(state.showToBottom, true);
  assert.deepEqual(reduceChatScroll(state, {type: 'reset', browsing: false}), {state: createChatScrollState(false), effect: 'none'});
  // Returning to a conversation the user was reading restores browsing, never a stale anchor.
  state = reduceChatScroll(reduceChatScroll(createChatScrollState(), {type: 'sent'}).state, {type: 'reset', browsing: true}).state;
  assert.deepEqual(state, {mode: 'browsing', pendingSend: false, anchor: null, showToBottom: false});
});

test('content shorter than one screen: distance stays 0, no button, finger at the end rejoins following', () => {
  assert.equal(distanceFromBottom(300, 600, 0), 0);
  let state = createChatScrollState();
  let step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: false, distance: 0});
  assert.equal(step.effect, 'toBottomInstant');
  assert.equal(step.state, state, 'unchanged state keeps its identity (no re-render)');
  state = bound('u1', 't1');
  // The spacer keeps the end at the pin: a late resize re-pins idempotently.
  step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: false, distance: 0, spacerActive: true});
  assert.equal(step.effect, 'toPinAnimated');
  assert.equal(step.state, state);
  // A drag while the spacer is open hands control to the user (no auto-follow into the spacer)…
  state = reduceChatScroll(state, {type: 'scrolled', distance: 0, byUser: true, spacerActive: true}).state;
  assert.equal(state.mode, 'browsing');
  assert.equal(state.showToBottom, false);
  // …and the next drag that ends at the bottom follows again.
  state = reduceChatScroll(state, {type: 'scrolled', distance: 0, byUser: true, spacerActive: false}).state;
  assert.equal(state.mode, 'following');
});

test('keyboard show/hide (list re-layout) never moves a pinned or browsing page outside the settle window', () => {
  const pinned = reduceChatScroll(bound('u1', 't1'), {type: 'contentResized', withinPinSettle: false, distance: 300, spacerActive: false}).state;
  // onLayout before any content size: distance unknown.
  let step = reduceChatScroll(pinned, {type: 'contentResized', withinPinSettle: false, spacerActive: false});
  assert.deepEqual(step, {state: pinned, effect: 'none'});
  const browsing = reduceChatScroll(createChatScrollState(), {type: 'scrolled', distance: 500, byUser: true, spacerActive: false}).state;
  step = reduceChatScroll(browsing, {type: 'contentResized', withinPinSettle: false});
  assert.deepEqual(step, {state: browsing, effect: 'none'});
  // The IME closing right after send (inside the settle window) re-aligns the pin.
  assert.equal(reduceChatScroll(pinned, {type: 'contentResized', withinPinSettle: true, distance: 300}).effect, 'toPinAnimated');
  // Following: a resize keeps the latest message in view.
  assert.equal(reduceChatScroll(createChatScrollState(), {type: 'contentResized', withinPinSettle: false}).effect, 'toBottomInstant');
});

test('negative and non-finite distances are treated as the bottom', () => {
  for (const args of [[Number.NaN, 600, 0], [1000, Number.POSITIVE_INFINITY, 0], [1000, 600, Number.NaN]]) assert.equal(distanceFromBottom(...args), 0);
  const browsing = reduceChatScroll(createChatScrollState(), {type: 'scrolled', distance: 500, byUser: true, spacerActive: false}).state;
  assert.equal(reduceChatScroll(browsing, {type: 'scrolled', distance: -40, byUser: true, spacerActive: false}).state.mode, 'following');
  const pinned = bound('u1', 't1');
  assert.equal(reduceChatScroll(pinned, {type: 'contentResized', withinPinSettle: false, distance: -10, spacerActive: false}).state.showToBottom, false);
});

test('programmatic scrolls only update the button, never the mode', () => {
  for (const state of [createChatScrollState(), bound('u1', 't1'), createChatScrollState(true)]) {
    const step = reduceChatScroll(state, {type: 'scrolled', distance: 900, byUser: false, spacerActive: false});
    assert.equal(step.state.mode, state.mode);
    assert.equal(step.effect, 'none');
  }
});

test('pinnedSpacerHeight regression: unmeasured (NaN) or negative sizes never yield a NaN or negative spacer', () => {
  const base = {viewportHeight: 600, userHeight: 48, assistantHeight: 26, footerHeight: 0, itemGap: 20};
  // Number.NaN <= 0 is false, so a NaN viewport used to slip through to a NaN height.
  assert.equal(pinnedSpacerHeight({...base, viewportHeight: Number.NaN}), 0);
  assert.equal(pinnedSpacerHeight({...base, userHeight: Number.NaN}), 0);
  assert.equal(pinnedSpacerHeight({...base, assistantHeight: Number.NaN}), 532, 'no reply measured yet: user row only');
  assert.equal(pinnedSpacerHeight({...base, footerHeight: Number.NaN}), 486);
  assert.equal(pinnedSpacerHeight({...base, itemGap: Number.NaN}), 526);
  assert.equal(pinnedSpacerHeight({...base, footerHeight: -50, itemGap: -20}), 526, 'negative sizes count as 0');
  assert.equal(pinnedSpacerHeight({...base, viewportHeight: Number.POSITIVE_INFINITY}), 0);
  assert.equal(pinnedSpacerHeight({...base, viewportHeight: 0}), 0);
  assert.equal(pinnedSpacerHeight({...base, footerHeight: 40}), 446, 'the turn footer shrinks the spacer');
});

test('findPinnedTurn edge cases: no anchor, missing row, last row, user after user, repeated turn ids', () => {
  const blocks = [
    {id: 'u1', role: 'user', turnId: 't1'}, {id: 'a1', role: 'assistant', turnId: 't1'},
    {id: 'u2', role: 'user', turnId: 't2'}, {id: 'u2b', role: 'user', turnId: 't2'},
  ];
  assert.equal(findPinnedTurn(blocks, null), null);
  assert.equal(findPinnedTurn([], {userId: 'u1', turnId: 't1'}), null);
  assert.equal(findPinnedTurn(blocks, {userId: 'gone', turnId: ''}), null, 'no turn to fall back on');
  assert.equal(findPinnedTurn(blocks, {userId: 'gone', turnId: 'tX'}), null);
  assert.deepEqual(findPinnedTurn(blocks, {userId: 'u2', turnId: 't2'}), {userIndex: 2, userId: 'u2', assistantId: null}, 'next row is a user row');
  assert.deepEqual(findPinnedTurn(blocks, {userId: 'u2b', turnId: 't2'}), {userIndex: 3, userId: 'u2b', assistantId: null}, 'last row');
  assert.deepEqual(findPinnedTurn(blocks, {userId: 'gone', turnId: 't2'}), {userIndex: 3, userId: 'u2b', assistantId: null}, 'the newest row of the turn');
  assert.equal(findPinnedTurn([{id: 'a1', role: 'assistant', turnId: 't1'}], {userId: 'a1', turnId: 't1'}), null, 'an assistant row is never the pin');
});

test('findSentUser: empty lists and rows without a turn id', () => {
  assert.equal(findSentUser([], knownUsers([])), null);
  const row = {id: 'u9', role: 'user', turnId: ''};
  assert.equal(findSentUser([row], knownUsers([])), row);
  assert.equal(findSentUser([row], knownUsers([row])), null);
  assert.deepEqual([...knownUsers([row]).turnIds], [], 'an empty turn id never marks other rows as known');
});

test('scroll intent: a new drag during a fling is the user; programmatic scroll mid-drag disarms it', () => {
  let intent = nextScrollIntent(nextScrollIntent(createScrollIntent(), 'dragBegin'), 'dragEnd');
  intent = nextScrollIntent(intent, 'momentumBegin');
  intent = nextScrollIntent(intent, 'dragBegin');
  assert.deepEqual(intent, {byUser: true, flingArmed: false});
  assert.deepEqual(nextScrollIntent(intent, 'programmatic'), {byUser: false, flingArmed: false});
  assert.deepEqual(nextScrollIntent(createScrollIntent(), 'momentumEnd'), {byUser: false, flingArmed: false});
});
