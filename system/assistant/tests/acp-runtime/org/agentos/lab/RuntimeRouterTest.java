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

        // ---- remote_t3: the second lane ----------------------------------------------------------
        List<JSONObject> remoteCreated = new CopyOnWriteArrayList<>();
        RuntimeRouter.RemoteT3Factory remoteFactory = config -> {
            remoteCreated.add(config);
            return new TaskRuntime() {
                @Override public String name() { return "remote_t3"; }
                @Override public void run(File directory, JSONObject request) {}
            };
        };
        JSONObject remoteConfig = acpConfig().put("default", "remote_t3").put("remoteT3", new JSONObject()
                .put("endpoint", "https://t3.example.ts.net").put("tokenEnv", "TAKU_REMOTE_T3_TOKEN").put("model", "base-model"));
        java.util.function.BiFunction<JSONObject, Map<String, String>, RuntimeRouter> remoteRouter =
                (config, env) -> new RuntimeRouter(CODEX, ACP, remoteFactory, () -> config, env);

        route = remoteRouter.apply(remoteConfig, noEnv).route(task());
        check("remote_t3".equals(route.runtime) && "selected-by-config".equals(route.reason), "config default remote_t3: remote lane");
        selected = remoteRouter.apply(remoteConfig, noEnv).select(route);
        check("remote_t3".equals(selected.name()) && "https://t3.example.ts.net".equals(remoteCreated.get(remoteCreated.size() - 1).getString("endpoint")),
                "remote route builds the remote runtime from remoteT3");
        route = remoteRouter.apply(acpConfig(), noEnv).route(task().put("runtime", "remote_t3"));
        check("codex".equals(route.runtime) && "remote-t3-unconfigured".equals(route.reason), "remote_t3 without remoteT3: codex fallback");
        route = remoteRouter.apply(remoteConfig, Map.of("TAKU_TASK_RUNTIME", "acp")).route(task());
        check("acp".equals(route.runtime) && "selected-by-env".equals(route.reason), "env still selects acp over a remote default");
        route = remoteRouter.apply(acpConfig(), Map.of("TAKU_TASK_RUNTIME", "remote_t3")).route(task());
        check("codex".equals(route.runtime) && "remote-t3-unconfigured".equals(route.reason), "env remote_t3 without config: codex");
        route = remoteRouter.apply(remoteConfig, noEnv).route(task().put("runtime", "acp"));
        check("acp".equals(route.runtime) && "selected-by-request".equals(route.reason), "request acp beats a remote default");

        route = remoteRouter.apply(remoteConfig, noEnv).route(task().put("runtime", "remote_t3")
                .put("remoteT3", new JSONObject().put("model", "claude-opus-5-5").put("projectId", "taku")));
        check("remote_t3".equals(route.runtime) && "claude-opus-5-5".equals(route.remoteConfig.getString("model"))
                && "taku".equals(route.remoteConfig.getString("projectId"))
                && "base-model".equals(remoteConfig.getJSONObject("remoteT3").getString("model")), "request overrides model/projectId on a copy");
        route = remoteRouter.apply(remoteConfig, noEnv).route(task().put("remoteT3", new JSONObject().put("endpoint", "https://attacker.example")));
        check("codex".equals(route.runtime) && "remote-t3-request-invalid".equals(route.reason), "a request cannot redirect the endpoint");
        route = remoteRouter.apply(remoteConfig, noEnv).route(task().put("remoteT3", new JSONObject().put("model", "bad model")));
        check("codex".equals(route.runtime) && "remote-t3-invalid".equals(route.reason), "invalid override value: codex");
        route = remoteRouter.apply(remoteConfig, noEnv).route(task().put("remoteT3", "x"));
        check("codex".equals(route.runtime) && "remote-t3-request-invalid".equals(route.reason), "non-object override: codex");

        JSONObject plaintext = new JSONObject(remoteConfig.toString());
        plaintext.getJSONObject("remoteT3").put("token", "sk-should-never-be-here");
        route = remoteRouter.apply(plaintext, noEnv).route(task());
        check("codex".equals(route.runtime) && "remote-t3-invalid".equals(route.reason), "hand-edited plain-text token: refused, codex");
        JSONObject insecure = new JSONObject(remoteConfig.toString());
        insecure.getJSONObject("remoteT3").put("endpoint", "http://t3.example.com");
        check("remote-t3-invalid".equals(remoteRouter.apply(insecure, noEnv).route(task()).reason), "plain http to a public host: refused");
        JSONObject noToken = new JSONObject(remoteConfig.toString());
        noToken.getJSONObject("remoteT3").remove("tokenEnv");
        check("remote-t3-unconfigured".equals(remoteRouter.apply(noToken, noEnv).route(task()).reason), "no token reference: unconfigured");

        for (String[] shape : new String[][]{{"conversationId", "conversation"}, {"kind", "control"}}) {
            JSONObject t = shape[0].equals("kind") ? task().put("kind", "agent_control") : task().put(shape[0], "thread-1");
            route = remoteRouter.apply(remoteConfig, noEnv).route(t);
            check("codex".equals(route.runtime) && ("remote-t3-ineligible:" + shape[1]).equals(route.reason), "remote: " + shape[1] + " stays on Codex");
        }
        route = remoteRouter.apply(remoteConfig, noEnv).route(task().put("panelUpdate", new JSONObject()));
        check("remote-t3-ineligible:panel-update".equals(route.reason), "remote: panel updates stay on Codex");
        route = router(remoteConfig, noEnv).route(task());
        check("codex".equals(route.runtime) && "remote-t3-unavailable".equals(route.reason), "router without a remote factory: codex");
        // The ACP lane is untouched by the remote block.
        route = remoteRouter.apply(acpConfig(), noEnv).route(task());
        check("acp".equals(route.runtime) && route.remoteConfig == null, "acp default unaffected by the remote lane");

        JSONObject json = router(acpConfig(), noEnv).route(task()).json();
        check(json.getString("runtime").equals("acp") && json.getString("requested").equals("acp") && json.has("reason"),
                "route diagnostics are serialisable");
        System.out.println("RuntimeRouterTest passed " + checks + " checks");
    }
}
