package org.agentos.lab;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * Taku Connect invariants that later iterations must not break:
 * <ul>
 *   <li>demo nodes ({@code demo_*} + {@code "demo":true}) are read, switched and removed in connect.json only;</li>
 *   <li>no {@code demo_*} / {@code "demo":true} entry is ever written into runtime.json {@code acp.mcpServers},
 *       even when a hand-edited connect.json tries to smuggle one in as a parked server;</li>
 *   <li>{@code routes[]}: parsing, single-{@code url} back-compatibility, preferred-route switching and
 *       lossless round trips through connect.json and the mcpServers entry;</li>
 *   <li>malformed connect.json / runtime.json pieces degrade per entry without hiding the real nodes.</li>
 * </ul>
 * Everything here is offline except one loopback runtime for {@code add}.
 */
public final class TakuConnectInvariantsTest {
    private static final Pattern DEMO = Pattern.compile("demo_.*");
    private static final String CLIENT = "/data/local/agentos/acp/bin/taku";
    private static final String TOKEN = "inv4riant-token";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(TakuConnect connect, JSONObject input, String code) throws Exception {
        try {
            connect.control(input);
            throw new AssertionError("accepted " + input);
        } catch (TakuConnect.Invalid invalid) {
            check(invalid.getMessage().equals(code), input + " -> " + code + " (got " + invalid.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        demoLifecycle();
        demoNeverReachesRuntime();
        routeParsing();
        routeReconcileAndBackCompat();
        routeSwitchRoundTrip();
        malformedInputs();
        System.out.println("TakuConnectInvariantsTest: " + checks + " checks passed");
    }

    // ---- fixtures ----

    static final class Env {
        final File dir, runtime, store, secrets, token;
        final TakuConnect connect;

        Env(String name) throws Exception {
            dir = Files.createTempDirectory("taku-connect-" + name).toFile();
            runtime = new File(dir, "runtime.json");
            store = new File(dir, "connect.json");
            secrets = new File(dir, "secrets");
            secrets.mkdirs();
            token = new File(secrets, "taku_mini.token");
            Files.writeString(token.toPath(), TOKEN + "\n");
            connect = fresh();
        }

        TakuConnect fresh() { return new TakuConnect(runtime, store, secrets, CLIENT); }

        void runtime(JSONArray servers) throws Exception {
            Files.writeString(runtime.toPath(), new JSONObject().put("default", "acp").put("keep", "me")
                    .put("acp", new JSONObject().put("binary", "/data/local/agentos/acp/bin/agy").put("mcpServers", servers)).toString(2));
        }

        void store(JSONObject nodes) throws Exception {
            Files.writeString(store.toPath(), new JSONObject().put("schema", TakuConnect.SCHEMA).put("nodes", nodes).toString(2));
        }

        JSONArray servers() throws Exception {
            return new JSONObject(Files.readString(runtime.toPath())).getJSONObject("acp").getJSONArray("mcpServers");
        }

        JSONObject server(String name) throws Exception {
            JSONArray servers = servers();
            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.optJSONObject(i);
                if (server != null && name.equals(server.optString("name"))) return server;
            }
            return null;
        }

        JSONObject saved() throws Exception {
            return new JSONObject(Files.readString(store.toPath())).getJSONObject("nodes");
        }

        JSONArray nodes() throws Exception {
            return connect.control(new JSONObject()).getJSONArray("nodes");
        }

        JSONObject node(String id) throws Exception {
            JSONArray nodes = nodes();
            for (int i = 0; i < nodes.length(); i++) if (id.equals(nodes.getJSONObject(i).getString("id"))) return nodes.getJSONObject(i);
            return null;
        }

        JSONObject act(String action, String id) throws Exception {
            return connect.control(new JSONObject().put("action", action).put("id", id));
        }

        JSONObject setEnabled(String id, boolean on) throws Exception {
            return connect.control(new JSONObject().put("action", "setEnabled").put("id", id).put("enabled", on));
        }

        /** The key invariant: no mcpServers entry is a demo node, by name or by content. */
        void assertNoDemoInRuntime(String when) throws Exception {
            if (!runtime.isFile()) return;
            JSONArray servers = servers();
            JSONObject saved = store.isFile() ? saved() : new JSONObject();
            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.optJSONObject(i);
                if (server == null) continue;
                String name = server.optString("name");
                check(!DEMO.matcher(name).matches(), when + ": demo_* server in runtime.json: " + server);
                JSONObject entry = saved.optJSONObject(name);
                check(entry == null || !entry.optBoolean("demo"), when + ": \"demo\":true node in runtime.json: " + server);
                check(!server.toString().contains("\"demo\""), when + ": demo fields leaked into runtime.json: " + server);
            }
        }
    }

    static JSONObject takuServer(String name, String urlArgs, String tokenFile) throws Exception {
        JSONArray args = new JSONArray().put("mcp-stdio");
        for (String part : urlArgs.split(" ")) args.put(part);
        return new JSONObject().put("name", name).put("command", CLIENT)
                .put("args", args.put("--token-file").put(tokenFile)).put("env", new JSONArray());
    }

    static JSONObject demo(String displayName, String deviceType) throws Exception {
        return new JSONObject().put("demo", true)
                .put("appearance", new JSONObject().put("displayName", displayName).put("deviceType", deviceType));
    }

    static JSONObject memoryServer() throws Exception {
        return new JSONObject().put("name", "taku_memory").put("command", "/data/local/agentos/acp/bin/taku-memory").put("args", new JSONArray().put("serve-mcp"));
    }

    // ---- demo nodes ----

    /** Read, switch (persisted across instances), appearance and delete — connect.json only. */
    static void demoLifecycle() throws Exception {
        Env env = new Env("demo");
        env.runtime(new JSONArray().put(memoryServer()).put(takuServer("taku_mini", "--url http://192.168.1.205:7878", env.token.getPath())));
        JSONObject car = demo("Tesla Model Y", "car").put("demoStatus", "  82% · 410 km  ").put("note", "kept");
        JSONObject tv = demo("客厅电视", "tv").put("enabled", false).put("demoStatus", "x".repeat(60));
        env.store(new JSONObject().put("demo_car", car).put("demo_tv", tv).put("demo_bare", new JSONObject().put("demo", true)));
        String runtimeBefore = Files.readString(env.runtime.toPath());

        // Read: real nodes first, then demo nodes; defaults and bounds applied.
        JSONArray nodes = env.nodes();
        check(nodes.length() == 4 && "taku_mini".equals(nodes.getJSONObject(0).getString("id")), "real node first, three demos after: " + nodes);
        JSONObject carView = env.node("demo_car");
        check(carView.getBoolean("demo") && carView.getBoolean("enabled") && !carView.getBoolean("hasToken")
                && carView.getString("url").isEmpty() && carView.getString("host").isEmpty() && carView.getJSONArray("routes").length() == 0
                && "internet".equals(carView.getString("network")), "demo view: on by default, no url/routes/token: " + carView);
        check("82% · 410 km".equals(carView.getString("demoStatus")) && "car".equals(carView.getJSONObject("appearance").getString("deviceType")),
                "demoStatus trimmed, appearance passed through: " + carView);
        check(!carView.has("note") && !carView.has("tokenFile") && !carView.has("disabled"), "only view fields leave the broker: " + carView);
        JSONObject tvView = env.node("demo_tv");
        check(!tvView.getBoolean("enabled") && tvView.getString("demoStatus").length() == TakuConnect.MAX_DEMO_STATUS_CHARS, "stored enabled:false, status bounded: " + tvView);
        JSONObject bare = env.node("demo_bare");
        check(bare.getJSONObject("appearance").length() == 0 && !bare.has("demoStatus") && bare.getBoolean("enabled"), "bare demo entry: " + bare);

        // Probe: answered locally from the switch, never via the network.
        check("online".equals(env.act("probe", "demo_car").getString("status")) && "offline".equals(env.act("probe", "demo_tv").getString("status")),
                "demo probes follow enabled");

        // Switch: persisted in connect.json, visible to a fresh instance, other fields untouched.
        JSONObject off = env.setEnabled("demo_car", false).getJSONObject("node");
        check(!off.getBoolean("enabled") && !env.saved().getJSONObject("demo_car").getBoolean("enabled"), "switch off persisted: " + env.saved());
        check(!nodeIn(env.fresh().control(new JSONObject()).getJSONArray("nodes"), "demo_car").getBoolean("enabled"), "a new broker instance reads it back");
        JSONObject savedCar = env.saved().getJSONObject("demo_car");
        check("kept".equals(savedCar.getString("note")) && savedCar.getBoolean("demo") && "Tesla Model Y".equals(savedCar.getJSONObject("appearance").getString("displayName"))
                && "  82% · 410 km  ".equals(savedCar.getString("demoStatus")), "switch keeps every other stored field: " + savedCar);
        String storeBefore = Files.readString(env.store.toPath());
        env.setEnabled("demo_car", false);
        check(storeBefore.equals(Files.readString(env.store.toPath())), "same-state switch writes nothing");
        check(env.setEnabled("demo_tv", true).getJSONObject("node").getBoolean("enabled") && env.saved().getJSONObject("demo_tv").getBoolean("enabled"), "switch on persisted");
        check("online".equals(env.act("probe", "demo_tv").getString("status")), "switched-on demo probes online");

        // Appearance and routes on a demo.
        JSONObject renamed = env.connect.control(new JSONObject().put("action", "appearance").put("id", "demo_car").put("shortName", "Tesla").put("icon", "builtin:car"))
                .getJSONObject("node");
        check("Tesla".equals(renamed.getJSONObject("appearance").getString("shortName")) && renamed.getBoolean("demo"), "demo appearance: " + renamed);
        rejects(env.connect, new JSONObject().put("action", "routes").put("id", "demo_car")
                .put("routes", new JSONArray().put(new JSONObject().put("url", "http://10.0.0.2:7878"))), "DEMO_NODE");
        check(!env.saved().getJSONObject("demo_car").has("routes"), "rejected routes leave no trace");

        // Delete: only that entry; the real node and other demos stay.
        check("demo_car".equals(env.act("remove", "demo_car").getString("removed")), "demo removed");
        check(!env.saved().has("demo_car") && env.saved().has("demo_tv") && env.node("demo_car") == null && env.node("taku_mini") != null, "remove is per entry: " + env.saved());
        rejects(env.connect, new JSONObject().put("action", "remove").put("id", "demo_car"), "NODE_NOT_FOUND");
        rejects(env.connect, new JSONObject().put("action", "setEnabled").put("id", "demo_car").put("enabled", true), "NODE_NOT_FOUND");
        check(runtimeBefore.equals(Files.readString(env.runtime.toPath())), "no demo action touched runtime.json");
        check(env.token.isFile(), "demo remove never deletes token files");

        // Not demos: missing flag, flag false, bad id shapes, demo flag on a taku_* id.
        env.store(new JSONObject().put("demo_noflag", new JSONObject().put("appearance", new JSONObject()))
                .put("demo_false", new JSONObject().put("demo", false)).put("demo_Upper", new JSONObject().put("demo", true))
                .put("demo_" + "a".repeat(41), new JSONObject().put("demo", true)).put("demo-dash", new JSONObject().put("demo", true))
                .put("taku_flagged", new JSONObject().put("demo", true)).put("demo_ok", new JSONObject().put("demo", true)));
        JSONArray filtered = env.nodes();
        check(filtered.length() == 2 && nodeIn(filtered, "demo_ok") != null && nodeIn(filtered, "taku_mini") != null, "only well-formed demo ids with demo:true: " + filtered);
        check(!TakuConnect.isDemo("demo_x", new JSONObject().put("demo", "yes")) && !TakuConnect.isDemo("demo_x", new JSONObject().put("demo", 1))
                && TakuConnect.isDemo("demo_x", new JSONObject().put("demo", true)), "only a truthy demo flag marks a demo");
    }

    /** No sequence of actions — including hostile hand-edited entries — puts a demo into mcpServers. */
    static void demoNeverReachesRuntime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> reply(exchange, 200, "{\"ok\":true}"));
        server.createContext("/v1/capabilities", exchange -> {
            boolean ok = ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, ok ? 200 : 401, ok ? "{\"runtime\":\"taku-runtime\",\"tools\":[],\"appearance\":{\"deviceType\":\"car\"}}" : "{}");
        });
        server.start();
        String live = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Env env = new Env("invariant");
            env.runtime(new JSONArray().put(memoryServer()).put(takuServer("taku_mini", "--url " + live, env.token.getPath())));
            // Hostile entries: a demo carrying a parked taku server under its own and under a real-looking name,
            // a taku_* key whose parked server is named demo_*, and a demo:true taku_* key with a parked server.
            JSONObject trojan = demo("Trojan", "car").put("enabled", false)
                    .put("disabled", takuServer("demo_trojan", "--url http://10.0.0.66:7878", env.token.getPath()));
            JSONObject alias = demo("Alias", "tv").put("disabled", takuServer("taku_alias", "--url http://10.0.0.67:7878", env.token.getPath()));
            JSONObject renamed = new JSONObject().put("disabled", takuServer("demo_renamed", "--url http://10.0.0.68:7878", env.token.getPath()));
            JSONObject flagged = new JSONObject().put("demo", true).put("disabled", takuServer("taku_flagged", "--url http://10.0.0.69:7878", env.token.getPath()));
            env.store(new JSONObject().put("demo_car", demo("Tesla", "car")).put("demo_trojan", trojan).put("demo_alias", alias)
                    .put("taku_renamed", renamed).put("taku_flagged", flagged));
            env.assertNoDemoInRuntime("start");

            JSONArray listed = env.nodes();
            for (int i = 0; i < listed.length(); i++) {
                JSONObject node = listed.getJSONObject(i);
                boolean demoish = DEMO.matcher(node.getString("id")).matches() || node.optBoolean("demo");
                check(!demoish || (node.optBoolean("demo") && node.getString("url").isEmpty()), "a demo-ish id is only ever shown as a demo: " + node);
                check(!"taku_flagged".equals(node.getString("id")) && !"taku_renamed".equals(node.getString("id")) && !"taku_alias".equals(node.getString("id")),
                        "smuggled parked servers are not switchable nodes: " + node);
            }

            // Every demo action, every hostile id, both directions.
            for (String id : new String[]{"demo_car", "demo_trojan", "demo_alias", "taku_renamed", "taku_flagged", "demo_renamed", "taku_alias"}) {
                for (boolean on : new boolean[]{true, false, true}) {
                    try {
                        env.setEnabled(id, on);
                    } catch (TakuConnect.Invalid expected) {
                        check("NODE_NOT_FOUND".equals(expected.getMessage()), id + " setEnabled: " + expected.getMessage());
                    }
                    env.assertNoDemoInRuntime("setEnabled " + id + "=" + on);
                }
                try {
                    env.connect.control(new JSONObject().put("action", "routes").put("id", id)
                            .put("routes", new JSONArray().put(new JSONObject().put("url", "http://10.9.9.9:7878"))));
                } catch (TakuConnect.Invalid expected) {
                    check("DEMO_NODE".equals(expected.getMessage()) || "NODE_NOT_FOUND".equals(expected.getMessage()), id + " routes: " + expected.getMessage());
                }
                env.assertNoDemoInRuntime("routes " + id);
                try {
                    env.connect.control(new JSONObject().put("action", "appearance").put("id", id).put("displayName", "X"));
                } catch (TakuConnect.Invalid expected) {
                    check("NODE_NOT_FOUND".equals(expected.getMessage()), id + " appearance: " + expected.getMessage());
                }
                env.assertNoDemoInRuntime("appearance " + id);
            }

            // Real-node writes rewrite mcpServers wholesale: still no demo.
            env.setEnabled("taku_mini", false);
            env.assertNoDemoInRuntime("real off");
            env.setEnabled("taku_mini", true);
            env.assertNoDemoInRuntime("real on");
            env.connect.control(new JSONObject().put("action", "routes").put("id", "taku_mini").put("routes", new JSONArray()
                    .put(new JSONObject().put("url", live)).put(new JSONObject().put("url", "http://10.0.0.5:7878"))));
            env.assertNoDemoInRuntime("real routes");

            // add: a name that slugs to "demo" stays in the taku_* namespace and drags no demo along.
            JSONObject added = env.connect.control(new JSONObject().put("action", "add").put("name", "demo car").put("url", live).put("token", TOKEN))
                    .getJSONObject("node");
            check("taku_demo_car".equals(added.getString("id")) && !added.optBoolean("demo"), "add never mints a demo id: " + added);
            env.assertNoDemoInRuntime("add");
            check(env.servers().length() == 3, "add appended exactly one server: " + env.servers());
            env.act("remove", "taku_demo_car");
            env.assertNoDemoInRuntime("real remove");

            // Removing demos never edits mcpServers.
            String before = Files.readString(env.runtime.toPath());
            env.act("remove", "demo_trojan");
            env.act("remove", "demo_car");
            check(before.equals(Files.readString(env.runtime.toPath())), "demo removes leave runtime.json byte-identical");
            env.assertNoDemoInRuntime("end");
        } finally {
            server.stop(0);
        }
    }

    // ---- routes ----

    static void routeParsing() throws Exception {
        String tf = "/data/local/agentos/acp/secrets/x.token";
        // Back-compat single --url / --url= / TAKU_URL env; urls always a 1-element list then.
        for (JSONObject server : new JSONObject[]{
                takuServer("taku_a", "--url http://10.0.0.1:7878", tf),
                new JSONObject().put("name", "taku_a").put("command", "taku").put("args", new JSONArray().put("mcp-stdio").put("--url=http://10.0.0.1:7878").put("--token-file=" + tf)),
                new JSONObject().put("name", "taku_a").put("command", "/x/taku-runtime").put("args", new JSONArray().put("mcp-stdio").put("--token-file").put(tf))
                        .put("env", new JSONArray().put(new JSONObject().put("name", "TAKU_URL").put("value", "http://10.0.0.1:7878")))}) {
            JSONObject parsed = TakuConnect.parseServer(server);
            check(parsed != null && "http://10.0.0.1:7878".equals(parsed.getString("url")) && parsed.getJSONArray("urls").length() == 1
                    && tf.equals(parsed.getString("tokenFile")) && "taku_a".equals(parsed.getString("id")), "single-url forms: " + parsed + " from " + server);
        }
        // Arguments beat TAKU_URL; taku-android* binaries count.
        JSONObject both = takuServer("taku_b", "--urls http://a:1,http://b:2", tf).put("command", "/bin/taku-android-arm64")
                .put("env", new JSONArray().put(new JSONObject().put("name", "TAKU_URL").put("value", "http://env:9")));
        JSONObject parsed = TakuConnect.parseServer(both);
        check(parsed != null && parsed.getJSONArray("urls").length() == 2 && !parsed.toString().contains("env:9"), "args win over TAKU_URL: " + parsed);
        // Not nodes.
        check(TakuConnect.parseServer(memoryServer()) == null, "taku-memory is not a node");
        check(TakuConnect.parseServer(new JSONObject().put("name", "x").put("command", "taku").put("args", new JSONArray().put("serve").put("--url").put("http://a:1"))) == null, "needs mcp-stdio");
        check(TakuConnect.parseServer(new JSONObject().put("name", "x").put("command", "taku").put("args", new JSONArray().put("mcp-stdio").put("--token-file").put(tf))) == null, "needs a url");
        check(TakuConnect.parseServer(new JSONObject().put("name", "x").put("command", "taku").put("args", new JSONArray().put("mcp-stdio").put("--url"))) == null, "dangling --url");
        check(TakuConnect.parseServer(new JSONObject().put("name", "x").put("command", "/opt/nottaku")) == null, "other binaries");
        check(TakuConnect.parseServer(new JSONObject()) == null, "empty entry");

        // withRoutes ∘ parseServer is the identity on the url list, extra args/env survive, TAKU_URL dropped, NO_PROXY merged once.
        JSONObject server = new JSONObject().put("name", "taku_c").put("command", CLIENT)
                .put("args", new JSONArray().put("mcp-stdio").put("--url").put("http://old:1").put("--token-file").put(tf).put("--verbose").put("--urls=http://older:2"))
                .put("env", new JSONArray().put(new JSONObject().put("name", "TAKU_URL").put("value", "http://old:1"))
                        .put(new JSONObject().put("name", "NO_PROXY").put("value", "192.168.1.5,localhost")).put(new JSONObject().put("name", "RUST_LOG").put("value", "info")));
        JSONArray routes = TakuConnect.routesInput(new JSONArray()
                .put(new JSONObject().put("label", " 家 ").put("url", "192.168.1.5/"))
                .put(new JSONObject().put("kind", "tailscale").put("url", "http://100.64.0.9:7878"))
                .put(new JSONObject().put("kind", "relay").put("url", "https://relay.example.com:443")));
        check("家".equals(routes.getJSONObject(0).getString("label")) && "http://192.168.1.5:7878".equals(routes.getJSONObject(0).getString("url"))
                && "lan".equals(routes.getJSONObject(0).getString("kind")) && "".equals(routes.getJSONObject(1).getString("label")), "routesInput normalizes: " + routes);
        TakuConnect.withRoutes(server, routes);
        JSONArray rewritten = server.getJSONArray("args");
        check("mcp-stdio".equals(rewritten.getString(0)) && "--urls".equals(rewritten.getString(1))
                && "http://192.168.1.5:7878,http://100.64.0.9:7878,https://relay.example.com:443".equals(rewritten.getString(2))
                && rewritten.length() == 6 && tf.equals(rewritten.getString(4)) && "--verbose".equals(rewritten.getString(5)), "args rewritten, others kept: " + rewritten);
        JSONObject reparsed = TakuConnect.parseServer(server);
        for (int i = 0; i < routes.length(); i++) check(routes.getJSONObject(i).getString("url").equals(reparsed.getJSONArray("urls").getString(i)), "round trip url " + i);
        String env = server.getJSONArray("env").toString();
        check(!env.contains("TAKU_URL") && env.contains("RUST_LOG"), "TAKU_URL dropped, other env kept: " + env);
        String noProxy = null;
        for (int i = 0; i < server.getJSONArray("env").length(); i++) {
            JSONObject item = server.getJSONArray("env").getJSONObject(i);
            if ("NO_PROXY".equals(item.getString("name"))) { check(noProxy == null, "one NO_PROXY"); noProxy = item.getString("value"); }
        }
        check("192.168.1.5,localhost,100.64.0.9".equals(noProxy), "NO_PROXY merged once; a public relay that is not the first route keeps the proxy: " + noProxy);

        // candidateUrls (test action): comma lists, arrays, de-dup, non-strings refused.
        check(TakuConnect.candidateUrls(new JSONObject().put("url", "a, b").put("urls", new JSONArray().put("http://a:7878").put("c"))).size() == 3, "candidates de-duplicated after normalizing");
        for (Object bad : new Object[]{new JSONArray().put(5), 7, new JSONObject()}) {
            try {
                TakuConnect.candidateUrls(new JSONObject().put("urls", bad));
                throw new AssertionError("accepted urls " + bad);
            } catch (TakuConnect.Invalid invalid) {
                check("INVALID_URL".equals(invalid.getMessage()), "urls " + bad + " -> INVALID_URL");
            }
        }
    }

    static void routeReconcileAndBackCompat() throws Exception {
        JSONArray urls = new JSONArray().put("http://192.168.1.5:7878").put("http://100.64.0.9:7878").put("http://[fd00::5]:7878");
        JSONArray stored = new JSONArray()
                .put(new JSONObject().put("label", "Tailscale").put("kind", "tailscale").put("url", "100.64.0.9/"))       // matched despite form
                .put(new JSONObject().put("label", "家").put("kind", "moon").put("url", "http://192.168.1.5:7878"))      // bad kind → derived
                .put(new JSONObject().put("label", "gone").put("kind", "relay").put("url", "http://8.8.8.8:7878"))       // not in runtime → dropped
                .put("junk").put(new JSONObject().put("label", "no url"));
        JSONArray out = TakuConnect.reconcile(urls, stored);
        check(out.length() == 3, "runtime decides which routes exist: " + out);
        check("家".equals(out.getJSONObject(0).getString("label")) && "lan".equals(out.getJSONObject(0).getString("kind")), "label kept, invalid kind derived: " + out);
        check("Tailscale".equals(out.getJSONObject(1).getString("label")) && "http://100.64.0.9:7878".equals(out.getJSONObject(1).getString("url")),
                "normalized match, runtime url form wins: " + out);
        check("".equals(out.getJSONObject(2).getString("label")) && "ipv6".equals(out.getJSONObject(2).getString("kind")), "undecorated route derives its kind: " + out);
        check(!out.toString().contains("gone") && !out.toString().contains("8.8.8.8"), "stale stored route not resurrected");
        check(TakuConnect.reconcile(urls, null).length() == 3, "no stored routes (legacy entry) still yields routes");

        // A legacy single-url node with a legacy connect.json entry (no routes field) is a one-route node.
        Env env = new Env("legacy");
        env.runtime(new JSONArray().put(takuServer("taku_mini", "--url http://192.168.1.205:7878", env.token.getPath())));
        env.store(new JSONObject().put("taku_mini", new JSONObject().put("appearance", new JSONObject().put("displayName", "Mini"))));
        JSONObject legacy = env.node("taku_mini");
        check("http://192.168.1.205:7878".equals(legacy.getString("url")) && legacy.getJSONArray("routes").length() == 1
                && "lan".equals(legacy.getJSONArray("routes").getJSONObject(0).getString("kind")) && "lan".equals(legacy.getString("network"))
                && legacy.getBoolean("hasToken") && "Mini".equals(legacy.getJSONObject("appearance").getString("displayName")), "legacy node view: " + legacy);
        // Legacy parked copy (switched off before routes existed) re-enables unchanged.
        String original = env.server("taku_mini").toString();
        env.setEnabled("taku_mini", false);
        check(env.server("taku_mini") == null && env.saved().getJSONObject("taku_mini").has("disabled") && !env.node("taku_mini").getBoolean("enabled"), "parked");
        env.setEnabled("taku_mini", true);
        check(new JSONObject(original).similar(env.server("taku_mini")) && !env.saved().getJSONObject("taku_mini").has("disabled"), "legacy entry comes back byte-equivalent");
    }

    /** Switching the preferred route rewrites order everywhere and nothing is lost on the way. */
    static void routeSwitchRoundTrip() throws Exception {
        Env env = new Env("routes");
        JSONObject mini = takuServer("taku_mini", "--url http://192.168.1.205:7878", env.token.getPath());
        mini.getJSONArray("args").put("--log-level").put("debug");
        mini.put("env", new JSONArray().put(new JSONObject().put("name", "RUST_LOG").put("value", "warn")));
        env.runtime(new JSONArray().put(memoryServer()).put(mini));
        env.store(new JSONObject().put("taku_mini", new JSONObject().put("note", "user field").put("appearance", new JSONObject().put("displayName", "Mini"))));
        JSONObject lan = new JSONObject().put("label", "局域网").put("kind", "lan").put("url", "http://192.168.1.205:7878");
        JSONObject ts = new JSONObject().put("label", "Tailscale").put("kind", "tailscale").put("url", "http://100.100.1.2:7878");
        JSONObject relay = new JSONObject().put("label", "中继").put("kind", "relay").put("url", "https://relay.example.com:8443");

        JSONObject view = env.connect.control(new JSONObject().put("action", "routes").put("id", "taku_mini")
                .put("routes", new JSONArray().put(lan).put(ts).put(relay))).getJSONObject("node");
        check("http://192.168.1.205:7878".equals(view.getString("url")) && view.getJSONArray("routes").length() == 3, "three routes, lan preferred: " + view);

        // Preferred route switch: tailscale first.
        view = env.connect.control(new JSONObject().put("action", "routes").put("id", "taku_mini")
                .put("routes", new JSONArray().put(ts).put(lan).put(relay))).getJSONObject("node");
        check("http://100.100.1.2:7878".equals(view.getString("url")) && "tailscale".equals(view.getString("network")) && "100.100.1.2".equals(view.getString("host")),
                "view follows the new first route: " + view);
        JSONArray args = env.server("taku_mini").getJSONArray("args");
        check("--urls".equals(args.getString(1)) && "http://100.100.1.2:7878,http://192.168.1.205:7878,https://relay.example.com:8443".equals(args.getString(2))
                && args.toString().contains("--log-level") && args.toString().contains("debug") && args.toString().contains(env.token.getPath()),
                "runtime args reordered, other flags kept: " + args);
        check(env.server("taku_memory") != null && "me".equals(new JSONObject(Files.readString(env.runtime.toPath())).getString("keep")), "unrelated servers and keys kept");

        // Serialization round trip: connect.json routes equal the input, every view field survives a fresh instance.
        JSONArray saved = env.saved().getJSONObject("taku_mini").getJSONArray("routes");
        check(saved.similar(new JSONArray().put(ts).put(lan).put(relay)), "connect.json routes exactly as given: " + saved);
        check("user field".equals(env.saved().getJSONObject("taku_mini").getString("note"))
                && "Mini".equals(env.saved().getJSONObject("taku_mini").getJSONObject("appearance").getString("displayName")), "other connect.json fields kept");
        JSONObject reread = nodeIn(env.fresh().control(new JSONObject()).getJSONArray("nodes"), "taku_mini");
        check(reread.similar(view), "fresh instance reads the identical view:\n" + reread + "\n" + view);

        // Round trip through switch off/on keeps order, labels and kinds.
        env.setEnabled("taku_mini", false);
        check(nodeIn(env.nodes(), "taku_mini").getJSONArray("routes").similar(view.getJSONArray("routes")), "parked node keeps its routes");
        env.setEnabled("taku_mini", true);
        check(nodeIn(env.nodes(), "taku_mini").getJSONArray("routes").similar(view.getJSONArray("routes")), "re-enabled node keeps its routes");

        // Back to one route: --url again (the legacy single-url form), stale stored routes dropped.
        env.connect.control(new JSONObject().put("action", "routes").put("id", "taku_mini").put("routes", new JSONArray().put(relay)));
        args = env.server("taku_mini").getJSONArray("args");
        check("--url".equals(args.getString(1)) && "https://relay.example.com:8443".equals(args.getString(2)) && !args.toString().contains("--urls"), "single route → --url: " + args);
        JSONArray single = nodeIn(env.nodes(), "taku_mini").getJSONArray("routes");
        check(single.length() == 1 && "中继".equals(single.getJSONObject(0).getString("label")), "one route view: " + single);

        // A hand edit of runtime.json (order changed outside the page) wins over stored order; labels follow urls.
        JSONObject edited = takuServer("taku_mini", "--urls http://192.168.1.205:7878,https://relay.example.com:8443", env.token.getPath());
        env.runtime(new JSONArray().put(edited));
        env.store(new JSONObject().put("taku_mini", new JSONObject().put("routes", new JSONArray().put(relay).put(lan))));
        JSONArray handEdited = nodeIn(env.nodes(), "taku_mini").getJSONArray("routes");
        check("局域网".equals(handEdited.getJSONObject(0).getString("label")) && "中继".equals(handEdited.getJSONObject(1).getString("label")), "runtime order wins: " + handEdited);

        // Rejected route updates change neither file.
        String r = Files.readString(env.runtime.toPath()), s = Files.readString(env.store.toPath());
        for (JSONArray bad : new JSONArray[]{new JSONArray(), new JSONArray().put("http://a:1"), new JSONArray().put(new JSONObject().put("url", 5)),
                new JSONArray().put(new JSONObject().put("url", "http://a:1").put("kind", "satellite")),
                new JSONArray().put(new JSONObject().put("url", "a")).put(new JSONObject().put("url", "http://a:7878/"))}) {
            try {
                env.connect.control(new JSONObject().put("action", "routes").put("id", "taku_mini").put("routes", bad));
                throw new AssertionError("accepted " + bad);
            } catch (TakuConnect.Invalid expected) {
                check(r.equals(Files.readString(env.runtime.toPath())) && s.equals(Files.readString(env.store.toPath())), "rejected " + bad + " (" + expected.getMessage() + ") wrote nothing");
            }
        }
    }

    // ---- malformed input ----

    static void malformedInputs() throws Exception {
        Env env = new Env("malformed");
        JSONArray servers = new JSONArray().put("not an object").put(JSONObject.NULL).put(new JSONObject())
                .put(new JSONObject().put("name", "taku_badargs").put("command", CLIENT).put("args", "mcp-stdio --url http://x"))   // args not an array
                .put(takuServer("taku_mini", "--url http://192.168.1.205:7878", env.token.getPath()))
                .put(takuServer("taku_mini", "--url http://10.0.0.99:7878", env.token.getPath()))                                 // duplicate name
                .put(memoryServer());
        env.runtime(servers);
        JSONObject nodes = new JSONObject()
                .put("taku_junk", 5).put("demo_junk", "x").put("taku_list", new JSONArray())                                    // not objects
                .put("taku_badparked", new JSONObject().put("disabled", "nope"))
                .put("taku_notserver", new JSONObject().put("disabled", memoryServer().put("name", "taku_notserver")))
                .put("taku_mini", new JSONObject().put("appearance", "not an object").put("routes", "not an array"))
                .put("demo_weird", new JSONObject().put("demo", true).put("appearance", 7).put("enabled", "maybe").put("demoStatus", JSONObject.NULL))
                .put("demo_good", demo("TV", "tv"));
        env.store(nodes);

        JSONArray listed = env.nodes();
        JSONObject mini = nodeIn(listed, "taku_mini");
        check(listed.length() == 3 && mini != null && nodeIn(listed, "demo_weird") != null && nodeIn(listed, "demo_good") != null,
                "malformed entries skipped, real and valid demo nodes still listed: " + listed);
        check("http://192.168.1.205:7878".equals(mini.getString("url")) && mini.getJSONArray("routes").length() == 1
                && mini.getJSONObject("appearance").length() == 0, "first well-formed taku_mini wins, bad decorations ignored: " + mini);
        JSONObject weird = nodeIn(listed, "demo_weird");
        check(weird.getJSONObject("appearance").length() == 0 && weird.getBoolean("enabled") && !weird.has("demoStatus"), "weird demo degrades to defaults: " + weird);

        // Writes on a real node keep every malformed neighbour byte-for-byte (nothing silently "repaired" away).
        // (Duplicate names are a read-only concern here: drop the duplicate before writing.)
        servers.remove(5);
        env.runtime(servers);
        env.setEnabled("taku_mini", false);
        JSONObject after = env.saved();
        check(after.get("taku_junk").equals(5) && "x".equals(after.get("demo_junk")) && after.get("taku_list") instanceof JSONArray
                && "nope".equals(after.getJSONObject("taku_badparked").getString("disabled")), "malformed neighbours preserved: " + after);
        JSONArray kept = env.servers();
        check(kept.length() == 5 && "not an object".equals(kept.get(0)) && JSONObject.NULL.equals(kept.get(1)) && env.server("taku_badargs") != null
                && env.server("taku_mini") == null, "only taku_mini left mcpServers, junk kept in place: " + kept);
        env.assertNoDemoInRuntime("malformed off");
        env.setEnabled("taku_mini", true);
        check(env.node("taku_mini").getBoolean("enabled"), "re-enabled amid junk");
        env.assertNoDemoInRuntime("malformed on");

        // Missing / wrong-typed fields in requests.
        rejects(env.connect, new JSONObject().put("action", "nope"), "UNKNOWN_ACTION");
        rejects(env.connect, new JSONObject().put("action", "setEnabled").put("id", "taku_ghost").put("enabled", true), "NODE_NOT_FOUND");
        rejects(env.connect, new JSONObject().put("action", "appearance").put("id", "demo_good"), "NOTHING_TO_UPDATE");
        rejects(env.connect, new JSONObject().put("action", "routes").put("id", "taku_mini").put("routes", "http://a:1"), "INVALID_ROUTES");
        rejects(env.connect, new JSONObject().put("action", "probe").put("id", "taku_junk"), "NODE_NOT_FOUND");

        // connect.json "nodes" of the wrong type or a missing file: runtime nodes still listed.
        Files.writeString(env.store.toPath(), "{\"schema\":\"taku.connect/1\",\"nodes\":[1,2]}");
        check(nodeIn(env.nodes(), "taku_mini") != null, "nodes as an array → treated as empty");
        Files.delete(env.store.toPath());
        check(env.nodes().length() == 1, "no connect.json → runtime nodes only");
        // Missing runtime.json: only demo nodes, no crash, nothing created.
        Env bare = new Env("bare");
        bare.store(new JSONObject().put("demo_good", demo("TV", "tv")));
        check(bare.nodes().length() == 1 && !bare.runtime.exists(), "no runtime.json: demo only, runtime.json not created");
        bare.setEnabled("demo_good", false);
        check(!bare.runtime.exists(), "demo switch never creates runtime.json");

        // Corrupt connect.json (not JSON): fail closed, write nothing — parked servers live there.
        String runtimeBefore = Files.readString(env.runtime.toPath());
        Files.writeString(env.store.toPath(), "{\"nodes\": {\"taku_mini\": ");
        for (JSONObject input : new JSONObject[]{new JSONObject(), new JSONObject().put("action", "setEnabled").put("id", "taku_mini").put("enabled", false),
                new JSONObject().put("action", "appearance").put("id", "taku_mini").put("displayName", "X")}) {
            boolean threw = false;
            try {
                env.connect.control(input);
            } catch (Exception expected) {
                threw = true;
            }
            check(threw, "corrupt connect.json refuses " + input);
            check("{\"nodes\": {\"taku_mini\": ".equals(Files.readString(env.store.toPath())) && runtimeBefore.equals(Files.readString(env.runtime.toPath())),
                    "corrupt connect.json: neither file rewritten after " + input);
        }
    }

    private static JSONObject nodeIn(JSONArray nodes, String id) throws Exception {
        for (int i = 0; i < nodes.length(); i++) if (id.equals(nodes.getJSONObject(i).getString("id"))) return nodes.getJSONObject(i);
        return null;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
