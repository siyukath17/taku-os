#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/execution-screens-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
# BUG-0034: the display host's sources (VirtualDisplayHost, ScreenGuard...) compile against android.jar alone.
mkdir -p "$task_out/display-android"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/display-android" system/display/src/org/agentos/lab/*.java
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/display/src/org/agentos/lab/ScreenGuard.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreens.java \
 system/assistant/broker/org/agentos/lab/WorkspacePolicy.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreensPointers.java \
 system/assistant/broker/org/agentos/lab/PhoneGestureSession.java \
 system/assistant/tests/execution-screens/org/agentos/lab/PhoneExecutionScreensTest.java \
 system/assistant/tests/execution-screens/org/agentos/lab/ScreenOffCaptureTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.PhoneExecutionScreensTest | tee "$task_out/result.txt"
# BUG-0034: independent-screen display flags, and SCREEN_OFF / black-frame captures instead of a black image.
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.ScreenOffCaptureTest | tee -a "$task_out/result.txt"
