package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The second lane: hands a background task to a remote T3 Code host (a VPS or Mac reached over
 * HTTPS or Tailscale) and reconciles the remote run back into the task directory.
 *
 * <p>Lifecycle: submit (idempotent, retried with backoff on transient failures) → poll the run's
 * status every {@code pollIntervalMs}, writing throttled {@code progress.json} for the Dynamic
 * Island → on the task's {@code cancel} marker or the turn budget, ask the remote host to interrupt
 * → write the terminal records exactly as every {@link TaskRuntime} does ({@code result.txt},
 * {@code state.json}, {@code done}, and a redacted {@code diagnostic.json} on failures).
 *
 * <p>Credentials: runtime.json holds only a reference ({@code tokenEnv} or a root-owned
 * {@code tokenFile}), never a token. The token is resolved per task, kept in memory, sent only to
 * the configured endpoint, and scrubbed from everything this runtime persists.
 */
final class RemoteT3Runtime implements TaskRuntime {
    static final String RUNTIME = "remote_t3";
    static final int MAX_REPLY_CHARS = 1024 * 1024;
    static final int MAX_ENDPOINT_CHARS = 512, MAX_TOKEN_BYTES = 4096;
    /** Root-owned locations only: the broker runs as root and must not read a shell-planted token. */
    static final List<String> TOKEN_FILE_PREFIXES = List.of("/data/local/agentos/", "/data/misc/agentos/");
    /** Keys that would hold a credential in plain text; runtime.json refuses them outright. */
    static final Set<String> SECRET_KEYS = Set.of("token", "accesstoken", "apikey", "secret", "password", "authorization", "bearer");
    /** Keys a single request may override; endpoint and token never come from a request. */
    static final Set<String> REQUEST_KEYS = Set.of("projectId", "environmentId", "model");
    /**
     * A healthy remote run can repeat the same summary for minutes (a long build); the island marks a
     * running card stale after 120 s without updates, so progress.json is refreshed at least this often.
     */
    static final long HEARTBEAT_MILLIS = 60_000;

    interface Notifier {
        void taskUpdated(String taskId);
    }

    /** Resolves the bearer token for one task; null or empty means not configured or missing. */
    interface TokenSource {
        String token() throws IOException;
    }

    /** Time seam so tests can run retries, polling and timeouts in virtual time. */
    interface Clock {
        long millis();

        void sleep(long millis) throws InterruptedException;

        Clock SYSTEM = new Clock() {
            @Override public long millis() { return System.currentTimeMillis(); }
            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
    }

    /** Parsed and validated {@code runtime.json "remoteT3"} (plus per-request overrides). */
    static final class Options {
        String endpoint = "", tokenEnv = "", tokenFile = "", projectId = "", environmentId = "", model = "";
        long pollIntervalMillis = 2000;
        long requestTimeoutMillis = TimeUnit.SECONDS.toMillis(15);
        long turnLimitMillis = TimeUnit.MINUTES.toMillis(60);
        long unreachableLimitMillis = TimeUnit.MINUTES.toMillis(3);
        long cancelGraceMillis = TimeUnit.SECONDS.toMillis(30);
        long progressIntervalMillis = 1000;
        int maxSubmitAttempts = 5;

        /** Endpoint and a token reference are present: the router may select this runtime. */
        boolean configured() {
            return !endpoint.isEmpty() && (!tokenEnv.isEmpty() || !tokenFile.isEmpty());
        }

        String tokenSource() {
            return !tokenEnv.isEmpty() ? "env" : !tokenFile.isEmpty() ? "file" : "none";
        }

        static Options fromJson(JSONObject c) throws Exception {
            Options o = new Options();
            if (c == null) return o;
            for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                String key = it.next();
                if (SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) throw new AcpRuntimeSettings.Invalid("PLAINTEXT_TOKEN_NOT_ALLOWED");
            }
            if (c.has("endpoint")) o.endpoint = endpoint(c.get("endpoint"));
            if (c.has("tokenEnv")) o.tokenEnv = tokenEnv(c.get("tokenEnv"));
            if (c.has("tokenFile")) o.tokenFile = tokenFile(c.get("tokenFile"));
            if (!o.tokenEnv.isEmpty() && !o.tokenFile.isEmpty()) throw new AcpRuntimeSettings.Invalid("AMBIGUOUS_TOKEN_REFERENCE");
            if (c.has("projectId")) o.projectId = identifier(c.get("projectId"), "INVALID_PROJECT_ID");
            if (c.has("environmentId")) o.environmentId = identifier(c.get("environmentId"), "INVALID_ENVIRONMENT_ID");
            if (c.has("model")) o.model = model(c.get("model"));
            if (c.has("pollIntervalMs")) o.pollIntervalMillis = bounded(c.get("pollIntervalMs"), 250, 60_000, "INVALID_POLL_INTERVAL");
            if (c.has("requestTimeoutMs")) o.requestTimeoutMillis = bounded(c.get("requestTimeoutMs"), 1000, 120_000, "INVALID_REQUEST_TIMEOUT");
            if (c.has("turnLimitMs")) o.turnLimitMillis = bounded(c.get("turnLimitMs"), 10_000, TimeUnit.HOURS.toMillis(12), "INVALID_TURN_LIMIT");
            if (c.has("unreachableLimitMs")) o.unreachableLimitMillis = bounded(c.get("unreachableLimitMs"), 1000, TimeUnit.HOURS.toMillis(1), "INVALID_UNREACHABLE_LIMIT");
            if (c.has("cancelGraceMs")) o.cancelGraceMillis = bounded(c.get("cancelGraceMs"), 1000, TimeUnit.MINUTES.toMillis(10), "INVALID_CANCEL_GRACE");
            if (c.has("progressIntervalMs")) o.progressIntervalMillis = bounded(c.get("progressIntervalMs"), 0, 60_000, "INVALID_PROGRESS_INTERVAL");
            if (c.has("maxSubmitAttempts")) o.maxSubmitAttempts = (int) bounded(c.get("maxSubmitAttempts"), 1, 20, "INVALID_SUBMIT_ATTEMPTS");
            return o;
        }

        /**
         * The stored config with a request's {@code remoteT3} overrides applied. Only
         * {@link #REQUEST_KEYS} are accepted: a request must never redirect the token elsewhere.
         */
        static JSONObject merge(JSONObject stored, Object requestOverrides) throws Exception {
            JSONObject merged = new JSONObject(stored.toString());
            if (requestOverrides == null || JSONObject.NULL.equals(requestOverrides)) return merged;
            if (!(requestOverrides instanceof JSONObject)) throw new AcpRuntimeSettings.Invalid("INVALID_REMOTE_T3");
            JSONObject overrides = (JSONObject) requestOverrides;
            for (Iterator<String> it = overrides.keys(); it.hasNext(); ) {
                String key = it.next();
                if (!REQUEST_KEYS.contains(key)) throw new AcpRuntimeSettings.Invalid("UNKNOWN_FIELD:remoteT3." + key);
                merged.put(key, overrides.get(key));
            }
            return merged;
        }

        /** Non-secret, agent-facing view. Never contains a token value. */
        JSONObject view(Map<String, String> env) throws Exception {
            boolean present;
            try {
                String token = RemoteT3Runtime.tokenSource(this, env).token();
                present = token != null && !token.isEmpty();
            } catch (IOException unreadable) {
                present = false;
            }
            return new JSONObject()
                    .put("endpoint", endpoint)
                    .put("tokenEnv", tokenEnv)
                    .put("tokenFile", tokenFile)
                    .put("tokenSource", tokenSource())
                    .put("tokenPresent", present)
                    .put("projectId", projectId)
                    .put("environmentId", environmentId)
                    .put("model", model)
                    .put("pollIntervalMs", pollIntervalMillis)
                    .put("requestTimeoutMs", requestTimeoutMillis)
                    .put("turnLimitMs", turnLimitMillis)
                    .put("configured", configured());
        }
    }

    // ---- validation (shared with AcpRuntimeSettings; codes are public, stable error strings) ----

    /**
     * {@code https://host[:port][/path]}, or plain {@code http} only to this device's loopback
     * ({@code 127.0.0.1}) or a Tailscale address ({@code 100.64.0.0/10}), where the tunnel itself
     * encrypts. No credentials, query or fragment. Returned without a trailing slash.
     */
    static String endpoint(Object value) throws AcpRuntimeSettings.Invalid {
        if (!(value instanceof String)) throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        String text = ((String) value).trim();
        if (text.isEmpty()) return "";
        if (text.length() > MAX_ENDPOINT_CHARS || hasControl(text) || text.contains(" ") || text.contains("\\"))
            throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception malformed) {
            throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null) {
            if (uri.getRawUserInfo() != null) throw new AcpRuntimeSettings.Invalid("ENDPOINT_CREDENTIALS_NOT_ALLOWED");
            throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
            throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.contains("/../") || path.endsWith("/..") || path.contains("/./") || path.contains("//"))
            throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        if ("http".equals(scheme)) {
            if (!"127.0.0.1".equals(host) && !tailscale(host)) throw new AcpRuntimeSettings.Invalid("INSECURE_ENDPOINT");
        } else if (!"https".equals(scheme)) {
            throw new AcpRuntimeSettings.Invalid("INVALID_ENDPOINT");
        }
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    /** IPv4 literal in Tailscale's CGNAT range 100.64.0.0/10. */
    static boolean tailscale(String host) {
        if (!host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\.");
        for (String part : parts) if (Integer.parseInt(part) > 255) return false;
        int second = Integer.parseInt(parts[1]);
        return "100".equals(parts[0]) && second >= 64 && second <= 127;
    }

    static String tokenEnv(Object value) throws AcpRuntimeSettings.Invalid {
        if (!(value instanceof String)) throw new AcpRuntimeSettings.Invalid("INVALID_TOKEN_ENV");
        String name = ((String) value).trim();
        if (name.isEmpty()) return "";
        if (!name.matches("[A-Z_][A-Z0-9_]{0,63}")) throw new AcpRuntimeSettings.Invalid("INVALID_TOKEN_ENV");
        return name;
    }

    static String tokenFile(Object value) throws AcpRuntimeSettings.Invalid {
        if (!(value instanceof String)) throw new AcpRuntimeSettings.Invalid("INVALID_TOKEN_FILE");
        String path = ((String) value).trim();
        if (path.isEmpty()) return "";
        if (path.length() > 512 || !path.startsWith("/") || hasControl(path) || path.contains("/../") || path.contains("/./")
                || path.contains("//") || path.endsWith("/..") || path.endsWith("/"))
            throw new AcpRuntimeSettings.Invalid("INVALID_TOKEN_FILE");
        boolean allowed = false;
        for (String prefix : TOKEN_FILE_PREFIXES) allowed |= path.startsWith(prefix);
        if (!allowed) throw new AcpRuntimeSettings.Invalid("TOKEN_FILE_LOCATION_NOT_ALLOWED");
        return path;
    }

    static String identifier(Object value, String code) throws AcpRuntimeSettings.Invalid {
        if (!(value instanceof String)) throw new AcpRuntimeSettings.Invalid(code);
        String id = ((String) value).trim();
        if (id.isEmpty()) return "";
        if (!id.matches("[A-Za-z0-9._:-]{1,128}")) throw new AcpRuntimeSettings.Invalid(code);
        return id;
    }

    static String model(Object value) throws AcpRuntimeSettings.Invalid {
        if (!(value instanceof String)) throw new AcpRuntimeSettings.Invalid("INVALID_MODEL");
        String id = ((String) value).trim();
        if (id.isEmpty()) return "";
        if (!id.matches("[A-Za-z0-9._:/@+-]{1,128}")) throw new AcpRuntimeSettings.Invalid("INVALID_MODEL");
        return id;
    }

    static long bounded(Object value, long min, long max, String code) throws AcpRuntimeSettings.Invalid {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new AcpRuntimeSettings.Invalid(code);
        long n = ((Number) value).longValue();
        if (n < min || n > max) throw new AcpRuntimeSettings.Invalid(code);
        return n;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    /** Device token resolution: the broker's environment variable, or a bounded root-owned file. */
    static TokenSource tokenSource(Options options, Map<String, String> env) {
        return () -> {
            if (!options.tokenEnv.isEmpty()) {
                String value = env == null ? null : env.get(options.tokenEnv);
                return value == null ? null : value.trim();
            }
            if (options.tokenFile.isEmpty()) return null;
            File file = new File(options.tokenFile);
            if (!file.isFile()) return null;
            if (file.length() > MAX_TOKEN_BYTES) throw new IOException("token file too large");
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_TOKEN_BYTES) throw new IOException("token file too large");
                }
                String token = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    if (c <= ' ' || c == 127) throw new IOException("token file is not a single token");
                }
                return token;
            }
        };
    }

    // ---- runtime ------------------------------------------------------------------------------------

    private final Options options;
    private final RemoteT3Transport transport;
    private final TokenSource tokens;
    private final Notifier notifier;
    private final Runnable idle;
    private final Clock clock;

    RemoteT3Runtime(Options options, RemoteT3Transport transport, TokenSource tokens, Notifier notifier, Runnable idle, Clock clock) {
        this.options = options;
        this.transport = transport;
        this.tokens = tokens;
        this.notifier = notifier == null ? id -> {} : notifier;
        this.idle = idle == null ? () -> {} : idle;
        this.clock = clock == null ? Clock.SYSTEM : clock;
    }

    @Override
    public String name() {
        return RUNTIME;
    }

    @Override
    public void run(File directory, JSONObject request) throws Exception {
        new Session(directory, request).execute();
    }

    private final class Session {
        final File directory;
        final JSONObject request;
        final String taskId;
        String token = "", runId = "", phase = "starting", summary = "", tool = "", plan = "", remoteStatus = "";
        boolean interruptRequested, interruptSent, timedOut;
        long deadline, stopDeadline, lastContact, lastProgressWrite;

        Session(File directory, JSONObject request) throws Exception {
            this.directory = directory;
            this.request = request;
            this.taskId = request.getString("id");
        }

        boolean cancelMarked() {
            return new File(directory, "cancel").exists();
        }

        void execute() throws Exception {
            if (cancelMarked()) {
                finish("cancelled", "Cancelled before execution.", null);
                return;
            }
            TaskRecords.state(directory, request, "running", "Sending the task to remote T3…");
            notifier.taskUpdated(taskId);
            try {
                String resolved = tokens.token();
                token = resolved == null ? "" : resolved;
            } catch (IOException unreadable) {
                token = "";
            }
            if (token.isEmpty()) {
                diagnostic("token-missing", "token reference (" + options.tokenSource() + ") resolved to nothing");
                finish("needs_user", "Remote T3 is not authorized on this phone: set the remote token reference in Taku settings, then resubmit.", null);
                return;
            }
            RemoteT3Transport.Call call = new RemoteT3Transport.Call(options.endpoint, token, (int) options.requestTimeoutMillis);
            deadline = clock.millis() + options.turnLimitMillis;
            progress("Sending the task to remote T3…", true);
            JSONObject submitted = submit(call);
            if (submitted == null) return;
            runId = submitted.optString("runId", "").trim();
            if (runId.isEmpty() || runId.length() > 256) {
                diagnostic("protocol", "submit response without a usable runId");
                finish("failed", "Remote T3 answered in an unexpected format. Check the remote host version, then retry.", null);
                return;
            }
            String host;
            try {
                host = new URI(options.endpoint).getHost();
            } catch (Exception impossible) {
                host = "";
            }
            TaskRecords.write(new File(directory, "runtime-info.json"), new JSONObject()
                    .put("taskId", taskId)
                    .put("runtime", RUNTIME)
                    .put("endpointHost", host)
                    .put("runId", clip(scrub(runId), 256))
                    .put("threadId", clip(scrub(submitted.optString("threadId", "")), 256))
                    .put("projectId", options.projectId)
                    .put("environmentId", options.environmentId)
                    .put("model", options.model)
                    .put("submittedAt", clock.millis()).toString());
            lastContact = clock.millis();
            if (apply(submitted)) return;
            poll(call);
        }

        /** The run body: the prompt and routing fields only (no screenshot or local paths). */
        JSONObject runBody() throws Exception {
            JSONObject body = new JSONObject()
                    .put("clientTaskId", taskId)
                    .put("prompt", request.getString("prompt"))
                    .put("source", "taku-phone");
            if (!options.projectId.isEmpty()) body.put("projectId", options.projectId);
            if (!options.environmentId.isEmpty()) body.put("environmentId", options.environmentId);
            if (!options.model.isEmpty()) body.put("model", options.model);
            String permission = request.optString("permissionMode", "");
            if (!permission.isEmpty()) body.put("permissionMode", permission);
            return body;
        }

        /** Submit with exponential backoff on transient failures; null when the task already ended. */
        JSONObject submit(RemoteT3Transport.Call call) throws Exception {
            JSONObject body = runBody();
            String key = "taku-task-" + taskId;
            long backoff = 500;
            for (int attempt = 1; ; attempt++) {
                try {
                    return transport.submit(call, key, body);
                } catch (RemoteT3Transport.Failure failure) {
                    if (failure.kind == RemoteT3Transport.Failure.Kind.AUTH) {
                        authFailed(failure);
                        return null;
                    }
                    if (failure.kind != RemoteT3Transport.Failure.Kind.TRANSIENT) {
                        diagnostic("submit-rejected", failure.getMessage());
                        finish("failed", "Remote T3 rejected the task. Check the remote project settings, then retry.", null);
                        return null;
                    }
                    if (attempt >= options.maxSubmitAttempts || clock.millis() + backoff >= deadline) {
                        diagnostic("unreachable", "submit: " + failure.getMessage() + " after " + attempt + " attempts");
                        finish("failed", "Remote T3 could not be reached. Check the network and the remote host, then retry.", null);
                        return null;
                    }
                    progress("Remote T3 is not responding; retrying…", true);
                    if (pause(backoff)) {
                        // The remote host may already hold the task (a lost response); say so honestly.
                        finish("cancelled", "Cancelled. Remote T3 may have received the task before the network failed.", null);
                        return null;
                    }
                    backoff = Math.min(backoff * 2, 8000);
                }
            }
        }

        void poll(RemoteT3Transport.Call call) throws Exception {
            while (true) {
                long now = clock.millis();
                if (!interruptRequested && cancelMarked()) {
                    interruptRequested = true;
                    stopDeadline = now + options.cancelGraceMillis;
                    phase = "stopping";
                    progress("Stopping. Completed actions will not be automatically undone.", true);
                }
                if (!interruptRequested && now >= deadline) {
                    interruptRequested = true;
                    timedOut = true;
                    stopDeadline = now + options.cancelGraceMillis;
                    phase = "stopping";
                    progress("Execution timed out; stopping the remote run…", true);
                }
                if (interruptRequested && !interruptSent) {
                    try {
                        JSONObject answer = transport.interrupt(call, runId);
                        interruptSent = true;
                        lastContact = clock.millis();
                        if (apply(answer)) return;
                    } catch (RemoteT3Transport.Failure failure) {
                        // 4xx/404 means the run cannot be interrupted any more (already over): read its status.
                        if (failure.kind == RemoteT3Transport.Failure.Kind.REJECTED || failure.kind == RemoteT3Transport.Failure.Kind.NOT_FOUND)
                            interruptSent = true;
                        // Transient or auth: try again on the next tick, bounded by stopDeadline.
                    }
                }
                if (interruptRequested && clock.millis() >= stopDeadline) {
                    diagnostic(timedOut ? "timeout" : "stop-unconfirmed", "remote status " + remoteStatus);
                    finish("failed", timedOut
                            ? "Execution timed out and remote T3 did not confirm stopping. Check the remote run before retrying."
                            : "Stopping was not confirmed by remote T3. Check the remote run before retrying.", null);
                    return;
                }
                pause(options.pollIntervalMillis);
                try {
                    JSONObject snapshot = transport.status(call, runId);
                    lastContact = clock.millis();
                    if (apply(snapshot)) return;
                } catch (RemoteT3Transport.Failure failure) {
                    switch (failure.kind) {
                        case AUTH:
                            authFailed(failure);
                            return;
                        case NOT_FOUND:
                            diagnostic("run-lost", failure.getMessage());
                            finish("failed", "Remote T3 no longer knows this run. Check the remote host, then retry.", null);
                            return;
                        case REJECTED:
                            diagnostic("status-rejected", failure.getMessage());
                            finish("failed", "Remote T3 refused the status request. Check the remote host version, then retry.", null);
                            return;
                        default:
                            if (clock.millis() - lastContact >= options.unreachableLimitMillis) {
                                diagnostic("unreachable", "status: " + failure.getMessage());
                                finish("failed", "Lost contact with remote T3; the remote run may still be running. Check it before retrying.", null);
                                return;
                            }
                            progress("Reconnecting to remote T3…", false);
                    }
                }
            }
        }

        /**
         * Sleeps in short slices, servicing the broker loop. Returns true as soon as a new cancel
         * marker appears (so a stop request never waits a whole poll interval).
         */
        boolean pause(long millis) throws Exception {
            long remaining = millis;
            while (remaining > 0) {
                long slice = Math.min(remaining, 100);
                clock.sleep(slice);
                remaining -= slice;
                idle.run();
                if (!interruptRequested && cancelMarked()) return true;
            }
            return false;
        }

        /** Reconciles one remote snapshot; true once the task's terminal records are written. */
        boolean apply(JSONObject snapshot) throws Exception {
            String status = snapshot.optString("status", "").trim().toLowerCase(Locale.ROOT);
            if (status.isEmpty()) status = remoteStatus.isEmpty() ? "queued" : remoteStatus;
            boolean statusChanged = !status.equals(remoteStatus);
            remoteStatus = status;
            switch (status) {
                case "completed": {
                    JSONObject result = snapshot.optJSONObject("result");
                    String text = result != null ? result.optString("message", "") : snapshot.optString("summary", "");
                    text = text.trim();
                    if (text.length() > MAX_REPLY_CHARS) text = text.substring(0, MAX_REPLY_CHARS);
                    String message = text.isEmpty() ? "Remote T3 finished." : headline(scrub(text));
                    phase = "replying";
                    progress(message, true);
                    finish("completed", message, text.isEmpty() ? null : scrubToken(text));
                    return true;
                }
                case "cancelled": {
                    String message = timedOut ? "Execution timed out. Check the results of any actions."
                            : interruptRequested ? "Stopped. Completed actions will not be automatically undone."
                            : "The run was cancelled on the remote host.";
                    progress(message, true);
                    finish(timedOut ? "failed" : "cancelled", message, null);
                    return true;
                }
                case "failed": {
                    JSONObject error = snapshot.optJSONObject("error");
                    String code = error == null ? "" : error.optString("code", "");
                    String detail = error == null ? snapshot.optString("summary", "") : error.optString("message", "");
                    diagnostic("remote-failed", (code.isEmpty() ? "" : code + ": ") + detail);
                    String shown = clip(scrub(detail), 200);
                    String message = shown.isEmpty() ? "Remote T3 could not finish the task. Check the remote run before retrying."
                            : "Remote T3 could not finish the task: " + shown;
                    progress(message, true);
                    finish("failed", message, null);
                    return true;
                }
                default: {
                    String nextPhase;
                    if (interruptRequested) nextPhase = "stopping";
                    else if ("queued".equals(status)) nextPhase = "queued";
                    else {
                        String remotePhase = snapshot.optString("phase", "");
                        nextPhase = Set.of("thinking", "tool", "replying").contains(remotePhase) ? remotePhase : "working";
                    }
                    boolean phaseChanged = !nextPhase.equals(phase);
                    phase = nextPhase;
                    tool = "tool".equals(phase) ? clip(scrub(snapshot.optString("tool", "")), 80) : "";
                    JSONObject progress = snapshot.optJSONObject("progress");
                    plan = "";
                    if (progress != null) {
                        long done = progress.optLong("done", -1), total = progress.optLong("total", -1);
                        if (total > 0 && done >= 0 && done <= total && total < 10_000) plan = done + "/" + total;
                    }
                    String text = clip(scrub(snapshot.optString("summary", "")), 600);
                    if (text.isEmpty()) text = interruptRequested ? summary
                            : "queued".equals(status) ? "Waiting for remote T3…" : "Remote T3 is working. You can continue using your phone.";
                    progress(text, statusChanged || phaseChanged);
                    return false;
                }
            }
        }

        /** Throttled progress.json (the island's source) plus a running state message, with a heartbeat. */
        void progress(String text, boolean force) throws Exception {
            long now = clock.millis();
            boolean changed = !text.equals(summary);
            boolean heartbeat = now - lastProgressWrite >= HEARTBEAT_MILLIS;
            if (!force && !heartbeat && (!changed || now - lastProgressWrite < options.progressIntervalMillis)) return;
            summary = text;
            lastProgressWrite = now;
            TaskRecords.write(new File(directory, "progress.json"), new JSONObject()
                    .put("taskId", taskId)
                    .put("runtime", RUNTIME)
                    .put("phase", phase)
                    .put("summary", text)
                    .put("tool", tool)
                    .put("toolCalls", 0)
                    .put("toolCallsCompleted", 0)
                    .put("plan", plan)
                    .put("remoteStatus", remoteStatus)
                    .put("updatedAt", now).toString());
            TaskRecords.state(directory, request, "running", text);
            notifier.taskUpdated(taskId);
        }

        void authFailed(RemoteT3Transport.Failure failure) throws Exception {
            diagnostic("auth-failed", "http " + failure.httpStatus);
            finish("needs_user", "Remote T3 rejected this phone's credentials. Update the remote token in Taku settings, then resubmit.", null);
        }

        void finish(String status, String message, String reply) throws Exception {
            if (reply != null || !"cancelled".equals(status))
                TaskRecords.write(new File(directory, "result.txt"), new JSONObject()
                        .put("status", "cancelled".equals(status) ? "failed" : status)
                        .put("message", reply == null || reply.isEmpty() ? message : reply)
                        .put("evidence", new JSONArray()).toString());
            TaskRecords.state(directory, request, status, message);
            TaskRecords.write(new File(directory, "done"), status);
            notifier.taskUpdated(taskId);
        }

        void diagnostic(String type, String detail) throws Exception {
            TaskRecords.write(new File(directory, "diagnostic.json"), new JSONObject()
                    .put("runtime", RUNTIME)
                    .put("type", type)
                    .put("runId", clip(scrub(runId), 256))
                    .put("detail", scrub(detail == null ? "" : detail)).toString());
        }

        /** Removes this task's token verbatim, then applies the shared credential patterns. */
        String scrub(String text) {
            return AcpRuntime.redact(scrubToken(text == null ? "" : text));
        }

        String scrubToken(String text) {
            return token.length() >= 4 ? text.replace(token, "[hidden]") : text;
        }
    }

    static String headline(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return clip(trimmed, 160);
        }
        return clip(text.trim(), 160);
    }

    /** At most {@code max} chars, never splitting a surrogate pair; control characters removed. */
    static String clip(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim();
        if (text.length() <= max) return text;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
