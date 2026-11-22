import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createChatScrollState,
  distanceFromBottom,
  findPinnedTurn,
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
  assert.equal(step.effect, 'toBottomAnimated');

  // During initial settle window (IME dismissing), content resize keeps aligning smoothly.
  step = reduceChatScroll(state, {type: 'contentResized', withinPinSettle: true, distance: 120});
  assert.equal(step.effect, 'toBottomAnimated');

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
