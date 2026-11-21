package org.agentos.lab;

import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The broker's dedicated screens, built from the device's parts: phone-display, am, a monotonic
 * clock, Codex through NativeBridge, and the system language from getprop. Only the broker uses
 * this; host tests build DedicatedScreens from fakes, so it never names NativeBridge.
 */
final class DedicatedScreensOnDevice {
    private static final String LOG_TAG = "TakuDedicatedScreens";

    private static DedicatedScreens shared;

    static synchronized DedicatedScreens shared() {
        if (shared == null)
            shared =
                    new DedicatedScreens(
                            PhoneExecutionScreens.DEVICE_HOST,
                            PhoneTasks.DEVICE,
                            new DeviceClock(),
                            DedicatedScreensOnDevice::askCodex,
                            DedicatedScreensOnDevice::systemLanguage,
                            message -> Log.w(LOG_TAG, message));
        return shared;
    }

    /**
     * Codex as the screen registry sees it. ACP phone sessions (an ACP conversation, or an ACP
     * background task's thread) are not app-server threads: a conversation created by thread/start
     * that never ran a Codex turn is not even persisted, so thread/read answers "thread not loaded"
     * and request_apps failed on the device. They never spawn sub-agents, so they answer as root
     * threads here; every other call goes to Codex unchanged.
     */
    static JSONObject askCodex(String method, JSONObject params) throws Exception {
        if ("thread/read".equals(method) && params != null && AcpConversations.phoneSession(params.optString("threadId")))
            return new JSONObject().put("thread", new JSONObject().put("id", params.getString("threadId")).put("source", "acp"));
        return NativeBridge.askCodex(method, params);
    }

    /** Monotonic time; callbacks run one at a time on a daemon thread. */
    private static final class DeviceClock implements DedicatedScreens.Clock {
        private final ScheduledExecutorService timer =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dedicated-screens-timer");
                    t.setDaemon(true);
                    return t;
                });

        public long now() {
            return System.nanoTime() / 1_000_000;
        }

        public void after(long ms, Runnable run) {
            timer.schedule(run, ms, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The system language ("zh", "en", ...): persist.sys.locale (written when the user picks a
     * language in Settings), else ro.product.locale, else the process default.
     */
    private static String systemLanguage() {
        for (String key : new String[] {"persist.sys.locale", "ro.product.locale"})
            try {
                String tag = TaskBroker.command("/system/bin/getprop", key).trim();
                if (!tag.isEmpty()) return Locale.forLanguageTag(tag).getLanguage();
            } catch (Exception unavailable) {
                break;
            }
        return Locale.getDefault().getLanguage();
    }
}
