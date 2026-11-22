package org.agentos.lab;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Task update broadcasts off the broker's task loop. Each {@code am broadcast} is a process start
 * (~100-300ms on the Pixel 9) that used to block the turn it reports on; now one daemon thread sends
 * them in order. An update for a task whose previous update has not been sent yet is merged into it:
 * the receiver reads the task's current state files, so the newest state is what it sees either way.
 */
final class CoalescingNotifier {
    private final Consumer<String> send;
    private final ExecutorService worker;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    CoalescingNotifier(Consumer<String> send) {
        this.send = send;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-notifier");
            thread.setDaemon(true);
            return thread;
        });
    }

    void notify(String taskId) {
        if (taskId == null || !pending.add(taskId)) return;
        worker.execute(() -> {
            // Removed before sending: an update that arrives while this one is sent is sent again.
            pending.remove(taskId);
            try {
                send.accept(taskId);
            } catch (Throwable failure) {
                // An uncaught throwable on any thread kills the broker's app_process (seen on the Pixel 9).
                System.err.println("notification: " + failure);
            }
        });
    }
}
