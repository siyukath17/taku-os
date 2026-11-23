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

            routes(connect, runtime, store, macToken, url, token);
        } finally {
            server.stop(0);
        }
        System.out.println("TakuConnectTest: " + checks + " checks passed");
    }

    /** Multi-route nodes: --urls parsing, the client's selection rule, the routes action, IPv6 routes. */
    private static void routes(TakuConnect connect, File runtime, File store, File macToken, String url, String token) throws Exception {
        // Parsing: --urls, --urls=, a comma-holding --url; order kept, duplicates and blanks dropped.
        String[][] forms = {{"--urls", "http://a:1,http://b:2"}, {"--urls=http://a:1, http://b:2,"}, {"--url", "http://a:1,http://b:2,http://a:1"}};
        for (String[] form : forms) {
            JSONArray args = new JSONArray().put("mcp-stdio");
            for (String part : form) args.put(part);
            JSONObject parsed = TakuConnect.parseServer(new JSONObject().put("name", "taku_x").put("command", "taku")
                    .put("args", args.put("--token-file").put("/data/local/agentos/t")));
            check("http://a:1".equals(parsed.getString("url")) && parsed.getJSONArray("urls").length() == 2
                    && "http://b:2".equals(parsed.getJSONArray("urls").getString(1)) && "/data/local/agentos/t".equals(parsed.getString("tokenFile")),
                    "parsed " + String.join(" ", form) + ": " + parsed);
        }

        // Selection: earliest-listed live route within 25 ms of the fastest, else the fastest; offline skipped.
        check(TakuConnect.select(new long[]{30, 6}) == 0, "first route within grace wins over a faster later one");
        check(TakuConnect.select(new long[]{31, 6}) == 0 && TakuConnect.select(new long[]{32, 6}) == 1 && TakuConnect.select(new long[]{40, 5, 9}) == 1, "grace is inclusive; beyond it the fastest wins (earliest among those within grace of it)");
        check(TakuConnect.select(new long[]{-1, 50, 12}) == 2 && TakuConnect.select(new long[]{-1, 50, 70}) == 1, "first offline: next by the same rule");
        check(TakuConnect.select(new long[]{-1, -1}) == -1 && TakuConnect.select(new long[0]) == -1, "nothing live");

        // IPv6 literals: kind ipv6 (Tailscale's ULA prefix stays tailscale), bracketed urls normalize, proxy bypassed.
        check("ipv6".equals(TakuConnect.network("fd7a::1")) && "ipv6".equals(TakuConnect.network("[2408:8207::5]"))
                && "tailscale".equals(TakuConnect.network("fd7a:115c:a1e0::9")) && "loopback".equals(TakuConnect.network("::1")), "ipv6 network classes");
        check("http://[fd7a::1]:7878".equals(TakuConnect.url("[fd7a::1]")) && "https://[fd7a::1]:9000".equals(TakuConnect.url("https://[fd7a::1]:9000/")),
                "ipv6 urls normalize");
        check("ipv6".equals(TakuConnect.routeKind("http://[fd7a::1]:7878")) && TakuConnect.direct("fd7a::1") && "fd7a::1".equals(TakuConnect.host("http://[fd7a::1]:7878")),
                "ipv6 route kind, direct, host");
        check("lan".equals(TakuConnect.routeKind(url)) && "internet".equals(TakuConnect.routeKind("http://frp.example.com:7000")), "kinds from hosts");

        // A second, slow runtime: same token, /healthz takes 120 ms.
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/healthz", exchange -> {
            try { Thread.sleep(120); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, "{\"ok\":true}");
        });
        slow.start();
        HttpServer fast = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fast.createContext("/healthz", exchange -> reply(exchange, 200, "{\"ok\":true}"));
        fast.createContext("/v1/capabilities", exchange -> reply(exchange, 200, "{\"runtime\":\"taku-runtime\",\"tools\":[]}"));
        fast.start();
        String slowUrl = "http://127.0.0.1:" + slow.getAddress().getPort(), fastUrl = "http://127.0.0.1:" + fast.getAddress().getPort();
        try {
            // routes: validated, stored, and written into the node's runtime.json entry as --urls.
            JSONObject updated = connect.control(new JSONObject().put("action", "routes").put("id", "taku_macmini").put("routes", new JSONArray()
                    .put(new JSONObject().put("label", "局域网").put("kind", "lan").put("url", "http://127.0.0.1:1"))
                    .put(new JSONObject().put("label", "Mac mini 转发").put("kind", "relay").put("url", url + "/"))
                    .put(new JSONObject().put("url", slowUrl)))).getJSONObject("node");
            JSONArray args = mac(runtime).getJSONArray("args");
            check(args.length() == 5 && "mcp-stdio".equals(args.getString(0)) && "--urls".equals(args.getString(1))
                    && ("http://127.0.0.1:1," + url + "," + slowUrl).equals(args.getString(2))
                    && "--token-file".equals(args.getString(3)) && macToken.getPath().equals(args.getString(4)), "args rewritten: " + args);
            check("http://127.0.0.1:1".equals(updated.getString("url")), "view url is the first route");
            JSONArray viewRoutes = updated.getJSONArray("routes");
            check(viewRoutes.length() == 3 && "局域网".equals(viewRoutes.getJSONObject(0).getString("label"))
                    && "relay".equals(viewRoutes.getJSONObject(1).getString("kind")) && "lan".equals(viewRoutes.getJSONObject(2).getString("kind"))
                    && "".equals(viewRoutes.getJSONObject(2).getString("label")), "view routes decorated and derived: " + viewRoutes);
            check(new JSONObject(Files.readString(store.toPath())).getJSONObject("nodes").getJSONObject("taku_macmini").getJSONArray("routes").length() == 3,
                    "routes stored in connect.json");
            check(!Files.readString(runtime.toPath()).contains(token), "token still absent from runtime.json");

            // Probe: concurrent, first route offline, the fast relay beats the slow one beyond the grace.
            long started = System.nanoTime();
            JSONObject probe = connect.control(new JSONObject().put("action", "probe").put("id", "taku_macmini"));
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            JSONArray probed = probe.getJSONArray("routes");
            check("online".equals(probe.getString("status")) && url.equals(probe.getString("url")) && probe.getJSONObject("route").getBoolean("selected")
                    && url.equals(probe.getJSONObject("route").getString("url")) && probe.has("capabilities"), "selected relay: " + probe);
            check(probed.length() == 3 && "offline".equals(probed.getJSONObject(0).getString("status")) && !probed.getJSONObject(0).getBoolean("selected")
                    && !probed.getJSONObject(0).has("latencyMs") && probed.getJSONObject(1).getBoolean("selected")
                    && "online".equals(probed.getJSONObject(2).getString("status")) && probed.getJSONObject(2).getLong("latencyMs") >= 100
                    && !probed.getJSONObject(2).getBoolean("selected") && "Mac mini 转发".equals(probed.getJSONObject(1).getString("label")), "per-route results: " + probed);
            check(probe.getLong("latencyMs") == probed.getJSONObject(1).getLong("latencyMs"), "top-level latency is the selected route's");
            check(elapsed < TakuConnect.PROBE_BUDGET_MS + 1500, "routes probed concurrently within budget: " + elapsed + "ms");

            // Earliest within grace: two fast routes → the first listed one.
            JSONObject tested = connect.control(new JSONObject().put("action", "test").put("urls", new JSONArray().put(fastUrl).put(url)).put("token", token));
            check(fastUrl.equals(tested.getJSONObject("route").getString("url")) && tested.getJSONArray("routes").length() == 2, "earliest within grace: " + tested);
            JSONObject comma = connect.control(new JSONObject().put("action", "test").put("url", slowUrl + "," + url).put("token", token));
            check(url.equals(comma.getJSONObject("route").getString("url")), "comma url: slow first route loses beyond grace: " + comma);

            // One route → --url; a disabled node's routes rewrite its parked copy, not runtime.json.
            connect.control(new JSONObject().put("action", "routes").put("id", "taku_macmini").put("routes", new JSONArray().put(new JSONObject().put("url", url))));
            args = mac(runtime).getJSONArray("args");
            check("--url".equals(args.getString(1)) && url.equals(args.getString(2)) && args.length() == 5, "single route keeps --url: " + args);
            connect.control(new JSONObject().put("action", "setEnabled").put("id", "taku_macmini").put("enabled", false));
            String parkedRuntime = Files.readString(runtime.toPath());
            connect.control(new JSONObject().put("action", "routes").put("id", "taku_macmini").put("routes", new JSONArray()
                    .put(new JSONObject().put("url", "[fd7a::1]")).put(new JSONObject().put("label", "Tailscale").put("url", url))));
            check(parkedRuntime.equals(Files.readString(runtime.toPath())), "disabled node: runtime.json untouched");
            JSONObject parked = new JSONObject(Files.readString(store.toPath())).getJSONObject("nodes").getJSONObject("taku_macmini").getJSONObject("disabled");
            check(("http://[fd7a::1]:7878," + url).equals(parked.getJSONArray("args").getString(2)), "parked copy rewritten: " + parked);
            JSONObject off = connect.control(new JSONObject()).getJSONArray("nodes").getJSONObject(0);
            check("ipv6".equals(off.getJSONArray("routes").getJSONObject(0).getString("kind")) && "ipv6".equals(off.getString("network"))
                    && "fd7a::1".equals(off.getString("host")), "ipv6 first route: " + off);
            connect.control(new JSONObject().put("action", "setEnabled").put("id", "taku_macmini").put("enabled", true));
            check(mac(runtime).getJSONArray("args").getString(2).startsWith("http://[fd7a::1]:7878,"), "re-enabled with its routes");
            String noProxy = mac(runtime).getJSONArray("env").getJSONObject(0).getString("value");
            check(noProxy.contains("fd7a::1") && noProxy.contains("127.0.0.1"), "direct route hosts join NO_PROXY: " + noProxy);

            // Validation.
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[]}", "INVALID_ROUTES");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[{\"url\":\"a\",\"kind\":\"moon\"}]}", "INVALID_ROUTE_KIND");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[{\"url\":\"a\",\"label\":\"" + "x".repeat(33) + "\"}]}", "INVALID_ROUTE_LABEL");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[{\"url\":\"a\"},{\"url\":\"http://a:7878/\"}]}", "DUPLICATE_ROUTE");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[{\"url\":\"ftp://a\"}]}", "INVALID_URL");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[{\"url\":\"a\",\"token\":\"x\"}]}", "INVALID_ROUTES");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_nobody\",\"routes\":[{\"url\":\"a\"}]}", "NODE_NOT_FOUND");
            StringBuilder nine = new StringBuilder();
            for (int i = 1; i <= 9; i++) nine.append(i > 1 ? "," : "").append("{\"url\":\"h").append(i).append("\"}");
            rejects(connect, "{\"action\":\"routes\",\"id\":\"taku_macmini\",\"routes\":[" + nine + "]}", "TOO_MANY_ROUTES");

            // add with routes: probes them all, stores them, writes --urls.
            JSONObject added = connect.control(new JSONObject().put("action", "add").put("name", "Desk").put("token", token)
                    .put("routes", new JSONArray().put(new JSONObject().put("label", "家里").put("url", url)).put(new JSONObject().put("kind", "relay").put("url", fastUrl))));
            JSONObject desk = added.getJSONObject("node");
            check(desk.getJSONArray("routes").length() == 2 && "家里".equals(desk.getJSONArray("routes").getJSONObject(0).getString("label"))
                    && url.equals(added.getJSONObject("probe").getJSONObject("route").getString("url")), "add with routes: " + added);
            connect.control(new JSONObject().put("action", "remove").put("id", desk.getString("id")));
        } finally {
            slow.stop(0);
            fast.stop(0);
        }
    }

    private static JSONObject mac(File runtime) throws Exception {
        JSONArray servers = new JSONObject(Files.readString(runtime.toPath())).getJSONObject("acp").getJSONArray("mcpServers");
        for (int i = 0; i < servers.length(); i++) if ("taku_macmini".equals(servers.getJSONObject(i).getString("name"))) return servers.getJSONObject(i);
        throw new AssertionError("taku_macmini not in runtime.json");
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
