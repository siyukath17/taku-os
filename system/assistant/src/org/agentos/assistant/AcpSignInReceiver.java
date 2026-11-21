package org.agentos.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.json.JSONObject;

/**
 * Not exported: reached only through this app's own island/notification PendingIntent. Asks the
 * broker to start the agent's oauth-personal sign-in, which opens the phone's browser.
 */
public final class AcpSignInReceiver extends BroadcastReceiver {
    static final String ACTION = "org.agentos.assistant.ACP_SIGN_IN";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        NativeClient.io.execute(() -> {
            try {
                JSONObject status = NativeClient.exchange(new JSONObject().put("op", "acpRuntime").put("action", "signIn"));
                Log.i("AcpSignIn", "sign-in " + status.optString("state")); // State only; never the URL.
            } catch (Exception failure) {
                Log.w("AcpSignIn", "sign-in unavailable: " + failure.getMessage());
            } finally {
                pending.finish();
            }
        });
    }
}
