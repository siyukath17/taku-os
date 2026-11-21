package org.agentos.lab;

import org.json.JSONObject;

import java.util.Map;

/**
 * Chooses the {@link TaskRuntime} for one task. Codex is the default and the fallback for every
 * doubt: no config, unreadable config, unknown runtime name, an ACP request for a task shape ACP
 * cannot serve yet, or ACP without a configured binary. Only a positively valid ACP selection for a
 * background task leaves the Codex path.
 *
 * <p>Precedence: {@code request.runtime} &gt; environment {@code TAKU_TASK_RUNTIME} &gt;
 * {@code runtime.json "default"} &gt; {@code codex}. Device wiring lives in {@link TaskBroker#router()}.
 */
final class RuntimeRouter {
    static final String CODEX = "codex", ACP = "acp";
    static final String ENV = "TAKU_TASK_RUNTIME";

    interface ConfigSource {
        /** The runtime.json object, or null when absent. Throwing means present but unusable. */
        JSONObject load() throws Exception;
    }

    interface AcpFactory {
        TaskRuntime create(JSONObject acpConfig) throws Exception;
    }

    static final class Route {
        final String runtime, requested, reason;
        final JSONObject acpConfig;

        Route(String runtime, String requested, String reason, JSONObject acpConfig) {
            this.runtime = runtime;
            this.requested = requested;
            this.reason = reason;
            this.acpConfig = acpConfig;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("runtime", runtime).put("requested", requested).put("reason", reason);
        }
    }

    private final TaskRuntime codex;
    private final AcpFactory acp;
    private final ConfigSource config;
    private final Map<String, String> env;

    RuntimeRouter(TaskRuntime codex, AcpFactory acp, ConfigSource config, Map<String, String> env) {
        this.codex = codex;
        this.acp = acp;
        this.config = config;
        this.env = env;
    }

    Route route(JSONObject request) {
        JSONObject loaded;
        try {
            loaded = config.load();
        } catch (Exception unusable) {
            return new Route(CODEX, "", "config-invalid", null);
        }
        if (loaded == null) loaded = new JSONObject();
        String requested = request.optString("runtime", "").trim();
        String source = "request";
        if (requested.isEmpty()) {
            requested = env == null ? "" : String.valueOf(env.getOrDefault(ENV, "")).trim();
            source = "env";
        }
        if (requested.isEmpty()) {
            requested = loaded.optString("default", "").trim();
            source = "config";
        }
        if (requested.isEmpty()) return new Route(CODEX, CODEX, "default", null);
        if (CODEX.equals(requested)) return new Route(CODEX, requested, "selected-by-" + source, null);
        if (!ACP.equals(requested)) return new Route(CODEX, requested, "unknown-runtime", null);
        // ACP sessions cannot resume Codex threads or produce panel card replies yet.
        if ("agent_control".equals(request.optString("kind")))
            return new Route(CODEX, requested, "acp-ineligible:control", null);
        if (request.has("conversationId"))
            return new Route(CODEX, requested, "acp-ineligible:conversation", null);
        if (request.optJSONObject("panelUpdate") != null)
            return new Route(CODEX, requested, "acp-ineligible:panel-update", null);
        JSONObject acpConfig = loaded.optJSONObject(ACP);
        if (acpConfig == null || acpConfig.optString("binary", "").trim().isEmpty())
            return new Route(CODEX, requested, "acp-unconfigured", null);
        return new Route(ACP, requested, "selected-by-" + source, acpConfig);
    }

    TaskRuntime select(Route route) throws Exception {
        return ACP.equals(route.runtime) ? acp.create(route.acpConfig) : codex;
    }
}
