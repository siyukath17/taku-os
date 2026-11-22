package org.agentos.lab;

import java.io.IOException;

/**
 * The gesture-pointer owner of one native thread's turn: {@code thread + "\n" + turn}, the same key
 * {@link PhoneGestures#startTurn} opens. Only the shape is checked here; a well-formed but unstarted
 * or ended owner is still refused by {@link PhoneGestureSession#touch}.
 *
 * <p>A Codex turn is a UUID. An ACP turn ({@link AcpRuntime}) is {@code acp-<uuid>} for a chat turn
 * ({@link AcpConversationStore}) or {@code acp-task-<task id>} for a background task (the task id
 * grammar {@link PhoneMcpHost} accepts).
 */
final class GestureOwners {
    static final String THREAD = "[a-f0-9-]{36}";
    static final String TURN = "[a-f0-9-]{36}|acp-[a-f0-9-]{36}|acp-task-[A-Za-z0-9._-]{1,128}";

    private GestureOwners() {}

    static String of(String thread, String turn) throws IOException {
        if (thread == null || turn == null || !thread.matches(THREAD) || !turn.matches(TURN))
            throw new IOException("Current task and execution turn are missing");
        return thread + "\n" + turn;
    }
}
