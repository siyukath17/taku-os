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
 */
final class AcpReadWarmup {
    static final String REASON = "thread/read";
    static final long MIN_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final BooleanSupplier poolEmpty;
    private final Consumer<String> prewarm;
    private final LongSupplier clock;
    private long lastTrigger = Long.MIN_VALUE;

    AcpReadWarmup(BooleanSupplier poolEmpty, Consumer<String> prewarm, LongSupplier clock) {
        this.poolEmpty = poolEmpty;
        this.prewarm = prewarm;
        this.clock = clock;
    }

    /** Called on each read of an ACP conversation; true when a background warm-up was started. */
    boolean onRead(String conversationId, AcpConversationStore store) {
        if (conversationId == null || conversationId.isEmpty() || store.removed(conversationId)) return false;
        if (!poolEmpty.getAsBoolean()) return false;
        long now = clock.getAsLong();
        synchronized (this) {
            if (lastTrigger != Long.MIN_VALUE && now - lastTrigger < MIN_INTERVAL_MILLIS) return false;
            lastTrigger = now;
        }
        Thread worker = new Thread(() -> {
            try {
                prewarm.accept(REASON);
            } catch (Throwable unexpected) {
                // A background helper must never end the broker (an uncaught throwable kills app_process).
                System.err.println("acp prewarm (" + REASON + ") trigger: " + unexpected);
            }
        }, "acp-prewarm-read");
        worker.setDaemon(true);
        worker.start();
        return true;
    }
}
