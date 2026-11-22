package org.agentos.lab;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reopening an existing ACP conversation (thread/read) warms the agent in the background: never for a
 * removed conversation, never while the pool already holds a process, at most once per interval, and
 * never on the reader's thread.
 */
public final class AcpReadWarmupTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("acp-read-warmup").toFile();
        dir.deleteOnExit();
        AcpConversationStore store = new AcpConversationStore(new File(dir, "acp-conversations"));
        String live = store.startNew(), removed = store.startNew(), half = store.startNew();
        store.remove(removed);
        // A crash between tombstone and delete: the record is still there, the tombstone wins.
        Files.write(store.tombstone(half).toPath(), new byte[0]);
        check(store.owns(half) && store.removed(half), "fixture: a half-removed conversation is still owned");

        AtomicBoolean empty = new AtomicBoolean(true);
        AtomicLong now = new AtomicLong(1_000_000);
        List<String> reasons = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        AcpReadWarmup warmup = new AcpReadWarmup(empty::get, reason -> {
            reasons.add(reason + "@" + Thread.currentThread().getName());
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }, now::get);

        check(!warmup.onRead(removed, store) && !warmup.onRead(half, store), "removed conversations never warm");
        check(!warmup.onRead("", store) && !warmup.onRead(null, store), "no id, no warm-up");

        // The trigger blocks until released: the read must return at once anyway.
        long begin = System.nanoTime();
        check(warmup.onRead(live, store), "a read of a live conversation with an empty pool warms");
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        check(tookMillis < 500, "the read is not blocked by the warm-up (" + tookMillis + "ms)");
        check(started.await(5, TimeUnit.SECONDS), "the warm-up runs");
        check(reasons.size() == 1 && reasons.get(0).equals("thread/read@acp-prewarm-read"),
                "the warm-up runs on its own thread with reason thread/read: " + reasons);
        release.countDown();

        check(!warmup.onRead(live, store), "a refresh within the interval does not trigger again");
        now.addAndGet(AcpReadWarmup.MIN_INTERVAL_MILLIS);
        empty.set(false);
        check(!warmup.onRead(live, store), "a pool holding a process (idle, warming or in a turn) is left alone");
        empty.set(true);
        check(warmup.onRead(live, store), "after the interval an empty pool warms again");

        // A trigger that throws must not escape its thread (an uncaught throwable ends the broker).
        AtomicLong later = new AtomicLong(0);
        CountDownLatch thrown = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        AtomicBoolean escaped = new AtomicBoolean(false);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> escaped.set(true));
        try {
            AcpReadWarmup failing = new AcpReadWarmup(() -> true, reason -> {
                thrown.countDown();
                throw new NoSuchFieldError("simulated");
            }, later::get);
            check(failing.onRead(live, store) && thrown.await(5, TimeUnit.SECONDS), "a failing trigger still starts");
            Thread.sleep(100);
            check(!escaped.get(), "a failing trigger is contained on its thread");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        System.out.println("AcpReadWarmupTest passed " + checks + " checks");
    }
}
