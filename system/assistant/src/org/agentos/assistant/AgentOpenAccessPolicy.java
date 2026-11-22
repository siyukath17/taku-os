package org.agentos.assistant;

import java.util.Set;
import java.util.function.Predicate;

/** Scope selection happens before parsing or dispatch and before clearing Binder identity. */
public final class AgentOpenAccessPolicy {
    private static final Set<String> ROOT_METHODS = Set.of(
            "plugin/install", "plugin/uninstall", "config/value/write", "config/mcpServer/reload",
            // Gate the entire multiplexed endpoint, including future inner operations.
            "manageAssistant", "manageSystemAi", "setSystemAi", "bindSystemAi",
            "bindSystemAiModel", "reorderSystemAi", "restoreSystemAi", "deleteSystemAiGroup",
            "interrupt", "cancelQueued", "continueTask", "readTaskDetail");
    private static final Set<String> CONTROL_READS = Set.of(
            "requests/list", "requests/read", "accounts/read", "accounts/keys",
            "composer/read", "composer/receipt", "composer/options/read",
            "skills/read", "skills/community/catalog", "skills/community/detail",
            "skills/community/check-update", "skills/credentials/status");

    private AgentOpenAccessPolicy() {}

    public static String requiredScope(String method) {
        // Draft/option writes can alter a later GUI submission; protect those as well as submit.
        boolean sensitiveFamily = method.startsWith("requests/") || method.startsWith("accounts/")
                || method.startsWith("composer/") || method.startsWith("skills/")
                || method.startsWith("capture/") || method.startsWith("jev/")
                || method.startsWith("account/login/");
        return ROOT_METHODS.contains(method) || sensitiveFamily && !CONTROL_READS.contains(method)
                ? "taku.root" : "taku.control";
    }

    public static String denial(String method, Predicate<String> allowed) {
        return allowed.test(requiredScope(method)) ? null : "PERMISSION_DENIED";
    }
}
