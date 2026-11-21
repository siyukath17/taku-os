package org.agentos.lab;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Runtime selection: Codex stays the default and the fallback for every doubtful case. */
public final class RuntimeRouterTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static final TaskRuntime CODEX = new TaskRuntime() {
        @Override public String name() { return "codex"; }
        @Override public void run(File directory, JSONObject request) {}
    };
    static final List<JSONObject> acpCreated = new CopyOnWriteArrayList<>();
    static final RuntimeRouter.AcpFactory ACP = config -> {
        acpCreated.add(config);
        return new TaskRuntime() {
            @Override public String name() { return "acp"; }
            @Override public void run(File directory, JSONObject request) {}
        };
    };

    static RuntimeRouter router(JSONObject config, Map<String, String> env) {
        return new RuntimeRouter(CODEX, ACP, () -> config, env);
    }

    static JSONObject acpConfig() {
        return new JSONObject().put("default", "acp").put("acp", new JSONObject().put("binary", "/data/local/agentos/acp/bin/agy"));
    }

    static JSONObject task() {
        return new JSONObject().put("id", "1").put("prompt", "x");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> noEnv = new HashMap<>();

        RuntimeRouter.Route route = router(null, noEnv).route(task());
        check("codex".equals(route.runtime) && "default".equals(route.reason), "no runtime.json: codex by default");
        check(router(null, noEnv).select(route) == CODEX, "codex route returns the existing Codex runtime instance");
        check("codex".equals(router(new JSONObject(), null).route(task()).runtime), "empty config and no env: codex");

        route = router(acpConfig(), noEnv).route(task());
        check("acp".equals(route.runtime) && "selected-by-config".equals(route.reason), "config default acp with binary: acp");
        TaskRuntime selected = router(acpConfig(), noEnv).select(route);
        check("acp".equals(selected.name()) && "/data/local/agentos/acp/bin/agy".equals(acpCreated.get(acpCreated.size() - 1).getString("binary")),
                "acp route builds the ACP runtime from the acp config");

        route = router(new JSONObject().put("default", "acp"), noEnv).route(task());
        check("codex".equals(route.runtime) && "acp-unconfigured".equals(route.reason), "acp without binary: codex fallback");
        route = router(new JSONObject().put("default", "acp").put("acp", new JSONObject().put("binary", "  ")), noEnv).route(task());
        check("codex".equals(route.runtime) && "acp-unconfigured".equals(route.reason), "blank binary: codex fallback");

        route = router(acpConfig(), noEnv).route(task().put("runtime", "codex"));
        check("codex".equals(route.runtime) && "selected-by-request".equals(route.reason), "request runtime=codex overrides config");

        JSONObject codexDefault = acpConfig().put("default", "codex");
        route = router(codexDefault, Map.of("TAKU_TASK_RUNTIME", "acp")).route(task());
        check("acp".equals(route.runtime) && "selected-by-env".equals(route.reason), "env selects acp over config default");
        route = router(codexDefault, Map.of("TAKU_TASK_RUNTIME", "acp")).route(task().put("runtime", "codex"));
        check("codex".equals(route.runtime), "request beats env");

        route = router(acpConfig(), noEnv).route(task().put("conversationId", "thread-1"));
        check("codex".equals(route.runtime) && "acp-ineligible:conversation".equals(route.reason), "chat conversations stay on Codex");
        route = router(acpConfig(), noEnv).route(task().put("panelUpdate", new JSONObject().put("originThreadId", "t")));
        check("codex".equals(route.runtime) && "acp-ineligible:panel-update".equals(route.reason), "panel updates stay on Codex");
        route = router(acpConfig(), noEnv).route(task().put("kind", "agent_control"));
        check("codex".equals(route.runtime) && "acp-ineligible:control".equals(route.reason), "agent controls stay on Codex");

        route = router(acpConfig(), noEnv).route(task().put("runtime", "claude"));
        check("codex".equals(route.runtime) && "unknown-runtime".equals(route.reason), "unknown runtime name: codex");

        route = new RuntimeRouter(CODEX, ACP, () -> { throw new IllegalStateException("bad json"); }, noEnv).route(task().put("runtime", "acp"));
        check("codex".equals(route.runtime) && "config-invalid".equals(route.reason), "unreadable runtime.json: codex");

        JSONObject json = router(acpConfig(), noEnv).route(task()).json();
        check(json.getString("runtime").equals("acp") && json.getString("requested").equals("acp") && json.has("reason"),
                "route diagnostics are serialisable");
        System.out.println("RuntimeRouterTest passed " + checks + " checks");
    }
}
