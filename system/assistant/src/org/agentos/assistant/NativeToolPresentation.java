package org.agentos.assistant;

import org.json.*;

/** Display names only; never infers a task workflow or successful completion. */
final class NativeToolPresentation {
    interface Labels { String text(String key, Object... arguments); }

    static String title(JSONObject item) {
        return title(item, null);
    }

    static String title(JSONObject item, Labels labels) {
        switch (string(item, "type")) {
            case "dynamicToolCall":
                String name = string(item, "tool");
                String phoneTool = phoneTool(item);
                if (phoneTool != null) return phoneLabel(labels, phoneTool);
                return name.isEmpty() ? label(labels, "tool", "Call tool") : name;
            case "commandExecution": return label(labels, "command", "Run command");
            case "fileChange": return label(labels, "file_change", "File changes");
            case "mcpToolCall":
                // ACP items carry a display "title" (AcpToolLabels); "tool" is then an internal id.
                String display = string(item, "title");
                if (!display.isEmpty()) return display;
                String mcpPhone = phoneTool(item);
                if (mcpPhone != null) return phoneLabel(labels, mcpPhone);
                String tool = string(item, "tool");
                return tool.isEmpty() ? label(labels, "extension", "Call extension tool") : tool;
            case "webSearch": return label(labels, "web_search", "Search the web");
            case "contextCompaction": return label(labels, "compaction", "Organize conversation context");
            case "collabAgentToolCall": case "collabToolCall": return label(labels, "collaboration", "Agent collaboration");
            case "enteredReviewMode": return label(labels, "review_enter", "Enter review");
            case "exitedReviewMode": return label(labels, "review_exit", "End review");
            default: return label(labels, "tool_call", "Tool call");
        }
    }

    static String status(JSONObject item) {
        return status(item, null);
    }

    static String status(JSONObject item, Labels labels) {
        if (Boolean.FALSE.equals(item.opt("success"))) return label(labels, "failed", "Failed");
        if ("commandExecution".equals(string(item, "type")) && item.has("exitCode")
                && !item.isNull("exitCode") && item.optInt("exitCode") != 0) return label(labels, "failed", "Failed");
        switch (string(item, "status")) {
            case "inProgress": case "in_progress": return label(labels, "working", "In progress");
            case "completed": return label(labels, "completed", "Ended");
            case "failed": case "errored": return label(labels, "failed", "Failed");
            case "declined": return label(labels, "declined", "Declined");
            case "interrupted": return label(labels, "interrupted", "Interrupted");
            case "cancelled": case "canceled": return label(labels, "cancelled", "Cancelled");
            case "pending": return label(labels, "pending", "Waiting");
            default: return ""; // Missing or future status is not evidence of success.
        }
    }

    static String details(JSONObject item, String streamedOutput) {
        return details(item, streamedOutput, null);
    }

    static String details(JSONObject item, String streamedOutput, Labels labels) {
        StringBuilder out = new StringBuilder();
        // A taku_phone tool's server, arguments and JSON answer are for the agent, not the user (BUG-0033):
        // only human text (plain output, a JSON result's message/error) is shown; the thread keeps the rest.
        boolean phone = phoneTool(item) != null;
        append(out, string(item, "command"));
        if (!phone) {
            append(out, string(item, "server"));
            JSONObject arguments = item.optJSONObject("arguments");
            if (arguments != null) append(out, arguments.toString());
        }
        String output = string(item, "aggregatedOutput");
        append(out, result(phone, output.isEmpty() ? streamedOutput : output));
        JSONArray content = item.optJSONArray("contentItems");
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            if ("inputText".equals(part.optString("type"))) append(out, result(phone, part.optString("text")));
            else if ("inputImage".equals(part.optString("type"))) append(out, label(labels, "image_result", "The tool returned an image"));
        }
        JSONObject result = item.optJSONObject("result");
        if (result != null) {
            JSONArray blocks = result.optJSONArray("content");
            if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) append(out, result(phone, block.optString("text")));
            }
        }
        JSONArray changes = item.optJSONArray("changes");
        if (changes != null) for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            if (change != null) { append(out, change.optString("path")); append(out, change.optString("diff")); }
        }
        Object error = item.opt("error");
        if (error != null && error != JSONObject.NULL) append(out, error instanceof JSONObject
                ? ((JSONObject) error).optString("message") : String.valueOf(error));
        if (item.has("exitCode") && !item.isNull("exitCode"))
            append(out, label(labels, "exit_code", "Exit code: %1$d", item.optInt("exitCode")));
        // Keep large command output scrollable without allocating an unbounded TextView.
        return out.length() > 16000 ? label(labels, "output_tail", "…(Showing only the end of the output)")
                + "\n" + out.substring(out.length() - 16000) : out.toString();
    }

    /** taku_phone tools by bare name → {label key, English fallback}. */
    private static final java.util.Map<String, String[]> PHONE_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("device_info", new String[]{"device_info", "Read device information"}),
            java.util.Map.entry("observe_background", new String[]{"observe_background", "View background screen"}),
            java.util.Map.entry("screenshot", new String[]{"screenshot", "View the screen"}),
            java.util.Map.entry("open_app", new String[]{"open_app", "Open app"}),
            java.util.Map.entry("tap", new String[]{"tap", "Tap background screen"}),
            java.util.Map.entry("swipe", new String[]{"swipe", "Swipe the screen"}),
            java.util.Map.entry("touch", new String[]{"touch", "Touch the screen"}),
            java.util.Map.entry("type_text", new String[]{"type_text", "Enter text"}),
            java.util.Map.entry("apps", new String[]{"apps", "Find apps"}),
            java.util.Map.entry("request_apps", new String[]{"request_apps", "Request a background screen"}),
            java.util.Map.entry("release_apps", new String[]{"release_apps", "Release the background screen"}),
            java.util.Map.entry("screen_status", new String[]{"screen_status", "Check the background screen"}));

    /**
     * The bare taku_phone tool of a dynamic (Codex) or MCP (ACP) tool item, else null: by namespace,
     * by a {@code taku_phone.}/{@code taku_phone__} prefix, or a known phone tool name without any
     * namespace (only taku_phone registers those names).
     */
    static String phoneTool(JSONObject item) {
        String type = string(item, "type");
        if (!"dynamicToolCall".equals(type) && !"mcpToolCall".equals(type)) return null;
        String name = string(item, "tool");
        String shortName = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('/')) + 1);
        if (shortName.startsWith("taku_phone__")) shortName = shortName.substring(12);
        String namespace = string(item, "namespace");
        if (name.startsWith("taku_phone") || "taku_phone".equals(namespace)) return shortName;
        boolean bare = namespace.isEmpty() && ("dynamicToolCall".equals(type) || "agy".equals(string(item, "server")));
        return bare && PHONE_LABELS.containsKey(name) ? name : null;
    }

    private static String phoneLabel(Labels labels, String tool) {
        String[] pair = PHONE_LABELS.get(tool);
        return pair == null ? label(labels, "phone_tool", "Use the phone") : label(labels, pair[0], pair[1]);
    }

    /** A phone tool's JSON answer keeps only its human message/error; plain text and other tools' output pass. */
    static String result(boolean phone, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!phone || !(trimmed.startsWith("{") || trimmed.startsWith("["))) return text;
        Object value;
        try {
            value = new JSONTokener(trimmed).nextValue();
        } catch (JSONException e) {
            return text;
        }
        if (!(value instanceof JSONObject)) return value instanceof JSONArray ? "" : text;
        JSONObject o = (JSONObject) value;
        Object error = o.opt("error");
        String errorText = error instanceof JSONObject ? ((JSONObject) error).optString("message") : error instanceof String ? (String) error : "";
        String message = string(o, "message").trim();
        return !message.isEmpty() ? message : errorText.trim();
    }

    private static String label(Labels labels, String key, String fallback, Object... arguments) {
        return labels == null ? String.format(java.util.Locale.ROOT, fallback, arguments)
                : labels.text(key, arguments);
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static void append(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(text);
    }
}
