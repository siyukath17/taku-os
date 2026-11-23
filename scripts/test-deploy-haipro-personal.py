#!/usr/bin/env python3
"""deploy-haipro-personal.sh against a fake adb, fake builds, a fake verify gate and a fake git branch.

No phone is touched and the working tree is never written: every artifact lives in a temp dir. One
JSONL log records, in order, every fake adb argv plus marker rows for the verify gate ("VERIFY"),
each build ("BUILD") and the health check ("HEALTH"). The health check that runs is the real
scripts/check-pixel9-health.sh (wrapped only to log its argv), answering from the same fake adb.
"""
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
DEPLOY = SCRIPTS / "deploy-haipro-personal.sh"
HEALTH = SCRIPTS / "check-pixel9-health.sh"
PIXEL9 = "46170DLAQ0006D"
REDMI = "5174f591"

_spec = importlib.util.spec_from_file_location("health_fixtures", SCRIPTS / "test-check-pixel9-health.py")
health_fixtures = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(health_fixtures)

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
scenario = json.load(open(os.environ["FAKE_ADB_SCENARIO"]))
if len(args) >= 3 and args[0] == "-s" and args[2] == "get-state":
    state = scenario.get("state", "device")
    if state == "device":
        print("device"); sys.exit(0)
    sys.stderr.write("error: device %s\n" % state); sys.exit(1)
if len(args) >= 5 and args[0] == "-s" and args[2] == "install":
    if scenario.get("install_fail"):
        print("Performing Streamed Install"); print("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"); sys.exit(1)
    print("Performing Streamed Install"); print("Success"); sys.exit(0)
if len(args) >= 4 and args[0] == "-s" and args[2] == "shell":
    command = " ".join(args[3:])
    for needle, reply in scenario["shell"]:
        if needle in command:
            sys.stdout.write(reply); sys.exit(0)
    sys.stderr.write("fake adb: no reply for %r\n" % command); sys.exit(1)
sys.stderr.write("fake adb: unsupported %r\n" % args); sys.exit(2)
'''

# Logs a marker row, then behaves per mode. argv[1] = marker kind.
FAKE_STEP = r'''#!/usr/bin/env python3
import json, os, sys
kind, rest = sys.argv[1], sys.argv[2:]
with open(os.environ["FAKE_LOG"], "a") as log:
    log.write(json.dumps([kind] + rest) + "\n")
if kind == "VERIFY":
    sys.exit(int(os.environ.get("FAKE_VERIFY_EXIT", "0")))
if kind == "BUILD":
    label, apk = rest
    if os.environ.get("FAKE_BUILD_NO_OUTPUT") == label:
        sys.exit(0)
    if os.environ.get("FAKE_BUILD_FAIL") == label:
        sys.exit(1)
    with open(apk, "wb") as f:
        f.write(b"PK\x03\x04 fake " + label.encode() * 64)
    sys.exit(0)
'''

FAKE_GIT = r'''#!/bin/sh
# Only the branch question is faked; everything else is the real (read-only) git.
for a in "$@"; do
  if [ "$a" = symbolic-ref ]; then
    [ "$FAKE_GIT_BRANCH" = DETACHED ] && exit 1
    echo "$FAKE_GIT_BRANCH"; exit 0
  fi
done
exec "$REAL_GIT" "$@"
'''

FORBIDDEN = re.compile(r"\b(uninstall|am|pm|input|ime|settings put|svc|cmd|wm|reboot|push|rm|kill|stop|start|setprop)\b")


def make_exec(path, text):
    path.write_text(text)
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return path


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        tmp = Path(self.tmp.name)
        self.log = tmp / "calls.jsonl"
        self.scenario = tmp / "scenario.json"
        self.adb = make_exec(tmp / "fake-adb", FAKE_ADB)
        step = make_exec(tmp / "fake-step", FAKE_STEP)
        bindir = tmp / "bin"
        bindir.mkdir()
        make_exec(bindir / "git", FAKE_GIT)
        self.verify = make_exec(tmp / "fake-verify.sh", '#!/bin/sh\nexec "%s" VERIFY "$@"\n' % step)
        self.health = make_exec(tmp / "health-wrapper.sh",
                                '#!/bin/sh\n"%s" HEALTH "$@"\nexec bash "%s" "$@"\n' % (step, HEALTH))
        self.agent_apk = tmp / "out" / "TakuAgent.apk"
        self.memory_apk = tmp / "out" / "TakuMemory.apk"
        self.agent_apk.parent.mkdir()
        self.env = dict(
            os.environ,
            PATH="%s%s%s" % (bindir, os.pathsep, os.environ.get("PATH", "")),
            REAL_GIT=shutil.which("git"),
            FAKE_GIT_BRANCH="haipro/personal",
            FAKE_LOG=str(self.log),
            FAKE_ADB_SCENARIO=str(self.scenario),
            ANDROID_SERIAL="SHOULD-NEVER-BE-USED",
            TAKU_DEPLOY_VERIFY_SCRIPT=str(self.verify),
            TAKU_DEPLOY_HEALTH_SCRIPT=str(self.health),
            TAKU_DEPLOY_AGENT_BUILD='"%s" BUILD agent "%s"' % (step, self.agent_apk),
            TAKU_DEPLOY_AGENT_APK=str(self.agent_apk),
            TAKU_DEPLOY_MEMORY_BUILD='"%s" BUILD memory "%s"' % (step, self.memory_apk),
            TAKU_DEPLOY_MEMORY_APK=str(self.memory_apk),
        )
        for key in ("FAKE_VERIFY_EXIT", "FAKE_BUILD_NO_OUTPUT", "FAKE_BUILD_FAIL"):
            self.env.pop(key, None)

    def tearDown(self):
        self.tmp.cleanup()

    def deploy(self, *args, scenario=None, adb=True, **env):
        self.scenario.write_text(json.dumps(scenario or health_fixtures.healthy()))
        self.log.write_text("")
        argv = ["bash", str(DEPLOY)] + (["--adb", str(self.adb)] if adb else []) + list(args)
        result = subprocess.run(argv, capture_output=True, text=True, env=dict(self.env, **env), timeout=180,
                                stdin=subprocess.DEVNULL)
        self.calls = [json.loads(line) for line in self.log.read_text().splitlines() if line.strip()]
        self.output = result.stdout + result.stderr
        return result

    # -- helpers -------------------------------------------------------------------------------------
    def adb_calls(self):
        return [c for c in self.calls if c and c[0] == "-s"]

    def kinds(self):
        """The call sequence condensed: VERIFY/BUILD/HEALTH markers, adb get-state/install; health's shell queries dropped."""
        seq = []
        for c in self.calls:
            if c[0] == "VERIFY":
                seq.append(("VERIFY",) + tuple(c[1:]))
            elif c[0] == "BUILD":
                seq.append(("BUILD", c[1]))
            elif c[0] == "HEALTH":
                seq.append(("HEALTH",) + tuple(c[1:]))
            elif c[0] == "-s" and c[2] == "install":
                seq.append(("install", c[3], Path(c[4]).name))
            elif c[0] == "-s" and c[2] == "get-state":
                seq.append(("get-state",))
        return seq

    def assert_device_contract(self, serial=PIXEL9):
        for argv in self.adb_calls():
            self.assertEqual(argv[:2], ["-s", serial], argv)
            self.assertIn(argv[2], ("get-state", "install", "shell"), argv)
            if argv[2] == "install":
                self.assertEqual(argv[3], "-r", argv)
                self.assertEqual(len(argv), 5, argv)
            if argv[2] == "shell":
                self.assertNotRegex(" ".join(argv[3:]), FORBIDDEN, argv)
        flat = " ".join(" ".join(c) for c in self.adb_calls())
        for word in ("uninstall", "settings put", " am ", " input ", "SHOULD-NEVER-BE-USED"):
            self.assertNotIn(word, " %s " % flat)

    # -- dry run -------------------------------------------------------------------------------------
    def test_dry_run_runs_nothing(self):
        for extra in ([], ["--with-memory"], ["--fast-verify", "--with-memory"]):
            result = self.deploy("--dry-run", *extra)
            self.assertEqual(result.returncode, 0, self.output)
            self.assertEqual(self.calls, [], "dry run must not call adb, build, verify or health")
            self.assertFalse(self.agent_apk.exists() or self.memory_apk.exists())
            self.assertIn("dry run: static checks passed", result.stdout)
            self.assertIn("-s %s get-state" % PIXEL9, result.stdout)
            self.assertIn("-s %s install -r" % PIXEL9, result.stdout)
            self.assertEqual("TakuMemory" in result.stdout, "--with-memory" in extra)

    def test_dry_run_reports_missing_adb(self):
        result = self.deploy("--dry-run", "--adb", str(Path(self.tmp.name) / "no-such-adb"), adb=False)
        self.assertEqual(result.returncode, 2, self.output)
        self.assertIn("adb not executable", self.output)
        self.assertEqual(self.calls, [])

    # -- guards --------------------------------------------------------------------------------------
    def test_refuses_main_and_detached_branch(self):
        for branch in ("main", "DETACHED"):
            for extra in (["--dry-run"], []):
                result = self.deploy(*extra, FAKE_GIT_BRANCH=branch)
                self.assertEqual(result.returncode, 2, self.output)
                self.assertIn("refusing to deploy from branch", self.output)
                self.assertEqual(self.calls, [])

    def test_allow_branch_overrides(self):
        result = self.deploy("--dry-run", "--allow-branch", FAKE_GIT_BRANCH="main")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertIn("WARNING deploying from 'main'", self.output)
        self.assertEqual(self.calls, [])

    def test_refuses_redmi_empty_and_other_serials(self):
        cases = [
            (["--serial", REDMI], "refusing Redmi"),
            (["--serial", REDMI, "--dry-run"], "refusing Redmi"),
            (["--serial", ""], "empty --serial refused"),
            (["--serial", "", "--allow-other-serial"], "empty --serial refused"),
            (["--serial", "emulator-5554"], "only the Pixel 9"),
            (["--serial", "x;reboot", "--allow-other-serial"], "malformed serial"),
        ]
        for args, message in cases:
            result = self.deploy(*args)
            self.assertEqual(result.returncode, 2, (args, self.output))
            self.assertIn(message, self.output)
            self.assertEqual(self.calls, [], args)

    def test_other_serial_needs_explicit_override(self):
        result = self.deploy("--serial", "emulator-5554", "--allow-other-serial", "--skip-verify", "--skip-health")
        self.assertEqual(result.returncode, 0, self.output)
        self.assert_device_contract(serial="emulator-5554")
        self.assertIn("WARNING target emulator-5554", self.output)

    def test_bad_usage(self):
        for args in (["--skip-verify", "--fast-verify"], ["--fast-verify", "--skip-verify"], ["--bogus"], ["--serial"]):
            result = self.deploy(*args)
            self.assertEqual(result.returncode, 2, (args, self.output))
            self.assertEqual(self.calls, [])

    # -- live flows ----------------------------------------------------------------------------------
    def test_full_deploy_order_and_contract(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, self.output)
        self.assertEqual(self.kinds(), [
            ("VERIFY",),
            ("BUILD", "agent"),
            ("get-state",),
            ("install", "-r", "TakuAgent.apk"),
            ("HEALTH", "--serial", PIXEL9, "--adb", str(self.adb)),
            ("get-state",),  # the health check's own read-only probe
        ])
        self.assert_device_contract()
        self.assertTrue(any(c[2] == "shell" for c in self.adb_calls()), "health check queries ran")
        self.assertRegex(result.stdout, r"built TakuAgent .*: [0-9a-f]{64}  \d+ bytes")
        self.assertIn("health check OK", result.stdout)
        self.assertIn("summary: 8 PASS, 0 WARN, 0 FAIL -> OK", result.stdout)

    def test_with_memory_and_fast_verify(self):
        result = self.deploy("--with-memory", "--fast-verify")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertEqual(self.kinds()[:7], [
            ("VERIFY", "--fast"),
            ("BUILD", "agent"),
            ("BUILD", "memory"),
            ("get-state",),
            ("install", "-r", "TakuAgent.apk"),
            ("install", "-r", "TakuMemory.apk"),
            ("HEALTH", "--serial", PIXEL9, "--adb", str(self.adb)),
        ])
        self.assert_device_contract()
        self.assertRegex(result.stdout, r"built TakuMemory .*: [0-9a-f]{64}  \d+ bytes")

    def test_skip_verify_and_health(self):
        result = self.deploy("--skip-verify", "--skip-health")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertEqual(self.kinds(), [("BUILD", "agent"), ("get-state",), ("install", "-r", "TakuAgent.apk")])
        self.assert_device_contract()
        self.assertIn("health check skipped", result.stdout)

    def test_offline_device_aborts_before_install(self):
        for state in ("offline", "unauthorized"):
            scenario = health_fixtures.healthy()
            scenario["state"] = state
            result = self.deploy("--with-memory", scenario=scenario)
            self.assertEqual(result.returncode, 5, self.output)
            self.assertEqual(self.kinds(), [("VERIFY",), ("BUILD", "agent"), ("BUILD", "memory"), ("get-state",)])
            self.assertIn("nothing installed", self.output)
            self.assert_device_contract()

    def test_health_fail_fails_deploy(self):
        scenario = health_fixtures.replace(health_fixtures.healthy(), "ps -A -o PID,PGID,NAME",
                                           "  PID  PGID NAME\n 5001  5001 agy\n 6100  5001 codex\n")
        result = self.deploy(scenario=scenario)
        self.assertEqual(result.returncode, 7, self.output)
        self.assertIn(("install", "-r", "TakuAgent.apk"), self.kinds())
        self.assertIn(("HEALTH", "--serial", PIXEL9, "--adb", str(self.adb)), self.kinds())
        self.assertRegex(result.stdout, r"FAIL\s+codex\s+codex still running: pid 6100")
        self.assertIn("health check reported FAIL", self.output)
        self.assert_device_contract()

    def test_verify_failure_stops_everything(self):
        result = self.deploy(FAKE_VERIFY_EXIT="1")
        self.assertEqual(result.returncode, 3, self.output)
        self.assertEqual(self.kinds(), [("VERIFY",)])

    def test_build_failure_or_missing_output_stops_before_adb(self):
        self.agent_apk.write_bytes(b"stale apk from an older build")
        for env in ({"FAKE_BUILD_FAIL": "agent"}, {"FAKE_BUILD_NO_OUTPUT": "agent"}, {"FAKE_BUILD_NO_OUTPUT": "memory"}):
            result = self.deploy("--with-memory", **env)
            self.assertEqual(result.returncode, 4, (env, self.output))
            self.assertEqual(self.adb_calls(), [], env)
            self.assertIn("nothing installed", self.output)
        self.assertFalse(self.memory_apk.exists(), "a stale artifact must never survive a build without output")

    def test_install_failure_skips_health(self):
        scenario = health_fixtures.healthy()
        scenario["install_fail"] = True
        result = self.deploy("--with-memory", scenario=scenario)
        self.assertEqual(result.returncode, 6, self.output)
        self.assertEqual(self.kinds()[-1], ("install", "-r", "TakuAgent.apk"))
        self.assertNotIn("HEALTH", [k[0] for k in self.kinds()])
        self.assert_device_contract()


if __name__ == "__main__":
    unittest.main(verbosity=2)
