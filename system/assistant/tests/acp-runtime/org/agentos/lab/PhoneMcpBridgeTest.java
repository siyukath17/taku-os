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

    /** Failures are recorded per turn with their leading code, for the ACP chat projection. */
    static void failuresRecordedPerTurn() throws Exception {
        Executor executor = new Executor();
        PhoneMcpBridge bridge = new PhoneMcpBridge(PhoneMcpBridgeTest::definitions, executor, null,
                new PhoneMcpBridge.Session("acp-42", "full", "turn-fail-1"));
        bridge.handle(rpc(0, "initialize", new JSONObject().put("protocolVersion", "2025-06-18")));
        bridge.handle(rpc(null, "notifications/initialized", null));
        executor.failure = new IOException("APPS_NOT_REQUESTED: call request_apps first");
        JSONObject result = call(bridge, 1, "tap", new JSONObject().put("x", 1).put("y", 1)).getJSONObject("result");
        check("APPS_NOT_REQUESTED".equals(result.getJSONObject("structuredContent").getString("code")),
                "a leading CODE: in free text becomes the error code");
        executor.failure = null;
        JSONObject recorded = PhoneMcpBridge.Failures.take("turn-fail-1", "tap");
        check(recorded != null && "APPS_NOT_REQUESTED".equals(recorded.getString("code"))
                && recorded.getString("message").contains("request_apps"), "failure recorded for the turn " + recorded);
        check(PhoneMcpBridge.Failures.take("turn-fail-1", "tap") == null, "take consumes");
        check(PhoneMcpBridge.Failures.code("Phone tool failed") == null && "X_Y".equals(PhoneMcpBridge.Failures.code("X_Y")), "code parsing");
        for (int i = 0; i < PhoneMcpBridge.Failures.MAX_TURNS + 5; i++) PhoneMcpBridge.Failures.record("t" + i, "tap", "A_B", "m");
        check(PhoneMcpBridge.Failures.take("t0", "tap") == null && PhoneMcpBridge.Failures.take("t" + (PhoneMcpBridge.Failures.MAX_TURNS + 4), "tap") != null,
                "registry is bounded to the newest turns");
    }

    /** device_info answers without a held execution screen (chat turns before request_apps). */
    static void deviceInfoWithoutScreen() throws Exception {
        java.util.Map<String, String> props = java.util.Map.of("ro.build.version.release", "17\n", "ro.product.model", "Pixel 9\n");
        JSONObject bare = PhoneDeviceInfo.read(props::get, () -> { throw new IOException("APPS_NOT_REQUESTED: call request_apps first"); });
        check("17".equals(bare.getString("android")) && "Pixel 9".equals(bare.getString("model")) && !bare.getBoolean("screen")
                && bare.getString("screenNote").startsWith("APPS_NOT_REQUESTED") && !bare.has("displayId"), "no screen: still answers " + bare);
        JSONObject held = PhoneDeviceInfo.read(props::get, () -> new JSONObject().put("displayId", 3).put("userId", 0));
        check(held.getBoolean("screen") && held.getInt("displayId") == 3 && "Pixel 9".equals(held.getString("model")), "held screen: merged");
        boolean interrupted = false;
        try { PhoneDeviceInfo.read(props::get, () -> { throw new InterruptedException(); }); } catch (InterruptedException expected) { interrupted = true; }
        check(interrupted, "interruption is not swallowed");
    }

    private static String[] serveLines(PhoneMcpBridge bridge, byte[] input, int maxLineBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bridge.serve(new ByteArrayInputStream(input), output, maxLineBytes);
        String text = output.toString("UTF-8");
        return text.isEmpty() ? new String[0] : text.split("\n");
    }

    private static int errorCode(String line) {
        JSONObject answer = new JSONObject(line);
        return answer.has("error") ? answer.getJSONObject("error").getInt("code") : 0;
    }

    /**
     * phone-mcp.sock carries whatever the agent's relay writes, inside the broker process: oversized,
     * malformed, non-object, deeply nested, non-UTF-8 and CRLF lines each get one answer (or none for
     * notifications) and the connection keeps serving; a tool that throws an Error fails that call only.
     */
    static void hostileInput() throws Exception {
        Executor executor = new Executor();
        String init = rpc(1, "initialize", new JSONObject().put("protocolVersion", "2025-06-18")).toString();
        String tap = rpc(9, "tools/call", new JSONObject().put("name", "tap").put("arguments", new JSONObject().put("x", 1).put("y", 2))).toString();
        // Oversized: drained and refused, the next request still runs; also at EOF without a newline.
        String huge = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\",\"pad\":\"" + "x".repeat(20_000) + "\"}";
        String[] lines = serveLines(bridge(executor, "full"), (init + "\n" + huge + "\n" + tap + "\n" + huge).getBytes(StandardCharsets.UTF_8), 1024);
        check(lines.length == 4, "one answer per line, oversized included: " + lines.length);
        check(errorCode(lines[1]) == PhoneMcpBridge.PARSE_ERROR && new JSONObject(lines[1]).isNull("id")
                && lines[1].contains("longer than 1024"), "an oversized line is a parse error: " + lines[1]);
        check(new JSONObject(lines[2]).getInt("id") == 9 && executor.calls.size() == 1, "the request after it is served");
        check(errorCode(lines[3]) == PhoneMcpBridge.PARSE_ERROR, "an oversized last line without newline is answered too");
        check(PhoneMcpBridge.MAX_LINE_BYTES >= 1 << 20, "the production cap leaves room for real tool arguments");

        // Malformed, non-object, invalid UTF-8 and CRLF lines.
        java.io.ByteArrayOutputStream input = new java.io.ByteArrayOutputStream();
        for (String line : new String[]{init, "[1,2]", "\"text\"", "42", "null", "{\"jsonrpc\":\"2.0\",\"id\":3,"})
            input.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        input.write(new byte[]{'{', (byte) 0xff, (byte) 0xfe, '}', '\n'});
        input.write((rpc(4, "tools/call", new JSONObject().put("name", "request_apps").put("arguments", new JSONObject()
                .put("packages", new JSONArray().put("com.android.settings")).put("taskTitle", "打开设置 ✓"))) + "\r\n").getBytes(StandardCharsets.UTF_8));
        input.write((rpc(6, "tools/call", null).put("params", new JSONArray().put(1)) + "\n").getBytes(StandardCharsets.UTF_8));
        input.write("\r\n   \n".getBytes(StandardCharsets.UTF_8));
        Executor second = new Executor();
        lines = serveLines(bridge(second, "full"), input.toByteArray(), PhoneMcpBridge.MAX_LINE_BYTES);
        check(lines.length == 9, "one answer per non-blank line: " + String.join(" | ", lines));
        for (int i = 1; i <= 6; i++) check(errorCode(lines[i]) == PhoneMcpBridge.PARSE_ERROR, "line " + i + " is a parse error: " + lines[i]);
        JSONObject apps = new JSONObject(lines[7]);
        check(apps.getInt("id") == 4 && !apps.getJSONObject("result").optBoolean("isError")
                && second.calls.size() == 1 && second.calls.get(0).contains("打开设置 ✓"), "a CRLF line with non-ASCII arguments runs intact: " + second.calls);
        check(errorCode(lines[8]) == PhoneMcpBridge.INVALID_PARAMS, "non-object params: an unknown-tool error, no crash: " + lines[8]);

        // Deep nesting: Android's org.json recurses without a depth limit; on a small stack it overflows.
        String deep = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"a\":" + "[".repeat(5000) + "]".repeat(5000) + "}}";
        AtomicReferenceHolder holder = new AtomicReferenceHolder();
        Thread small = new Thread(null, () -> {
            try {
                holder.value = bridge(new Executor(), "full").handleLine(deep);
            } catch (Throwable thrown) {
                holder.thrown = thrown;
            }
        }, "small-stack", 64 * 1024);
        small.start();
        small.join();
        check(holder.thrown == null && holder.value != null && errorCode(holder.value) == PhoneMcpBridge.PARSE_ERROR,
                "deep nesting is a parse error, never a thrown Error: " + holder.thrown);

        // A tool that throws an Error (an Android-missing API, a runaway recursion) fails that call only.
        List<Throwable> errors = List.of(new NoSuchFieldError("DISCARD"), new StackOverflowError());
        int[] calls = {0};
        PhoneMcpBridge broken = new PhoneMcpBridge(PhoneMcpBridgeTest::definitions, (tool, arguments, session) -> {
            int n = calls[0]++;
            if (n < errors.size()) {
                Throwable error = errors.get(n);
                if (error instanceof LinkageError) throw (LinkageError) error;
                throw (StackOverflowError) error;
            }
            return new JSONObject().put("success", true).put("contentItems", new JSONArray());
        }, null, new PhoneMcpBridge.Session("acp-err", "full", "turn-err"));
        broken.handle(rpc(0, "initialize", new JSONObject()));
        for (int i = 0; i < errors.size(); i++) {
            JSONObject result = call(broken, 20 + i, "tap", new JSONObject().put("x", 1).put("y", 2)).getJSONObject("result");
            check(result.getBoolean("isError") && "TOOL_FAILED".equals(result.getJSONObject("structuredContent").getString("code"))
                    && result.getJSONArray("content").getJSONObject(0).getString("text").contains(errors.get(i).getClass().getSimpleName()),
                    "an Error in the tool is one failed call: " + result);
        }
        check(!call(broken, 30, "tap", new JSONObject().put("x", 1).put("y", 2)).getJSONObject("result").optBoolean("isError"),
                "the next call on the same connection works");
        PhoneMcpBridge.Failures.take("turn-err", "tap");
        PhoneMcpBridge.Failures.take("turn-err", "tap");
    }

    private static final class AtomicReferenceHolder {
        volatile String value;
        volatile Throwable thrown;
    }

    public static void main(String[] args) throws Exception {
        failuresRecordedPerTurn();
        deviceInfoWithoutScreen();
        initialize();
        listTools();
        callTools();
        invalidArguments();
        permissions();
        protocolErrors();
        hostileInput();
        System.out.println("PhoneMcpBridgeTest passed " + checks + " checks");
    }
}
