package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Taku Connect: the phone's taku-runtime nodes (Mac mini, TV box, servers …) for the GUI and for
 * Agents, through one path (NativeBridge op {@code connect}, gateway/open-access {@code connect/*}).
 *
 * <p>Source of truth for <em>which</em> nodes exist is {@code runtime.json} {@code acp.mcpServers}:
 * a node is an entry that runs {@code taku mcp-stdio --url <url> --token-file <file>} (or
 * {@code --urls <u1,u2,…>}: ordered candidate routes) — exactly what the on-device agent uses, so the
 * page can never show a device the agent cannot reach (or hide one it can). Phone-side presentation
 * lives in {@code connect.json} next to it:
 *
 * <pre>{"schema":"taku.connect/1","nodes":{"taku_macmini":{
 *    "appearance":{"displayName":"…","shortName":"…","deviceType":"mac_mini","icon":"builtin:mac_mini|https://…|data:image/…"},
 *    "routes":[{"label":"…","kind":"lan|ipv6|tailscale|relay|internet","url":"http://192.168.1.205:7878"},…],
 *    "disabled":{…the mcpServers entry while switched off…}}}}</pre>
 *
 * <p>Demo nodes: a connect.json entry keyed {@code demo_*} with {@code "demo":true} (plus
 * {@code appearance}, optional {@code enabled} and {@code demoStatus}) is listed for the page as a
 * showcase device (e.g. a car or TV card). It has no runtime.json entry, so the agent never gets
 * tools for it; only {@code setEnabled} (stored as {@code enabled}), {@code appearance} and
 * {@code remove} apply, and deleting the entry from connect.json removes it. The {@code demo_} namespace
 * and {@code "demo":true} entries are never runtime nodes ({@link #reservedForDemo}): a parked
 * {@code disabled} copy on such an entry is ignored, so no action can move a demo into mcpServers.
 *
 * <p>Routes: the runtime url list is authoritative for <em>which</em> routes exist and their order
 * (preference: lan → ipv6 → tailscale → relay); stored labels/kinds only decorate them. The taku client
 * probes every route's {@code /healthz} concurrently and takes the earliest-listed live route within
 * {@link #ROUTE_GRACE_MS} of the fastest ({@link #select}); probes here apply the same rule, so the page
 * shows the route the agent will use.
 *
 * <p>Tokens never leave the broker: they are written root-only under {@code secrets/} and every
 * answer carries only {@code hasToken}. Probes measure {@code GET /healthz} round trips and read
 * {@code GET /v1/capabilities}.
 */
final class TakuConnect {
    static final String SCHEMA = "taku.connect/1";
    static final Set<String> DEVICE_TYPES = Set.of("mac_mini", "laptop", "desktop", "tv", "server", "phone", "tablet", "speaker", "car", "generic");
    static final int MAX_ICON_BYTES = 256 * 1024, MAX_NAME_CHARS = 64, MAX_TOKEN_CHARS = 512, MAX_NODES = 32;
    static final Pattern ID = Pattern.compile("taku_[a-z0-9_]{1,40}");
    static final Pattern DEMO_ID = Pattern.compile("demo_[a-z0-9_]{1,40}");
    static final int MAX_DEMO_STATUS_CHARS = 48, MAX_SHORT_NAME_CHARS = 16;
    static final String DEFAULT_CLIENT = "/data/local/agentos/acp/bin/taku";
    static final String TOKEN_FILE_PREFIX = "/data/local/agentos/";
    static final int CONNECT_TIMEOUT_MS = 2500, READ_TIMEOUT_MS = 4000;
    /** Route probing (mirrors the taku client): per-request health timeout, whole-probe budget, selection grace. */
    static final int HEALTH_TIMEOUT_MS = 1500, PROBE_BUDGET_MS = 2500, ROUTE_GRACE_MS = 25;
    static final int MAX_ROUTES = 8, MAX_ROUTE_LABEL_CHARS = 32;
    static final Set<String> ROUTE_KINDS = Set.of("lan", "ipv6", "tailscale", "relay", "internet");

    /** Validation failure; the message is a stable public error code. */
    static final class Invalid extends IOException {
        Invalid(String code) { super(code); }
    }

    final File runtimeConfig, store, secrets;
    final String client;

    TakuConnect(File runtimeConfig, File store, File secrets, String client) {
        this.runtimeConfig = runtimeConfig;
        this.store = store;
        this.secrets = secrets;
        this.client = client;
    }

    /**
     * Actions: list, probe {id}, test {url|urls, token|tokenFile}, add, remove {id}, setEnabled {id, enabled},
     * appearance {id, …}, routes {id, routes:[{label,kind,url}]}.
     */
    JSONObject control(JSONObject input) throws Exception {
        String action = input.optString("action", "list");
        // Probes read the node under the lock but do their network round trips outside it, so the
        // page's concurrent per-node probes run in parallel instead of queueing behind each other
        // (and behind dead backup routes).
        if (action.equals("probe")) {
            JSONObject node;
            String file;
            synchronized (this) {
                node = node(input.getString("id"));
                if (node.optBoolean("demo")) return demoProbe(node);
                file = tokenFile(node.getString("id"));
            }
            return probe(node, file);
        }
        if (action.equals("test")) return test(input);
        return locked(action, input);
    }

    private synchronized JSONObject locked(String action, JSONObject input) throws Exception {
        switch (action) {
            case "list": return list();
            case "add": return add(input);
            case "remove": return remove(input.getString("id"));
            case "setEnabled": return setEnabled(input.getString("id"), input.getBoolean("enabled"));
            case "appearance": return appearance(input);
            case "routes": return routes(input);
            default: throw new Invalid("UNKNOWN_ACTION");
        }
    }

    // ---- reading ----

    /** A runtime node parsed from an mcpServers entry, or null when the entry is something else. */
    static JSONObject parseServer(JSONObject server) throws Exception {
        String command = server.optString("command", "");
        String base = command.substring(command.lastIndexOf('/') + 1);
        if (!base.equals("taku") && !base.equals("taku-runtime") && !base.startsWith("taku-android")) return null;
        JSONArray args = server.optJSONArray("args");
        if (args == null || args.length() == 0 || !"mcp-stdio".equals(args.optString(0))) return null;
        Set<String> urls = new LinkedHashSet<>();
        String tokenFile = "";
        for (int i = 1; i < args.length(); i++) {
            String arg = args.optString(i);
            String next = i + 1 < args.length() ? args.optString(i + 1) : "";
            if (arg.equals("--url") || arg.equals("--urls")) { splitUrls(next, urls); i++; }
            else if (arg.startsWith("--url=")) splitUrls(arg.substring(6), urls);
            else if (arg.startsWith("--urls=")) splitUrls(arg.substring(7), urls);
            else if (arg.equals("--token-file")) { tokenFile = next; i++; }
            else if (arg.startsWith("--token-file=")) tokenFile = arg.substring(13);
        }
        JSONArray env = server.optJSONArray("env");
        if (env != null) for (int i = 0; i < env.length(); i++) {
            JSONObject item = env.optJSONObject(i);
            if (item != null && urls.isEmpty() && "TAKU_URL".equals(item.optString("name"))) splitUrls(item.optString("value"), urls);
        }
        if (urls.isEmpty()) return null;
        JSONArray list = new JSONArray();
        for (String url : urls) list.put(url);
        return new JSONObject().put("id", server.optString("name")).put("url", list.getString(0)).put("urls", list).put("tokenFile", tokenFile);
    }

    /** {@code a,b , c} → ordered, de-duplicated, blanks dropped (same as the taku client's --urls). */
    static void splitUrls(String value, Set<String> into) {
        for (String part : value.split(",")) if (!part.trim().isEmpty()) into.add(part.trim());
    }

    JSONObject loadStore() throws Exception {
        if (!store.isFile()) return new JSONObject().put("schema", SCHEMA).put("nodes", new JSONObject());
        JSONObject stored = new JSONObject(new String(readBounded(store, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        if (stored.optJSONObject("nodes") == null) stored.put("nodes", new JSONObject());
        return stored.put("schema", SCHEMA);
    }

    JSONObject loadRuntime() throws Exception {
        JSONObject stored = AcpRuntimeSettings.load(runtimeConfig);
        return stored == null ? new JSONObject() : stored;
    }

    static JSONArray servers(JSONObject runtime) throws Exception {
        JSONObject acp = runtime.optJSONObject(RuntimeRouter.ACP);
        JSONArray servers = acp == null ? null : acp.optJSONArray("mcpServers");
        return servers == null ? new JSONArray() : servers;
    }

    /** Every node: enabled ones from runtime.json first (in their order), then switched-off ones. */
    JSONObject list() throws Exception {
        JSONObject runtime = loadRuntime(), saved = loadStore().getJSONObject("nodes");
        JSONArray out = new JSONArray();
        Set<String> seen = new java.util.HashSet<>();
        JSONArray servers = servers(runtime);
        for (int i = 0; i < servers.length(); i++) {
            JSONObject server = servers.optJSONObject(i);
            JSONObject parsed = server == null ? null : parseServer(server);
            if (parsed == null || reservedForDemo(parsed.getString("id"), null) || !seen.add(parsed.getString("id"))) continue;
            out.put(view(parsed, true, saved.optJSONObject(parsed.getString("id"))));
        }
        for (Iterator<String> it = saved.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject entry = saved.optJSONObject(id);
            JSONObject disabled = entry == null ? null : entry.optJSONObject("disabled");
            JSONObject parsed = disabled == null ? null : parseServer(disabled);
            // A parked copy is re-enabled into runtime.json verbatim, so a demo (or demo-named) entry
            // carrying one must never become a switchable node: demo nodes never reach mcpServers.
            if (parsed == null || reservedForDemo(id, entry) || reservedForDemo(parsed.getString("id"), null) || !seen.add(id)) continue;
            out.put(view(parsed, false, entry));
        }
        for (Iterator<String> it = saved.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject entry = saved.optJSONObject(id);
            if (entry != null && isDemo(id, entry) && seen.add(id)) out.put(demoView(id, entry));
        }
        return new JSONObject().put("schema", SCHEMA).put("nodes", out);
    }

    static boolean isDemo(String id, JSONObject entry) {
        return DEMO_ID.matcher(id).matches() && entry.optBoolean("demo");
    }

    /** The {@code demo_} namespace and {@code "demo":true} entries are showcase-only, never runtime nodes. */
    static boolean reservedForDemo(String id, JSONObject entry) {
        return id.startsWith("demo_") || entry != null && entry.optBoolean("demo");
    }

    /** A showcase node: no url, routes or token; never in runtime.json. */
    static JSONObject demoView(String id, JSONObject entry) throws Exception {
        JSONObject appearance = entry.optJSONObject("appearance");
        String status = entry.optString("demoStatus", "").trim();
        if (status.length() > MAX_DEMO_STATUS_CHARS) status = status.substring(0, MAX_DEMO_STATUS_CHARS);
        JSONObject view = new JSONObject().put("id", id).put("url", "").put("host", "").put("network", "internet")
                .put("routes", new JSONArray()).put("enabled", entry.optBoolean("enabled", true)).put("hasToken", false)
                .put("demo", true).put("appearance", appearance == null ? new JSONObject() : appearance);
        if (!status.isEmpty()) view.put("demoStatus", status);
        return view;
    }

    /** Demo nodes answer "online" while switched on, without any network I/O. */
    static JSONObject demoProbe(JSONObject node) throws Exception {
        boolean on = node.getBoolean("enabled");
        return new JSONObject().put("id", node.getString("id")).put("demo", true).put("enabled", on)
                .put("status", on ? "online" : "offline").put("online", on).put("checkedAt", System.currentTimeMillis());
    }

    JSONObject view(JSONObject parsed, boolean enabled, JSONObject saved) throws Exception {
        String tokenFile = parsed.optString("tokenFile");
        String host = host(parsed.getString("url"));
        JSONObject appearance = saved == null ? null : saved.optJSONObject("appearance");
        return new JSONObject().put("id", parsed.getString("id")).put("url", parsed.getString("url"))
                .put("host", host).put("network", network(host))
                .put("routes", reconcile(parsed.getJSONArray("urls"), saved == null ? null : saved.optJSONArray("routes")))
                .put("enabled", enabled).put("hasToken", !tokenFile.isEmpty() && new File(tokenFile).isFile())
                .put("appearance", appearance == null ? new JSONObject() : appearance);
    }

    /** Host of a url without IPv6 brackets; "" when unparsable. */
    static String host(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return "";
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        } catch (Exception malformed) {
            return "";
        }
    }

    /** Comparable form of a route url (normalized when valid, as-is otherwise). */
    static String routeKey(String url) {
        try {
            return url(url);
        } catch (Invalid invalid) {
            return url.trim();
        }
    }

    /** lan | ipv6 | tailscale | relay | internet inferred from the host (loopback counts as lan). */
    static String routeKind(String url) {
        String network = network(host(url));
        return network.equals("loopback") ? "lan" : network;
    }

    /**
     * The node's routes: runtime urls decide which routes exist and their order; a stored route with the
     * same (normalized) url lends its label and kind; anything else gets a kind derived from its host.
     */
    static JSONArray reconcile(JSONArray urls, JSONArray stored) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < urls.length(); i++) {
            String url = urls.getString(i), key = routeKey(url);
            JSONObject match = null;
            if (stored != null) for (int j = 0; j < stored.length() && match == null; j++) {
                JSONObject candidate = stored.optJSONObject(j);
                if (candidate != null && key.equals(routeKey(candidate.optString("url")))) match = candidate;
            }
            String kind = match == null ? "" : match.optString("kind");
            out.put(new JSONObject().put("label", match == null ? "" : match.optString("label"))
                    .put("kind", ROUTE_KINDS.contains(kind) ? kind : routeKind(url)).put("url", url));
        }
        return out;
    }

    JSONObject node(String id) throws Exception {
        JSONArray nodes = list().getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).getString("id").equals(id)) return nodes.getJSONObject(i);
        throw new Invalid("NODE_NOT_FOUND");
    }

    /** The token file of an enabled or switched-off node (never returned to callers). */
    String tokenFile(String id) throws Exception {
        JSONArray servers = servers(loadRuntime());
        for (int i = 0; i < servers.length(); i++) {
            JSONObject parsed = servers.optJSONObject(i) == null ? null : parseServer(servers.getJSONObject(i));
            if (parsed != null && parsed.getString("id").equals(id)) return parsed.optString("tokenFile");
        }
        JSONObject saved = loadStore().getJSONObject("nodes").optJSONObject(id);
        JSONObject disabled = saved == null ? null : saved.optJSONObject("disabled");
        JSONObject parsed = disabled == null ? null : parseServer(disabled);
        return parsed == null ? "" : parsed.optString("tokenFile");
    }

    // ---- probing ----

    JSONObject probe(JSONObject node, String file) throws Exception {
        String token = file.isEmpty() ? "" : readToken(new File(file));
        return probe(node.getJSONArray("routes"), token).put("id", node.getString("id")).put("enabled", node.getBoolean("enabled"));
    }

    JSONObject test(JSONObject input) throws Exception {
        JSONArray routes = new JSONArray();
        for (String url : candidateUrls(input)) routes.put(new JSONObject().put("label", "").put("kind", routeKind(url)).put("url", url));
        return probe(routes, token(input));
    }

    /** {@code url} (may hold commas) and/or {@code urls} (array or comma list) → normalized, ordered, de-duplicated. */
    static List<String> candidateUrls(JSONObject input) throws Exception {
        Set<String> raw = new LinkedHashSet<>();
        splitUrls(input.optString("url", ""), raw);
        Object urls = input.opt("urls");
        if (urls instanceof JSONArray) for (int i = 0; i < ((JSONArray) urls).length(); i++) {
            Object item = ((JSONArray) urls).get(i);
            if (!(item instanceof String)) throw new Invalid("INVALID_URL");
            splitUrls((String) item, raw);
        } else if (urls instanceof String) splitUrls((String) urls, raw);
        else if (urls != null) throw new Invalid("INVALID_URL");
        Set<String> out = new LinkedHashSet<>();
        for (String url : raw) out.add(url(url));
        if (out.isEmpty()) throw new Invalid("INVALID_URL");
        if (out.size() > MAX_ROUTES) throw new Invalid("TOO_MANY_ROUTES");
        return new ArrayList<>(out);
    }

    /**
     * The route the taku client takes: among live routes (latency ≥ 0), the earliest-listed one whose
     * latency is within {@link #ROUTE_GRACE_MS} of the fastest; -1 when none is live.
     */
    static int select(long[] latencies) {
        long fastest = Long.MAX_VALUE;
        for (long latency : latencies) if (latency >= 0) fastest = Math.min(fastest, latency);
        if (fastest == Long.MAX_VALUE) return -1;
        for (int i = 0; i < latencies.length; i++) if (latencies[i] >= 0 && latencies[i] <= fastest + ROUTE_GRACE_MS) return i;
        return -1;
    }

    /**
     * One {@code /healthz} round trip per route, all routes at once (the taku client's probe). Returns as
     * soon as the choice is settled: {@link #ROUTE_GRACE_MS} after the first live answer (a later answer
     * could not win {@link #select}), when every route finished, or at {@link #PROBE_BUDGET_MS}. A dead
     * backup route therefore never delays a probe once a live one answered; it reports {@code TIMEOUT}.
     */
    static long[] health(List<String> urls, String[] errors) throws InterruptedException {
        int n = urls.size();
        long[] latencies = new long[n];
        java.util.Arrays.fill(latencies, -1);
        java.util.Arrays.fill(errors, "TIMEOUT");
        int[] finished = {0};
        long[] firstLive = {0};
        for (int r = 0; r < n; r++) {
            final int route = r;
            Thread worker = new Thread(() -> {
                long started = System.nanoTime();
                String failure = "";
                try {
                    Response health = get(urls.get(route) + "/healthz", "", HEALTH_TIMEOUT_MS, HEALTH_TIMEOUT_MS);
                    if (health.code != 200) failure = "HTTP_" + health.code;
                } catch (IOException unreachable) {
                    failure = "UNREACHABLE";
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                synchronized (latencies) {
                    if (failure.isEmpty()) {
                        latencies[route] = elapsed;
                        errors[route] = "";
                        if (firstLive[0] == 0) firstLive[0] = System.nanoTime();
                    } else errors[route] = failure;
                    finished[0]++;
                    latencies.notifyAll();
                }
            }, "taku-connect-probe");
            worker.setDaemon(true);
            worker.start();
        }
        long deadline = System.nanoTime() + PROBE_BUDGET_MS * 1_000_000L;
        synchronized (latencies) {
            while (finished[0] < n) {
                long until = firstLive[0] == 0 ? deadline : Math.min(deadline, firstLive[0] + ROUTE_GRACE_MS * 1_000_000L);
                long waitMs = (until - System.nanoTime()) / 1_000_000L;
                if (waitMs <= 0) break;
                latencies.wait(waitMs);
            }
            long[] snapshot = latencies.clone();
            for (int i = 0; i < n; i++) if (snapshot[i] < 0 && errors[i].isEmpty()) errors[i] = "TIMEOUT";
            return snapshot;
        }
    }

    /**
     * {@code status}: online | unauthorized | offline. Every route is health-probed concurrently, the
     * client's rule picks one ({@link #select}) and {@code /v1/capabilities} is read through it.
     * {@code url}/{@code network}/{@code latencyMs} describe the selected route (the first one when none
     * is live); {@code route} is the selected route and {@code routes} all of them in configured order.
     */
    static JSONObject probe(JSONArray configured, String token) throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < configured.length(); i++) urls.add(configured.getJSONObject(i).getString("url"));
        String[] errors = new String[urls.size()];
        long[] latencies = health(urls, errors);
        int chosen = select(latencies);
        JSONArray routes = new JSONArray();
        for (int i = 0; i < urls.size(); i++) {
            JSONObject source = configured.getJSONObject(i);
            JSONObject route = new JSONObject().put("label", source.optString("label")).put("kind", source.optString("kind", routeKind(urls.get(i))))
                    .put("url", urls.get(i)).put("selected", i == chosen);
            // "standby": still answering when the choice settled (it could not have won), so neither
            // live nor known dead; a backup route the page lists quietly.
            boolean pending = latencies[i] < 0 && chosen >= 0 && "TIMEOUT".equals(errors[i]);
            route.put("status", latencies[i] >= 0 ? "online" : pending ? "standby" : "offline");
            if (latencies[i] >= 0) route.put("latencyMs", latencies[i]);
            else if (!pending) route.put("error", errors[i]);
            routes.put(route);
        }
        String url = urls.get(chosen < 0 ? 0 : chosen);
        JSONObject out = new JSONObject().put("url", url).put("network", network(host(url))).put("checkedAt", System.currentTimeMillis()).put("routes", routes);
        if (chosen < 0) return out.put("status", "offline").put("online", false).put("error", errors[0].isEmpty() ? "UNREACHABLE" : errors[0]);
        JSONObject route = routes.getJSONObject(chosen);
        out.put("online", true).put("latencyMs", latencies[chosen]).put("route", route);
        if (token.isEmpty()) {
            route.put("status", "unauthorized");
            return out.put("status", "unauthorized").put("error", "TOKEN_MISSING");
        }
        Response caps;
        try {
            caps = get(url + "/v1/capabilities", token);
        } catch (IOException failed) {
            route.put("status", "offline").put("error", "UNREACHABLE");
            return out.put("status", "offline").put("online", false).put("error", "UNREACHABLE");
        }
        if (caps.code == 401) {
            route.put("status", "unauthorized");
            return out.put("status", "unauthorized").put("error", "UNAUTHORIZED");
        }
        if (caps.code != 200) return out.put("status", "offline").put("error", "HTTP_" + caps.code);
        return out.put("status", "online").put("capabilities", summary(new JSONObject(caps.body)));
    }

    /** The capabilities fields clients show; input schemas and engine paths stay on the device. */
    static JSONObject summary(JSONObject caps) throws Exception {
        JSONArray tools = new JSONArray();
        JSONArray source = caps.optJSONArray("tools");
        if (source != null) for (int i = 0; i < source.length(); i++) {
            JSONObject tool = source.optJSONObject(i);
            if (tool != null) tools.put(new JSONObject().put("name", tool.optString("name")).put("description", tool.optString("description")));
        }
        JSONObject exec = caps.optJSONObject("exec");
        JSONObject engine = caps.optJSONObject("engine");
        JSONObject appearance = caps.optJSONObject("appearance");
        JSONObject out = new JSONObject().put("runtime", caps.optString("runtime")).put("version", caps.optString("version"))
                .put("device", caps.optString("device")).put("os", caps.optString("os")).put("arch", caps.optString("arch"))
                .put("mode", caps.optString("mode")).put("tools", tools)
                .put("reservedTools", caps.optJSONArray("reserved_tools") == null ? new JSONArray() : caps.getJSONArray("reserved_tools"))
                .put("protocols", caps.optJSONArray("protocols") == null ? new JSONArray() : caps.getJSONArray("protocols"))
                .put("appearance", appearance == null ? new JSONObject() : appearance);
        if (engine != null) out.put("engine", new JSONObject().put("name", engine.optString("name")).put("protocol", engine.optString("protocol")));
        if (exec != null) out.put("exec", new JSONObject().put("enabled", exec.optBoolean("enabled"))
                .put("allowedRoots", exec.optJSONArray("allowed_roots") == null ? new JSONArray() : exec.getJSONArray("allowed_roots"))
                .put("defaultTimeoutMs", exec.optLong("default_timeout_ms")).put("maxTimeoutMs", exec.optLong("max_timeout_ms"))
                .put("maxOutputBytes", exec.optLong("max_output_bytes")));
        return out;
    }

    static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    static Response get(String url, String token) throws IOException {
        return get(url, token, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    static Response get(String url, String token, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URL target = new URL(url);
        // LAN / IPv6 / Tailscale peers are never reached through the phone's proxy (as NO_PROXY for the agent).
        HttpURLConnection http = (HttpURLConnection) (direct(host(url)) ? target.openConnection(Proxy.NO_PROXY) : target.openConnection());
        try {
            http.setConnectTimeout(connectTimeoutMs);
            http.setReadTimeout(readTimeoutMs);
            http.setInstanceFollowRedirects(false);
            if (!token.isEmpty()) http.setRequestProperty("Authorization", "Bearer " + token);
            int code = http.getResponseCode();
            InputStream in = code >= 400 ? http.getErrorStream() : http.getInputStream();
            return new Response(code, in == null ? "" : new String(readStream(in, 1024 * 1024), StandardCharsets.UTF_8));
        } finally {
            http.disconnect();
        }
    }

    /** lan | ipv6 | tailscale | loopback | internet, from the host alone (no DNS for names). */
    static String network(String host) {
        if (host == null || host.isEmpty()) return "internet";
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.equals("localhost")) return "loopback";
        if (host.indexOf(':') >= 0) return ipv6Network(host.toLowerCase(Locale.ROOT));
        if (host.endsWith(".local")) return "lan";
        if (host.endsWith(".ts.net")) return "tailscale";
        int[] ip = ipv4(host);
        if (ip == null) return "internet";
        if (ip[0] == 127) return "loopback";
        if (ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31 || ip[0] == 169 && ip[1] == 254) return "lan";
        if (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127) return "tailscale";
        return "internet";
    }

    /** IPv6 literals: ::1 loopback, Tailscale's fd7a:115c:a1e0::/48, everything else (ULA, link-local, global) ipv6. */
    static String ipv6Network(String host) {
        int zone = host.indexOf('%');
        String address = zone >= 0 ? host.substring(0, zone) : host;
        if (address.equals("::1")) return "loopback";
        if (address.startsWith("fd7a:115c:a1e0:")) return "tailscale";
        return "ipv6";
    }

    /** Hosts that bypass the phone's proxy: everything but public names/IPv4 (a relay on the LAN is lan). */
    static boolean direct(String host) {
        return !"internet".equals(network(host));
    }

    static int[] ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) return null;
            out[i] = Integer.parseInt(parts[i]);
            if (out[i] > 255) return null;
        }
        return out;
    }

    // ---- writing ----

    static String url(String raw) throws Invalid {
        String text = raw == null ? "" : raw.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (!text.contains("://")) text = "http://" + text;
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception malformed) {
            throw new Invalid("INVALID_URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new Invalid("INVALID_URL");
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null) throw new Invalid("INVALID_URL");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
            throw new Invalid("INVALID_URL");
        int port = uri.getPort() == -1 ? 7878 : uri.getPort();
        if (port < 1 || port > 65535) throw new Invalid("INVALID_URL");
        return scheme + "://" + uri.getHost() + ":" + port;
    }

    /** A token given inline or as a root-readable file; "" when neither. */
    static String token(JSONObject input) throws Exception {
        String inline = input.optString("token", "").trim();
        if (!inline.isEmpty()) {
            if (inline.length() > MAX_TOKEN_CHARS || !inline.matches("[\\x21-\\x7e]+")) throw new Invalid("INVALID_TOKEN");
            return inline;
        }
        String file = input.optString("tokenFile", "").trim();
        if (file.isEmpty()) return "";
        // Only under the agent's own root-owned tree: a token file is sent as a bearer to the given
        // URL, so an arbitrary path would let a caller exfiltrate any root-readable file.
        if (!file.startsWith(TOKEN_FILE_PREFIX) || file.contains("/../") || file.contains("/./")) throw new Invalid("TOKEN_FILE_LOCATION_NOT_ALLOWED");
        File f = new File(file);
        if (!f.isFile()) throw new Invalid("TOKEN_FILE_NOT_FOUND");
        return readToken(f);
    }

    static String readToken(File file) throws IOException {
        if (!file.isFile() || file.length() > MAX_TOKEN_CHARS + 2) return "";
        return new String(readBounded(file, MAX_TOKEN_CHARS + 2), StandardCharsets.UTF_8).trim();
    }

    static String displayName(Object value) throws Invalid {
        if (!(value instanceof String)) throw new Invalid("INVALID_NAME");
        String name = ((String) value).trim();
        if (name.isEmpty() || name.length() > MAX_NAME_CHARS) throw new Invalid("INVALID_NAME");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new Invalid("INVALID_NAME");
        return name;
    }

    /** The orbit label (e.g. "Tesla" for "Tesla Model Y"): a display name of at most 16 characters. */
    static String shortName(Object value) throws Invalid {
        String name = displayName(value);
        if (name.length() > MAX_SHORT_NAME_CHARS) throw new Invalid("INVALID_NAME");
        return name;
    }

    /** builtin:<type> | http(s) URL | data:image/… ; an absolute image path is inlined as a data: URI. */
    static String icon(Object value) throws Exception {
        if (!(value instanceof String)) throw new Invalid("INVALID_ICON");
        String icon = ((String) value).trim();
        if (icon.isEmpty()) return "";
        if (icon.startsWith("/") || icon.startsWith("file://")) {
            File file = new File(icon.startsWith("file://") ? icon.substring(7) : icon);
            String lower = file.getName().toLowerCase(Locale.ROOT);
            String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                    : lower.endsWith(".webp") ? "image/webp" : lower.endsWith(".svg") ? "image/svg+xml" : "";
            if (mime.isEmpty()) throw new Invalid("ICON_TYPE_NOT_SUPPORTED");
            if (!file.isFile()) throw new Invalid("ICON_FILE_NOT_FOUND");
            if (file.length() > MAX_ICON_BYTES * 3L / 4) throw new Invalid("ICON_TOO_LARGE");
            icon = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(readBounded(file, MAX_ICON_BYTES));
        }
        if (icon.length() > MAX_ICON_BYTES) throw new Invalid("ICON_TOO_LARGE");
        if (icon.startsWith("builtin:")) {
            if (!DEVICE_TYPES.contains(icon.substring(8))) throw new Invalid("INVALID_ICON");
            return icon;
        }
        if (icon.startsWith("https://") || icon.startsWith("http://")) {
            if (icon.matches(".*\\s.*")) throw new Invalid("INVALID_ICON");
            return icon;
        }
        if (icon.matches("data:image/(png|jpeg|webp|gif|svg\\+xml);base64,[A-Za-z0-9+/=]+")) return icon;
        throw new Invalid("INVALID_ICON");
    }

    static String deviceType(Object value) throws Invalid {
        if (!(value instanceof String) || !DEVICE_TYPES.contains(value)) throw new Invalid("INVALID_DEVICE_TYPE");
        return (String) value;
    }

    /** taku_<ascii slug of the name>, else taku_node, made unique against existing ids. */
    static String newId(String name, Set<String> taken) {
        StringBuilder slug = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') slug.append(c);
            else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '_') slug.append('_');
        }
        String base = slug.toString().replaceAll("^_+|_+$", "");
        if (base.length() > 30) base = base.substring(0, 30).replaceAll("_+$", "");
        base = "taku_" + (base.isEmpty() ? "node" : base);
        String id = base;
        for (int n = 2; taken.contains(id) || "taku_phone".equals(id) || "taku_memory".equals(id); n++) id = base + "_" + n;
        return id;
    }

    Set<String> takenIds(JSONObject runtime, JSONObject saved) throws Exception {
        Set<String> taken = new java.util.HashSet<>();
        JSONArray servers = servers(runtime);
        for (int i = 0; i < servers.length(); i++) {
            JSONObject server = servers.optJSONObject(i);
            if (server != null) taken.add(server.optString("name"));
        }
        for (Iterator<String> it = saved.keys(); it.hasNext(); ) taken.add(it.next());
        return taken;
    }

    JSONObject add(JSONObject input) throws Exception {
        String name = displayName(input.opt("name"));
        // Either one url or an ordered routes list (then url may be omitted; it must be one of them).
        JSONArray routes = input.has("routes") ? routesInput(input.opt("routes")) : null;
        if (routes == null) {
            String only = url(input.optString("url"));
            routes = new JSONArray().put(new JSONObject().put("label", "").put("kind", routeKind(only)).put("url", only));
        } else if (!input.optString("url", "").isEmpty()) {
            String given = url(input.optString("url"));
            boolean listed = false;
            for (int i = 0; i < routes.length(); i++) listed |= given.equals(routes.getJSONObject(i).getString("url"));
            if (!listed) throw new Invalid("INVALID_ROUTES");
        }
        String token = token(input);
        if (token.isEmpty()) throw new Invalid("TOKEN_REQUIRED");
        JSONObject check = probe(routes, token);
        if (!"online".equals(check.getString("status"))) throw new Invalid("unauthorized".equals(check.getString("status")) ? "NODE_UNAUTHORIZED" : "NODE_UNREACHABLE");
        JSONObject runtime = loadRuntime(), stored = loadStore(), saved = stored.getJSONObject("nodes");
        if (list().getJSONArray("nodes").length() >= MAX_NODES) throw new Invalid("TOO_MANY_NODES");
        String id = newId(name, takenIds(runtime, saved));
        File tokenFile;
        String given = input.optString("tokenFile", "").trim();
        if (input.optString("token", "").trim().isEmpty() && !given.isEmpty()) tokenFile = new File(given);
        else {
            tokenFile = new File(secrets, id + ".token");
            writePrivate(tokenFile, (token + "\n").getBytes(StandardCharsets.UTF_8));
        }
        JSONObject server = new JSONObject().put("name", id).put("command", client)
                .put("args", new JSONArray().put("mcp-stdio").put("--token-file").put(tokenFile.getPath())).put("env", new JSONArray());
        withRoutes(server, routes);
        JSONObject appearance = new JSONObject().put("displayName", name);
        if (input.has("deviceType")) appearance.put("deviceType", deviceType(input.get("deviceType")));
        else {
            JSONObject remote = check.getJSONObject("capabilities").optJSONObject("appearance");
            String advertised = remote == null ? "" : remote.optString("deviceType");
            if (DEVICE_TYPES.contains(advertised)) appearance.put("deviceType", advertised);
        }
        if (input.has("icon") && !icon(input.get("icon")).isEmpty()) appearance.put("icon", icon(input.get("icon")));
        saved.put(id, new JSONObject().put("appearance", appearance).put("routes", routes));
        writeStore(stored);
        writeServers(runtime, servers(runtime).put(server));
        return new JSONObject().put("node", node(id)).put("probe", check.put("id", id).put("enabled", true));
    }

    JSONObject remove(String id) throws Exception {
        if (node(id).optBoolean("demo")) {
            JSONObject stored = loadStore();
            stored.getJSONObject("nodes").remove(id);
            writeStore(stored);
            return new JSONObject().put("removed", id);
        }
        JSONObject runtime = loadRuntime(), stored = loadStore();
        JSONArray kept = withoutServer(servers(runtime), id);
        String file = tokenFile(id);
        stored.getJSONObject("nodes").remove(id);
        writeStore(stored);
        writeServers(runtime, kept);
        // Only tokens this page wrote are deleted; a hand-placed token file belongs to whoever made it.
        File token = new File(file);
        if (!file.isEmpty() && token.getAbsoluteFile().getParentFile() != null
                && token.getAbsoluteFile().getParentFile().equals(secrets.getAbsoluteFile()) && token.getName().equals(id + ".token")) token.delete();
        return new JSONObject().put("removed", id);
    }

    JSONObject setEnabled(String id, boolean enabled) throws Exception {
        JSONObject current = node(id);
        if (current.getBoolean("enabled") == enabled) return new JSONObject().put("node", current);
        if (current.optBoolean("demo")) {
            JSONObject stored = loadStore();
            stored.getJSONObject("nodes").getJSONObject(id).put("enabled", enabled);
            writeStore(stored);
            return new JSONObject().put("node", node(id));
        }
        JSONObject runtime = loadRuntime(), stored = loadStore(), saved = stored.getJSONObject("nodes");
        JSONObject entry = saved.optJSONObject(id);
        if (entry == null) saved.put(id, entry = new JSONObject());
        JSONArray servers = servers(runtime);
        if (enabled) {
            JSONObject disabled = entry.getJSONObject("disabled");
            entry.remove("disabled");
            writeStore(stored);
            writeServers(runtime, servers.put(disabled));
        } else {
            JSONObject server = null;
            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.optJSONObject(i);
                if (s != null && id.equals(s.optString("name"))) server = s;
            }
            if (server == null) throw new Invalid("NODE_NOT_FOUND");
            entry.put("disabled", server);
            writeStore(stored);
            writeServers(runtime, withoutServer(servers, id));
        }
        return new JSONObject().put("node", node(id));
    }

    /** Phone-side presentation; null or "" clears a field. The Agent/Skill path for device icons. */
    JSONObject appearance(JSONObject input) throws Exception {
        String id = input.getString("id");
        node(id);
        JSONObject stored = loadStore(), saved = stored.getJSONObject("nodes");
        JSONObject entry = saved.optJSONObject(id);
        if (entry == null) saved.put(id, entry = new JSONObject());
        JSONObject appearance = entry.optJSONObject("appearance");
        if (appearance == null) entry.put("appearance", appearance = new JSONObject());
        boolean changed = false;
        for (String key : new String[]{"displayName", "shortName", "deviceType", "icon"}) {
            if (!input.has(key)) continue;
            changed = true;
            Object value = input.get(key);
            if (value == JSONObject.NULL || "".equals(value)) { appearance.remove(key); continue; }
            appearance.put(key, key.equals("displayName") ? displayName(value) : key.equals("shortName") ? shortName(value)
                    : key.equals("deviceType") ? deviceType(value) : icon(value));
        }
        if (!changed) throw new Invalid("NOTHING_TO_UPDATE");
        writeStore(stored);
        return new JSONObject().put("node", node(id));
    }

    /**
     * Replace a node's routes: validated, stored in connect.json, and written into its mcpServers entry
     * (runtime.json when enabled, the switched-off copy otherwise) as {@code --urls a,b} ({@code --url}
     * for one route), so the agent's taku client and this page use the same ordered candidates.
     */
    JSONObject routes(JSONObject input) throws Exception {
        String id = input.getString("id");
        if (node(id).optBoolean("demo")) throw new Invalid("DEMO_NODE");
        JSONArray routes = routesInput(input.opt("routes"));
        JSONObject runtime = loadRuntime(), stored = loadStore(), saved = stored.getJSONObject("nodes");
        JSONObject entry = saved.optJSONObject(id);
        if (entry == null) saved.put(id, entry = new JSONObject());
        JSONArray servers = servers(runtime);
        JSONObject enabled = null;
        for (int i = 0; i < servers.length(); i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s != null && id.equals(s.optString("name")) && parseServer(s) != null) enabled = s;
        }
        entry.put("routes", routes);
        if (enabled != null) {
            withRoutes(enabled, routes);
            writeStore(stored);
            writeServers(runtime, servers);
        } else {
            JSONObject disabled = entry.optJSONObject("disabled");
            if (disabled == null || parseServer(disabled) == null) throw new Invalid("NODE_NOT_FOUND");
            withRoutes(disabled, routes);
            writeStore(stored);
        }
        return new JSONObject().put("node", node(id));
    }

    /** {@code [{label?,kind?,url}]} → 1…8 validated routes with normalized, unique urls and a kind each. */
    static JSONArray routesInput(Object value) throws Invalid {
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() == 0) throw new Invalid("INVALID_ROUTES");
        JSONArray given = (JSONArray) value;
        if (given.length() > MAX_ROUTES) throw new Invalid("TOO_MANY_ROUTES");
        JSONArray out = new JSONArray();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < given.length(); i++) {
            Object item = given.opt(i);
            if (!(item instanceof JSONObject)) throw new Invalid("INVALID_ROUTES");
            JSONObject route = (JSONObject) item;
            for (Iterator<String> it = route.keys(); it.hasNext(); ) {
                String key = it.next();
                if (!key.equals("label") && !key.equals("kind") && !key.equals("url")) throw new Invalid("INVALID_ROUTES");
            }
            Object rawUrl = route.opt("url");
            if (!(rawUrl instanceof String)) throw new Invalid("INVALID_URL");
            String url = url((String) rawUrl);
            if (!seen.add(url)) throw new Invalid("DUPLICATE_ROUTE");
            Object rawKind = route.opt("kind");
            String kind;
            if (rawKind == null || rawKind == JSONObject.NULL || "".equals(rawKind)) kind = routeKind(url);
            else if (rawKind instanceof String && ROUTE_KINDS.contains(rawKind)) kind = (String) rawKind;
            else throw new Invalid("INVALID_ROUTE_KIND");
            Object rawLabel = route.opt("label");
            String label = "";
            if (rawLabel != null && rawLabel != JSONObject.NULL) {
                if (!(rawLabel instanceof String)) throw new Invalid("INVALID_ROUTE_LABEL");
                label = ((String) rawLabel).trim();
                if (label.length() > MAX_ROUTE_LABEL_CHARS) throw new Invalid("INVALID_ROUTE_LABEL");
                for (int c = 0; c < label.length(); c++) if (Character.isISOControl(label.charAt(c))) throw new Invalid("INVALID_ROUTE_LABEL");
            }
            try {
                out.put(new JSONObject().put("label", label).put("kind", kind).put("url", url));
            } catch (Exception impossible) {
                throw new Invalid("INVALID_ROUTES");
            }
        }
        return out;
    }

    /**
     * Point an mcpServers entry at {@code routes}: {@code mcp-stdio --url u} or {@code --urls a,b}
     * first, its other arguments (token file, flags) kept as they were; a TAKU_URL env is dropped (the
     * arguments win) and the routes' direct hosts join NO_PROXY.
     */
    static void withRoutes(JSONObject server, JSONArray routes) throws Exception {
        JSONArray args = server.optJSONArray("args");
        StringBuilder list = new StringBuilder();
        Set<String> noProxy = new LinkedHashSet<>();
        for (int i = 0; i < routes.length(); i++) {
            String url = routes.getJSONObject(i).getString("url");
            if (list.length() > 0) list.append(',');
            list.append(url);
            String host = host(url);
            if (i == 0 || direct(host)) noProxy.add(host);
        }
        JSONArray rewritten = new JSONArray().put("mcp-stdio").put(routes.length() == 1 ? "--url" : "--urls").put(list.toString());
        if (args != null) for (int i = 1; i < args.length(); i++) {
            String arg = args.optString(i);
            if (arg.equals("--url") || arg.equals("--urls")) i++;
            else if (!arg.startsWith("--url=") && !arg.startsWith("--urls=")) rewritten.put(args.get(i));
        }
        server.put("args", rewritten);
        JSONArray env = server.optJSONArray("env"), kept = new JSONArray();
        if (env != null) for (int i = 0; i < env.length(); i++) {
            JSONObject item = env.optJSONObject(i);
            String name = item == null ? "" : item.optString("name");
            if (name.equals("TAKU_URL")) continue;
            if (name.equals("NO_PROXY")) {
                Set<String> merged = new LinkedHashSet<>();
                splitUrls(item.optString("value"), merged);
                merged.addAll(noProxy);
                noProxy = merged;
                continue;
            }
            kept.put(env.get(i));
        }
        if (!noProxy.isEmpty()) kept.put(new JSONObject().put("name", "NO_PROXY").put("value", String.join(",", noProxy)));
        server.put("env", kept);
    }

    static JSONArray withoutServer(JSONArray servers, String id) throws Exception {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < servers.length(); i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s == null || !id.equals(s.optString("name"))) kept.put(servers.get(i));
        }
        return kept;
    }

    /** runtime.json through its single authority: validated, size-bounded, atomic, other keys untouched. */
    void writeServers(JSONObject runtime, JSONArray servers) throws Exception {
        String before = AcpRuntimeSettings.revision(runtimeConfig);
        JSONObject acp = runtime.optJSONObject(RuntimeRouter.ACP);
        if (acp == null) runtime.put(RuntimeRouter.ACP, acp = new JSONObject());
        acp.put("mcpServers", servers);
        AcpRuntime.Options.fromJson(acp);
        byte[] bytes = runtime.toString(2).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > AcpRuntimeSettings.MAX_BYTES) throw new Invalid("RUNTIME_CONFIG_TOO_LARGE");
        if (!before.equals(AcpRuntimeSettings.revision(runtimeConfig))) throw new Invalid("REVISION_CONFLICT");
        AcpRuntimeSettings.atomicWrite(runtimeConfig, bytes);
    }

    void writeStore(JSONObject stored) throws Exception {
        writePrivate(store, stored.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    static void writePrivate(File file, byte[] bytes) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("STORE_UNAVAILABLE");
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(false, false);
        tmp.setWritable(true, true);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] readBounded(File file, int limit) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readStream(in, limit);
        }
    }

    static byte[] readStream(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new Invalid("TOO_LARGE");
        }
        return out.toByteArray();
    }
}
