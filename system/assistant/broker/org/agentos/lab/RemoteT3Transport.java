package org.agentos.lab;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The network seam of {@link RemoteT3Runtime}: submit one run to a remote T3 Code host, read its
 * status, and ask it to stop. Tests inject an in-memory fake; the device uses {@link #http()}.
 *
 * <p>Wire contract (version 1, JSON over HTTPS, {@code Authorization: Bearer <token>}):
 * <ul>
 *   <li>{@code POST {endpoint}/api/taku/v1/runs} with {@code Idempotency-Key} → {@code {runId, threadId?, status}}</li>
 *   <li>{@code GET {endpoint}/api/taku/v1/runs/{runId}} → {@code {runId, status, phase?, summary?, tool?,
 *       progress?{done,total}, result?{message}, error?{code,message}}}</li>
 *   <li>{@code POST {endpoint}/api/taku/v1/runs/{runId}/interrupt} → {@code {status}}</li>
 * </ul>
 * {@code status} is one of {@code queued|running|completed|failed|cancelled}. The idempotency key
 * makes a retried submit (request accepted, response lost) return the same run instead of a second one.
 */
interface RemoteT3Transport {
    String API = "/api/taku/v1/runs";

    /** One authenticated exchange target. The token is held in memory only for the call. */
    final class Call {
        final String endpoint, token;
        final int timeoutMillis;

        Call(String endpoint, String token, int timeoutMillis) {
            this.endpoint = endpoint;
            this.token = token;
            this.timeoutMillis = timeoutMillis;
        }
    }

    /** Classified failure; the message never contains the token or response headers. */
    final class Failure extends IOException {
        enum Kind { TRANSIENT, AUTH, REJECTED, NOT_FOUND }

        final Kind kind;
        final int httpStatus;

        Failure(Kind kind, int httpStatus, String detail) {
            super(detail);
            this.kind = kind;
            this.httpStatus = httpStatus;
        }

        static Failure fromStatus(int status, String body) {
            Kind kind;
            if (status == 401 || status == 403) kind = Kind.AUTH;
            else if (status == 404) kind = Kind.NOT_FOUND;
            else if (status == 408 || status == 425 || status == 429 || status >= 500) kind = Kind.TRANSIENT;
            else kind = Kind.REJECTED;
            return new Failure(kind, status, "http " + status + (body == null || body.isEmpty() ? "" : ": " + body));
        }
    }

    JSONObject submit(Call call, String idempotencyKey, JSONObject run) throws IOException;

    JSONObject status(Call call, String runId) throws IOException;

    JSONObject interrupt(Call call, String runId) throws IOException;

    /** {@link HttpURLConnection} binding (available on Android and the host JVM). */
    static RemoteT3Transport http() {
        return new Http();
    }

    final class Http implements RemoteT3Transport {
        static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

        @Override
        public JSONObject submit(Call call, String idempotencyKey, JSONObject run) throws IOException {
            return exchange(call, "POST", API, idempotencyKey, run.toString());
        }

        @Override
        public JSONObject status(Call call, String runId) throws IOException {
            return exchange(call, "GET", API + "/" + segment(runId), null, null);
        }

        @Override
        public JSONObject interrupt(Call call, String runId) throws IOException {
            return exchange(call, "POST", API + "/" + segment(runId) + "/interrupt", null, "{}");
        }

        private static String segment(String runId) throws IOException {
            return URLEncoder.encode(runId, "UTF-8").replace("+", "%20");
        }

        private static JSONObject exchange(Call call, String method, String path, String idempotencyKey, String body) throws IOException {
            HttpURLConnection connection;
            try {
                connection = (HttpURLConnection) new URL(call.endpoint + path).openConnection();
            } catch (IOException | RuntimeException malformed) {
                throw new Failure(Failure.Kind.REJECTED, 0, "invalid endpoint");
            }
            try {
                // A redirect could carry the bearer token to a host nobody configured.
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(call.timeoutMillis);
                connection.setReadTimeout(call.timeoutMillis);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + call.token);
                connection.setRequestProperty("User-Agent", "taku-task-broker/1");
                if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", idempotencyKey);
                if (body != null) {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(bytes);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = read(stream);
                if (status < 200 || status >= 300) throw Failure.fromStatus(status, text.length() > 512 ? text.substring(0, 512) : text);
                try {
                    return new JSONObject(text);
                } catch (Exception notJson) {
                    throw new Failure(Failure.Kind.TRANSIENT, status, "response is not JSON");
                }
            } catch (Failure classified) {
                throw classified;
            } catch (IOException network) {
                // Connect/read timeouts, resets, DNS and TLS hiccups: worth retrying.
                throw new Failure(Failure.Kind.TRANSIENT, 0, network.getClass().getSimpleName());
            } finally {
                connection.disconnect();
            }
        }

        private static String read(InputStream in) throws IOException {
            if (in == null) return "";
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_RESPONSE_BYTES) throw new Failure(Failure.Kind.REJECTED, 0, "response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
