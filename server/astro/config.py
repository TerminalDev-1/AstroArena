"""The plain-text files an operator edits while the server runs.

They are re-read whenever they change on disk, so there is no need to restart the server after an edit.

versions_not_supported.cfg   which client versions are turned away, and what they are told
notices.cfg                  short messages shown to players on the home screen
bots.cfg                     how bots behave at each difficulty (the client has the same numbers built in as a fallback)
"""

from __future__ import annotations

import configparser
import os
import re
import threading

_VERSION = re.compile(r"^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?")
_RULE = re.compile(r"^(<=|>=|<|>|=)?\s*(\S+)$")


def parse_version(text: str) -> tuple[int, int, int] | None:
    """"0.5.1-preview", "v0.5.1-preview", "6" and "v6" all work; anything else is None."""
    m = _VERSION.match(text.strip())
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2) or 0), int(m.group(3) or 0))


def matches(rule: str, version: str) -> bool:
    """Does `version` fall under `rule`?  Rules: `*`, an exact version, or a comparison such as `<0.5.0` or `>=6`."""
    rule = rule.strip()
    if rule == "*":
        return True
    m = _RULE.match(rule)
    if not m:
        return False
    op = m.group(1) or "="
    want = parse_version(m.group(2))
    have = parse_version(version)
    if want is None or have is None:
        return False
    return {"=": have == want, "<": have < want, "<=": have <= want, ">": have > want, ">=": have >= want}[op]


def _rule_lines(path: str) -> list[tuple[str, str]]:
    """Lines of the form `rule | text`, ignoring blanks and # comments."""
    out: list[tuple[str, str]] = []
    try:
        with open(path, encoding="utf-8") as f:
            for raw in f:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                rule, _, text = line.partition("|")
                out.append((rule.strip(), text.strip()))
    except FileNotFoundError:
        pass
    return out


class Config:
    """Loads the .cfg files from one directory and reloads any of them that change."""

    def __init__(self, directory: str):
        self.directory = directory
        self._lock = threading.Lock()
        self._stamps: dict[str, float] = {}
        self._unsupported: list[tuple[str, str]] = []
        self._notices: list[tuple[str, str]] = []
        self._bots: dict[str, dict[str, float | bool]] = {}

    def _path(self, name: str) -> str:
        return os.path.join(self.directory, name)

    def _changed(self, name: str) -> bool:
        try:
            stamp = os.path.getmtime(self._path(name))
        except OSError:
            stamp = -1.0
        if self._stamps.get(name) == stamp:
            return False
        self._stamps[name] = stamp
        return True

    def _refresh(self) -> None:
        if self._changed("versions_not_supported.cfg"):
            self._unsupported = _rule_lines(self._path("versions_not_supported.cfg"))
        if self._changed("notices.cfg"):
            self._notices = _rule_lines(self._path("notices.cfg"))
        if self._changed("bots.cfg"):
            parser = configparser.ConfigParser()
            parser.read(self._path("bots.cfg"), encoding="utf-8")
            bots: dict[str, dict[str, float | bool]] = {}
            for section in parser.sections():
                values: dict[str, float | bool] = {}
                for key, raw in parser.items(section):
                    low = raw.strip().lower()
                    if low in ("true", "false"):
                        values[key] = low == "true"
                    else:
                        try:
                            values[key] = float(raw)
                        except ValueError:
                            continue
                bots[section.upper()] = values
            self._bots = bots

    def unsupported_message(self, version: str) -> str | None:
        """The message for a client version that is turned away, or None if the version is welcome."""
        with self._lock:
            self._refresh()
            for rule, text in self._unsupported:
                if matches(rule, version):
                    return text or "This version is no longer supported. Please update to keep playing."
        return None

    def notice(self, version: str) -> str:
        """The first notice that applies to this client version ("" if none)."""
        with self._lock:
            self._refresh()
            for rule, text in self._notices:
                if matches(rule, version):
                    return text
        return ""

    def bots(self) -> dict[str, dict[str, float | bool]]:
        with self._lock:
            self._refresh()
            return {k: dict(v) for k, v in self._bots.items()}
