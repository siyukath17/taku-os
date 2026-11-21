package org.agentos.lab;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * The {@code taku_phone.device_info} result. Device facts are not a property of the execution screen:
 * a turn that has not called {@code request_apps} yet (typically a chat turn asking "which phone is
 * this?") must still read them. It used to fail with {@code APPS_NOT_REQUESTED} and the agent fell
 * back to shell. The held execution screen is merged in when there is one; otherwise
 * {@code screen:false} plus a short {@code screenNote}.
 */
final class PhoneDeviceInfo {
    interface Props { String get(String key) throws Exception; }

    interface HeldScreen { JSONObject json() throws Exception; }

    private PhoneDeviceInfo() {}

    static JSONObject read(Props props, HeldScreen held) throws Exception {
        JSONObject info = new JSONObject()
                .put("android", props.get("ro.build.version.release").trim())
                .put("model", props.get("ro.product.model").trim());
        try {
            JSONObject screen = held.json();
            for (Iterator<String> it = screen.keys(); it.hasNext(); ) {
                String key = it.next();
                info.put(key, screen.get(key));
            }
            info.put("screen", true);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception noScreen) {
            String message = String.valueOf(noScreen.getMessage());
            info.put("screen", false).put("screenNote", message.length() > 160 ? message.substring(0, 160) : message);
        }
        return info;
    }
}
