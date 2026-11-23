package org.agentos.lab;

import org.agentos.assistant.TakuConnectContract;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * The app gateway ({@code TakuConnectContract}, used by the Agent UI gateway and the open-access
 * provider) against the broker ({@code TakuConnect}): every request the gateway builds is fed straight
 * into a real broker over a temp runtime.json/connect.json, so a field the gateway forgets to pass or
 * an error it swallows shows up here instead of on a device.
 */
public final class TakuConnectGatewayContractTest {
    private static final String CLIENT = "/data/local/agentos/acp/bin/taku";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        fieldsMatchBroker();
        appearanceShortNameRoundTrip();
        brokerErrorsPassThrough();
        gatewayOnlyRejections();
        credentialHandling();
        System.out.println("TakuConnectGatewayContractTest: " + checks + " checks passed");
    }

    // ---- fixture ----

    static final class Env {
        final File runtime, store, secrets, token;
        final TakuConnect connect;

        Env(String name) throws Exception {
            File dir = Files.createTempDirectory("taku-connect-gateway-" + name).toFile();
            runtime = new File(dir, "runtime.json");
            store = new File(dir, "connect.json");
            secrets = new File(dir, "secrets");
            check(secrets.mkdirs(), "secrets dir");
            token = new File(secrets, "taku_mini.token");
            Files.writeString(token.toPath(), "g4teway-token\n");
            JSONObject server = new JSONObject().put("name", "taku_mini").put("command", CLIENT)
                    .put("args", new JSONArray().put("mcp-stdio").put("--url").put("http://192.168.1.5:7878")
                            .put("--token-file").put(token.getPath())).put("env", new JSONArray());
            Files.writeString(runtime.toPath(), new JSONObject().put("acp", new JSONObject()
                    .put("mcpServers", new JSONArray().put(server))).toString(2));
            JSONObject demo = new JSONObject().put("demo", true).put("enabled", true)
                    .put("appearance", new JSONObject().put("displayName", "Tesla Model Y").put("shortName", "Tesla").put("deviceType", "car"));
            Files.writeString(store.toPath(), new JSONObject().put("schema", TakuConnect.SCHEMA)
                    .put("nodes", new JSONObject().put("demo_tesla", demo)).toString(2));
            connect = new TakuConnect(runtime, store, secrets, CLIENT);
        }

        /** The gateway's request, run by the broker exactly as NativeBridge op {@code connect} does. */
        JSONObject call(String method, JSONObject input) throws Exception {
            JSONObject request = TakuConnectContract.request(method, input, null);
            check("connect".equals(request.getString("op")), method + " op");
            return connect.control(request);
        }

        JSONObject node(String id) throws Exception {
            JSONArray nodes = call("connect/list", new JSONObject()).getJSONArray("nodes");
            for (int i = 0; i < nodes.length(); i++) if (id.equals(nodes.getJSONObject(i).getString("id"))) return nodes.getJSONObject(i);
            throw new AssertionError("no node " + id);
        }

        JSONObject appearance(String id) throws Exception {
            JSONObject appearance = node(id).optJSONObject("appearance");
            return appearance == null ? new JSONObject() : appearance;
        }

        /** The error code a gateway+broker round trip fails with ("" when it succeeds). */
        String error(String method, JSONObject input) {
            try {
                call(method, input);
                return "";
            } catch (TakuConnect.Invalid broker) {
                return "broker:" + broker.getMessage();
            } catch (IOException gateway) {
                return "gateway:" + gateway.getMessage();
            } catch (Exception other) {
                return "other:" + other;
            }
        }
    }

    // ---- cases ----

    /** The gateway whitelist and the broker's appearance keys agree (the Agent UI method list: AgentGatewayProtocolTest). */
    static void fieldsMatchBroker() throws Exception {
        check(TakuConnectContract.APPEARANCE_FIELDS.equals(TakuConnect.APPEARANCE_FIELDS),
                "gateway appearance fields " + TakuConnectContract.APPEARANCE_FIELDS + " != broker " + TakuConnect.APPEARANCE_FIELDS);
        Set<String> appearance = TakuConnectContract.FIELDS.get("connect/appearance");
        check(appearance.contains("id") && appearance.containsAll(TakuConnect.APPEARANCE_FIELDS) && appearance.size() == 5,
                "connect/appearance whitelist " + appearance);
        check(TakuConnectContract.ACTIONS.equals(Set.of("connect/list", "connect/probe", "connect/test", "connect/add",
                "connect/remove", "connect/setEnabled", "connect/appearance", "connect/routes")), "connect actions " + TakuConnectContract.ACTIONS);
        // Every action reaches a broker handler (none answers UNKNOWN_ACTION).
        Env env = new Env("actions");
        for (String method : TakuConnectContract.ACTIONS) {
            if (method.equals("connect/test") || method.equals("connect/add")) continue; // network probes
            JSONObject input = new JSONObject();
            if (TakuConnectContract.FIELDS.get(method).contains("id")) input.put("id", "demo_tesla");
            if (method.equals("connect/setEnabled")) input.put("enabled", true);
            if (method.equals("connect/appearance")) input.put("shortName", "Tesla");
            String error = env.error(method, input);
            check(!error.contains("UNKNOWN"), method + " reaches the broker (" + error + ")");
        }
    }

    /** Agent/front end can set, replace and clear {@code shortName} through the gateway, for real and demo nodes. */
    static void appearanceShortNameRoundTrip() throws Exception {
        Env env = new Env("short-name");
        for (String id : List.of("taku_mini", "demo_tesla")) {
            env.call("connect/appearance", new JSONObject().put("id", id).put("displayName", "书房 Mac mini 2024").put("shortName", "Mac mini"));
            check("Mac mini".equals(env.appearance(id).optString("shortName")), id + " shortName set: " + env.appearance(id));
            check("书房 Mac mini 2024".equals(env.appearance(id).optString("displayName")), id + " displayName kept alongside");
            env.call("connect/appearance", new JSONObject().put("id", id).put("shortName", "  Mini  "));
            check("Mini".equals(env.appearance(id).optString("shortName")), id + " shortName trimmed and replaced");
            env.call("connect/appearance", new JSONObject().put("id", id).put("shortName", JSONObject.NULL));
            check(!env.appearance(id).has("shortName"), id + " shortName cleared by null");
            check("书房 Mac mini 2024".equals(env.appearance(id).optString("displayName")), id + " clearing shortName leaves displayName");
            env.call("connect/appearance", new JSONObject().put("id", id).put("shortName", "Mini"));
            env.call("connect/appearance", new JSONObject().put("id", id).put("shortName", ""));
            check(!env.appearance(id).has("shortName"), id + " shortName cleared by \"\"");
            // 16 characters is the documented ceiling; the broker's code comes back unchanged.
            env.call("connect/appearance", new JSONObject().put("id", id).put("shortName", "1234567890abcdef"));
            check("1234567890abcdef".equals(env.appearance(id).optString("shortName")), id + " 16-char shortName accepted");
            check("broker:INVALID_NAME".equals(env.error("connect/appearance", new JSONObject().put("id", id).put("shortName", "1234567890abcdefg"))),
                    id + " 17-char shortName -> INVALID_NAME");
            check("broker:INVALID_NAME".equals(env.error("connect/appearance", new JSONObject().put("id", id).put("shortName", 7))),
                    id + " non-string shortName -> INVALID_NAME");
            check("1234567890abcdef".equals(env.appearance(id).optString("shortName")), id + " rejected update leaves the stored shortName");
        }
        // Demo appearance edits stay in connect.json; runtime.json still has only the real node.
        check(!Files.readString(env.runtime.toPath()).contains("demo_"), "demo appearance never reaches runtime.json");
    }

    /** Errors the broker owns reach the caller with the broker's code, not a gateway INVALID_ARGUMENT. */
    static void brokerErrorsPassThrough() throws Exception {
        Env env = new Env("errors");
        check("broker:DEMO_NODE".equals(env.error("connect/routes", new JSONObject().put("id", "demo_tesla"))),
                "routes on a demo node without routes -> DEMO_NODE");
        check("broker:DEMO_NODE".equals(env.error("connect/routes", new JSONObject().put("id", "demo_tesla").put("routes", "x"))),
                "routes on a demo node with bad routes -> DEMO_NODE");
        check("broker:DEMO_NODE".equals(env.error("connect/routes", new JSONObject().put("id", "demo_tesla")
                .put("routes", new JSONArray().put(new JSONObject().put("url", "192.168.1.9:7878"))))), "valid routes on a demo node -> DEMO_NODE");
        check("broker:INVALID_ROUTES".equals(env.error("connect/routes", new JSONObject().put("id", "taku_mini"))), "missing routes -> INVALID_ROUTES");
        check("broker:INVALID_ROUTES".equals(env.error("connect/routes", new JSONObject().put("id", "taku_mini").put("routes", "192.168.1.9:7878"))),
                "string routes -> INVALID_ROUTES");
        check("broker:INVALID_ROUTES".equals(env.error("connect/routes", new JSONObject().put("id", "taku_mini").put("routes", new JSONArray()))),
                "empty routes -> INVALID_ROUTES");
        JSONArray nine = new JSONArray();
        for (int i = 1; i <= 9; i++) nine.put(new JSONObject().put("url", "192.168.1." + i + ":7878"));
        check("broker:TOO_MANY_ROUTES".equals(env.error("connect/routes", new JSONObject().put("id", "taku_mini").put("routes", nine))),
                "nine routes -> TOO_MANY_ROUTES");
        check("broker:NODE_NOT_FOUND".equals(env.error("connect/appearance", new JSONObject().put("id", "taku_gone").put("shortName", "x"))),
                "unknown id -> NODE_NOT_FOUND");
        check("broker:NOTHING_TO_UPDATE".equals(env.error("connect/appearance", new JSONObject().put("id", "taku_mini"))),
                "appearance with only id -> NOTHING_TO_UPDATE");
        check("broker:INVALID_DEVICE_TYPE".equals(env.error("connect/appearance", new JSONObject().put("id", "taku_mini").put("deviceType", "toaster"))),
                "bad deviceType -> INVALID_DEVICE_TYPE");
        check("broker:INVALID_ICON".equals(env.error("connect/appearance", new JSONObject().put("id", "taku_mini").put("icon", "builtin:toaster"))),
                "bad icon -> INVALID_ICON");
        check("broker:INVALID_URL".equals(env.error("connect/test", new JSONObject().put("url", "ftp://x"))), "bad test url -> INVALID_URL");

        // A valid routes call through the gateway rewrites the real node's mcpServers entry.
        env.call("connect/routes", new JSONObject().put("id", "taku_mini").put("routes", new JSONArray()
                .put(new JSONObject().put("label", "局域网").put("url", "192.168.1.5:7878"))
                .put(new JSONObject().put("kind", "tailscale").put("url", "100.101.1.2:7878"))));
        JSONArray routes = env.node("taku_mini").getJSONArray("routes");
        check(routes.length() == 2 && "局域网".equals(routes.getJSONObject(0).getString("label"))
                && "tailscale".equals(routes.getJSONObject(1).getString("kind")), "routes stored with labels/kinds: " + routes);
        check(Files.readString(env.runtime.toPath()).contains("http://192.168.1.5:7878,http://100.101.1.2:7878"), "runtime.json rewritten to --urls");

        // Demo switch/remove go through the gateway too.
        check(!env.call("connect/setEnabled", new JSONObject().put("id", "demo_tesla").put("enabled", false)).getJSONObject("node").getBoolean("enabled"),
                "demo setEnabled via gateway");
        check("demo_tesla".equals(env.call("connect/remove", new JSONObject().put("id", "demo_tesla")).getString("removed")), "demo remove via gateway");
        check(!Files.readString(env.store.toPath()).contains("demo_tesla"), "demo entry gone from connect.json");
    }

    /** What only the gateway can reject: unknown fields, a bad id/enabled, unknown methods. */
    static void gatewayOnlyRejections() throws Exception {
        Env env = new Env("gateway");
        check("gateway:INVALID_ARGUMENT".equals(env.error("connect/appearance", new JSONObject().put("id", "taku_mini").put("nickname", "x"))),
                "unknown appearance field -> INVALID_ARGUMENT");
        check("gateway:INVALID_ARGUMENT".equals(env.error("connect/add", new JSONObject().put("name", "x").put("shortName", "x"))),
                "add does not take shortName (set it with appearance)");
        check("gateway:INVALID_ARGUMENT".equals(env.error("connect/list", new JSONObject().put("id", "taku_mini"))), "list takes no fields");
        for (String method : List.of("connect/probe", "connect/remove", "connect/appearance", "connect/routes", "connect/setEnabled")) {
            check("gateway:INVALID_ARGUMENT".equals(env.error(method, new JSONObject())), method + " without id -> INVALID_ARGUMENT");
            check("gateway:INVALID_ARGUMENT".equals(env.error(method, new JSONObject().put("id", 7))), method + " numeric id -> INVALID_ARGUMENT");
            check("gateway:INVALID_ARGUMENT".equals(env.error(method, new JSONObject().put("id", "  "))), method + " blank id -> INVALID_ARGUMENT");
            check("gateway:INVALID_ARGUMENT".equals(env.error(method, new JSONObject().put("id", JSONObject.NULL))), method + " null id -> INVALID_ARGUMENT");
        }
        check("gateway:INVALID_ARGUMENT".equals(env.error("connect/setEnabled", new JSONObject().put("id", "taku_mini").put("enabled", "false"))),
                "string enabled -> INVALID_ARGUMENT");
        check("gateway:INVALID_ARGUMENT".equals(env.error("connect/setEnabled", new JSONObject().put("id", "taku_mini"))), "missing enabled -> INVALID_ARGUMENT");
        check("gateway:UNKNOWN_METHOD".equals(env.error("connect/rename", new JSONObject())), "unknown connect method");
        check(Files.readString(env.runtime.toPath()).contains("taku_mini"), "rejected calls leave runtime.json alone");
    }

    /** The native credential field becomes {@code token} for test/add only, and never mixes with an inline token. */
    static void credentialHandling() throws Exception {
        JSONObject input = new JSONObject().put("url", "192.168.1.30:7878");
        JSONObject request = TakuConnectContract.request("connect/test", input, "  s3cret \n");
        check("s3cret".equals(request.getString("token")) && "test".equals(request.getString("action")), "credential trimmed into token");
        check(!input.has("token") && !input.has("op") && !input.has("action"), "caller input is not mutated");
        check("add".equals(TakuConnectContract.request("connect/add", new JSONObject().put("name", "TV").put("url", "192.168.1.30:7878"), "k")
                .getString("action")), "add action");
        for (String key : List.of("token", "tokenFile")) {
            try {
                TakuConnectContract.request("connect/add", new JSONObject().put("name", "TV").put(key, "x"), "k");
                throw new AssertionError("ambiguous " + key + " accepted");
            } catch (IOException expected) {
                check("KEY_SOURCE_AMBIGUOUS".equals(expected.getMessage()), key + " + credential -> KEY_SOURCE_AMBIGUOUS");
            }
        }
        JSONObject plain = TakuConnectContract.request("connect/test", new JSONObject().put("url", "x").put("token", "inline"), null);
        check("inline".equals(plain.getString("token")), "inline token passes through without a credential");
        JSONObject appearance = TakuConnectContract.request("connect/appearance", new JSONObject().put("id", "taku_mini").put("shortName", JSONObject.NULL), "k");
        check(!appearance.has("token") && appearance.isNull("shortName") && "appearance".equals(appearance.getString("action")),
                "credential never added outside test/add; null shortName survives the copy");
    }
}
