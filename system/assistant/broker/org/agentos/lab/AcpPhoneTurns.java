package org.agentos.lab;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ACP turn that currently owns each phone-tool thread.
 *
 * <p>A warm {@code agy acp} process (see {@link AcpAgentPool}) keeps its {@code taku_phone} MCP
 * server connected across turns, so "the MCP connection closed" no longer means "the turn ended".
 * {@link AcpRuntime} therefore opens the turn here before the agent starts and closes it exactly once
 * when the task reaches a terminal state (completed, failed, cancelled, needs_user); the device
 * {@link Hooks} start the turn's gesture owner and release its background screen, as
 * {@code turn/started}..{@code turn/completed} does for a Codex turn. The phone MCP host resolves a
 * tool call's turn through {@link #current}, never through the turn its relay was started for.
 *
 * <p>Ending a turn always closes that turn's own gesture owner, but only the thread's current turn
 * releases thread-scoped state (its background screen): a late or repeated end of an older turn must
 * never take the screen of a newer turn that already began. Begin and end are serialized so a turn's
 * hooks never interleave with the next turn's.
 */
final class AcpPhoneTurns {
    interface Hooks {
        void started(String thread, String turn) throws Exception;

        /**
         * {@code turn} ended. {@code current} is true only when it was still the thread's current turn
         * (false for a repeated end, or for an older turn ending after a newer one began): only then may
         * thread-scoped state such as the background screen be released.
         */
        void ended(String thread, String turn, boolean current) throws Exception;
    }

    private static final Map<String, String> CURRENT = new ConcurrentHashMap<>();
    /** Device wiring (gestures, dedicated screens), set by the broker; host tests install a recorder. */
    static volatile Hooks hooks;

    private AcpPhoneTurns() {}

    static synchronized void begin(String thread, String turn) {
        if (thread == null || thread.isEmpty() || turn == null || turn.isEmpty()) return;
        CURRENT.put(thread, turn);
        Hooks h = hooks;
        if (h != null) try {
            h.started(thread, turn);
        } catch (Exception | LinkageError failure) {
            System.err.println("acp phone turn start: " + failure.getMessage());
        }
    }

    /** Ends {@code turn}; a later turn of the same thread that already began is left current. */
    static synchronized void end(String thread, String turn) {
        if (thread == null || thread.isEmpty() || turn == null || turn.isEmpty()) return;
        boolean current = CURRENT.remove(thread, turn);
        Hooks h = hooks;
        if (h != null) try {
            h.ended(thread, turn, current);
        } catch (Exception | LinkageError failure) {
            System.err.println("acp phone turn end: " + failure.getMessage());
        }
    }

    /** The running turn of {@code thread}, or null between turns. */
    static String current(String thread) {
        return thread == null ? null : CURRENT.get(thread);
    }
}
