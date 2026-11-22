package org.agentos.lab;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only view of the asynchronous subagents an agy session started, from agy's own conversation
 * store. ACP itself carries no subagent lifecycle (agy 1.5.14, verified on the Pixel 9, 2026-11-22):
 * {@code invoke_subagent} completes as soon as the child is created, the parent's
 * {@code session/prompt} answers {@code end_turn} while children still run, and when a child reports
 * back agy wakes the parent by itself and streams more {@code session/update}s with no
 * {@code session/prompt} reply and no end marker.
 *
 * <p>agy records, under {@code $HOME/.gemini/<appDataDir>/brain/<sessionId>/.system_generated/}:
 * {@code subagents/<childId>.json} ({@code conversationId}, {@code state}; it stays
 * {@code SUBAGENT_STATE_ALIVE} after the child answers, so it is not a completion signal),
 * {@code messages/<messageId>.json} ({@code sender} = child, {@code recipient} = parent) for each report,
 * and {@code messages/read.json} ({@code {messageId: true}}) once the parent consumed it.
 * A child has <em>reported</em> when a message from it, newer than the turn's start, exists; it has been
 * <em>delivered</em> when read.json marks that message read.
 */
final class AcpSubagents {
    private final File root;
    private final Set<String> baselineChildren = new HashSet<>();
    private final Set<String> baselineMessages = new HashSet<>();

    /** A snapshot: children started in this turn, and which of them reported / were delivered. */
    static final class Status {
        final Map<String, Boolean> reported = new LinkedHashMap<>();
        final Map<String, Boolean> delivered = new LinkedHashMap<>();
        boolean readable;
        /** Wall-clock time the newest report of a started child was written (0 if none). */
        long lastReportMillis;

        int started() { return reported.size(); }

        int reportedCount() {
            int n = 0;
            for (boolean value : reported.values()) if (value) n++;
            return n;
        }

        boolean allReported() { return !reported.isEmpty() && reportedCount() == reported.size(); }

        boolean allDelivered() {
            if (!allReported()) return false;
            for (boolean value : delivered.values()) if (!value) return false;
            return true;
        }
    }

    AcpSubagents(File sessionRoot) {
        this.root = sessionRoot;
    }

    /** {@code <home>/.gemini/<appDataDir>/brain/<sessionId>/.system_generated}. */
    static File sessionRoot(String home, String appDataDir, String sessionId) {
        return new File(new File(new File(new File(new File(home, ".gemini"), appDataDir), "brain"), sessionId), ".system_generated");
    }

    /** Remembers what existed before the turn, so a resumed session's earlier children are ignored. */
    void baseline() {
        baselineChildren.addAll(children().keySet());
        for (JSONObject message : messages().values()) baselineMessages.add(message.optString("id"));
    }

    Status status() {
        Status status = new Status();
        File subagents = new File(root, "subagents");
        status.readable = subagents.isDirectory();
        Map<String, JSONObject> messages = messages();
        JSONObject read = json(new File(new File(root, "messages"), "read.json"));
        for (String child : children().keySet()) {
            if (baselineChildren.contains(child)) continue;
            boolean reported = false, delivered = false;
            for (JSONObject message : messages.values()) {
                String id = message.optString("id");
                if (!child.equals(message.optString("sender")) || baselineMessages.contains(id)) continue;
                reported = true;
                status.lastReportMillis = Math.max(status.lastReportMillis, message.optLong(MODIFIED, 0));
                // Without read.json (an older or newer store layout) a report counts as delivered.
                if (read == null || read.optBoolean(id, false)) delivered = true;
            }
            status.reported.put(child, reported);
            status.delivered.put(child, delivered);
        }
        return status;
    }

    /** What one child started in this turn looks like, for its chat card (see {@link AcpSubagentCards}). */
    static final class Child {
        String id = "", role = "", typeName = "";
        /** When the child's record appeared (its file's mtime), 0 if unknown. */
        long createdMillis;
        /** The model the child ran on (agy's id, e.g. {@code gemini-4-neon-high}), empty if not yet known. */
        String model = "";
        /** agy's name of the child's latest tool call, empty if none; {@code stage} says what it is doing. */
        String lastTool = "";
        /** {@code starting} (no step yet), {@code thinking}, {@code tool}, {@code reporting}, {@code done}. */
        String stage = "starting";
        boolean reported;
        long reportedMillis;
        /** The child's report to the parent (raw; the card sanitizes and clips it). */
        String report = "";
    }

    /** Children started in this turn, oldest first, with their latest step and report. Never null. */
    java.util.List<Child> details() {
        java.util.List<Child> out = new java.util.ArrayList<>();
        Map<String, JSONObject> messages = messages();
        for (Map.Entry<String, JSONObject> entry : children().entrySet()) {
            if (baselineChildren.contains(entry.getKey())) continue;
            Child child = new Child();
            child.id = entry.getKey();
            JSONObject descriptor = entry.getValue().optJSONObject("subagentDescriptor");
            if (descriptor != null) {
                child.role = descriptor.optString("role", "");
                child.typeName = descriptor.optString("typeName", "");
            }
            child.createdMillis = entry.getValue().optLong(MODIFIED, 0);
            for (JSONObject message : messages.values()) {
                if (!child.id.equals(message.optString("sender")) || baselineMessages.contains(message.optString("id"))) continue;
                long at = timestamp(message.optString("timestamp", ""), message.optLong(MODIFIED, 0));
                if (!child.reported || at >= child.reportedMillis) {
                    child.reported = true;
                    child.reportedMillis = at;
                    child.report = message.optString("content", "");
                }
            }
            transcript(child);
            if (child.reported) child.stage = "done";
            out.add(child);
        }
        out.sort((a, b) -> Long.compare(a.createdMillis, b.createdMillis));
        return out;
    }

    private static long timestamp(String iso, long fallback) {
        try {
            return iso.isEmpty() ? fallback : java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception unparsable) {
            return fallback;
        }
    }

    /**
     * The child's model and latest step from its own transcript,
     * {@code brain/<child>/.system_generated/logs/transcript.jsonl} (one JSON step per line; a model
     * step carries {@code model} and {@code tool_calls:[{name,args}]}). Only the tail is read.
     */
    private void transcript(Child child) {
        File brain = root.getParentFile() == null ? null : root.getParentFile().getParentFile();
        if (brain == null) return;
        File log = new File(new File(new File(new File(brain, child.id), ".system_generated"), "logs"), "transcript.jsonl");
        String tail = tail(log, 96 * 1024);
        if (tail.isEmpty()) return;
        String[] lines = tail.split("\n");
        boolean stepSeen = false;
        for (int i = lines.length - 1; i >= 0; i--) {
            JSONObject step;
            try {
                step = new JSONObject(lines[i]);
            } catch (Exception partial) {
                continue; // The first line of a tail, or a line agy is still writing.
            }
            if (child.model.isEmpty() && !step.optString("model", "").isEmpty()) child.model = step.optString("model");
            if (!stepSeen && "MODEL".equals(step.optString("source"))) {
                stepSeen = true;
                org.json.JSONArray calls = step.optJSONArray("tool_calls");
                JSONObject last = calls == null || calls.length() == 0 ? null : calls.optJSONObject(calls.length() - 1);
                if (last != null && !last.optString("name", "").isEmpty()) {
                    child.lastTool = last.optString("name");
                    child.stage = "send_message".equals(child.lastTool) ? "reporting" : "tool";
                } else if ("PLANNER_RESPONSE".equals(step.optString("type"))) {
                    child.stage = "thinking";
                } else {
                    child.stage = "thinking"; // A tool's output: the model reads it next.
                }
            }
            if (stepSeen && !child.model.isEmpty()) break;
        }
    }

    private static String tail(File file, int max) {
        if (!file.isFile()) return "";
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
            long length = in.length();
            long start = Math.max(0, length - max);
            byte[] bytes = new byte[(int) (length - start)];
            in.seek(start);
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return "";
        }
    }

    private Map<String, JSONObject> children() {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        File[] files = new File(root, "subagents").listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File file : files) {
            JSONObject child = json(file);
            String id = child == null ? "" : child.optString("conversationId", "");
            if (id.isEmpty()) continue;
            try { child.put(MODIFIED, file.lastModified()); } catch (Exception impossible) { /* org.json on Android declares it */ }
            out.put(id, child);
        }
        return out;
    }

    private Map<String, JSONObject> messages() {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        File[] files = new File(root, "messages").listFiles((dir, name) -> name.endsWith(".json") && !"read.json".equals(name));
        if (files == null) return out;
        for (File file : files) {
            JSONObject message = json(file);
            if (message != null && !message.optString("id").isEmpty()) {
                try { message.put(MODIFIED, file.lastModified()); } catch (Exception impossible) { /* org.json on Android declares it */ }
                out.put(message.optString("id"), message);
            }
        }
        return out;
    }

    private static final String MODIFIED = "\u0000modified";

    private static JSONObject json(File file) {
        try {
            if (!file.isFile() || file.length() > 4 * 1024 * 1024) return null;
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception torn) {
            return null; // agy may be rewriting it; the next poll reads it again.
        }
    }
}
