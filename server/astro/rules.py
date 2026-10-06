"""The rules the server enforces itself rather than taking the client's word for them.

  * how many Cups a match is worth (by trophies.cfg; the client has no copy and shows what it is told)
  * which finishes earn a Spark Drop, and how many a day
  * whether a reported result is believable at all
  * what comes out of a Spark Drop
"""

from __future__ import annotations

import math

# ---------------------------------------------------------------------------- Cups

# Cups don't depend on how hard the bots are. What each mode pays is the operator's to set, in trophies.cfg
# (`Config.cups`); these are the numbers for whatever that file leaves out.
#   places      Cups by finishing place, first to last. A mode that has them is paid by place and nothing else.
#   win, draw   Cups for a victory and for a draw; mvp_bonus is added to a victory as the match's MVP.
#   max_loss    the most a defeat costs. With loss_step, a defeat costs 1 Cup for every loss_step Cups the
#               player has, up to max_loss, so new players lose nothing.
DEFAULT_CUPS = {
    "LAST_SPARK": {"places": [25, 22, 20, 17, 14, 12, 7, 3, 0, 0]},
    "KNOCKOUT_RUSH": {"win": 8, "mvp_bonus": 2, "draw": 1, "max_loss": 6, "loss_step": 80},
    "BOSS": {"win": 5},
    # 1v1, against another real player (the lobby replays it and says who won: duel.py).
    "DUEL": {"win": 8, "draw": 0, "max_loss": 6, "loss_step": 80},
}
# The Training Area is never played for Cups (nothing referees it), whatever trophies.cfg says.
CUP_MODES = tuple(DEFAULT_CUPS)
DIFFICULTIES = ("EASY", "NORMAL", "HARD", "ELITE")

# A fighter has Cups of its own (won and lost while playing it) and a rank that follows them: rank 1 starts at
# the first number here, rank 2 at the second, and so on. There is no top rank: past the end of the list every
# further rank is another FIGHTER_RANK_STEP Cups, for as long as the Cups keep coming.
FIGHTER_RANK_CUPS = [0, 10, 20, 35, 50, 75, 100, 140, 180, 230, 280, 340, 400, 470, 540, 620, 700, 790, 880, 1000]
FIGHTER_RANK_STEP = 150


def fighter_rank(cups: int) -> int:
    if cups >= FIGHTER_RANK_CUPS[-1]:
        return len(FIGHTER_RANK_CUPS) + (cups - FIGHTER_RANK_CUPS[-1]) // FIGHTER_RANK_STEP
    return max(1, sum(1 for start in FIGHTER_RANK_CUPS if start <= cups))


OUTCOMES = ("VICTORY", "DEFEAT", "DRAW")

# The bosses of Boss Mode. A player may ask for one; otherwise the match's seed picks. Keep in step with BossKind.
BOSSES = ("BARRAGE", "SWEEPER", "STAMPEDE")

# Bots in each mode (the names the server hands out).
MODES = {"LAST_SPARK": 9, "KNOCKOUT_RUSH": 5, "BOSS": 0, "TRAINING": 0}


def cup_delta(mode: str, outcome: str, placement: int, cups: int, mvp: bool, table: dict | None = None) -> int:
    """How a match changes a player's Cups, by `table` (trophies.cfg). Never takes them below zero."""
    pays = (DEFAULT_CUPS if table is None else table).get(mode) if mode in CUP_MODES else None
    if not pays:
        return 0
    places = pays.get("places")
    if places:
        delta = places[min(max(placement - 1, 0), len(places) - 1)]
    elif outcome == "VICTORY":
        delta = pays.get("win", 0) + (pays.get("mvp_bonus", 0) if mvp else 0)
    elif outcome == "DRAW":
        delta = pays.get("draw", 0)
    else:
        loss, step = pays.get("max_loss", 0), pays.get("loss_step", 0)
        delta = -(min(loss, cups // step) if step > 0 else loss)
    return max(delta, -cups)


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
_MAX_KOS = {"LAST_SPARK": 9, "KNOCKOUT_RUSH": 40, "BOSS": 80, "DUEL": 40, "TRAINING": 100000}
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
    # (A 1v1 is won the moment the other player walks out, and it is the lobby that says so, not the device.)
    if good and mode not in ("TRAINING", "DUEL") and elapsed < MIN_GOOD_RESULT_SECONDS:
        return "finished faster than a match can be played"
    return None


# ---------------------------------------------------------------------------- Spark Drops: opening

TIERS = [("SCRAP", 40), ("TUNED", 28), ("CHARGED", 18), ("OVERCLOCKED", 8), ("PRISMATIC", 4), ("ULTRA", 2)]
MAX_LUCK = 14.0
MAX_PIECES = 8
# The pieces a drop splits into roll with this much extra luck and are never Scrap.
SPLIT_LUCK = 0.6
# Every Bolt and Prism amount a drop gives is multiplied by this (3 = the amounts below, plus 200%).
DROP_BUFF = 3
# "Open all" opens the drops the player holds at that moment; the pieces that split off wait for the next one.
# (Chasing the splits has no end when luck makes every drop split.) This is the most one request goes through.
MAX_OPEN_ALL = 10000

# Every fighter and how many colourways it has (index 0 is the one it comes with). Keep in step with Balance.kt.
FIGHTER_SKINS = {name: 3 for name in ("JUNO", "BRAKK", "MIRA", "KITO", "VARUN")}
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
        # Credits unlock fighters on the Spark Road. (Drops don't hand out fighters themselves.) The buff leaves
        # them alone: they are worth what the road charges.
        return {"type": "credits", "amount": rng.randint(lo, hi)}

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
    # Ultra, the jackpot: a colourway (while any is left) plus a pile of every currency.
    items = [new_skin(), credits(250, 300), prisms(400, 500), bolts(2000, 2500)]
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
    elif kind == "credits":
        save["credits"] = int(save.get("credits") or 0) + int(reward["amount"])
    elif kind == "glory":
        save["glory"] = int(save.get("glory") or 0) + int(reward["amount"])
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
