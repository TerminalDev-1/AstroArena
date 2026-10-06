"""The plain-text files an operator edits while the server runs.

They are re-read whenever they change on disk, so there is no need to restart the server after an edit.

versions_not_supported.cfg   which client versions are turned away, and what they are told
notices.cfg                  short messages shown to players on the home screen
bots.cfg                     how bots behave at each difficulty (the client has the same numbers built in as a fallback)
game.cfg                     the difficulties players may pick, who the developers are, how new accounts start
shop.cfg                     the pool the day's shop offers are picked from
news.cfg                     the items on the News tab
trophies.cfg                 the Cups each mode pays
"""

from __future__ import annotations

import configparser
import os
import re
import threading

from . import rules

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
        self._game: dict[str, dict[str, str]] = {}
        self._shop: list[dict] = []
        self._offers_per_day = 3
        self._news: list[dict] = []
        self._cups: dict[str, dict] = {}

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
        if self._changed("game.cfg"):
            parser = configparser.ConfigParser()
            parser.read(self._path("game.cfg"), encoding="utf-8")
            self._game = {section.lower(): dict(parser.items(section)) for section in parser.sections()}

        if self._changed("shop.cfg"):
            # Section names are kept as written: they are the titles players see.
            parser = configparser.ConfigParser()
            parser.optionxform = str.lower
            parser.read(self._path("shop.cfg"), encoding="utf-8")
            pool, per_day = [], 3
            for section in parser.sections():
                values = dict(parser.items(section))
                if section.lower() == "settings":
                    try:
                        per_day = max(0, int(values.get("offers_per_day", "3")))
                    except ValueError:
                        pass
                    continue
                pool.append({
                    "title": section, "bolts": values.get("bolts", 0), "prisms": values.get("prisms", 0),
                    "fighter": values.get("fighter", "").upper(), "skinFighter": values.get("skin_fighter", "").upper(),
                    "skinIndex": values.get("skin", 0), "currency": values.get("currency", "PRISMS").upper(),
                    "price": values.get("price", 0), "wasPrice": values.get("was", 0), "theme": values.get("theme", 0),
                })
            self._shop, self._offers_per_day = pool, per_day

        if self._changed("news.cfg"):
            # Section names are kept as written: they are the headlines players see.
            parser = configparser.ConfigParser(interpolation=None)
            parser.optionxform = str.lower
            try:
                parser.read(self._path("news.cfg"), encoding="utf-8")
            except configparser.Error:
                pass
            self._news = [
                {
                    "title": section[:80], "date": parser.get(section, "date", fallback="")[:20],
                    "tag": (parser.get(section, "tag", fallback="NEWS").strip().upper() or "NEWS")[:12],
                    "text": " ".join(parser.get(section, "text", fallback="").split())[:1200],
                }
                for section in parser.sections()
            ][:30]

        if self._changed("trophies.cfg"):
            # Each section is a mode. Whatever the file leaves out, or gets wrong, keeps the built-in number.
            parser = configparser.ConfigParser()
            try:
                parser.read(self._path("trophies.cfg"), encoding="utf-8")
            except configparser.Error:
                parser = configparser.ConfigParser()
            cups = {mode: dict(pays) for mode, pays in rules.DEFAULT_CUPS.items()}
            for section in parser.sections():
                pays = cups.get(section.upper())
                if pays is None:
                    continue
                for key, raw in parser.items(section):
                    try:
                        if key == "places":
                            pays[key] = [int(n) for n in re.split(r"[,\s]+", raw.strip()) if n]
                        elif key in ("win", "draw", "mvp_bonus"):
                            pays[key] = int(raw)
                        elif key in ("max_loss", "loss_step"):
                            pays[key] = max(0, int(raw))
                    except ValueError:
                        continue
            self._cups = cups

    def cups(self) -> dict[str, dict]:
        """The Cups each mode pays (as written in trophies.cfg, over the built-in numbers)."""
        with self._lock:
            self._refresh()
            return {mode: dict(pays) for mode, pays in self._cups.items()}

    def news(self) -> list[dict]:
        """The News tab's items (as written in news.cfg), newest first."""
        with self._lock:
            self._refresh()
            return [dict(item) for item in self._news]

    def daily_pool(self) -> tuple[list[dict], int]:
        """The offers the day's shop is picked from (as written in shop.cfg), and how many to pick."""
        with self._lock:
            self._refresh()
            return [dict(o) for o in self._shop], self._offers_per_day

    def allowed_difficulties(self) -> list[str]:
        """The difficulties an ordinary player may pick."""
        raw = self._setting("players", "allowed", "EASY, NORMAL, HARD, ELITE")
        picked = [d for d in re.split(r"[,\s]+", raw.upper()) if d in ("EASY", "NORMAL", "HARD", "ELITE")]
        return picked or [self.default_difficulty()]

    def _setting(self, section: str, key: str, default: str = "") -> str:
        with self._lock:
            self._refresh()
            return self._game.get(section, {}).get(key, default).strip()

    def default_difficulty(self) -> str:
        """The bot difficulty ordinary players are given."""
        value = self._setting("players", "difficulty", "EASY").upper()
        return value if value in ("EASY", "NORMAL", "HARD", "ELITE") else "EASY"

    def is_developer(self, player_id: str) -> bool:
        if self._setting("developers", "everyone", "no").lower() in ("yes", "true", "on", "1"):
            return True
        ids = re.split(r"[,\s]+", self._setting("developers", "ids"))
        return player_id in ids

    def import_saves(self) -> bool:
        return self._setting("accounts", "import_saves", "yes").lower() in ("yes", "true", "on", "1")

    def accounts_per_hour(self) -> int:
        try:
            return max(1, int(self._setting("accounts", "per_address_per_hour", "10")))
        except ValueError:
            return 10

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
