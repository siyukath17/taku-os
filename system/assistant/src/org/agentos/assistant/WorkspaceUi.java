package org.agentos.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.util.function.IntFunction;
import org.json.*;

/** Presentation helpers; task ownership and input authorization remain in the broker. */
final class WorkspaceUi {
    final Activity activity;
    final WorkspaceStyle theme;
    final WorkspaceWidgets ui;
    static void applySystemTheme(Activity activity) {
        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        activity.setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
    }
    WorkspaceUi(Activity activity) {
        this.activity = activity;
        theme = new WorkspaceStyle((activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        ui = new WorkspaceWidgets(activity, theme);
    }
    String s(int id, Object... values) { return activity.getString(id, values); }
    LinearLayout root() {
        LinearLayout root = ui.column(); root.setBackgroundColor(theme.surface);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        activity.getWindow().getDecorView().setSystemUiVisibility(theme.dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.setContentView(root); root.requestApplyInsets(); return root;
    }
    void navigation(LinearLayout parent, String breadcrumb, Runnable back) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(48)); row.setContentDescription(s(R.string.workspace_back) + " · " + breadcrumb);
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(theme.border), null, null));
        ImageView arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_drawer_chevron_down);
        arrow.setRotation(90); arrow.setColorFilter(theme.muted); arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)); icon.rightMargin = ui.dp(8); row.addView(arrow, icon);
        TextView copy = ui.text(breadcrumb, 13, false); copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); row.setOnClickListener(v -> back.run());
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    void heading(LinearLayout root, String title, Runnable back) {
        LinearLayout box = ui.column(); box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(14));
        navigation(box, s(R.string.workspace_breadcrumb), back);
        ui.add(box, ui.text(title, 27, true), 8); root.addView(box);
    }
    static NativeToolPresentation.Labels toolLabels(Context context) {
        return (key, arguments) -> {
            int resource;
            switch (key) {
                case "device_info": resource = R.string.chat_tool_device_info; break;
                case "observe_background": resource = R.string.chat_tool_observe_background; break;
                case "open_app": resource = R.string.chat_tool_open_app; break;
                case "tap": resource = R.string.chat_tool_tap; break;
                case "type_text": resource = R.string.chat_tool_type_text; break;
                case "screenshot": resource = R.string.chat_tool_screenshot; break;
                case "swipe": resource = R.string.chat_tool_swipe; break;
                case "touch": resource = R.string.chat_tool_touch; break;
                case "apps": resource = R.string.chat_tool_apps; break;
                case "request_apps": resource = R.string.chat_tool_request_apps; break;
                case "release_apps": resource = R.string.chat_tool_release_apps; break;
                case "screen_status": resource = R.string.chat_tool_screen_status; break;
                case "phone_tool": resource = R.string.chat_tool_phone; break;
                case "command": resource = R.string.chat_tool_command; break;
                case "file_change": resource = R.string.chat_tool_file_change; break;
                case "extension": resource = R.string.chat_tool_extension; break;
                case "web_search": resource = R.string.chat_tool_web_search; break;
                case "compaction": resource = R.string.chat_tool_compaction; break;
                case "collaboration": resource = R.string.chat_tool_collaboration; break;
                case "review_enter": resource = R.string.chat_tool_review_enter; break;
                case "review_exit": resource = R.string.chat_tool_review_exit; break;
                case "working": resource = R.string.chat_tool_working; break;
                case "completed": resource = R.string.chat_tool_completed; break;
                case "failed": resource = R.string.chat_tool_failed; break;
                case "declined": resource = R.string.chat_tool_declined; break;
                case "interrupted": resource = R.string.chat_tool_interrupted; break;
                case "cancelled": resource = R.string.chat_tool_cancelled; break;
                case "pending": resource = R.string.chat_tool_pending; break;
                case "image_result": resource = R.string.chat_tool_image_result; break;
                case "exit_code": resource = R.string.chat_tool_exit_code; break;
                case "output_tail": resource = R.string.chat_tool_output_tail; break;
                default: resource = R.string.chat_tool_call;
            }
            return context.getString(resource, arguments);
        };
    }
    String taskLabel(JSONObject screen) {
        String title = screen.optString("taskTitle").trim();
        if (title.isEmpty()) title = screen.optString("label").trim();
        return title.isEmpty() ? s(R.string.workspace_task_fallback) : title;
    }
    static JSONObject request(String action, JSONObject screen) throws Exception {
        JSONObject request = new JSONObject().put("op", "workspaces").put("action", action);
        if (screen != null) for (String key : new String[]{"threadId", "screenKey", "generation"}) request.put(key, screen.getString(key));
        return request;
    }
    static boolean same(JSONObject left, JSONObject right) {
        if (left == null || right == null) return false;
        for (String key : new String[]{"threadId", "screenKey", "generation"})
            if (left.optString(key).isEmpty() || !left.optString(key).equals(right.optString(key))) return false;
        return true;
    }
    String appLabel(JSONObject screen) {
        String pkg = screen.optString("packageName");
        try { return activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception unavailable) { return screen.optString("appLabel", pkg.isEmpty() ? s(R.string.workspace_task_fallback) : pkg); }
    }
    String status(JSONObject screen) {
        return status(screen, id -> s(id));
    }
    static String status(JSONObject screen, IntFunction<String> text) {
        String owner = screen.optString("controlOwner"), status = screen.optString("status");
        if (status.equals("cleanup_failed")) return text.apply(R.string.workspace_cleanup_failed);
        if (status.equals("stale") || status.equals("disconnected") || status.equals("unavailable") || status.equals("rebind_required")) return text.apply(R.string.workspace_status_unavailable);
        if (status.equals("stopping") || owner.equals("stopping")) return text.apply(R.string.workspace_status_stopping);
        if (owner.equals("user")) return text.apply(R.string.workspace_status_user);
        if (owner.equals("paused")) return text.apply(R.string.workspace_status_paused);
        if (screen.optBoolean("resumeRequired") || status.equals("awaiting_resume")) return text.apply(R.string.workspace_status_resume);
        if (status.equals("waiting_for_app")) {
            JSONObject reason = screen.optJSONObject("waitReason");
            String waiting = reason == null ? "" : reason.optString("text").trim();
            return waiting.isEmpty() ? text.apply(R.string.workspace_status_waiting_app) : waiting;
        }
        if (status.equals("waiting") || status.equals("waiting_for_user") || screen.optBoolean("waitingForUser")) return text.apply(R.string.workspace_status_waiting);
        if (!screen.optString("activeTurnId").isEmpty() || status.equals("running") || status.equals("working")) return text.apply(R.string.workspace_status_working);
        if (owner.equals("agent") || status.equals("idle") || status.equals("ready")) return text.apply(R.string.workspace_status_ready);
        return text.apply(R.string.workspace_status_unknown);
    }
    String error(String message) {
        String value = message == null ? "" : message;
        if (value.contains("GENERATION") || value.contains("OWNER_MISMATCH") || value.contains("NOT_BOUND") || value.contains("REBIND") || value.contains("CHANGED")) return s(R.string.workspace_error_changed);
        if (value.contains("WAITING_FOR_USER")) return s(R.string.workspace_error_question);
        if (value.contains("ACTIVE") || value.contains("BUSY") || value.contains("STOPPING")) return s(R.string.workspace_error_busy);
        if (value.contains("DENIED") || value.contains("DISABLED") || value.contains("NOT_ALLOWED")) return s(R.string.workspace_error_policy);
        if (value.contains("LIMIT")) return s(R.string.workspace_error_limit);
        return s(R.string.workspace_action_failed);
    }
}
