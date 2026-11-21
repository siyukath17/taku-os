package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** runtime.json read/update: validation, preservation of unmanaged keys, atomicity, revisions. */
public final class AcpRuntimeSettingsTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(File file, String patch, String code) throws Exception {
        String before = file.isFile() ? Files.readString(file.toPath()) : null;
        try {
            AcpRuntimeSettings.update(file, new JSONObject(patch), null);
            throw new AssertionError("accepted invalid patch " + patch);
        } catch (AcpRuntimeSettings.Invalid invalid) {
            check(invalid.getMessage().startsWith(code), patch + " -> " + code + " (got " + invalid.getMessage() + ")");
        }
        String after = file.isFile() ? Files.readString(file.toPath()) : null;
        check(java.util.Objects.equals(before, after), "rejected patch leaves the file untouched: " + patch);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("acp-settings").toFile();
        File file = new File(dir, "runtime.json");

        JSONObject empty = AcpRuntimeSettings.read(file);
        check(!empty.getBoolean("exists") && empty.isNull("default") && "codex".equals(empty.getString("effectiveDefault"))
                && "".equals(empty.getString("revision")), "absent runtime.json reads as codex with no revision");

        JSONObject written = AcpRuntimeSettings.update(file, new JSONObject()
                .put("default", "acp")
                .put("acp", new JSONObject().put("binary", "/data/local/agentos/acp/bin/agy")
                        .put("httpsProxy", "http://127.0.0.1:7890")
                        .put("extraArgs", new JSONArray().put("--verbose"))), null);
        check("acp".equals(written.getString("default")) && "acp".equals(written.getString("effectiveDefault")),
                "default acp with a binary is effective");
        check("/data/local/agentos/acp/bin/agy".equals(written.getJSONObject("acp").getString("binary"))
                && "http://127.0.0.1:7890".equals(written.getJSONObject("acp").getString("httpsProxy"))
                && "--verbose".equals(written.getJSONObject("acp").getJSONArray("extraArgs").getString(0)), "acp fields round-trip");
        check(!new File(dir, "runtime.json.settings.tmp").exists(), "no temp file left behind");
        check(file.canRead() && !Files.getPosixFilePermissions(file.toPath()).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                "runtime.json is owner-only");

        // The router reads exactly what the settings interface wrote.
        RuntimeRouter router = new RuntimeRouter(RuntimeRouterTest.CODEX, c -> RuntimeRouterTest.CODEX,
                () -> AcpRuntimeSettings.load(file), Map.of());
        RuntimeRouter.Route route = router.route(new JSONObject().put("id", "1").put("prompt", "x"));
        check("acp".equals(route.runtime) && "selected-by-config".equals(route.reason), "router honours the written default");

        // Unmanaged keys (home, env, mcpServers, ...) survive a patch; env values are never read back.
        JSONObject raw = new JSONObject(Files.readString(file.toPath()));
        raw.getJSONObject("acp").put("home", "/data/local/agentos/acp/home2").put("env", new JSONObject().put("GEMINI_API_KEY", "sk-secret"));
        raw.put("futureKey", 7);
        Files.writeString(file.toPath(), raw.toString());
        JSONObject read = AcpRuntimeSettings.read(file);
        check(!read.toString().contains("sk-secret") && "GEMINI_API_KEY".equals(read.getJSONObject("acp").getJSONArray("envNames").getString(0)),
                "read exposes env names only, never values");
        AcpRuntimeSettings.update(file, new JSONObject().put("acp", new JSONObject().put("httpsProxy", JSONObject.NULL)), null);
        JSONObject stored = new JSONObject(Files.readString(file.toPath()));
        check(!stored.getJSONObject("acp").has("httpsProxy"), "null clears a field");
        check("/data/local/agentos/acp/home2".equals(stored.getJSONObject("acp").getString("home"))
                && "sk-secret".equals(stored.getJSONObject("acp").getJSONObject("env").getString("GEMINI_API_KEY"))
                && stored.getInt("futureKey") == 7, "unmanaged keys are preserved byte-for-value");

        // Optimistic concurrency.
        String revision = AcpRuntimeSettings.read(file).getString("revision");
        check(revision.length() == 64, "revision is a sha-256");
        try {
            AcpRuntimeSettings.update(file, new JSONObject().put("default", "codex"), "deadbeef");
            throw new AssertionError("stale revision accepted");
        } catch (AcpRuntimeSettings.Invalid conflict) {
            check("REVISION_CONFLICT".equals(conflict.getMessage()), "stale expectedRevision conflicts");
        }
        JSONObject codex = AcpRuntimeSettings.update(file, new JSONObject().put("default", "codex"), revision);
        check("codex".equals(codex.getString("default")) && !revision.equals(codex.getString("revision")), "matching revision writes");

        // Validation: nothing invalid reaches disk.
        rejects(file, "{}", "EMPTY_PATCH");
        rejects(file, "{\"default\":\"claude\"}", "INVALID_DEFAULT");
        rejects(file, "{\"default\":1}", "INVALID_DEFAULT");
        rejects(file, "{\"runtime\":\"acp\"}", "UNKNOWN_FIELD:runtime");
        rejects(file, "{\"acp\":{\"home\":\"/tmp\"}}", "UNKNOWN_FIELD:acp.home");
        rejects(file, "{\"acp\":\"agy\"}", "INVALID_ACP");
        rejects(file, "{\"acp\":{\"binary\":\"agy\"}}", "INVALID_BINARY");
        rejects(file, "{\"acp\":{\"binary\":\"/data/../system/bin/sh\"}}", "INVALID_BINARY");
        rejects(file, "{\"acp\":{\"binary\":\"/data/local/agentos/agy\\n--evil\"}}", "INVALID_BINARY");
        rejects(file, "{\"acp\":{\"binary\":\"/data/local/tmp/agy\"}}", "BINARY_LOCATION_NOT_ALLOWED");
        rejects(file, "{\"acp\":{\"binary\":\"/sdcard/agy\"}}", "BINARY_LOCATION_NOT_ALLOWED");
        rejects(file, "{\"acp\":{\"binary\":\"/data/local/agentos//../tmp/agy\"}}", "INVALID_BINARY");
        // ADB shell callers cannot inject flags into the root-run agent; everything else stays open to them.
        String beforeShell = Files.readString(file.toPath());
        try {
            AcpRuntimeSettings.control(file, new JSONObject().put("action", "update").put("caller", "shell")
                    .put("patch", new JSONObject().put("acp", new JSONObject().put("extraArgs", new JSONArray().put("--x")))),
                    new AcpAuthHelper(new File(dir, "unused.json"), new FakeAcpAgent(), url -> {}, 1));
            throw new AssertionError("shell extraArgs accepted");
        } catch (AcpRuntimeSettings.Invalid denied) {
            check("PRIVILEGED_FIELD:acp.extraArgs".equals(denied.getMessage()) && beforeShell.equals(Files.readString(file.toPath())),
                    "shell callers cannot write extraArgs");
        }
        JSONObject shellWrite = AcpRuntimeSettings.update(file, new JSONObject().put("acp", new JSONObject()
                .put("binary", "/system_ext/bin/agy").put("httpsProxy", "http://10.0.2.2:8080")), null, false);
        check("/system_ext/bin/agy".equals(shellWrite.getJSONObject("acp").getString("binary")), "shell may set an allowed binary and proxy");
        rejects(file, "{\"acp\":{\"httpsProxy\":\"127.0.0.1:7890\"}}", "INVALID_PROXY");
        rejects(file, "{\"acp\":{\"httpsProxy\":\"ftp://127.0.0.1:21\"}}", "INVALID_PROXY");
        rejects(file, "{\"acp\":{\"httpsProxy\":\"http://127.0.0.1\"}}", "INVALID_PROXY");
        rejects(file, "{\"acp\":{\"httpsProxy\":\"http://user:pw@127.0.0.1:7890\"}}", "PROXY_CREDENTIALS_NOT_ALLOWED");
        rejects(file, "{\"acp\":{\"extraArgs\":\"--x\"}}", "INVALID_EXTRA_ARGS");
        rejects(file, "{\"acp\":{\"extraArgs\":[1]}}", "INVALID_EXTRA_ARGS");
        rejects(file, "{\"acp\":{\"extraArgs\":[\"\"]}}", "INVALID_EXTRA_ARGS");
        rejects(file, "{\"acp\":{\"extraArgs\":[\"--app-data-dir=/sdcard\"]}}", "MANAGED_FLAG:--app-data-dir");
        JSONArray many = new JSONArray();
        for (int i = 0; i < 17; i++) many.put("--a" + i);
        rejects(file, new JSONObject().put("acp", new JSONObject().put("extraArgs", many)).toString(), "INVALID_EXTRA_ARGS");
        // socks5 proxies and clearing the binary are valid.
        AcpRuntimeSettings.update(file, new JSONObject().put("default", "acp").put("acp", new JSONObject().put("httpsProxy", "socks5://10.0.2.2:1080").put("binary", "")), null);
        JSONObject unconfigured = AcpRuntimeSettings.read(file);
        check("codex".equals(unconfigured.getString("effectiveDefault")), "acp default without a binary is effectively codex");

        // A hand-broken file is reported, never silently overwritten.
        Files.writeString(file.toPath(), "{not json");
        JSONObject broken = AcpRuntimeSettings.read(file);
        check(!broken.getBoolean("valid") && "codex".equals(broken.getString("effectiveDefault")), "invalid file reads as invalid/codex");
        rejects(file, "{\"default\":\"codex\"}", "RUNTIME_CONFIG_INVALID");
        byte[] big = new byte[AcpRuntimeSettings.MAX_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        Files.write(file.toPath(), big);
        try {
            AcpRuntimeSettings.load(file);
            throw new AssertionError("oversized config loaded");
        } catch (AcpRuntimeSettings.Invalid tooLarge) {
            check("RUNTIME_CONFIG_TOO_LARGE".equals(tooLarge.getMessage()), "oversized runtime.json is rejected");
        }
        file.delete();

        // The control entry (NativeBridge op acpRuntime) dispatches and refuses unknown actions.
        AcpAuthHelper auth = new AcpAuthHelper(new File(dir, "acp-login.json"), new FakeAcpAgent(), url -> {}, 1000);
        JSONObject viaControl = AcpRuntimeSettings.control(file, new JSONObject().put("action", "update")
                .put("patch", new JSONObject().put("default", "codex")), auth);
        check("codex".equals(viaControl.getString("default")), "control update");
        check("idle".equals(AcpRuntimeSettings.control(file, new JSONObject().put("action", "signInStatus"), auth).getString("state")),
                "sign-in status is idle before any attempt");
        for (String bad : new String[]{"{\"action\":\"delete\"}", "{\"action\":\"update\",\"patch\":\"x\"}", "{\"action\":\"signIn\"}"}) {
            try {
                AcpRuntimeSettings.control(file, new JSONObject(bad), auth);
                throw new AssertionError("accepted " + bad);
            } catch (AcpRuntimeSettings.Invalid expected) {
                checks++;
            }
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        check(new JSONObject(text).getString("default").equals("codex"), "control writes the same file");
        remoteT3(dir);
        System.out.println("AcpRuntimeSettingsTest passed " + checks + " checks");
    }

    /** remoteT3: token references only, privileged routing fields, redacted reads, router agreement. */
    static void remoteT3(File dir) throws Exception {
        File file = new File(dir, "remote-runtime.json");
        String secret = "t3tok_should_never_be_echoed_123456";
        Map<String, String> env = Map.of("TAKU_REMOTE_T3_TOKEN", secret);
        JSONObject written = AcpRuntimeSettings.update(file, new JSONObject()
                .put("default", "remote_t3")
                .put("remoteT3", new JSONObject().put("endpoint", "https://t3.example.ts.net/")
                        .put("tokenEnv", "TAKU_REMOTE_T3_TOKEN").put("projectId", "taku").put("environmentId", "vps-1")
                        .put("model", "claude-opus-5-5").put("pollIntervalMs", 1500)), null);
        check("remote_t3".equals(written.getString("default")) && "remote_t3".equals(written.getString("effectiveDefault")),
                "default remote_t3 with endpoint and token reference is effective");
        JSONObject remote = written.getJSONObject("remoteT3");
        check("https://t3.example.ts.net".equals(remote.getString("endpoint")) && "TAKU_REMOTE_T3_TOKEN".equals(remote.getString("tokenEnv"))
                && remote.getLong("pollIntervalMs") == 1500 && remote.getBoolean("configured"), "remoteT3 fields round-trip");
        JSONObject withEnv = AcpRuntimeSettings.read(file, env);
        check(withEnv.getJSONObject("remoteT3").getBoolean("tokenPresent") && !withEnv.toString().contains(secret), "read: token presence, never value");
        check(!AcpRuntimeSettings.read(file, Map.of()).getJSONObject("remoteT3").getBoolean("tokenPresent"), "read: missing env reported");

        RuntimeRouter router = new RuntimeRouter(RuntimeRouterTest.CODEX, c -> RuntimeRouterTest.CODEX, c -> RuntimeRouterTest.CODEX,
                () -> AcpRuntimeSettings.load(file), Map.of());
        check("remote_t3".equals(router.route(new JSONObject().put("id", "1").put("prompt", "x")).runtime), "router honours a written remote default");

        // Plain-text credentials are refused in every spelling; nothing reaches disk.
        rejects(file, "{\"remoteT3\":{\"token\":\"" + secret + "\"}}", "PLAINTEXT_TOKEN_NOT_ALLOWED");
        rejects(file, "{\"remoteT3\":{\"Authorization\":\"Bearer x\"}}", "PLAINTEXT_TOKEN_NOT_ALLOWED");
        rejects(file, "{\"remoteT3\":{\"apiKey\":\"x\"}}", "PLAINTEXT_TOKEN_NOT_ALLOWED");
        rejects(file, "{\"remoteT3\":{\"turnLimitMs\":5}}", "UNKNOWN_FIELD:remoteT3.turnLimitMs");
        rejects(file, "{\"remoteT3\":\"x\"}", "INVALID_REMOTE_T3");
        rejects(file, "{\"remoteT3\":{\"endpoint\":\"http://evil.example\"}}", "INSECURE_ENDPOINT");
        rejects(file, "{\"remoteT3\":{\"endpoint\":\"https://u:p@t3.example\"}}", "ENDPOINT_CREDENTIALS_NOT_ALLOWED");
        rejects(file, "{\"remoteT3\":{\"tokenFile\":\"/sdcard/t3.token\"}}", "TOKEN_FILE_LOCATION_NOT_ALLOWED");
        rejects(file, "{\"remoteT3\":{\"pollIntervalMs\":1}}", "INVALID_POLL_INTERVAL");
        rejects(file, "{\"remoteT3\":{\"model\":\"a b\"}}", "INVALID_MODEL");
        rejects(file, "{\"default\":\"remote\"}", "INVALID_DEFAULT");

        // ADB shell: may tune project/model/poll, never where the token goes or where it comes from.
        for (String key : new String[]{"endpoint", "tokenEnv", "tokenFile"}) {
            String before = Files.readString(file.toPath());
            try {
                AcpRuntimeSettings.update(file, new JSONObject().put("remoteT3", new JSONObject().put(key, "http://127.0.0.1:9")), null, false);
                throw new AssertionError("shell wrote remoteT3." + key);
            } catch (AcpRuntimeSettings.Invalid denied) {
                check(("PRIVILEGED_FIELD:remoteT3." + key).equals(denied.getMessage()) && before.equals(Files.readString(file.toPath())),
                        "shell cannot write remoteT3." + key);
            }
        }
        JSONObject shell = AcpRuntimeSettings.update(file, new JSONObject().put("remoteT3", new JSONObject()
                .put("model", "claude-sonnet-5-5").put("projectId", "other").put("pollIntervalMs", 3000)), null, false);
        check("claude-sonnet-5-5".equals(shell.getJSONObject("remoteT3").getString("model"))
                && "https://t3.example.ts.net".equals(shell.getJSONObject("remoteT3").getString("endpoint")), "shell may tune non-sensitive fields");

        // Switching the token reference keeps exactly one source.
        AcpRuntimeSettings.update(file, new JSONObject().put("remoteT3", new JSONObject().put("tokenFile", "/data/local/agentos/secrets/t3.token")), null);
        JSONObject stored = new JSONObject(Files.readString(file.toPath())).getJSONObject("remoteT3");
        check("/data/local/agentos/secrets/t3.token".equals(stored.getString("tokenFile")) && !stored.has("tokenEnv"), "tokenFile replaces tokenEnv");
        AcpRuntimeSettings.update(file, new JSONObject().put("remoteT3", new JSONObject().put("model", JSONObject.NULL)), null);
        check(!new JSONObject(Files.readString(file.toPath())).getJSONObject("remoteT3").has("model"), "null clears a remote field");

        // A hand-edited plain-text token is reported as invalid and never echoed; the router refuses it.
        JSONObject raw = new JSONObject(Files.readString(file.toPath()));
        raw.getJSONObject("remoteT3").put("token", secret);
        Files.writeString(file.toPath(), raw.toString());
        JSONObject view = AcpRuntimeSettings.read(file, env);
        check(!view.toString().contains(secret) && !view.getJSONObject("remoteT3").getBoolean("valid")
                && "PLAINTEXT_TOKEN_NOT_ALLOWED".equals(view.getJSONObject("remoteT3").getString("error"))
                && "codex".equals(view.getString("effectiveDefault")), "hand-edited token: invalid, hidden, effectively codex");
        check("remote-t3-invalid".equals(router.route(new JSONObject().put("id", "1").put("prompt", "x")).reason), "router refuses it too");
        rejects(file, "{\"remoteT3\":{\"model\":\"m\"}}", "PLAINTEXT_TOKEN_NOT_ALLOWED");
        // An unrelated ACP patch is not blocked by the remote block and never strips it.
        AcpRuntimeSettings.update(file, new JSONObject().put("default", "codex"), null);
        check(secret.equals(new JSONObject(Files.readString(file.toPath())).getJSONObject("remoteT3").getString("token")),
                "unrelated patches preserve the hand-edited block for the owner to fix");
        file.delete();
    }
}
