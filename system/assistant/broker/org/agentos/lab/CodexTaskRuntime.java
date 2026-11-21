package org.agentos.lab;

import org.json.JSONObject;

import java.io.File;

/** The default runtime: the existing Codex app-server path, unchanged. */
final class CodexTaskRuntime implements TaskRuntime {
    static final CodexTaskRuntime INSTANCE = new CodexTaskRuntime();

    private CodexTaskRuntime() {}

    @Override
    public String name() {
        return RuntimeRouter.CODEX;
    }

    @Override
    public void run(File directory, JSONObject request) throws Exception {
        CodexAppServer.run(directory, request);
    }
}
