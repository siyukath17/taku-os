package org.agentos.lab;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The broker loop's pause between passes over {@code tasks/}, cut short when a task is published.
 *
 * <p>Clients publish a task by writing {@code tasks/<id>/request.json} and then a {@code ready} mark;
 * the loop used to find it on its next pass, up to 150ms later (P50 ~150ms claim delay on the Pixel 9).
 * An inotify watch on {@code tasks/} (FileObserver CREATE / MOVED_TO) reports the new directory;
 * {@link #await} then checks that directory's {@code ready} mark every {@value #STEP_MILLIS}ms and
 * returns as soon as it exists. Without events it is the plain timed pause, so a missed event only
 * costs the old delay. A directory that never gets its mark is forgotten after {@value #FRESH_SECONDS}s.
 */
final class TaskWake {
    static final long STEP_MILLIS = 5, FRESH_SECONDS = 10;

    private final File tasks;
    private final long freshNanos;
    /** New task directory name -> when it was reported (nanoTime). */
    private final Map<String, Long> fresh = new LinkedHashMap<>();

    TaskWake(File tasks) {
        this(tasks, TimeUnit.SECONDS.toMillis(FRESH_SECONDS));
    }

    /** {@code freshMillis}: how long a reported directory without its ready mark is remembered (tests). */
    TaskWake(File tasks, long freshMillis) {
        this.tasks = tasks;
        this.freshNanos = TimeUnit.MILLISECONDS.toNanos(freshMillis);
    }

    /** A directory appeared in {@code tasks/} (an inotify event's name; null and dot names are ignored). */
    synchronized void created(String name) {
        if (name == null || name.isEmpty() || name.startsWith(".") || name.contains("/")) return;
        fresh.put(name, System.nanoTime());
        notifyAll();
    }

    synchronized int pending() {
        return fresh.size();
    }

    /** Pauses up to {@code maxMillis}; returns early once a reported directory has its ready mark. */
    synchronized void await(long maxMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
        for (; ; ) {
            long now = System.nanoTime();
            boolean ready = false;
            for (Iterator<Map.Entry<String, Long>> it = fresh.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> entry = it.next();
                if (new File(new File(tasks, entry.getKey()), "ready").exists()) {
                    it.remove();
                    ready = true;
                } else if (now - entry.getValue() > freshNanos) it.remove();
            }
            if (ready) return;
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - now);
            if (left <= 0) return;
            wait(fresh.isEmpty() ? left : Math.min(left, STEP_MILLIS));
        }
    }
}
