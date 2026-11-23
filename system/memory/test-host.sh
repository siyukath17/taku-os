#!/bin/bash
# Device-free host checks for TakuMemory: the ROM native-packaging contract (Android.bp / manifest / build.sh, plus the
# pinned ONNX Runtime AAR's arm64 JNI libraries) and the embedder fallback on a host JVM. No adb, nothing installed.
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
OUTPUT=out/host-tests
ORT_AAR=${TAKU_ORT_AAR:-downloads/onnxruntime-android-1.29.0.aar}
if [ ! -f "$ORT_AAR" ]; then
 # Fetch and verify only the AAR (the model is not needed here), from the same pin as scripts/prepare-memory-vectors.py.
 python3 - "$ORT_AAR" <<'PY'
import hashlib, json, pathlib, sys, tempfile, urllib.request
item = next(a for a in json.load(open('vector-dependencies.json'))['assets'] if a['name'] == 'onnxruntime-android')
dest = pathlib.Path(sys.argv[1]); dest.parent.mkdir(parents=True, exist_ok=True)
with urllib.request.urlopen(item['url'], timeout=60) as r: data = r.read()
if hashlib.sha256(data).hexdigest() != item['sha256']: raise SystemExit('ONNX Runtime AAR checksum mismatch')
with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as t: t.write(data)
pathlib.Path(t.name).replace(dest)
PY
fi
python3 tests/host/check_native_packaging.py --self-test "$ORT_AAR"
MARKDOWN_CP=$(python3 ../../scripts/prepare-markdown.py)
rm -rf "$OUTPUT" && mkdir -p "$OUTPUT/classes" "$OUTPUT/fakes" "$OUTPUT/generated" "$OUTPUT/ort"
unzip -q -o -j "$ORT_AAR" classes.jar -d "$OUTPUT/ort"
"$TOOLS/aapt2" compile --dir res -o "$OUTPUT/resources.zip"
"$TOOLS/aapt2" link -o "$OUTPUT/resources.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java "$OUTPUT/generated" "$OUTPUT/resources.zip"
printf 'package org.agentos.memory;\nfinal class BuildFlags{static final boolean DEBUG=false;}\n' > "$OUTPUT/generated/BuildFlags.java"
CP="$ANDROID_JAR:$MARKDOWN_CP:$OUTPUT/ort/classes.jar"
# Same production sources and fixture stand-ins as build-tests.sh, compiled against the SDK; only the host test and the
# android.util.Log stand-in (the SDK stub throws) are compiled and run ahead of android.jar.
"$JDK/bin/javac" --release 17 -nowarn -cp "$CP" -d "$OUTPUT/classes" "$OUTPUT/generated/org/agentos/memory/R.java" "$OUTPUT/generated/BuildFlags.java" src/org/agentos/memory/{MemoryStore,MemoryResult,MemoryContent,MemoryCopy,Delta,Recall,Embedder,Organizer,Privacy,BrokerClient,MemoryMaintenance,Proactive,FeedCards,FeedCheck,FeedFeedback}.java ../ai/client/org/agentos/ai/AiClient.java tests/fixture/*.java
"$JDK/bin/javac" --release 17 -d "$OUTPUT/fakes" tests/host/android/util/Log.java
"$JDK/bin/javac" --release 17 -cp "$OUTPUT/fakes:$OUTPUT/classes:$CP" -d "$OUTPUT/classes" tests/host/org/agentos/memory/*.java
"$JDK/bin/java" -cp "$OUTPUT/fakes:$OUTPUT/classes:$CP" org.agentos.memory.EmbedderFallbackHostTests
