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

export type ChatScrollEffect = 'none' | 'toBottomInstant' | 'toBottomAnimated';

export type ChatScrollEvent =
  | {type: 'reset'; browsing: boolean}
  | {type: 'sent'}
  | {type: 'anchorBound'; userId: string; turnId: string}
  | {type: 'scrolled'; distance: number; byUser: boolean; spacerActive: boolean}
  | {type: 'contentResized'; withinPinSettle: boolean; distance?: number}
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
  const {viewportHeight, userHeight, assistantHeight, footerHeight, itemGap} = input;
  if (viewportHeight <= 0 || userHeight <= 0) return 0;
  const turnHeight = userHeight + (assistantHeight > 0 ? itemGap + assistantHeight : 0) + Math.max(0, footerHeight);
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
        effect: 'toBottomAnimated',
      };

    case 'scrolled': {
      const dist = Math.max(0, event.distance);
      const showToBottom = dist > SCROLL_LEAVE_DISTANCE;
      if (!event.byUser) {
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
        if (event.withinPinSettle) {
          return {state, effect: 'toBottomAnimated'};
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
