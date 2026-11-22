/** Restore local conversation context independently of the optional history index. */
export async function restoreConversation<T extends {id: string}>(options: {
  preferred: string;
  list: () => Promise<T[]>;
  select: (id: string) => Promise<void>;
  isCurrent: () => boolean;
  onListError: (error: unknown) => void;
}): Promise<void> {
  const {preferred, list, select, isCurrent, onListError} = options;
  if (!isCurrent()) return;
  if (preferred) {
    // A slow index must not delay an explicit route or its saved draft.
    void list().catch(onListError);
    await select(preferred);
    return;
  }
  let items: T[] = [];
  try { items = await list(); } catch (error) { onListError(error); }
  // Even an unavailable index must release restoration of the local new-chat draft.
  if (isCurrent()) await select(items[0]?.id || '');
}

/** Each independent read owns its errors; late results cannot mutate another route. */
export async function readCurrent<T>(options: {
  read: () => Promise<T>;
  isCurrent: () => boolean;
  accept: (value: T) => void;
  reject: (error: unknown) => void;
  settled?: () => void;
}): Promise<void> {
  try {
    const value = await options.read();
    if (options.isCurrent()) options.accept(value);
  } catch (error) {
    if (options.isCurrent()) options.reject(error);
  } finally {
    if (options.isCurrent()) options.settled?.();
  }
}

/**
 * Deleting a conversation (management route) and showing it (chat controller) share one JS
 * runtime: the delete tells the controller at once, so it never keeps polling a removed thread.
 */
type RemovalListener = (threadId: string) => void;
const removalListeners = new Set<RemovalListener>();
export function notifyConversationRemoved(threadId: string): void {
  if (threadId) for (const listener of [...removalListeners]) listener(threadId);
}
export function onConversationRemoved(listener: RemovalListener): {remove(): void} {
  removalListeners.add(listener);
  return {remove() { removalListeners.delete(listener); }};
}

export const isThreadUnavailable = (problem: unknown): boolean =>
  typeof problem === 'object' && problem !== null && (problem as {code?: unknown}).code === 'THREAD_UNAVAILABLE';

/**
 * Where the chat goes once `removed` is gone: the newest remaining conversation, else a new one.
 * Null keeps the selection: an unconfirmed THREAD_UNAVAILABLE for a thread the fresh index still
 * lists is transient, never a reason to leave it.
 */
export function selectionAfterRemoval<T extends {id: string}>(removed: string, threads: readonly T[], confirmed: boolean): string | null {
  if (!removed || (!confirmed && threads.some(thread => thread.id === removed))) return null;
  return threads.find(thread => thread.id !== removed)?.id ?? '';
}
