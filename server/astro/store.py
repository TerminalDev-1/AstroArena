"""Everything the server remembers, in one SQLite file.

Tables
  players   one row per installed game: an id, a secret token, its name, and what the server keeps for it:
            Cups, unopened Spark Drops, and how many drops it has earned today
  saves     the latest copy of each player's save file (the JSON the client writes), with a revision counter
  matches   every match the server planned: who, which mode, the seed it handed out, and the reported result
"""

from __future__ import annotations

import datetime
import json
import random
import secrets
import sqlite3
import threading
import time

from . import rules

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
]

BOT_NAMES = [
    "Rivet", "Cobalt", "Fennick", "Quill", "Tamsin", "Brisk", "Moss", "Pixel", "Juniper",
    "Sprocket", "Vesper", "Nimbus", "Pepper", "Ziggy", "Onyx", "Marlow", "Kestrel", "Fizz",
    "Dynamo", "Wren", "Solder", "Halcyon", "Bramble", "Gizmo",
]


def clean_name(name: object) -> str:
    """Letters, digits, space, _ and -; at most 16 characters; never empty."""
    text = "".join(ch for ch in str(name or "") if ch.isalnum() or ch in " _-")[:16].strip()
    return text or "Player"


def today() -> int:
    """The server's calendar day, as a number that goes up by one at midnight."""
    return datetime.date.today().toordinal()


class Store:
    def __init__(self, path: str):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock, self._db:
            self._db.executescript(SCHEMA)
            have = {row["name"] for row in self._db.execute("PRAGMA table_info(players)")}
            for name, definition in PLAYER_COLUMNS:
                if name not in have:
                    self._db.execute("ALTER TABLE players ADD COLUMN %s %s" % (name, definition))

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

    def grant(self, player_id: str, cups: int = 0, drops: int = 0) -> None:
        """Developer hand-outs."""
        with self._lock, self._db:
            self._db.execute(
                "UPDATE players SET cups = MAX(0, cups + ?), drops = MAX(0, drops + ?) WHERE id = ?", (int(cups), int(drops), player_id)
            )

    def rank(self, player_id: str) -> tuple[int, int]:
        """Where a player stands by Cups (1 = top; ties go to the older account), and how many players there are."""
        with self._lock:
            me = self._db.execute("SELECT cups, created_at FROM players WHERE id = ?", (player_id,)).fetchone()
            total = self._db.execute("SELECT COUNT(*) FROM players").fetchone()[0]
            ahead = self._db.execute(
                "SELECT COUNT(*) FROM players WHERE cups > ? OR (cups = ? AND created_at < ?)", (me["cups"], me["cups"], me["created_at"])
            ).fetchone()[0]
        return ahead + 1, total

    def drops_left_today(self, player: sqlite3.Row) -> int:
        return rules.DROPS_PER_DAY - (player["drops_today"] if player["drops_day"] == today() else 0)

    # ------------------------------------------------------------------ saves

    def put_save(self, player_id: str, save: dict, import_progress: bool = True) -> int:
        """Stores the player's save and returns its new revision.

        The name and fighter are copied out for the leaderboard. Cups and Spark Drops are the server's own:
        they are read from a save only once, the first time an account uploads one (and only if
        `import_progress` allows it), so that progress made before the server kept them carries over.
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
            if import_progress:
                self._db.execute(
                    "UPDATE players SET cups = ?, drops = ?, boosted = ?, imported = 1 WHERE id = ? AND imported = 0",
                    (cups, drops, boosted, player_id),
                )
            else:
                self._db.execute("UPDATE players SET imported = 1 WHERE id = ? AND imported = 0", (player_id,))
        return revision

    def get_save(self, player_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT revision, updated_at, json FROM saves WHERE player_id = ?", (player_id,)).fetchone()
        if row is None:
            return None
        return {"revision": row["revision"], "updatedAt": row["updated_at"], "save": json.loads(row["json"])}

    # ------------------------------------------------------------------ matches

    def plan_match(self, player_id: str, mode: str, fighter: str, level: int, difficulty: str, bots: int) -> dict:
        """The server decides the match: its seed (which fixes the bots' fighters and behaviour), the bots' names and the difficulty."""
        seed = secrets.randbits(62)
        names = random.Random(seed).sample(BOT_NAMES, k=min(max(bots, 0), len(BOT_NAMES)))
        with self._lock, self._db:
            cur = self._db.execute(
                "INSERT INTO matches (player_id, mode, fighter, level, difficulty, seed, started_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (player_id, mode[:24], fighter[:16], int(level), difficulty[:12], seed, time.time()),
            )
            match_id = cur.lastrowid
        return {"matchId": match_id, "seed": seed, "botNames": names, "difficulty": difficulty}

    def finish_match(self, player_id: str, match_id: int, result: dict, now: float | None = None) -> dict | None:
        """Closes a match the server planned and settles what it was worth.

        None if there is no open match with that id for this player. Otherwise a dict: either
        {"rejected": reason} when the result isn't believable (the match is closed and the player flagged), or
        {"cupDelta", "cups", "drop"} with the Cups and Spark Drop the server awarded.
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
            reason = rules.check_result(match["mode"], now - match["started_at"], outcome, placement, kos, deaths, damage)
            if reason is not None:
                self._db.execute("UPDATE matches SET finished_at = ?, outcome = 'REJECTED' WHERE id = ?", (now, match_id))
                self._db.execute("UPDATE players SET flags = flags + 1 WHERE id = ?", (player_id,))
                return {"rejected": reason}
            self._db.execute(
                "UPDATE matches SET finished_at = ?, outcome = ?, placement = ?, kos = ?, deaths = ?, damage = ? WHERE id = ?",
                (now, outcome, placement, kos, deaths, damage, match_id),
            )
            player = self._db.execute("SELECT * FROM players WHERE id = ?", (player_id,)).fetchone()
            cups = max(0, player["cups"] + rules.cup_delta(match["mode"], outcome, placement, player["cups"], match["difficulty"], mvp))
            day = today()
            earned = player["drops_today"] if player["drops_day"] == day else 0
            drop = rules.earns_drop(match["mode"], outcome, placement) and earned < rules.DROPS_PER_DAY
            self._db.execute(
                "UPDATE players SET cups = ?, drops = drops + ?, drops_day = ?, drops_today = ? WHERE id = ?",
                (cups, 1 if drop else 0, day, earned + (1 if drop else 0), player_id),
            )
            return {"cupDelta": cups - player["cups"], "cups": cups, "drop": drop}

    # ------------------------------------------------------------------ Spark Drops

    def open_drop(self, player_id: str, luck: float = 0.0, free: bool = False) -> dict | None:
        """Opens one of the player's Spark Drops: the server rolls it and adds the reward to the stored save.

        None if they have none to open. `luck` and `free` (the drop isn't used up) are for developers; the
        caller decides whether this player may use them.
        """
        with self._lock, self._db:
            player = self._db.execute("SELECT drops, boosted FROM players WHERE id = ?", (player_id,)).fetchone()
            if player is None or (player["drops"] <= 0 and not free):
                return None
            row = self._db.execute("SELECT revision, json FROM saves WHERE player_id = ?", (player_id,)).fetchone()
            save = json.loads(row["json"]) if row else {}
            # Pieces from an earlier split are opened first, and roll better than a plain one.
            boosted = player["boosted"] > 0
            result = rules.open_drop(save, boosted, luck, secrets.SystemRandom())
            extra = result["pieces"] - 1
            self._db.execute(
                "UPDATE players SET drops = ?, boosted = ? WHERE id = ?",
                (
                    player["drops"] - (0 if free else 1) + extra,
                    max(0, player["boosted"] - (1 if boosted else 0)) + extra,
                    player_id,
                ),
            )
            if row:
                rules.apply_reward(save, result["reward"])
                self._db.execute(
                    "UPDATE saves SET revision = ?, updated_at = ?, json = ? WHERE player_id = ?",
                    (row["revision"] + 1, time.time(), json.dumps(save, separators=(",", ":")), player_id),
                )
        return result

    # ------------------------------------------------------------------ leaderboard & stats

    def leaderboard(self, limit: int) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT id, name, cups, fighter FROM players ORDER BY cups DESC, created_at ASC LIMIT ?", (max(1, min(limit, 200)),)
            ).fetchall()
        return [dict(r) for r in rows]

    def stats(self) -> dict:
        with self._lock:
            players = self._db.execute("SELECT COUNT(*) FROM players").fetchone()[0]
            matches = self._db.execute("SELECT COUNT(*) FROM matches").fetchone()[0]
            finished = self._db.execute("SELECT COUNT(*) FROM matches WHERE finished_at IS NOT NULL").fetchone()[0]
        return {"players": players, "matches": matches, "finished": finished}
