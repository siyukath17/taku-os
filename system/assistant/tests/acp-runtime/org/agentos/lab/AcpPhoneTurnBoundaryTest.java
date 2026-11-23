package org.agentos.lab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AcpPhoneTurns} turn boundaries as the device hooks see them: the gesture owner of the turn
 * that ends is always closed, but only the thread's current turn releases the thread's background
 * screen. Wired to a real {@link PhoneGestureSession} with {@link GestureOwners} keys and the phone MCP
 * session's {@link PhoneMcpBridge.Session#turnId()} binding, exactly as PhoneMcpHost.TURN_HOOKS does.
 */
public final class AcpPhoneTurnBoundaryTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private interface Work {
        void run() throws Exception;
    }

    private static String refusal(Work work) {
        try {
            work.run();
            return null;
        } catch (Exception expected) {
            return String.valueOf(expected.getMessage());
        }
    }

    /** PhoneMcpHost.TURN_HOOKS on a host pointer: gesture owner per turn, the screen per thread. */
    private static final class DeviceHooks implements AcpPhoneTurns.Hooks {
        final PhoneGestureSession pointer;
        final List<String> screenReleases = new CopyOnWriteArrayList<>();
        final List<String> log = new CopyOnWriteArrayList<>();

        DeviceHooks(PhoneGestureSession pointer) {
            this.pointer = pointer;
        }

        @Override public void started(String thread, String turn) throws Exception {
            pointer.startOwner(GestureOwners.of(thread, turn));
            log.add("start " + turn);
        }

        @Override public void ended(String thread, String turn, boolean current) throws Exception {
            pointer.endOwner(GestureOwners.of(thread, turn));
            if (current) screenReleases.add(thread + " " + turn);
            log.add((current ? "end " : "end-stale ") + turn);
        }
    }

    private static String uuid(int n) {
        return String.format("00000000-0000-4000-8000-%012x", n);
    }

    /** A late end of an older turn, and a repeated end, never release the newer turn's screen. */
    static void staleAndRepeatedEnds() {
        AcpPhoneTurns.Hooks saved = AcpPhoneTurns.hooks;
        List<String> events = new CopyOnWriteArrayList<>();
        AcpPhoneTurns.hooks = new AcpPhoneTurns.Hooks() {
            @Override public void started(String thread, String turn) { events.add("start " + turn); }
            @Override public void ended(String thread, String turn, boolean current) {
                events.add((current ? "end " : "end-stale ") + turn);
            }
        };
        try {
            String thread = uuid(0xa1);
            AcpPhoneTurns.begin(thread, "turn-a");
            AcpPhoneTurns.begin(thread, "turn-b");
            AcpPhoneTurns.end(thread, "turn-a");
            check("turn-b".equals(AcpPhoneTurns.current(thread)), "stale end: the newer turn stays current");
            AcpPhoneTurns.end(thread, "turn-b");
            AcpPhoneTurns.end(thread, "turn-b");
            check(AcpPhoneTurns.current(thread) == null, "after the last end: no current turn");
            check(events.equals(List.of("start turn-a", "start turn-b", "end-stale turn-a", "end turn-b", "end-stale turn-b")),
                    "only the current turn's end is current; stale and repeated ends are marked: " + events);
            // An end of a turn that never began on this thread (another thread's, or a forged one).
            events.clear();
            String other = uuid(0xa2);
            AcpPhoneTurns.begin(other, "turn-c");
            AcpPhoneTurns.end(thread, "turn-c");
            check("turn-c".equals(AcpPhoneTurns.current(other)) && events.equals(List.of("start turn-c", "end-stale turn-c")),
                    "an end on the wrong thread leaves the other thread's turn current: " + events);
            AcpPhoneTurns.end(other, "turn-c");
            // Missing thread or turn: nothing changes, no hook runs.
            events.clear();
            AcpPhoneTurns.begin(null, "x");
            AcpPhoneTurns.begin(thread, "");
            AcpPhoneTurns.end("", "x");
            AcpPhoneTurns.end(thread, null);
            check(events.isEmpty() && AcpPhoneTurns.current(thread) == null && AcpPhoneTurns.current(null) == null,
                    "null / empty thread or turn: ignored");
        } finally {
            AcpPhoneTurns.hooks = saved;
        }
    }

    /** A failing hook never corrupts which turn is current. */
    static void failingHooksKeepState() {
        AcpPhoneTurns.Hooks saved = AcpPhoneTurns.hooks;
        AcpPhoneTurns.hooks = new AcpPhoneTurns.Hooks() {
            @Override public void started(String thread, String turn) throws Exception { throw new IOException("start broke"); }
            @Override public void ended(String thread, String turn, boolean current) { throw new LinkageError("end broke"); }
        };
        try {
            String thread = uuid(0xb1);
            AcpPhoneTurns.begin(thread, "turn-1");
            check("turn-1".equals(AcpPhoneTurns.current(thread)), "a failed start hook still opens the turn");
            AcpPhoneTurns.end(thread, "turn-1");
            check(AcpPhoneTurns.current(thread) == null, "a failed end hook still closes the turn");
        } finally {
            AcpPhoneTurns.hooks = saved;
        }
    }

    /**
     * A warm agent's MCP session across turn switches: each gesture runs as the thread's current ACP
     * turn; an ended turn's owner refuses late gestures and its held pointer is cancelled at its end.
     */
    static void gestureOwnersFollowTurnSwitch() throws Exception {
        AcpPhoneTurns.Hooks saved = AcpPhoneTurns.hooks;
        long[] now = {1000};
        List<String> emitted = new CopyOnWriteArrayList<>();
        PhoneGestureSession pointer = new PhoneGestureSession(() -> now[0],
                (display, downAt, at, phase, x, y, pressure) -> emitted.add(phase));
        DeviceHooks device = new DeviceHooks(pointer);
        AcpPhoneTurns.hooks = device;
        try {
            String thread = AcpRuntime.phoneThread("", "2001");
            String relayTurn = "acp-task-2001";
            PhoneMcpBridge.Session session = new PhoneMcpBridge.Session(thread, "full", relayTurn,
                    () -> AcpPhoneTurns.current(thread));
            // Between turns the session falls back to the relay's turn, which no hook started.
            String idle = refusal(() -> pointer.touch(GestureOwners.of(thread, session.turnId()), 2, "DOWN", 10, 10));
            check(idle != null && idle.contains("turn has ended"), "between turns: a warm agent's gesture is refused: " + idle);

            String turn1 = "acp-" + java.util.UUID.randomUUID();
            AcpPhoneTurns.begin(thread, turn1);
            String owner1 = GestureOwners.of(thread, session.turnId());
            check(owner1.equals(thread + "\n" + turn1), "during turn 1: the session's turn is turn 1");
            pointer.touch(owner1, 2, "DOWN", 100, 100);
            check(pointer.state() != null && owner1.equals(pointer.state().owner()), "turn 1 holds the pointer");

            // Turn 2 begins before turn 1's end arrives (a late finish of the previous task).
            String turn2 = "acp-" + java.util.UUID.randomUUID();
            AcpPhoneTurns.begin(thread, turn2);
            String owner2 = GestureOwners.of(thread, session.turnId());
            check(owner2.equals(thread + "\n" + turn2), "after the switch: the session's turn is turn 2");
            String busy = refusal(() -> pointer.touch(owner2, 2, "DOWN", 5, 5));
            check(busy != null && busy.contains("already held"), "turn 2 cannot take a pointer turn 1 still holds: " + busy);

            AcpPhoneTurns.end(thread, turn1);
            check(pointer.state() == null && emitted.get(emitted.size() - 1).equals("CANCEL"),
                    "turn 1's late end cancels its held pointer: " + emitted);
            String late = refusal(() -> pointer.touch(owner1, 2, "DOWN", 100, 100));
            check(late != null && late.contains("turn has ended"), "a late gesture of turn 1 is refused: " + late);
            check(device.screenReleases.isEmpty(), "turn 1's late end leaves turn 2's background screen: " + device.screenReleases);
            pointer.touch(owner2, 2, "DOWN", 200, 200);
            pointer.touch(owner2, 2, "UP", 200, 200);
            check(pointer.state() == null, "turn 2 owns the pointer after turn 1 ended");

            AcpPhoneTurns.end(thread, turn2);
            check(device.screenReleases.equals(List.of(thread + " " + turn2)),
                    "turn 2's end is the only screen release: " + device.screenReleases);
            String after = refusal(() -> pointer.touch(GestureOwners.of(thread, session.turnId()), 2, "DOWN", 3, 3));
            check(after != null && relayTurn.equals(session.turnId()), "after the last turn: back to the relay turn, refused");

            // Re-beginning an ended turn id (a replayed request) cannot revive its gesture owner.
            AcpPhoneTurns.begin(thread, turn1);
            check(owner1.equals(GestureOwners.of(thread, session.turnId())), "a replayed turn id is current again");
            String revived = refusal(() -> pointer.touch(owner1, 2, "DOWN", 1, 1));
            check(revived != null && revived.contains("turn has ended"), "an ended owner stays ended: " + revived);
            AcpPhoneTurns.end(thread, turn1);
        } finally {
            AcpPhoneTurns.hooks = saved;
        }
    }

    /**
     * Turns of one thread beginning and ending from many threads at once: every screen release is for
     * the turn the device last started, and begin/end hooks never interleave.
     */
    static void concurrentTurnsReleaseOnlyTheCurrentScreen() throws Exception {
        AcpPhoneTurns.Hooks saved = AcpPhoneTurns.hooks;
        String thread = uuid(0xc1);
        AtomicReference<String> deviceTurn = new AtomicReference<>();
        List<String> violations = new CopyOnWriteArrayList<>();
        List<String> releases = Collections.synchronizedList(new ArrayList<>());
        AcpPhoneTurns.hooks = new AcpPhoneTurns.Hooks() {
            @Override public void started(String t, String turn) {
                deviceTurn.set(turn);
                Thread.yield();
                if (!turn.equals(deviceTurn.get())) violations.add("start interleaved " + turn);
            }

            @Override public void ended(String t, String turn, boolean current) {
                if (current) {
                    if (!turn.equals(deviceTurn.get())) violations.add("released " + turn + " while " + deviceTurn.get() + " is current");
                    releases.add(turn);
                }
            }
        };
        try {
            int workers = 8, rounds = 200;
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            for (int w = 0; w < workers; w++) {
                int id = w;
                Thread worker = new Thread(() -> {
                    try {
                        go.await();
                        for (int r = 0; r < rounds; r++) {
                            String turn = "acp-task-w" + id + "-" + r;
                            AcpPhoneTurns.begin(thread, turn);
                            AcpPhoneTurns.end(thread, turn);
                        }
                    } catch (Throwable failed) {
                        failures.add(failed);
                    }
                });
                threads.add(worker);
                worker.start();
            }
            go.countDown();
            for (Thread worker : threads) worker.join(TimeUnit.SECONDS.toMillis(20));
            check(failures.isEmpty(), "concurrent turns: no failure " + failures);
            check(violations.isEmpty(), "concurrent turns: no other turn's screen is released: "
                    + violations.subList(0, Math.min(5, violations.size())));
            check(AcpPhoneTurns.current(thread) == null, "concurrent turns: none stays current once all ended");
            check(!releases.isEmpty() && releases.size() <= workers * rounds && releases.size() == releases.stream().distinct().count(),
                    "concurrent turns: each release is a distinct turn (" + releases.size() + ")");
        } finally {
            AcpPhoneTurns.hooks = saved;
        }
    }

    public static void main(String[] args) throws Exception {
        staleAndRepeatedEnds();
        failingHooksKeepState();
        gestureOwnersFollowTurnSwitch();
        concurrentTurnsReleaseOnlyTheCurrentScreen();
        System.out.println("AcpPhoneTurnBoundaryTest passed " + checks + " checks");
    }
}
