import type {MessageBlock, SessionView} from './session';

export const FIRST_RESPONSE_TIMEOUT_MS = 60_000;
export type ResponseErrorKey = 'replyTimeout' | 'replyFailed';
export interface OptimisticSubmission {
  threadId: string;
  requestId: string;
  text: string;
  action: 'sendMessage' | 'steer';
  expectedTurnId?: string;
  startedAt: number;
  baselineBlockIds: string[];
  baselineTurnIds: string[];
  state: 'sending' | 'accepted' | 'failed' | 'timedOut' | 'settled' | 'stopped';
  correlationPending?: boolean;
  nonDeliverable?: boolean;
  taskId?: string;
  turnId?: string;
  userBlockId?: string;
  assistantBlockId?: string;
  error?: string;
  errorKey?: ResponseErrorKey;
}

export function beginOptimisticSubmission(
  request: Pick<OptimisticSubmission, 'threadId' | 'requestId' | 'text' | 'action' | 'expectedTurnId'>,
  session: SessionView | null, now = Date.now(),
): OptimisticSubmission {
  return {...request, startedAt: now, baselineBlockIds: session?.blocks.map(block => block.id) || [],
    baselineTurnIds: [...new Set(session?.blocks.map(block => block.turnId) || [])], state: 'sending'};
}

export const hasResponse = (block: MessageBlock | undefined) => Boolean(block && (block.text.trim() || block.parts?.some(part => part.card || part.replyState) || block.activity.length || block.subagents?.length || block.attachments.length));

/** A rejected steer may have been published as a new, independently identified task. */
export function acceptOptimisticReceipt(request: OptimisticSubmission, receipt: Record<string, unknown>): OptimisticSubmission {
  const result = receipt.result;
  const fallback = request.action === 'steer' && result !== null && typeof result === 'object'
    && !Array.isArray(result) && (result as Record<string, unknown>).queuedAfterTurn === true
    && typeof receipt.taskId === 'string' && /^[0-9]+$/.test(receipt.taskId);
  return {...request, state: ['settled', 'stopped'].includes(request.state) ? request.state : 'accepted',
    taskId: typeof receipt.taskId === 'string' && receipt.taskId ? receipt.taskId : request.taskId,
    ...(fallback ? {action: 'sendMessage' as const, expectedTurnId: undefined} : {}), error: undefined, errorKey: undefined};
}

/** Correlate only this request's new native records; receipts remain the delivery authority. */
export function reconcileOptimisticSubmission(request: OptimisticSubmission, session: SessionView | null, now = Date.now()): OptimisticSubmission {
  if (request.nonDeliverable) return (request.state === 'sending' || request.state === 'accepted')
    && now - request.startedAt >= FIRST_RESPONSE_TIMEOUT_MS
    ? {...request, state: 'timedOut', errorKey: 'replyTimeout'} : request;
  if (session && session.id !== request.threadId) return request;
  const blocks = session?.blocks || [];
  const taskMatches = Boolean(request.taskId && request.taskId === session?.taskId);
  const runtimeTurn = taskMatches ? session?.taskTurnId : '';
  const user = blocks.find(block => block.id === request.userBlockId) || blocks.find(block => block.role === 'user'
    && (!request.baselineBlockIds.includes(block.id) || Boolean(runtimeTurn)) && block.text.trim() === request.text
    && (!request.correlationPending || Boolean(runtimeTurn))
    && (request.action !== 'steer' || block.turnId === request.expectedTurnId)
    && (!runtimeTurn || block.turnId === runtimeTurn));
  const turnId = user?.turnId || runtimeTurn || request.turnId || (request.action === 'sendMessage' && !request.correlationPending
    ? blocks.find(block => block.role === 'assistant' && !request.baselineTurnIds.includes(block.turnId))?.turnId : '');
  const userIndex = user ? blocks.indexOf(user) : -1;
  const nextUserIndex = userIndex >= 0
    ? blocks.findIndex((block, index) => index > userIndex && block.role === 'user' && block.turnId === turnId)
    : -1;
  const segmentAssistant = userIndex >= 0 && blocks[userIndex + 1]?.role === 'assistant' && blocks[userIndex + 1].turnId === turnId
    ? blocks[userIndex + 1]
    : undefined;
  const assistant = blocks.find(block => block.id === request.assistantBlockId)
    || segmentAssistant
    || blocks.find((block, index) => block.role === 'assistant' && block.turnId === turnId
      && !request.baselineBlockIds.includes(block.id)
      && (request.action === 'steer' ? userIndex >= 0 && index > userIndex : (userIndex < 0 || index > userIndex))
      && (nextUserIndex < 0 || index < nextUserIndex));
  let next = {...request, ...(turnId ? {turnId} : {}), ...(user ? {userBlockId: user.id} : {}),
    ...(assistant ? {assistantBlockId: assistant.id} : {})};
  if (hasResponse(assistant)) {
    next = {...next, state: 'settled', error: undefined, errorKey: undefined};
  } else if (assistant?.status === 'stopped' || taskMatches && session?.taskState === 'cancelled') {
    next = {...next, state: 'stopped', error: undefined, errorKey: undefined};
  } else if (taskMatches && (session?.taskFailure || session?.taskState === 'completed')
    || assistant && !assistant.active && ['failed', 'completed'].includes(assistant.status)
    || user && ['failed', 'completed'].includes(user.status) && !assistant) {
    next = {...next, state: 'failed', error: taskMatches ? session?.taskFailure?.message || undefined : undefined, errorKey: 'replyFailed'};
  } else if ((request.state === 'sending' || request.state === 'accepted') && now - request.startedAt >= FIRST_RESPONSE_TIMEOUT_MS) {
    next = {...next, state: 'timedOut', errorKey: 'replyTimeout'};
  }
  return Object.keys(next).every(key => next[key as keyof OptimisticSubmission] === request[key as keyof OptimisticSubmission]) ? request : next;
}

/** Reopening an accepted queued task uses the gateway's thread-scoped prompt, never demo data. */
export function recoverQueuedSubmission(session: SessionView, requests: readonly OptimisticSubmission[], now = Date.now()): OptimisticSubmission | null {
  const waiting = session.blocks.find(block => block.role === 'assistant' && block.active && !hasResponse(block));
  if (waiting && !requests.some(request => request.threadId === session.id
    && (request.turnId === waiting.turnId || request.assistantBlockId === waiting.id))) {
    const user = [...session.blocks.slice(0, session.blocks.indexOf(waiting))].reverse()
      .find(block => block.role === 'user' && block.turnId === waiting.turnId);
    const text = user?.text || (session.taskTurnId === waiting.turnId ? session.taskPrompt : '');
    if (text && !requests.some(request => request.threadId === session.id && request.text === text
      && request.state !== 'settled')) {
      return {...beginOptimisticSubmission({threadId: session.id, requestId: `turn-${waiting.turnId}`, text, action: 'sendMessage'}, session, now),
        state: 'accepted', turnId: waiting.turnId, userBlockId: user?.id, assistantBlockId: waiting.id,
        taskId: session.taskTurnId === waiting.turnId ? session.taskId : undefined};
    }
  }
  if (!session.queued || !session.taskId || !session.taskPrompt.trim()
    || requests.some(request => request.threadId === session.id && request.taskId === session.taskId)) return null;
  // Sideband task state can lag a completed streamed turn. Positive native evidence wins.
  if (session.taskTurnId && session.blocks.some(block => block.turnId === session.taskTurnId)) return null;
  if (requests.some(request => request.threadId === session.id && request.text === session.taskPrompt && !request.nonDeliverable)) return null;
  return {...beginOptimisticSubmission({threadId: session.id, requestId: `task-${session.taskId}`,
    text: session.taskPrompt, action: 'sendMessage'}, session, now), taskId: session.taskId, state: 'accepted'};
}
