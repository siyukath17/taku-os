package org.agentos.lab;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        /** Another MCP server's name ({@code taku_memory}, {@code taku_macmini}), without disambiguator; empty otherwise. */
        final String server;
        /** That server's tool ({@code memory_search}, {@code taku_exec}); empty when {@link #server} is. */
        final String serverTool;

        Tool(String key, String name, boolean phone, String title) {
            this(key, name, phone, title, "", "");
        }

        Tool(String key, String name, boolean phone, String title, String server, String serverTool) {
            this.key = key;
            this.name = name;
            this.phone = phone;
            this.title = title;
            this.server = server;
            this.serverTool = serverTool;
        }

        /** Another MCP server's tool (not taku_phone). */
        boolean mcp() {
            return !server.isEmpty();
        }

        /** The canonical tool id the chat page understands: {@code taku_phone__<key>} or the bare key. */
        String canonical() {
            if (phone) return "taku_phone__" + key;
            if (mcp()) return server + "__" + serverTool;
            return "tool".equals(key) && !title.isEmpty() ? title : key;
        }
    }

    /**
     * A taku_phone tool name. agy prefixes {@code mcp_}; when one agy process holds several
     * {@code taku_phone} servers (a warm process serving several conversations, each with its own
     * declaration) it inserts an 8-hex disambiguator: {@code mcp_taku_phone_3013b55d_apps}.
     */
    private static final Pattern PHONE = Pattern.compile("^(?:mcp_)?taku_phone(?:__|_|\\.|/)(?:[0-9a-f]{8}_)?([a-z][a-z0-9_]*)$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:/-]{0,79}");
    /**
     * Any other MCP tool as agy names it: {@code mcp_<server>_<tool>}, with an optional 8-hex
     * disambiguator after the server ({@code mcp_taku_memory_3013b55d_memory_search}), or
     * {@code mcp__<server>__<tool>}.
     */
    private static final Pattern MCP = Pattern.compile("^mcp_{1,2}([a-z0-9][a-z0-9_-]{0,119})$");
    private static final Pattern MCP_HASHED = Pattern.compile("^([a-z0-9][a-z0-9_-]*?)_[0-9a-f]{8}_([a-z0-9][a-z0-9_-]*)$");
    private static final Pattern MCP_PART = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    /** taku-runtime's tools on a remote node (taku-runtime/internal/server/mcp.go). */
    private static final Set<String> RUNTIME_TOOLS = Set.of("taku_capabilities", "taku_exec", "taku_run_prompt", "taku_call_tool");
    /** {@code en} and {@code zh} labels of taku-runtime tools; {@code %s} is the node. */
    private static final Map<String, String[]> RUNTIME_LABELS = Map.of(
            "taku_capabilities", new String[]{"Check %s capabilities", "查询 %s 能力"},
            "taku_exec", new String[]{"Run a command on %s", "在 %s 执行命令"},
            "taku_run_prompt", new String[]{"Hand a task to %s", "派 %s 运行任务"},
            "taku_call_tool", new String[]{"Use a tool on %s", "在 %s 调用工具"});
    /** {@code en} and {@code zh} labels of the taku_memory knowledge-base server's tools. */
    private static final Map<String, String[]> MEMORY_LABELS = Map.of(
            "memory_search", new String[]{"Search memory", "搜索知识库"},
            "memory_read", new String[]{"Read memory note", "读取知识库笔记"},
            "memory_capture", new String[]{"Capture to memory", "写入知识库"},
            "memory_profile_summary", new String[]{"Read profile summary", "读取个人画像摘要"},
            "memory_propose_profile", new String[]{"Propose a profile update", "提议更新个人画像"});

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
        String[] mcp = mcpParts(n);
        if (mcp != null) return new Tool(mcp[0] + "__" + mcp[1], n, false, human, mcp[0], mcp[1]);
        String key = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return new Tool(key.isEmpty() ? "tool" : key, n, false, human);
    }

    /**
     * {server, tool} of an agy MCP tool name, else null. A double underscore or the disambiguator
     * separates them exactly; otherwise a taku-runtime tool name or the {@code taku_memory} prefix
     * does, and failing that the server is the first word ({@code taku_<word>} for Taku's own).
     */
    static String[] mcpParts(String name) {
        Matcher m = MCP.matcher(name == null ? "" : name);
        if (!m.matches()) return null;
        String rest = m.group(1), server, tool;
        int split = rest.indexOf("__");
        Matcher hashed = MCP_HASHED.matcher(rest);
        if (split > 0) {
            server = rest.substring(0, split);
            tool = rest.substring(split + 2).replaceFirst("^[0-9a-f]{8}_", "");
        } else if (hashed.matches()) {
            server = hashed.group(1);
            tool = hashed.group(2);
        } else {
            server = null;
            tool = null;
            for (String runtime : RUNTIME_TOOLS) {
                if (rest.endsWith("_" + runtime) && rest.length() > runtime.length() + 1) {
                    server = rest.substring(0, rest.length() - runtime.length() - 1);
                    tool = runtime;
                }
            }
            if (server == null && rest.startsWith("taku_memory_")) {
                server = "taku_memory";
                tool = rest.substring("taku_memory_".length());
            }
            if (server == null) {
                String[] words = rest.split("_");
                int serverWords = "taku".equals(words[0]) && words.length > 2 ? 2 : 1;
                if (words.length <= serverWords) return null;
                server = String.join("_", java.util.Arrays.copyOfRange(words, 0, serverWords));
                tool = rest.substring(server.length() + 1);
            }
        }
        if (!MCP_PART.matcher(server).matches() || !MCP_PART.matcher(tool).matches()) return null;
        return new String[]{server, tool};
    }

    /** The node a taku-runtime server reaches: {@code taku_macmini} → {@code macmini}. */
    private static String node(String server) {
        String node = server.startsWith("taku_") ? server.substring(5) : server;
        return node.isEmpty() || "runtime".equals(node) ? "" : node;
    }

    /** The label of another MCP server's tool: Taku's own by name, any other as {@code server · tool}. */
    private static String mcpLabel(Tool tool, boolean zh) {
        if (RUNTIME_TOOLS.contains(tool.serverTool)) {
            String node = node(tool.server);
            return String.format(RUNTIME_LABELS.get(tool.serverTool)[zh ? 1 : 0], node.isEmpty() ? (zh ? "远端节点" : "the remote node") : node);
        }
        String[] memory = MEMORY_LABELS.get(tool.serverTool);
        if (memory != null && tool.server.endsWith("memory")) return memory[zh ? 1 : 0];
        if (!tool.title.isEmpty()) return tool.title;
        return tool.server + " · " + tool.serverTool.replace('_', ' ');
    }

    static boolean chinese(Locale locale) {
        return locale != null && "zh".equals(locale.getLanguage());
    }

    /** The user-facing name in the device language (zh or en). */
    static String label(Tool tool, Locale locale) {
        String[] pair = LABELS.get(tool.key);
        boolean zh = chinese(locale);
        if (tool.mcp()) return mcpLabel(tool, zh);
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
            "`?(?<![\\w./-])(?:mcp_{0,2})?taku_?phone(?:__|_|\\.|/)?(?:[0-9a-f]{8}_)?([a-z][a-z0-9_]*)(?![\\w/-]|\\.\\w)(?:\\(\\))?`?",
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
     * {@code file://} or relative ({@code ../acp/home/…}) paths. As a regular expression (the
     * definition {@link #internalPathStart} implements; AcpFaultInjectionTest checks them against
     * each other):
     * <pre>
     * (?:file://)?(?:(?:\.\./)+|/)?(?:data/local/agentos/[^D]*|(?:[^D/]+/)*(?:acp/home|\.gemini)/[^D]*)
     * </pre>
     * with {@code D} the delimiters {@code \s ) ] " ' ` < >}. A regex engine runs this in quadratic
     * time on a long run without delimiters (the segment loop rescans the run from every start; 40K
     * characters of CJK text or base64 took over a second on a desktop JVM, a capped 1M-character reply
     * minutes), which held the turn thread, and with it the broker, as a long reply finished.
     * {@link #INTERNAL_LINK} stays a regex: it runs only on text with {@code "]("} and a marker, and
     * each link candidate is bounded by its 200-character text and its target's first {@code )} or
     * blank (only a long blank-free run packed with {@code "]("} would make it slow).
     */
    private static final String AGENT_DATA = "data/local/agentos/";
    private static final String[] SEGMENT_MARKERS = {"acp/home/", ".gemini/"};
    private static final Pattern INTERNAL_LINK = Pattern.compile(
            "\\[([^\\]\\n]{0,200})\\]\\((?:conversation://[^)\\s]*|(?:file://)?(?:(?:\\.\\./)+|/)?(?:data/local/agentos/|[^)\\s]*(?:acp/home|\\.gemini)/)[^)\\s]*)\\)");

    /**
     * Text for display with agent-internal paths removed: a Markdown link to an internal file or a
     * subagent conversation keeps only its text; a bare internal path becomes its file name (or
     * {@code [internal file]} for a directory). Linear in the text for the bare paths.
     */
    static String sanitize(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        // Every internal link or path contains one of these; most replies contain none.
        boolean paths = text.contains(AGENT_DATA) || text.contains(SEGMENT_MARKERS[0]) || text.contains(SEGMENT_MARKERS[1]);
        if (!paths && !text.contains("conversation://")) return text;
        String out = text.contains("](") ? INTERNAL_LINK.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1))) : text;
        if (!paths) return out;
        StringBuilder sb = new StringBuilder(out.length());
        int n = out.length(), copied = 0;
        for (int a = 0; a < n; ) {
            if (pathDelimiter(out.charAt(a))) { a++; continue; }
            int b = a;
            while (b < n && !pathDelimiter(out.charAt(b))) b++;
            // A match never crosses a delimiter and its tail runs to the token's end: at most one per token.
            int start = internalPathStart(out, a, b);
            if (start >= 0) {
                sb.append(out, copied, start);
                String match = out.substring(start, b), path = match;
                while (path.endsWith(".") || path.endsWith(",") || path.endsWith(";") || path.endsWith(":")) path = path.substring(0, path.length() - 1);
                String rest = match.substring(path.length());
                int slash = path.lastIndexOf('/');
                String base = slash >= 0 ? path.substring(slash + 1) : path;
                sb.append(base.matches("[^/]{1,80}\\.[A-Za-z0-9]{1,8}") ? base : "[internal file]").append(rest);
                copied = b;
            }
            a = b;
        }
        return copied == 0 ? out : sb.append(out, copied, n).toString();
    }

    /** {@code D} of the internal path definition: regex {@code \s} (ASCII only) and {@code ) ] " ' ` < >}. */
    private static boolean pathDelimiter(char c) {
        switch (c) {
            case ' ': case '\t': case '\n': case 0x0B: case '\f': case '\r':
            case ')': case ']': case '"': case '\'': case '`': case '<': case '>':
                return true;
            default:
                return false;
        }
    }

    /**
     * Where the leftmost internal path in the delimiter-free token {@code t[a, b)} starts, or -1. One
     * pass: {@code chain} is the leftmost start of a run of non-empty {@code segment/} units ending at
     * the current segment boundary {@code segment} (an empty segment, {@code //}, restarts it).
     */
    static int internalPathStart(String t, int a, int b) {
        int best = -1, chain = a, segment = a;
        for (int p = a; p < b; p++) {
            if (t.startsWith(AGENT_DATA, p)) best = leftmost(best, agentDataStart(t, a, p));
            for (String marker : SEGMENT_MARKERS)
                if (t.startsWith(marker, p)) best = leftmost(best, prefixedStart(t, a, p == segment ? chain : p));
            if (best == a) return a;
            if (t.charAt(p) == '/') {
                if (p == segment) chain = p + 1;
                segment = p + 1;
            }
        }
        return best;
    }

    private static int leftmost(int best, int candidate) {
        return best < 0 ? candidate : Math.min(best, candidate);
    }

    /** {@code (?:file://)?(?:(?:\.\./)+|/)?} before {@code data/local/agentos/} at {@code m}. */
    private static int agentDataStart(String t, int a, int m) {
        int best = withFileScheme(t, a, m);
        if (m - 1 >= a && t.charAt(m - 1) == '/') best = Math.min(best, withFileScheme(t, a, m - 1));
        int up = m;
        while (up - 3 >= a && t.startsWith("../", up - 3)) up -= 3;
        if (up < m) best = Math.min(best, withFileScheme(t, a, up));
        return best;
    }

    /**
     * {@code (?:file://)?(?:(?:\.\./)+|/)?} before a segment run starting at {@code c} ({@code ../}
     * units are segments of the run already).
     */
    private static int prefixedStart(String t, int a, int c) {
        int best = withFileScheme(t, a, c);
        if (c - 1 >= a && t.charAt(c - 1) == '/') best = Math.min(best, withFileScheme(t, a, c - 1));
        return best;
    }

    private static int withFileScheme(String t, int a, int c) {
        return c - 7 >= a && t.startsWith("file://", c - 7) ? c - 7 : c;
    }
}
