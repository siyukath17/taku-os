/**
 * Chat scroll state machine and pin-to-top geometry.
 *
 * Modeled after Renma's ChatFollow (explicit user-owned mode with hysteresis so
 * streaming/layout never hijack reading) extended with a ChatGPT/Claude-style
 * `pinned` mode on send: sending a message smoothly pins the user bubble to the
 * top of the reading viewport with a dynamic bottom spacer that shrinks as the
 * reply grows downward. Once the reply overflows past one screen, the page stays
 * put so the user can read from the top while a scroll-to-bottom button appears.
 */

export type ChatScrollMode = 'following' | 'pinned' | 'browsing';

/** User must drag this far above the bottom to leave `following` (hysteresis). */
export const SCROLL_LEAVE_DISTANCE = 60;

/** Returning within this distance of the bottom rejoins `following`. */
export const SCROLL_REJOIN_DISTANCE = 24;

/** Window after send to smoothly settle the pinned bubble while the IME dismisses. */
export const PIN_SETTLE_MS = 450;

export interface PinnedAnchor {
  userId: string;
  turnId: string;
}

export interface ChatScrollState {
  mode: ChatScrollMode;
  pendingSend: boolean;
  anchor: PinnedAnchor | null;
  showToBottom: boolean;
}

/**
 * `toPinAnimated` puts the pinned user row at the top of the reading viewport by the row itself (its
 * measured frame), not by "content end": the end moves a frame before the spacer catches up while the
 * reply streams, so aiming at it overshoots and the ScrollView clamps back. The row's top does not move.
 */
export type ChatScrollEffect = 'none' | 'toBottomInstant' | 'toBottomAnimated' | 'toPinAnimated';

export type ChatScrollEvent =
  | {type: 'reset'; browsing: boolean}
  | {type: 'sent'}
  | {type: 'anchorBound'; userId: string; turnId: string}
  | {type: 'scrolled'; distance: number; byUser: boolean; spacerActive: boolean}
  | {type: 'contentResized'; withinPinSettle: boolean; distance?: number; spacerActive?: boolean}
  | {type: 'tappedToBottom'};

export function createChatScrollState(browsing = false): ChatScrollState {
  return {
    mode: browsing ? 'browsing' : 'following',
    pendingSend: false,
    anchor: null,
    showToBottom: false,
  };
}

export function sameChatScrollState(a: ChatScrollState, b: ChatScrollState): boolean {
  return (
    a.mode === b.mode &&
    a.pendingSend === b.pendingSend &&
    a.showToBottom === b.showToBottom &&
    a.anchor?.userId === b.anchor?.userId &&
    a.anchor?.turnId === b.anchor?.turnId
  );
}

export function distanceFromBottom(contentHeight: number, viewportHeight: number, offsetY: number): number {
  if (!Number.isFinite(contentHeight) || !Number.isFinite(viewportHeight) || !Number.isFinite(offsetY)) return 0;
  return Math.max(0, contentHeight - viewportHeight - offsetY);
}

export interface PinnedSpacerInput {
  viewportHeight: number;
  userHeight: number;
  assistantHeight: number;
  footerHeight: number;
  itemGap: number;
}

/**
 * Dynamic bottom spacer that lets the newly sent user bubble sit at the top of
 * the reading viewport and shrinks naturally to 0 as the reply fills the screen.
 */
export function pinnedSpacerHeight(input: PinnedSpacerInput): number {
  // A layout value that is not a finite size yet (NaN before a measure) counts as 0, never a NaN spacer height.
  const size = (value: number) => Number.isFinite(value) ? Math.max(0, value) : 0;
  const viewportHeight = size(input.viewportHeight), userHeight = size(input.userHeight);
  const assistantHeight = size(input.assistantHeight), footerHeight = size(input.footerHeight), itemGap = size(input.itemGap);
  if (viewportHeight <= 0 || userHeight <= 0) return 0;
  const turnHeight = userHeight + (assistantHeight > 0 ? itemGap + assistantHeight : 0) + footerHeight;
  return Math.max(0, Math.round(viewportHeight - turnHeight - itemGap));
}

export interface PinnedTurnMatch {
  userIndex: number;
  userId: string;
  assistantId: string | null;
}

/**
 * Locate the pinned user block (and its paired assistant block) across optimistic
 * -> native block ID handoffs using either `userId` or `turnId`.
 */
export function findPinnedTurn(
  blocks: readonly {id: string; role: 'user' | 'assistant'; turnId: string}[],
  anchor: PinnedAnchor | null,
): PinnedTurnMatch | null {
  if (!anchor || blocks.length === 0) return null;
  let userIndex = blocks.findIndex(block => block.role === 'user' && block.id === anchor.userId);
  if (userIndex < 0 && anchor.turnId) {
    for (let i = blocks.length - 1; i >= 0; i -= 1) {
      if (blocks[i].role === 'user' && blocks[i].turnId === anchor.turnId) {
        userIndex = i;
        break;
      }
    }
  }
  if (userIndex < 0) return null;
  const next = blocks[userIndex + 1];
  const assistantId = next && next.role === 'assistant' ? next.id : null;
  return {userIndex, userId: blocks[userIndex].id, assistantId};
}

export function reduceChatScroll(
  state: ChatScrollState,
  event: ChatScrollEvent,
): {state: ChatScrollState; effect: ChatScrollEffect} {
  switch (event.type) {
    case 'reset':
      return {state: createChatScrollState(event.browsing), effect: 'none'};

    case 'sent':
      return {
        state: {mode: 'pinned', pendingSend: true, anchor: null, showToBottom: false},
        effect: 'none',
      };

    case 'anchorBound':
      return {
        state: {
          mode: 'pinned',
          pendingSend: false,
          anchor: {userId: event.userId, turnId: event.turnId},
          showToBottom: false,
        },
        effect: 'toPinAnimated',
      };

    case 'scrolled': {
      const dist = Math.max(0, event.distance);
      const showToBottom = dist > SCROLL_LEAVE_DISTANCE;
      if (!event.byUser) {
        // While the pin spacer is still open, the reply fits on screen: the content end is the pin
        // position, so a programmatic settle passing through larger distances is not "content below".
        if (state.mode === 'pinned' && event.spacerActive) {
          return {state: state.showToBottom ? {...state, showToBottom: false} : state, effect: 'none'};
        }
        return {
          state: state.showToBottom === showToBottom ? state : {...state, showToBottom},
          effect: 'none',
        };
      }
      if (state.mode === 'following') {
        if (dist > SCROLL_LEAVE_DISTANCE) {
          return {state: {...state, mode: 'browsing', pendingSend: false, showToBottom: true}, effect: 'none'};
        }
        return {state: state.showToBottom ? {...state, showToBottom: false} : state, effect: 'none'};
      }
      if (state.mode === 'pinned') {
        if (dist <= SCROLL_REJOIN_DISTANCE && !event.spacerActive) {
          return {state: {...state, mode: 'following', pendingSend: false, showToBottom: false}, effect: 'none'};
        }
        return {state: {...state, mode: 'browsing', pendingSend: false, showToBottom}, effect: 'none'};
      }
      // browsing
      if (dist <= SCROLL_REJOIN_DISTANCE) {
        return {state: {...state, mode: 'following', pendingSend: false, showToBottom: false}, effect: 'none'};
      }
      return {
        state: state.showToBottom === showToBottom ? state : {...state, showToBottom},
        effect: 'none',
      };
    }

    case 'contentResized': {
      if (state.mode === 'following') {
        return {state: state.showToBottom ? {...state, showToBottom: false} : state, effect: 'toBottomInstant'};
      }
      if (state.mode === 'pinned') {
        // While the spacer is open the content end IS the pin position (spacer = viewport - turn), so
        // re-aligning is idempotent and absorbs late layout (IME closing, optimistic->native handoff).
        if (event.withinPinSettle || event.spacerActive) {
          return {state: state.showToBottom ? {...state, showToBottom: false} : state, effect: 'toPinAnimated'};
        }
        if (event.distance !== undefined) {
          const showToBottom = Math.max(0, event.distance) > SCROLL_LEAVE_DISTANCE;
          return {
            state: state.showToBottom === showToBottom ? state : {...state, showToBottom},
            effect: 'none',
          };
        }
        return {state, effect: 'none'};
      }
      // browsing
      if (event.distance !== undefined) {
        const showToBottom = Math.max(0, event.distance) > SCROLL_LEAVE_DISTANCE;
        return {
          state: state.showToBottom === showToBottom ? state : {...state, showToBottom},
          effect: 'none',
        };
      }
      return {state, effect: 'none'};
    }

    case 'tappedToBottom':
      return {
        state: {...state, mode: 'following', pendingSend: false, showToBottom: false},
        effect: 'toBottomAnimated',
      };
  }
}

/**
 * Who is moving the list. Android's ScrollView reports a programmatic smooth scroll
 * (`scrollToEnd({animated: true})`) as momentum too (ReactScrollViewHelper.smoothScrollTo ->
 * startFlingAnimator -> onMomentumScrollBegin), so momentum is the user's only when it is the fling
 * of a drag that just ended; any programmatic scroll disarms that.
 */
export interface ScrollIntent {
  byUser: boolean;
  flingArmed: boolean;
}

export type ScrollIntentEvent = 'dragBegin' | 'dragEnd' | 'momentumBegin' | 'momentumEnd' | 'programmatic';

export function createScrollIntent(): ScrollIntent {
  return {byUser: false, flingArmed: false};
}

export function nextScrollIntent(intent: ScrollIntent, event: ScrollIntentEvent): ScrollIntent {
  switch (event) {
    case 'dragBegin':
      return {byUser: true, flingArmed: false};
    case 'dragEnd':
      return {byUser: false, flingArmed: true};
    case 'momentumBegin':
      return {byUser: intent.flingArmed, flingArmed: false};
    case 'momentumEnd':
    case 'programmatic':
      return {byUser: false, flingArmed: false};
  }
}

/** The user rows (ids and turns) already on screen when a message is sent. */
export interface KnownUsers {
  ids: ReadonlySet<string>;
  turnIds: ReadonlySet<string>;
}

export function knownUsers(blocks: readonly {id: string; role: 'user' | 'assistant'; turnId: string}[]): KnownUsers {
  const ids = new Set<string>(), turnIds = new Set<string>();
  for (const block of blocks) {
    if (block.role !== 'user') continue;
    ids.add(block.id);
    if (block.turnId) turnIds.add(block.turnId);
  }
  return {ids, turnIds};
}

/**
 * The sent message's row: the last user row whose id AND turn were both unknown at send. An earlier
 * row can change id meanwhile (its optimistic request settles or is pruned: `optimistic/<r>/user` <->
 * native id), or rows can be appended around it; neither makes it the message just sent.
 */
export function findSentUser<T extends {id: string; role: 'user' | 'assistant'; turnId: string}>(
  blocks: readonly T[],
  before: KnownUsers,
): T | null {
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    const block = blocks[i];
    if (block.role !== 'user') continue;
    if (!before.ids.has(block.id) && !(block.turnId && before.turnIds.has(block.turnId))) return block;
  }
  return null;
}
