"""accounts.cfg: every account on the server, written out for whoever runs it to read and to overrule.

The server writes the file from its database, in leaderboard order, and writes it again whenever an account
changes. When the operator edits a value and saves, that value is forced onto the account. Only what the
operator changed is applied: the file carries a revision number, the server remembers what it wrote at each
one, and an edit is a value that differs from what that revision said. So a file that was open for a while
does not undo what other players earned in the meantime.

It holds players' ids and names, so it is not committed (see .gitignore), and it never holds their tokens.
"""

from __future__ import annotations

import configparser
import json
import os
import re
import sys
import threading
import time

from . import economy, rules
from .store import Store

NAME = "accounts.cfg"
DELETED = "deleted_accounts.log"
# The whole numbers of an account, as the file calls them.
NUMBERS = ("cups", "best_cups", "drops", "bolts", "prisms", "credits")
_KEPT_REVISIONS = 500
_YES, _NO = ("yes", "true", "on", "1"), ("no", "false", "off", "0")
_DATE = "%Y-%m-%d %H:%M"
_SPAN = re.compile(r"(\d+)\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|w)(?![a-z])")
_SECONDS = {"m": 60, "h": 3600, "d": 86400, "w": 7 * 86400}
_ID = re.compile(r"^.*\(([0-9a-f]+)\)\s*$")

HEADER = """\
# Every account on this server, top of the leaderboard first. The server writes this file and keeps it
# up to date. Change a value and save: the server forces it onto that account within a few seconds,
# then writes the file out again. Only the values you changed are applied.
#
#   disabled    yes shuts the account out: the game tells the player it is disabled and they can't
#               play at all, and they are taken off the leaderboard. no lets them back in. The
#               account stays in the database and nothing they have is lost.
#               (It can't stop them starting over with a new account.)
#   disabled_reason   why, in your words: the player is shown it. Optional.
#   disabled_until    when it ends by itself. Optional: leave it empty and it lasts until you write
#               disabled = no. Write how long (30 minutes, 12 hours, 3 days, 2 weeks, 1 day 6 hours)
#               or a date (20/10/2026 18:00 or 2026-10-20 18:00, this computer's time); the server
#               turns it into the date. Both only count while disabled = yes.
#   cups        the player's Cups: their place on the leaderboard
#   best_cups   the most Cups they have had: how far along the Cup Track they are
#   drops       unopened Arena Boxes
#   bolts       Upgrade Credits
#   prisms      CPU Chips
#   credits     Credits on the Spark Road, toward the next fighter (enough of them unlocks it)
#   <fighter>   unlocked or locked, its level (1 to %d) and its own Cups (its rank follows them):
#                   mira = unlocked, level 4, cups 120
#               %s can't be locked: everyone starts with it.
#
# A section is one account: [the player's name (their player id)]. The id is the one shown in the
# game under Settings > Data. Names are the players' own to choose and can't be set here.
#   delete      deletes the account for good: its progress, its matches, its place on the
#               leaderboard. There is no undo in the game, so it takes three lines that agree,
#               all in the same save:
#                   delete = yes
#                   delete_name = <the account's name, exactly as its section shows it>
#                   delete_confirm = DELETE
#               With any of them missing or wrong nothing is deleted, and a line under the
#               section's heading says which. (A copy of what a deleted account held is added to
#               deleted_accounts.log beside this file, in case it was the wrong one.) If the
#               player opens the game again they simply start over as a new account.
#
# Removing a line or a section changes nothing: an account is only deleted as above.
# Edit it while the server is running. This file is private: it is not committed.

[file]
# The server's own line. Leave it as it is: it is how the server tells what you changed.
revision = %d
"""


def _say(text: str) -> None:
    sys.stderr.write("%s  accounts.cfg: %s\n" % (time.strftime("%H:%M:%S"), text))


def _fighter_text(entry: dict) -> str:
    return "%s, level %d, cups %d" % ("unlocked" if entry["unlocked"] else "locked", entry["level"], entry["cups"])


def _fighter_value(text: str) -> dict | None:
    """`unlocked, level 4, cups 120` as a dict of the parts that were written; None if it can't be read."""
    words = [w for w in re.split(r"[,\s=:]+", text.strip().lower()) if w]
    out: dict = {}
    i = 0
    while i < len(words):
        word = words[i]
        if word in ("locked", "unlocked"):
            out["unlocked"] = word == "unlocked"
            i += 1
        elif word in ("level", "cups") and i + 1 < len(words):
            try:
                out[word] = int(words[i + 1])
            except ValueError:
                return None
            i += 2
        else:
            return None
    return out


def when(text: str, now: float | None = None) -> float | None:
    """`3 days`, `1 day 6 hours`, `2026-10-20 18:00` or `20/10/2026 18:00` (day first, the UK way) as a time
    (seconds since 1970); 0 for nothing, which means no end; None if it can't be read."""
    text = " ".join(text.strip().lower().split())
    if text in ("", "never", "forever"):
        return 0.0
    for form in (_DATE, "%Y-%m-%d", "%d/%m/%Y %H:%M", "%d/%m/%Y"):
        try:
            return time.mktime(time.strptime(text, form))
        except (ValueError, OverflowError):
            pass
    spans = _SPAN.findall(text)
    if not spans or _SPAN.sub("", text).strip(" ,"):
        return None
    return (time.time() if now is None else now) + sum(int(n) * _SECONDS[unit[0]] for n, unit in spans)


def render(accounts: list[dict], revision: int, notes: dict[str, str] | None = None) -> str:
    lines = [HEADER % (economy.LEVEL_LIMIT, rules.STARTING_FIGHTER.lower(), revision)]
    for place, account in enumerate(accounts, start=1):
        lines.append("[%s (%s)]" % (account["name"], account["id"]))
        lines.append("# disabled: off the leaderboard" if account["disabled"] else "# %d on the leaderboard" % place)
        if notes and account["id"] in notes:
            lines.append("# NOT DELETED: " + notes[account["id"]])
        lines.append("disabled = %s" % ("yes" if account["disabled"] else "no"))
        lines.append("disabled_reason = %s" % account["disabled_reason"])
        lines.append("disabled_until = %s" % (time.strftime(_DATE, time.localtime(account["disabled_until"])) if account["disabled_until"] > 0 else ""))
        lines.extend("%s = %d" % (key, account[key]) for key in NUMBERS)
        lines.extend("%s = %s" % (name.lower(), _fighter_text(entry)) for name, entry in account["fighters"].items())
        lines += ["delete = no", "delete_name = ", "delete_confirm = "]
        lines.append("")
    return "\n".join(lines)


def parse(text: str) -> tuple[int, dict[str, dict[str, str]]]:
    """The file's revision (-1 if it has none) and each account's lines as written. Raises configparser.Error."""
    parser = configparser.ConfigParser(interpolation=None)
    parser.read_string(text)
    try:
        revision = int(parser.get("file", "revision", fallback="-1"))
    except ValueError:
        revision = -1
    # A section is `name (id)`; the id is what counts.
    return revision, {_ID.sub(r"\1", section): dict(parser.items(section)) for section in parser.sections() if section != "file"}


def edits(written: dict[str, dict[str, str]], now: dict[str, dict[str, str]]) -> dict[str, dict]:
    """What the operator changed between the file the server wrote and the file as it is now, per account,
    in the form `Store.force` takes. Lines that can't be read are left out."""
    out: dict[str, dict] = {}
    for player_id, lines in now.items():
        before = written.get(player_id)
        if before is None:
            continue
        change: dict = {}
        for key, value in lines.items():
            if key not in before or value.strip() == before[key].strip():
                continue
            if key in ("delete", "delete_name", "delete_confirm"):
                # Asked for, but not carried out here: the caller checks the two confirmations first.
                if lines.get("delete", "").strip().lower() in _YES:
                    change["delete"] = {"name": lines.get("delete_name", "").strip(), "confirm": lines.get("delete_confirm", "").strip()}
            elif key == "disabled":
                word = value.strip().lower()
                if word in _YES or word in _NO:
                    change["disabled"] = word in _YES
            elif key == "disabled_reason":
                change["disabled_reason"] = value
            elif key == "disabled_until":
                until = when(value)
                if until is not None:
                    change["disabled_until"] = until
            elif key in NUMBERS:
                try:
                    change[key] = int(value.replace(",", "").replace("_", ""))
                except ValueError:
                    continue
            elif key.upper() in rules.FIGHTER_SKINS:
                parts, old = _fighter_value(value), _fighter_value(before[key]) or {}
                changed = {k: v for k, v in (parts or {}).items() if old.get(k) != v}
                if changed:
                    change.setdefault("fighters", {})[key.upper()] = changed
        if change:
            out[player_id] = change
    return out


class Accounts:
    """Keeps accounts.cfg and the database in step. `sync()` is one pass; `start()` runs it every few seconds."""

    def __init__(self, store: Store, directory: str, quiet: bool = False):
        self.store = store
        self.path = os.path.join(directory, NAME)
        self.quiet = quiet
        # Revisions start from the clock, so a copy of the file from an earlier run is never taken for this run's.
        self._revision = int(time.time())
        self._text: str | None = None  # the file as the server last wrote it
        self._written: dict[int, dict[str, dict[str, str]]] = {}  # what each revision said
        self._unreadable: str | None = None  # an edit that couldn't be read, so it is reported once
        self._notes: dict[str, str] = {}  # why an account that was asked to be deleted wasn't, shown in its section
        self._lock = threading.Lock()

    def _read(self) -> str | None:
        try:
            with open(self.path, encoding="utf-8") as f:
                return f.read()
        except (OSError, UnicodeDecodeError):
            return None

    def _apply(self, text: str) -> bool:
        """Forces what the operator changed. False if the file can't be read as it stands (it is left alone)."""
        try:
            revision, now = parse(text)
        except configparser.Error as problem:
            if text != self._unreadable and not self.quiet:
                _say("can't be read, so nothing was changed. Fix it and save again. (%s)" % str(problem).splitlines()[0])
            self._unreadable = text
            return False
        self._notes = {}  # what was said about the last edit has been read by now
        written = self._written.get(revision)
        if written is None:
            if not self.quiet:
                _say("that copy is from before the server started, so it can't tell what was changed. Written out again: edit this one.")
            return True
        for player_id, change in edits(written, now).items():
            asked = change.pop("delete", None)
            player = self.store.player(player_id) if asked else None
            if player is not None:
                # Two confirmations, both in the owner's own hand, or nothing is deleted.
                if asked["name"].lower() != player["name"].strip().lower():
                    self._notes[player_id] = "delete_name has to be this account's name, %s." % player["name"].strip()
                elif asked["confirm"] != "DELETE":
                    self._notes[player_id] = "delete_confirm has to be the word DELETE, in capitals."
                if player_id in self._notes:
                    if not self.quiet:
                        _say("%s not deleted: %s" % (player_id, self._notes[player_id]))
                    asked = None
            if asked and player is not None:
                # Deleting wins over anything else written for that account. What it held is kept in a log first.
                gone = self.store.delete(player_id)
                if gone is not None:
                    with open(os.path.join(os.path.dirname(self.path), DELETED), "a", encoding="utf-8") as log:
                        log.write(json.dumps({"deleted": time.strftime(_DATE), **gone}) + "\n")
                    if not self.quiet:
                        _say("%s (%s) deleted. What it held is in %s." % (gone["name"], player_id, DELETED))
            elif change and self.store.force(player_id, change) and not self.quiet:
                _say("%s forced: %s" % (player_id, change))
        return True

    def sync(self) -> None:
        with self._lock:
            self.store.lift_expired()
            text = self._read()
            if self._text is not None and text is not None and text != self._text:
                if not self._apply(text):
                    return
            fresh = render(self.store.accounts(), self._revision, self._notes)
            if text is not None and fresh == text == self._text:
                return
            self._revision += 1
            fresh = render(self.store.accounts(), self._revision, self._notes)
            self._written[self._revision] = parse(fresh)[1]
            self._written.pop(self._revision - _KEPT_REVISIONS, None)
            scratch = self.path + ".new"
            with open(scratch, "w", encoding="utf-8", newline="\n") as f:
                f.write(fresh)
            os.replace(scratch, self.path)
            self._text = fresh

    def start(self, every: float = 3.0) -> "Accounts":
        def loop() -> None:
            while True:
                try:
                    self.sync()
                except Exception as problem:  # the file is a convenience: it must never take the server down
                    if not self.quiet:
                        _say("skipped a pass: %s" % problem)
                time.sleep(every)

        threading.Thread(target=loop, daemon=True, name="accounts.cfg").start()
        return self
