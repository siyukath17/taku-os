#!/bin/bash
# ACP runtime (background and chat turns), the ACP conversation store, remote T3 runtime, runtime
# router, phone MCP bridge, runtime.json settings, the oauth-personal sign-in trigger and the
# progress.json -> taku.island.v1 projection: host tests with an in-process fake ACP agent, an
# in-memory remote T3 host and a loopback HTTP server.
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
 system/assistant/broker/org/agentos/lab/AcpToolLabels.java
 system/assistant/broker/org/agentos/lab/AcpSubagents.java
 system/assistant/broker/org/agentos/lab/PhoneMcpBridge.java
 system/assistant/broker/org/agentos/lab/PhoneDeviceInfo.java
 system/assistant/broker/org/agentos/lab/AcpRuntimeSettings.java
 system/assistant/broker/org/agentos/lab/AcpAuthHelper.java
 system/assistant/broker/org/agentos/lab/RemoteT3Transport.java
 system/assistant/broker/org/agentos/lab/RemoteT3Runtime.java
 system/assistant/broker/org/agentos/lab/AcpConversationStore.java
 system/assistant/broker/org/agentos/lab/AcpTurnControl.java
 system/assistant/broker/org/agentos/lab/AcpModelCatalog.java
 system/assistant/broker/org/agentos/lab/AcpCodexGate.java
 system/assistant/src/org/agentos/assistant/AcpIslandProjection.java
)
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/android-check" "${task_sources[@]}"
"$JDK/bin/javac" --release 17 -Xlint:all -Werror -Xlint:-serial -cp "$task_json" -d "$task_out/classes" \
 "${task_sources[@]}" \
 system/assistant/tests/acp-runtime/org/agentos/lab/FakeAcpAgent.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/RuntimeRouterTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpRuntimeTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/PhoneMcpBridgeTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpRuntimeSettingsTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpAuthHelperTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpIslandProjectionTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/RemoteT3RuntimeTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpConversationStoreTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpChatRuntimeTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpChatFixtures.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpStandaloneConversationTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpModelCatalogTest.java \
 system/assistant/tests/acp-runtime/org/agentos/lab/AcpSubagentRuntimeTest.java \
 system/assistant/tests/acp-runtime/org/agentos/assistant/AcpChatProjectionTest.java \
 system/assistant/src/org/agentos/assistant/AgentConversation.java \
 system/assistant/src/org/agentos/assistant/NativeToolPresentation.java \
 system/assistant/tests/acp-runtime/android/util/JsonReader.java \
 system/assistant/tests/acp-runtime/android/util/JsonToken.java \
 system/island/src/com/android/systemui/taku/island/IslandPayload.java
for test in RuntimeRouterTest AcpRuntimeTest PhoneMcpBridgeTest AcpRuntimeSettingsTest AcpAuthHelperTest AcpIslandProjectionTest \
 RemoteT3RuntimeTest AcpConversationStoreTest AcpChatRuntimeTest AcpStandaloneConversationTest AcpModelCatalogTest AcpSubagentRuntimeTest assistant.AcpChatProjectionTest; do
 case "$test" in assistant.*) main="org.agentos.$test" ;; *) main="org.agentos.lab.$test" ;; esac
 "$JDK/bin/java" -cp "$task_out/classes:$task_json" "$main"
done | tee "$task_out/result.txt"
