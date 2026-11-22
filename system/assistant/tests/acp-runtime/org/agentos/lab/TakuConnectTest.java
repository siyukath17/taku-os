package org.agentos.lab;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Taku Connect: node discovery from runtime.json, probes, add/switch/remove, appearance, secrecy. */
public final class TakuConnectTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(TakuConnect connect, String input, String code) throws Exception {
        try {
            connect.control(new JSONObject(input));
            throw new AssertionError("accepted " + input);
        } catch (TakuConnect.Invalid invalid) {
            check(invalid.getMessage().equals(code), input + " -> " + code + " (got " + invalid.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        String token = "s3cret-token-value";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> reply(exchange, 200, "{\"ok\":true}"));
        server.createContext("/v1/capabilities", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + token).equals(auth)) { reply(exchange, 401, "{\"error\":{\"code\":\"unauthorized\"}}"); return; }
            reply(exchange, 200, "{\"runtime\":\"taku-runtime\",\"version\":\"0.1.0\",\"device\":\"mini.local\",\"os\":\"darwin\",\"arch\":\"arm64\","
                    + "\"appearance\":{\"deviceType\":\"mac_mini\",\"displayName\":\"HaiPro 的 Mac mini\"},\"mode\":\"endpoint\",\"engine\":null,"
                    + "\"tools\":[{\"name\":\"shell_exec\",\"description\":\"run\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"file_read\",\"description\":\"read\",\"inputSchema\":{}}],"
                    + "\"reserved_tools\":[\"screen_capture\"],\"exec\":{\"enabled\":true,\"allowed_roots\":[\"/Users/x\"],\"default_timeout_ms\":30000,\"max_timeout_ms\":300000,\"max_output_bytes\":65536},"
                    + "\"protocols\":[\"mcp/streamable-http\"],\"engines_detected\":[{\"name\":\"agy\",\"path\":\"/secret/path\"}]}");
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            File dir = Files.createTempDirectory("taku-connect").toFile();
            File runtime = new File(dir, "runtime.json"), store = new File(dir, "connect.json"), secrets = new File(dir, "secrets");
            secrets.mkdirs();
            File macToken = new File(secrets, "taku-macmini.token");
            Files.writeString(macToken.toPath(), token + "\n");
            JSONObject config = new JSONObject().put("default", "acp").put("keep", "me").put("acp", new JSONObject().put("binary", "/data/local/agentos/acp/bin/agy")
                    .put("mcpServers", new JSONArray()
                            .put(new JSONObject().put("name", "taku_memory").put("command", "/data/local/agentos/acp/bin/taku-memory").put("args", new JSONArray().put("serve-mcp")))
                            .put(new JSONObject().put("name", "taku_macmini").put("command", "/data/local/agentos/acp/bin/taku")
                                    .put("args", new JSONArray().put("mcp-stdio").put("--url").put(url).put("--token-file").put(macToken.getPath()))
                                    .put("env", new JSONArray().put(new JSONObject().put("name", "NO_PROXY").put("value", "127.0.0.1"))))));
            Files.writeString(runtime.toPath(), config.toString(2));
            TakuConnect connect = new TakuConnect(runtime, store, secrets, "/data/local/agentos/acp/bin/taku");

            // Discovery: only taku-runtime bridges are nodes; tokens are never echoed.
            JSONArray nodes = connect.control(new JSONObject()).getJSONArray("nodes");
            check(nodes.length() == 1 && "taku_macmini".equals(nodes.getJSONObject(0).getString("id")), "taku_memory is not a node, taku_macmini is");
            JSONObject mac = nodes.getJSONObject(0);
            check(mac.getBoolean("enabled") && mac.getBoolean("hasToken") && "loopback".equals(mac.getString("network")), "enabled node with a token: " + mac);
            check(!mac.toString().contains(token) && !mac.has("tokenFile"), "list never exposes the token or its path");

            // Probe: online, latency, capability summary without schemas or engine paths.
            JSONObject probe = connect.control(new JSONObject().put("action", "probe").put("id", "taku_macmini"));
            check("online".equals(probe.getString("status")) && probe.getLong("latencyMs") >= 0, "probe online with latency: " + probe);
            JSONObject caps = probe.getJSONObject("capabilities");
            check(caps.getJSONArray("tools").length() == 2 && "mac_mini".equals(caps.getJSONObject("appearance").getString("deviceType"))
                    && caps.getJSONObject("exec").getJSONArray("allowedRoots").length() == 1, "capability summary: " + caps);
            check(!probe.toString().contains("inputSchema") && !probe.toString().contains("/secret/path") && !probe.toString().contains(token), "summary drops schemas, paths, token");

            // test: wrong token is unauthorized, unreachable is offline.
            check("unauthorized".equals(connect.control(new JSONObject().put("action", "test").put("url", url).put("token", "nope")).getString("status")), "wrong token: unauthorized");
            check("offline".equals(connect.control(new JSONObject().put("action", "test").put("url", "http://127.0.0.1:1")).getString("status")), "closed port: offline");
            rejects(connect, "{\"action\":\"test\",\"url\":\"ftp://x\"}", "INVALID_URL");
            rejects(connect, "{\"action\":\"test\",\"url\":\"http://u:p@x:1\"}", "INVALID_URL");
            check("http://192.168.1.9:7878".equals(TakuConnect.url(" 192.168.1.9/ ")), "bare host gets scheme and default port");

            // Appearance: the Agent/Skill path for names and icons; a local image is inlined.
            File png = new File(dir, "mini.png");
            Files.write(png.toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            JSONObject styled = connect.control(new JSONObject().put("action", "appearance").put("id", "taku_macmini")
                    .put("displayName", "书房 Mac mini").put("deviceType", "mac_mini").put("icon", png.getPath())).getJSONObject("node");
            check("书房 Mac mini".equals(styled.getJSONObject("appearance").getString("displayName"))
                    && styled.getJSONObject("appearance").getString("icon").startsWith("data:image/png;base64,"), "appearance stored, file icon inlined: " + styled);
            rejects(connect, "{\"action\":\"appearance\",\"id\":\"taku_macmini\",\"deviceType\":\"toaster\"}", "INVALID_DEVICE_TYPE");
            rejects(connect, "{\"action\":\"appearance\",\"id\":\"taku_macmini\",\"icon\":\"javascript:alert(1)\"}", "INVALID_ICON");
            rejects(connect, "{\"action\":\"appearance\",\"id\":\"taku_nobody\",\"icon\":\"builtin:tv\"}", "NODE_NOT_FOUND");
            JSONObject cleared = connect.control(new JSONObject().put("action", "appearance").put("id", "taku_macmini").put("icon", JSONObject.NULL)).getJSONObject("node");
            check(!cleared.getJSONObject("appearance").has("icon") && cleared.getJSONObject("appearance").has("displayName"), "null clears only that field");

            // Switch off: the entry leaves runtime.json (agent stops seeing it) and comes back intact.
            String before = Files.readString(runtime.toPath());
            connect.control(new JSONObject().put("action", "setEnabled").put("id", "taku_macmini").put("enabled", false));
            JSONObject offConfig = new JSONObject(Files.readString(runtime.toPath()));
            check(offConfig.getJSONObject("acp").getJSONArray("mcpServers").length() == 1 && "me".equals(offConfig.getString("keep")), "disabled: removed from mcpServers, other keys kept");
            JSONObject off = connect.control(new JSONObject()).getJSONArray("nodes").getJSONObject(0);
            check(!off.getBoolean("enabled") && off.getBoolean("hasToken"), "disabled node still listed: " + off);
            check("online".equals(connect.control(new JSONObject().put("action", "probe").put("id", "taku_macmini")).getString("status")), "disabled node can still be probed");
            connect.control(new JSONObject().put("action", "setEnabled").put("id", "taku_macmini").put("enabled", true));
            check(new JSONObject(before).getJSONObject("acp").getJSONArray("mcpServers").getJSONObject(1).similar(
                    new JSONObject(Files.readString(runtime.toPath())).getJSONObject("acp").getJSONArray("mcpServers").getJSONObject(1)), "re-enabled entry identical");

            // Add: requires a working token; token goes to a root-only file, not runtime.json or answers.
            rejects(connect, "{\"action\":\"add\",\"name\":\"Bad\",\"url\":\"" + url + "\",\"token\":\"nope\"}", "NODE_UNAUTHORIZED");
            rejects(connect, "{\"action\":\"add\",\"name\":\"Gone\",\"url\":\"http://127.0.0.1:1\",\"token\":\"x\"}", "NODE_UNREACHABLE");
            rejects(connect, "{\"action\":\"add\",\"name\":\"\",\"url\":\"" + url + "\",\"token\":\"x\"}", "INVALID_NAME");
            rejects(connect, "{\"action\":\"add\",\"name\":\"NoToken\",\"url\":\"" + url + "\"}", "TOKEN_REQUIRED");
            JSONObject added = connect.control(new JSONObject().put("action", "add").put("name", "Living Room TV").put("url", url).put("token", token));
            JSONObject tv = added.getJSONObject("node");
            check("taku_living_room_tv".equals(tv.getString("id")) && tv.getBoolean("enabled") && tv.getBoolean("hasToken"), "added node: " + tv);
            check("mac_mini".equals(tv.getJSONObject("appearance").getString("deviceType")), "device type defaults to the advertised one");
            check(!added.toString().contains(token) && !Files.readString(runtime.toPath()).contains(token), "token absent from answer and runtime.json");
            File tvToken = new File(secrets, "taku_living_room_tv.token");
            check(tvToken.isFile() && token.equals(Files.readString(tvToken.toPath()).trim()), "token stored in secrets");
            check("taku_living_room_tv_2".equals(TakuConnect.newId("Living Room TV", java.util.Set.of("taku_living_room_tv"))), "ids are unique");
            check("taku_node".equals(TakuConnect.newId("客厅电视", java.util.Set.of())), "non-ascii names fall back to taku_node");

            // Remove: entry, presentation and the page's own token file go; hand-placed tokens stay.
            connect.control(new JSONObject().put("action", "remove").put("id", "taku_living_room_tv"));
            check(!tvToken.exists() && connect.control(new JSONObject()).getJSONArray("nodes").length() == 1, "removed with its token");
            check(macToken.isFile(), "hand-placed token untouched");

            check("lan".equals(TakuConnect.network("192.168.1.205")) && "tailscale".equals(TakuConnect.network("100.101.1.2"))
                    && "internet".equals(TakuConnect.network("8.8.8.8")) && "lan".equals(TakuConnect.network("mini.local")), "network classes");
        } finally {
            server.stop(0);
        }
        System.out.println("TakuConnectTest: " + checks + " checks passed");
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
