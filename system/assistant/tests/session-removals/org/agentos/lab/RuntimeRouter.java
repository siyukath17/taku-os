package org.agentos.lab;

import org.json.JSONObject;

/**
 * The route shape {@link AcpCodexGate} reads, without the router's runtime dependencies: removal
 * host tests only need "ACP selected" or "Codex selected". The real router is covered by
 * test-acp-runtime-host.sh.
 */
final class RuntimeRouter {
    static final String CODEX = "codex", ACP = "acp";

    static final class Route {
        final String runtime, requested, reason;
        final JSONObject acpConfig;

        Route(String runtime, String requested, String reason, JSONObject acpConfig) {
            this.runtime = runtime; this.requested = requested; this.reason = reason; this.acpConfig = acpConfig;
        }
    }
}
