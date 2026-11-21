package org.agentos.lab;

import org.json.JSONObject;

import java.io.File;

/**
 * One agent runtime that executes a prepared background task directory.
 *
 * <p>Contract (shared with the UI and the Dynamic Island): before returning normally, the runtime
 * writes a terminal {@code state.json} ({@code completed|failed|needs_user|cancelled}) and then the
 * {@code done} marker. Throwing leaves terminal bookkeeping to {@link TaskBroker}'s failure path.
 */
interface TaskRuntime {
    /** Stable identifier recorded in diagnostics: {@code codex}, {@code acp} or {@code remote_t3}. */
    String name();

    void run(File directory, JSONObject request) throws Exception;
}
