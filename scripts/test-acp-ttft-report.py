#!/usr/bin/env python3
"""Offline tests for acp-ttft-report.py: a synthetic broker.log, no device access."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("ttft", Path(__file__).with_name("acp-ttft-report.py"))
ttft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ttft)

LOG = """\
11-22 18:55:08.329 broker start pid 21570
11-22 18:55:16.130 acp prewarm (thread/read) ready in 6596ms
11-22 23:33:23.025 broker start pid 8456
11-22 23:33:33.016 acp prewarm (startup) ready in 5924ms
11-22 23:33:49.264 acp prewarm (thread_read) skipped: pool-busy
11-22 23:33:53.966 acp prewarm (onResume) ready in 4991ms
11-22 23:34:06.766 acp prewarm (onResume) skipped: pool-busy
11-22 23:34:10.000 acp prewarm (overlay) failed: java.io.IOException: boom
11-22 23:35:34.877 acp turn 1001 completed pool=warm_hit wait=2 ping=2 init=- claim=160 session=324 config=12 prep=363 firstChunk=2900 firstText=3847 firstTool=- total=4320 (ms)
11-22 23:36:27.901 codex app server tick: java.io.IOException: The assistant service has exited
11-22 23:37:00.000 acp turn 1002 completed pool=cold_start wait=5100 ping=- init=900 claim=20 session=400 config=10 prep=5600 firstChunk=3000 firstText=3500 firstTool=3100 total=12000 (ms)
11-22 23:38:00.000 acp turn 1003 failed pool=- wait=- ping=- init=- claim=- session=- config=- prep=- firstChunk=- firstText=- firstTool=- total=50 (ms)
11-22 23:39:00.000 acp turn 1004 completed pool=waited_prewarm wait=1200 ping=3 init=- claim=30 session=300 config=- prep=1600 firstChunk=2000 firstText=2600 firstTool=- total=6000 (ms)
"""


class ReportTests(unittest.TestCase):
    def test_parses_turn_and_prewarm_lines(self):
        parsed = ttft.parse_log(LOG)
        self.assertEqual([t["id"] for t in parsed["turns"]], ["1001", "1002", "1003", "1004"])
        first = parsed["turns"][0]
        self.assertEqual((first["poolState"], first["warmWaitMs"], first["sessionPrepareMs"], first["totalPrepMs"],
                          first["promptToFirstChunkMs"], first["promptToFirstTextMs"], first["promptToFirstToolMs"],
                          first["turnTotalMs"], first["claimDelayMs"]), ("warm_hit", 2, 324, 363, 2900, 3847, None, 4320, 160))
        self.assertIsNone(parsed["turns"][2]["poolState"])
        outcomes = [(p["reason"], p["outcome"], p["ms"], p["why"]) for p in parsed["prewarms"]]
        self.assertIn(("onResume", "ready", 4991, None), outcomes)
        self.assertIn(("thread_read", "skipped", None, "pool-busy"), outcomes)
        self.assertEqual(outcomes[-1][:2], ("overlay", "failed"))
        self.assertEqual([s["pid"] for s in parsed["starts"]], [21570, 8456])

    def test_summary_rates_and_percentiles(self):
        report = ttft.summarize(ttft.parse_log(LOG), limit=10)
        self.assertEqual(report["turnCount"], 4)
        self.assertEqual(report["poolStates"], {"warm_hit": 1, "cold_start": 1, "-": 1, "waited_prewarm": 1})
        self.assertEqual(report["warmHitRate"], 0.25)
        text = report["stats"]["promptToFirstTextMs"]
        self.assertEqual((text["n"], text["p50"], text["p90"], text["max"]), (3, 3500, 3847, 3847))
        self.assertEqual(report["stats"]["promptToFirstToolMs"]["n"], 1)
        on_resume = report["prewarm"]["onResume"]
        self.assertEqual((on_resume["triggers"], on_resume["ready"], on_resume["skipped"], on_resume["successRate"]), (2, 1, 1, 1.0))
        self.assertEqual(on_resume["skippedWhy"], {"pool-busy": 1})
        self.assertEqual(report["prewarm"]["overlay"]["successRate"], 0.0)
        self.assertIsNone(report["prewarm"]["thread_read"]["successRate"])
        self.assertEqual(report["prewarmTotals"]["successRate"], 0.75)  # 3 ready of 4 attempted (skips do not count)

    def test_limit_and_since_start(self):
        parsed = ttft.parse_log(LOG)
        self.assertEqual([t["id"] for t in ttft.summarize(parsed, limit=2)["turns"]], ["1003", "1004"])
        since = ttft.summarize(parsed, limit=0, since_start=True)
        self.assertNotIn("thread/read", since["prewarm"])
        self.assertEqual(since["turnCount"], 4)

    def test_percentile_nearest_rank(self):
        self.assertIsNone(ttft.percentile([], 0.5))
        self.assertEqual(ttft.percentile([5], 0.9), 5)
        self.assertEqual(ttft.percentile(list(range(1, 11)), 0.5), 5)
        self.assertEqual(ttft.percentile(list(range(1, 11)), 0.9), 9)

    def test_runtime_info_fills_gaps_and_cli_renders(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp, "broker.log")
            log.write_text(LOG, encoding="utf-8")
            info = Path(tmp, "tasks", "1003")
            info.mkdir(parents=True)
            (info / "runtime-info.json").write_text(json.dumps({
                "conversationId": "c-1", "model": "m", "agentProcess": "cold",
                "timings": {"poolState": "cold_start", "warmWaitMs": 4000, "turnTotalMs": 1}}), encoding="utf-8")
            parsed = ttft.read_local([str(log)], str(Path(tmp, "tasks")))
            turn = parsed["turns"][2]
            self.assertEqual((turn["poolState"], turn["warmWaitMs"], turn["turnTotalMs"], turn["conversationId"]),
                             ("cold_start", 4000, 50, "c-1"))  # the log line wins where it has a value
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                ttft.main(["--log", str(log), "--limit", "3"])
            text = out.getvalue()
            for needle in ("ACP turns (last 3, ms)", "1stChunk", "warm hit rate", "promptToFirstTextMs",
                           "prewarm triggers: 6", "onResume", "pool-busy=1"):
                self.assertIn(needle, text)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                ttft.main(["--log", str(log), "--json"])
            self.assertEqual(json.loads(out.getvalue())["turnCount"], 4)

    def test_device_dump_parsing(self):
        dump = "@@ 1001\n{\"model\": \"x\"}\n@@ 1002\nnot json\n"
        self.assertEqual(ttft.parse_info_dump(dump), {"1001": {"model": "x"}})


def turn_line(time, tid, status="completed", pool="warm_hit", first_text="3000", total="5000"):
    return ("%s acp turn %s %s pool=%s wait=1 ping=2 init=- claim=10 session=300 config=12 prep=400 "
            "firstChunk=2000 firstText=%s firstTool=- total=%s (ms)" % (time, tid, status, pool, first_text, total))


def run_cli(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = ttft.main(argv)
    return code, out.getvalue()


class EdgeTests(unittest.TestCase):
    def write(self, tmp, name, data):
        path = Path(tmp, name)
        path.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))
        return str(path)

    def test_turns_across_rotated_logs_oldest_first(self):
        older = "\n".join([
            "11-23 08:00:00.000 broker start pid 100",
            turn_line("11-23 08:01:00.000", "2001"),
            turn_line("11-23 08:02:00.000", "2002", pool="cold_start"),
        ]) + "\n"
        newer = "\n".join([turn_line("11-23 08:03:00.000", "2003"), turn_line("11-23 08:04:00.000", "2004")]) + "\n"
        with tempfile.TemporaryDirectory() as tmp:
            a, b = self.write(tmp, "broker.log.1", older), self.write(tmp, "broker.log", newer)
            parsed = ttft.read_local([a, b], None)
            self.assertEqual([t["id"] for t in parsed["turns"]], ["2001", "2002", "2003", "2004"])
            report = ttft.summarize(parsed, limit=3)
            self.assertEqual([t["id"] for t in report["turns"]], ["2002", "2003", "2004"])  # the window spans both files
            self.assertEqual(report["warmHitRate"], round(2 / 3, 3))
            # .1 without a final newline (a torn last line) still does not swallow the next file's first line.
            self.write(tmp, "broker.log.1", older.rstrip("\n") + "\n11-23 08:02:30.000 acp turn 20")
            parsed = ttft.read_local([a, b], None)
            self.assertEqual([t["id"] for t in parsed["turns"]], ["2001", "2002", "2003", "2004"])

    def test_missing_stages_and_interrupted_turns(self):
        log = "\n".join([
            "11-23 09:00:00.000 broker start pid 1",
            # Failed before the prompt: every stage is "-".
            "11-23 09:00:01.000 acp turn 3001 failed pool=cold_start wait=- ping=- init=- claim=- session=- config=- "
            "prep=- firstChunk=- firstText=- firstTool=- total=12 (ms)",
            # Cut off mid-line (the broker died): no "(ms)", not a turn.
            "11-23 09:00:02.000 acp turn 3002 completed pool=warm_hit wait=1 ping=2 init=- cla",
            # A newer broker that knows one more stage, and one that dropped some: unknown keys are ignored.
            "11-23 09:00:03.000 acp turn 3003 completed pool=warm_hit wait=1 queue=7 total=900 (ms)",
            # Garbage values never crash the parser.
            "11-23 09:00:04.000 acp turn 3004 cancelled pool=- wait=abc session=1.5 total= (ms)",
            "11-23 09:00:05.000 acp prewarm (startup) failed: java.io.IOException: agent closed stdout during initialize",
            "11-23 09:00:06.000 acp prewarm (thread_read) trigger: java.lang.NoSuchFieldError: DISCARD",
            "11-23 09:00:07.000 acp prewarm (onResume) skipped",
            "11-23 09:00:08.000 acp prewarm (overlay) something new",
            "11-23 09:00:09.000 acp prewarm: java.lang.OutOfMemoryError",
            "acp turn 9999 completed pool=warm_hit total=1 (ms)",  # no timestamp: not a broker.log line
        ])
        parsed = ttft.parse_log(log)
        turns = {t["id"]: t for t in parsed["turns"]}
        self.assertEqual(sorted(turns), ["3001", "3003", "3004"])
        self.assertEqual((turns["3001"]["status"], turns["3001"]["poolState"], turns["3001"]["turnTotalMs"], turns["3001"]["totalPrepMs"]),
                         ("failed", "cold_start", 12, None))
        self.assertEqual((turns["3003"]["warmWaitMs"], turns["3003"]["turnTotalMs"], turns["3003"]["sessionPrepareMs"]), (1, 900, None))
        self.assertEqual((turns["3004"]["poolState"], turns["3004"]["warmWaitMs"], turns["3004"]["sessionPrepareMs"], turns["3004"]["turnTotalMs"]),
                         (None, None, None, None))
        outcomes = [(p["reason"], p["outcome"]) for p in parsed["prewarms"]]
        self.assertEqual(outcomes, [("startup", "failed"), ("thread_read", "failed"), ("onResume", "skipped"), ("overlay", "other")])
        report = ttft.summarize(parsed, limit=0)
        self.assertEqual(report["stats"]["promptToFirstTextMs"], {"n": 0, "p50": None, "p90": None, "max": None})
        self.assertEqual(report["stats"]["turnTotalMs"]["n"], 2)
        self.assertEqual(report["prewarm"]["onResume"]["skippedWhy"], {"?": 1})
        self.assertIsNone(report["prewarm"]["overlay"]["successRate"])
        self.assertEqual(report["prewarmTotals"]["successRate"], 0.0)
        text = ttft.render(report)
        self.assertIn("ACP turns (last 3, ms)", text)
        self.assertIn("warm hit rate: 33%", text)

    def test_empty_and_unrelated_logs(self):
        for text in ("", "\n\n", "11-23 09:00:00.000 codex app server tick: nothing\n"):
            parsed = ttft.parse_log(text)
            report = ttft.summarize(parsed, limit=10, since_start=True)
            self.assertEqual((report["turnCount"], report["warmHitRate"], report["prewarmTotals"]["successRate"]), (0, None, None))
            rendered = ttft.render(report)
            self.assertIn("(no acp turn lines)", rendered)
            self.assertIn("(no prewarm lines)", rendered)
            self.assertIn("warm hit rate: -", rendered)
        with tempfile.TemporaryDirectory() as tmp:
            empty = self.write(tmp, "broker.log", "")
            code, out = run_cli(["--log", empty, "--json"])
            self.assertEqual((code, json.loads(out)["turnCount"]), (0, 0))

    def test_non_ascii_and_invalid_utf8(self):
        raw = ("11-23 10:00:00.000 broker start pid 5\n"
               "11-23 10:00:01.000 acp prewarm (打开设置) failed: java.io.IOException: 连接被重置 ✓\n"
               "11-23 10:00:02.000 acp tool label: Wi‑Fi “开” 📶\n").encode("utf-8") + b"\xff\xfe broken bytes\n" + (
               turn_line("11-23 10:00:03.000", "任务-1") + "\n").encode("utf-8")
        with tempfile.TemporaryDirectory() as tmp:
            log = self.write(tmp, "broker.log", raw)
            parsed = ttft.read_local([log], None)
            self.assertEqual([t["id"] for t in parsed["turns"]], ["任务-1"])
            self.assertEqual(parsed["prewarms"][0]["reason"], "打开设置")
            self.assertIn("连接被重置", parsed["prewarms"][0]["why"])
            code, out = run_cli(["--log", log])
            self.assertEqual(code, 0)
            self.assertIn("打开设置", out)
            code, out = run_cli(["--log", log, "--json"])
            self.assertEqual(json.loads(out)["turns"][0]["id"], "任务-1")
            self.assertIn("任务-1", out)  # ensure_ascii=False: readable, not \\u escapes

    def test_since_start_is_positional(self):
        # A year boundary and a boot-time clock (01-01 before network time): string comparison of the
        # stamps would drop the turns after the last start or keep the ones before it.
        log = "\n".join([
            "12-31 23:58:00.000 broker start pid 10",
            turn_line("12-31 23:59:00.000", "4001"),
            "12-31 23:59:30.000 broker start pid 11",
            turn_line("01-01 00:00:10.000", "4002"),
            "01-01 00:00:20.000 acp prewarm (startup) ready in 100ms",
            "01-01 00:01:00.000 broker start pid 12",
            "01-01 00:00:05.000 acp prewarm (startup) ready in 200ms",
            turn_line("01-01 00:00:06.000", "4003"),
        ])
        report = ttft.summarize(ttft.parse_log(log), limit=0, since_start=True)
        self.assertEqual([t["id"] for t in report["turns"]], ["4003"])
        self.assertEqual(report["prewarmTotals"]["readyP50Ms"], 200)
        log = "\n".join(log.splitlines()[:5])  # the last start is the 12-31 23:59:30 one
        report = ttft.summarize(ttft.parse_log(log), limit=0, since_start=True)
        self.assertEqual([t["id"] for t in report["turns"]], ["4002"])
        self.assertEqual(report["prewarmTotals"]["triggers"], 1)

    def test_negative_limit_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = self.write(tmp, "broker.log", "")
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                ttft.main(["--log", log, "--limit", "-1"])

    def test_runtime_info_errors_are_tolerated(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = self.write(tmp, "broker.log", turn_line("11-23 11:00:00.000", "5001", first_text="-") + "\n")
            bad = Path(tmp, "tasks", "5001")
            bad.mkdir(parents=True)
            (bad / "runtime-info.json").write_text("{not json", encoding="utf-8")
            parsed = ttft.read_local([log], str(Path(tmp, "tasks")))
            self.assertIsNone(parsed["turns"][0]["promptToFirstTextMs"])
            (bad / "runtime-info.json").write_text(json.dumps({"timings": {"promptToFirstTextMs": 3333}, "model": "模型"}), encoding="utf-8")
            parsed = ttft.read_local([log], str(Path(tmp, "tasks")))
            self.assertEqual((parsed["turns"][0]["promptToFirstTextMs"], parsed["turns"][0]["model"]), (3333, "模型"))

    def test_device_read_decodes_utf8_and_stays_read_only(self):
        log = ("11-23 12:00:00.000 broker start pid 9\n" + turn_line("11-23 12:00:01.000", "6001") + "\n"
               "11-23 12:00:02.000 acp prewarm (onResume) failed: 连接被重置\n")
        info = "@@ 6001\n" + json.dumps({"model": "gemini-3", "timings": {"promptToFirstToolMs": 2500}}) + "\n"
        calls = []

        def fake_run(argv, **kwargs):
            calls.append((argv, kwargs))
            # What subprocess would hand back for these bytes under the requested decoding.
            data = (log if "broker.log" in argv[-1] else info).encode("utf-8")
            decoded = data.decode(kwargs.get("encoding") or "ascii", kwargs.get("errors") or "strict")
            return subprocess.CompletedProcess(argv, 0, stdout=decoded, stderr="")

        with mock.patch.object(ttft.subprocess, "run", fake_run):
            parsed = ttft.read_device("SERIAL", 10, False)
        self.assertEqual([t["id"] for t in parsed["turns"]], ["6001"])
        self.assertEqual((parsed["turns"][0]["model"], parsed["turns"][0]["promptToFirstToolMs"]), ("gemini-3", 2500))
        self.assertIn("连接被重置", parsed["prewarms"][0]["why"])
        for argv, kwargs in calls:
            self.assertEqual(argv[:4], ["adb", "-s", "SERIAL", "shell"])
            self.assertEqual(kwargs.get("encoding"), "utf-8")
            for verb in ("rm ", "mv ", "> ", "setprop", "kill", "am ", "pm ", "stop", "start "):
                self.assertNotIn(verb, argv[-1].replace("2>/dev/null", ""), "device access stays read-only")


@unittest.skipUnless(os.environ.get("ACP_TTFT_SAMPLE_DIR"), "ACP_TTFT_SAMPLE_DIR: run via system/assistant/test-acp-runtime-host.sh")
class BrokerOutputContractTests(unittest.TestCase):
    """broker.log(.1) written by the broker's own BrokerLog + AcpRuntime.Timings.summary (BrokerLogTest)."""

    def setUp(self):
        self.dir = Path(os.environ["ACP_TTFT_SAMPLE_DIR"])
        for name in ("broker.log.1", "broker.log", "expected.json"):
            self.assertTrue((self.dir / name).is_file(), "missing broker sample %s" % (self.dir / name))
        self.expected = json.loads((self.dir / "expected.json").read_text(encoding="utf-8"))
        self.logs = [str(self.dir / "broker.log.1"), str(self.dir / "broker.log")]

    def test_every_turn_line_parses_with_its_values(self):
        parsed = ttft.read_local(self.logs, None)
        self.assertEqual([t["id"] for t in parsed["turns"]], [t["id"] for t in self.expected["turns"]],
                         "every turn the broker logged, across the rotation, in order")
        for turn, want in zip(parsed["turns"], self.expected["turns"]):
            for key, value in want.items():
                self.assertEqual(turn[key], value, "%s.%s" % (want["id"], key))
            for key in ttft.FIELDS.values():
                self.assertIn(key, turn)
        self.assertEqual([s["pid"] for s in parsed["starts"]], self.expected["starts"])
        reasons = [(p["reason"], p["outcome"]) for p in parsed["prewarms"]]
        self.assertEqual(reasons, [("startup", "ready"), ("onResume", "skipped"), ("overlay", "failed")])

    def test_summary_keys_match_the_parser(self):
        # Each key=value of a broker turn line is one the report knows: a renamed stage fails here.
        text = "\n".join(Path(p).read_text(encoding="utf-8") for p in self.logs)
        lines = [l for l in text.splitlines() if " acp turn " in l]
        self.assertTrue(lines)
        for line in lines:
            keys = [pair.split("=", 1)[0] for pair in line.split(" (ms)")[0].split() if "=" in pair]
            self.assertEqual(keys, ["pool"] + list(ttft.FIELDS.keys()), line)

    def test_report_since_last_start(self):
        report = ttft.summarize(ttft.read_local(self.logs, None), limit=0, since_start=True)
        self.assertEqual(report["turns"][0]["id"], self.expected["sinceStartFirstTurn"])
        code, out = run_cli(["--log"] + self.logs[:1] + ["--log"] + self.logs[1:] + ["--limit", "0", "--json"])
        self.assertEqual((code, json.loads(out)["turnCount"]), (0, len(self.expected["turns"])))


if __name__ == "__main__":
    unittest.main()
