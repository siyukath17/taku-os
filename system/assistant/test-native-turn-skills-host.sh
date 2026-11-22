#!/bin/bash
# Exercise the real native turn/context/skill path against a local stdio RPC fixture.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/native-turn-skills-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/CodexAppServer.java \
 system/assistant/broker/org/agentos/lab/ComposerInput.java \
 system/assistant/broker/org/agentos/lab/HarnessContext.java \
 system/assistant/broker/org/agentos/lab/HarnessStore.java \
 system/assistant/broker/org/agentos/lab/AgentSkills.java \
 system/assistant/broker/org/agentos/lab/BuiltinSkillUpdate.java \
 system/assistant/tests/native-turn-skills/android/system/Os.java \
 system/assistant/tests/acp-doubles/org/agentos/lab/AcpConversationsTestDouble.java \
 system/assistant/broker/org/agentos/lab/AcpConversationStore.java \
 system/assistant/broker/org/agentos/lab/TaskRecords.java \
 system/assistant/tests/native-turn-skills/org/agentos/lab/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.NativeTurnSkillsTest | tee "$task_out/result.txt"
