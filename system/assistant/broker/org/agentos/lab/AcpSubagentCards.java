package org.agentos.lab;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The structured {@code subagent} object the broker adds to each {@code invoke_subagent} chat item
 * ({@code mcpToolCall}), so the chat page can draw one task card per subagent instead of a generic
 * activity row. Additive: records without it (older turns, Codex turns) keep rendering as before.
 *
 * <pre>
 * subagent: {
 *   id        "subagent-1" (ordinal in the turn; never agy's conversation id)
 *   title     the Role the parent gave it ("Device Status Inspector"), else a generic name
 *   role      same as title when the parent named one, else ""
 *   type      agy's TypeName ("self", "research", …)
 *   source    "local" (an on-device agy child)
 *   status    running | completed | failed | interrupted
 *   startedAt, completedAt (epoch ms; completedAt 0 while running), elapsedMs (0 while running: the
 *             page ticks from startedAt)
 *   model     display name ("Gemini 4 Neon"), "" when unknown
 *   goal      the task it was given (sanitized, clipped)
 *   activity  what it is doing now, localized ("读取设备信息"), "" once it ended
 *   summary   one plain line of its report, "" until it reported
 *   report    its report (Markdown, sanitized, clipped)
 * }
 * </pre>
 *
 * Every text is passed through {@link AcpRuntime#redact} and {@link AcpToolLabels#sanitize}, so no
 * agent-internal path, conversation link or credential reaches the page.
 */
final class AcpSubagentCards {
    private AcpSubagentCards() {}

    static final int MAX_TITLE = 80, MAX_GOAL = 1200, MAX_REPORT = 4000, MAX_SUMMARY = 160;

    /** Creates or refreshes a card from {@code invoke_subagent} arguments ({@code Role}, {@code Prompt}, {@code TypeName}, {@code Model}). */
    static JSONObject fromArguments(JSONObject card, JSONObject args, int ordinal, long now) throws Exception {
        JSONObject out = card == null ? fresh(ordinal, now) : card;
        if (args == null) return out;
        String role = clean(args.optString("Role", args.optString("role", "")), MAX_TITLE).replace('\n', ' ');
        if (!role.isEmpty()) out.put("role", role).put("title", role);
        String type = args.optString("TypeName", args.optString("typeName", "")).trim();
        if (type.matches("[A-Za-z0-9_.-]{1,40}")) out.put("type", type);
        String goal = clean(args.optString("Prompt", args.optString("prompt", "")), MAX_GOAL);
        if (!goal.isEmpty()) out.put("goal", goal);
        String model = args.optString("Model", "").trim();
        if (!model.isEmpty() && !"inherit".equalsIgnoreCase(model)) out.put("model", model(model)).put("modelPinned", true);
        return out;
    }

    private static JSONObject fresh(int ordinal, long now) throws Exception {
        return new JSONObject().put("id", "subagent-" + ordinal).put("title", "").put("role", "").put("type", "")
                .put("source", "local").put("provider", "agy").put("status", "running").put("startedAt", now).put("completedAt", 0)
                .put("elapsedMs", 0).put("model", "").put("goal", "").put("activity", "").put("summary", "").put("report", "");
    }

    /** The {@code invoke_subagent} call's own ACP status: a failed call means the child never ran. */
    static void toolStatus(JSONObject card, String acpStatus, long now) throws Exception {
        if ("failed".equals(acpStatus) && "running".equals(card.optString("status"))) finish(card, "failed", now);
    }

    /**
     * Merges what agy's store says about the child bound to this card. Returns true when anything the
     * page shows changed (so the turn is rewritten only then; the running clock ticks on the page).
     */
    static boolean merge(JSONObject card, AcpSubagents.Child child, String parentModel, Locale locale) throws Exception {
        String before = card.toString();
        if (card.optString("role").isEmpty() && !child.role.isEmpty()) {
            String role = clean(child.role, MAX_TITLE).replace('\n', ' ');
            card.put("role", role).put("title", role);
        }
        if (card.optString("type").isEmpty() && child.typeName.matches("[A-Za-z0-9_.-]{1,40}")) card.put("type", child.typeName);
        if (!card.optBoolean("modelPinned")) {
            String model = model(!child.model.isEmpty() ? child.model : parentModel == null ? "" : parentModel);
            if (!model.isEmpty()) card.put("model", model);
        }
        if (!"running".equals(card.optString("status"))) return !before.equals(card.toString());
        if (child.reported) {
            String report = clean(child.report, MAX_REPORT);
            card.put("report", report).put("summary", summary(report));
            long end = child.reportedMillis > 0 ? child.reportedMillis : System.currentTimeMillis();
            finish(card, "completed", Math.max(end, card.optLong("startedAt")));
        } else {
            card.put("activity", stage(child, locale));
        }
        return !before.equals(card.toString());
    }

    /** At the end of the turn a child that never reported did not finish: it is shown as interrupted. */
    static boolean end(JSONObject card, long now) throws Exception {
        if (!"running".equals(card.optString("status"))) return false;
        finish(card, "interrupted", now);
        return true;
    }

    private static void finish(JSONObject card, String status, long at) throws Exception {
        long started = card.optLong("startedAt", at);
        card.put("status", status).put("completedAt", at).put("elapsedMs", Math.max(0, at - started)).put("activity", "");
    }

    /** What a running child is doing, in the device language. */
    static String stage(AcpSubagents.Child child, Locale locale) {
        boolean zh = AcpToolLabels.chinese(locale);
        switch (child.stage) {
            case "tool":
                return AcpToolLabels.label(AcpToolLabels.identify(child.lastTool, "", ""), locale);
            case "reporting":
                return zh ? "回报结果" : "Reporting back";
            case "thinking":
                return zh ? "思考中" : "Thinking";
            default:
                return zh ? "启动中" : "Starting";
        }
    }

    /**
     * A model id as a name: {@code gemini-4-neon-high} → {@code Gemini 4 Neon},
     * {@code claude-opus-5-5} → {@code Claude Opus 5.5}. A trailing reasoning-effort word is dropped;
     * a value that already reads as a name ("Gemini 4 Neon") is kept.
     */
    static String model(String id) {
        String raw = id == null ? "" : id.trim();
        if (raw.isEmpty() || raw.length() > 80) return "";
        if (raw.contains(" ")) return clean(raw, 40);
        String[] parts = raw.split("[-_/]+");
        int end = parts.length;
        while (end > 1 && parts[end - 1].toLowerCase(Locale.ROOT).matches("minimal|low|medium|high|xhigh|max|thinking|preview|latest")) end--;
        StringBuilder out = new StringBuilder();
        boolean lastNumber = false;
        for (int i = 0; i < end; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            boolean number = part.matches("\\d+");
            if (out.length() > 0) out.append(number && lastNumber ? "." : " ");
            out.append(number ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
            lastNumber = number;
        }
        return out.toString();
    }

    /**
     * The first line of substance of a Markdown report, as plain text: headings and lead-ins ending in
     * a colon name what follows, so the line after one wins; table rules and fences are skipped; emphasis, code marks and links keep
     * their words.
     */
    static String summary(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String heading = "";
        boolean fence = false;
        for (String raw : markdown.split("\\n")) {
            String line = raw.trim();
            if (line.startsWith("```")) { fence = !fence; continue; }
            if (fence || line.isEmpty() || line.matches("[-*_=|:\\s]{3,}")) continue;
            String plain = plain(line);
            if (plain.isEmpty()) continue;
            // "Results:" only introduces what follows, like a heading.
            boolean isHeading = line.startsWith("#") || plain.endsWith(":") || plain.endsWith("：");
            if (isHeading) {
                if (heading.isEmpty()) heading = plain;
                continue;
            }
            return AcpConversationStore.clip(plain, MAX_SUMMARY);
        }
        return AcpConversationStore.clip(heading, MAX_SUMMARY);
    }

    private static String plain(String line) {
        String out = line.replaceFirst("^#{1,6}\\s*", "").replaceFirst("^(?:[-*+>]|\\d+[.)])\\s+", "");
        if (out.startsWith("|")) out = out.replaceAll("\\s*\\|\\s*", " ").trim();
        out = out.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("\\*\\*|__|`", "").replaceAll("(?<![\\w*])\\*(?!\\s)([^*]+)\\*", "$1");
        return out.trim();
    }

    /** Redacted, internal paths removed, clipped. */
    static String clean(String text, int max) {
        if (text == null || text.isBlank()) return "";
        String bounded = AcpConversationStore.clip(text.trim(), Math.max(max * 2, 256));
        return AcpConversationStore.clip(AcpToolLabels.sanitize(AcpRuntime.redact(bounded)).trim(), max);
    }
}
