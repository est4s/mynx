"""mic: the phone's microphone as a stream of 16-bit samples, for tuner,
spectrum and dbmeter.

It streams from the app's sound device (PulseAudio, through `parec`). The
device can stall: parec stays connected but no audio arrives, not even
silence. Mic notices that, restarts the device with `pocket sound start`
and reconnects; if that doesn't help, or there's no sound device, it reads
a `pocket audio record` WAV as it grows. `status` says what it's doing,
for the screen; read() raises MicError when nothing works. Exact silence
(the device sends zeros while the app is in the background, or the mic is
off or not allowed) isn't a fault: `status` only says to open the app.

    mic = Mic(48000)
    mic.start()
    samples = mic.read()      # array('h'), maybe empty; call it often
    mic.close()
"""
import array
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

STALL_FIRST = 2.0             # seconds to wait for parec's first audio
STALL = 1.0                   # seconds without audio mid-stream
MUTE = 2.0                    # seconds of exact zeros before saying so
RESTARTS = 2                  # device restarts before recording directly
HEALTHY = 10.0                # seconds of audio that make restarts count from 0
READY_WAIT = 10.0             # seconds for the device to come back up
RECORD_FIRST = 8.0            # seconds for `pocket audio record` to start writing
RECORD_STALL = 3.0
RECORD_RESTART = 20 * 60      # a fresh recording every 20 min keeps the file small


MUTED = "Microphone is silent: open the app (or allow the mic)"


class MicError(RuntimeError):
    pass


def run(cmd, timeout):
    try:
        return subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, timeout=timeout).stdout
    except (OSError, subprocess.TimeoutExpired):
        return b""


def device_on():
    """Whether the sound device is running and parec is there to read it."""
    return (bool(shutil.which("parec")) and bool(shutil.which("pactl"))
            and b": on" in run(["pocket", "sound"], 10))


def device_ready():
    """Whether PulseAudio answers and has its mic source."""
    return any(line.split(b"\t")[1:2] == [b"mic"]
               for line in run(["pactl", "list", "short", "sources"], 5).splitlines())


def stop(proc, sig=signal.SIGTERM, wait=3):
    if proc and proc.poll() is None:
        proc.send_signal(sig)
        try:
            proc.wait(wait)
        except subprocess.TimeoutExpired:
            proc.kill()


class Parec:
    """Raw PCM from the sound device's mic source."""

    def __init__(self, rate, latency_ms):
        self.proc = subprocess.Popen(
            ["parec", "--device=mic", "--raw", "--format=s16le", f"--rate={rate}",
             "--channels=1", f"--latency-msec={latency_ms}"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True)
        os.set_blocking(self.proc.stdout.fileno(), False)

    def fileno(self):
        return self.proc.stdout.fileno()

    def read(self):
        data = b""
        while True:
            try:
                chunk = os.read(self.fileno(), 65536)
            except BlockingIOError:
                return data
            if not chunk:
                err = self.proc.stderr.read().decode(errors="replace").strip()
                if "No such entity" in err:
                    err = "the sound device has no microphone"
                raise EOFError(err or "the microphone stream stopped")
            data += chunk

    def close(self):
        stop(self.proc)


class Recorder:
    """Runs `pocket audio record` and reads its WAV as it grows."""

    def __init__(self, rate):
        self.folder = tempfile.mkdtemp(prefix="mic-")
        self.part = os.path.join(self.folder, ".mic.wav.part")
        self.proc = subprocess.Popen(
            ["pocket", "audio", "record", "--rate", str(rate),
             os.path.join(self.folder, "mic.wav")],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            start_new_session=True)
        self.file, self.head = None, b""

    def fileno(self):
        return None

    def read(self):
        if self.file is None:
            try:
                self.file = open(self.part, "rb")
            except FileNotFoundError:
                if self.proc.poll() is not None:
                    out = self.proc.stdout.read().decode(errors="replace").strip()
                    raise EOFError(out or "the recording stopped")
                return b""
        data = self.file.read()
        if not data and self.proc.poll() is not None:
            raise EOFError("the recording stopped")
        if self.head is not None:          # skip the WAV header
            self.head += data
            at = self.head.find(b"data")
            if at < 0 or len(self.head) < at + 8:
                return b""
            data, self.head = self.head[at + 8:], None
        return data

    def close(self):
        if self.file:
            self.file.close()
        stop(self.proc, signal.SIGINT, 6)
        shutil.rmtree(self.folder, ignore_errors=True)


class Mic:
    def __init__(self, rate, latency_ms=30):
        self.rate, self.latency = rate, latency_ms
        self.src, self.mode, self.status = None, None, None
        self.restarts, self.fixer, self.fixed = 0, None, False
        self.rest = b""

    def start(self):
        if device_on():
            self._live()
        else:
            self._record()

    def fileno(self):
        """For select(); None while there's nothing to wait on."""
        return self.src.fileno() if self.src else None

    def read(self):
        """New samples since the last call (may be empty)."""
        now = time.monotonic()
        if self.fixer:
            if self.fixer.is_alive():
                return array.array("h")
            self.fixer = None
            if self.fixed:
                self._live()
            else:
                self.status = "Sound device didn't come back: recording directly…"
                self._record()
            return array.array("h")
        if self.mode == "record" and now - self.since > RECORD_RESTART:
            self.src.close()
            self._record()
        try:
            data = self.src.read()
        except EOFError as e:
            return self._trouble(str(e))
        if data:
            self.last = now
            if not self.got:
                self.got, self.status = True, None
            if self.restarts and now - self.since > HEALTHY:
                self.restarts = 0
        else:
            live = self.mode == "live"
            limit = (STALL if live else RECORD_STALL) if self.got else \
                (STALL_FIRST if live else RECORD_FIRST)
            if now - self.last > limit:
                return self._trouble("no sound comes from the microphone")
        data = self.rest + data
        cut = len(data) & ~1
        self.rest = data[cut:]
        samples = array.array("h", data[:cut])
        if sys.byteorder == "big":
            samples.byteswap()
        if self.mode == "live" and samples:
            if any(samples):
                self.sound = now
                if self.status == MUTED:
                    self.status = None
            elif now - self.sound > MUTE and not self.status:
                self.status = MUTED
        return samples

    def close(self):
        if self.src:
            self.src.close()
            self.src = None

    def _begin(self, src, mode):
        self.src, self.mode, self.rest = src, mode, b""
        self.since = self.last = self.sound = time.monotonic()
        self.got = False

    def _live(self):
        self._begin(Parec(self.rate, self.latency), "live")

    def _record(self):
        self._begin(Recorder(self.rate), "record")

    def _trouble(self, why):
        self.close()
        if self.mode == "record":
            raise MicError(why)
        if self.restarts < RESTARTS:
            self.restarts += 1
            self.status = "Microphone stalled: restarting the sound device…"
            self.fixer = threading.Thread(target=self._fix, daemon=True)
            self.fixer.start()
        else:
            self.status = "Sound device keeps stalling: recording directly…"
            self._record()
        return array.array("h")

    def _fix(self):
        run(["pocket", "sound", "start"], 30)
        end = time.monotonic() + READY_WAIT
        while not (ok := device_ready()) and time.monotonic() < end:
            time.sleep(0.5)
        self.fixed = ok
