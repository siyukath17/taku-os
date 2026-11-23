package org.agentos.lab;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private static File publish(File tasks, String name) throws Exception {
        File task = new File(tasks, name);
        check(task.mkdir() && new File(task, "ready").createNewFile(), "published " + name);
        return task;
    }

    /** Duplicate events, withdrawn and expiring directories, interrupts and concurrent publishers. */
    static void edges(File tasks) throws Exception {
        // The same directory reported twice (CREATE then MOVED_TO) is one pending entry.
        TaskWake twice = new TaskWake(tasks);
        twice.created("2001");
        twice.created("2001");
        check(twice.pending() == 1, "a repeated event is one pending directory");
        // Withdrawn before its ready mark (the client deleted it): never ends the pause early.
        File withdrawn = new File(tasks, "2001");
        check(withdrawn.mkdir() && withdrawn.delete(), "withdrawn task dir");
        long waited = timed(twice, 60);
        check(waited >= 55, "a withdrawn task does not end the pause early: " + waited + "ms");

        // A directory that never gets its mark is forgotten after the fresh window, then costs nothing.
        TaskWake expiring = new TaskWake(tasks, 40);
        expiring.created("2002");
        check(timed(expiring, 120) >= 115 && expiring.pending() == 0, "an unready task is forgotten after the fresh window");
        // ...and its ready mark arriving after that is found by the loop's next pass, not by the wake.
        publish(tasks, "2002");
        check(timed(expiring, 60) >= 55, "a forgotten task no longer wakes the loop");

        // Two tasks ready in one pause: both drained by a single early return.
        TaskWake both = new TaskWake(tasks);
        publish(tasks, "2003");
        publish(tasks, "2004");
        both.created("2003");
        both.created("2004");
        check(timed(both, 1000) < 50 && both.pending() == 0, "every ready task is drained by one early return");
        check(timed(both, 60) >= 55, "drained tasks do not wake the next pause");

        // An unready directory keeps the step poll, but a later ready one still wakes it within a step.
        TaskWake mixed = new TaskWake(tasks);
        mixed.created("2005");
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(30);
                publish(tasks, "2006");
                mixed.created("2006");
            } catch (Exception failed) { throw new RuntimeException(failed); }
        });
        late.start();
        long mixedWait = timed(mixed, 1000);
        late.join();
        check(mixedWait >= 25 && mixedWait < 200 && mixed.pending() == 1,
                "a ready task wakes the pause past an unready one: " + mixedWait + "ms, pending " + mixed.pending());

        // The broker thread interrupted mid-pause: the interrupt surfaces at once.
        TaskWake interrupted = new TaskWake(tasks);
        List<Throwable> outcome = new CopyOnWriteArrayList<>();
        Thread sleeper = new Thread(() -> {
            try {
                interrupted.await(10_000);
                outcome.add(new AssertionError("await returned without the interrupt"));
            } catch (InterruptedException expected) {
                outcome.add(expected);
            }
        });
        sleeper.start();
        Thread.sleep(30);
        long begin = System.nanoTime();
        sleeper.interrupt();
        sleeper.join(2000);
        check(!sleeper.isAlive() && outcome.size() == 1 && outcome.get(0) instanceof InterruptedException
                && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 500, "an interrupt ends the pause: " + outcome);

        // Many clients publishing at once while the loop pauses: every task wakes it, nothing is lost.
        TaskWake busy = new TaskWake(tasks);
        int clients = 16;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> publishers = new ArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < clients; i++) {
            String name = Integer.toString(3000 + i);
            Thread client = new Thread(() -> {
                try {
                    go.await();
                    File task = new File(tasks, name);
                    if (!task.mkdir()) throw new IllegalStateException("mkdir " + name);
                    busy.created(name);
                    Thread.sleep(2);
                    if (!new File(task, "ready").createNewFile()) throw new IllegalStateException("ready " + name);
                } catch (Throwable failed) {
                    failures.add(failed);
                }
            });
            publishers.add(client);
            client.start();
        }
        go.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int wakes = 0;
        boolean published = false;
        while (System.nanoTime() < deadline) {
            busy.await(1000);
            wakes++;
            published = publishers.stream().noneMatch(Thread::isAlive);
            if (published && busy.pending() == 0) break;
        }
        for (Thread client : publishers) client.join();
        check(failures.isEmpty(), "concurrent publishers: no failure " + failures);
        check(published && busy.pending() == 0 && wakes <= clients + 1,
                "concurrent publishers: all drained in " + wakes + " wakes, pending " + busy.pending());
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
        edges(tasks);
        System.out.println("TaskWakeTest passed " + checks + " checks");
    }
}
