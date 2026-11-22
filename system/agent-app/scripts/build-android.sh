#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../scripts/android-env.sh
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export JAVA_HOME="$JDK"
node scripts/export-reply-contract.ts
node scripts/build-voice-effects.cjs

node scripts/patch-enriched-markdown.cjs
node scripts/patch-dev-menu.cjs
CI=1 EXPO_NO_TELEMETRY=1 node node_modules/expo/bin/cli prebuild --platform android --no-install
printf 'sdk.dir=%s\n' "$SDK" > android/local.properties
(cd android && ./gradlew :app:assembleRelease -PreactNativeArchitectures="${TAKU_AGENT_ARCHITECTURES:-arm64-v8a}" --console=plain)

mkdir -p out
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
"$TOOLS/zipalign" -P 16 -f 4 android/app/build/outputs/apk/release/app-release.apk out/agent-aligned.apk
"$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out out/TakuAgent.apk out/agent-aligned.apk
"$TOOLS/apksigner" verify out/TakuAgent.apk
shasum -a 256 out/TakuAgent.apk
