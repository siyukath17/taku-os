#!/usr/bin/env python3
"""Sum up ACP turn timings and prewarm outcomes from the broker's log.

Two inputs:
  --device <serial>  read-only over `adb -s <serial>`: broker.log(.1) and the runtime-info.json of
                     the listed turns' task directories (nothing is written, nothing restarted).
  --log <file>       an offline broker.log copy (repeatable, oldest first); --runtime-info-dir <dir>
                     optionally adds <dir>/<taskId>/runtime-info.json.

Output: the last N turns' millisecond table, the prewarm reasons with ready / failed / skipped counts
and success rate, and the warm-hit rate with P50 / P90 of each timing. --json prints the same as JSON.
Lines come from AcpRuntime.Timings.summary and AcpAgentPool / AcpReadWarmup's prewarm logging:
  11-22 23:35:34.877 acp turn <id> <status> pool=warm_hit wait=2 ... firstChunk=1 ... total=4320 (ms)
  11-22 23:33:53.966 acp prewarm (onResume) ready in 4991ms | failed... | skipped: pool-busy
"""
import argparse
import json
import math
import re
import shlex
import subprocess
import sys
from collections import Counter, OrderedDict
from pathlib import Path

DEVICE_LOGS = "/data/local/agentos/acp/logs"
DEVICE_TASKS = "/data/user/0/org.agentos.assistant/files/runtime/tasks"

STAMP = r"(?P<time>\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?)\s+"
TURN = re.compile(STAMP + r"acp turn (?P<id>\S+) (?P<status>\S+) (?P<fields>.*?)\s*\(ms\)\s*$")
PREWARM = re.compile(STAMP + r"acp prewarm \((?P<reason>[^)]*)\) (?P<rest>.*)$")
START = re.compile(STAMP + r"broker start pid (?P<pid>\d+)")

# summary key -> runtime-info timings key, in table order.
FIELDS = OrderedDict([
    ("wait", "warmWaitMs"), ("ping", "pingMs"), ("init", "initializeMs"), ("claim", "claimDelayMs"),
    ("session", "sessionPrepareMs"), ("config", "configOptionMs"), ("prep", "totalPrepMs"),
    ("firstChunk", "promptToFirstChunkMs"), ("firstText", "promptToFirstTextMs"),
    ("firstTool", "promptToFirstToolMs"), ("total", "turnTotalMs"),
])
TABLE = ["warmWaitMs", "sessionPrepareMs", "configOptionMs", "totalPrepMs", "promptToFirstChunkMs",
         "promptToFirstTextMs", "turnTotalMs"]
SHORT = {"warmWaitMs": "wait", "sessionPrepareMs": "session", "configOptionMs": "config", "totalPrepMs": "prep",
         "promptToFirstChunkMs": "1stChunk", "promptToFirstTextMs": "1stText", "promptToFirstToolMs": "1stTool",
         "turnTotalMs": "total", "claimDelayMs": "claim"}
STATS = ["warmWaitMs", "claimDelayMs", "sessionPrepareMs", "configOptionMs", "totalPrepMs",
         "promptToFirstChunkMs", "promptToFirstTextMs", "promptToFirstToolMs", "turnTotalMs"]


def number(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def parse_log(text):
    """Turns, prewarm events and broker starts in log order."""
    turns, prewarms, starts = [], [], []
    for line in text.splitlines():
        match = TURN.match(line)
        if match:
            fields = dict(pair.split("=", 1) for pair in match.group("fields").split() if "=" in pair)
            turn = {"time": match.group("time"), "id": match.group("id"), "status": match.group("status"),
                    "poolState": None if fields.get("pool", "-") == "-" else fields["pool"]}
            for short, key in FIELDS.items():
                turn[key] = number(fields.get(short))
            turns.append(turn)
            continue
        match = PREWARM.match(line)
        if match:
            rest = match.group("rest").strip()
            event = {"time": match.group("time"), "reason": match.group("reason"), "outcome": "other", "ms": None, "why": None}
            ready = re.match(r"ready in (\d+)ms", rest)
            if ready:
                event.update(outcome="ready", ms=int(ready.group(1)))
            elif rest.startswith("skipped"):
                event.update(outcome="skipped", why=rest.split(":", 1)[1].strip() if ":" in rest else "")
            elif rest.startswith("failed") or rest.startswith("trigger"):
                event.update(outcome="failed", why=rest)
            prewarms.append(event)
            continue
        match = START.match(line)
        if match:
            starts.append({"time": match.group("time"), "pid": int(match.group("pid"))})
    return {"turns": turns, "prewarms": prewarms, "starts": starts}


def merge_runtime_info(turn, info):
    """runtime-info.json fills what the log line left out and adds conversation / model / process."""
    timings = info.get("timings") or {}
    for key in list(FIELDS.values()) + ["poolState"]:
        if turn.get(key) is None and timings.get(key) is not None:
            turn[key] = timings[key]
    for key in ("conversationId", "model", "effort", "agentProcess", "sessionResumed"):
        if key in info:
            turn[key] = info[key]
    return turn


def percentile(values, fraction):
    """Nearest-rank percentile; None for no values."""
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    return values[max(0, math.ceil(fraction * len(values)) - 1)]


def summarize(parsed, limit, since_start=False):
    turns, prewarms = parsed["turns"], parsed["prewarms"]
    if since_start and parsed["starts"]:
        last = parsed["starts"][-1]["time"]
        turns = [t for t in turns if t["time"] >= last]
        prewarms = [p for p in prewarms if p["time"] >= last]
    turns = turns[-limit:] if limit else turns
    pools = Counter(t.get("poolState") or "-" for t in turns)
    warm = pools.get("warm_hit", 0)
    stats = OrderedDict()
    for key in STATS:
        values = [t.get(key) for t in turns if t.get(key) is not None]
        stats[key] = {"n": len(values), "p50": percentile(values, 0.5), "p90": percentile(values, 0.9),
                      "max": max(values) if values else None}
    reasons = OrderedDict()
    for event in prewarms:
        row = reasons.setdefault(event["reason"], {"triggers": 0, "ready": 0, "failed": 0, "skipped": 0, "other": 0,
                                                   "readyMs": [], "skippedWhy": Counter()})
        row["triggers"] += 1
        row[event["outcome"]] += 1
        if event["ms"] is not None:
            row["readyMs"].append(event["ms"])
        if event["outcome"] == "skipped":
            row["skippedWhy"][event["why"] or "?"] += 1
    prewarm = OrderedDict()
    for reason, row in reasons.items():
        attempted = row["ready"] + row["failed"]
        prewarm[reason] = {
            "triggers": row["triggers"], "ready": row["ready"], "failed": row["failed"], "skipped": row["skipped"],
            "successRate": round(row["ready"] / attempted, 3) if attempted else None,
            "readyP50Ms": percentile(row["readyMs"], 0.5), "readyMaxMs": max(row["readyMs"]) if row["readyMs"] else None,
            "skippedWhy": dict(row["skippedWhy"]),
        }
    ready_all = [e["ms"] for e in prewarms if e["ms"] is not None]
    attempted = sum(1 for e in prewarms if e["outcome"] in ("ready", "failed"))
    return {
        "turns": turns,
        "turnCount": len(turns),
        "statuses": dict(Counter(t["status"] for t in turns)),
        "poolStates": dict(pools),
        "warmHitRate": round(warm / len(turns), 3) if turns else None,
        "stats": stats,
        "prewarm": prewarm,
        "prewarmTotals": {"triggers": len(prewarms), "ready": len(ready_all),
                          "successRate": round(len(ready_all) / attempted, 3) if attempted else None,
                          "readyP50Ms": percentile(ready_all, 0.5), "readyP90Ms": percentile(ready_all, 0.9)},
        "brokerStarts": parsed["starts"],
    }


def cell(value):
    return "-" if value is None else str(value)


def table(headers, rows):
    widths = [max(len(str(h)), *(len(str(r[i])) for r in rows)) if rows else len(str(h)) for i, h in enumerate(headers)]
    line = lambda values: "  ".join(str(v).rjust(w) if i else str(v).ljust(w) for i, (v, w) in enumerate(zip(values, widths)))
    return "\n".join([line(headers), line(["-" * w for w in widths])] + [line(r) for r in rows])


def render(report):
    out = []
    out.append("ACP turns (last %d, ms)" % report["turnCount"])
    headers = ["time", "task", "status", "pool"] + [SHORT[k] for k in TABLE]
    rows = [[t["time"][:-4] if "." in t["time"] else t["time"], t["id"][-8:], t["status"], t.get("poolState") or "-"]
            + [cell(t.get(k)) for k in TABLE] for t in report["turns"]]
    out.append(table(headers, rows) if rows else "(no acp turn lines)")
    out.append("")
    rate = report["warmHitRate"]
    out.append("warm hit rate: %s (%s)  statuses: %s" % (
        "-" if rate is None else "%.0f%%" % (rate * 100),
        ", ".join("%s=%d" % kv for kv in sorted(report["poolStates"].items())) or "-",
        ", ".join("%s=%d" % kv for kv in sorted(report["statuses"].items())) or "-"))
    out.append(table(["timing", "n", "p50", "p90", "max"],
                     [[k, s["n"], cell(s["p50"]), cell(s["p90"]), cell(s["max"])] for k, s in report["stats"].items()]))
    out.append("")
    totals = report["prewarmTotals"]
    out.append("prewarm triggers: %d, ready %d, success %s, ready p50 %s / p90 %s ms" % (
        totals["triggers"], totals["ready"],
        "-" if totals["successRate"] is None else "%.0f%%" % (totals["successRate"] * 100),
        cell(totals["readyP50Ms"]), cell(totals["readyP90Ms"])))
    rows = []
    for reason, p in report["prewarm"].items():
        why = ", ".join("%s=%d" % kv for kv in sorted(p["skippedWhy"].items())) or "-"
        rows.append([reason, p["triggers"], p["ready"], p["failed"], p["skipped"],
                     "-" if p["successRate"] is None else "%.0f%%" % (p["successRate"] * 100),
                     cell(p["readyP50Ms"]), cell(p["readyMaxMs"]), why])
    out.append(table(["reason", "n", "ready", "failed", "skipped", "success", "readyP50", "readyMax", "skipped why"], rows)
               if rows else "(no prewarm lines)")
    if report["brokerStarts"]:
        out.append("")
        out.append("broker starts: " + ", ".join("%s pid %d" % (s["time"], s["pid"]) for s in report["brokerStarts"][-3:]))
    return "\n".join(out)


def adb(serial, command):
    """One read-only `adb shell` command; stdout text."""
    result = subprocess.run(["adb", "-s", serial, "shell", command], capture_output=True, text=True, timeout=60)
    if result.returncode != 0 and not result.stdout:
        raise RuntimeError("adb -s %s shell %s: %s" % (serial, command, result.stderr.strip()))
    return result.stdout


def read_device(serial, limit, since_start):
    text = adb(serial, "cat %s/broker.log.1 2>/dev/null; cat %s/broker.log" % (DEVICE_LOGS, DEVICE_LOGS))
    parsed = parse_log(text)
    wanted = parsed["turns"][-limit:] if limit else parsed["turns"]
    ids = [t["id"] for t in wanted if re.fullmatch(r"[\w.-]+", t["id"])]
    if ids:
        script = "; ".join("f=%s/%s/runtime-info.json; [ -f $f ] && { echo '@@ %s'; cat $f; echo; }"
                           % (DEVICE_TASKS, shlex.quote(i), i) for i in ids) + "; true"
        infos = parse_info_dump(adb(serial, script))
        for turn in parsed["turns"]:
            if turn["id"] in infos:
                merge_runtime_info(turn, infos[turn["id"]])
    return parsed


def parse_info_dump(text):
    infos, current = {}, None
    for line in text.splitlines():
        if line.startswith("@@ "):
            current = line[3:].strip()
        elif current and line.strip():
            try:
                infos[current] = json.loads(line)
            except ValueError:
                pass
            current = None
    return infos


def read_local(paths, info_dir):
    parsed = parse_log("\n".join(Path(p).read_text(encoding="utf-8", errors="replace") for p in paths))
    if info_dir:
        for turn in parsed["turns"]:
            path = Path(info_dir) / turn["id"] / "runtime-info.json"
            if path.is_file():
                try:
                    merge_runtime_info(turn, json.loads(path.read_text(encoding="utf-8")))
                except ValueError:
                    pass
    return parsed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--device", help="adb serial; read-only")
    source.add_argument("--log", action="append", help="local broker.log (repeatable, oldest first)")
    parser.add_argument("--runtime-info-dir", help="with --log: <dir>/<taskId>/runtime-info.json")
    parser.add_argument("--limit", type=int, default=10, help="last N turns (0 = all; default 10)")
    parser.add_argument("--since-start", action="store_true", help="only since the last 'broker start'")
    parser.add_argument("--json", action="store_true", help="print JSON instead of tables")
    args = parser.parse_args(argv)
    parsed = read_device(args.device, args.limit, args.since_start) if args.device else read_local(args.log, args.runtime_info_dir)
    report = summarize(parsed, args.limit, args.since_start)
    print(json.dumps(report, indent=2, ensure_ascii=False) if args.json else render(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
