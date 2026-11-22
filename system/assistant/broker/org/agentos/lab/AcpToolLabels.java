package org.agentos.lab;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Display names for the tool calls an ACP agent reports, and redaction of agent-internal file paths
 * from everything shown to the user.
 *
 * <p>agy reports a tool call as {@code name} (e.g. {@code mcp_taku_phone_device_info},
 * {@code invoke_subagent}) plus a {@code title} written for a terminal ("Run mcp_taku_phone_tap",
 * "File ../acp/home/.gemini/taku-acp/brain/…/media_0.png"). The chat page and the Dynamic Island show
 * {@link #label} instead; the agent's own name is kept as {@link Tool#name} for diagnostics only.
 */
final class AcpToolLabels {
    private AcpToolLabels() {}

    /** One reported tool call, identified. */
    static final class Tool {
        /** Stable key for labels and app-side translation, e.g. {@code device_info}, {@code invoke_subagent}. */
        final String key;
        /** The agent's own tool name (diagnostics), e.g. {@code mcp_taku_phone_device_info}. */
        final String name;
        /** A taku_phone tool, whatever prefix the agent gave it. */
        final boolean phone;
        /** A human-written title (not agy's "Run …"/"File …" form), sanitized; empty otherwise. */
        final String title;

        Tool(String key, String name, boolean phone, String title) {
            this.key = key;
            this.name = name;
            this.phone = phone;
            this.title = title;
        }

        /** The canonical tool id the chat page understands: {@code taku_phone__<key>} or the bare key. */
        String canonical() {
            if (phone) return "taku_phone__" + key;
            return "tool".equals(key) && !title.isEmpty() ? title : key;
        }
    }

    private static final Pattern PHONE = Pattern.compile("^(?:mcp_)?taku_phone(?:__|_|\\.|/)([a-z][a-z0-9_]*)$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:/-]{0,79}");

    /** {@code en} and {@code zh} labels by key. Unknown keys fall back to a generic label. */
    private static final Map<String, String[]> LABELS = Map.ofEntries(
            Map.entry("device_info", new String[]{"Read device info", "读取设备信息"}),
            Map.entry("request_apps", new String[]{"Request a background screen", "申请后台专屏"}),
            Map.entry("release_apps", new String[]{"Release the background screen", "释放后台专屏"}),
            Map.entry("screen_status", new String[]{"Check the background screen", "查看专屏状态"}),
            Map.entry("open_app", new String[]{"Open app", "打开应用"}),
            Map.entry("screenshot", new String[]{"View the screen", "查看屏幕"}),
            Map.entry("observe_background", new String[]{"View the screen", "查看屏幕"}),
            Map.entry("tap", new String[]{"Tap the screen", "点击屏幕"}),
            Map.entry("touch", new String[]{"Touch the screen", "触摸屏幕"}),
            Map.entry("swipe", new String[]{"Swipe the screen", "滑动屏幕"}),
            Map.entry("type_text", new String[]{"Enter text", "输入文字"}),
            Map.entry("apps", new String[]{"Find apps", "查找应用"}),
            Map.entry("ask_user", new String[]{"Ask you", "向你确认"}),
            Map.entry("confirm_action", new String[]{"Confirm an action", "确认操作"}),
            Map.entry("prepare_foreground_draft", new String[]{"Prepare a draft", "准备草稿"}),
            Map.entry("memory_get", new String[]{"Read memory", "读取记忆"}),
            Map.entry("memory_search", new String[]{"Search memory", "搜索记忆"}),
            Map.entry("invoke_subagent", new String[]{"Dispatch a sub-task", "派发子任务"}),
            Map.entry("manage_subagents", new String[]{"Check sub-tasks", "查看子任务"}),
            Map.entry("send_message", new String[]{"Message a sub-task", "联系子任务"}),
            Map.entry("run_command", new String[]{"Run command", "运行命令"}),
            Map.entry("command_status", new String[]{"Check command", "查看命令进度"}),
            Map.entry("read_file", new String[]{"Read file", "读取文件"}),
            Map.entry("view_file", new String[]{"Read file", "读取文件"}),
            Map.entry("list_dir", new String[]{"Browse files", "浏览文件"}),
            Map.entry("write_to_file", new String[]{"Write file", "写入文件"}),
            Map.entry("replace_file_content", new String[]{"Edit file", "编辑文件"}),
            Map.entry("grep_search", new String[]{"Search files", "搜索文件"}),
            Map.entry("find_by_name", new String[]{"Find files", "查找文件"}),
            Map.entry("list_resources", new String[]{"List tool resources", "查看工具资源"}),
            Map.entry("read_resource", new String[]{"Read tool resource", "读取工具资源"}),
            Map.entry("search_web", new String[]{"Search the web", "搜索网页"}),
            Map.entry("read_url_content", new String[]{"Read web page", "读取网页"}));

    /**
     * Identifies a tool call from its ACP {@code name} (agy extension), else from its title
     * ("Run X" → X; a {@code read} title → {@code read_file}). Never returns null.
     */
    static Tool identify(String name, String title, String kind) {
        String n = name == null ? "" : name.trim();
        String t = title == null ? "" : title.trim();
        boolean agyTitle = t.startsWith("Run ") || t.startsWith("File ") || NAME.matcher(t).matches();
        if (n.isEmpty() && t.startsWith("Run ") && NAME.matcher(t.substring(4).trim()).matches()) n = t.substring(4).trim();
        if (n.isEmpty() && t.startsWith("File ")) n = "read_file";
        if (n.isEmpty() && NAME.matcher(t).matches()) n = t;
        String human = agyTitle ? "" : sanitize(t);
        if (human.length() > 80) human = human.substring(0, 79) + "…";
        Matcher phone = PHONE.matcher(n);
        if (phone.matches()) return new Tool(phone.group(1), n, true, human);
        String key = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return new Tool(key.isEmpty() ? "tool" : key, n, false, human);
    }

    static boolean chinese(Locale locale) {
        return locale != null && "zh".equals(locale.getLanguage());
    }

    /** The user-facing name in the device language (zh or en). */
    static String label(Tool tool, Locale locale) {
        String[] pair = LABELS.get(tool.key);
        boolean zh = chinese(locale);
        if (pair != null) return pair[zh ? 1 : 0];
        if (tool.phone) return zh ? "操作手机" : "Use the phone";
        if (!tool.title.isEmpty()) return tool.title; // Another ACP agent's own readable title.
        return zh ? "调用工具" : "Use a tool";
    }

    // ---- internal tool names in prose ----------------------------------------------------------

    /**
     * A taku_phone tool name as an agent writes it in prose: {@code mcp_taku_phone_device_info},
     * {@code mcp__taku_phone__tap}, {@code taku_phone.open_app}, and the squashed
     * {@code mcptakuphonedeviceinfo} some models produce. Group 1 is the tool part.
     */
    private static final Pattern PHONE_IN_TEXT = Pattern.compile(
            "`?(?<![\\w./-])(?:mcp_{0,2})?taku_?phone(?:__|_|\\.|/)?([a-z][a-z0-9_]*)(?![\\w/-]|\\.\\w)(?:\\(\\))?`?",
            Pattern.CASE_INSENSITIVE);
    /** A bare snake_case tool key the agent runs (e.g. {@code invoke_subagent}, {@code view_file}). */
    private static final Pattern KEY_IN_TEXT = Pattern.compile("`?(?<![\\w./-])([a-z][a-z0-9]*(?:_[a-z0-9]+)+)(?![\\w/-]|\\.\\w)(?:\\(\\))?`?");

    /**
     * Text for display with agent-internal tool identifiers replaced by their user-facing names in
     * the device language: "called `mcp_taku_phone_device_info`" → "called Read device info" /
     * "调用了读取设备信息". Known agent tool keys ({@code invoke_subagent}) read the same way; any other
     * snake_case word is left alone. Used for subagent cards (goal, report, summary).
     */
    static String humanize(String text, Locale locale) {
        String phoneNamed = humanizePhoneTools(text, locale);
        boolean zh = chinese(locale);
        Matcher key = KEY_IN_TEXT.matcher(phoneNamed);
        StringBuilder out = new StringBuilder();
        while (key.find()) {
            String[] pair = LABELS.get(key.group(1));
            key.appendReplacement(out, Matcher.quoteReplacement(pair == null ? key.group() : unquoted(key.group(), pair[zh ? 1 : 0])));
        }
        key.appendTail(out);
        return out.toString();
    }

    /**
     * Only the taku_phone tool identifiers of {@link #humanize}, which never mean anything else: safe
     * for the agent's chat messages, where a bare {@code read_file} may be the user's own code.
     */
    static String humanizePhoneTools(String text, Locale locale) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        boolean zh = chinese(locale);
        Matcher phone = PHONE_IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (phone.find()) phone.appendReplacement(sb, Matcher.quoteReplacement(unquoted(phone.group(), phoneLabel(phone.group(1), zh))));
        phone.appendTail(sb);
        return sb.toString();
    }

    /** {@code label} in place of {@code found}; a lone backtick belongs to the surrounding text and stays. */
    private static String unquoted(String found, String label) {
        boolean open = found.startsWith("`"), close = found.endsWith("`") && found.length() > 1;
        if (open == close) return label;
        return open ? "`" + label : label + "`";
    }

    /** The label of a phone tool written as {@code device_info} or squashed as {@code deviceinfo}. */
    private static String phoneLabel(String tool, boolean zh) {
        String wanted = tool.toLowerCase(Locale.ROOT);
        String[] pair = LABELS.get(wanted);
        if (pair == null) {
            String squashed = wanted.replace("_", "");
            for (Map.Entry<String, String[]> entry : LABELS.entrySet()) {
                if (entry.getKey().replace("_", "").equals(squashed)) {
                    pair = entry.getValue();
                    break;
                }
            }
        }
        if (pair != null) return pair[zh ? 1 : 0];
        return zh ? "手机操作" : "a phone action";
    }

    // ---- internal paths --------------------------------------------------------------------------

    /**
     * Agent-internal locations: the agent home and app data ({@code …/acp/home/…}, {@code .gemini/…}),
     * the agent's work directory and the rest of {@code /data/local/agentos}, as absolute,
     * {@code file://} or relative ({@code ../acp/home/…}) paths.
     */
    private static final Pattern INTERNAL_PATH = Pattern.compile(
            "(?:file://)?(?:(?:\\.\\./)+|/)?(?:data/local/agentos/[^\\s)\\]\"'`<>]*|(?:[^\\s)\\]\"'`<>/]+/)*(?:acp/home|\\.gemini)/[^\\s)\\]\"'`<>]*)");
    private static final Pattern INTERNAL_LINK = Pattern.compile(
            "\\[([^\\]\\n]{0,200})\\]\\((?:conversation://[^)\\s]*|(?:file://)?(?:(?:\\.\\./)+|/)?(?:data/local/agentos/|[^)\\s]*(?:acp/home|\\.gemini)/)[^)\\s]*)\\)");

    /**
     * Text for display with agent-internal paths removed: a Markdown link to an internal file or a
     * subagent conversation keeps only its text; a bare internal path becomes its file name (or
     * {@code [internal file]} for a directory).
     */
    static String sanitize(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = INTERNAL_LINK.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        Matcher m = INTERNAL_PATH.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group();
            while (path.endsWith(".") || path.endsWith(",") || path.endsWith(";") || path.endsWith(":")) path = path.substring(0, path.length() - 1);
            String rest = m.group().substring(path.length());
            int slash = path.lastIndexOf('/');
            String base = slash >= 0 ? path.substring(slash + 1) : path;
            String shown = base.matches("[^/]{1,80}\\.[A-Za-z0-9]{1,8}") ? base : "[internal file]";
            m.appendReplacement(sb, Matcher.quoteReplacement(shown + rest));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
