import type {MessageBlock} from './session';
import {hasResponse, type OptimisticSubmission, type ResponseErrorKey} from './optimisticChat.ts';

export type ChatMessageBlock = MessageBlock & {requestStopped?: boolean; responsePending?: boolean; responseError?: string; responseErrorKey?: ResponseErrorKey};

function optimisticBlocks(blocks: readonly MessageBlock[], requests: readonly OptimisticSubmission[]): ChatMessageBlock[] {
  const result: ChatMessageBlock[] = [...blocks];
  for (const [requestIndex, request] of requests.entries()) {
    const prefix = `optimistic/${request.requestId}`;
    const base = {turnId: request.turnId || prefix, parts: [], attachments: [], activity: [], status: 'running' as const, active: false};
    let userIndex = result.findIndex(block => block.id === request.userBlockId);
    let assistantIndex = result.findIndex(block => block.id === request.assistantBlockId);
    // A broker resnapshot can temporarily omit history. It is not a cancellation.
    if (request.state === 'settled' && assistantIndex < 0) continue;
    if (userIndex < 0) {
      const nextRequests = requests.slice(requestIndex + 1);
      const nextNativeIndex = result.findIndex(block => nextRequests.some(next => block.id === next.userBlockId || block.id === next.assistantBlockId));
      // A failed local attempt remains before the next request when its native rows arrive.
      userIndex = assistantIndex >= 0 ? assistantIndex : nextNativeIndex >= 0 ? nextNativeIndex : result.length;
      result.splice(userIndex, 0, {...base, id: `${prefix}/user`, role: 'user', text: request.text});
      if (assistantIndex >= 0) ++assistantIndex;
    } else result[userIndex] = {...result[userIndex], id: `${prefix}/user`};
    if (assistantIndex < 0) {
      assistantIndex = userIndex + 1;
      result.splice(assistantIndex, 0, {...base, id: `${prefix}/assistant`, role: 'assistant', text: '', active: true});
    }
    const assistant = result[assistantIndex];
    const responsePending = !hasResponse(assistant) && (request.state === 'sending' || request.state === 'accepted');
    const failed = !hasResponse(assistant) && (request.state === 'failed' || request.state === 'timedOut');
    const stopped = request.state === 'stopped' && !hasResponse(assistant);
    if (stopped) result[userIndex] = {...result[userIndex], status: 'stopped', active: false};
    result[assistantIndex] = {...assistant, id: `${prefix}/assistant`, responsePending,
      ...(failed ? {status: 'failed', active: false, responseError: request.error, responseErrorKey: request.errorKey || 'replyFailed'} : {}),
      ...(stopped ? {status: 'stopped', active: false} : {})};
  }
  return result;
}

/** Keep an empty interrupted response with its request, without changing native/task history. */
export function chatPresentation(blocks: readonly MessageBlock[], requests: readonly OptimisticSubmission[] = []): ChatMessageBlock[] {
  const result: ChatMessageBlock[] = [];
  for (const block of optimisticBlocks(blocks, requests)) {
    const request = result[result.length - 1];
    const emptyStoppedReply = block.role === 'assistant' && block.status === 'stopped' && !block.active
      && !block.text.trim() && !block.activity.length && !block.subagents?.length && !block.attachments.length && !block.parts?.some(part => part.card || part.replyState);
    if (emptyStoppedReply && request?.role === 'user' && request.turnId === block.turnId
      && request.status === 'stopped' && !request.active && !request.requestStopped) {
      result[result.length - 1] = {...request, requestStopped: true};
    } else {
      result.push(block);
    }
  }
  return result;
}
