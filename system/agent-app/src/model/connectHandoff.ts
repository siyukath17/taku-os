/**
 * "Dispatch a task" from Taku Connect: the chat screen takes this once, opens a new conversation
 * and puts the text in its composer (never sent automatically), like an Ask-Taku draft from Home.
 */
let pending: string | null = null;

export function queueConnectDispatch(text: string): void {
  pending = text.trim() ? text : null;
}

export function takeConnectDispatch(): string | null {
  const text = pending;
  pending = null;
  return text;
}
