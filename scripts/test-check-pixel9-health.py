#!/usr/bin/env python3
"""check-pixel9-health.sh against a fake, read-only `adb` (no phone is ever touched).

The fake adb records every argv and answers from a per-test scenario: `get-state`, and `shell <cmd>`
matched by substring. Every test also asserts the script's adb traffic stayed read-only: each call is
`-s <serial> get-state|shell ...` and no shell command carries a write verb.
"""
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
HEALTH = SCRIPTS / "check-pixel9-health.sh"
SERIAL = "46170DLAQ0006D"
SECRET = "sk-live-TOPSECRETvalue0123456789abcdefABCDEF"

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_ADB_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
scenario = json.load(open(os.environ["FAKE_ADB_SCENARIO"]))
if len(args) >= 3 and args[0] == "-s" and args[2] == "get-state":
    sys.stdout.write(scenario.get("state", "device") + "\n")
    sys.exit(0 if scenario.get("state", "device") == "device" else 1)
if len(args) >= 4 and args[0] == "-s" and args[2] == "shell":
    command = " ".join(args[3:])
    for needle, reply in scenario["shell"]:
        if needle in command:
            sys.stdout.write(reply)
            sys.exit(0)
    sys.stderr.write("fake adb: no reply for %r\n" % command)
    sys.exit(1)
sys.stderr.write("fake adb: unsupported %r\n" % args)
sys.exit(2)
'''

RUNTIME = {
    "acp": {
        "binary": "/data/local/agentos/acp/bin/agy",
        "phoneMcp": {"env": {"TAKU_TOKEN": SECRET}},
        "mcpServers": [
            {"name": "taku_memory", "command": "bin/taku-memory", "args": [], "env": []},
            {"name": "taku_macmini", "command": "bin/taku", "args": ["mcp-stdio", "--token-file", "x"],
             "env": [{"name": "TAKU_API_KEY", "value": SECRET}]},
        ],
    },
}

BROKER_LOG = """11-23 09:00:00.000 broker start pid 4242
11-23 09:00:01.000 acp prewarm (onResume) ready in 4991ms
11-23 09:00:05.000 acp turn t1 completed pool=warm_hit wait=2 firstChunk=900 firstText=1200 total=4320 (ms)
"""


LOG_TAIL = "tail -n 400 /data/local/agentos/acp/logs/broker.log"


def healthy():
    return {
        "state": "device",
        "shell": [
            ["test -x /system/bin/setsid", "present\n"],
            ["ps -A -o PID,PGID,NAME", "  PID  PGID NAME\n    1     0 init\n 4242  4242 app_process\n 5001  5001 agy\n"],
            ["settings get secure default_input_method", "com.tencent.wetype/.plugin.hld.WxHldService\n"],
            ["test -S /data/local/agentos/acp/run/phone-mcp.sock", "present\n"],
            ["cat /data/user/0/org.agentos.assistant/files/runtime/runtime.json", json.dumps(RUNTIME)],
            [LOG_TAIL, BROKER_LOG],
            # acp-ttft-report.py --device: the broker.log read, then the listed turns' runtime-info.json.
            ["cat /data/local/agentos/acp/logs/broker.log.1", BROKER_LOG],
            ["runtime-info.json", ""],
        ],
    }


def replace(scenario, needle, reply):
    """The scenario with the reply of the rule keyed exactly `needle` swapped (the rule must exist)."""
    assert any(n == needle for n, _ in scenario["shell"]), needle
    scenario["shell"] = [[n, reply if n == needle else r] for n, r in scenario["shell"]]
    return scenario


WRITE_VERBS = re.compile(r"\b(install|uninstall|push|pull|reboot|remount|root|am|pm|input|ime|svc|cmd|setprop|"
                         r"rm|rmdir|mv|cp|kill|pkill|killall|stop|start|mount|umount|chmod|chown|chcon|touch|"
                         r"mkdir|dd|tee|sed|wm|monitor)\b")


class HealthTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        tmp = Path(self.tmp.name)
        self.adb = tmp / "fake-adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(self.adb.stat().st_mode | stat.S_IXUSR)
        self.log = tmp / "adb-calls.jsonl"
        self.scenario = tmp / "scenario.json"

    def tearDown(self):
        self.tmp.cleanup()

    def run_health(self, scenario, *extra, serial=SERIAL):
        self.scenario.write_text(json.dumps(scenario))
        self.log.write_text("")
        env = dict(os.environ, FAKE_ADB_LOG=str(self.log), FAKE_ADB_SCENARIO=str(self.scenario))
        args = ["bash", str(HEALTH), "--adb", str(self.adb)] + (["--serial", serial] if serial else []) + list(extra)
        result = subprocess.run(args, capture_output=True, text=True, env=env, timeout=120)
        self.assert_read_only(serial or SERIAL)
        self.assertNotIn(SECRET, result.stdout + result.stderr)
        return result

    def assert_read_only(self, serial):
        calls = [json.loads(line) for line in self.log.read_text().splitlines() if line.strip()]
        self.assertTrue(calls, "no adb call recorded")
        for argv in calls:
            self.assertEqual(argv[:2], ["-s", serial], argv)
            self.assertIn(argv[2], ("get-state", "shell"), argv)
            if argv[2] == "shell":
                command = " ".join(argv[3:])
                self.assertNotRegex(command, WRITE_VERBS, command)
                self.assertNotIn("settings put", command)
                self.assertNotRegex(command.replace("2>/dev/null", ""), r">", command)
        self.calls = calls

    def checks(self, result):
        report = json.loads(result.stdout)
        return report, {c["id"]: c for c in report["checks"]}

    def test_all_green_json(self):
        result = self.run_health(healthy(), "--json")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        report, checks = self.checks(result)
        self.assertEqual(set(report), {"serial", "ok", "summary", "checks", "ttft"})
        self.assertEqual(report["serial"], SERIAL)
        self.assertTrue(report["ok"])
        self.assertEqual(list(checks), ["setsid", "agy-pgid", "codex", "ime", "phone-mcp", "runtime-mcp", "broker-log", "ttft"])
        self.assertEqual({c["status"] for c in checks.values()}, {"PASS"}, checks)
        self.assertEqual(report["summary"], {"PASS": 8, "WARN": 0, "FAIL": 0})
        for check in report["checks"]:
            self.assertEqual(set(check), {"id", "status", "detail"})
        self.assertIn("taku_memory, taku_macmini", checks["runtime-mcp"]["detail"])
        # The TTFT report ran through the same fake adb (read-only, -s serial) and was embedded.
        self.assertEqual(report["ttft"]["turnCount"], 1)
        self.assertEqual(report["ttft"]["turns"][0]["promptToFirstTextMs"], 1200)
        self.assertTrue(any("broker.log.1" in " ".join(c) for c in self.calls))

    def test_all_green_text_skip_ttft(self):
        result = self.run_health(healthy(), "--skip-ttft")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("summary: 7 PASS, 0 WARN, 0 FAIL -> OK", result.stdout)
        self.assertNotIn("ttft", result.stdout)
        self.assertFalse(any("broker.log.1" in " ".join(c) for c in self.calls))

    def test_default_serial_is_pixel9(self):
        result = self.run_health(healthy(), "--skip-ttft", serial=None)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(all(c[:2] == ["-s", SERIAL] for c in self.calls))

    def test_codex_leftover_fails(self):
        scenario = replace(healthy(), "ps -A -o PID,PGID,NAME",
                           "  PID  PGID NAME\n 5001  5001 agy\n 6100  5001 codex\n")
        result = self.run_health(scenario, "--json", "--skip-ttft")
        self.assertEqual(result.returncode, 1)
        report, checks = self.checks(result)
        self.assertFalse(report["ok"])
        self.assertEqual(checks["codex"]["status"], "FAIL")
        self.assertIn("pid 6100", checks["codex"]["detail"])

    def test_demo_node_in_runtime_fails_without_leaking_tokens(self):
        leaked = json.loads(json.dumps(RUNTIME))
        leaked["acp"]["mcpServers"].append({"name": "demo_tesla_model_y", "command": "bin/taku",
                                            "args": ["--token", SECRET], "env": [{"name": "TOKEN", "value": SECRET}]})
        scenario = replace(healthy(), "cat /data/user/0/org.agentos.assistant/files/runtime/runtime.json", json.dumps(leaked))
        for extra in (["--json", "--skip-ttft"], ["--skip-ttft"]):
            result = self.run_health(scenario, *extra)
            self.assertEqual(result.returncode, 1, result.stdout)
            self.assertIn("demo_tesla_model_y", result.stdout)
            self.assertNotIn(SECRET, result.stdout)
            self.assertNotIn("TOPSECRET", result.stdout)
        report, checks = self.checks(self.run_health(scenario, "--json", "--skip-ttft"))
        self.assertEqual(checks["runtime-mcp"]["status"], "FAIL")

    def test_demo_node_as_map_key_fails(self):
        mapped = {"mcpServers": {"taku_memory": {"command": "x"}, "demo_xiaomi_tv": {"env": {"KEY": SECRET}}}}
        scenario = replace(healthy(), "cat /data/user/0/org.agentos.assistant/files/runtime/runtime.json", json.dumps(mapped))
        report, checks = self.checks(self.run_health(scenario, "--json", "--skip-ttft"))
        self.assertEqual(checks["runtime-mcp"]["status"], "FAIL")
        self.assertIn("demo_xiaomi_tv", checks["runtime-mcp"]["detail"])

    def test_non_wetype_ime_warns(self):
        scenario = replace(healthy(), "settings get secure default_input_method", "com.android.inputmethod.latin/.LatinIME\n")
        result = self.run_health(scenario, "--json", "--skip-ttft")
        self.assertEqual(result.returncode, 0, "a WARN must not block")
        report, checks = self.checks(result)
        self.assertEqual(checks["ime"]["status"], "WARN")
        self.assertIn("LatinIME", checks["ime"]["detail"])
        self.assertEqual(report["summary"]["WARN"], 1)

    def test_missing_setsid_and_agy_outside_own_group_warn(self):
        scenario = replace(healthy(), "test -x /system/bin/setsid", "missing\n")
        scenario = replace(scenario, "ps -A -o PID,PGID,NAME", "  PID  PGID NAME\n 4242  4242 app_process\n 5001  4242 agy\n")
        result = self.run_health(scenario, "--skip-ttft")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertRegex(result.stdout, r"WARN\s+setsid\s+/system/bin/setsid missing")
        self.assertRegex(result.stdout, r"WARN\s+agy-pgid\s+agy not isolated in its own group: pid 5001 pgid 4242")
        self.assertIn("summary: 5 PASS, 2 WARN, 0 FAIL -> OK", result.stdout)

    def test_no_agy_running_passes(self):
        scenario = replace(healthy(), "ps -A -o PID,PGID,NAME", "  PID  PGID NAME\n    1     0 init\n")
        report, checks = self.checks(self.run_health(scenario, "--json", "--skip-ttft"))
        self.assertEqual(checks["agy-pgid"]["status"], "PASS")
        self.assertIn("no agy running", checks["agy-pgid"]["detail"])

    def test_missing_socket_fails(self):
        scenario = replace(healthy(), "test -S /data/local/agentos/acp/run/phone-mcp.sock", "missing\n")
        result = self.run_health(scenario, "--json", "--skip-ttft")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.checks(result)[1]["phone-mcp"]["status"], "FAIL")

    def test_broker_log_scan(self):
        old_fatal = "11-23 08:00:00.000 FATAL old crash before restart\n"
        scenario = replace(healthy(), LOG_TAIL, old_fatal + BROKER_LOG)
        self.assertEqual(self.checks(self.run_health(scenario, "--json", "--skip-ttft"))[1]["broker-log"]["status"], "PASS")

        nosetsid = BROKER_LOG + "11-23 09:01:00.000 acp: no setsid in [/system/bin/setsid]; agent child processes are reclaimed only with the agent\n"
        scenario = replace(healthy(), LOG_TAIL, nosetsid)
        self.assertEqual(self.checks(self.run_health(scenario, "--json", "--skip-ttft"))[1]["broker-log"]["status"], "WARN")

        oom = BROKER_LOG + "11-23 09:02:00.000 java.lang.OutOfMemoryError token=%s\n" % SECRET
        scenario = replace(healthy(), LOG_TAIL, oom)
        result = self.run_health(scenario, "--json", "--skip-ttft")
        self.assertEqual(result.returncode, 1)
        check = self.checks(result)[1]["broker-log"]
        self.assertEqual(check["status"], "FAIL")
        self.assertIn("OutOfMemoryError", check["detail"])
        self.assertIn("<redacted>", check["detail"])

    def test_unreachable_device_fails_without_shell(self):
        scenario = healthy()
        scenario["state"] = "offline"
        result = self.run_health(scenario, "--json")
        self.assertEqual(result.returncode, 1)
        report, checks = self.checks(result)
        self.assertEqual(list(checks), ["device"])
        self.assertTrue(all(c[2] == "get-state" for c in self.calls))

    def test_ttft_failure_only_warns(self):
        scenario = healthy()
        scenario["shell"] = [s for s in scenario["shell"] if s[0] != "cat /data/local/agentos/acp/logs/broker.log.1"]
        result = self.run_health(scenario, "--json")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.checks(result)[1]["ttft"]["status"], "WARN")

    def test_script_sources_only_read_only_queries(self):
        """Static guard: the script's device command table holds queries only."""
        text = HEALTH.read_text()
        table = text[text.index("QUERIES = {"):text.index("READ_ONLY_VERBS")]
        for command in re.findall(r'"(\w[^"]*)",?\n', table):
            self.assertNotRegex(command, WRITE_VERBS, command)
        self.assertNotIn("settings put", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
