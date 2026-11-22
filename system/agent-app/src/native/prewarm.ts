/**
 * Foreground warm-up of the on-device agent (gateway `prewarmAgent`, broker `acpPrewarm`).
 *
 * An open chat page that comes back to the front issues no `thread/read`, so without this the first
 * send after the screen wakes paid agy's whole cold start (process launch plus an OAuth refresh).
 * The broker decides everything that matters (ACP default, signed in, empty pool, its own 5s
 * interval) and answers at once; this side only keeps the UI from asking more than once per
 * interval, never awaits the result and never surfaces a failure.
 */
export type PrewarmSurface = 'onResume' | 'overlay' | 'focus';
export const PREWARM_INTERVAL_MS = 5_000;

let last = Number.NEGATIVE_INFINITY;

/** Fire-and-forget; true when a request was sent (not throttled). */
export function requestPrewarm(surface: PrewarmSurface, send: (surface: PrewarmSurface) => Promise<unknown>, now = Date.now()): boolean {
  if (now - last < PREWARM_INTERVAL_MS) return false;
  last = now;
  try {
    void send(surface).catch(() => { /* Best effort: the send path still starts the agent itself. */ });
  } catch { /* Same: a synchronous failure (no native module) is ignored. */ }
  return true;
}

/** Tests only. */
export function resetPrewarmThrottle(): void {
  last = Number.NEGATIVE_INFINITY;
}
