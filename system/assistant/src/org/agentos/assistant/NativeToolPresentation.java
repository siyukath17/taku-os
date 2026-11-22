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
                String shortName = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('/')) + 1);
                if (shortName.startsWith("taku_phone__")) shortName = shortName.substring(12);
                boolean phone = name.startsWith("taku_phone") || "taku_phone".equals(string(item, "namespace"));
                if (phone) switch (shortName) {
                    case "device_info": return label(labels, "device_info", "Read device information");
                    case "observe_background": return label(labels, "observe_background", "View background screen");
                    case "open_app": return label(labels, "open_app", "Open app");
                    case "tap": return label(labels, "tap", "Tap background screen");
                    case "type_text": return label(labels, "type_text", "Enter text");
                }
                return name.isEmpty() ? label(labels, "tool", "Call tool") : name;
            case "commandExecution": return label(labels, "command", "Run command");
            case "fileChange": return label(labels, "file_change", "File changes");
            case "mcpToolCall":
                // ACP items carry a display "title" (AcpToolLabels); "tool" is then an internal id.
                String display = string(item, "title");
                if (!display.isEmpty()) return display;
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
        append(out, string(item, "command"));
        append(out, string(item, "server"));
        JSONObject arguments = item.optJSONObject("arguments");
        if (arguments != null) append(out, arguments.toString());
        String output = string(item, "aggregatedOutput");
        append(out, output.isEmpty() ? streamedOutput : output);
        JSONArray content = item.optJSONArray("contentItems");
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            if ("inputText".equals(part.optString("type"))) append(out, part.optString("text"));
            else if ("inputImage".equals(part.optString("type"))) append(out, label(labels, "image_result", "The tool returned an image"));
        }
        JSONObject result = item.optJSONObject("result");
        if (result != null) {
            JSONArray blocks = result.optJSONArray("content");
            if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) append(out, block.optString("text"));
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
