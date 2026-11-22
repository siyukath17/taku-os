package org.agentos.lab;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** The broker loop's pause: a full pause without events, an early return once a reported task is ready. */
public final class TaskWakeTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static long timed(TaskWake wake, long maxMillis) throws Exception {
        long begin = System.nanoTime();
        wake.await(maxMillis);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
    }

    public static void main(String[] args) throws Exception {
        File tasks = Files.createTempDirectory("task-wake").toFile();
        tasks.deleteOnExit();
        TaskWake wake = new TaskWake(tasks);
        check(timed(wake, 120) >= 115, "no event: the whole pause");
        // Published while the loop pauses: mkdir (event), request.json, then the ready mark.
        File task = new File(tasks, "1001");
        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(30);
                check(task.mkdir(), "task dir");
                wake.created("1001");
                Thread.sleep(20);
                Files.write(new File(task, "ready").toPath(), new byte[0]);
            } catch (Exception failed) { throw new RuntimeException(failed); }
        });
        publisher.start();
        long waited = timed(wake, 1000);
        publisher.join();
        check(waited >= 45 && waited < 200, "returns within a step of the ready mark: " + waited + "ms");
        check(wake.pending() == 0, "the ready task is no longer pending");
        // Reported but never ready: the pause still ends on time and the name stays pending (bounded).
        wake.created("1002");
        long unready = timed(wake, 80);
        check(unready >= 75 && unready < 300 && wake.pending() == 1, "an unready task does not end the pause early: " + unready + "ms");
        // A task already ready when reported ends the pause at once.
        File ready = new File(tasks, "1003");
        check(ready.mkdir() && new File(ready, "ready").createNewFile(), "ready task");
        wake.created("1003");
        check(timed(wake, 1000) < 50, "an already-ready task: no pause");
        // Junk event names are ignored.
        wake.created(null);
        wake.created("");
        wake.created(".tmp");
        wake.created("a/b");
        check(wake.pending() == 1, "null / empty / dot / nested names are ignored");
        System.out.println("TaskWakeTest passed " + checks + " checks");
    }
}
