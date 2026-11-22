#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
source ../../scripts/compile-jvm.sh
[ -f ../audio-engine/out/audio-client.jar ] || bash ../audio-engine/build.sh
AUDIO_CP=../audio-engine/out/audio-client.jar
node ../agent-app/scripts/export-reply-contract.ts
rm -rf out/classes out/dex out/broker-classes out/broker-dex out/generated
mkdir -p out/classes out/dex out/broker-classes out/broker-dex out/generated
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
"$TOOLS/aapt2" compile --dir res -o out/resources.zip
"$TOOLS/aapt2" link -o out/resources.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java out/generated -A assets -A out/reply-assets out/resources.zip
compile_jvm out/classes "$ANDROID_JAR:$AUDIO_CP" out/generated/org/agentos/assistant/R.java \
 src/org/agentos/assistant/*.java src/org/agentos/assistant/*.kt broker/org/agentos/lab/UiCardRequest.kt \
 broker/org/agentos/lab/ComposerInput.java \
 ../../tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java \
 ../surfaces/contract/org/agentos/surfaces/*.java ../surfaces/wire/org/agentos/surfaces/*.java ../surfaces/client/org/agentos/surfaces/client/*.java
"$JDK/bin/jar" --create --file out/classes.jar -C out/classes .
# audio-client.jar is built with -include-runtime (same kotlinc), so it already carries the
# Kotlin stdlib the app classes need; adding kotlin-stdlib.jar again defines every class twice.
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/dex out/classes.jar "$AUDIO_CP"
cp out/resources.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 out/unsigned.apk out/aligned.apk
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out out/AgentOSAssistant.apk out/aligned.apk
compile_jvm out/broker-classes "$ANDROID_JAR" broker/org/agentos/lab/*.java broker/org/agentos/lab/*.kt \
 ../../tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java
"$JDK/bin/jar" --create --file out/broker-classes.jar -C out/broker-classes .
# javac resolves java.* from JDK 17: fail on platform members ART lacks (e.g. Redirect.DISCARD).
python3 scripts/check-android-api.py "$ANDROID_JAR" out/broker-classes.jar
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/broker-dex out/broker-classes.jar "$KOTLIN_LIB/kotlin-stdlib.jar"
"$JDK/bin/jar" --create --file out/task-broker.jar -C out/broker-dex classes.dex -C assets licenses/Kotlin-LICENSE.txt -C assets licenses/Kotlin-NOTICE.txt
