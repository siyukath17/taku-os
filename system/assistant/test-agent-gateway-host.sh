#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_out="$PWD/out/agent-gateway-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
rm -rf "$task_out/classes"
mkdir -p "$task_out/classes"
compile_jvm "$task_out/classes" "$task_json:$ANDROID_JAR" \
 system/assistant/src/org/agentos/assistant/AgentGatewayProtocol.kt \
 system/assistant/src/org/agentos/assistant/ComposerState.kt \
 system/assistant/src/org/agentos/assistant/ComposerPreferences.kt \
 system/assistant/src/org/agentos/assistant/AgentGatewaySubmissions.kt \
 system/assistant/src/org/agentos/assistant/PanelProtocol.kt \
 system/assistant/src/org/agentos/assistant/TaskDetails.kt \
 system/assistant/src/org/agentos/assistant/TakuConnectContract.kt \
 system/assistant/tests/agent-gateway/org/agentos/assistant/*.java \
 system/assistant/tests/frontend-route/org/agentos/assistant/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.assistant.AgentGatewayProtocolTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.assistant.ComposerStateTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.assistant.AgentGatewaySubmissionTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.assistant.TaskDetailsTest
