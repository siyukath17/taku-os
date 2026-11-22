package org.agentos.assistant;

import android.content.ContentProvider;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Ordinary device UIDs use the same native commands as the GUI. */
public final class AgentOpenAccessProvider extends ContentProvider {
    private static final Set<String> RPC = new HashSet<>(Arrays.asList(
        "plugin/list", "plugin/installed", "plugin/read", "plugin/install", "plugin/uninstall",
        "config/value/write", "config/mcpServer/reload", "mcpServerStatus/list", "account/usage/read", "account/rateLimits/read"));
    private static final Set<String> SYSTEM_AI = Set.of("readSystemAi", "manageSystemAi", "setSystemAi",
            "bindSystemAi", "bindSystemAiModel", "reorderSystemAi", "restoreSystemAi", "deleteSystemAiGroup");
    private static final Set<String> TURN_CONTROL = Set.of("interrupt", "cancelQueued", "continueTask");
    private static final int INLINE_BYTES = 256 * 1024, MAX_RESULT_BYTES = 8 * 1024 * 1024, TOTAL_BYTES = 16 * 1024 * 1024;
    private final LinkedHashMap<String, Payload> payloads = new LinkedHashMap<>();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "open-access-result-expiry"); thread.setDaemon(true); return thread;
    });
    private static final class Payload {
        final int uid;
        final byte[] bytes;
        final long expires = SystemClock.elapsedRealtime() + 30_000;
        boolean opened;
        Payload(int uid, byte[] bytes) { this.uid = uid; this.bytes = bytes; }
    }
    /** Methods that hand out or replace a credential; trusted callers only. */
    private static final Set<String> CREDENTIAL_METHODS = new HashSet<>(Arrays.asList("account/login/start", "jev/connect"));
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        int caller = Binder.getCallingUid();
        if (method == null || method.isEmpty()) {
            out.putString("result", error("INVALID_ARGUMENT").toString());
            return out;
        }
        android.content.Context ctx = getContext();
        if (ctx == null) {
            out.putString("result", error("SOURCE_UNAVAILABLE").toString());
            return out;
        }
        if (method.equals("workspaces/list") || method.equals("workspaces/configure") || method.equals("workspaces/release")) {
            if (caller != 0 && caller != android.os.Process.SYSTEM_UID && caller != android.os.Process.SHELL_UID) {
                out.putString("result", error("WORKSPACE_PERMISSION_DENIED").toString());
                return out;
            }
        } else {
            String denied = AgentOpenAccessPolicy.denial(method,
                    scope -> AppPermissionProvider.isAllowedLocal(ctx, caller, scope));
            if (denied != null) {
                out.putString("result", error(denied).toString());
                return out;
            }
        }
        int callerPid = Binder.getCallingPid();
        // The on-device Agent, as opposed to a third-party app holding a granted scope.
        boolean agent = caller == 0 || caller == android.os.Process.SYSTEM_UID || caller == android.os.Process.SHELL_UID;
        boolean composer = method.startsWith("composer/") && !AgentGatewayProtocol.COMPOSER_IMPORT.equals(method);
        Context context = getContext();
        if (composer || method.startsWith("capture/") || method.startsWith("accounts/") || method.startsWith("requests/") ||
                method.equals("readTaskDetail") || SYSTEM_AI.contains(method) || TURN_CONTROL.contains(method) ||
                method.equals("manageAssistant") || method.equals("sessions/removals")) {
            String code = ComposerState.userError(context, caller);
            if (code != null) {
                out.putString("result", error(code).toString());
                return out;
            }
        }
        // Credentials stay with trusted callers (root, system, shell, platform-signed), whatever
        // the Permission Center grants: a ChatGPT sign-in hands out its device code, and a Jev
        // key replaces the user's own, as Taku Audio refuses Fal key changes.
        if (CREDENTIAL_METHODS.contains(method)
                && !AppPermissionCatalog.isTrustedSubject(ctx, caller, AppPermissionCatalog.SCOPE_TAKU_CONTROL)) {
            out.putString("result", error("PERMISSION_DENIED").toString());
            return out;
        }
        long identity = Binder.clearCallingIdentity();
        JSONObject envelope;
        try {
            if (composer) {
                JSONObject input = AgentGatewayProtocol.request(method, arg);
                JSONObject result;
                SharedPreferences prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE);
                String thread = input.optString("threadId");
                switch (method) {
                    case AgentGatewayProtocol.COMPOSER_READ: result = ComposerState.read(prefs, thread); break;
                    case AgentGatewayProtocol.COMPOSER_WRITE: result = ComposerState.write(prefs, thread, input.getString("revision"), input.getString("text")); break;
                    case AgentGatewayProtocol.COMPOSER_MOVE: result = ComposerDelivery.move(context, input); break;
                    case AgentGatewayProtocol.COMPOSER_CREATE_THREAD: result = (JSONObject) AgentGatewayProtocol.clean(ComposerDelivery.createThread(), 0); break;
                    case AgentGatewayProtocol.COMPOSER_PRESERVE_DRAFT: result = ComposerState.preserveDraft(prefs, thread, input.getString("recoveryId"), input.getString("text")); break;
                    case AgentGatewayProtocol.COMPOSER_RECEIPT:
                    case AgentGatewayProtocol.COMPOSER_SUBMIT:
                    case AgentGatewayProtocol.COMPOSER_SETTLE:
                    case AgentGatewayProtocol.COMPOSER_RELEASE: result = ComposerDelivery.call(context, method, input); break;
                    case AgentGatewayProtocol.COMPOSER_ATTACH: result = ComposerAttachments.attach(context, input, extras, caller, callerPid); break;
                    case AgentGatewayProtocol.COMPOSER_REMOVE: result = ComposerState.removeAttachment(prefs, thread, input.getString("expectedRevision"), input.getString("attachmentId")); break;
                    case AgentGatewayProtocol.COMPOSER_HANDOFF: result = ComposerState.handoff(prefs, thread, input.getString("handoffId"), input.getString("text")); break;
                    case AgentGatewayProtocol.COMPOSER_RESTORE: result = ComposerState.restore(prefs, thread, input.getString("expectedRevision"), input.getString("recoveryId")); break;
                    case AgentGatewayProtocol.COMPOSER_RESTORE_TASK: result = ComposerDelivery.restoreTask(context, input); break;
                    case AgentGatewayProtocol.COMPOSER_OPTIONS_READ:
                    case AgentGatewayProtocol.COMPOSER_OPTIONS_WRITE: result = ComposerOptions.call(context, method, input); break;
                    default: throw new IOException("UNKNOWN_METHOD");
                }
                return transport(out, new JSONObject().put("ok", true).put("result", result), caller);
            }
            if (arg != null && arg.length() > AgentGatewayProtocol.MAX_REQUEST) throw new IOException("INVALID_ARGUMENT");
            JSONObject input = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
            JSONObject result;
            if (method.startsWith("panels/")) result = PanelRegistry.get(getContext()).call(method.substring(7), input);
            else if (method.startsWith("card-view/")) result = PanelRegistry.get(getContext()).cardView(method.substring(10), input);
            else if (method.equals("matter/read")) {
                PanelProtocol.parse(method, input);
                result = MatterRuntime.get(getContext()).read(input.getString("matterId"));
            } else if (method.equals("matter/dispatch")) {
                PanelProtocol.parse(method, input);
                result = MatterRuntime.get(getContext()).dispatch(input);
            } else if (method.equals("reply/read")) {
                envelope = AgentGatewayProtocol.replyRead(input, NativeClient::exchange);
                if (!envelope.optBoolean("ok")) {
                    String code = envelope.getJSONObject("error").optString("code", "SOURCE_UNAVAILABLE");
                    envelope = error(code);
                }
                return transport(out, envelope, caller);
            } else if (method.startsWith("skills/")) result = TaskRequests.control(method, input);
            else if (RPC.contains(method)) result = NativeClient.rpcSync(method, input);
            else if (method.equals("reactions/options")) {
                only(input);
                result = MessageReactions.INSTANCE.options(getContext());
            } else if (method.equals("reactions/list")) {
                only(input, "threadId");
                AgentGatewayProtocol.requireId(input, "threadId");
                String thread = input.getString("threadId");
                result = MessageReactions.INSTANCE.list(getContext(), thread)
                        .put("messages", MessageReactions.INSTANCE.messages(getContext(), thread, 20));
            } else if (method.equals("reactions/set")) {
                only(input, "threadId", "turnId", "itemId", "reaction");
                if (!input.has("reaction") || !input.isNull("reaction") && !(input.get("reaction") instanceof String)) throw new IOException("INVALID_ARGUMENT");
                for (String key : new String[]{"turnId", "itemId"})
                    if (input.has(key) && !(input.get(key) instanceof String)) throw new IOException("INVALID_ARGUMENT");
                AgentGatewayProtocol.requireId(input, "threadId");
                String thread = input.getString("threadId");
                JSONObject target = MessageReactions.INSTANCE.set(getContext(), thread,
                        input.has("turnId") ? input.getString("turnId") : null, input.has("itemId") ? input.getString("itemId") : null,
                        input.isNull("reaction") ? null : input.getString("reaction"));
                result = MessageReactions.INSTANCE.list(getContext(), thread).put("target", target);
            }
            else if (method.equals("thread/usage")) {
                AgentGatewayProtocol.requireId(input, "threadId");
                JSONObject usage = NativeClient.state(input.getString("threadId")).optJSONObject("usage");
                result = new JSONObject().put("threadId", input.getString("threadId")).put("usage", usage == null ? JSONObject.NULL : usage);
            }
            else if (method.equals("account/status")) result = AccountStatus.read();
            else if (method.equals("account/login/start")) {
                only(input, "type", "open");
                result = AiConnections.startChatGpt(getContext(), input.optString("type", "chatgpt"), input.optBoolean("open", false), true, false);
            } else if (method.equals("account/login/cancel")) { only(input); result = AiConnections.cancelChatGpt(); }
            else if (method.equals("jev/status")) { only(input); result = AiConnections.jevStatus(getContext()); }
            else if (method.equals("jev/enable")) { only(input); result = AiConnections.enableJev(getContext()); }
            else if (method.equals("jev/connect")) { only(input, "key"); result = AiConnections.connectJev(getContext(), input.getString("key")); }
            else if (method.equals("sessions/removals")) result = NativeClient.exchange(input.put("op", "sessionRemovals"));
            else if (TakuConnectGateway.ACTIONS.contains(method)) result = TakuConnectGateway.execute(method, input, extras);
            else if (method.startsWith("workspaces/")) result = WorkspaceCommands.call(context, method, input, caller);
            else if (method.startsWith("capture/")) result = AssistantCaptureBackend.execute(context, method, input);
            else if (method.startsWith("accounts/")) {
                // A granted third-party app sees sign-in progress, not the one-time code or its page.
                result = AssistantAccounts.command(context, method, input, extras, agent);
            }
            else if (method.startsWith("requests/")) result = AssistantRequests.execute(method, input, extras, agent);
            else if (method.equals("manageAssistant")) result = AssistantManagement.execute(context, AssistantManagement.parse(arg), extras);
            else if (method.equals("readTaskDetail")) {
                result = TaskDetails.read(context.getFilesDir(), AgentGatewayProtocol.request(method, arg).getString("taskId"));
            } else if (TURN_CONTROL.contains(method)) {
                JSONObject validated = AgentGatewayProtocol.request(method, arg);
                String thread = validated.getString("threadId");
                result = method.equals("interrupt")
                        ? TaskRequests.interrupt(thread, validated.getString("turnId"))
                        : method.equals("continueTask")
                        ? TaskRequests.continueTask(thread, validated.getString("taskId"), task -> {
                            // Same receipt as the gateway: readSession reports the continuation, not the old failure.
                            if (!context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE).edit().putString("task-" + thread, task).commit())
                                throw new IllegalStateException("RECEIPT_SAVE_FAILED");
                            return kotlin.Unit.INSTANCE;
                        })
                        : TaskRequests.cancelQueued(thread, validated.getString("taskId"));
            } else if (SYSTEM_AI.contains(method)) {
                result = SystemAiUiBridge.execute(context, method, AgentGatewayProtocol.request(method, arg));
            } else throw new IOException("UNKNOWN_METHOD");
            envelope = new JSONObject().put("ok", true).put("result", result);
            return transport(out, envelope, caller);
        } catch (ComposerState.RevisionConflict conflict) {
            out.putString("result", error("DRAFT_REVISION_CONFLICT", conflict.getRevision()).toString());
            return out;
        } catch (Exception failure) {
            String code = failure instanceof SQLiteException ? "STORAGE_ERROR" : failure.getMessage();
            if (failure instanceof org.json.JSONException) code = "INVALID_ARGUMENT";
            if (code == null || !code.matches("[A-Z][A-Z_]{2,80}")) code = "SOURCE_UNAVAILABLE";
            out.putString("result", error(code).toString());
            return out;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    private static JSONObject error(String code) { return error(code, null); }
    private static JSONObject error(String code, String revision) {
        try {
            JSONObject envelope = new JSONObject().put("ok", false).put("code", code).put("message", code);
            if (revision != null) envelope.put("revision", revision);
            return envelope;
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private Bundle transport(Bundle out, JSONObject envelope, int uid) throws Exception {
        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESULT_BYTES) throw new IOException("RESULT_TOO_LARGE");
        if (bytes.length <= INLINE_BYTES) { out.putString("result", envelope.toString()); return out; }
        String token = UUID.randomUUID().toString();
        synchronized (payloads) {
            expire();
            int total = bytes.length;
            for (Payload payload : payloads.values()) total += payload.bytes.length;
            if (payloads.size() >= 8 || total > TOTAL_BYTES) throw new IOException("RESULT_BUFFER_FULL");
            payloads.put(token, new Payload(uid, bytes));
        }
        expiry.schedule(() -> { synchronized (payloads) { expire(); } }, 30, TimeUnit.SECONDS);
        out.putString("result", new JSONObject().put("ok", true).put("resultUri", "content://org.agentos.assistant.open-access/result/" + token).toString());
        return out;
    }
    private void expire() {
        long now = SystemClock.elapsedRealtime();
        payloads.values().removeIf(payload -> now >= payload.expires);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 || !"result".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("RESULT_EXPIRED");
        Payload payload;
        synchronized (payloads) {
            expire();
            payload = payloads.get(uri.getLastPathSegment());
            if (payload == null || payload.opened || payload.uid != Binder.getCallingUid()) throw new FileNotFoundException("RESULT_EXPIRED");
            payload.opened = true;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            var deadline = expiry.schedule(() -> { try { pipe[1].closeWithError("RESULT_EXPIRED"); } catch (IOException ignored) { } },
                Math.max(1, payload.expires - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            Thread writer = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { output.write(payload.bytes); }
                catch (IOException ignored) { }
                finally { deadline.cancel(false); synchronized (payloads) { payloads.remove(uri.getLastPathSegment()); } }
            }, "open-access-result");
            writer.setDaemon(true); writer.start();
            return pipe[0];
        } catch (IOException failure) { throw new FileNotFoundException("RESULT_EXPIRED"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException("Use content call"); }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }

    /** Rejects any parameter outside [allowed] (`UNSUPPORTED_PARAMETER`), like the gateway's protocol check. */
    private static void only(JSONObject input, String... allowed) throws IOException {
        java.util.List<String> fields = java.util.Arrays.asList(allowed);
        java.util.Iterator<String> keys = input.keys();
        while (keys.hasNext()) if (!fields.contains(keys.next())) throw new IOException("UNSUPPORTED_PARAMETER");
    }
}
