"""The rules the server enforces itself rather than taking the client's word for them.

  * how many Cups a match is worth (the same table as the client's `Balance.kt`; keep the two in step)
  * which finishes earn a Spark Drop, and how many a day
  * whether a reported result is believable at all
  * what comes out of a Spark Drop
"""

from __future__ import annotations

import math

# ---------------------------------------------------------------------------- Cups

CUP_BONUS = {"EASY": 6, "NORMAL": 8, "HARD": 10, "ELITE": 12}
PLACEMENT_CUPS = [10, 8, 6, 4, 2, 0, -1, -2, -3, -4]
DIFFICULTIES = tuple(CUP_BONUS)
# What a match is planned at when the request doesn't name one.
DEFAULT_DIFFICULTY = "NORMAL"
OUTCOMES = ("VICTORY", "DEFEAT", "DRAW")

# Bots in each mode (the names the server hands out).
MODES = {"LAST_SPARK": 9, "KNOCKOUT_RUSH": 5, "BOSS": 0, "TRAINING": 0}


def cup_delta(mode: str, outcome: str, placement: int, cups: int, difficulty: str, mvp: bool) -> int:
    """How a match changes a player's Cups. Never takes them below zero."""
    bonus = CUP_BONUS.get(difficulty, CUP_BONUS["NORMAL"])
    if mode == "LAST_SPARK":
        base = PLACEMENT_CUPS[min(max(placement - 1, 0), len(PLACEMENT_CUPS) - 1)]
        if base > 0:
            return math.floor(base * bonus / 8 + 0.5)
        return 0 if cups < 40 else max(base, -cups)  # beginners don't lose Cups
    if mode == "KNOCKOUT_RUSH":
        if outcome == "VICTORY":
            return bonus + (2 if mvp else 0)
        if outcome == "DRAW":
            return 1
        return -min(6, cups // 80, cups)
    return 0  # Boss Mode and the Training Area are not played for Cups


# ---------------------------------------------------------------------------- Spark Drops: earning

DROPS_PER_DAY = 3
STARTING_DROPS = 1
MAX_IMPORTED_DROPS = 50


def earns_drop(mode: str, outcome: str, placement: int) -> bool:
    """A win in team modes, or a top-4 finish in free-for-all."""
    if mode == "LAST_SPARK":
        return 1 <= placement <= 4
    if mode == "KNOCKOUT_RUSH":
        return outcome == "VICTORY"
    return False


# ---------------------------------------------------------------------------- is a result believable?

# Nobody wins a real match faster than this, and a match left open this long is abandoned.
MIN_GOOD_RESULT_SECONDS = 20.0
MAX_MATCH_SECONDS = 3600.0
_MAX_KOS = {"LAST_SPARK": 9, "KNOCKOUT_RUSH": 40, "BOSS": 80, "TRAINING": 100000}
_MAX_DAMAGE = 3_000_000


def check_result(mode: str, elapsed: float, outcome: str, placement: int, kos: int, deaths: int, damage: int) -> str | None:
    """Why this result can't be real, or None if it could be.

    When the referee has replayed the match these are the referee's own numbers, and the check is a formality.
    It matters when there is no referee (no Java on the server): then it is all that stands between the server
    and a device that simply claims a win.
    """
    if outcome not in OUTCOMES:
        return "unknown outcome"
    if elapsed > MAX_MATCH_SECONDS:
        return "the match expired"
    if kos < 0 or deaths < 0 or damage < 0:
        return "negative numbers"
    if kos > _MAX_KOS.get(mode, 0):
        return "more knockouts than the mode allows"
    if damage > _MAX_DAMAGE or deaths > 1000:
        return "numbers out of range"
    if mode == "LAST_SPARK":
        if not 1 <= placement <= 10:
            return "placement out of range"
        if deaths > 1:
            return "more than one life in Last Spark"
        if placement == 1 and kos == 0 and damage == 0:
            return "a win without a fight"
    good = outcome == "VICTORY" or (mode == "LAST_SPARK" and placement <= 4)
    if good and mode != "TRAINING" and elapsed < MIN_GOOD_RESULT_SECONDS:
        return "finished faster than a match can be played"
    return None


# ---------------------------------------------------------------------------- Spark Drops: opening

TIERS = [("SCRAP", 40), ("TUNED", 28), ("CHARGED", 18), ("OVERCLOCKED", 8), ("PRISMATIC", 4), ("ULTRA", 2)]
MAX_LUCK = 14.0
MAX_PIECES = 8
# The pieces a drop splits into roll with this much extra luck and are never Scrap.
SPLIT_LUCK = 0.6

# Every fighter and how many colourways it has (index 0 is the one it comes with). Keep in step with Balance.kt.
FIGHTER_SKINS = {"JUNO": 3, "BRAKK": 3, "MIRA": 3, "KITO": 3}
STARTING_FIGHTER = "JUNO"


def odds(luck: float = 0.0) -> list[float]:
    """Chance of each tier. Luck multiplies a tier's weight by (1 + luck) for every tier it is above Scrap."""
    weights = [w * (1.0 + luck) ** i for i, (_, w) in enumerate(TIERS)]
    total = sum(weights)
    return [w / total for w in weights]


def roll_tier(rng, luck: float = 0.0) -> int:
    roll = rng.random()
    for i, chance in enumerate(odds(luck)):
        roll -= chance
        if roll < 0:
            return i
    return 0


def roll_pieces(rng, luck: float = 0.0) -> int:
    """1 for a plain drop; 2, 4 or 8 when it splits."""
    if rng.random() >= min(1.0, 0.25 + 0.05 * luck):
        return 1
    pieces = 2
    while pieces < MAX_PIECES and rng.random() < min(1.0, 0.5 + 0.08 * luck):
        pieces *= 2
    return pieces


def _fighters(save: dict) -> dict:
    fighters = save.get("fighters")
    return fighters if isinstance(fighters, dict) else {}


def _unlocked(save: dict, fighter: str) -> bool:
    entry = _fighters(save).get(fighter)
    if isinstance(entry, dict) and "unlocked" in entry:
        return bool(entry["unlocked"])
    return fighter == STARTING_FIGHTER


def _owned_skins(save: dict, fighter: str) -> set[int]:
    entry = _fighters(save).get(fighter)
    skins = entry.get("ownedSkins") if isinstance(entry, dict) else None
    out = {0}
    if isinstance(skins, list):
        out.update(int(s) for s in skins if isinstance(s, int))
    return out


def roll_reward(tier: int, save: dict, rng) -> dict:
    """What a drop of this tier gives this player. Never something they already own."""

    def bolts(lo: int, hi: int) -> dict:
        return {"type": "bolts", "amount": rng.randint(lo, hi) // 5 * 5}

    def prisms(lo: int, hi: int) -> dict:
        return {"type": "prisms", "amount": rng.randint(lo, hi)}

    def new_skin() -> dict | None:
        choices = [
            {"type": "skin", "fighter": f, "skin": i}
            for f, count in FIGHTER_SKINS.items() if _unlocked(save, f)
            for i in range(count) if i not in _owned_skins(save, f)
        ]
        return rng.choice(choices) if choices else None

    def new_fighter() -> dict | None:
        locked = [f for f in FIGHTER_SKINS if not _unlocked(save, f)]
        return {"type": "fighter", "fighter": rng.choice(locked)} if locked else None

    name = TIERS[tier][0]
    if name == "SCRAP":
        return bolts(60, 120)
    if name == "TUNED":
        return prisms(15, 25) if rng.randrange(3) == 0 else bolts(180, 300)
    if name == "CHARGED":
        return prisms(35, 50) if rng.randrange(2) == 0 else bolts(400, 600)
    if name == "OVERCLOCKED":
        skin = new_skin() if rng.randrange(2) == 0 else None
        return skin or (prisms(80, 110) if rng.randrange(2) == 0 else bolts(1000, 1300))
    if name == "PRISMATIC":
        return new_fighter() or new_skin() or prisms(250, 300)
    # Ultra, the jackpot: something new to play with (while anything is left) plus a pile of both currencies.
    items = [new_fighter() or new_skin(), prisms(400, 500), bolts(2000, 2500)]
    return {"type": "bundle", "items": [i for i in items if i]}


def apply_reward(save: dict, reward: dict) -> None:
    """Adds a reward to a save (in place), the way the client's `Progression.grant` does."""
    kind = reward.get("type")
    if kind == "bundle":
        for item in reward.get("items", []):
            apply_reward(save, item)
    elif kind == "bolts":
        save["bolts"] = int(save.get("bolts") or 0) + int(reward["amount"])
    elif kind == "prisms":
        save["prisms"] = int(save.get("prisms") or 0) + int(reward["amount"])
    elif kind in ("fighter", "skin"):
        fighters = save.setdefault("fighters", {})
        if not isinstance(fighters, dict):
            fighters = save["fighters"] = {}
        name = reward["fighter"]
        entry = fighters.get(name)
        if not isinstance(entry, dict):
            entry = fighters[name] = {"unlocked": name == STARTING_FIGHTER, "level": 1, "skin": 0, "ownedSkins": [0]}
        if kind == "fighter":
            entry["unlocked"] = True
        else:
            entry["ownedSkins"] = sorted(_owned_skins(save, name) | {int(reward["skin"])})


def open_drop(save: dict, boosted: bool, luck: float, rng) -> dict:
    """Rolls one drop: its tier, how many it split into, and its reward."""
    luck = min(max(float(luck), 0.0), MAX_LUCK)
    tier = roll_tier(rng, luck + (SPLIT_LUCK if boosted else 0.0))
    if boosted and tier == 0:
        tier = 1
    reward = roll_reward(tier, save, rng)
    return {"tier": TIERS[tier][0], "pieces": roll_pieces(rng, luck), "reward": reward}
