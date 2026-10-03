"""Everything the server remembers, in one SQLite file.

Tables
  players   one row per installed game: an id, a secret token, the name and Cups last reported
  saves     the latest copy of each player's save file (the JSON the client writes), with a revision counter
  matches   every match the server planned: who, which mode, the seed it handed out, and the reported result
"""

from __future__ import annotations

import json
import random
import secrets
import sqlite3
import threading
import time

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

BOT_NAMES = [
    "Rivet", "Cobalt", "Fennick", "Quill", "Tamsin", "Brisk", "Moss", "Pixel", "Juniper",
    "Sprocket", "Vesper", "Nimbus", "Pepper", "Ziggy", "Onyx", "Marlow", "Kestrel", "Fizz",
    "Dynamo", "Wren", "Solder", "Halcyon", "Bramble", "Gizmo",
]


def clean_name(name: object) -> str:
    """Letters, digits, space, _ and -; at most 16 characters; never empty."""
    text = "".join(ch for ch in str(name or "") if ch.isalnum() or ch in " _-")[:16].strip()
    return text or "Player"


class Store:
    def __init__(self, path: str):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock, self._db:
            self._db.executescript(SCHEMA)

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

    # ------------------------------------------------------------------ saves

    def put_save(self, player_id: str, save: dict) -> int:
        """Stores the player's save and returns its new revision. Name, Cups and fighter are copied out for the leaderboard."""
        text = json.dumps(save, separators=(",", ":"))
        name = clean_name((save.get("settings") or {}).get("playerName"))
        cups = max(0, int(save.get("cups") or 0))
        fighter = str(save.get("selectedFighter") or "JUNO")[:16]
        with self._lock, self._db:
            row = self._db.execute("SELECT revision FROM saves WHERE player_id = ?", (player_id,)).fetchone()
            revision = (row["revision"] if row else 0) + 1
            self._db.execute(
                "INSERT INTO saves (player_id, revision, updated_at, json) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(player_id) DO UPDATE SET revision = excluded.revision, updated_at = excluded.updated_at, json = excluded.json",
                (player_id, revision, time.time(), text),
            )
            self._db.execute("UPDATE players SET name = ?, cups = ?, fighter = ? WHERE id = ?", (name, cups, fighter, player_id))
        return revision

    def get_save(self, player_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT revision, updated_at, json FROM saves WHERE player_id = ?", (player_id,)).fetchone()
        if row is None:
            return None
        return {"revision": row["revision"], "updatedAt": row["updated_at"], "save": json.loads(row["json"])}

    # ------------------------------------------------------------------ matches

    def plan_match(self, player_id: str, mode: str, fighter: str, level: int, difficulty: str, bots: int) -> dict:
        """The server decides the match: its seed (which fixes the bots' fighters and behaviour) and the bots' names."""
        seed = secrets.randbits(62)
        names = random.Random(seed).sample(BOT_NAMES, k=min(max(bots, 0), len(BOT_NAMES)))
        with self._lock, self._db:
            cur = self._db.execute(
                "INSERT INTO matches (player_id, mode, fighter, level, difficulty, seed, started_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (player_id, mode[:24], fighter[:16], int(level), difficulty[:12], seed, time.time()),
            )
            match_id = cur.lastrowid
        return {"matchId": match_id, "seed": seed, "botNames": names}

    def finish_match(self, player_id: str, match_id: int, result: dict) -> bool:
        """Records a result once, and only for the player the match was planned for."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE matches SET finished_at = ?, outcome = ?, placement = ?, kos = ?, deaths = ?, damage = ? "
                "WHERE id = ? AND player_id = ? AND finished_at IS NULL",
                (
                    time.time(), str(result.get("outcome") or "")[:12], int(result.get("placement") or 0),
                    int(result.get("kos") or 0), int(result.get("deaths") or 0), int(result.get("damage") or 0),
                    match_id, player_id,
                ),
            )
            return cur.rowcount == 1

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
