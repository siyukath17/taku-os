#!/bin/bash
# One-command local regression gate for the haipro/personal line: broker/ACP host suites (with the
# Python TTFT report tests), the agent gateway and builtin skill host suites, the taku-agent-bridge
# Gradle unit tests and the agent app's test / typecheck / lint.
#
# Device-free by construction: an `adb` shim that refuses and records any call is first on PATH and
# ANDROID_SERIAL is cleared, so no step can reach a phone or emulator. The working tree is compared
# before and after: any tracked or untracked (non-ignored) change made by a step fails the gate.
# Logs go to out/verify-haipro-personal/ (gitignored).
#
#   bash scripts/verify-haipro-personal.sh          # everything
#   bash scripts/verify-haipro-personal.sh --fast   # skips the slower Gradle step
set -uo pipefail
cd "$(dirname "$0")/.."
repo=$PWD

fast=0
for arg in "$@"; do
  case "$arg" in
    --fast) fast=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg (use --fast or --help)" >&2; exit 2 ;;
  esac
done

logs="$repo/out/verify-haipro-personal"
rm -rf "$logs" && mkdir -p "$logs/bin"

# No device, ever: any adb call fails and is recorded (checked after every step).
adb_calls="$logs/adb-calls.txt"
: > "$adb_calls"
cat > "$logs/bin/adb" <<EOF
#!/bin/sh
echo "adb \$*" >> "$adb_calls"
echo "verify-haipro-personal: adb is forbidden in this local gate" >&2
exit 97
EOF
chmod +x "$logs/bin/adb"
export PATH="$logs/bin:$PATH"
unset ANDROID_SERIAL

source scripts/android-env.sh
export JAVA_HOME="$JDK" ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"

tree_state() {
  { git status --porcelain=v1 --untracked-files=all; git diff HEAD --binary; } | shasum -a 256
}
tree_before=$(tree_state)

names=() results=() seconds=()
run_step() {
  local name=$1; shift
  local log="$logs/$name.log" start end status
  printf '==> %-28s ' "$name"
  start=$(date +%s)
  ( "$@" ) > "$log" 2>&1
  status=$?
  end=$(date +%s)
  if [ "$status" -eq 0 ] && [ -s "$adb_calls" ]; then
    echo "adb was called: $(cat "$adb_calls")" >> "$log"
    status=97
  fi
  names+=("$name") seconds+=($((end - start)))
  if [ "$status" -eq 0 ]; then results+=(PASS); echo "PASS  $((end - start))s"
  else results+=(FAIL); echo "FAIL  $((end - start))s (exit $status, log: ${log#"$repo"/})"; tail -n 20 "$log" | sed 's/^/    /'
  fi
}

skip_step() {
  names+=("$1") seconds+=(0) results+=(SKIP)
  printf '==> %-28s SKIP  (%s)\n' "$1" "$2"
}

gradle_bridge_tests() {
  [ -x system/agent-app/android/gradlew ] || {
    echo "system/agent-app/android is missing: generate it once with the app's Android prebuild (npm run prebuild:android)."
    return 1
  }
  cd system/agent-app/android && ./gradlew --console=plain :taku-agent-bridge:testDebugUnitTest --rerun
}

total_start=$(date +%s)
run_step acp-runtime-host bash system/assistant/test-acp-runtime-host.sh
run_step agent-gateway-host bash system/assistant/test-agent-gateway-host.sh
run_step builtin-skill-host bash system/assistant/test-builtin-skill-host.sh
if [ "$fast" -eq 1 ]; then skip_step taku-agent-bridge-gradle "--fast"
else run_step taku-agent-bridge-gradle gradle_bridge_tests
fi
run_step agent-app-test bash -c 'cd system/agent-app && npm test'
run_step agent-app-typecheck bash -c 'cd system/agent-app && npm run typecheck'
run_step agent-app-lint bash -c 'cd system/agent-app && npm run lint'

# The gate itself must leave the working tree exactly as it found it.
if [ "$(tree_state)" = "$tree_before" ]; then names+=(worktree-unchanged) results+=(PASS) seconds+=(0)
else
  names+=(worktree-unchanged) results+=(FAIL) seconds+=(0)
  echo "==> worktree-unchanged           FAIL  a step changed the working tree:"
  git status --porcelain=v1 --untracked-files=all | sed 's/^/    /'
fi
total=$(( $(date +%s) - total_start ))

echo
printf '%-30s %-6s %s\n' STEP RESULT SECONDS
failed=0
for i in "${!names[@]}"; do
  printf '%-30s %-6s %s\n' "${names[$i]}" "${results[$i]}" "${seconds[$i]}"
  [ "${results[$i]}" != FAIL ] || failed=1
done
printf '%-30s %-6s %s\n' total "$([ "$failed" -eq 0 ] && echo PASS || echo FAIL)" "$total"
echo "logs: ${logs#"$repo"/}/"
exit "$failed"
