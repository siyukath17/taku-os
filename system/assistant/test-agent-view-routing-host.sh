#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$PWD/out"
task_out="$(mktemp -d "$PWD/out/agent-view-routing-host.XXXXXX")"
trap 'rm -rf "$task_out"' EXIT
# Only the native transport, unrelated phone endpoints, and host filesystem are adapters.
# NativeBridge, all three field filters, and PhoneSubAgents are production sources.
compile_jvm "$task_out/classes" "$task_json:$ANDROID_JAR" \
 system/assistant/broker/org/agentos/lab/NativeBridge.java \
 system/assistant/broker/org/agentos/lab/SessionRemovalRuntime.java \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/PhoneConfirmation.java \
 system/assistant/broker/org/agentos/lab/PhoneAskUser.java \
 system/assistant/broker/org/agentos/lab/WorkspaceTextFiles.kt \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/broker/org/agentos/lab/AgentViewProjection.java \
 system/assistant/broker/org/agentos/lab/AgentSessionReducer.java \
 system/assistant/broker/org/agentos/lab/SessionEvents.java \
 system/assistant/broker/org/agentos/lab/UiCardRequest.kt \
 system/assistant/broker/org/agentos/lab/MainReplyStream.kt \
 system/assistant/broker/org/agentos/lab/MainReplySession.kt \
 system/assistant/broker/org/agentos/lab/AgentViews.kt \
 system/assistant/broker/org/agentos/lab/ThreadListOrder.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeTestDoubles.java \
 system/assistant/tests/acp-doubles/org/agentos/lab/AcpConversationsTestDouble.java \
 system/assistant/broker/org/agentos/lab/AcpSessionRemovals.java \
 system/assistant/broker/org/agentos/lab/AcpConversationStore.java \
 system/assistant/broker/org/agentos/lab/TaskRecords.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeHostServer.java \
 system/assistant/tests/main-reply/org/agentos/lab/MainReplyHostAdapters.java \
 system/assistant/tests/main-reply/android/system/Os.java \
 system/assistant/tests/org/agentos/lab/AgentViewProjectionTest.java \
 system/assistant/tests/agent-view-routing/org/agentos/lab/AgentViewRoutingHostTest.java
task_classpath="$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar:$ANDROID_JAR"
"$JDK/bin/java" -Dmain.reply.test.root="$task_out/state" -cp "$task_classpath" \
 org.agentos.lab.AgentViewRoutingHostTest "$@"
"$JDK/bin/java" -cp "$task_classpath" org.agentos.lab.AgentViewProjectionTest
