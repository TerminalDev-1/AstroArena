"""The match referee: the server's own run of every match.

The fight is played on the player's device, but the device only gets to say what the player *did* (their inputs,
tick by tick). The server then plays the whole match again itself, with the same seed, the same bots and those
inputs, and reads the result off its own copy. What the device claims the result was is never used.

The simulation is the game's own code (Kotlin), built into `referee/referee.jar` by `gradlew :referee:installReferee`
in the client project. This module starts it with Java, hands it one match, and reads the verdict back. If Java or
the jar is missing the server says so when it starts and falls back to checking that results are believable.
"""

from __future__ import annotations

import base64
import gzip
import os
import shutil
import subprocess

from .economy import Refused

MAX_INPUT_BYTES = 1_500_000  # a 20-minute match with every tick different is about 1.4 MB
TICKS_PER_SECOND = 60
RECORD_BYTES = 19


def find_java() -> str | None:
    """The Java to run the referee with: ASTRO_JAVA, then JAVA_HOME, then whatever `java` is on the PATH."""
    explicit = os.environ.get("ASTRO_JAVA")
    if explicit and os.path.exists(explicit):
        return explicit
    home = os.environ.get("JAVA_HOME")
    if home:
        for name in ("java.exe", "java"):
            candidate = os.path.join(home, "bin", name)
            if os.path.exists(candidate):
                return candidate
    return shutil.which("java")


def decode_inputs(text: object) -> bytes:
    """The input log as the game sends it: base64 of the gzipped log."""
    if not isinstance(text, str) or not text:
        raise Refused(422, "result refused: the match's inputs are missing")
    try:
        packed = base64.b64decode(text, validate=True)
        with gzip.GzipFile(fileobj=__import__("io").BytesIO(packed)) as stream:
            raw = stream.read(MAX_INPUT_BYTES + 1)
    except (ValueError, OSError, EOFError):
        raise Refused(400, "the match's inputs can't be read")
    if len(raw) > MAX_INPUT_BYTES:
        raise Refused(413, "the match's inputs are too long")
    return raw


def count_ticks(raw: bytes) -> int:
    """How many ticks a log covers (the first two bytes of each record)."""
    return sum(int.from_bytes(raw[i:i + 2], "big") for i in range(0, len(raw) - RECORD_BYTES + 1, RECORD_BYTES))


class Referee:
    def __init__(self, jar: str, java: str | None = None, timeout: float = 30.0):
        self.jar = jar
        self.java = java or find_java()
        self.timeout = timeout

    @property
    def available(self) -> bool:
        return bool(self.java) and os.path.exists(self.jar)

    def why_not(self) -> str:
        if not os.path.exists(self.jar):
            return "referee.jar is missing (build it with `gradlew :referee:installReferee` in client/)"
        return "Java was not found (install Java 17 or newer, or set JAVA_HOME)"

    def judge(self, mode: str, fighter: str, level: int, difficulty: str, seed: int, names: list[str], bots: dict, raw: bytes) -> dict:
        """Plays the match back and returns {outcome, placement, kos, deaths, damage, mvp, ticks, finished}."""
        lines = [
            "mode=%s" % mode, "fighter=%s" % fighter, "level=%d" % level, "difficulty=%s" % difficulty,
            "seed=%d" % seed, "names=%s" % ",".join(names),
        ]
        for key, value in sorted(bots.items()):
            lines.append("bot.%s=%s" % (key, ("true" if value else "false") if isinstance(value, bool) else repr(float(value))))
        lines.append("inputs=%s" % base64.b64encode(raw).decode("ascii"))
        try:
            done = subprocess.run(
                [self.java, "-Xss4m", "-jar", self.jar], input="\n".join(lines) + "\n", capture_output=True, text=True, timeout=self.timeout
            )
        except (OSError, subprocess.TimeoutExpired) as problem:
            raise Refused(503, "the referee couldn't run: %s" % problem)
        fields = dict(line.split("=", 1) for line in done.stdout.splitlines() if "=" in line)
        if done.returncode != 0 or "outcome" not in fields:
            raise Refused(422, "result refused: the match can't be replayed (%s)" % fields.get("error", done.stderr.strip()[:200] or "no verdict"))
        return {
            "outcome": fields["outcome"], "placement": int(fields["placement"]), "kos": int(fields["kos"]),
            "deaths": int(fields["deaths"]), "damage": int(fields["damage"]), "mvp": fields["mvp"] == "true",
            "ticks": int(fields["ticks"]), "finished": fields["finished"] == "true",
        }
