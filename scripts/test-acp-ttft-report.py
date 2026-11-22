#!/usr/bin/env python3
"""Offline tests for acp-ttft-report.py: a synthetic broker.log, no device access."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

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


if __name__ == "__main__":
    unittest.main()
