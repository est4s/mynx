"""Tests for the apps' mynx.js (tools/web/mynx.js): its own tests under
node (tests/js), and a table that it sends the lines `mynx` sends for
the same options, so core sees the same either way. Skipped without node.

Run: python3 -m unittest discover -s tests/mynx
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest

from test_mynx import MYNX, FakeApp

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
JS_TESTS = os.path.join(ROOT, "tests", "js")
NODE = shutil.which("node")

# (JavaScript, the mynx command line): both must send the same request.
TABLE = [
    ("mynx.location()", ["location"]),
    ("mynx.location({gps: true, timeout: 30})", ["location", "--gps", "--timeout", "30"]),
    ("mynx.location.stream()", ["location", "--stream"]),
    ("mynx.location.stream({gps: true, every: 5})", ["location", "--stream", "--gps", "--every", "5"]),
    ("mynx.sensor('compass')", ["sensor", "compass"]),
    ("mynx.sensor('light', {timeout: 5})", ["sensor", "light", "--timeout", "5"]),
    ("mynx.sensor.stream('accelerometer', {rate: 20})", ["sensor", "accelerometer", "--stream", "--rate", "20"]),
    ("mynx.sensor.list()", ["sensor", "list"]),
    ("mynx.vibrate()", ["vibrate"]),
    ("mynx.vibrate(200)", ["vibrate", "200"]),
    ("mynx.vibrate(200, {strength: 50})", ["vibrate", "200", "--strength", "50"]),
    ("mynx.vibrate(undefined, {strength: 50})", ["vibrate", "--strength", "50"]),
    ("mynx.vibrate.effect('double-click')", ["vibrate", "effect", "double-click"]),
    ("mynx.vibrate.wave([200, '100@0', 600])", ["vibrate", "wave", "200", "100@0", "600"]),
    ("mynx.vibrate.wave([200, '100@0'], {repeat: true})", ["vibrate", "wave", "200", "100@0", "--repeat"]),
    ("mynx.vibrate.chain(['slow-rise', 'click+100', 'thud@60'])",
     ["vibrate", "chain", "slow-rise", "click+100", "thud@60"]),
    ("mynx.vibrate.list()", ["vibrate", "list"]),
    ("mynx.vibrate.stop()", ["vibrate", "stop"]),
    ("mynx.torch.on()", ["torch", "on"]),
    ("mynx.torch.on(30)", ["torch", "on", "30"]),
    ("mynx.torch.off()", ["torch", "off"]),
    ("mynx.battery()", ["battery"]),
    ("mynx.battery.stream()", ["battery", "--stream"]),
    ("mynx.battery.stream({every: 60})", ["battery", "--stream", "--every", "60"]),
    ("mynx.clipboard.get()", ["clipboard", "get"]),
    ("mynx.clipboard.set(\"a b\\n!'()*~-._%ü\")", ["clipboard", "set", "a b\n!'()*~-._%ü"]),
    ("mynx.share.text('héllo & wörld/?')", ["share", "--text", "héllo & wörld/?"]),
    ("mynx.notify('Done')", ["notify", "Done"]),
    ("mynx.notify('Done', 'it\\nfinished', {ifAway: true})", ["notify", "--if-away", "Done", "it\nfinished"]),
    ("mynx.ble.scan()", ["ble", "scan"]),
    ("mynx.ble.scan({seconds: 3, name: 'mynx', service: '180f', all: true})",
     ["ble", "scan", "--seconds", "3", "--name", "mynx", "--service", "180f", "--all"]),
    ("mynx.ble.scan.stream({name: 'mynx', all: true})", ["ble", "scan", "--stream", "--name", "mynx", "--all"]),
    ("mynx.ble.services('mynx-ble')", ["ble", "services", "mynx-ble"]),
    ("mynx.ble.read('mynx-ble', 'battery-level')", ["ble", "read", "mynx-ble", "battery-level"]),
    ("mynx.ble.read('D4:E9:F4:A2:4A:06', '180f/2a19', {as: 'u8'})",
     ["ble", "read", "D4:E9:F4:A2:4A:06", "180f/2a19", "--as", "u8"]),
    ("mynx.ble.write('mynx-ble', 'nus-rx', 'hello')", ["ble", "write", "mynx-ble", "nus-rx", "hello", "--as", "text"]),
    ("mynx.ble.write('mynx-ble', 'nus-rx', new Uint8Array([10, 27]), {noResponse: true})",
     ["ble", "write", "mynx-ble", "nus-rx", "0a1b", "--as", "hex", "--no-response"]),
    ("mynx.ble.write('mynx-ble', 'x', -5, {as: 'i8'})", ["ble", "write", "mynx-ble", "x", "-5", "--as", "i8"]),
    ("mynx.ble.notify('mynx-ble', 'nus-tx', {as: 'text'})", ["ble", "notify", "mynx-ble", "nus-tx", "--as", "text"]),
    ("mynx.ble.connect('mynx-ble')", ["ble", "connect", "mynx-ble"]),
    ("mynx.service.list()", ["service", "list"]),
    ("mynx.service.start('web')", ["service", "start", "web"]),
    ("mynx.service.stop('web')", ["service", "stop", "web"]),
    ("mynx.service.restart('web')", ["service", "restart", "web"]),
    ("mynx.service.log('web')", ["service", "log", "web"]),
    ("mynx.service.log('web', {lines: 50})", ["service", "log", "web", "-n", "50"]),
]


class AnswerAll(dict):
    """Every request is answered {"ok": true}: only what was sent counts."""

    def get(self, key, default=None):
        return {"ok": True}


@unittest.skipUnless(NODE, "no node")
class MynxJsTest(unittest.TestCase):
    def test_its_own_tests_pass_under_node(self):
        run = subprocess.run([NODE, "--test", os.path.join(JS_TESTS, "mynx.test.js")],
                             capture_output=True, text=True, timeout=120)
        self.assertEqual(run.returncode, 0, run.stdout[-6000:] + run.stderr[-2000:])

    def test_it_parses_as_a_plain_script(self):
        run = subprocess.run([NODE, "--check", os.path.join(ROOT, "tools", "web", "mynx.js")],
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 0, run.stderr)

    def js_lines(self, expressions):
        run = subprocess.run([NODE, os.path.join(JS_TESTS, "lines.js")], input=json.dumps(expressions),
                             capture_output=True, text=True, timeout=120)
        self.assertEqual(run.returncode, 0, run.stderr)
        return json.loads(run.stdout)

    def cli_lines(self, commands):
        with tempfile.TemporaryDirectory() as tmp:
            requests = os.path.join(tmp, "requests")
            home = os.path.join(tmp, "home")
            os.mkdir(requests)
            os.mkdir(home)
            app = FakeApp(requests, AnswerAll())
            app.thread.start()
            sent = []
            try:
                for argv in commands:
                    before = len(app.requests)
                    env = {"PATH": os.environ["PATH"], "MYNX_REQUESTS": requests, "HOME": home,
                           "MYNX_TOOLS": tmp, "MYNX_TIMEOUT": "1"}
                    # What it prints after doesn't matter: the answer is a stand-in.
                    subprocess.run(["python3", MYNX, *argv, "--json"], capture_output=True, text=True,
                                   env=env, timeout=30)
                    sent.append(app.requests[before] if len(app.requests) > before else None)
            finally:
                app.stop.set()
                app.thread.join()
        return sent

    def test_it_sends_the_lines_mynx_sends_for_the_same_options(self):
        js = self.js_lines([expression for expression, _ in TABLE])
        cli = self.cli_lines([argv for _, argv in TABLE])
        for (expression, argv), from_js, from_cli in zip(TABLE, js, cli):
            self.assertIsNotNone(from_cli, f"mynx {' '.join(argv)} sent nothing")
            self.assertEqual(from_js, from_cli, expression)


if __name__ == "__main__":
    unittest.main()
