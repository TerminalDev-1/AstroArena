"""Everything the server remembers, in one SQLite file.

Tables
  players         one row per installed game: an id, a secret token, its name, and what the server keeps for it:
                  Cups, unopened Spark Drops, how many drops it has earned today, and its profile (Bolts, Prisms,
                  fighters, claimed rewards: see economy.py) as JSON
  saves           the latest copy of each player's save file (the JSON the client writes), with a revision counter
  matches         every match the server planned: who, which mode, the seed it handed out, and the reported result
  deals           shop offers made by developers, shown to every player
  deal_purchases  how many times each player has bought each deal
"""

from __future__ import annotations

import datetime
import json
import random
import secrets
import sqlite3
import threading
import time

from . import economy, rules
from .economy import Refused

SCHEMA = """
CREATE TABLE IF NOT EXISTS players (
    id          TEXT PRIMARY KEY,
    token       TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL DEFAULT 'Player',
    cups        INTEGER NOT NULL DEFAULT 0,
    fighter     TEXT NOT NULL DEFAULT 'JUNO',
    version     TEXT NOT NULL DEFAULT '',
    created_at  REAL NOT NULL,
    last_seen   REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS saves (
    player_id   TEXT PRIMARY KEY REFERENCES players(id),
    revision    INTEGER NOT NULL,
    updated_at  REAL NOT NULL,
    json        TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS matches (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id   TEXT NOT NULL REFERENCES players(id),
    mode        TEXT NOT NULL,
    fighter     TEXT NOT NULL,
    level       INTEGER NOT NULL,
    difficulty  TEXT NOT NULL,
    seed        INTEGER NOT NULL,
    started_at  REAL NOT NULL,
    finished_at REAL,
    outcome     TEXT,
    placement   INTEGER,
    kos         INTEGER,
    deaths      INTEGER,
    damage      INTEGER
);
CREATE TABLE IF NOT EXISTS deals (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_by  TEXT NOT NULL REFERENCES players(id),
    created_at  REAL NOT NULL,
    json        TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS deal_purchases (
    player_id   TEXT NOT NULL REFERENCES players(id),
    deal_id     INTEGER NOT NULL,
    count       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, deal_id)
);
CREATE INDEX IF NOT EXISTS players_by_cups ON players(cups DESC);
CREATE INDEX IF NOT EXISTS matches_by_player ON matches(player_id);
"""

# Columns added since the first release, as (name, definition). Existing databases get them on start-up.
PLAYER_COLUMNS = [
    ("drops", "INTEGER NOT NULL DEFAULT %d" % rules.STARTING_DROPS),
    ("boosted", "INTEGER NOT NULL DEFAULT 0"),     # how many of the unopened drops came from a split
    ("drops_day", "INTEGER NOT NULL DEFAULT -1"),  # the day drops_today counts for
    ("drops_today", "INTEGER NOT NULL DEFAULT 0"),
    ("imported", "INTEGER NOT NULL DEFAULT 0"),    # 1 once the starting Cups and drops have been settled
    ("flags", "INTEGER NOT NULL DEFAULT 0"),       # results the server refused to believe
    ("profile", "TEXT"),                           # Bolts, Prisms, fighters, claimed rewards (economy.py); NULL until started
    ("disabled", "INTEGER NOT NULL DEFAULT 0"),    # 1 = the operator has shut this account out (accounts.cfg); nothing is deleted
    ("disabled_reason", "TEXT NOT NULL DEFAULT ''"),  # what the player is told
    ("disabled_until", "REAL NOT NULL DEFAULT 0"),    # when it ends by itself (seconds since 1970); 0 = when the operator says
    ("difficulty", "TEXT"),                        # the bot difficulty this player picked (and the server approved); NULL = the default
]

MATCH_COLUMNS = [
    ("names", "TEXT"),                        # the bots' names the server handed out (JSON list)
    ("bots", "TEXT"),                         # the bot settings the match was played with (JSON)
    ("ticks", "INTEGER"),                     # how long the match ran, as the referee counted it
    ("verified", "INTEGER NOT NULL DEFAULT 0"),  # 1 = the referee replayed it and this is its result; 0 = the device's claim
    ("boss", "TEXT"),                         # Boss Mode: the boss the player asked for ('' or NULL = the seed picks)
]

BOT_NAMES = [
    "Rivet", "Cobalt", "Fennick", "Quill", "Tamsin", "Brisk", "Moss", "Pixel", "Juniper",
    "Sprocket", "Vesper", "Nimbus", "Pepper", "Ziggy", "Onyx", "Marlow", "Kestrel", "Fizz",
    "Dynamo", "Wren", "Solder", "Halcyon", "Bramble", "Gizmo",
]

_EPOCH = datetime.date(1970, 1, 1)


def clean_name(name: object) -> str:
    """Letters, digits, space, _ and -; at most 16 characters; never empty."""
    text = "".join(ch for ch in str(name or "") if ch.isalnum() or ch in " _-")[:16].strip()
    return text or "Player"


def today() -> int:
    """The server's calendar day, counted from 1970. The day, and when it ends, are the server's to say."""
    return (datetime.date.today() - _EPOCH).days


def season_ends_ms(day: int) -> int:
    """When the Spark Pass season that `day` falls in ends (ms since 1970, the server's midnight)."""
    last = _EPOCH + datetime.timedelta(days=(economy.pass_season(day) + 1) * economy.PASS_SEASON_DAYS)
    return int(datetime.datetime.combine(last, datetime.time.min).timestamp() * 1000)


def clock() -> dict:
    """The server's time, for the game to count down from: now, today's number, and when today ends (ms since 1970)."""
    midnight = datetime.datetime.combine(datetime.date.today() + datetime.timedelta(days=1), datetime.time.min)
    return {"now": int(time.time() * 1000), "day": today(), "dayEndsAt": int(midnight.timestamp() * 1000)}


class Store:
    def __init__(self, path: str):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        # Whether a new account's first save sets its starting progress (the operator's choice; see game.cfg).
        self.import_progress = lambda: True
        # The Cups each mode pays (the operator's choice; see trophies.cfg).
        self.cups = lambda: None
        with self._lock, self._db:
            self._db.executescript(SCHEMA)
            have = {row["name"] for row in self._db.execute("PRAGMA table_info(players)")}
            for name, definition in PLAYER_COLUMNS:
                if name not in have:
                    self._db.execute("ALTER TABLE players ADD COLUMN %s %s" % (name, definition))
            have = {row["name"] for row in self._db.execute("PRAGMA table_info(matches)")}
            for name, definition in MATCH_COLUMNS:
                if name not in have:
                    self._db.execute("ALTER TABLE matches ADD COLUMN %s %s" % (name, definition))

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ------------------------------------------------------------------ players

    def register(self, name: object, version: str) -> dict:
        now = time.time()
        player = {"id": secrets.token_hex(8), "token": secrets.token_hex(24)}
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO players (id, token, name, version, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)",
                (player["id"], player["token"], clean_name(name), version, now, now),
            )
        return player

    def player_for(self, token: str, version: str = "") -> sqlite3.Row | None:
        """The player a token belongs to (and note that they were just here)."""
        if not token:
            return None
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM players WHERE token = ?", (token,)).fetchone()
            if row is not None:
                self._db.execute(
                    "UPDATE players SET last_seen = ?, version = CASE WHEN ? = '' THEN version ELSE ? END WHERE id = ?",
                    (time.time(), version, version, row["id"]),
                )
        return row

    def player(self, player_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute("SELECT * FROM players WHERE id = ?", (player_id,)).fetchone()

    def grant(self, player_id: str, cups: int = 0, drops: int = 0, bolts: int = 0, prisms: int = 0, credits: int = 0) -> None:
        """Developer hand-outs."""
        with self._lock, self._db:
            self._db.execute(
                "UPDATE players SET cups = MAX(0, cups + ?), drops = MAX(0, drops + ?) WHERE id = ?", (int(cups), int(drops), player_id)
            )
            profile = self._profile(player_id)
            profile["bolts"] = max(0, profile["bolts"] + int(bolts))
            profile["prisms"] = max(0, profile["prisms"] + int(prisms))
            profile["credits"] = max(0, profile["credits"] + int(credits))
            self._keep(player_id, profile)

    def accounts(self) -> list[dict]:
        """Every account as accounts.cfg shows it, top of the leaderboard first."""
        with self._lock:
            rows = self._db.execute("SELECT id, name, cups, drops, disabled, disabled_reason, disabled_until FROM players ORDER BY disabled ASC, cups DESC, created_at ASC").fetchall()
            out = []
            for row in rows:
                profile = self._profile(row["id"])
                out.append({
                    "id": row["id"], "name": row["name"], "cups": row["cups"], "drops": row["drops"], "disabled": bool(row["disabled"]),
                    "disabled_reason": row["disabled_reason"], "disabled_until": row["disabled_until"],
                    "best_cups": profile["bestCups"], "bolts": profile["bolts"], "prisms": profile["prisms"],
                    "credits": profile["credits"], "glory": profile["glory"],
                    "fighters": {
                        name: {"unlocked": bool(entry.get("unlocked")), "level": int(entry.get("level") or 1), "cups": int(entry.get("cups") or 0)}
                        for name, entry in profile["fighters"].items() if name in rules.FIGHTER_SKINS
                    },
                })
            return out

    def force(self, player_id: str, change: dict) -> bool:
        """The operator's word (accounts.cfg): sets what `change` names, whatever it was. False if there is no such account."""
        def number(value, high=2_000_000_000) -> int:
            return min(max(int(value), 0), high)

        with self._lock, self._db:
            row = self._db.execute("SELECT cups, drops, boosted, disabled, disabled_reason, disabled_until FROM players WHERE id = ?", (player_id,)).fetchone()
            if row is None:
                return False
            cups = number(change.get("cups", row["cups"]))
            drops = number(change.get("drops", row["drops"]))
            # Disabling marks the account and nothing more; a reason and an end only mean something while it is disabled.
            disabled = bool(change.get("disabled", row["disabled"]))
            reason = " ".join(str(change.get("disabled_reason", row["disabled_reason"])).split())[:200] if disabled else ""
            until = max(0.0, float(change.get("disabled_until", row["disabled_until"]))) if disabled else 0.0
            self._db.execute(
                "UPDATE players SET cups = ?, drops = ?, boosted = ?, disabled = ?, disabled_reason = ?, disabled_until = ? WHERE id = ?",
                (cups, drops, min(row["boosted"], drops), 1 if disabled else 0, reason, until, player_id),
            )
            profile = self._profile(player_id)
            for key, field in (("best_cups", "bestCups"), ("bolts", "bolts"), ("prisms", "prisms"), ("credits", "credits"), ("glory", "glory")):
                if key in change:
                    profile[field] = number(change[key])
            for fighter, parts in (change.get("fighters") or {}).items():
                entry = profile["fighters"].get(fighter)
                if entry is None:
                    continue
                if "unlocked" in parts:
                    entry["unlocked"] = bool(parts["unlocked"]) or fighter == rules.STARTING_FIGHTER
                if "level" in parts:
                    entry["level"] = min(max(int(parts["level"]), 1), economy.LEVEL_LIMIT)
                if "cups" in parts:
                    entry["cups"] = number(parts["cups"])
            self._keep(player_id, profile)
        return True

    def delete(self, player_id: str) -> dict | None:
        """Deletes an account and everything that hangs off it, for good. Returns what it held (so the caller can
        keep a record), or None if there is no such account. Shop deals it made stay in the shop."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM players WHERE id = ?", (player_id,)).fetchone()
            if row is None:
                return None
            held = dict(row)
            held.pop("token", None)  # the key to the account is not something to leave lying in a log
            held["profile"] = self._profile(player_id)
            for table in ("deal_purchases", "matches", "saves"):
                self._db.execute("DELETE FROM %s WHERE player_id = ?" % table, (player_id,))
            self._db.execute("DELETE FROM players WHERE id = ?", (player_id,))
            return held

    def lift_expired(self, now: float | None = None) -> None:
        """Lets back in every account whose time disabled has run out."""
        with self._lock, self._db:
            self._db.execute(
                "UPDATE players SET disabled = 0, disabled_reason = '', disabled_until = 0 WHERE disabled = 1 AND disabled_until > 0 AND disabled_until <= ?",
                (time.time() if now is None else now,),
            )

    def set_difficulty(self, player_id: str, difficulty: str) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE players SET difficulty = ? WHERE id = ?", (difficulty, player_id))

    def buy_daily(self, player_id: str, offers: list[dict], index: int, day: int) -> dict:
        """Buys one of the day's offers. `day` is the day the player saw it on: after midnight it no longer counts."""
        now = today()
        if day != now:
            raise Refused(409, "the shop has changed since then")
        return self._change(player_id, lambda p: economy.buy_daily(p, offers, index, now))

    def reset(self, player_id: str) -> None:
        """Starts a player's progress over: Cups, drops and profile. Their name and account stay."""
        with self._lock, self._db:
            self._db.execute(
                "UPDATE players SET cups = 0, drops = ?, boosted = 0, drops_day = -1, drops_today = 0, profile = ? WHERE id = ?",
                (rules.STARTING_DROPS, json.dumps(economy.new_profile()), player_id),
            )
            self._db.execute("DELETE FROM deal_purchases WHERE player_id = ?", (player_id,))

    def rank(self, player_id: str) -> tuple[int, int]:
        """Where a player stands by Cups (1 = top; ties go to the older account), and how many players there are."""
        with self._lock:
            me = self._db.execute("SELECT cups, created_at FROM players WHERE id = ?", (player_id,)).fetchone()
            total = self._db.execute("SELECT COUNT(*) FROM players WHERE disabled = 0").fetchone()[0]
            ahead = self._db.execute(
                "SELECT COUNT(*) FROM players WHERE disabled = 0 AND (cups > ? OR (cups = ? AND created_at < ?))", (me["cups"], me["cups"], me["created_at"])
            ).fetchone()[0]
        return ahead + 1, total

    def drops_left_today(self, player: sqlite3.Row) -> int:
        return rules.DROPS_PER_DAY - (player["drops_today"] if player["drops_day"] == today() else 0)

    # ------------------------------------------------------------------ profiles (callers hold the lock)

    def _profile(self, player_id: str) -> dict:
        """The player's profile. One that hasn't been started yet begins from their stored save, or from scratch."""
        row = self._db.execute("SELECT profile FROM players WHERE id = ?", (player_id,)).fetchone()
        if row["profile"]:
            return economy.complete(json.loads(row["profile"]))
        saved = self._db.execute("SELECT json FROM saves WHERE player_id = ?", (player_id,)).fetchone()
        if saved is not None and self.import_progress():
            return economy.profile_from_save(json.loads(saved["json"]))
        return economy.new_profile()

    def _keep(self, player_id: str, profile: dict) -> None:
        self._db.execute("UPDATE players SET profile = ? WHERE id = ?", (json.dumps(profile, separators=(",", ":")), player_id))

    def profile(self, player_id: str) -> dict:
        with self._lock:
            return self._profile(player_id)

    def _change(self, player_id: str, action):
        """Runs `action(profile)` and stores the profile if it didn't refuse. Returns what the action returned."""
        with self._lock, self._db:
            profile = self._profile(player_id)
            result = action(profile)
            self._keep(player_id, profile)
            return result

    def upgrade(self, player_id: str, fighter: str, factor: float = 1.0, no_cap: bool = False) -> int:
        return self._change(player_id, lambda p: economy.upgrade(p, fighter, factor, no_cap))

    def buy(self, player_id: str, key: str) -> dict:
        return self._change(player_id, lambda p: economy.buy(p, key))

    def claim_gift(self, player_id: str) -> dict:
        return self._change(player_id, lambda p: economy.claim_gift(p, today()))

    def claim_milestone(self, player_id: str, cups: int) -> dict:
        return self._change(player_id, lambda p: economy.claim_milestone(p, cups))

    def road_unlock(self, player_id: str) -> dict:
        return self._change(player_id, economy.road_unlock)

    def claim_pass(self, player_id: str, tier: int) -> dict:
        return self._change(player_id, lambda p: economy.claim_pass(p, tier, today()))

    # ------------------------------------------------------------------ deals

    def create_deal(self, player_id: str, data: dict) -> int:
        deal = economy.clean_deal(data)
        with self._lock, self._db:
            cur = self._db.execute(
                "INSERT INTO deals (created_by, created_at, json) VALUES (?, ?, ?)", (player_id, time.time(), json.dumps(deal))
            )
            return cur.lastrowid

    def delete_deal(self, deal_id: int) -> bool:
        with self._lock, self._db:
            self._db.execute("DELETE FROM deal_purchases WHERE deal_id = ?", (deal_id,))
            return self._db.execute("DELETE FROM deals WHERE id = ?", (deal_id,)).rowcount == 1

    def deals(self, player_id: str) -> list[dict]:
        """Every deal still running, with how many times this player has bought each."""
        now_ms = int(time.time() * 1000)
        with self._lock:
            rows = self._db.execute(
                "SELECT d.id, d.json, COALESCE(p.count, 0) AS purchased FROM deals d "
                "LEFT JOIN deal_purchases p ON p.deal_id = d.id AND p.player_id = ? ORDER BY d.id", (player_id,)
            ).fetchall()
        out = []
        for row in rows:
            deal = json.loads(row["json"])
            if 0 < deal["expiresAt"] <= now_ms:
                continue
            out.append({"id": row["id"], **deal, "purchased": row["purchased"]})
        return out

    def buy_deal(self, player_id: str, deal_id: int) -> dict:
        with self._lock, self._db:
            row = self._db.execute("SELECT json FROM deals WHERE id = ?", (deal_id,)).fetchone()
            if row is None:
                raise Refused(404, "no such deal")
            bought = self._db.execute("SELECT count FROM deal_purchases WHERE player_id = ? AND deal_id = ?", (player_id, deal_id)).fetchone()
            profile = self._profile(player_id)
            reward = economy.buy_deal(profile, json.loads(row["json"]), bought["count"] if bought else 0, int(time.time() * 1000))
            self._keep(player_id, profile)
            self._db.execute(
                "INSERT INTO deal_purchases (player_id, deal_id, count) VALUES (?, ?, 1) "
                "ON CONFLICT(player_id, deal_id) DO UPDATE SET count = count + 1", (player_id, deal_id),
            )
            return reward

    # ------------------------------------------------------------------ saves

    def put_save(self, player_id: str, save: dict, import_progress: bool = True) -> int:
        """Stores the player's save and returns its new revision.

        The name and fighter are copied out for the leaderboard. Cups, Spark Drops, Bolts, Prisms and everything
        else in the profile are the server's own: they are read from a save only once, the first time an account
        uploads one (and only if `import_progress` allows it), so that earlier progress carries over.
        """
        text = json.dumps(save, separators=(",", ":"))
        name = clean_name((save.get("settings") or {}).get("playerName"))
        cups = max(0, int(save.get("cups") or 0))
        drops = min(max(0, int(save.get("capsules") or 0)), rules.MAX_IMPORTED_DROPS)
        boosted = min(max(0, int(save.get("boostedCapsules") or 0)), drops)
        fighter = str(save.get("selectedFighter") or "JUNO")[:16]
        with self._lock, self._db:
            row = self._db.execute("SELECT revision FROM saves WHERE player_id = ?", (player_id,)).fetchone()
            revision = (row["revision"] if row else 0) + 1
            self._db.execute(
                "INSERT INTO saves (player_id, revision, updated_at, json) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(player_id) DO UPDATE SET revision = excluded.revision, updated_at = excluded.updated_at, json = excluded.json",
                (player_id, revision, time.time(), text),
            )
            self._db.execute("UPDATE players SET name = ?, fighter = ? WHERE id = ?", (name, fighter, player_id))
            player = self._db.execute("SELECT imported, profile FROM players WHERE id = ?", (player_id,)).fetchone()
            if not player["profile"]:
                self._keep(player_id, economy.profile_from_save(save) if import_progress else economy.new_profile())
            if not player["imported"]:
                if import_progress:
                    self._db.execute("UPDATE players SET cups = ?, drops = ?, boosted = ? WHERE id = ?", (cups, drops, boosted, player_id))
                self._db.execute("UPDATE players SET imported = 1 WHERE id = ?", (player_id,))
        return revision

    def get_save(self, player_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT revision, updated_at, json FROM saves WHERE player_id = ?", (player_id,)).fetchone()
        if row is None:
            return None
        return {"revision": row["revision"], "updatedAt": row["updated_at"], "save": json.loads(row["json"])}

    # ------------------------------------------------------------------ matches

    def plan_match(self, player_id: str, mode: str, fighter: str, difficulty: str, bots: int, bot_settings: dict | None = None, boss: str = "") -> dict:
        """The server decides the match: its seed (which fixes the bots' fighters and behaviour), the bots' names,
        the difficulty and how the bots behave. The fighter has to be one the player has unlocked, and it plays at
        the level the server holds for it. All of this is kept, so the referee can replay the match later."""
        seed = secrets.randbits(62)
        names = random.Random(seed).sample(BOT_NAMES, k=min(max(bots, 0), len(BOT_NAMES)))
        bot_settings = bot_settings or {}
        with self._lock, self._db:
            entry = self._profile(player_id)["fighters"].get(fighter)
            if not entry or not entry.get("unlocked"):
                raise Refused(409, "that fighter isn't unlocked")
            level = int(entry["level"])
            cur = self._db.execute(
                "INSERT INTO matches (player_id, mode, fighter, level, difficulty, seed, started_at, names, bots, boss) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (player_id, mode[:24], fighter, level, difficulty[:12], seed, time.time(), json.dumps(names), json.dumps(bot_settings), boss),
            )
            match_id = cur.lastrowid
        return {"matchId": match_id, "seed": seed, "botNames": names, "difficulty": difficulty, "fighter": fighter, "level": level, "bots": bot_settings, "boss": boss}

    def open_match(self, player_id: str, match_id: int) -> dict | None:
        """A match this player was given that hasn't been reported yet, with everything needed to replay it."""
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM matches WHERE id = ? AND player_id = ? AND finished_at IS NULL", (match_id, player_id)
            ).fetchone()
        if row is None:
            return None
        match = dict(row)
        match["names"] = json.loads(row["names"] or "[]")
        match["bots"] = json.loads(row["bots"] or "{}")
        return match

    def refuse_match(self, player_id: str, match_id: int) -> None:
        """Closes a match whose result was refused, and notes it against the player."""
        with self._lock, self._db:
            done = self._db.execute(
                "UPDATE matches SET finished_at = ?, outcome = 'REJECTED' WHERE id = ? AND player_id = ? AND finished_at IS NULL",
                (time.time(), match_id, player_id),
            ).rowcount
            if done:
                self._db.execute("UPDATE players SET flags = flags + 1 WHERE id = ?", (player_id,))

    def finish_match(self, player_id: str, match_id: int, result: dict, now: float | None = None, verified: bool = False) -> dict | None:
        """Closes a match the server planned and settles what it was worth.

        None if there is no open match with that id for this player. Otherwise a dict: either
        {"rejected": reason} when the result isn't believable (the match is closed and the player flagged), or
        {"cupDelta", "cups", "drop", "bolts", "firstWinPrisms"} with what the server awarded.
        """
        outcome = str(result.get("outcome") or "")[:12]
        placement = int(result.get("placement") or 0)
        kos = int(result.get("kos") or 0)
        deaths = int(result.get("deaths") or 0)
        damage = int(result.get("damage") or 0)
        mvp = bool(result.get("mvp"))
        now = time.time() if now is None else now
        with self._lock, self._db:
            match = self._db.execute(
                "SELECT * FROM matches WHERE id = ? AND player_id = ? AND finished_at IS NULL", (match_id, player_id)
            ).fetchone()
            if match is None:
                return None
            mode, difficulty = match["mode"], match["difficulty"]
            reason = rules.check_result(mode, now - match["started_at"], outcome, placement, kos, deaths, damage)
            if reason is not None:
                self._db.execute("UPDATE matches SET finished_at = ?, outcome = 'REJECTED' WHERE id = ?", (now, match_id))
                self._db.execute("UPDATE players SET flags = flags + 1 WHERE id = ?", (player_id,))
                return {"rejected": reason}
            self._db.execute(
                "UPDATE matches SET finished_at = ?, outcome = ?, placement = ?, kos = ?, deaths = ?, damage = ?, ticks = ?, verified = ? WHERE id = ?",
                (now, outcome, placement, kos, deaths, damage, result.get("ticks"), 1 if verified else 0, match_id),
            )
            player = self._db.execute("SELECT * FROM players WHERE id = ?", (player_id,)).fetchone()
            table = self.cups()
            cups = max(0, player["cups"] + rules.cup_delta(mode, outcome, placement, player["cups"], mvp, table))
            # How many of those Cups were the MVP's bonus, for the result screen to say so.
            mvp_cups = rules.cup_delta(mode, outcome, placement, 10 ** 9, mvp, table) - rules.cup_delta(mode, outcome, placement, 10 ** 9, False, table)
            day = today()
            earned = player["drops_today"] if player["drops_day"] == day else 0
            drop = rules.earns_drop(mode, outcome, placement) and earned < rules.DROPS_PER_DAY
            self._db.execute(
                "UPDATE players SET cups = ?, drops = drops + ?, drops_day = ?, drops_today = ? WHERE id = ?",
                (cups, 1 if drop else 0, day, earned + (1 if drop else 0), player_id),
            )
            profile = self._profile(player_id)
            bolts = economy.match_bolts(mode, outcome, placement, kos, difficulty)
            prisms = economy.first_win_prisms(mode, outcome, profile, day)
            profile["bolts"] += bolts
            profile["prisms"] += prisms
            if prisms:
                profile["lastFirstWinDay"] = day
            profile["bestCups"] = max(profile["bestCups"], cups)
            # The fighter that was played wins (or loses) the same Cups, and its rank follows them.
            entry = profile["fighters"].get(match["fighter"])
            fighter_before = entry.get("cups", 0) if entry else 0
            fighter_cups = max(0, fighter_before + cups - player["cups"])
            if entry:
                entry["cups"] = fighter_cups
            # Credits for the Spark Road (Glory once it is finished) and points for the Spark Pass.
            paid = economy.grant(profile, {"type": "credits", "amount": economy.match_credits(mode, outcome, placement)})
            points = economy.pass_points(mode, outcome, placement)
            economy.add_pass_points(profile, points, day)
            self._keep(player_id, profile)
            return {
                "cupDelta": cups - player["cups"], "cups": cups, "drop": drop, "bolts": bolts, "firstWinPrisms": prisms,
                "credits": paid["amount"] if paid["type"] == "credits" else 0, "glory": paid["amount"] if paid["type"] == "glory" else 0,
                "passPoints": points, "mvpCups": mvp_cups,
                "fighter": match["fighter"], "fighterCupsBefore": fighter_before, "fighterCups": fighter_cups,
                "fighterRank": rules.fighter_rank(fighter_cups),
            }

    # ------------------------------------------------------------------ Spark Drops

    def open_drop(self, player_id: str, luck: float = 0.0, free: bool = False) -> dict | None:
        """Opens one of the player's Spark Drops: the server rolls it and adds the reward to their profile.

        None if they have none to open. `luck` and `free` (the drop isn't used up) are for developers; the
        caller decides whether this player may use them.
        """
        results = self._open_drops(player_id, luck, free, 1)
        return results[0] if results else None

    def open_all_drops(self, player_id: str, luck: float = 0.0) -> list[dict]:
        """Opens every Spark Drop the player holds right now, one after another (up to `rules.MAX_OPEN_ALL`). The
        pieces that split off on the way are left for them to open next. The results come back in the order they
        were opened; empty if there were none."""
        return self._open_drops(player_id, luck, False, None)

    def _open_drops(self, player_id: str, luck: float, free: bool, most: int | None) -> list[dict]:
        """`most`: how many to open, or None for as many as the player holds."""
        results = []
        with self._lock, self._db:
            player = self._db.execute("SELECT drops, boosted FROM players WHERE id = ?", (player_id,)).fetchone()
            if player is None:
                return results
            drops, boosted_left = player["drops"], player["boosted"]
            if most is None:
                most = min(drops, rules.MAX_OPEN_ALL)
            profile = self._profile(player_id)
            rng = secrets.SystemRandom()
            while len(results) < most and (drops > 0 or (free and not results)):
                # Pieces from an earlier split are opened first, and roll better than a plain one.
                boosted = boosted_left > 0
                result = rules.open_drop(profile, boosted, luck, rng)
                result["reward"] = economy.grant(profile, result["reward"])
                extra = result["pieces"] - 1
                drops = drops - (0 if free else 1) + extra
                boosted_left = max(0, boosted_left - (1 if boosted else 0)) + extra
                results.append(result)
            if results:
                self._keep(player_id, profile)
                self._db.execute("UPDATE players SET drops = ?, boosted = ? WHERE id = ?", (drops, boosted_left, player_id))
        return results

    # ------------------------------------------------------------------ leaderboard & stats

    def leaderboard(self, limit: int) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT id, name, cups, fighter, profile FROM players WHERE disabled = 0 ORDER BY cups DESC, created_at ASC LIMIT ?", (max(1, min(limit, 200)),)
            ).fetchall()
        # Glory is shown beside a player's name; it lives in their profile.
        board = []
        for r in rows:
            row = dict(r)
            stored = row.pop("profile")
            row["glory"] = int((json.loads(stored) if stored else {}).get("glory") or 0)
            board.append(row)
        return board

    def stats(self) -> dict:
        with self._lock:
            players = self._db.execute("SELECT COUNT(*) FROM players").fetchone()[0]
            matches = self._db.execute("SELECT COUNT(*) FROM matches").fetchone()[0]
            finished = self._db.execute("SELECT COUNT(*) FROM matches WHERE finished_at IS NOT NULL").fetchone()[0]
        return {"players": players, "matches": matches, "finished": finished}
