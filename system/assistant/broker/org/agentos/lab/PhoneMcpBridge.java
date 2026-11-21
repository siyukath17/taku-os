package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Taku phone tools ({@code taku_phone}: dedicated background screens, apps, memory, ...) as a
 * standard MCP server over newline-delimited JSON-RPC 2.0, so any ACP agent can use them.
 *
 * <p>This class is the protocol core only: tool declarations, argument validation against each
 * tool's JSON Schema, permission checks and error mapping. Execution is delegated to a
 * {@link ToolExecutor} (on device: {@code PhoneNativeTools.execute}, the same dispatcher Codex
 * uses), so both runtimes share one authoritative implementation of every phone action.
 */
final class PhoneMcpBridge {
    static final String SERVER_NAME = "taku_phone";
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601,
            INVALID_PARAMS = -32602, INTERNAL_ERROR = -32603, NOT_INITIALIZED = -32002;
    /**
     * Interactive cards wait on the native UI request flow owned by the Codex bridge; they are not
     * offered over MCP until that flow is runtime-neutral.
     */
    static final Set<String> NOT_EXPOSED = Set.of("ask_user", "confirm_action");

    /** Who is calling: the per-task thread identity that owns a background screen. */
    static final class Session {
        final String threadId, permissionMode;

        Session(String threadId, String permissionMode) {
            this.threadId = threadId;
            this.permissionMode = permissionMode == null || permissionMode.isEmpty() ? "full" : permissionMode;
        }
    }

    /** Executes one validated, permitted call. Returns the Codex-style {@code {success, contentItems}}. */
    interface ToolExecutor {
        JSONObject execute(String tool, JSONObject arguments, Session session) throws Exception;
    }

    /** Returns null to allow, or a user-readable reason to deny. */
    interface PermissionHook {
        String deny(String tool, JSONObject arguments, boolean observation, Session session);
    }

    interface Definitions {
        JSONArray load() throws Exception;
    }

    private final Definitions definitions;
    private final ToolExecutor executor;
    private final PermissionHook permissions;
    private final Session session;
    private Map<String, JSONObject> tools;
    private boolean initialized;

    PhoneMcpBridge(Definitions definitions, ToolExecutor executor, PermissionHook permissions, Session session) {
        this.definitions = definitions;
        this.executor = executor;
        this.permissions = permissions == null ? modePolicy() : permissions;
        this.session = session;
    }

    /**
     * The default hook, matching the Codex bridge: {@code readOnly} allows observation only;
     * {@code ask} needs a native approval card, which MCP calls cannot show yet, so it denies.
     */
    static PermissionHook modePolicy() {
        return (tool, arguments, observation, session) -> {
            if (observation) return null;
            if ("readOnly".equals(session.permissionMode)) return "This conversation is read-only; the phone operation was blocked";
            if ("ask".equals(session.permissionMode)) return "This phone operation needs your approval, which is not available for this agent yet; it was blocked";
            return null;
        };
    }

    /** Same classification the Codex bridge uses for read-only tools (NativeBridge.request). */
    static boolean observation(String tool, JSONObject arguments) {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        switch (tool) {
            case "device_info":
            case "screen_status":
            case "observe_background":
            case "memory_search":
            case "memory_get":
            case "apps":
                return true;
            case "matter":
                return "read".equals(args.optString("action"));
            case "audio":
                return "capabilities".equals(args.optString("action")) || "status".equals(args.optString("operation"));
            default:
                return false;
        }
    }

    // ---- stdio ------------------------------------------------------------------------------

    /** Serve one MCP client until EOF. Messages are processed in order; responses are flushed per line. */
    void serve(InputStream input, OutputStream output) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String answer = handleLine(line);
            if (answer == null) continue;
            output.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    /** One inbound line → one outbound line, or null for notifications. Batches are not used by MCP 2025-06-18. */
    String handleLine(String line) throws Exception {
        JSONObject message;
        try {
            message = new JSONObject(line);
        } catch (Exception malformed) {
            return error(JSONObject.NULL, PARSE_ERROR, "Parse error", null).toString();
        }
        JSONObject answer = handle(message);
        return answer == null ? null : answer.toString();
    }

    JSONObject handle(JSONObject message) throws Exception {
        Object id = message.opt("id");
        boolean notification = id == null;
        String method = message.optString("method", "");
        if (!"2.0".equals(message.optString("jsonrpc")) || method.isEmpty())
            return notification ? null : error(id, INVALID_REQUEST, "Invalid Request", null);
        JSONObject params = message.optJSONObject("params");
        if (params == null) params = new JSONObject();
        try {
            switch (method) {
                case "initialize":
                    return notification ? null : ok(id, initialize(params));
                case "notifications/initialized":
                case "notifications/cancelled":
                    return null;
                case "ping":
                    return notification ? null : ok(id, new JSONObject());
                case "tools/list":
                    if (!initialized) return notification ? null : error(id, NOT_INITIALIZED, "Server not initialized", null);
                    return notification ? null : ok(id, new JSONObject().put("tools", listTools()));
                case "tools/call":
                    if (!initialized) return notification ? null : error(id, NOT_INITIALIZED, "Server not initialized", null);
                    JSONObject result = call(params, id);
                    return notification ? null : result;
                default:
                    return notification ? null : error(id, METHOD_NOT_FOUND, "Method not found: " + method, null);
            }
        } catch (Exception failure) {
            return notification ? null : error(id, INTERNAL_ERROR, "Internal error", null);
        }
    }

    private JSONObject initialize(JSONObject params) throws Exception {
        String requested = params.optString("protocolVersion", "");
        initialized = true;
        return new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0))
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                .put("serverInfo", new JSONObject().put("name", SERVER_NAME).put("version", "1"))
                .put("instructions", "Android phone primitives on Taku OS. Request apps before acting; actions run on your own background screen.");
    }

    private Map<String, JSONObject> tools() throws Exception {
        if (tools != null) return tools;
        Map<String, JSONObject> out = new LinkedHashMap<>();
        collect(definitions.load(), out);
        tools = out;
        return tools;
    }

    /** Accepts the Codex declaration shape (namespaces of function tools) or a flat tool list. */
    private static void collect(JSONArray declared, Map<String, JSONObject> out) throws Exception {
        for (int i = 0; i < declared.length(); i++) {
            JSONObject item = declared.optJSONObject(i);
            if (item == null) continue;
            if ("namespace".equals(item.optString("type"))) {
                JSONArray nested = item.optJSONArray("tools");
                if (nested != null) collect(nested, out);
                continue;
            }
            String name = item.optString("name", "");
            if (name.isEmpty() || NOT_EXPOSED.contains(name)) continue;
            JSONObject schema = item.optJSONObject("inputSchema");
            if (schema == null) schema = item.optJSONObject("parameters");
            if (schema == null) schema = new JSONObject().put("type", "object");
            out.put(name, new JSONObject().put("name", name)
                    .put("description", item.optString("description", ""))
                    .put("inputSchema", new JSONObject(schema.toString())));
        }
    }

    private JSONArray listTools() throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject tool : tools().values()) list.put(new JSONObject(tool.toString()));
        return list;
    }

    private JSONObject call(JSONObject params, Object id) throws Exception {
        String name = params.optString("name", "");
        JSONObject tool = tools().get(name);
        if (tool == null) return error(id, INVALID_PARAMS, "Unknown tool: " + name, null);
        Object raw = params.opt("arguments");
        if (raw == null || raw == JSONObject.NULL) raw = new JSONObject();
        if (!(raw instanceof JSONObject)) return error(id, INVALID_PARAMS, "Invalid arguments for " + name,
                new JSONObject().put("errors", new JSONArray().put("$: expected object")));
        JSONObject arguments = (JSONObject) raw;
        List<String> problems = new ArrayList<>();
        validate(tool.getJSONObject("inputSchema"), arguments, "$", problems);
        if (!problems.isEmpty())
            return error(id, INVALID_PARAMS, "Invalid arguments for " + name, new JSONObject().put("errors", new JSONArray(problems)));
        String denied = permissions.deny(name, arguments, observation(name, arguments), session);
        if (denied != null) return ok(id, toolError("PERMISSION_DENIED", denied));
        JSONObject executed;
        try {
            executed = executor.execute(name, new JSONObject(arguments.toString()), session);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ok(id, toolError("INTERRUPTED", "The phone operation was interrupted"));
        } catch (Exception failure) {
            String detail = failure.getMessage() == null ? "" : failure.getMessage();
            if (detail.matches("[A-Z][A-Z0-9_]{2,63}")) return ok(id, toolError(detail, "Phone tool failed: " + detail));
            return ok(id, toolError("TOOL_FAILED", AcpRuntime.clip(AcpRuntime.redact(detail.isEmpty() ? "The phone operation failed" : detail), 500)));
        }
        return ok(id, content(executed));
    }

    /** Codex-style {@code contentItems} → MCP content blocks. */
    static JSONObject content(JSONObject executed) throws Exception {
        JSONArray blocks = new JSONArray();
        JSONArray items = executed == null ? null : executed.optJSONArray("contentItems");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if ("inputText".equals(item.optString("type"))) {
                blocks.put(new JSONObject().put("type", "text").put("text", item.optString("text")));
            } else if ("inputImage".equals(item.optString("type"))) {
                String url = item.optString("imageUrl");
                int comma = url.indexOf(',');
                if (url.startsWith("data:") && comma > 0 && url.substring(0, comma).endsWith(";base64"))
                    blocks.put(new JSONObject().put("type", "image")
                            .put("mimeType", url.substring(5, url.indexOf(';')))
                            .put("data", url.substring(comma + 1)));
            }
        }
        return new JSONObject().put("content", blocks).put("isError", executed == null || !executed.optBoolean("success", false));
    }

    static JSONObject toolError(String code, String message) throws Exception {
        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", message)))
                .put("structuredContent", new JSONObject().put("code", code))
                .put("isError", true);
    }

    // ---- JSON Schema subset used by the phone tool declarations ----------------------------------

    static void validate(JSONObject schema, Object value, String path, List<String> problems) throws Exception {
        if (problems.size() >= 20) return;
        String type = schema.optString("type", "");
        if (!type.isEmpty() && !hasType(value, type)) {
            problems.add(path + ": expected " + type);
            return;
        }
        JSONArray allowed = schema.optJSONArray("enum");
        if (allowed != null) {
            boolean found = false;
            for (int i = 0; i < allowed.length() && !found; i++) found = allowed.get(i).equals(value);
            if (!found) problems.add(path + ": not one of " + allowed);
        }
        if (value instanceof String) {
            int length = ((String) value).codePointCount(0, ((String) value).length());
            if (schema.has("minLength") && length < schema.getInt("minLength")) problems.add(path + ": shorter than " + schema.getInt("minLength"));
            if (schema.has("maxLength") && length > schema.getInt("maxLength")) problems.add(path + ": longer than " + schema.getInt("maxLength"));
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (schema.has("minimum") && number < schema.getDouble("minimum")) problems.add(path + ": below " + schema.get("minimum"));
            if (schema.has("maximum") && number > schema.getDouble("maximum")) problems.add(path + ": above " + schema.get("maximum"));
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (schema.has("minItems") && array.length() < schema.getInt("minItems")) problems.add(path + ": fewer than " + schema.getInt("minItems") + " items");
            if (schema.has("maxItems") && array.length() > schema.getInt("maxItems")) problems.add(path + ": more than " + schema.getInt("maxItems") + " items");
            JSONObject items = schema.optJSONObject("items");
            if (items != null) for (int i = 0; i < array.length(); i++) validate(items, array.get(i), path + "[" + i + "]", problems);
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject properties = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            if (required != null) for (int i = 0; i < required.length(); i++)
                if (!object.has(required.getString(i))) problems.add(path + "." + required.getString(i) + ": required");
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject property = properties == null ? null : properties.optJSONObject(key);
                if (property != null) validate(property, object.get(key), path + "." + key, problems);
                else if (schema.has("additionalProperties") && !schema.optBoolean("additionalProperties", true))
                    problems.add(path + "." + key + ": not allowed");
            }
        }
    }

    private static boolean hasType(Object value, String type) {
        switch (type) {
            case "object": return value instanceof JSONObject;
            case "array": return value instanceof JSONArray;
            case "string": return value instanceof String;
            case "boolean": return value instanceof Boolean;
            case "number": return value instanceof Number;
            case "integer":
                if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                        || value instanceof java.math.BigInteger) return true;
                if (value instanceof Number) {
                    double d = ((Number) value).doubleValue();
                    return !Double.isInfinite(d) && d == Math.rint(d);
                }
                return false;
            case "null": return value == JSONObject.NULL;
            default: return true;
        }
    }

    // ---- JSON-RPC envelopes --------------------------------------------------------------------

    static JSONObject ok(Object id, JSONObject result) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    static JSONObject error(Object id, int code, String message, JSONObject data) throws Exception {
        JSONObject error = new JSONObject().put("code", code).put("message", message);
        if (data != null) error.put("data", data);
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", error);
    }
}
