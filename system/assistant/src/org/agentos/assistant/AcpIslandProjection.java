package org.agentos.assistant;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Projects an ACP task's records (runtime-route.json, state.json, the throttled progress.json and
 * acp-auth.json) into a {@code taku.island.v1} payload for the background-task notification.
 *
 * <p>Returns null for every task the ACP runtime did not run, and for unreadable records, so the
 * Codex path and the existing notification stay exactly as before. Pure Java + org.json: no
 * Android types, so host tests check the output against SystemUI's own parser.
 */
public final class AcpIslandProjection {
    public static final String PIC = "taku", OPEN = "open", SIGN_IN = "sign_in";
    static final int MAX_RECORD_BYTES = 256 * 1024;

    /** The island payload plus which actions the caller must attach (keys of taku.island.actions). */
    public static final class Projection {
        public final JSONObject payload;
        public final boolean signIn;
        public final boolean running;

        Projection(JSONObject payload, boolean signIn, boolean running) {
            this.payload = payload;
            this.signIn = signIn;
            this.running = running;
        }
    }

    private AcpIslandProjection() {}

    /** Reads the task directory; null unless runtime-route.json says this task ran on ACP. */
    public static Projection fromTaskDirectory(File directory, String taskId) {
        try {
            JSONObject route = record(new File(directory, "runtime-route.json"));
            if (route == null || !"acp".equals(route.optString("runtime"))) return null;
            JSONObject state = record(new File(directory, "state.json"));
            if (state == null || !taskId.equals(state.optString("id"))) return null;
            JSONObject progress = record(new File(directory, "progress.json"));
            if (progress != null && !taskId.equals(progress.optString("taskId"))) progress = null;
            JSONObject auth = record(new File(directory, "acp-auth.json"));
            return project(state, progress, auth);
        } catch (Exception unreadable) {
            return null; // A torn or damaged record falls back to the plain notification.
        }
    }

    /** The mapping itself. {@code progress} and {@code auth} may be null. */
    public static Projection project(JSONObject state, JSONObject progress, JSONObject auth) throws Exception {
        String status = state.optString("state");
        String phase = progress == null ? "starting" : progress.optString("phase", "starting");
        String summary = progress != null && !progress.optString("summary").isEmpty()
                ? progress.optString("summary") : state.optString("message");
        boolean running = "running".equals(status);
        boolean signIn = "needs_user".equals(status) && auth != null && !auth.isNull("loginMethod")
                && !auth.optString("loginMethod").isEmpty();
        String title, trailing, emphasis = "normal";
        double relevance = .5;
        boolean alert = false;
        switch (status) {
            case "running":
                switch (phase) {
                    case "thinking": title = "Taku is thinking"; trailing = "Thinking"; break;
                    case "tool": {
                        String tool = progress == null ? "" : progress.optString("tool");
                        title = tool.isEmpty() ? "Taku is using a tool" : "Using " + tool;
                        trailing = toolCounter(progress);
                        break;
                    }
                    case "replying": title = "Taku is writing the result"; trailing = "Writing"; break;
                    case "stopping": title = "Taku is stopping"; trailing = "Stopping"; emphasis = "muted"; break;
                    case "starting": title = "Starting the on-device agent"; trailing = "Starting"; break;
                    default: title = "Taku is working"; trailing = "Working"; break;
                }
                break;
            case "needs_user":
                title = signIn ? "Sign in to the on-device agent" : "Taku needs your attention";
                trailing = signIn ? "Sign in" : "Needs you";
                emphasis = "accent";
                relevance = 1;
                alert = true;
                break;
            case "failed":
                title = "Taku couldn’t finish";
                trailing = "Failed";
                emphasis = "accent";
                break;
            case "cancelled":
                title = "Taku stopped";
                trailing = "Stopped";
                emphasis = "muted";
                break;
            case "completed":
                title = "Taku finished";
                trailing = "Done";
                break;
            default:
                return null; // Unknown state: keep the plain notification.
        }
        JSONObject progressSlot = new JSONObject().put("type", "progress");
        Double ratio = planRatio(progress == null ? "" : progress.optString("plan"));
        if (!running) progressSlot.put("value", 1);
        else if (ratio != null) progressSlot.put("value", ratio);
        JSONArray actions = new JSONArray();
        if (signIn) actions.put(SIGN_IN);
        actions.put(OPEN);
        JSONObject expanded = new JSONObject()
                .put("template", "progress")
                .put("title", clip(title, 80))
                .put("body", clip(summary, 600))
                .put("pic", PIC)
                .put("progress", progressSlot)
                .put("actions", actions)
                .put("primaryAction", signIn ? SIGN_IN : OPEN);
        JSONObject payload = new JSONObject()
                .put("version", 1)
                .put("category", "progress")
                .put("relevance", relevance)
                .put("bigIsland", new JSONObject()
                        .put("leading", new JSONObject().put("type", "pic").put("pic", PIC)
                                .put("animated", running && !"stopping".equals(phase) ? "working" : "none")
                                .put("contentDescription", "Taku"))
                        .put("trailing", new JSONObject().put("type", "text").put("text", clip(trailing, 16)).put("emphasis", emphasis)))
                .put("smallIsland", new JSONObject().put("type", "pic").put("pic", PIC))
                .put("expanded", expanded)
                .put("alert", alert)
                .put("islandTimeoutS", 3600)
                .put("statusBar", "auto");
        // A running task that stops updating is shown stale instead of looking alive forever.
        if (running) payload.put("staleAfterS", 120);
        while (payload.toString().getBytes(StandardCharsets.UTF_8).length > 8192 && !expanded.optString("body").isEmpty()) {
            String body = expanded.getString("body");
            expanded.put("body", clip(body, body.codePointCount(0, body.length()) / 2));
        }
        return new Projection(payload, signIn, running);
    }

    static String toolCounter(JSONObject progress) {
        if (progress == null) return "Tool";
        int total = progress.optInt("toolCalls", 0), done = progress.optInt("toolCallsCompleted", 0);
        if (total <= 0 || total > 999) return "Tool";
        return "Tools " + Math.min(done, total) + "/" + total;
    }

    static Double planRatio(String plan) {
        if (plan == null || !plan.matches("[0-9]{1,4}/[0-9]{1,4}")) return null;
        int slash = plan.indexOf('/');
        int done = Integer.parseInt(plan.substring(0, slash)), total = Integer.parseInt(plan.substring(slash + 1));
        if (total <= 0 || done > total) return null;
        return done / (double) total;
    }

    /** At most {@code max} UTF-16 chars, never splitting a surrogate pair; control characters removed. */
    static String clip(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
        if (text.length() <= max) return text;
        int end = 0;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            if (next > max - 1) break;
            end = next;
        }
        return text.substring(0, end) + "…";
    }

    private static JSONObject record(File file) throws Exception {
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_RECORD_BYTES) throw new IOException("record too large");
            }
            return new JSONObject(out.toString("UTF-8"));
        }
    }
}
