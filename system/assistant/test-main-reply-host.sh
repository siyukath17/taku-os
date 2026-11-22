#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
mkdir -p "$PWD/out"
task_out="$(mktemp -d "$PWD/out/main-reply-host.XXXXXX")"
trap 'rm -rf "$task_out"' EXIT
task_json="${TAKU_TEST_JSON_JAR:-$task_out/json-20240303.jar}"
task_contract="${TAKU_REPLY_CONTRACT:-$PWD/system/assistant/out/reply-assets/native/reply-contract.json}"
if [ ! -f "$task_contract" ]; then
    printf '%s\n' 'Generate the reply manifest with: bun system/agent-app/scripts/export-reply-contract.ts' >&2
    exit 1
fi
python3 - "$task_json" <<'PY'
import hashlib
import pathlib
import sys
import urllib.request
path = pathlib.Path(sys.argv[1])
digest = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if path.exists():
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise SystemExit('The supplied org.json jar has an unexpected SHA-256')
else:
    data = urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30).read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise SystemExit('The downloaded org.json jar has an unexpected SHA-256')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
PY
compile_jvm "$task_out/classes" "$task_json:$ANDROID_JAR" \
 system/assistant/broker/org/agentos/lab/AgentSessionReducer.java \
 system/assistant/broker/org/agentos/lab/SessionEvents.java \
 system/assistant/broker/org/agentos/lab/UiCardRequest.kt \
 system/assistant/broker/org/agentos/lab/MainReplyStream.kt \
 system/assistant/broker/org/agentos/lab/MainReplySession.kt \
 system/assistant/broker/org/agentos/lab/AgentViews.kt \
 system/assistant/broker/org/agentos/lab/ThreadListOrder.java \
 system/assistant/broker/org/agentos/lab/AgentViewProjection.java \
 system/assistant/tests/main-reply/android/system/Os.java \
 system/assistant/tests/main-reply/org/agentos/lab/MainReplyHostAdapters.java \
 system/assistant/tests/main-reply/org/agentos/lab/MainReplyHostTest.java \
 system/assistant/tests/org/agentos/lab/UiCardRequestTest.java
"$JDK/bin/java" -Dmain.reply.test.root="$task_out/state" \
 -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar:$ANDROID_JAR" \
 org.agentos.lab.MainReplyHostTest "$task_contract"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.UiCardRequestTest
