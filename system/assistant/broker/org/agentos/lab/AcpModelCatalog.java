package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The model catalog while chat turns run on the ACP runtime, so the model picker and every send
 * ({@code ComposerOptions.loadCatalog()} pages native-RPC {@code model/list}) never cold-start the
 * Codex app server just to list models the ACP agent would not use.
 *
 * <p>Source of truth is the agent itself: {@code agy acp} answers {@code session/new} with
 * {@code configOptions} (select {@code model}: Gemini 4 Neon, Claude Opus 5.5, ...; select
 * {@code thought_level}: low/medium/high/xhigh). {@link AcpRuntime} saves that list after every
 * session it opens ({@link #remember}); no extra agent process is ever started to list models.
 * Until the first ACP turn has run (or after the binary changes) {@link #BUILTIN} answers.
 *
 * <p>The answer has Codex's {@code model/list} shape ({@code model}, {@code displayName},
 * {@code isDefault}, {@code supportedReasoningEfforts}, {@code defaultReasoningEffort}), so the
 * picker, composer options and submission resolve it unchanged; every entry carries
 * {@code runtime: "acp"}. The chosen {@code model}/{@code effort} reach {@link AcpRuntime} in the
 * task request and are applied with {@code session/set_config_option}.
 */
final class AcpModelCatalog {
    static final String MODEL = "model", EFFORT = "thought_level";
    /**
     * Taku's default thinking level on ACP. agy reports {@code thought_level} {@code currentValue: "high"}
     * on every session/new (its own default); mirroring that as the picker's default made every send
     * carry {@code effort: "high"}. The picker defaults to this level when the agent offers it, and a turn
     * whose request names no effort applies it ({@link #effortFor}); an explicit choice still wins.
     */
    static final String DEFAULT_EFFORT = "medium";
    static final int MAX_BYTES = 262144;

    /** Shown until the agent has reported its own list: agy's default model and thinking levels. */
    static final JSONArray BUILTIN = builtin();

    private AcpModelCatalog() {}

    private static JSONArray builtin() {
        try {
            JSONArray efforts = new JSONArray();
            for (String level : new String[] {"low", "medium", "high", "xhigh"})
                efforts.put(new JSONObject().put("value", level).put("name", level));
            return new JSONArray()
                    .put(new JSONObject().put("id", MODEL).put("category", "model").put("type", "select")
                            .put("currentValue", "gemini-4-neon")
                            .put("options", new JSONArray().put(new JSONObject().put("value", "gemini-4-neon").put("name", "Gemini 4 Neon"))))
                    .put(new JSONObject().put("id", EFFORT).put("category", "thought_level").put("type", "select")
                            .put("currentValue", DEFAULT_EFFORT).put("options", efforts));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The native-RPC answer for {@code method} while new chat turns route to ACP, or null when the
     * call must keep its Codex path (any other method, or Codex / remote T3 selected).
     */
    static JSONObject nativeAnswer(String method, RuntimeRouter.Route route, File cache) throws Exception {
        if (!"model/list".equals(method) || route == null || !RuntimeRouter.ACP.equals(route.runtime)) return null;
        String binary = AcpRuntime.Options.fromJson(route.acpConfig).binary;
        JSONArray stored = stored(cache, binary);
        return modelList(stored != null ? stored : BUILTIN).put("source", stored != null ? "agent" : "builtin");
    }

    /** Codex {@code model/list} shape for ACP {@code configOptions}; one page, no cursor. */
    static JSONObject modelList(JSONArray configOptions) throws Exception {
        JSONObject model = select(configOptions, MODEL);
        if (model == null) model = select(BUILTIN, MODEL);
        JSONObject effort = select(configOptions, EFFORT);
        JSONArray efforts = new JSONArray();
        String defaultEffort = null;
        if (effort != null) {
            JSONArray options = effort.optJSONArray("options");
            for (int i = 0; options != null && i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                String value = option == null ? "" : option.optString("value", "");
                if (value.isEmpty()) continue;
                efforts.put(new JSONObject().put("reasoningEffort", value).put("description", option.optString("name", value)));
            }
            Set<String> offered = values(configOptions, EFFORT);
            String current = effort.optString("currentValue", "");
            if (offered.contains(DEFAULT_EFFORT)) defaultEffort = DEFAULT_EFFORT;
            else if (offered.contains(current)) defaultEffort = current;
        }
        JSONArray data = new JSONArray();
        JSONArray options = model.optJSONArray("options");
        String current = model.optString("currentValue", "");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; options != null && i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            String value = option == null ? "" : option.optString("value", "");
            if (value.isEmpty() || !seen.add(value)) continue;
            JSONObject entry = new JSONObject().put("id", value).put("model", value)
                    .put("displayName", option.optString("name", value))
                    .put("description", option.optString("description", ""))
                    .put("hidden", false)
                    .put("isDefault", value.equals(current))
                    .put("supportedReasoningEfforts", new JSONArray(efforts.toString()))
                    .put("runtime", RuntimeRouter.ACP);
            if (defaultEffort != null) entry.put("defaultReasoningEffort", defaultEffort);
            data.put(entry);
        }
        return new JSONObject().put("data", data).put("nextCursor", JSONObject.NULL).put("runtime", RuntimeRouter.ACP);
    }

    /** The select option {@code id} (by id, else by category), or null. */
    static JSONObject select(JSONArray configOptions, String id) {
        if (configOptions == null) return null;
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < configOptions.length(); i++) {
                JSONObject option = configOptions.optJSONObject(i);
                if (option == null || option.optJSONArray("options") == null) continue;
                if (id.equals(option.optString(pass == 0 ? "id" : "category", ""))) return option;
            }
        return null;
    }

    /** The values select {@code id} accepts (empty when the agent does not offer it). */
    static Set<String> values(JSONArray configOptions, String id) {
        Set<String> out = new LinkedHashSet<>();
        JSONObject option = select(configOptions, id);
        JSONArray options = option == null ? null : option.optJSONArray("options");
        for (int i = 0; options != null && i < options.length(); i++) {
            JSONObject item = options.optJSONObject(i);
            String value = item == null ? "" : item.optString("value", "");
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    /** The thinking level a turn applies: the request's own choice, else {@link #DEFAULT_EFFORT}. */
    static String effortFor(String requested) {
        return requested == null || requested.isEmpty() ? DEFAULT_EFFORT : requested;
    }

    static String current(JSONArray configOptions, String id) {
        JSONObject option = select(configOptions, id);
        return option == null ? "" : option.optString("currentValue", "");
    }

    /** Save the agent's configOptions for {@code binary} (best effort; needs a model select). */
    static void remember(File cache, String binary, JSONArray configOptions) {
        if (cache == null || configOptions == null || values(configOptions, MODEL).isEmpty()) return;
        try {
            JSONObject snapshot = new JSONObject().put("binary", binary == null ? "" : binary)
                    .put("savedAt", System.currentTimeMillis()).put("configOptions", configOptions);
            byte[] bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return;
            File parent = cache.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(parent, cache.getName() + ".tmp");
            Files.write(tmp.toPath(), bytes);
            Files.move(tmp.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unavailable) {
            // Only the picker's list goes stale; the built-in entry still answers.
        }
    }

    /** The saved configOptions when they were reported by {@code binary}, else null. */
    static JSONArray stored(File cache, String binary) {
        if (cache == null || !cache.isFile() || cache.length() > MAX_BYTES) return null;
        try {
            JSONObject snapshot = new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            if (!snapshot.optString("binary", "").equals(binary == null ? "" : binary)) return null;
            JSONArray options = snapshot.optJSONArray("configOptions");
            return options != null && !values(options, MODEL).isEmpty() ? options : null;
        } catch (Exception unusable) {
            return null;
        }
    }
}
