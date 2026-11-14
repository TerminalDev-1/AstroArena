"""The rules the server enforces itself rather than taking the client's word for them.

  * how many Cups a match is worth (by trophies.cfg; the client has no copy and shows what it is told)
  * which finishes earn an Arena Box, and how many a day
  * whether a reported result is believable at all
  * what comes out of an Arena Box
"""

from __future__ import annotations

import math

# ---------------------------------------------------------------------------- Cups (removed)

# Cups were removed in build 111, by the user's decision: the game is about fighting the Entity, and nothing pays a
# Cup. (The leaderboard ranks players by their wins: `Store.leaderboard`.) cup_delta stays so that what reports a
# match's worth can still say "0 Cups" to anything that asks.
DIFFICULTIES = ("EASY", "NORMAL", "HARD", "ELITE")


OUTCOMES = ("VICTORY", "DEFEAT", "DRAW")

# Bots in each mode (the names the server hands out): in the Entity's fight, two allies make up a team of three.
MODES = {"BOSS": 2, "TRAINING": 0}


def cup_delta(mode: str, outcome: str, placement: int, cups: int, mvp: bool, table: dict | None = None) -> int:
    """Cups were removed: no match changes them."""
    return 0


# ---------------------------------------------------------------------------- Arena Boxes ("drops" in the code and the API): earning

# Arena Boxes were removed in 1.3 (build 110), by the owner's decision ("they have no use in the game"): every box a
# player held was wiped (Store wipes them on start), nothing pays one any more, and none can be opened. The counter is
# still in the database and the API (always 0) so older code reading it doesn't break. The roll tables further down
# are kept with nothing using them.
DROPS_PER_DAY = 0
STARTING_DROPS = 0
MAX_IMPORTED_DROPS = 0


def earns_drop(mode: str, outcome: str, placement: int) -> bool:
    """Nothing earns an Arena Box any more: they were removed in 1.3."""
    return False


# ---------------------------------------------------------------------------- is a result believable?

# Nobody wins a real match faster than this, and a match left open this long is abandoned.
MIN_GOOD_RESULT_SECONDS = 20.0
MAX_MATCH_SECONDS = 3600.0
_MAX_KOS = {"BOSS": 80, "DUEL": 40, "TRAINING": 100000}
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
    good = outcome == "VICTORY"
    # (A 1v1 is won the moment the other player walks out, and it is the lobby that says so, not the device.)
    if good and mode not in ("TRAINING", "DUEL") and elapsed < MIN_GOOD_RESULT_SECONDS:
        return "finished faster than a match can be played"
    return None


# ---------------------------------------------------------------------------- Arena Boxes: opening

TIERS = [("SCRAP", 40), ("TUNED", 28), ("CHARGED", 18), ("OVERCLOCKED", 8), ("PRISMATIC", 4), ("ULTRA", 2)]
MAX_LUCK = 14.0
# An Arena Box holds this many items, and now and then more: after these, each further item comes with
# `MORE_ITEMS_CHANCE` (plus `MORE_ITEMS_LUCK` for each point of luck), up to `MAX_ITEMS`.
BOX_ITEMS = 3
MAX_ITEMS = 8
MORE_ITEMS_CHANCE = 0.4
MORE_ITEMS_LUCK = 0.05
# Every Bolt and Prism amount a drop gives is multiplied by this (3 = the amounts below, plus 200%).
DROP_BUFF = 3
# Every Credit amount a drop gives is multiplied by this: the Spark Road asks for thousands, and drops are the
# way to get them in any number.
CREDIT_BUFF = 12
# "Open all" opens the boxes the player holds at that moment. This is the most one request goes through.
MAX_OPEN_ALL = 10000

# Every fighter and how many colourways it has (index 0 is the one it comes with). Keep in step with Balance.kt.
FIGHTER_SKINS = {name: 3 for name in ("BYTE", "BRAKK", "KIRA", "KITO", "BUDDY")}
STARTING_FIGHTER = "BYTE"


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


def roll_items(rng, luck: float = 0.0) -> int:
    """How many items an Arena Box holds: `BOX_ITEMS`, and with some luck a few more."""
    items = BOX_ITEMS
    while items < MAX_ITEMS and rng.random() < min(1.0, MORE_ITEMS_CHANCE + MORE_ITEMS_LUCK * luck):
        items += 1
    return items


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
        return {"type": "bolts", "amount": rng.randint(lo, hi) // 5 * 5 * DROP_BUFF}

    def prisms(lo: int, hi: int) -> dict:
        return {"type": "prisms", "amount": rng.randint(lo, hi) * DROP_BUFF}

    def new_skin() -> dict | None:
        choices = [
            {"type": "skin", "fighter": f, "skin": i}
            for f, count in FIGHTER_SKINS.items() if _unlocked(save, f)
            for i in range(count) if i not in _owned_skins(save, f)
        ]
        return rng.choice(choices) if choices else None

    def credits(lo: int, hi: int) -> dict:
        # Credits unlock fighters on the Spark Road. (Drops don't hand out fighters themselves.)
        return {"type": "credits", "amount": rng.randint(lo, hi) * CREDIT_BUFF}

    name = TIERS[tier][0]
    if name == "SCRAP":
        return bolts(60, 120)
    if name == "TUNED":
        pick = rng.randrange(4)
        return prisms(15, 25) if pick == 0 else credits(8, 14) if pick == 1 else bolts(180, 300)
    if name == "CHARGED":
        pick = rng.randrange(3)
        return prisms(35, 50) if pick == 0 else credits(20, 30) if pick == 1 else bolts(400, 600)
    if name == "OVERCLOCKED":
        skin = new_skin() if rng.randrange(2) == 0 else None
        pick = rng.randrange(3)
        return skin or (prisms(80, 110) if pick == 0 else credits(45, 60) if pick == 1 else bolts(1000, 1300))
    if name == "PRISMATIC":
        return credits(120, 160) if rng.randrange(2) == 0 else (new_skin() or prisms(250, 300))
    # Ultra, the jackpot. One item is one thing, so it is a single, very big pile.
    pick = rng.randrange(3)
    return credits(500, 600) if pick == 0 else prisms(800, 1000) if pick == 1 else bolts(4000, 5000)


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
    elif kind == "credits":
        save["credits"] = int(save.get("credits") or 0) + int(reward["amount"])
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


def open_box(save: dict, luck: float, rng, grant=apply_reward) -> dict:
    """Opens one Arena Box: rolls how many items it holds, then each item's tier and reward.

    Every item is handed to `grant(save, reward)` before the next is rolled, so a box never holds the same
    colourway twice; what `grant` returns (if anything) is the reward as it is reported.
    """
    luck = min(max(float(luck), 0.0), MAX_LUCK)
    items = []
    for _ in range(roll_items(rng, luck)):
        tier = roll_tier(rng, luck)
        reward = roll_reward(tier, save, rng)
        items.append({"tier": TIERS[tier][0], "reward": grant(save, reward) or reward})
    return {"items": items}
