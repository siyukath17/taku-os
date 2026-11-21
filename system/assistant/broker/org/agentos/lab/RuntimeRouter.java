package org.agentos.lab;

import org.json.JSONObject;

import java.util.Map;

/**
 * Chooses the {@link TaskRuntime} for one task. Codex is the default and the fallback for every
 * doubt: no config, unreadable config, unknown runtime name, an ACP or remote T3 request for a task
 * shape those lanes cannot serve yet, ACP without a configured binary, or remote T3 without a valid
 * endpoint and token reference. Only a positively valid ACP selection for a background or chat task,
 * or a positively valid remote T3 selection for a background task, leaves the Codex path.
 *
 * <p>Lane split: ACP serves background tasks and chat turns ({@code conversationId}); remote T3 serves
 * only background heavy work, so a chat turn that resolves to {@code remote_t3} stays on Codex
 * ({@code remote-t3-ineligible:conversation}).
 *
 * <p>Remote T3 reads {@code runtime.json "remoteT3"}; a request may override only
 * {@code remoteT3.projectId|environmentId|model} (never the endpoint or the token reference).
 *
 * <p>Precedence: {@code request.runtime} &gt; environment {@code TAKU_TASK_RUNTIME} &gt;
 * {@code runtime.json "default"} &gt; {@code codex}. Device wiring lives in {@link TaskBroker#router()}.
 */
final class RuntimeRouter {
    static final String CODEX = "codex", ACP = "acp", REMOTE_T3 = RemoteT3Runtime.RUNTIME;
    /** runtime.json and request key for the remote T3 lane. */
    static final String REMOTE_T3_KEY = "remoteT3";
    static final String ENV = "TAKU_TASK_RUNTIME";

    interface ConfigSource {
        /** The runtime.json object, or null when absent. Throwing means present but unusable. */
        JSONObject load() throws Exception;
    }

    interface AcpFactory {
        TaskRuntime create(JSONObject acpConfig) throws Exception;
    }

    interface RemoteT3Factory {
        /** {@code remoteConfig} is validated and already carries the request's allowed overrides. */
        TaskRuntime create(JSONObject remoteConfig) throws Exception;
    }

    static final class Route {
        final String runtime, requested, reason;
        final JSONObject acpConfig, remoteConfig;

        Route(String runtime, String requested, String reason, JSONObject acpConfig) {
            this(runtime, requested, reason, acpConfig, null);
        }

        Route(String runtime, String requested, String reason, JSONObject acpConfig, JSONObject remoteConfig) {
            this.runtime = runtime;
            this.requested = requested;
            this.reason = reason;
            this.acpConfig = acpConfig;
            this.remoteConfig = remoteConfig;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("runtime", runtime).put("requested", requested).put("reason", reason);
        }
    }

    private final TaskRuntime codex;
    private final AcpFactory acp;
    private final RemoteT3Factory remote;
    private final ConfigSource config;
    private final Map<String, String> env;

    /** Without a remote factory: a remote_t3 request falls back to Codex ({@code remote-t3-unavailable}). */
    RuntimeRouter(TaskRuntime codex, AcpFactory acp, ConfigSource config, Map<String, String> env) {
        this(codex, acp, null, config, env);
    }

    RuntimeRouter(TaskRuntime codex, AcpFactory acp, RemoteT3Factory remote, ConfigSource config, Map<String, String> env) {
        this.codex = codex;
        this.acp = acp;
        this.remote = remote;
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
        if (REMOTE_T3.equals(requested)) return remoteRoute(request, loaded, requested, source);
        if (!ACP.equals(requested)) return new Route(CODEX, requested, "unknown-runtime", null);
        // Conversation reads and panel card replies stay on Codex. Chat turns (conversationId) run
        // on ACP: AcpConversationStore keeps their session and turns, and the conversation's display
        // projection merges them, so thread/read and the chat page see them like native turns.
        String ineligible = acpIneligible(request);
        if (ineligible != null) return new Route(CODEX, requested, "acp-ineligible:" + ineligible, null);
        JSONObject acpConfig = loaded.optJSONObject(ACP);
        if (acpConfig == null || acpConfig.optString("binary", "").trim().isEmpty())
            return new Route(CODEX, requested, "acp-unconfigured", null);
        return new Route(ACP, requested, "selected-by-" + source, acpConfig);
    }

    /**
     * The route a new conversation's chat turns will take (request, env and runtime.json as for
     * {@link #route}, without a per-request override). {@code agent_control thread/start} asks this
     * so a conversation the broker starts itself on ACP (no Codex thread) also gets its first and
     * later turns on ACP, and a Codex selection keeps thread/start on the Codex app server.
     */
    Route newConversation() throws Exception {
        return route(new JSONObject().put("conversationId", ""));
    }

    /** Task shapes ACP cannot serve: agent controls and panel updates. Chat turns are served. */
    private static String acpIneligible(JSONObject request) {
        if ("agent_control".equals(request.optString("kind"))) return "control";
        if (request.optJSONObject("panelUpdate") != null) return "panel-update";
        return null;
    }

    /** Task shapes remote T3 cannot serve: everything ACP cannot, plus chat conversations. */
    private static String remoteIneligible(JSONObject request) {
        String acp = acpIneligible(request);
        if (acp != null) return acp;
        if (request.has("conversationId")) return "conversation";
        return null;
    }

    private Route remoteRoute(JSONObject request, JSONObject loaded, String requested, String source) {
        String ineligible = remoteIneligible(request);
        if (ineligible != null) return new Route(CODEX, requested, "remote-t3-ineligible:" + ineligible, null);
        if (remote == null) return new Route(CODEX, requested, "remote-t3-unavailable", null);
        JSONObject stored = loaded.optJSONObject(REMOTE_T3_KEY);
        if (stored == null) return new Route(CODEX, requested, "remote-t3-unconfigured", null);
        JSONObject merged;
        try {
            merged = RemoteT3Runtime.Options.merge(stored, request.opt(REMOTE_T3_KEY));
        } catch (Exception badOverride) {
            return new Route(CODEX, requested, "remote-t3-request-invalid", null);
        }
        try {
            if (!RemoteT3Runtime.Options.fromJson(merged).configured())
                return new Route(CODEX, requested, "remote-t3-unconfigured", null);
        } catch (Exception invalid) {
            return new Route(CODEX, requested, "remote-t3-invalid", null);
        }
        return new Route(REMOTE_T3, requested, "selected-by-" + source, null, merged);
    }

    TaskRuntime select(Route route) throws Exception {
        if (REMOTE_T3.equals(route.runtime)) return remote.create(route.remoteConfig);
        return ACP.equals(route.runtime) ? acp.create(route.acpConfig) : codex;
    }
}
