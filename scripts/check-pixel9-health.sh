#!/bin/bash
# Read-only runtime health check of the Pixel 9 after a hot deploy: one command, no screen, no state.
#
#   bash scripts/check-pixel9-health.sh                      # Pixel 9 46170DLAQ0006D, text summary
#   bash scripts/check-pixel9-health.sh --json --skip-ttft   # machine-readable, no TTFT report
#   options: --serial <id>  --adb <path>  --skip-ttft  --json  -h|--help
#
# Checks (PASS / WARN / FAIL):
#   setsid       /system/bin/setsid exists (AcpProcessGroup's process-group reclaim needs it)   WARN
#   agy-pgid     every live agy leads its own process group (pgid == pid)                       WARN
#   codex        no codex process is left running                                               FAIL
#   ime          the default input method is WeType                                             WARN
#   phone-mcp    /data/local/agentos/acp/run/phone-mcp.sock exists                              FAIL
#   runtime-mcp  runtime.json mcpServers holds no demo_* node (only names are ever printed)     FAIL
#   broker-log   broker.log tail since the last broker start: FATAL / OutOfMemoryError (FAIL),
#                "no setsid" (WARN); printed lines are redacted
#   ttft         scripts/acp-ttft-report.py --since-start --limit 5 (informational; WARN on error)
# Exit status: 0 when nothing FAILs (WARNs included), 1 on any FAIL, 2 on bad usage.
#
# Strictly read-only: every adb call is `adb -s <serial> get-state` or `adb -s <serial> shell <cmd>`
# with <cmd> taken from a fixed allowlist of queries (test, ps, settings get, cat, tail). Nothing is
# installed, started, stopped, typed, written or deleted, and the screen is never woken.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
export CHECK_PIXEL9_HEALTH_SCRIPTS="$here"
exec python3 - "$@" <<'PY'
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

DEFAULT_SERIAL = "46170DLAQ0006D"
SETSID = "/system/bin/setsid"
PHONE_MCP_SOCKET = "/data/local/agentos/acp/run/phone-mcp.sock"
RUNTIME_JSON = "/data/user/0/org.agentos.assistant/files/runtime/runtime.json"
BROKER_LOG = "/data/local/agentos/acp/logs/broker.log"
LOG_TAIL_LINES = 400

# The only device commands this script may send (`adb -s <serial> shell <cmd>`); all are queries.
QUERIES = {
    "setsid": "test -x %s && echo present || echo missing" % SETSID,
    "ps": "ps -A -o PID,PGID,NAME",
    "ime": "settings get secure default_input_method",
    "socket": "test -S %s && echo present || echo missing" % PHONE_MCP_SOCKET,
    "runtime": "cat %s" % RUNTIME_JSON,
    "log": "tail -n %d %s" % (LOG_TAIL_LINES, BROKER_LOG),
}
READ_ONLY_VERBS = {"test", "ps", "cat", "tail"}

SECRET_ASSIGN = re.compile(r"(?i)((?:token|key|secret|password|passwd|authorization|bearer|cookie)[\w-]*\"?\s*[:=]\s*\"?)[^\s\",}]+")
SECRET_LONG = re.compile(r"[A-Za-z0-9+_=-]{32,}")


def redact(text):
    return SECRET_LONG.sub("<redacted>", SECRET_ASSIGN.sub(r"\1<redacted>", text))


class Device:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def _run(self, args, timeout=30):
        return subprocess.run([self.adb, "-s", self.serial] + args, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)

    def state(self):
        try:
            result = self._run(["get-state"], timeout=15)
        except (OSError, subprocess.TimeoutExpired) as error:
            return None, str(error)
        return (result.stdout.strip() if result.returncode == 0 else None), result.stderr.strip()

    def query(self, key):
        command = QUERIES[key]
        verb = command.split()[0]
        if verb not in READ_ONLY_VERBS and not command.startswith("settings get "):
            raise AssertionError("refusing non read-only device command: " + command)
        try:
            result = self._run(["shell", command])
        except (OSError, subprocess.TimeoutExpired) as error:
            return None, str(error)
        if result.returncode != 0 and not result.stdout:
            return None, redact(result.stderr.strip() or "exit %d" % result.returncode)
        return result.stdout, None


def processes(ps_text):
    rows = []
    for line in ps_text.splitlines():
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
            rows.append({"pid": int(parts[0]), "pgid": int(parts[1]), "name": parts[2].strip()})
    return rows


def base(name):
    return name.rsplit("/", 1)[-1]


def mcp_server_names(node):
    """Every name under any `mcpServers` (list of {name} or map name -> config), anywhere in runtime.json."""
    names = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "mcpServers":
                if isinstance(value, dict):
                    names.extend(str(k) for k in value)
                elif isinstance(value, list):
                    names.extend(str(item.get("name", "<unnamed>")) for item in value if isinstance(item, dict))
            else:
                names.extend(mcp_server_names(value))
    elif isinstance(node, list):
        for item in node:
            names.extend(mcp_server_names(item))
    return names


def check_all(device):
    checks = []

    def add(check_id, status, detail):
        checks.append({"id": check_id, "status": status, "detail": redact(detail)})

    out, err = device.query("setsid")
    if out is None:
        add("setsid", "WARN", "could not query %s: %s" % (SETSID, err))
    elif out.strip() == "present":
        add("setsid", "PASS", "%s present: agents start as their own process-group leader" % SETSID)
    else:
        add("setsid", "WARN", "%s missing: agent children are reclaimed only with the agent" % SETSID)

    out, err = device.query("ps")
    if out is None:
        add("agy-pgid", "WARN", "ps failed: %s" % err)
        add("codex", "FAIL", "ps failed, codex leftovers unknown: %s" % err)
    else:
        rows = processes(out)
        agy = [r for r in rows if base(r["name"]) == "agy"]
        stray = [r for r in agy if r["pgid"] != r["pid"]]
        if not agy:
            add("agy-pgid", "PASS", "no agy running (pool idle)")
        elif stray:
            add("agy-pgid", "WARN", "agy not isolated in its own group: " +
                ", ".join("pid %d pgid %d" % (r["pid"], r["pgid"]) for r in stray))
        else:
            add("agy-pgid", "PASS", "agy " + ", ".join("pid %d = pgid" % r["pid"] for r in agy))
        codex = [r for r in rows if base(r["name"]).startswith("codex")]
        if codex:
            add("codex", "FAIL", "codex still running: " + ", ".join("pid %d (%s)" % (r["pid"], r["name"]) for r in codex))
        else:
            add("codex", "PASS", "no codex process")

    out, err = device.query("ime")
    ime = (out or "").strip()
    if out is None:
        add("ime", "WARN", "could not read default_input_method: %s" % err)
    elif "wetype" in ime.lower():
        add("ime", "PASS", "default IME %s" % ime)
    else:
        add("ime", "WARN", "default IME is %s, expected WeType (com.tencent.wetype/.plugin.hld.WxHldService)" % (ime or "unset"))

    out, err = device.query("socket")
    if out is None:
        add("phone-mcp", "FAIL", "could not query %s: %s" % (PHONE_MCP_SOCKET, err))
    elif out.strip() == "present":
        add("phone-mcp", "PASS", "%s present" % PHONE_MCP_SOCKET)
    else:
        add("phone-mcp", "FAIL", "%s missing" % PHONE_MCP_SOCKET)

    out, err = device.query("runtime")
    if out is None:
        add("runtime-mcp", "WARN", "could not read runtime.json: %s" % err)
    else:
        try:
            names = mcp_server_names(json.loads(out))
        except ValueError:
            names = None
        if names is None:
            add("runtime-mcp", "FAIL", "runtime.json is not valid JSON")
        else:
            demo = [n for n in names if n.lower().startswith("demo_")]
            if demo:
                add("runtime-mcp", "FAIL", "demo node(s) leaked into runtime.json mcpServers: " + ", ".join(demo))
            else:
                add("runtime-mcp", "PASS", "%d mcpServers, none demo_*: %s" % (len(names), ", ".join(names) or "-"))
        del out  # runtime.json carries tokens: never kept, never printed.

    out, err = device.query("log")
    if out is None:
        add("broker-log", "WARN", "could not read broker.log: %s" % err)
    else:
        lines = out.splitlines()
        starts = [i for i, line in enumerate(lines) if "broker start pid" in line]
        recent = lines[starts[-1]:] if starts else lines
        fatal = [l for l in recent if "FATAL" in l or "OutOfMemoryError" in l]
        nosetsid = [l for l in recent if "no setsid" in l]
        scope = "since last broker start" if starts else "last %d lines" % len(lines)
        sample = lambda found: "; ".join(l.strip()[:160] for l in found[-3:])
        if fatal:
            add("broker-log", "FAIL", "%d FATAL/OutOfMemoryError line(s) %s: %s" % (len(fatal), scope, sample(fatal)))
        elif nosetsid:
            add("broker-log", "WARN", "broker reports no setsid %s: %s" % (scope, sample(nosetsid)))
        else:
            add("broker-log", "PASS", "no FATAL / OutOfMemoryError / no setsid %s" % scope)
    return checks


def ttft(adb, serial, as_json):
    """acp-ttft-report.py runs plain `adb`: put the chosen adb first on its PATH."""
    script = os.path.join(os.environ["CHECK_PIXEL9_HEALTH_SCRIPTS"], "acp-ttft-report.py")
    argv = [sys.executable, script, "--device", serial, "--since-start", "--limit", "5"] + (["--json"] if as_json else [])
    env = dict(os.environ)
    shim = tempfile.mkdtemp(prefix="pixel9-health-adb.")
    try:
        os.symlink(adb, os.path.join(shim, "adb"))
        env["PATH"] = shim + os.pathsep + env.get("PATH", "")
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                env=env, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, str(error)
    finally:
        shutil.rmtree(shim, ignore_errors=True)
    if result.returncode != 0:
        return None, redact((result.stderr.strip().splitlines() or ["exit %d" % result.returncode])[-1])
    return result.stdout, None


def main(argv):
    parser = argparse.ArgumentParser(prog="check-pixel9-health.sh", description="Read-only Pixel 9 runtime health check.")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="adb serial (default %s)" % DEFAULT_SERIAL)
    parser.add_argument("--adb", default="adb", help="adb binary (default: adb on PATH)")
    parser.add_argument("--skip-ttft", action="store_true", help="skip the acp-ttft-report.py summary")
    parser.add_argument("--json", action="store_true", help="print one JSON document")
    args = parser.parse_args(argv)
    adb = shutil.which(args.adb) or args.adb
    adb = os.path.abspath(adb) if os.sep in adb else adb
    device = Device(adb, args.serial)

    state, err = device.state()
    if state != "device":
        checks = [{"id": "device", "status": "FAIL", "detail": redact("adb -s %s get-state: %s" % (args.serial, state or err or "unreachable"))}]
    else:
        checks = check_all(device)

    ttft_report, ttft_text = None, None
    if not args.skip_ttft and state == "device":
        out, err = ttft(adb, args.serial, args.json)
        if out is None:
            checks.append({"id": "ttft", "status": "WARN", "detail": "acp-ttft-report.py failed: %s" % err})
        else:
            checks.append({"id": "ttft", "status": "PASS", "detail": "acp-ttft-report.py --since-start --limit 5"})
            if args.json:
                try:
                    ttft_report = json.loads(out)
                except ValueError:
                    ttft_report = None
            else:
                ttft_text = out.rstrip()

    counts = {s: sum(1 for c in checks if c["status"] == s) for s in ("PASS", "WARN", "FAIL")}
    ok = counts["FAIL"] == 0
    if args.json:
        print(json.dumps({"serial": args.serial, "ok": ok, "summary": counts, "checks": checks, "ttft": ttft_report},
                         indent=2, ensure_ascii=False))
    else:
        print("Pixel health %s (read-only)" % args.serial)
        for c in checks:
            print("%-4s  %-11s  %s" % (c["status"], c["id"], c["detail"]))
        if ttft_text:
            print("\n-- acp-ttft-report.py --since-start --limit 5 --")
            print(ttft_text)
        print("\nsummary: %d PASS, %d WARN, %d FAIL -> %s" % (counts["PASS"], counts["WARN"], counts["FAIL"], "OK" if ok else "FAIL"))
    return 0 if ok else 1


sys.exit(main(sys.argv[1:]))
PY
