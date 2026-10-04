"""The economy, kept by the server: Bolts, Prisms, what a player owns and what things cost.

A player's *profile* is the part of their progress the server is in charge of:

    {"bolts", "prisms", "credits", "glory", "bestCups", "fighters": {ID: {"unlocked", "level", "ownedSkins"}},
     "claimedMilestones": [cups, ...], "lastDailyGiftDay", "lastFirstWinDay",
     "pass": {"season", "points", "claimed": [tier, ...]}}

Players see Bolts as "Power Ups" and Prisms as "Crystals". Credits are progress along the Spark Road toward the
next fighter on it; once every fighter is unlocked they are earned as Glory instead.

The keys are the ones the game's save file uses, so a profile can be started from a save and sent back to the
game as it is. Every number here used to live in the client's `Balance.kt` / `Catalog.kt`; the client still has
copies of the prices for showing them, so keep the two in step.

Rewards are small dicts: {"type": "bolts"|"prisms"|"credits"|"glory", "amount"}, {"type": "fighter", "fighter"},
{"type": "skin", "fighter", "skin"}, {"type": "bundle", "items": [...]}.
"""

from __future__ import annotations

import math
import random

from . import rules

STARTING_BOLTS = 60
STARTING_PRISMS = 0

# ---------------------------------------------------------------------------- upgrades

MAX_LEVEL = 10
LEVEL_LIMIT = 9999
# Bolts to go from level (index + 1) to the next. Past the table (developers with the cap off) it climbs by a step.
UPGRADE_COST = [10, 20, 35, 60, 95, 145, 210, 290, 400]
UPGRADE_COST_STEP = 50
MAX_COST_FACTOR = 3.0


def upgrade_cost(level: int, factor: float = 1.0) -> int:
    level = max(1, level)
    base = UPGRADE_COST[level - 1] if level <= len(UPGRADE_COST) else UPGRADE_COST[-1] + UPGRADE_COST_STEP * (level - len(UPGRADE_COST))
    return _round(base * min(max(factor, 0.0), MAX_COST_FACTOR))


def _round(x: float) -> int:
    """Rounds halves up, as the client's Math.round does."""
    return math.floor(x + 0.5)


# ---------------------------------------------------------------------------- the shop

# What a fighter is: its rarity. That sets what it costs in the shop (Prisms) and on the Spark Road (Credits).
# The starting fighter has no rarity and isn't sold. Keep in step with Balance.kt.
FIGHTER_RARITY = {"BRAKK": "RARE", "MIRA": "EPIC", "KITO": "MYTHIC"}
# Fighters that were in the game for a few builds and were taken out again, with the Credits each took on the
# Spark Road. A profile that still holds one loses it and gets those Credits back.
REMOVED_FIGHTERS = {"PIP": 160, "DOZER": 160, "NOVA": 420, "FENN": 420, "VOLT": 900, "ONYX": 900, "AURA": 1600, "ZERO": 2600}
RARITY_PRICE = {"RARE": 40, "EPIC": 70, "MYTHIC": 90, "LEGENDARY": 160, "ULTRA": 250}
FIGHTER_PRICE = {name: RARITY_PRICE[rarity] for name, rarity in FIGHTER_RARITY.items()}
SKIN_PRICE = 20  # Prisms, for every colourway but a fighter's first
BOLT_CRATES = {"crate_s": (400, 10), "crate_m": (1200, 25), "crate_l": (3000, 50)}  # key: (Bolts, price in Prisms)
CREDIT_PACKS = {"credits_s": (60, 15), "credits_m": (200, 45), "credits_l": (500, 100)}  # key: (Credits, price in Prisms)
CURRENCIES = ("FREE", "BOLTS", "PRISMS")


def shop_item(key: str) -> tuple[dict, int] | None:
    """The reward and Prism price of a standing shop item, or None if there is no such item."""
    if key in CREDIT_PACKS:
        credits, price = CREDIT_PACKS[key]
        return {"type": "credits", "amount": credits}, price
    if key in BOLT_CRATES:
        bolts, price = BOLT_CRATES[key]
        return {"type": "bolts", "amount": bolts}, price
    if key.startswith("fighter_"):
        fighter = key[len("fighter_"):]
        if fighter in FIGHTER_PRICE:
            return {"type": "fighter", "fighter": fighter}, FIGHTER_PRICE[fighter]
    if key.startswith("skin_"):
        fighter, _, index = key[len("skin_"):].rpartition("_")
        if fighter in rules.FIGHTER_SKINS and index.isdigit() and 1 <= int(index) < rules.FIGHTER_SKINS[fighter]:
            return {"type": "skin", "fighter": fighter, "skin": int(index)}, SKIN_PRICE
    return None


def daily_gift(day: int) -> dict:
    """The free daily gift alternates by calendar day, so it is predictable."""
    return {"type": "bolts", "amount": 40} if day % 2 == 0 else {"type": "prisms", "amount": 8}


# ---------------------------------------------------------------------------- match pay

BOLT_MULTIPLIER = {"EASY": 0.75, "NORMAL": 1.0, "HARD": 1.25, "ELITE": 1.5}
PLACEMENT_BOLTS = [30, 26, 22, 18, 15, 12, 10, 8, 6, 5]
OUTCOME_BOLTS = {"VICTORY": 24, "DRAW": 14, "DEFEAT": 10}
FIRST_WIN_PRISMS = 10


def match_bolts(mode: str, outcome: str, placement: int, kos: int, difficulty: str) -> int:
    if mode == "TRAINING":
        return 0
    multiplier = BOLT_MULTIPLIER.get(difficulty, 1.0)
    ko_bonus = 2 * min(max(kos, 0), 6)
    if mode == "LAST_SPARK":
        base = PLACEMENT_BOLTS[min(max(placement - 1, 0), len(PLACEMENT_BOLTS) - 1)]
    else:
        base = OUTCOME_BOLTS.get(outcome, 10)
    return _round((base + ko_bonus) * multiplier)


def first_win_prisms(mode: str, outcome: str, profile: dict, day: int) -> int:
    """Prisms for the first victory of the day. Boss Mode and the Training Area don't count."""
    if mode in ("BOSS", "TRAINING") or outcome != "VICTORY" or profile.get("lastFirstWinDay") == day:
        return 0
    return FIRST_WIN_PRISMS


# ---------------------------------------------------------------------------- the Cup Track

def _b(n): return {"type": "bolts", "amount": n}
def _p(n): return {"type": "prisms", "amount": n}
def _c(n): return {"type": "credits", "amount": n}
def _f(name): return {"type": "fighter", "fighter": name}
def _s(name, i): return {"type": "skin", "fighter": name, "skin": i}


CUP_TRACK = {
    10: _b(40), 25: _p(10), 40: _b(75), 60: _s("JUNO", 1), 80: _p(20), 100: _c(80), 130: _b(150), 160: _p(25),
    200: _s("BRAKK", 1), 250: _b(250), 300: _p(40), 350: _c(200), 420: _b(400), 500: _s("MIRA", 1), 600: _p(60),
    700: _b(600), 850: _s("JUNO", 2), 1000: _p(100), 1200: _c(400), 1500: _s("KITO", 1),
}


# ---------------------------------------------------------------------------- Credits, the Spark Road and Glory

# Credits are not a wallet: whatever is earned goes straight into the Spark Road, toward the next fighter along
# it. When that fighter's cost is covered it is theirs to claim, and what is left over stays on the road.
# Fighters have a rarity; a rarer one takes more Credits. (Fighters are also sold in the shop for Prisms.)
RARITIES = ["RARE", "EPIC", "MYTHIC", "LEGENDARY", "ULTRA"]
ROAD_COST = {"RARE": 160, "EPIC": 420, "MYTHIC": 900, "LEGENDARY": 1600, "ULTRA": 2600}
# The road: every fighter in the order it is unlocked (the cheapest rarity first) with what it costs.
SPARK_ROAD = sorted(((name, ROAD_COST[rarity]) for name, rarity in FIGHTER_RARITY.items()), key=lambda step: step[1])  # stable: the order above within a rarity


def _locked(profile: dict) -> list[tuple[str, int]]:
    return [(name, cost) for name, cost in SPARK_ROAD if not profile["fighters"].get(name, {}).get("unlocked")]


def road_next(profile: dict) -> tuple[str, int] | None:
    """The fighter the Credits are filling and what it costs, or None when every one is unlocked. The road has a
    fixed order: the first fighter along it that is still locked."""
    locked = _locked(profile)
    return locked[0] if locked else None


def road_unlock(profile: dict) -> dict:
    """Claims the fighter the Credits have been filling, once they cover it, and returns the reward."""
    step = road_next(profile)
    if step is None:
        raise Refused(409, "the Spark Road is finished")
    name, cost = step
    if profile["credits"] < cost:
        raise Refused(402, "not enough Credits")
    profile["credits"] -= cost
    reward = grant(profile, _f(name))
    # The road has just been finished: Credits left on it have nowhere to go, so they become Glory.
    if road_next(profile) is None and profile["credits"] > 0:
        profile["glory"] += profile["credits"]
        profile["credits"] = 0
    return reward


def match_credits(mode: str, outcome: str, placement: int) -> int:
    """Credits for playing a match: a few for turning up, more for a good finish."""
    if mode == "TRAINING":
        return 0
    good = outcome == "VICTORY" or (mode == "LAST_SPARK" and 1 <= placement <= 4)
    if mode == "BOSS":
        return 3 if good else 1
    return 6 if good else 2


# ---------------------------------------------------------------------------- the Spark Pass

# A season of tiers. Playing earns pass points; every PASS_TIER_POINTS of them reaches the next tier, and each
# tier has a reward to claim, most of them Credits. A new season starts everyone from nothing.
PASS_SEASON_DAYS = 28
PASS_TIER_POINTS = 100
PASS_TIERS = 30


def pass_reward(tier: int) -> dict:
    """What tier `tier` (1-based) gives."""
    if tier % 10 == 0:
        return _c(150)
    if tier % 5 == 0:
        return _p(30)
    if tier % 2 == 1:
        return _c(30 + 10 * (tier // 10))
    return _b(200 + 20 * tier)


def pass_points(mode: str, outcome: str, placement: int) -> int:
    if mode == "TRAINING":
        return 0
    good = outcome == "VICTORY" or (mode == "LAST_SPARK" and 1 <= placement <= 4)
    if mode == "BOSS":
        return 20 if good else 8
    return 40 if good else 15


def pass_season(day: int) -> int:
    return day // PASS_SEASON_DAYS


def pass_view(profile: dict, day: int) -> dict:
    """The player's pass this season, without changing the profile: a pass from an earlier season counts as new."""
    state = profile.get("pass")
    if not isinstance(state, dict) or state.get("season") != pass_season(day):
        return {"season": pass_season(day), "points": 0, "claimed": []}
    return state


def add_pass_points(profile: dict, points: int, day: int) -> None:
    state = dict(pass_view(profile, day))
    state["points"] = min(state["points"] + max(points, 0), PASS_TIERS * PASS_TIER_POINTS)
    profile["pass"] = state


def claim_pass(profile: dict, tier: int, day: int) -> dict:
    state = dict(pass_view(profile, day))
    if not 1 <= tier <= PASS_TIERS:
        raise Refused(404, "no such Spark Pass tier")
    if state["points"] < tier * PASS_TIER_POINTS:
        raise Refused(409, "not reached yet")
    if tier in state["claimed"]:
        raise Refused(409, "already claimed")
    state["claimed"] = sorted(state["claimed"] + [tier])
    profile["pass"] = state
    return grant(profile, pass_reward(tier))


# ---------------------------------------------------------------------------- profiles

def new_profile() -> dict:
    return {
        "bolts": STARTING_BOLTS, "prisms": STARTING_PRISMS, "credits": 0, "glory": 0, "bestCups": 0,
        "fighters": {name: {"unlocked": name == rules.STARTING_FIGHTER, "level": 1, "ownedSkins": [0]} for name in rules.FIGHTER_SKINS},
        "claimedMilestones": [], "lastDailyGiftDay": -1, "lastFirstWinDay": -1,
    }


def complete(profile: dict) -> dict:
    """Fills in what a profile stored by an older server doesn't have yet."""
    profile.setdefault("credits", 0)
    profile.setdefault("glory", 0)
    profile.pop("roadTarget", None)  # the road was briefly pick-your-own
    for name, cost in REMOVED_FIGHTERS.items():
        entry = profile["fighters"].pop(name, None)
        if isinstance(entry, dict) and entry.get("unlocked"):
            # Back onto the road if there is still road left; Glory otherwise.
            profile["credits" if road_next(profile) is not None else "glory"] += cost
    return profile


def _int(value, default=0, low=0, high=2_000_000_000) -> int:
    try:
        return min(max(int(value), low), high)
    except (TypeError, ValueError):
        return default


def profile_from_save(save: dict) -> dict:
    """Starts a profile from a save made before the server kept the economy, so that progress carries over."""
    profile = new_profile()
    profile["bolts"] = _int(save.get("bolts"), STARTING_BOLTS)
    profile["prisms"] = _int(save.get("prisms"), STARTING_PRISMS)
    profile["bestCups"] = _int(save.get("bestCups"))
    profile["lastDailyGiftDay"] = _int(save.get("lastDailyGiftDay"), -1, low=-1)
    profile["lastFirstWinDay"] = _int(save.get("lastFirstWinDay"), -1, low=-1)
    claimed = save.get("claimedMilestones")
    if isinstance(claimed, list):
        profile["claimedMilestones"] = sorted({c for c in claimed if isinstance(c, int) and c in CUP_TRACK})
    fighters = save.get("fighters")
    if isinstance(fighters, dict):
        for name, count in rules.FIGHTER_SKINS.items():
            entry = fighters.get(name)
            if not isinstance(entry, dict):
                continue
            skins = entry.get("ownedSkins")
            owned = {0} | ({s for s in skins if isinstance(s, int) and 0 <= s < count} if isinstance(skins, list) else set())
            profile["fighters"][name] = {
                "unlocked": bool(entry.get("unlocked")) or name == rules.STARTING_FIGHTER,
                "level": _int(entry.get("level"), 1, low=1, high=LEVEL_LIMIT),
                "ownedSkins": sorted(owned),
            }
    return profile


def owns(profile: dict, reward: dict) -> bool:
    kind = reward.get("type")
    entry = profile["fighters"].get(reward.get("fighter"), {})
    if kind == "fighter":
        return bool(entry.get("unlocked"))
    if kind == "skin":
        return reward.get("skin") in entry.get("ownedSkins", [])
    return False


def compensation(reward: dict) -> dict:
    """What is paid out instead of something the player already owns."""
    kind = reward.get("type")
    if kind == "fighter":
        return _b(300)
    if kind == "skin":
        return _p(30)
    if kind == "bundle":
        return {"type": "bundle", "items": [compensation(i) for i in reward.get("items", [])]}
    return reward


def grant(profile: dict, reward: dict) -> dict:
    """Adds a reward to a profile (in place) and returns what was actually given: anything already owned is paid out instead."""
    if reward.get("type") == "bundle":
        return {"type": "bundle", "items": [grant(profile, item) for item in reward.get("items", [])]}
    actual = compensation(reward) if owns(profile, reward) else reward
    # With every fighter unlocked the road is finished, and Credits earned from then on are Glory: a rank to
    # climb for its own sake, which buys nothing.
    if actual.get("type") == "credits" and road_next(profile) is None:
        actual = {"type": "glory", "amount": int(actual["amount"])}
    rules.apply_reward(profile, actual)
    return actual


class Refused(Exception):
    """A request the rules don't allow. `status` is the HTTP status the client is told."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def upgrade(profile: dict, fighter: str, factor: float = 1.0, no_cap: bool = False) -> int:
    """Levels a fighter up and returns what it cost."""
    entry = profile["fighters"].get(fighter)
    if entry is None or not entry.get("unlocked"):
        raise Refused(409, "that fighter isn't unlocked")
    level = entry["level"]
    if level >= (LEVEL_LIMIT if no_cap else MAX_LEVEL):
        raise Refused(409, "that fighter is at the top level")
    cost = upgrade_cost(level, factor)
    if profile["bolts"] < cost:
        raise Refused(402, "not enough Power Ups")
    profile["bolts"] -= cost
    entry["level"] = level + 1
    return cost


def buy(profile: dict, key: str) -> dict:
    """Buys a standing shop item with Prisms and returns the reward."""
    item = shop_item(key)
    if item is None:
        raise Refused(404, "no such shop item")
    reward, price = item
    if owns(profile, reward):
        raise Refused(409, "already owned")
    if reward["type"] == "skin" and not profile["fighters"][reward["fighter"]]["unlocked"]:
        raise Refused(409, "unlock the fighter first")
    if profile["prisms"] < price:
        raise Refused(402, "not enough Crystals")
    profile["prisms"] -= price
    return grant(profile, reward)


def claim_gift(profile: dict, day: int) -> dict:
    if profile.get("lastDailyGiftDay") == day:
        raise Refused(409, "today's gift is already claimed")
    profile["lastDailyGiftDay"] = day
    return grant(profile, daily_gift(day))


def claim_milestone(profile: dict, cups: int) -> dict:
    reward = CUP_TRACK.get(cups)
    if reward is None:
        raise Refused(404, "no such Cup Track reward")
    if cups > profile["bestCups"]:
        raise Refused(409, "not reached yet")
    if cups in profile["claimedMilestones"]:
        raise Refused(409, "already claimed")
    profile["claimedMilestones"] = sorted(profile["claimedMilestones"] + [cups])
    return grant(profile, reward)


# ---------------------------------------------------------------------------- deals (offers made by developers)

def deal_reward(deal: dict) -> dict:
    items = []
    if deal["bolts"] > 0:
        items.append(_b(deal["bolts"]))
    if deal["prisms"] > 0:
        items.append(_p(deal["prisms"]))
    if deal["fighter"]:
        items.append(_f(deal["fighter"]))
    if deal["skinFighter"]:
        items.append(_s(deal["skinFighter"], deal["skinIndex"]))
    return items[0] if len(items) == 1 else {"type": "bundle", "items": items}


def clean_deal(data: dict) -> dict:
    """Checks a deal a developer sent and returns it in the stored shape."""
    def fighter(value):
        return value if value in rules.FIGHTER_SKINS else ""

    deal = {
        "title": "".join(ch for ch in str(data.get("title") or "Offer") if ch.isprintable())[:24].strip() or "Offer",
        "bolts": _int(data.get("bolts"), high=1_000_000),
        "prisms": _int(data.get("prisms"), high=1_000_000),
        "fighter": fighter(data.get("fighter")),
        "skinFighter": fighter(data.get("skinFighter")),
        "skinIndex": _int(data.get("skinIndex")),
        "currency": data.get("currency") if data.get("currency") in CURRENCIES else "PRISMS",
        "price": _int(data.get("price"), high=1_000_000),
        "wasPrice": _int(data.get("wasPrice"), high=1_000_000),
        "expiresAt": _int(data.get("expiresAt"), high=10**15),
        "limit": _int(data.get("limit"), 1, high=1000),
        "theme": _int(data.get("theme"), high=50),
    }
    if deal["skinFighter"] and not 0 <= deal["skinIndex"] < rules.FIGHTER_SKINS[deal["skinFighter"]]:
        raise Refused(400, "no such colourway")
    if not (deal["bolts"] or deal["prisms"] or deal["fighter"] or deal["skinFighter"]):
        raise Refused(400, "a deal has to contain something")
    return deal


def buy_deal(profile: dict, deal: dict, purchased: int, now_ms: int) -> dict:
    """Pays for a deal and returns the reward. `purchased` is how many times this player has bought it."""
    if 0 < deal["expiresAt"] <= now_ms:
        raise Refused(409, "this deal has ended")
    if 0 < deal["limit"] <= purchased:
        raise Refused(409, "sold out")
    wallet = {"BOLTS": "bolts", "PRISMS": "prisms"}.get(deal["currency"])
    if wallet:
        if profile[wallet] < deal["price"]:
            raise Refused(402, "not enough " + ("Power Ups" if wallet == "bolts" else "Crystals"))
        profile[wallet] -= deal["price"]
    return grant(profile, deal_reward(deal))


# ---------------------------------------------------------------------------- daily offers

def daily_offers(pool: list[dict], count: int, day: int) -> list[dict]:
    """The day's offers: `count` of the pool, the same for every player, different each day.

    Entries the operator got wrong (an unknown colourway, nothing in them) are left out rather than breaking the shop.
    """
    good = []
    for entry in pool:
        try:
            good.append({**clean_deal(entry), "limit": 1, "expiresAt": 0})
        except Refused:
            continue
    picker = random.Random(day * 7919 + 17)
    return picker.sample(good, min(max(count, 0), len(good)))


def buy_daily(profile: dict, offers: list[dict], index: int, day: int) -> dict:
    """Buys today's offer number `index`, once. What a player has bought today is kept in their profile."""
    if not 0 <= index < len(offers):
        raise Refused(404, "no such offer today")
    offer = offers[index]
    if profile.get("dailyDay") != day:
        profile["dailyDay"], profile["dailyBought"] = day, []
    if offer["title"] in profile["dailyBought"]:
        raise Refused(409, "already bought today")
    # An offer for something already owned would just pay out; say no instead.
    reward = deal_reward(offer)
    if any(owns(profile, item) for item in reward.get("items", [reward])):
        raise Refused(409, "you already own what this offer gives")
    granted = buy_deal(profile, offer, 0, 0)
    profile["dailyBought"] = profile["dailyBought"] + [offer["title"]]
    return granted


def bought_today(profile: dict, title: str, day: int) -> bool:
    return profile.get("dailyDay") == day and title in profile.get("dailyBought", [])
