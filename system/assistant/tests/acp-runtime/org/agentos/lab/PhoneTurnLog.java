package org.agentos.lab;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records {@link AcpPhoneTurns} starts and ends ("start|end <thread> <turn>") in place of the device hooks. */
final class PhoneTurnLog {
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private PhoneTurnLog() {}

    static void install() {
        AcpPhoneTurns.hooks = new AcpPhoneTurns.Hooks() {
            @Override public void started(String thread, String turn) { EVENTS.add("start " + thread + " " + turn); }
            @Override public void ended(String thread, String turn) { EVENTS.add("end " + thread + " " + turn); }
        };
    }

    static List<String> events() {
        return EVENTS;
    }

    static void clear() {
        EVENTS.clear();
    }
}
