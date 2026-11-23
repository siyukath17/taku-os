#!/bin/bash
# Read-only health snapshot of the Taku OS ecosystem as seen from this Mac: one command, no device.
#
#   bash scripts/check-taku-ecosystem.sh                       # everything
#   bash scripts/check-taku-ecosystem.sh --skip-gh --skip-go   # local git + launchd only (fast, offline)
#   options: --skip-gh  --skip-go  -h|--help
#   env:     TAKU_RUNTIME_DIR (default ../../taku-runtime)  TAKU_MEMORY_DIR (default ../../taku-memory-mcp)
#            TAKU_GH_REPO (default Taku-OS/taku-os)
#
# Sections (each line is PASS / WARN / FAIL / SKIP / INFO):
#   1. this checkout (haipro/personal): branch, ahead/behind the *local* origin/main ref, clean tree
#   2. the standalone Go repos taku-runtime and taku-memory-mcp: short HEAD, clean tree and, unless
#      --skip-go, `go test -race ./...` (FAIL on a failing test or a test run that dirties the tree)
#   3. launchd: every `launchctl list` job whose label contains "taku" -> label, PID, last exit status
#   4. unless --skip-gh: `gh pr list` of open PRs (draft, mergeable state, CI rollup, author, title)
# Exit status: 0 when nothing FAILs (WARNs included), 1 on any FAIL, 2 on bad usage.
#
# Strictly read-only: no `git fetch` (ahead/behind uses whatever origin/main was last fetched, and its
# age is printed), no adb (a shell function refuses it), no launchctl load/kickstart/print (`print`
# would dump job environments, which can hold tokens; only label/PID/status are shown), no gh writes.
# `go test` only writes Go's own build/test cache outside the repos.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)

skip_gh=0 skip_go=0
for arg in "$@"; do
  case "$arg" in
    --skip-gh) skip_gh=1 ;;
    --skip-go) skip_go=1 ;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg (use --skip-gh, --skip-go or --help)" >&2; exit 2 ;;
  esac
done

adb() { echo "check-taku-ecosystem: adb is forbidden here" >&2; return 97; }

workspace=$(cd "$repo/../.." 2>/dev/null && pwd)
runtime_dir=${TAKU_RUNTIME_DIR:-$workspace/taku-runtime}
memory_dir=${TAKU_MEMORY_DIR:-$workspace/taku-memory-mcp}
gh_repo=${TAKU_GH_REPO:-Taku-OS/taku-os}

fails=0 warns=0
report() {  # report LEVEL name message
  printf '  %-5s %-24s %s\n' "$1" "$2" "$3"
  case "$1" in FAIL) fails=$((fails + 1)) ;; WARN) warns=$((warns + 1)) ;; esac
}
dirty_count() { git -C "$1" status --porcelain=v1 --untracked-files=normal 2>/dev/null | wc -l | tr -d ' '; }

# 1. this checkout ------------------------------------------------------------------------------
echo "== haipro/personal ($repo)"
branch=$(git -C "$repo" rev-parse --abbrev-ref HEAD)
head=$(git -C "$repo" rev-parse --short HEAD)
[ "$branch" = haipro/personal ] && report PASS branch "$branch @ $head" || report WARN branch "$branch @ $head (expected haipro/personal)"
if git -C "$repo" rev-parse -q --verify origin/main >/dev/null; then
  read -r behind ahead < <(git -C "$repo" rev-list --left-right --count origin/main...HEAD)
  fetched=$(stat -f %Sm -t '%Y-%m-%d %H:%M' "$(git -C "$repo" rev-parse --git-common-dir)/FETCH_HEAD" 2>/dev/null || echo unknown)
  msg="ahead $ahead / behind $behind origin/main (origin/main $(git -C "$repo" rev-parse --short origin/main), last fetch $fetched)"
  [ "$behind" -eq 0 ] && report PASS vs-origin-main "$msg" || report WARN vs-origin-main "$msg"
else
  report WARN vs-origin-main "no local origin/main ref (run git fetch yourself; this check never fetches)"
fi
if upstream=$(git -C "$repo" rev-parse -q --abbrev-ref '@{upstream}' 2>/dev/null); then
  read -r ub ua < <(git -C "$repo" rev-list --left-right --count "$upstream...HEAD")
  [ "$ua" -eq 0 ] && [ "$ub" -eq 0 ] && report PASS upstream "in sync with $upstream" || report WARN upstream "ahead $ua / behind $ub $upstream"
fi
n=$(dirty_count "$repo")
[ "$n" -eq 0 ] && report PASS worktree clean || report WARN worktree "$n uncommitted/untracked path(s)"

# 2. standalone Go repos ------------------------------------------------------------------------
for dir in "$runtime_dir" "$memory_dir"; do
  name=$(basename "$dir")
  echo "== $name ($dir)"
  if ! git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then report FAIL "$name" "not a git checkout"; continue; fi
  report INFO head "$(git -C "$dir" rev-parse --abbrev-ref HEAD) @ $(git -C "$dir" rev-parse --short HEAD)"
  before=$(git -C "$dir" status --porcelain=v1 --untracked-files=normal)
  n=$(dirty_count "$dir")
  [ "$n" -eq 0 ] && report PASS worktree clean || report WARN worktree "$n uncommitted/untracked path(s)"
  if [ "$skip_go" -eq 1 ]; then report SKIP go-test-race "--skip-go"; continue; fi
  if ! command -v go >/dev/null; then report FAIL go-test-race "go not on PATH"; continue; fi
  start=$(date +%s)
  out=$(cd "$dir" && go test -race ./... 2>&1); status=$?
  secs=$(( $(date +%s) - start ))
  pkgs=$(printf '%s\n' "$out" | grep -c '^ok ')
  if [ "$status" -eq 0 ]; then report PASS go-test-race "$pkgs package(s) ok in ${secs}s"
  else
    report FAIL go-test-race "exit $status after ${secs}s"
    printf '%s\n' "$out" | grep -E '^(FAIL|---|panic|WARNING: DATA RACE)' | head -n 20 | sed 's/^/        /'
  fi
  [ "$(git -C "$dir" status --porcelain=v1 --untracked-files=normal)" = "$before" ] || report FAIL go-test-tree "go test changed the working tree"
done

# 3. launchd -----------------------------------------------------------------------------------
echo "== launchd (label contains taku)"
if ! command -v launchctl >/dev/null; then report SKIP launchctl "not macOS"
else
  jobs=$(launchctl list 2>/dev/null | awk 'NR > 1 && tolower($3) ~ /taku/ { print $1, $2, $3 }')
  if [ -z "$jobs" ]; then report WARN launchctl "no taku job loaded in this user's launchd domain"
  else
    while read -r pid status label; do
      if [ "$pid" != - ]; then report PASS "$label" "running (pid $pid)"
      elif [ "$status" = 0 ]; then report INFO "$label" "loaded, not running (last exit 0; on-demand/periodic?)"
      else report WARN "$label" "not running (last exit $status)"
      fi
    done <<< "$jobs"
  fi
fi

# 4. open PRs ----------------------------------------------------------------------------------
echo "== open PRs ($gh_repo)"
if [ "$skip_gh" -eq 1 ]; then report SKIP gh "--skip-gh"
elif ! command -v gh >/dev/null; then report WARN gh "gh not on PATH"
elif ! prs=$(gh pr list -R "$gh_repo" --state open --limit 100 \
    --json number,isDraft,mergeable,mergeStateStatus,author,title,statusCheckRollup \
    --jq '.[] | [ "#\(.number)", (if .isDraft then "draft" else "ready" end),
                  "\(.mergeable)/\(.mergeStateStatus)",
                  ([.statusCheckRollup[]? | (.conclusion // .state // .status)] | group_by(.)
                     | map("\(.[0])=\(length)") | join(",") | if . == "" then "no-checks" else . end),
                  .author.login, (.title | .[0:70]) ] | @tsv' 2>&1); then
  report WARN gh "gh pr list failed: $(printf '%s' "$prs" | head -n 1)"
elif [ -z "$prs" ]; then report INFO gh "no open PRs"
else
  report INFO gh "$(printf '%s\n' "$prs" | wc -l | tr -d ' ') open PR(s)"
  while IFS=$'\t' read -r num draft merge ci author title; do
    level=INFO
    case "$merge$ci" in *CONFLICTING*|*DIRTY*|*FAILURE*|*ERROR*|*CANCELLED*|*TIMED_OUT*) level=WARN ;; esac
    printf '  %-5s %-5s %-5s %-22s %-24s %-16s %s\n' "$level" "$num" "$draft" "$merge" "$ci" "$author" "$title"
    [ "$level" = WARN ] && warns=$((warns + 1))
  done <<< "$prs"
fi

echo
echo "summary: $fails FAIL, $warns WARN"
[ "$fails" -eq 0 ]
