package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The model picker while chat turns run on ACP: native-RPC {@code model/list} is answered from the
 * ACP agent's own catalog without touching the Codex runtime; with Codex (the default) or remote T3
 * selected it keeps the Codex path. The composer's model / effort reach the agent through
 * {@code session/set_config_option}, and session/new refreshes the saved catalog.
 */
public final class AcpModelCatalogTest {
    private static int checks;
    private static final String BINARY = "/data/local/agentos/acp/bin/agy";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static File temp(String name) throws IOException {
        File dir = Files.createTempDirectory("acp-models-" + name).toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static JSONObject json(File dir, String name) throws IOException {
        return new JSONObject(new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8));
    }

    private static JSONObject option(String value, String name) {
        return new JSONObject().put("value", value).put("name", name);
    }

    /** configOptions as agy 1.5.14 answers session/new on the Pixel 9 (trimmed). */
    static JSONArray agyConfigOptions() {
        return new JSONArray()
                .put(new JSONObject().put("id", "model").put("name", "Model").put("category", "model").put("type", "select")
                        .put("currentValue", "gemini-4-neon")
                        .put("options", new JSONArray().put(option("gemini-4-neon", "Gemini 4 Neon"))
                                .put(option("gemini-3.8-flash", "Gemini 3.8 Flash"))
                                .put(option("claude-opus-5-5", "Claude Opus 5.5"))))
                .put(new JSONObject().put("id", "thought_level").put("name", "Thinking Level").put("category", "thought_level")
                        .put("type", "select").put("currentValue", "high")
                        .put("options", new JSONArray().put(option("low", "Low")).put(option("medium", "Medium"))
                                .put(option("high", "High")).put(option("xhigh", "Extra"))))
                .put(new JSONObject().put("id", "mode").put("category", "mode").put("type", "select").put("currentValue", "default")
                        .put("options", new JSONArray().put(option("default", "Default"))));
    }

    private static JSONObject acpConfig() {
        return new JSONObject().put("default", "acp").put("acp", new JSONObject().put("binary", BINARY));
    }

    /** A router whose Codex runtime counts every use: listing models must never reach it. */
    private static final AtomicInteger codexUses = new AtomicInteger();
    private static final TaskRuntime COUNTING_CODEX = new TaskRuntime() {
        @Override public String name() { codexUses.incrementAndGet(); return "codex"; }
        @Override public void run(File directory, JSONObject request) { codexUses.incrementAndGet(); }
    };

    private static RuntimeRouter router(JSONObject config) {
        return new RuntimeRouter(COUNTING_CODEX, RuntimeRouterTest.ACP, () -> config, new HashMap<>());
    }

    static void codexShape() throws Exception {
        JSONObject list = AcpModelCatalog.modelList(agyConfigOptions());
        JSONArray data = list.getJSONArray("data");
        check(data.length() == 3 && list.isNull("nextCursor") && "acp".equals(list.getString("runtime")),
                "shape: one page with every agent model, got " + list);
        JSONObject neon = data.getJSONObject(0);
        check("gemini-4-neon".equals(neon.getString("model")) && "Gemini 4 Neon".equals(neon.getString("displayName"))
                && neon.getBoolean("isDefault") && "acp".equals(neon.getString("runtime")),
                "shape: the agent's current model is the default, with its display name, got " + neon);
        check(!data.getJSONObject(1).getBoolean("isDefault") && !data.getJSONObject(2).getBoolean("isDefault"), "shape: one default");
        JSONArray efforts = neon.getJSONArray("supportedReasoningEfforts");
        check(efforts.length() == 4 && "low".equals(efforts.getJSONObject(0).getString("reasoningEffort"))
                && "xhigh".equals(efforts.getJSONObject(3).getString("reasoningEffort"))
                && "medium".equals(neon.getString("defaultReasoningEffort")),
                "shape: thought_level becomes the reasoning efforts (Codex keys); Taku defaults to medium although agy reports high, got " + neon);
        JSONArray noMedium = agyConfigOptions();
        noMedium.getJSONObject(1).put("options", new JSONArray().put(option("low", "Low")).put(option("high", "High")));
        check("high".equals(AcpModelCatalog.modelList(noMedium).getJSONArray("data").getJSONObject(0).getString("defaultReasoningEffort")),
                "shape: an agent without medium keeps its own current level as the default");
        check("medium".equals(AcpModelCatalog.select(AcpModelCatalog.BUILTIN, "thought_level").getString("currentValue")),
                "shape: the built-in catalog (before the first turn) also defaults to medium");
        JSONObject noEffort = AcpModelCatalog.modelList(new JSONArray().put(agyConfigOptions().getJSONObject(0)))
                .getJSONArray("data").getJSONObject(0);
        check(noEffort.getJSONArray("supportedReasoningEfforts").length() == 0 && !noEffort.has("defaultReasoningEffort"),
                "shape: no thought_level, no efforts");
    }

    static void nativeAnswerOnAcpWithoutCodex() throws Exception {
        File dir = temp("native");
        File cache = new File(dir, "acp-models.json");
        codexUses.set(0);
        RuntimeRouter acp = router(acpConfig());

        JSONObject builtin = AcpModelCatalog.nativeAnswer("model/list", acp.newConversation(), cache);
        check(builtin != null && "builtin".equals(builtin.getString("source")), "no cache: the built-in ACP catalog answers, got " + builtin);
        JSONObject only = builtin.getJSONArray("data").getJSONObject(0);
        check(builtin.getJSONArray("data").length() == 1 && "gemini-4-neon".equals(only.getString("model"))
                && "Gemini 4 Neon".equals(only.getString("displayName")) && only.getBoolean("isDefault"),
                "no cache: Gemini 4 Neon is the default, got " + only);

        AcpModelCatalog.remember(cache, BINARY, agyConfigOptions());
        JSONObject agent = AcpModelCatalog.nativeAnswer("model/list", acp.newConversation(), cache);
        check("agent".equals(agent.getString("source")) && agent.getJSONArray("data").length() == 3,
                "cache: the agent's saved catalog answers, got " + agent);

        File other = new File(dir, "other.json");
        AcpModelCatalog.remember(other, "/data/local/agentos/acp/bin/agy-old", agyConfigOptions());
        check("builtin".equals(AcpModelCatalog.nativeAnswer("model/list", acp.newConversation(), other).getString("source")),
                "cache: a catalog saved by another binary is not used");
        Files.write(other.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        check("builtin".equals(AcpModelCatalog.nativeAnswer("model/list", acp.newConversation(), other).getString("source")),
                "cache: an unreadable file falls back to the built-in catalog");
        AcpModelCatalog.remember(other, BINARY, new JSONArray());
        check(AcpModelCatalog.stored(other, BINARY) == null, "cache: configOptions without a model select are not saved");

        check(AcpModelCatalog.nativeAnswer("thread/list", acp.newConversation(), cache) == null,
                "gate: only model/list is answered here");
        check(codexUses.get() == 0, "gate: listing ACP models never touches the Codex runtime");
    }

    static void codexDefaultKeepsCodexPath() throws Exception {
        File cache = new File(temp("codex"), "acp-models.json");
        AcpModelCatalog.remember(cache, BINARY, agyConfigOptions());
        check(AcpModelCatalog.nativeAnswer("model/list", router(null).newConversation(), cache) == null,
                "codex default (no runtime.json): model/list keeps the Codex path");
        check(AcpModelCatalog.nativeAnswer("model/list", router(new JSONObject().put("default", "codex")
                .put("acp", new JSONObject().put("binary", BINARY))).newConversation(), cache) == null,
                "default codex with an ACP binary configured: Codex path");
        check(AcpModelCatalog.nativeAnswer("model/list", router(new JSONObject().put("default", "acp")).newConversation(), cache) == null,
                "acp selected without a binary routes to Codex: Codex path");
        check(AcpModelCatalog.nativeAnswer("model/list", null, cache) == null, "no route: Codex path");
    }

    private static AcpRuntime.Options options(File cache) throws Exception {
        AcpRuntime.Options o = AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", BINARY).put("cwd", "")
                .put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000).put("cancelGraceMillis", 2000));
        o.modelCatalog = cache;
        return o;
    }

    private static JSONObject request(String id, String model, String effort) {
        JSONObject r = new JSONObject().put("id", id).put("prompt", "hello").put("permissionMode", "full");
        if (model != null) r.put("model", model);
        if (effort != null) r.put("effort", effort);
        return r;
    }

    private static FakeAcpAgent agent() {
        FakeAcpAgent agent = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        agent.configOptions = agyConfigOptions();
        return agent;
    }

    static void selectionReachesAgent() throws Exception {
        File cacheDir = temp("cache");
        File cache = new File(cacheDir, "acp-models.json");
        File dir = temp("select");
        FakeAcpAgent agent = agent();
        new AcpRuntime(options(cache), agent, null, null).run(dir, request("301", "claude-opus-5-5", "low"));
        List<JSONObject> sets = agent.methods("session/set_config_option");
        check(sets.size() == 2, "select: model and effort are both applied, got " + sets);
        JSONObject model = sets.get(0).getJSONObject("params"), effort = sets.get(1).getJSONObject("params");
        check("s-1".equals(model.getString("sessionId")) && "model".equals(model.getString("configId"))
                && "claude-opus-5-5".equals(model.getString("value")), "select: model set on the session, got " + model);
        check("thought_level".equals(effort.getString("configId")) && "low".equals(effort.getString("value")),
                "select: effort maps to thought_level, got " + effort);
        int setIndex = agent.received.indexOf(sets.get(1)), promptIndex = agent.received.indexOf(agent.methods("session/prompt").get(0));
        check(setIndex >= 0 && setIndex < promptIndex, "select: applied before the prompt");
        JSONObject info = json(dir, "runtime-info.json");
        check("claude-opus-5-5".equals(info.getString("model")) && "low".equals(info.getString("effort")) && !info.has("configRefusals"),
                "select: runtime-info records the model the turn ran on, got " + info);
        check("completed".equals(json(dir, "state.json").getString("state")), "select: the turn completes");
        JSONArray saved = AcpModelCatalog.stored(cache, BINARY);
        check(saved != null && AcpModelCatalog.values(saved, "model").contains("claude-opus-5-5"),
                "session/new saves the agent's catalog for the picker");

        File same = temp("same");
        FakeAcpAgent unchanged = agent();
        new AcpRuntime(options(cache), unchanged, null, null).run(same, request("302", "gemini-4-neon", "high"));
        check(unchanged.methods("session/set_config_option").isEmpty(), "select: current values are not re-sent (an explicit high stays high)");

        File unset = temp("unset");
        FakeAcpAgent defaults = agent();
        new AcpRuntime(options(cache), defaults, null, null).run(unset, request("306", null, null));
        List<JSONObject> defaultSets = defaults.methods("session/set_config_option");
        check(defaultSets.size() == 1 && "thought_level".equals(defaultSets.get(0).getJSONObject("params").getString("configId"))
                && "medium".equals(defaultSets.get(0).getJSONObject("params").getString("value")),
                "select: a request without an effort runs on medium, not agy's high, got " + defaultSets);
        check("medium".equals(json(unset, "runtime-info.json").getString("effort")), "select: runtime-info records medium");

        File stale = temp("stale");
        FakeAcpAgent codexModel = agent();
        new AcpRuntime(options(cache), codexModel, null, null).run(stale, request("303", "gpt-5.5", "minimal"));
        check(codexModel.methods("session/set_config_option").isEmpty(), "select: ids the agent does not offer are not sent");
        JSONObject staleInfo = json(stale, "runtime-info.json");
        check("gemini-4-neon".equals(staleInfo.getString("model")) && "completed".equals(json(stale, "state.json").getString("state")),
                "select: a Codex model id runs on the agent's default, got " + staleInfo);

        File refused = temp("refused");
        FakeAcpAgent refusing = agent();
        refusing.refusedConfigValues.add("claude-opus-5-5");
        new AcpRuntime(options(cache), refusing, null, null).run(refused, request("304", "claude-opus-5-5", null));
        JSONObject refusedInfo = json(refused, "runtime-info.json");
        check("completed".equals(json(refused, "state.json").getString("state")) && "gemini-4-neon".equals(refusedInfo.getString("model"))
                && refusedInfo.getJSONArray("configRefusals").getJSONObject(0).getInt("code") == -32602,
                "select: a refused model keeps the agent's choice and the turn still runs, got " + refusedInfo);
        check(!new File(refused, "diagnostic.json").exists(), "select: a refusal is not a failure diagnostic");

        File plain = temp("plain");
        FakeAcpAgent noOptions = new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL);
        new AcpRuntime(options(null), noOptions, null, null).run(plain, request("305", "claude-opus-5-5", "low"));
        check(noOptions.methods("session/set_config_option").isEmpty() && !json(plain, "runtime-info.json").has("model")
                && "completed".equals(json(plain, "state.json").getString("state")),
                "select: an agent without configOptions is left alone");
    }

    static void conversationListWithoutCodex() throws Exception {
        RuntimeRouter.Route acp = router(acpConfig()).newConversation(), codex = router(null).newConversation();
        check(AcpCodexGate.listsWithoutCodex("thread/list", acp, false), "list: ACP selected, Codex not running: no Codex start");
        check(!AcpCodexGate.listsWithoutCodex("thread/list", acp, true), "list: a running Codex app server is still asked");
        check(!AcpCodexGate.listsWithoutCodex("thread/list", codex, false), "list: Codex default keeps the Codex path");
        check(!AcpCodexGate.listsWithoutCodex("thread/read", acp, false), "list: only thread/list");
        check(!AcpCodexGate.listsWithoutCodex("thread/list", null, false), "list: no route keeps the Codex path");
    }

    public static void main(String[] args) throws Exception {
        conversationListWithoutCodex();
        codexShape();
        nativeAnswerOnAcpWithoutCodex();
        codexDefaultKeepsCodexPath();
        selectionReachesAgent();
        System.out.println("AcpModelCatalogTest: " + checks + " checks passed");
    }
}
