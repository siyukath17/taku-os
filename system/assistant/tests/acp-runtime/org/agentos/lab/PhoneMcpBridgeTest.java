package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** MCP protocol core of the phone tool bridge with a recording executor; no Android. */
public final class PhoneMcpBridgeTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    /** The declaration shape PhoneNativeTools.definitions() produces (Codex namespace of function tools). */
    static JSONArray definitions() throws Exception {
        JSONObject coordinate720 = new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 719);
        JSONArray tools = new JSONArray()
                .put(tool("ask_user", new JSONObject().put("question", new JSONObject().put("type", "string"))))
                .put(tool("confirm_action", new JSONObject().put("title", new JSONObject().put("type", "string"))))
                .put(tool("observe_background", new JSONObject()))
                .put(tool("tap", new JSONObject().put("x", new JSONObject().put("type", "integer")).put("y", new JSONObject().put("type", "integer"))))
                .put(tool("swipe", new JSONObject().put("fromX", coordinate720).put("durationMs",
                        new JSONObject().put("type", "integer").put("minimum", 100).put("maximum", 5000))))
                .put(tool("request_apps", new JSONObject()
                        .put("packages", new JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 3)
                                .put("items", new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)))
                        .put("taskTitle", new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 80))))
                .put(tool("touch", new JSONObject().put("phase", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("DOWN").put("MOVE").put("UP").put("CANCEL")))))
                .put(tool("matter", new JSONObject().put("action", new JSONObject().put("type", "string"))));
        return new JSONArray().put(new JSONObject().put("type", "namespace").put("name", "taku_phone").put("tools", tools));
    }

    static JSONObject tool(String name, JSONObject properties) throws Exception {
        JSONArray required = new JSONArray();
        for (String key : properties.keySet()) required.put(key);
        return new JSONObject().put("type", "function").put("name", name).put("description", name + " tool")
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", required).put("additionalProperties", false));
    }

    static final class Executor implements PhoneMcpBridge.ToolExecutor {
        final List<String> calls = new ArrayList<>();
        Exception failure;
        JSONObject answer;

        @Override
        public JSONObject execute(String tool, JSONObject arguments, PhoneMcpBridge.Session session) throws Exception {
            calls.add(tool + " " + arguments + " @" + session.threadId);
            if (failure != null) throw failure;
            if (answer != null) return answer;
            if (tool.equals("observe_background"))
                return new JSONObject().put("success", true).put("contentItems", new JSONArray()
                        .put(new JSONObject().put("type", "inputText").put("text", "{\"displayId\":7}"))
                        .put(new JSONObject().put("type", "inputImage").put("imageUrl", "data:image/png;base64,iVBORw0KGgo=")));
            return new JSONObject().put("success", true).put("contentItems", new JSONArray()
                    .put(new JSONObject().put("type", "inputText").put("text", "{\"action\":\"" + tool + "\",\"ok\":true}")));
        }
    }

    static PhoneMcpBridge bridge(Executor executor, String mode) throws Exception {
        return new PhoneMcpBridge(PhoneMcpBridgeTest::definitions, executor, null, new PhoneMcpBridge.Session("acp-42", mode));
    }

    static JSONObject rpc(Object id, String method, JSONObject params) throws Exception {
        JSONObject message = new JSONObject().put("jsonrpc", "2.0").put("method", method);
        if (id != null) message.put("id", id);
        if (params != null) message.put("params", params);
        return message;
    }

    static JSONObject call(PhoneMcpBridge bridge, int id, String name, Object arguments) throws Exception {
        return bridge.handle(rpc(id, "tools/call", new JSONObject().put("name", name).put("arguments", arguments)));
    }

    static PhoneMcpBridge ready(Executor executor, String mode) throws Exception {
        PhoneMcpBridge bridge = bridge(executor, mode);
        bridge.handle(rpc(0, "initialize", new JSONObject().put("protocolVersion", "2025-06-18")));
        check(bridge.handle(rpc(null, "notifications/initialized", null)) == null, "notifications get no response");
        return bridge;
    }

    static void initialize() throws Exception {
        PhoneMcpBridge bridge = bridge(new Executor(), "full");
        JSONObject early = bridge.handle(rpc(1, "tools/list", null));
        check(early.getJSONObject("error").getInt("code") == PhoneMcpBridge.NOT_INITIALIZED, "tools/list before initialize is refused");
        JSONObject init = bridge.handle(rpc(2, "initialize", new JSONObject().put("protocolVersion", "2025-03-26")
                .put("capabilities", new JSONObject()).put("clientInfo", new JSONObject().put("name", "agy"))));
        JSONObject result = init.getJSONObject("result");
        check("2.0".equals(init.getString("jsonrpc")) && init.getInt("id") == 2, "initialize: JSON-RPC envelope");
        check("2025-03-26".equals(result.getString("protocolVersion")), "initialize: supported client version is echoed");
        check(result.getJSONObject("capabilities").has("tools") && "taku_phone".equals(result.getJSONObject("serverInfo").getString("name")),
                "initialize: tools capability and server info");
        JSONObject future = bridge(new Executor(), "full").handle(rpc(3, "initialize", new JSONObject().put("protocolVersion", "2099-01-01")));
        check("2025-06-18".equals(future.getJSONObject("result").getString("protocolVersion")), "initialize: unknown version negotiates the latest");
        check(new JSONObject().similar(bridge.handle(rpc(4, "ping", null)).getJSONObject("result")), "ping answers {}");
    }

    static void listTools() throws Exception {
        PhoneMcpBridge bridge = ready(new Executor(), "full");
        JSONArray tools = bridge.handle(rpc(1, "tools/list", null)).getJSONObject("result").getJSONArray("tools");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).getString("name"));
        check(names.equals(List.of("observe_background", "tap", "swipe", "request_apps", "touch", "matter")),
                "tools/list: namespace flattened, interactive cards withheld, got " + names);
        JSONObject tap = tools.getJSONObject(1);
        check("tap tool".equals(tap.getString("description")) && tap.getJSONObject("inputSchema").getJSONArray("required").length() == 2
                && !tap.has("type"), "tools/list: MCP tool shape with the original input schema");
    }

    static void callTools() throws Exception {
        Executor executor = new Executor();
        PhoneMcpBridge bridge = ready(executor, "full");
        JSONObject tap = call(bridge, 5, "tap", new JSONObject().put("x", 10).put("y", 20));
        JSONObject result = tap.getJSONObject("result");
        check(!result.getBoolean("isError") && "text".equals(result.getJSONArray("content").getJSONObject(0).getString("type"))
                && result.getJSONArray("content").getJSONObject(0).getString("text").contains("\"ok\":true"), "tools/call tap: text result");
        check(executor.calls.equals(List.of("tap {\"x\":10,\"y\":20} @acp-42")), "tools/call: executor gets the arguments and the task's thread");

        JSONObject observed = call(bridge, 6, "observe_background", new JSONObject()).getJSONObject("result");
        JSONObject image = observed.getJSONArray("content").getJSONObject(1);
        check("image".equals(image.getString("type")) && "image/png".equals(image.getString("mimeType"))
                && "iVBORw0KGgo=".equals(image.getString("data")), "tools/call observe_background: screenshot becomes an MCP image block");
        check(!call(bridge, 7, "tap", new JSONObject().put("x", 10.0).put("y", 20)).getJSONObject("result").getBoolean("isError"),
                "integral numbers satisfy integer");

        executor.answer = new JSONObject().put("success", false).put("contentItems", new JSONArray()
                .put(new JSONObject().put("type", "inputText").put("text", "App not held")));
        check(call(bridge, 8, "tap", new JSONObject().put("x", 1).put("y", 1)).getJSONObject("result").getBoolean("isError"),
                "executor success=false maps to isError");
        executor.answer = null;

        executor.failure = new IOException("SCREEN_AGENT_PAUSED");
        JSONObject paused = call(bridge, 9, "tap", new JSONObject().put("x", 1).put("y", 1)).getJSONObject("result");
        check(paused.getBoolean("isError") && "SCREEN_AGENT_PAUSED".equals(paused.getJSONObject("structuredContent").getString("code")),
                "error codes from the phone runtime are preserved");
        executor.failure = new IOException("upstream said Bearer abc.def.ghi failed");
        JSONObject failed = call(bridge, 10, "tap", new JSONObject().put("x", 1).put("y", 1)).getJSONObject("result");
        check("TOOL_FAILED".equals(failed.getJSONObject("structuredContent").getString("code"))
                && !failed.getJSONArray("content").getJSONObject(0).getString("text").contains("abc.def.ghi"), "free-text failures are redacted");
    }

    static void invalidArguments() throws Exception {
        Executor executor = new Executor();
        PhoneMcpBridge bridge = ready(executor, "full");
        JSONObject missing = call(bridge, 1, "tap", new JSONObject().put("x", 1));
        check(missing.getJSONObject("error").getInt("code") == PhoneMcpBridge.INVALID_PARAMS
                && missing.getJSONObject("error").getJSONObject("data").getJSONArray("errors").toList().contains("$.y: required"),
                "missing required argument is -32602 with the path");
        JSONObject wrongType = call(bridge, 2, "tap", new JSONObject().put("x", "10").put("y", 1));
        check(wrongType.getJSONObject("error").getJSONObject("data").getJSONArray("errors").toList().contains("$.x: expected integer"),
                "string for integer is rejected");
        JSONObject extra = call(bridge, 3, "tap", new JSONObject().put("x", 1).put("y", 1).put("display", 0));
        check(extra.getJSONObject("error").getJSONObject("data").getJSONArray("errors").toList().contains("$.display: not allowed"),
                "additionalProperties false blocks extra arguments (e.g. choosing another display)");
        JSONObject bounds = call(bridge, 4, "swipe", new JSONObject().put("fromX", 720).put("durationMs", 50));
        check(bounds.getJSONObject("error").getJSONObject("data").getJSONArray("errors").length() == 2, "minimum/maximum enforced");
        JSONObject empty = call(bridge, 5, "request_apps", new JSONObject().put("packages", new JSONArray()).put("taskTitle", "x"));
        check(empty.has("error"), "minItems enforced");
        JSONObject item = call(bridge, 6, "request_apps", new JSONObject().put("packages", new JSONArray().put("")).put("taskTitle", "x"));
        check(item.getJSONObject("error").getJSONObject("data").getJSONArray("errors").toList().contains("$.packages[0]: shorter than 1"),
                "array items are validated");
        JSONObject badEnum = call(bridge, 7, "touch", new JSONObject().put("phase", "HOLD"));
        check(badEnum.has("error"), "enum enforced");
        JSONObject notObject = call(bridge, 8, "tap", new JSONArray().put(1));
        check(notObject.getJSONObject("error").getInt("code") == PhoneMcpBridge.INVALID_PARAMS, "non-object arguments are -32602");
        JSONObject unknown = call(bridge, 9, "format_phone", new JSONObject());
        check(unknown.getJSONObject("error").getInt("code") == PhoneMcpBridge.INVALID_PARAMS, "unknown tool is -32602");
        JSONObject hidden = call(bridge, 10, "ask_user", new JSONObject().put("question", "?"));
        check(hidden.has("error"), "withheld interactive tools cannot be called");
        check(executor.calls.isEmpty(), "invalid calls never reach the phone");
    }

    static void permissions() throws Exception {
        Executor executor = new Executor();
        PhoneMcpBridge readOnly = ready(executor, "readOnly");
        JSONObject denied = call(readOnly, 1, "tap", new JSONObject().put("x", 1).put("y", 1)).getJSONObject("result");
        check(denied.getBoolean("isError") && "PERMISSION_DENIED".equals(denied.getJSONObject("structuredContent").getString("code")),
                "readOnly: actions are denied as tool errors");
        check(!call(readOnly, 2, "observe_background", new JSONObject()).getJSONObject("result").getBoolean("isError"), "readOnly: observation allowed");
        check(!call(readOnly, 3, "matter", new JSONObject().put("action", "read")).getJSONObject("result").getBoolean("isError"), "readOnly: matter read allowed");
        check(call(readOnly, 4, "matter", new JSONObject().put("action", "write")).getJSONObject("result").getBoolean("isError"), "readOnly: matter write denied");
        PhoneMcpBridge ask = ready(executor, "ask");
        check(call(ask, 5, "tap", new JSONObject().put("x", 1).put("y", 1)).getJSONObject("result").getBoolean("isError"),
                "ask: actions needing an approval card are denied");
        check(executor.calls.size() == 2, "denied calls never reach the phone, got " + executor.calls);

        List<String> seen = new ArrayList<>();
        PhoneMcpBridge custom = new PhoneMcpBridge(PhoneMcpBridgeTest::definitions, executor,
                (tool, arguments, observation, session) -> { seen.add(tool + ":" + observation); return tool.equals("swipe") ? "policy says no" : null; },
                new PhoneMcpBridge.Session("acp-9", "full"));
        custom.handle(rpc(0, "initialize", new JSONObject()));
        JSONObject swipe = call(custom, 6, "swipe", new JSONObject().put("fromX", 1).put("durationMs", 200)).getJSONObject("result");
        check(swipe.getBoolean("isError") && swipe.getJSONArray("content").getJSONObject(0).getString("text").equals("policy says no"),
                "custom permission hook can deny with its reason");
        call(custom, 7, "observe_background", new JSONObject());
        check(seen.equals(List.of("swipe:false", "observe_background:true")), "hook receives the observation classification");
    }

    static void protocolErrors() throws Exception {
        PhoneMcpBridge bridge = ready(new Executor(), "full");
        JSONObject parse = new JSONObject(bridge.handleLine("{not json"));
        check(parse.getJSONObject("error").getInt("code") == PhoneMcpBridge.PARSE_ERROR && parse.isNull("id"), "parse error is -32700 with null id");
        JSONObject invalid = bridge.handle(new JSONObject().put("id", 1).put("method", "tools/list"));
        check(invalid.getJSONObject("error").getInt("code") == PhoneMcpBridge.INVALID_REQUEST, "missing jsonrpc is -32600");
        JSONObject unknown = bridge.handle(rpc(2, "resources/list", null));
        check(unknown.getJSONObject("error").getInt("code") == PhoneMcpBridge.METHOD_NOT_FOUND, "unknown method is -32601");
        check(bridge.handle(rpc(null, "notifications/cancelled", new JSONObject().put("requestId", 1))) == null, "cancel notification is accepted silently");

        Executor executor = new Executor();
        String input = rpc(1, "initialize", new JSONObject().put("protocolVersion", "2025-06-18")) + "\n"
                + rpc(null, "notifications/initialized", null) + "\n\n"
                + rpc(2, "tools/list", null) + "\n"
                + rpc("c-3", "tools/call", new JSONObject().put("name", "tap").put("arguments", new JSONObject().put("x", 3).put("y", 4))) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bridge(executor, "full").serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        String[] lines = output.toString("UTF-8").trim().split("\n");
        check(lines.length == 3, "serve: one line per request, none for notifications");
        check("c-3".equals(new JSONObject(lines[2]).getString("id")) && executor.calls.size() == 1, "serve: string ids round-trip and the tool ran");
    }

    public static void main(String[] args) throws Exception {
        initialize();
        listTools();
        callTools();
        invalidArguments();
        permissions();
        protocolErrors();
        System.out.println("PhoneMcpBridgeTest passed " + checks + " checks");
    }
}
