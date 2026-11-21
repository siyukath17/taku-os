#!/bin/bash
# ACP runtime, runtime router and phone MCP bridge: host tests with an in-process fake ACP agent.
# Production sources compile first against android.jar ALONE (Android's org.json: checked
# JSONException, no keySet), then against the host json jar ALONE (no Android dependency).
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/acp-runtime-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
rm -rf "$task_out" && mkdir -p "$task_out/classes" "$task_out/android-check"
task_sources=(
 system/assistant/broker/org/agentos/lab/TaskRecords.java
 system/assistant/broker/org/agentos/lab/TaskRuntime.java
 system/assistant/broker/org/agentos/lab/RuntimeRouter.java
 system/assistant/broker/org/agentos/lab/AcpProcessTransport.java
 system/assistant/broker/org/agentos/lab/AcpRuntime.java
 system/assistant/broker/org/agentos/lab/PhoneMcpBridge.java
)
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/android-check" "${task_sources[@]}"
"$JDK/bin/javac" --release 17 -Xlint:all -Werror -Xlint:-serial -cp "$task_json" -d "$task_out/classes" \
 "${task_sources[@]}" \
 system/assistant/tests/acp-runtime/org/agentos/lab/FakeAcpAgent.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/RuntimeRouterTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpRuntimeTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/PhoneMcpBridgeTest.java
for test in RuntimeRouterTest AcpRuntimeTest PhoneMcpBridgeTest; do
 "$JDK/bin/java" -cp "$task_out/classes:$task_json" "org.agentos.lab.$test"
done | tee "$task_out/result.txt"
