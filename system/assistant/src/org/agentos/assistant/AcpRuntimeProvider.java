package org.agentos.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.Set;

/**
 * Standard Agent/ADB interface for the task runtime settings (runtime.json) and the on-device
 * agent's sign-in trigger:
 * {@code content call --uri content://org.agentos.assistant.acp-runtime --method acp --arg '<json>'}.
 * Validation and the atomic write live in the broker ({@code AcpRuntimeSettings}), the same path the
 * GUI uses. System, shell and root callers only: these settings choose which agent runs phone tasks.
 */
public final class AcpRuntimeProvider extends ContentProvider {
    static final Set<String> ACTIONS = Set.of("read", "update", "signIn", "signInStatus", "signInCancel");

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (!AppPermissionCatalog.isSystemOrShellUid(uid)) throw new SecurityException("PERMISSION_DENIED");
        Bundle out = new Bundle();
        try {
            if (!"acp".equals(method)) throw new IllegalArgumentException("UNKNOWN_METHOD");
            if (arg != null && arg.length() > 65536) throw new IllegalArgumentException("REQUEST_TOO_LARGE");
            JSONObject source = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
            String action = source.optString("action", "read");
            if (!ACTIONS.contains(action)) throw new IllegalArgumentException("UNKNOWN_ACTION");
            JSONObject request = new JSONObject().put("op", "acpRuntime").put("action", action);
            // The broker runs as root: shell may not inject agent flags (see AcpRuntimeSettings).
            if (uid == android.os.Process.SHELL_UID) request.put("caller", "shell");
            if (source.has("patch")) request.put("patch", source.get("patch"));
            if (source.has("expectedRevision")) request.put("expectedRevision", source.getString("expectedRevision"));
            long token = Binder.clearCallingIdentity();
            try {
                JSONObject result = NativeClient.exchange(request);
                out.putString("result", new JSONObject().put("ok", true).put("result", result).toString());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (Exception failure) {
            try {
                out.putString("result", new JSONObject().put("ok", false)
                        .put("message", failure.getMessage() == null ? "ACP_RUNTIME_UNAVAILABLE" : failure.getMessage()).toString());
            } catch (Exception ignored) {}
        }
        return out;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return "application/vnd.taku.acp-runtime+json"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
