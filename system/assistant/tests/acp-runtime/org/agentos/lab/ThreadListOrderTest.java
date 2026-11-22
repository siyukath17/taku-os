package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The conversation list is newest-first by updatedAt, whatever order rows arrive in: a conversation
 * whose UUID sorts late but was updated last comes first, ACP millisecond and native second
 * timestamps compare on one scale, and ties fall back to createdAt, then id.
 */
public final class ThreadListOrderTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject row(String id, Object created, Object updated) {
        JSONObject row = new JSONObject().put("id", id).put("preview", id.substring(0, 8));
        if (created != null) row.put("createdAt", created);
        if (updated != null) row.put("updatedAt", updated);
        return row;
    }

    private static String ids(JSONArray rows) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) out.append(i == 0 ? "" : ",").append(rows.getJSONObject(i).getString("id").substring(0, 8));
        return out.toString();
    }

    private static void newestUpdatedFirstDespiteUuidOrder() {
        // Field case: 0c9870b9 updated last but sat behind 01a1... rows in insertion/UUID order.
        JSONArray rows = new JSONArray()
                .put(row("01a1fe6b-0000-4000-8000-000000000000", 1795300000L, 1795320000L))
                .put(row("01a1ff12-0000-4000-8000-000000000000", 1795300100L, 1795321000L))
                .put(row("01a1ff1d-0000-4000-8000-000000000000", 1795300200L, 1795322000L))
                .put(row("0b479dfc-0000-4000-8000-000000000000", 1795300300L, 1795323000L))
                .put(row("0c9870b9-fc22-402c-a39c-ef1670c7078e", 1795330000L, 1795330690L))
                .put(row("ffffffff-0000-4000-8000-000000000000", 1795000000L, 1795000001L));
        JSONArray sorted = ThreadListOrder.sorted(rows);
        check(ids(sorted).equals("0c9870b9,0b479dfc,01a1ff1d,01a1ff12,01a1fe6b,ffffffff"),
                "rows are newest updatedAt first: " + ids(sorted));
        check(rows.getJSONObject(0).getString("id").startsWith("01a1fe6b"), "input array is not reordered in place");
    }

    private static void millisAndSecondsShareOneScale() {
        // ACP store writes ms; native/projection writes s. 1795330690000 ms == 1795330690 s.
        JSONArray rows = new JSONArray()
                .put(row("aaaaaaaa-0000-4000-8000-000000000000", 1795300000L, 1795330000L))          // s, older
                .put(row("bbbbbbbb-0000-4000-8000-000000000000", 1795300000000L, 1795330690000L))    // ms, newest
                .put(row("cccccccc-0000-4000-8000-000000000000", 1795300000L, "1795330500"));       // string s
        JSONArray sorted = ThreadListOrder.sorted(rows);
        check(ids(sorted).equals("bbbbbbbb,cccccccc,aaaaaaaa"), "ms and s timestamps interleave correctly: " + ids(sorted));
        check(ThreadListOrder.seconds(rows.getJSONObject(1), "updatedAt") == 1795330690L, "ms normalize to s");
    }

    private static void tiesFallBackToCreatedThenId() {
        JSONArray rows = new JSONArray()
                .put(row("dddddddd-0000-4000-8000-000000000000", 100L, 500L))
                .put(row("cccccccc-0000-4000-8000-000000000000", 200L, 500L))
                .put(row("bbbbbbbb-0000-4000-8000-000000000000", 200L, 500L))
                .put(row("eeeeeeee-0000-4000-8000-000000000000", null, null));
        JSONArray sorted = ThreadListOrder.sorted(rows);
        check(ids(sorted).equals("bbbbbbbb,cccccccc,dddddddd,eeeeeeee"),
                "ties: createdAt desc, then id; missing timestamps last: " + ids(sorted));
    }

    public static void main(String[] args) {
        newestUpdatedFirstDespiteUuidOrder();
        millisAndSecondsShareOneScale();
        tiesFallBackToCreatedThenId();
        System.out.println("PASS ThreadListOrderTest: " + checks + " checks, 3 cases");
    }
}
