#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/session-removals-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/BoundedCommand.java \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/broker/org/agentos/lab/TaskRecords.java \
 system/assistant/broker/org/agentos/lab/AcpConversationStore.java \
 system/assistant/broker/org/agentos/lab/AcpSessionRemovals.java \
 system/assistant/broker/org/agentos/lab/AcpCodexGate.java \
 system/assistant/tests/session-removals/org/agentos/lab/RuntimeRouter.java \
 system/assistant/tests/session-removals/org/agentos/lab/AcpSessionRemovalsTest.java \
 system/assistant/tests/session-removals/org/agentos/lab/SessionRemovalsTest.java \
 system/assistant/tests/session-removals/org/agentos/lab/SessionRemovalsRoundRobinTest.java
if [ "${1:-}" = round-robin ]; then
 "$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.SessionRemovalsRoundRobinTest | tee "$task_out/result-round-robin.txt"
 exit
fi
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.SessionRemovalsTest "$@" | tee "$task_out/result${1:+-$1}.txt"
if [ "$#" = 0 ]; then
 "$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.AcpSessionRemovalsTest | tee "$task_out/result-acp.txt"
 "$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.SessionRemovalsRoundRobinTest | tee "$task_out/result-round-robin.txt"
fi
