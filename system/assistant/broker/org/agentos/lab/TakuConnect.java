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
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Taku Connect: the phone's taku-runtime nodes (Mac mini, TV box, servers …) for the GUI and for
 * Agents, through one path (NativeBridge op {@code connect}, gateway/open-access {@code connect/*}).
 *
 * <p>Source of truth for <em>which</em> nodes exist is {@code runtime.json} {@code acp.mcpServers}:
 * a node is an entry that runs {@code taku mcp-stdio --url <url> --token-file <file>} — exactly what
 * the on-device agent uses, so the page can never show a device the agent cannot reach (or hide one
 * it can). Phone-side presentation lives in {@code connect.json} next to it:
 *
 * <pre>{"schema":"taku.connect/1","nodes":{"taku_macmini":{
 *    "appearance":{"displayName":"…","deviceType":"mac_mini","icon":"builtin:mac_mini|https://…|data:image/…"},
 *    "disabled":{…the mcpServers entry while switched off…}}}}</pre>
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
    static final String DEFAULT_CLIENT = "/data/local/agentos/acp/bin/taku";
    static final String TOKEN_FILE_PREFIX = "/data/local/agentos/";
    static final int CONNECT_TIMEOUT_MS = 2500, READ_TIMEOUT_MS = 4000;

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

    /** Actions: list, probe {id}, test {url, token|tokenFile}, add, remove {id}, setEnabled {id, enabled}, appearance {id, …}. */
    synchronized JSONObject control(JSONObject input) throws Exception {
        String action = input.optString("action", "list");
        switch (action) {
            case "list": return list();
            case "probe": return probe(node(input.getString("id")));
            case "test": return test(input);
            case "add": return add(input);
            case "remove": return remove(input.getString("id"));
            case "setEnabled": return setEnabled(input.getString("id"), input.getBoolean("enabled"));
            case "appearance": return appearance(input);
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
        String url = "", tokenFile = "";
        for (int i = 1; i < args.length(); i++) {
            String arg = args.optString(i);
            String next = i + 1 < args.length() ? args.optString(i + 1) : "";
            if (arg.equals("--url")) { url = next; i++; }
            else if (arg.startsWith("--url=")) url = arg.substring(6);
            else if (arg.equals("--token-file")) { tokenFile = next; i++; }
            else if (arg.startsWith("--token-file=")) tokenFile = arg.substring(13);
        }
        JSONArray env = server.optJSONArray("env");
        if (env != null) for (int i = 0; i < env.length(); i++) {
            JSONObject item = env.optJSONObject(i);
            if (item != null && url.isEmpty() && "TAKU_URL".equals(item.optString("name"))) url = item.optString("value");
        }
        if (url.isEmpty()) return null;
        return new JSONObject().put("id", server.optString("name")).put("url", url).put("tokenFile", tokenFile);
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
            if (parsed == null || !seen.add(parsed.getString("id"))) continue;
            out.put(view(parsed, true, saved.optJSONObject(parsed.getString("id"))));
        }
        for (Iterator<String> it = saved.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject entry = saved.getJSONObject(id);
            JSONObject disabled = entry.optJSONObject("disabled");
            JSONObject parsed = disabled == null ? null : parseServer(disabled);
            if (parsed == null || !seen.add(id)) continue;
            out.put(view(parsed, false, entry));
        }
        return new JSONObject().put("schema", SCHEMA).put("nodes", out);
    }

    JSONObject view(JSONObject parsed, boolean enabled, JSONObject saved) throws Exception {
        String tokenFile = parsed.optString("tokenFile");
        URI uri = new URI(parsed.getString("url"));
        JSONObject appearance = saved == null ? null : saved.optJSONObject("appearance");
        return new JSONObject().put("id", parsed.getString("id")).put("url", parsed.getString("url"))
                .put("host", uri.getHost() == null ? "" : uri.getHost()).put("network", network(uri.getHost()))
                .put("enabled", enabled).put("hasToken", !tokenFile.isEmpty() && new File(tokenFile).isFile())
                .put("appearance", appearance == null ? new JSONObject() : appearance);
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

    JSONObject probe(JSONObject node) throws Exception {
        String file = tokenFile(node.getString("id"));
        String token = file.isEmpty() ? "" : readToken(new File(file));
        return probe(node.getString("url"), token).put("id", node.getString("id")).put("enabled", node.getBoolean("enabled"));
    }

    JSONObject test(JSONObject input) throws Exception {
        String url = url(input.optString("url"));
        return probe(url, token(input));
    }

    /** {@code status}: online | unauthorized | offline. Latency is the best of two /healthz round trips. */
    static JSONObject probe(String url, String token) throws Exception {
        URI uri = new URI(url);
        JSONObject out = new JSONObject().put("url", url).put("network", network(uri.getHost())).put("checkedAt", System.currentTimeMillis());
        long best = Long.MAX_VALUE;
        String failure = "";
        for (int i = 0; i < 2; i++) {
            long started = System.nanoTime();
            try {
                Response health = get(url + "/healthz", "");
                if (health.code != 200) { failure = "HTTP_" + health.code; break; }
                best = Math.min(best, (System.nanoTime() - started) / 1_000_000L);
            } catch (IOException unreachable) {
                failure = "UNREACHABLE";
                break;
            }
        }
        if (best == Long.MAX_VALUE) return out.put("status", "offline").put("online", false).put("error", failure.isEmpty() ? "UNREACHABLE" : failure);
        out.put("online", true).put("latencyMs", best);
        if (token.isEmpty()) return out.put("status", "unauthorized").put("error", "TOKEN_MISSING");
        Response caps;
        try {
            caps = get(url + "/v1/capabilities", token);
        } catch (IOException failed) {
            return out.put("status", "offline").put("online", false).put("error", "UNREACHABLE");
        }
        if (caps.code == 401) return out.put("status", "unauthorized").put("error", "UNAUTHORIZED");
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
        URL target = new URL(url);
        // LAN / Tailscale peers are never reached through the phone's proxy (as NO_PROXY for the agent).
        HttpURLConnection http = (HttpURLConnection) (direct(target.getHost()) ? target.openConnection(Proxy.NO_PROXY) : target.openConnection());
        try {
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(READ_TIMEOUT_MS);
            http.setInstanceFollowRedirects(false);
            if (!token.isEmpty()) http.setRequestProperty("Authorization", "Bearer " + token);
            int code = http.getResponseCode();
            InputStream in = code >= 400 ? http.getErrorStream() : http.getInputStream();
            return new Response(code, in == null ? "" : new String(readStream(in, 1024 * 1024), StandardCharsets.UTF_8));
        } finally {
            http.disconnect();
        }
    }

    /** lan | tailscale | loopback | internet, from the host alone (no DNS for names). */
    static String network(String host) {
        if (host == null || host.isEmpty()) return "internet";
        if (host.equals("localhost")) return "loopback";
        if (host.endsWith(".local")) return "lan";
        if (host.endsWith(".ts.net")) return "tailscale";
        int[] ip = ipv4(host);
        if (ip == null) return "internet";
        if (ip[0] == 127) return "loopback";
        if (ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31 || ip[0] == 169 && ip[1] == 254) return "lan";
        if (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127) return "tailscale";
        return "internet";
    }

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
        String url = url(input.optString("url"));
        String token = token(input);
        if (token.isEmpty()) throw new Invalid("TOKEN_REQUIRED");
        JSONObject check = probe(url, token);
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
        String host = new URI(url).getHost();
        JSONObject server = new JSONObject().put("name", id).put("command", client)
                .put("args", new JSONArray().put("mcp-stdio").put("--url").put(url).put("--token-file").put(tokenFile.getPath()))
                .put("env", new JSONArray().put(new JSONObject().put("name", "NO_PROXY").put("value", host)));
        JSONObject appearance = new JSONObject().put("displayName", name);
        if (input.has("deviceType")) appearance.put("deviceType", deviceType(input.get("deviceType")));
        else {
            JSONObject remote = check.getJSONObject("capabilities").optJSONObject("appearance");
            String advertised = remote == null ? "" : remote.optString("deviceType");
            if (DEVICE_TYPES.contains(advertised)) appearance.put("deviceType", advertised);
        }
        if (input.has("icon") && !icon(input.get("icon")).isEmpty()) appearance.put("icon", icon(input.get("icon")));
        saved.put(id, new JSONObject().put("appearance", appearance));
        writeStore(stored);
        writeServers(runtime, servers(runtime).put(server));
        return new JSONObject().put("node", node(id)).put("probe", check.put("id", id).put("enabled", true));
    }

    JSONObject remove(String id) throws Exception {
        node(id);
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
        for (String key : new String[]{"displayName", "deviceType", "icon"}) {
            if (!input.has(key)) continue;
            changed = true;
            Object value = input.get(key);
            if (value == JSONObject.NULL || "".equals(value)) { appearance.remove(key); continue; }
            appearance.put(key, key.equals("displayName") ? displayName(value) : key.equals("deviceType") ? deviceType(value) : icon(value));
        }
        if (!changed) throw new Invalid("NOTHING_TO_UPDATE");
        writeStore(stored);
        return new JSONObject().put("node", node(id));
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
