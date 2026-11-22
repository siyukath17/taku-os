package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Order of the conversation list ({@code agent-view/threads.json}, {@code agent_control thread/list}):
 * newest {@code updatedAt} first, then newest {@code createdAt}, then {@code id} as a stable tiebreak.
 *
 * <p>Rows come from several sources (the Codex app server's thread/list, ACP store projections,
 * saved views); the projection map keeps first-insertion order, so the list must be sorted here
 * rather than trusting how rows arrived. Native timestamps are Unix seconds while the ACP store
 * records milliseconds; {@link #seconds} reads either, so mixed sources never interleave wrongly.
 */
final class ThreadListOrder {
    private ThreadListOrder() {}

    /** Any value at or above this is milliseconds (1e11 s is past the year 5000). */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    /** A timestamp field as Unix seconds; missing or unparsable is 0 (sorted last). */
    static long seconds(JSONObject row, String key) {
        Object value = row.opt(key);
        long raw;
        if (value instanceof Number) raw = ((Number) value).longValue();
        else if (value instanceof String) {
            try { raw = Long.parseLong(((String) value).trim()); } catch (NumberFormatException ignored) { return 0L; }
        } else return 0L;
        if (raw <= 0) return 0L;
        return raw >= MILLIS_THRESHOLD ? raw / 1000L : raw;
    }

    static int compare(JSONObject a, JSONObject b) {
        int byUpdated = Long.compare(seconds(b, "updatedAt"), seconds(a, "updatedAt"));
        if (byUpdated != 0) return byUpdated;
        int byCreated = Long.compare(seconds(b, "createdAt"), seconds(a, "createdAt"));
        if (byCreated != 0) return byCreated;
        return a.optString("id").compareTo(b.optString("id"));
    }

    /** A new array of the same rows, newest first. */
    static JSONArray sorted(JSONArray rows) {
        List<JSONObject> list = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) list.add(row);
        }
        list.sort(ThreadListOrder::compare);
        JSONArray out = new JSONArray();
        for (JSONObject row : list) out.put(row);
        return out;
    }
}
