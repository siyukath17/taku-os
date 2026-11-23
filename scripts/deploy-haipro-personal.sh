#!/bin/bash
# One-command hot deploy of the haipro/personal line to the Pixel 9:
#   verify gate -> build -> targeted `adb install -r` -> read-only health check.
#
#   bash scripts/deploy-haipro-personal.sh --dry-run        # print the plan, run the static checks only
#   bash scripts/deploy-haipro-personal.sh                  # full gate, TakuAgent.apk, health check
#   bash scripts/deploy-haipro-personal.sh --fast-verify --with-memory
#
# Options:
#   --serial <id>          target device (default: Pixel 9 46170DLAQ0006D). An empty serial is always
#                          refused; any other serial (Redmi 5174f591 included) needs --allow-other-serial
#   --allow-other-serial   accept a --serial other than the Pixel 9
#   --allow-branch         deploy from a branch other than haipro/personal (main included)
#   --dry-run              print the plan and run the static checks: no verify, no build, no adb call
#   --skip-verify          skip scripts/verify-haipro-personal.sh
#   --fast-verify          run scripts/verify-haipro-personal.sh --fast
#   --with-memory          also build and `install -r` system/memory/out/TakuMemory.apk
#   --skip-health          skip scripts/check-pixel9-health.sh after the install
#   --adb <path>           adb binary (default: adb on PATH, then $ANDROID_HOME/platform-tools/adb)
#   -h|--help
#
# Exit status: 0 deployed and healthy (or dry run clean); 2 bad usage or a guard refused (branch,
# serial, missing prerequisite); 3 verify gate failed; 4 build failed or produced no APK; 5 device
# not in `device` state (nothing installed); 6 `adb install -r` failed; 7 installed but the health
# check reported a FAIL.
#
# Device contract: every adb call is `adb -s <serial> get-state` or `adb -s <serial> install -r <apk>`
# (the health check adds only its own read-only `get-state` / `shell` queries). Never uninstall (app
# data survives), never touch the input method, settings, activities or the screen. ANDROID_SERIAL
# is cleared so nothing can fall back to an implicit device.
#
# Test hooks (scripts/test-deploy-haipro-personal.py; not for normal use), all environment variables:
#   TAKU_DEPLOY_VERIFY_SCRIPT   replaces scripts/verify-haipro-personal.sh
#   TAKU_DEPLOY_HEALTH_SCRIPT   replaces scripts/check-pixel9-health.sh
#   TAKU_DEPLOY_AGENT_BUILD / TAKU_DEPLOY_MEMORY_BUILD   build commands (run with bash -c)
#   TAKU_DEPLOY_AGENT_APK   / TAKU_DEPLOY_MEMORY_APK     expected build outputs
set -uo pipefail
cd "$(dirname "$0")/.."
repo=$PWD
unset ANDROID_SERIAL

PIXEL9=46170DLAQ0006D
REDMI=5174f591
BRANCH=haipro/personal

serial=$PIXEL9 serial_given=0 allow_other_serial=0 allow_branch=0 dry_run=0
verify_mode=full with_memory=0 skip_health=0 adb_arg=""

die() { local code=$1; shift; echo "deploy: $*" >&2; exit "$code"; }
usage() { sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial) [ "$#" -ge 2 ] || die 2 "--serial needs a value"; serial=$2 serial_given=1; shift 2 ;;
    --serial=*) serial=${1#--serial=} serial_given=1; shift ;;
    --allow-other-serial) allow_other_serial=1; shift ;;
    --allow-branch) allow_branch=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    --skip-verify)
      [ "$verify_mode" != fast ] || die 2 "--skip-verify and --fast-verify are mutually exclusive"
      verify_mode=skip; shift ;;
    --fast-verify)
      [ "$verify_mode" != skip ] || die 2 "--skip-verify and --fast-verify are mutually exclusive"
      verify_mode=fast; shift ;;
    --with-memory) with_memory=1; shift ;;
    --skip-health) skip_health=1; shift ;;
    --adb) [ "$#" -ge 2 ] || die 2 "--adb needs a value"; adb_arg=$2; shift 2 ;;
    --adb=*) adb_arg=${1#--adb=}; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die 2 "unknown argument: $1 (see --help)" ;;
  esac
done

verify_script=${TAKU_DEPLOY_VERIFY_SCRIPT:-$repo/scripts/verify-haipro-personal.sh}
health_script=${TAKU_DEPLOY_HEALTH_SCRIPT:-$repo/scripts/check-pixel9-health.sh}
agent_build=${TAKU_DEPLOY_AGENT_BUILD:-bash system/agent-app/scripts/build-android.sh}
agent_apk=${TAKU_DEPLOY_AGENT_APK:-$repo/system/agent-app/out/TakuAgent.apk}
memory_build=${TAKU_DEPLOY_MEMORY_BUILD:-bash system/memory/build.sh}
memory_apk=${TAKU_DEPLOY_MEMORY_APK:-$repo/system/memory/out/TakuMemory.apk}

# ---- Guards (also run by --dry-run) ---------------------------------------------------------------
# Serial: an explicit target is mandatory; only the Pixel 9 is accepted without an override.
[ -n "$serial" ] || die 2 "empty --serial refused: adb would pick an arbitrary device"
case "$serial" in *[!A-Za-z0-9._:-]*) die 2 "refusing malformed serial: $serial" ;; esac
if [ "$serial" != "$PIXEL9" ]; then
  if [ "$allow_other_serial" -ne 1 ]; then
    if [ "$serial" = "$REDMI" ]; then
      die 2 "refusing Redmi $REDMI: it is not a Taku OS device (pass --allow-other-serial to force)"
    fi
    die 2 "refusing serial $serial: only the Pixel 9 $PIXEL9 is a default target (pass --allow-other-serial)"
  fi
  echo "deploy: WARNING target $serial is not the Pixel 9 ($PIXEL9); proceeding on --allow-other-serial" >&2
fi

branch=$(git -C "$repo" symbolic-ref --quiet --short HEAD 2>/dev/null) || branch="(detached HEAD)"
if [ "$branch" != "$BRANCH" ]; then
  [ "$allow_branch" -eq 1 ] || die 2 "refusing to deploy from branch '$branch': expected $BRANCH (pass --allow-branch)"
  echo "deploy: WARNING deploying from '$branch' instead of $BRANCH on --allow-branch" >&2
fi
commit=$(git -C "$repo" rev-parse --short HEAD 2>/dev/null || echo unknown)
dirty=""
[ -z "$(git -C "$repo" status --porcelain=v1 --untracked-files=no 2>/dev/null)" ] || dirty=" (+ uncommitted changes)"

if [ -z "$adb_arg" ]; then
  adb=$(command -v adb 2>/dev/null || true)
  [ -n "$adb" ] || { sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}; adb=$sdk/platform-tools/adb; }
else
  adb=$adb_arg
  case "$adb" in */*) ;; *) adb=$(command -v "$adb" 2>/dev/null || echo "$adb") ;; esac
fi
case "$adb" in /*) ;; */*) adb=$repo/$adb ;; esac  # the health check runs with its own cwd

missing=()
[ -x "$adb" ] || missing+=("adb not executable: $adb")
[ "$verify_mode" = skip ] || [ -f "$verify_script" ] || missing+=("verify script missing: $verify_script")
[ "$skip_health" -eq 1 ] || [ -f "$health_script" ] || missing+=("health script missing: $health_script")
[ -n "${TAKU_DEPLOY_AGENT_BUILD:-}" ] || [ -f system/agent-app/scripts/build-android.sh ] || missing+=("agent build script missing")
[ "$with_memory" -eq 0 ] || [ -n "${TAKU_DEPLOY_MEMORY_BUILD:-}" ] || [ -f system/memory/build.sh ] || missing+=("memory build script missing")

apks=("$agent_apk"); builds=("$agent_build"); labels=(TakuAgent)
if [ "$with_memory" -eq 1 ]; then apks+=("$memory_apk"); builds+=("$memory_build"); labels+=(TakuMemory); fi

apk_info() {  # <path> -> "<sha256>  <bytes> bytes"
  printf '%s  %s bytes' "$(shasum -a 256 "$1" | cut -d' ' -f1)" "$(wc -c < "$1" | tr -d ' ')"
}

case "$verify_mode" in
  full) verify_desc="bash ${verify_script#"$repo"/}" ;;
  fast) verify_desc="bash ${verify_script#"$repo"/} --fast" ;;
  skip) verify_desc="skipped (--skip-verify)" ;;
esac
echo "deploy plan ($( [ "$dry_run" -eq 1 ] && echo "dry run" || echo live ))"
echo "  branch   $branch @ $commit$dirty"
echo "  serial   $serial"
echo "  adb      $adb"
echo "  1 verify $verify_desc"
for i in "${!apks[@]}"; do
  echo "  2 build  ${labels[$i]}: ${builds[$i]} -> ${apks[$i]#"$repo"/}"
done
echo "  3 state  $adb -s $serial get-state   (must be 'device')"
for i in "${!apks[@]}"; do
  echo "  4 install $adb -s $serial install -r ${apks[$i]#"$repo"/}"
done
if [ "$skip_health" -eq 1 ]; then echo "  5 health skipped (--skip-health)"
else echo "  5 health bash ${health_script#"$repo"/} --serial $serial --adb $adb"
fi

if [ "${#missing[@]}" -gt 0 ]; then
  for m in "${missing[@]}"; do echo "deploy: missing prerequisite: $m" >&2; done
  exit 2
fi

if [ "$dry_run" -eq 1 ]; then
  for i in "${!apks[@]}"; do
    if [ -f "${apks[$i]}" ]; then echo "  current ${labels[$i]}: $(apk_info "${apks[$i]}") (will be rebuilt)"
    else echo "  current ${labels[$i]}: not built yet"
    fi
  done
  echo "dry run: static checks passed; nothing verified, built or installed."
  exit 0
fi

step() { echo; echo "==> $*"; }

# ---- 1. Verify gate --------------------------------------------------------------------------------
if [ "$verify_mode" != skip ]; then
  step "verify: $verify_desc"
  if [ "$verify_mode" = fast ]; then bash "$verify_script" --fast; else bash "$verify_script"; fi
  status=$?
  [ "$status" -eq 0 ] || die 3 "verify gate failed (exit $status): nothing built or installed"
fi

# ---- 2. Build ----------------------------------------------------------------------------------------
for i in "${!apks[@]}"; do
  step "build ${labels[$i]}: ${builds[$i]}"
  before=""
  [ ! -f "${apks[$i]}" ] || before=$(apk_info "${apks[$i]}")
  # Remove a stale artifact first so a build that "succeeds" without output cannot ship the old APK.
  rm -f "${apks[$i]}"
  bash -c "${builds[$i]}"
  status=$?
  [ "$status" -eq 0 ] || die 4 "${labels[$i]} build failed (exit $status): nothing installed"
  [ -s "${apks[$i]}" ] || die 4 "${labels[$i]} build produced no APK at ${apks[$i]}: nothing installed"
  info=$(apk_info "${apks[$i]}")
  echo "built ${labels[$i]} ${apks[$i]#"$repo"/}: $info${before:+ (previous: $before)}"
done

# ---- 3. Device state ---------------------------------------------------------------------------------
step "device: $adb -s $serial get-state"
state=$("$adb" -s "$serial" get-state 2>&1 </dev/null)
status=$?
state=$(printf '%s' "$state" | tr -d '\r' | tail -n 1)
[ "$status" -eq 0 ] && [ "$state" = device ] || die 5 "device $serial is not ready (state: ${state:-none}, exit $status): nothing installed"
echo "device $serial: $state"

# ---- 4. Install (replace, keep data) ------------------------------------------------------------------
for i in "${!apks[@]}"; do
  step "install ${labels[$i]}: $adb -s $serial install -r ${apks[$i]#"$repo"/}"
  out=$("$adb" -s "$serial" install -r "${apks[$i]}" 2>&1 </dev/null)
  status=$?
  printf '%s\n' "$out"
  if [ "$status" -ne 0 ] || ! printf '%s' "$out" | grep -q '^Success'; then
    die 6 "${labels[$i]} install failed (exit $status)"
  fi
done

# ---- 5. Read-only health check ------------------------------------------------------------------------
if [ "$skip_health" -eq 1 ]; then
  echo; echo "deployed ${labels[*]} to $serial from $branch @ $commit; health check skipped (--skip-health)."
  exit 0
fi
step "health: bash ${health_script#"$repo"/} --serial $serial --adb $adb"
bash "$health_script" --serial "$serial" --adb "$adb"
status=$?
[ "$status" -eq 0 ] || die 7 "installed ${labels[*]} on $serial but the health check reported FAIL (exit $status)"
echo; echo "deployed ${labels[*]} to $serial from $branch @ $commit; health check OK."
