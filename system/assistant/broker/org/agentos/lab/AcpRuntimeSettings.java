package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single authority for {@code runtime.json}: the router's loader, the {@code content call}
 * provider and the GUI all read and validate through this class, so an Agent and the GUI can never
 * disagree about what is stored or what is accepted.
 *
 * <p>Writable through {@link #update}: {@code default} ({@code codex} | {@code acp} |
 * {@code remote_t3} | null to clear), {@code acp.binary}, {@code acp.httpsProxy},
 * {@code acp.extraArgs}, and the {@code remoteT3} fields in {@link #REMOTE_PATCH_KEYS}. Every other key
 * in the file is preserved untouched. Writes are atomic (temp file + fsync + atomic rename),
 * owner-only, size-bounded, and guarded by an optional optimistic {@code expectedRevision}.
 *
 * <p>Reads never expose {@code acp.env} values (they may hold provider keys): only their names.
 * {@code remoteT3} stores only a token <em>reference</em> ({@code tokenEnv} or a root-owned
 * {@code tokenFile}); a plain-text token is refused on write and never read back.
 */
final class AcpRuntimeSettings {
    static final int MAX_BYTES = 65536;
    static final int MAX_EXTRA_ARGS = 16, MAX_ARG_CHARS = 256, MAX_PATH_CHARS = 512, MAX_PROXY_CHARS = 512;
    /** Flags the runtime manages itself; letting extraArgs repeat them would split the agent's state. */
    static final Set<String> MANAGED_FLAGS = Set.of("--app-data-dir");
    static final Set<String> PATCH_KEYS = Set.of("default", "acp", RuntimeRouter.REMOTE_T3_KEY);
    static final Set<String> RUNTIMES = Set.of(RuntimeRouter.CODEX, RuntimeRouter.ACP, RuntimeRouter.REMOTE_T3);
    static final Set<String> REMOTE_PATCH_KEYS = Set.of("endpoint", "tokenEnv", "tokenFile", "projectId", "environmentId", "model", "pollIntervalMs");
    /**
     * Where the bearer token goes and where it comes from: only root/system may change these, or an
     * ADB shell could point the root broker's token at a listener of its own.
     */
    static final Set<String> REMOTE_PRIVILEGED_KEYS = Set.of("endpoint", "tokenEnv", "tokenFile");
    static final Set<String> ACP_PATCH_KEYS = Set.of("binary", "httpsProxy", "extraArgs");
    /**
     * The broker runs as root and executes {@code acp.binary}: it must live where only root (or the
     * ROM) can place files, never in a shell- or app-writable location such as /data/local/tmp.
     */
    static final java.util.List<String> BINARY_PREFIXES =
            java.util.List.of("/data/local/agentos/", "/system/", "/system_ext/", "/product/", "/vendor/");

    private AcpRuntimeSettings() {}

    /** Validation failure; the message is a stable, public error code. */
    static final class Invalid extends IOException {
        Invalid(String code) { super(code); }
    }

    /** The parsed file, or null when absent. Throws when present but unusable (router: config-invalid). */
    static JSONObject load(File file) throws Exception {
        if (!file.isFile()) return null;
        if (file.length() > MAX_BYTES) throw new Invalid("RUNTIME_CONFIG_TOO_LARGE");
        return new JSONObject(new String(readBounded(file), StandardCharsets.UTF_8));
    }

    /** Agent-facing view: stored values plus the effective defaults, without secret env values. */
    static JSONObject read(File file) throws Exception {
        return read(file, System.getenv());
    }

    /** {@code env} is the broker environment {@code remoteT3.tokenEnv} resolves against (presence only). */
    static JSONObject read(File file, Map<String, String> env) throws Exception {
        JSONObject out = new JSONObject().put("path", file.getPath());
        JSONObject stored;
        try {
            stored = load(file);
        } catch (Exception unusable) {
            return out.put("exists", true).put("valid", false).put("revision", revision(file))
                    .put("effectiveDefault", RuntimeRouter.CODEX).put("error", "RUNTIME_CONFIG_INVALID");
        }
        out.put("exists", stored != null).put("valid", true).put("revision", revision(file));
        if (stored == null) stored = new JSONObject();
        String def = stored.optString("default", "");
        out.put("default", def.isEmpty() ? JSONObject.NULL : def);
        JSONObject acp = stored.optJSONObject(RuntimeRouter.ACP);
        AcpRuntime.Options effective = AcpRuntime.Options.fromJson(acp);
        JSONObject view = new JSONObject()
                .put("binary", effective.binary)
                .put("httpsProxy", effective.httpsProxy)
                .put("extraArgs", new JSONArray(effective.extraArgs))
                .put("home", effective.home)
                .put("appDataDir", effective.appDataDir)
                .put("cwd", effective.cwd)
                .put("args", new JSONArray(effective.args))
                .put("envNames", new JSONArray(effective.extraEnv.keySet()))
                .put("loginMethod", effective.loginMethod);
        out.put("acp", view);
        boolean remoteReady = false;
        JSONObject remoteStored = stored.optJSONObject(RuntimeRouter.REMOTE_T3_KEY);
        try {
            RemoteT3Runtime.Options remote = RemoteT3Runtime.Options.fromJson(remoteStored);
            out.put(RuntimeRouter.REMOTE_T3_KEY, remote.view(env).put("valid", true));
            remoteReady = remote.configured();
        } catch (Exception invalidRemote) {
            // Never echo the stored object: a hand-edited file may hold a plain-text token.
            out.put(RuntimeRouter.REMOTE_T3_KEY, new JSONObject().put("valid", false).put("configured", false)
                    .put("error", invalidRemote instanceof Invalid ? invalidRemote.getMessage() : "RUNTIME_CONFIG_INVALID"));
        }
        // What a background task without request/env overrides would actually use (see RuntimeRouter).
        boolean acpReady = RuntimeRouter.ACP.equals(def) && !effective.binary.isEmpty();
        String effectiveDefault = acpReady ? RuntimeRouter.ACP
                : RuntimeRouter.REMOTE_T3.equals(def) && remoteReady ? RuntimeRouter.REMOTE_T3 : RuntimeRouter.CODEX;
        out.put("effectiveDefault", effectiveDefault);
        return out;
    }

    /**
     * Merge {@code patch} into the stored file. {@code null} clears a field. Unknown keys and
     * invalid values are rejected before anything is written; the previous file stays intact.
     */
    static synchronized JSONObject update(File file, JSONObject patch, String expectedRevision) throws Exception {
        return update(file, patch, expectedRevision, true);
    }

    /**
     * {@code privileged} is false for ADB shell (uid 2000) callers: they may switch the runtime and
     * proxy and point at an allowed binary, but not inject flags into the root-run agent.
     */
    static synchronized JSONObject update(File file, JSONObject patch, String expectedRevision, boolean privileged) throws Exception {
        if (patch == null || patch.length() == 0) throw new Invalid("EMPTY_PATCH");
        JSONObject acpPatchProbe = patch.optJSONObject(RuntimeRouter.ACP);
        if (!privileged && acpPatchProbe != null && acpPatchProbe.has("extraArgs")) throw new Invalid("PRIVILEGED_FIELD:acp.extraArgs");
        JSONObject remotePatchProbe = patch.optJSONObject(RuntimeRouter.REMOTE_T3_KEY);
        if (!privileged && remotePatchProbe != null)
            for (String key : REMOTE_PRIVILEGED_KEYS)
                if (remotePatchProbe.has(key)) throw new Invalid("PRIVILEGED_FIELD:remoteT3." + key);
        String current = revision(file);
        if (expectedRevision != null && !expectedRevision.isEmpty() && !expectedRevision.equals(current))
            throw new Invalid("REVISION_CONFLICT");
        JSONObject stored;
        try {
            stored = load(file);
        } catch (Invalid tooLarge) {
            throw tooLarge;
        } catch (Exception unusable) {
            // Never silently replace a file someone hand-edited into an invalid state.
            throw new Invalid("RUNTIME_CONFIG_INVALID");
        }
        if (stored == null) stored = new JSONObject();
        for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!PATCH_KEYS.contains(key)) throw new Invalid("UNKNOWN_FIELD:" + key);
        }
        if (patch.has("default")) {
            if (patch.isNull("default")) stored.remove("default");
            else {
                Object value = patch.get("default");
                if (!(value instanceof String)) throw new Invalid("INVALID_DEFAULT");
                String runtime = ((String) value).trim();
                if (!RUNTIMES.contains(runtime)) throw new Invalid("INVALID_DEFAULT");
                stored.put("default", runtime);
            }
        }
        if (patch.has(RuntimeRouter.ACP)) {
            Object raw = patch.get(RuntimeRouter.ACP);
            if (!(raw instanceof JSONObject)) throw new Invalid("INVALID_ACP");
            JSONObject acpPatch = (JSONObject) raw;
            JSONObject acp = stored.optJSONObject(RuntimeRouter.ACP);
            if (acp == null) {
                if (stored.has(RuntimeRouter.ACP)) throw new Invalid("RUNTIME_CONFIG_INVALID");
                acp = new JSONObject();
            }
            for (Iterator<String> it = acpPatch.keys(); it.hasNext(); ) {
                String key = it.next();
                if (!ACP_PATCH_KEYS.contains(key)) throw new Invalid("UNKNOWN_FIELD:acp." + key);
                if (acpPatch.isNull(key)) {
                    acp.remove(key);
                    continue;
                }
                Object value = acpPatch.get(key);
                switch (key) {
                    case "binary": acp.put(key, binary(value)); break;
                    case "httpsProxy": acp.put(key, proxy(value)); break;
                    default: acp.put(key, extraArgs(value)); break;
                }
            }
            stored.put(RuntimeRouter.ACP, acp);
        }
        if (patch.has(RuntimeRouter.REMOTE_T3_KEY)) {
            Object raw = patch.get(RuntimeRouter.REMOTE_T3_KEY);
            if (!(raw instanceof JSONObject)) throw new Invalid("INVALID_REMOTE_T3");
            JSONObject remotePatch = (JSONObject) raw;
            JSONObject remote = stored.optJSONObject(RuntimeRouter.REMOTE_T3_KEY);
            if (remote == null) {
                if (stored.has(RuntimeRouter.REMOTE_T3_KEY)) throw new Invalid("RUNTIME_CONFIG_INVALID");
                remote = new JSONObject();
            }
            for (Iterator<String> it = remotePatch.keys(); it.hasNext(); ) {
                String key = it.next();
                if (RemoteT3Runtime.SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) throw new Invalid("PLAINTEXT_TOKEN_NOT_ALLOWED");
                if (!REMOTE_PATCH_KEYS.contains(key)) throw new Invalid("UNKNOWN_FIELD:remoteT3." + key);
                if (remotePatch.isNull(key)) {
                    remote.remove(key);
                    continue;
                }
                Object value = remotePatch.get(key);
                switch (key) {
                    case "endpoint": remote.put(key, RemoteT3Runtime.endpoint(value)); break;
                    case "tokenEnv": remote.put(key, RemoteT3Runtime.tokenEnv(value)); break;
                    case "tokenFile": remote.put(key, RemoteT3Runtime.tokenFile(value)); break;
                    case "projectId": remote.put(key, RemoteT3Runtime.identifier(value, "INVALID_PROJECT_ID")); break;
                    case "environmentId": remote.put(key, RemoteT3Runtime.identifier(value, "INVALID_ENVIRONMENT_ID")); break;
                    case "model": remote.put(key, RemoteT3Runtime.model(value)); break;
                    default: remote.put(key, RemoteT3Runtime.bounded(value, 250, 60_000, "INVALID_POLL_INTERVAL")); break;
                }
            }
            // Switching references: setting one clears the other so exactly one source stays.
            if (!remote.optString("tokenEnv", "").isEmpty() && remotePatch.has("tokenEnv") && !remotePatch.has("tokenFile")) remote.remove("tokenFile");
            if (!remote.optString("tokenFile", "").isEmpty() && remotePatch.has("tokenFile") && !remotePatch.has("tokenEnv")) remote.remove("tokenEnv");
            // The router must accept what we store (also refuses a hand-edited plain-text token).
            RemoteT3Runtime.Options.fromJson(remote);
            stored.put(RuntimeRouter.REMOTE_T3_KEY, remote);
        }
        // The router must accept what we store: parse it exactly as a task start would.
        AcpRuntime.Options.fromJson(stored.optJSONObject(RuntimeRouter.ACP));
        byte[] bytes = stored.toString(2).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new Invalid("RUNTIME_CONFIG_TOO_LARGE");
        if (!current.equals(revision(file))) throw new Invalid("REVISION_CONFLICT");
        atomicWrite(file, bytes);
        return read(file);
    }

    static String binary(Object value) throws Invalid {
        if (!(value instanceof String)) throw new Invalid("INVALID_BINARY");
        String path = ((String) value).trim();
        if (path.isEmpty()) return "";
        if (path.length() > MAX_PATH_CHARS || !path.startsWith("/") || hasControl(path)
                || path.contains("/../") || path.contains("/./") || path.contains("//") || path.endsWith("/..") || path.endsWith("/"))
            throw new Invalid("INVALID_BINARY");
        boolean allowed = false;
        for (String prefix : BINARY_PREFIXES) allowed |= path.startsWith(prefix);
        if (!allowed) throw new Invalid("BINARY_LOCATION_NOT_ALLOWED");
        return path;
    }

    static String proxy(Object value) throws Invalid {
        if (!(value instanceof String)) throw new Invalid("INVALID_PROXY");
        String text = ((String) value).trim();
        if (text.isEmpty()) return "";
        if (text.length() > MAX_PROXY_CHARS || hasControl(text) || text.contains(" ")) throw new Invalid("INVALID_PROXY");
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception malformed) {
            throw new Invalid("INVALID_PROXY");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https", "socks5", "socks5h").contains(scheme)) throw new Invalid("INVALID_PROXY");
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getPort() < 1 || uri.getPort() > 65535)
            throw new Invalid("INVALID_PROXY");
        // runtime.json is not a credential store: proxy credentials belong in the agent's own config.
        if (uri.getRawUserInfo() != null) throw new Invalid("PROXY_CREDENTIALS_NOT_ALLOWED");
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !path.equals("/")) || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new Invalid("INVALID_PROXY");
        return text;
    }

    static JSONArray extraArgs(Object value) throws Exception {
        if (!(value instanceof JSONArray)) throw new Invalid("INVALID_EXTRA_ARGS");
        JSONArray input = (JSONArray) value;
        if (input.length() > MAX_EXTRA_ARGS) throw new Invalid("INVALID_EXTRA_ARGS");
        JSONArray out = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            Object item = input.get(i);
            if (!(item instanceof String)) throw new Invalid("INVALID_EXTRA_ARGS");
            String arg = (String) item;
            if (arg.isEmpty() || arg.length() > MAX_ARG_CHARS || hasControl(arg)) throw new Invalid("INVALID_EXTRA_ARGS");
            String flag = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (MANAGED_FLAGS.contains(flag)) throw new Invalid("MANAGED_FLAG:" + flag);
            out.put(arg);
        }
        return out;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    /** SHA-256 of the stored bytes ("" when absent): the optimistic-concurrency token. */
    static String revision(File file) throws Exception {
        if (!file.isFile()) return "";
        if (file.length() > MAX_BYTES) return "oversized";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(readBounded(file));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static byte[] readBounded(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) throw new Invalid("RUNTIME_CONFIG_TOO_LARGE");
            }
            return out.toByteArray();
        }
    }

    /** temp + fsync + atomic rename; owner-only (the broker is the only reader). */
    static void atomicWrite(File file, byte[] bytes) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dir, file.getName() + ".settings.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(false, false);
        tmp.setWritable(true, true);
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            tmp.delete();
            throw failed;
        }
    }

    // ---- standard read/write entry: NativeBridge op "acpRuntime" (content://org.agentos.assistant.acp-runtime) ----

    /**
     * Actions: {@code read}; {@code update} ({@code patch}, optional {@code expectedRevision});
     * {@code signIn}; {@code signInStatus}; {@code signInCancel}.
     */
    static JSONObject control(File file, JSONObject input, AcpAuthHelper auth) throws Exception {
        String action = input.optString("action", "read");
        switch (action) {
            case "read": return read(file);
            case "update": {
                Object patch = input.opt("patch");
                if (!(patch instanceof JSONObject)) throw new Invalid("INVALID_PATCH");
                // NativeBridge serves only root/system sockets; the provider labels shell callers.
                boolean privileged = !"shell".equals(input.optString("caller"));
                return update(file, (JSONObject) patch, input.optString("expectedRevision", ""), privileged);
            }
            case "signIn": {
                JSONObject stored = load(file);
                JSONObject acp = stored == null ? null : stored.optJSONObject(RuntimeRouter.ACP);
                AcpRuntime.Options options = AcpRuntime.Options.fromJson(acp);
                if (options.binary.isEmpty()) throw new Invalid("ACP_UNCONFIGURED");
                return auth.start(options, acp == null ? null : acp.optJSONArray("authUrlHosts"));
            }
            case "signInStatus": return auth.status();
            case "signInCancel": return auth.cancel();
            default: throw new Invalid("UNKNOWN_ACTION");
        }
    }
}
