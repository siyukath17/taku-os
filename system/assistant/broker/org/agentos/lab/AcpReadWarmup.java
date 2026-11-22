package org.agentos.lab;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Warms the ACP agent when the user opens an existing ACP conversation ({@code thread/read}), so the
 * next message there skips agy's ~9 s cold start just like a new conversation does after
 * {@code thread/start}.
 *
 * <p>Rules, in order: a removed (tombstoned) conversation never warms anything; nothing happens while
 * the pool already has a process idle, warming or checked out by a turn; repeated reads (the chat page
 * refreshes) start at most one trigger per {@link #MIN_INTERVAL_MILLIS}. The trigger itself runs on a
 * daemon thread, so the read is never delayed, and it decides on its own whether ACP is the new
 * conversation runtime and the agent is signed in ({@code TaskBroker.prewarmAcpNow}).
 *
 * <p>The client's foreground entries (chat page or Home back in front, the in-place overlay shown, the
 * composer focused) call {@link #onForeground} through the native {@code acpPrewarm} op: an open chat
 * page that comes back to the front issues no {@code thread/read}, so without it the first send after
 * the screen wakes paid the whole cold start (agy launch plus an OAuth refresh). Both entries share one
 * pool-empty check and one interval.
 */
final class AcpReadWarmup {
    static final String REASON = "thread_read";
    /**
     * Foreground triggers a client may send, logged as the prewarm reason: {@code onResume} (chat page or
     * Home back in front), {@code overlay} (the in-place overlay shown), {@code focus} (composer focused).
     */
    static final java.util.Set<String> SURFACES = java.util.Set.of("onResume", "overlay", "focus");
    static final long MIN_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final BooleanSupplier poolEmpty;
    private final Consumer<String> prewarm;
    private final LongSupplier clock;
    private long lastTrigger = Long.MIN_VALUE, lastSkipLog = Long.MIN_VALUE;

    AcpReadWarmup(BooleanSupplier poolEmpty, Consumer<String> prewarm, LongSupplier clock) {
        this.poolEmpty = poolEmpty;
        this.prewarm = prewarm;
        this.clock = clock;
    }

    /** Called on each read of an ACP conversation; true when a background warm-up was started. */
    boolean onRead(String conversationId, AcpConversationStore store) {
        if (conversationId == null || conversationId.isEmpty() || store.removed(conversationId)) return false;
        return trigger(REASON, "acp-prewarm-read");
    }

    /**
     * Called when a client surface comes to the front; true when a background warm-up was started.
     * An unknown surface is refused (IllegalArgumentException), never silently widened.
     */
    boolean onForeground(String surface) {
        if (surface == null || !SURFACES.contains(surface)) throw new IllegalArgumentException("UNSUPPORTED_SURFACE");
        return trigger(surface, "acp-prewarm-foreground");
    }

    private boolean trigger(String reason, String threadName) {
        long now = clock.getAsLong();
        String skipped = null;
        synchronized (this) {
            if (!poolEmpty.getAsBoolean()) skipped = "pool-busy";
            else if (lastTrigger != Long.MIN_VALUE && now - lastTrigger < MIN_INTERVAL_MILLIS) skipped = "debounced";
            else lastTrigger = now;
            // Skips are logged at most once per interval: the chat page re-reads often.
            if (skipped != null && (lastSkipLog != Long.MIN_VALUE && now - lastSkipLog < MIN_INTERVAL_MILLIS)) skipped = "";
            else if (skipped != null) lastSkipLog = now;
        }
        if (skipped != null) {
            if (!skipped.isEmpty()) System.err.println("acp prewarm (" + reason + ") skipped: " + skipped);
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                prewarm.accept(reason);
            } catch (Throwable unexpected) {
                // A background helper must never end the broker (an uncaught throwable kills app_process).
                System.err.println("acp prewarm (" + reason + ") trigger: " + unexpected);
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
        return true;
    }
}
