package org.agentos.lab;

import com.android.systemui.taku.island.IslandPayload;
import org.agentos.assistant.AcpIslandProjection;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * progress.json → taku.island.v1: every projection must pass SystemUI's own IslandPayload parser,
 * and tasks the ACP runtime did not run must keep the plain notification (null projection).
 */
public final class AcpIslandProjectionTest {
    private static int checks;
    static final String PACKAGE = "org.agentos.assistant";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static JSONObject valid(AcpIslandProjection.Projection projection, String label) {
        check(projection != null, label + ": projected");
        IslandPayload.Result result = IslandPayload.parse(projection.payload.toString(), PACKAGE, false);
        check(result.isValid(), label + ": SystemUI parser accepts the payload (" + result.error + ") " + projection.payload);
        return projection.payload;
    }

    static JSONObject state(String id, String state, String message) {
        return new JSONObject().put("id", id).put("state", state).put("prompt", "Turn on Wi-Fi").put("message", message);
    }

    static JSONObject progress(String id, String phase, String summary, String tool, String plan, int calls, int done) {
        return new JSONObject().put("taskId", id).put("runtime", "acp").put("phase", phase).put("summary", summary)
                .put("tool", tool).put("plan", plan).put("toolCalls", calls).put("toolCallsCompleted", done);
    }

    static void write(File dir, String name, JSONObject value) throws Exception {
        Files.writeString(new File(dir, name).toPath(), value.toString());
    }

    public static void main(String[] args) throws Exception {
        // ---- the pure mapping ------------------------------------------------------------------
        JSONObject p = valid(AcpIslandProjection.project(state("7", "running", "x"),
                progress("7", "tool", "Working: Open Settings", "Open Settings", "1/4", 3, 1), null), "tool phase");
        check("Using Open Settings".equals(p.getJSONObject("expanded").getString("title")), "tool phase names the tool");
        JSONObject labelled = valid(AcpIslandProjection.project(state("7", "running", "x"),
                progress("7", "tool", "Working: Read device info", "Read device info", "", 1, 0).put("toolKey", "device_info"), null), "labelled tool");
        check("Read device info".equals(labelled.getJSONObject("expanded").getString("title")), "an ACP tool label is the title itself");
        check("Tools 1/3".equals(p.getJSONObject("bigIsland").getJSONObject("trailing").getString("text")), "tool counter in the compact island");
        check(p.getJSONObject("expanded").getJSONObject("progress").getDouble("value") == 0.25, "plan drives determinate progress");
        check("working".equals(p.getJSONObject("bigIsland").getJSONObject("leading").getString("animated")) && p.has("staleAfterS"),
                "running: animated and stale-guarded");
        check("open".equals(p.getJSONObject("expanded").getString("primaryAction")), "running: open is primary");

        p = valid(AcpIslandProjection.project(state("7", "running", "Starting the on-device agent…"), null, null), "no progress yet");
        check("Starting".equals(p.getJSONObject("bigIsland").getJSONObject("trailing").getString("text"))
                && !p.getJSONObject("expanded").getJSONObject("progress").has("value"), "starting is indeterminate");
        for (String phase : new String[]{"working", "thinking", "replying", "stopping", "unexpected"})
            valid(AcpIslandProjection.project(state("7", "running", "x"), progress("7", phase, "s", "", "", 0, 0), null), "phase " + phase);
        // Waiting for asynchronous subagents: their own counter and determinate progress.
        p = valid(AcpIslandProjection.project(state("7", "running", "x"),
                progress("7", "subagents", "Waiting for sub-tasks (1/2 done)…", "Dispatch a sub-task", "", 2, 2)
                        .put("subagents", 2).put("subagentsDone", 1), null), "subagents phase");
        check("Waiting for sub-tasks".equals(p.getJSONObject("expanded").getString("title"))
                && "Sub-tasks 1/2".equals(p.getJSONObject("bigIsland").getJSONObject("trailing").getString("text"))
                && p.getJSONObject("expanded").getJSONObject("progress").getDouble("value") == 0.5
                && p.getJSONObject("expanded").getString("body").contains("1/2"), "subagents phase: counter and progress " + p);
        p = valid(AcpIslandProjection.project(state("7", "running", "x"),
                progress("7", "subagents", "s", "", "", 2, 2).put("subagents", 2).put("subagentsDone", 2), null), "subagents done");
        check("Sub-tasks finished".equals(p.getJSONObject("expanded").getString("title")), "subagents phase: all reported");
        p = valid(AcpIslandProjection.project(state("7", "running", "x"), progress("7", "stopping", "Stopping.", "", "", 0, 0), null), "stopping");
        check("none".equals(p.getJSONObject("bigIsland").getJSONObject("leading").getString("animated")), "stopping is not animated");

        AcpIslandProjection.Projection login = AcpIslandProjection.project(state("7", "needs_user", "Sign in first"), null,
                new JSONObject().put("state", "needs_user").put("loginMethod", "oauth-personal"));
        p = valid(login, "needs sign-in");
        check(login.signIn && "sign_in".equals(p.getJSONObject("expanded").getString("primaryAction"))
                && p.getBoolean("alert") && p.getDouble("relevance") == 1, "needs_user + oauth-personal: sign-in action, alert, top relevance");
        AcpIslandProjection.Projection noMethod = AcpIslandProjection.project(state("7", "needs_user", "No method"), null,
                new JSONObject().put("state", "needs_user").put("loginMethod", JSONObject.NULL));
        p = valid(noMethod, "needs user without a login method");
        check(!noMethod.signIn && !p.getJSONObject("expanded").getJSONArray("actions").toList().contains("sign_in"), "no sign-in action without a method");

        for (String terminal : new String[]{"completed", "failed", "cancelled"}) {
            AcpIslandProjection.Projection done = AcpIslandProjection.project(state("7", terminal, "Wi-Fi is on."),
                    progress("7", "replying", "Wi-Fi is on.", "", "", 1, 1), null);
            p = valid(done, terminal);
            check(!done.running && !p.has("staleAfterS") && p.getJSONObject("expanded").getJSONObject("progress").getDouble("value") == 1,
                    terminal + ": settled");
        }
        check(AcpIslandProjection.project(state("7", "mystery", "x"), null, null) == null, "unknown state keeps the plain notification");

        // Bounds: long, multi-byte, control-character text still fits the 8 KiB / field limits.
        String huge = "🚀界".repeat(3000) + "\u0007";
        valid(AcpIslandProjection.project(state("7", "running", huge), progress("7", "tool", huge, huge, "x/y", 5000, 1), null), "oversized text");

        // ---- task directories: ACP only ------------------------------------------------------------
        File codexTask = Files.createTempDirectory("codex-task").toFile();
        write(codexTask, "state.json", state("8", "running", "x"));
        check(AcpIslandProjection.fromTaskDirectory(codexTask, "8") == null, "no runtime-route.json: unchanged (null)");
        write(codexTask, "runtime-route.json", new JSONObject().put("runtime", "codex").put("requested", "").put("reason", "default"));
        write(codexTask, "progress.json", progress("8", "tool", "s", "t", "", 1, 0));
        check(AcpIslandProjection.fromTaskDirectory(codexTask, "8") == null, "codex route: unchanged (null) even with a progress file");
        File torn = Files.createTempDirectory("acp-torn").toFile();
        write(torn, "runtime-route.json", new JSONObject().put("runtime", "acp"));
        Files.writeString(new File(torn, "state.json").toPath(), "{\"id\":\"9\",");
        check(AcpIslandProjection.fromTaskDirectory(torn, "9") == null, "torn state.json: plain notification");
        write(torn, "state.json", state("other", "running", "x"));
        check(AcpIslandProjection.fromTaskDirectory(torn, "9") == null, "state.json of another task is ignored");

        // ---- end to end: the real AcpRuntime writes, the projection reads at every update -----------
        File dir = Files.createTempDirectory("acp-island").toFile();
        write(dir, "runtime-route.json", new JSONObject().put("runtime", "acp").put("requested", "acp").put("reason", "selected-by-config"));
        List<String> titles = new CopyOnWriteArrayList<>();
        AcpRuntime.Options options = AcpRuntime.Options.fromJson(new JSONObject().put("binary", "/agy").put("cwd", "")
                .put("progressIntervalMillis", 0).put("requestTimeoutMillis", 5000));
        new AcpRuntime(options, new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), id -> {
            AcpIslandProjection.Projection projection = AcpIslandProjection.fromTaskDirectory(dir, id);
            titles.add(valid(projection, "live update").getJSONObject("expanded").getString("title"));
        }, null).run(dir, new JSONObject().put("id", "301").put("prompt", "Turn on Wi-Fi").put("permissionMode", "full"));
        check(titles.contains("Open Settings"), "live: the tool phase reached the island " + titles);
        check("Taku finished".equals(titles.get(titles.size() - 1)), "live: the final update is the finished card " + titles);
        check("Open Settings".equals(new JSONObject(Files.readString(new File(dir, "progress.json").toPath())).getString("tool")),
                "progress.json carries the current tool");

        File authDir = Files.createTempDirectory("acp-island-auth").toFile();
        write(authDir, "runtime-route.json", new JSONObject().put("runtime", "acp"));
        new AcpRuntime(options, new FakeAcpAgent(FakeAcpAgent.Scenario.NEEDS_AUTH), null, null)
                .run(authDir, new JSONObject().put("id", "302").put("prompt", "x"));
        AcpIslandProjection.Projection needsLogin = AcpIslandProjection.fromTaskDirectory(authDir, "302");
        valid(needsLogin, "needs_user from a real run");
        check(needsLogin.signIn, "a real auth_required run offers the sign-in action");

        // Each task gets its own notification key, so SystemUI's per-key island lifetime starts fresh.
        check(!AcpIslandProjection.notificationTag("301").equals(AcpIslandProjection.notificationTag("302")), "per-task notification tag");
        check(AcpIslandProjection.staleTag(null, "acp-task-2") == null && AcpIslandProjection.staleTag("acp-task-2", "acp-task-2") == null
                && "acp-task-1".equals(AcpIslandProjection.staleTag("acp-task-1", "acp-task-2")), "previous task slot replaced, current kept");
        System.out.println("AcpIslandProjectionTest passed " + checks + " checks");
    }
}
