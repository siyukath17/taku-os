package org.agentos.lab;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records {@link AcpPhoneTurns} starts and ends in place of the device hooks: "start|end <thread> <turn>",
 * and "end-stale <thread> <turn>" for an end that was no longer the thread's current turn.
 */
final class PhoneTurnLog {
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private PhoneTurnLog() {}

    static void install() {
        AcpPhoneTurns.hooks = new AcpPhoneTurns.Hooks() {
            @Override public void started(String thread, String turn) { EVENTS.add("start " + thread + " " + turn); }
            @Override public void ended(String thread, String turn, boolean current) {
                EVENTS.add((current ? "end " : "end-stale ") + thread + " " + turn);
            }
        };
    }

    static List<String> events() {
        return EVENTS;
    }

    static void clear() {
        EVENTS.clear();
    }
}
