"""Run with:  python -m unittest  (from the server directory)"""

import json
import os
import random
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from astro import economy, rules
from astro.economy import Refused
from astro.app import serve
from astro.config import matches, parse_version

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "9"


class VersionRules(unittest.TestCase):
    def test_parsing(self):
        self.assertEqual(parse_version("0.5.1-preview"), (0, 5, 1))
        self.assertEqual(parse_version("v0.4.2-preview"), (0, 4, 2))
        self.assertEqual(parse_version("6"), (6, 0, 0))
        self.assertEqual(parse_version("v6"), (6, 0, 0))
        self.assertIsNone(parse_version("latest"))

    def test_rules(self):
        self.assertTrue(matches("<6", "0.5.1-preview"))
        self.assertFalse(matches("<6", "6"))
        self.assertFalse(matches("<6", "7"))
        self.assertTrue(matches(">=6", "v6"))
        self.assertTrue(matches("0.4.2-preview", "0.4.2-preview"))
        self.assertFalse(matches("0.4.2-preview", "0.4.3-preview"))
        self.assertTrue(matches("*", "anything"))
        self.assertFalse(matches("<6", "garbage"))


class Rules(unittest.TestCase):
    def test_cups_match_the_client_table(self):
        # Knockout Rush: a win pays the difficulty's bonus (+2 for MVP), a loss costs more as Cups grow.
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "VICTORY", 0, 0, "NORMAL", False), 8)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "VICTORY", 0, 0, "EASY", True), 8)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "DRAW", 0, 0, "ELITE", False), 1)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "DEFEAT", 0, 3, "NORMAL", False), 0)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "DEFEAT", 0, 500, "NORMAL", False), -6)
        # Last Spark: by placement, scaled by difficulty when positive; beginners lose nothing.
        self.assertEqual(rules.cup_delta("LAST_SPARK", "VICTORY", 1, 100, "NORMAL", False), 10)
        self.assertEqual(rules.cup_delta("LAST_SPARK", "VICTORY", 1, 100, "EASY", False), 8)
        self.assertEqual(rules.cup_delta("LAST_SPARK", "DEFEAT", 10, 10, "NORMAL", False), 0)
        self.assertEqual(rules.cup_delta("LAST_SPARK", "DEFEAT", 10, 100, "NORMAL", False), -4)
        self.assertEqual(rules.cup_delta("BOSS", "VICTORY", 0, 100, "ELITE", True), 0)

    def test_unbelievable_results_are_caught(self):
        ok = dict(outcome="VICTORY", placement=1, kos=4, deaths=0, damage=9000)
        self.assertIsNone(rules.check_result("LAST_SPARK", 90, **ok))
        self.assertIsNotNone(rules.check_result("LAST_SPARK", 3, **ok))  # nobody wins in three seconds
        self.assertIsNotNone(rules.check_result("LAST_SPARK", 5000, **ok))  # left open for over an hour
        self.assertIsNotNone(rules.check_result("LAST_SPARK", 90, **{**ok, "kos": 10}))  # only nine opponents
        self.assertIsNotNone(rules.check_result("LAST_SPARK", 90, **{**ok, "placement": 0}))
        self.assertIsNotNone(rules.check_result("LAST_SPARK", 90, **{**ok, "kos": 0, "damage": 0}))
        self.assertIsNotNone(rules.check_result("KNOCKOUT_RUSH", 90, **{**ok, "outcome": "WON"}))
        self.assertIsNotNone(rules.check_result("KNOCKOUT_RUSH", 90, **{**ok, "damage": -1}))
        # Losing quickly is believable.
        self.assertIsNone(rules.check_result("LAST_SPARK", 6, outcome="DEFEAT", placement=10, kos=0, deaths=1, damage=0))

    def test_drop_odds_and_luck(self):
        self.assertAlmostEqual(sum(rules.odds()), 1.0)
        self.assertAlmostEqual(rules.odds()[0], 0.4)
        self.assertGreater(rules.odds(5.0)[5], rules.odds()[5] * 20)
        rng = random.Random(7)
        self.assertTrue(all(rules.roll_pieces(rng) in (1, 2, 4, 8) for _ in range(500)))
        # At full luck a drop nearly always splits, and one that splits always goes all the way to eight.
        self.assertEqual({rules.roll_pieces(rng, rules.MAX_LUCK) for _ in range(200)}, {1, 8})

    def test_drops_never_give_what_is_owned(self):
        rng = random.Random(11)
        save = {"bolts": 0, "prisms": 0, "fighters": {"JUNO": {"unlocked": True, "level": 1, "skin": 0, "ownedSkins": [0]}}}
        seen = set()
        for _ in range(400):
            result = rules.open_drop(save, boosted=False, luck=4.0, rng=rng)
            reward = result["reward"]
            for item in reward.get("items", [reward]):
                key = (item["type"], item.get("fighter"), item.get("skin"))
                if item["type"] in ("fighter", "skin"):
                    self.assertNotIn(key, seen)
                    seen.add(key)
            rules.apply_reward(save, reward)
        # Everything there is to own was handed out exactly once, and only currency after that.
        self.assertEqual(len([k for k in seen if k[0] == "fighter"]), len(rules.FIGHTER_SKINS) - 1)
        self.assertEqual(len([k for k in seen if k[0] == "skin"]), sum(rules.FIGHTER_SKINS.values()) - len(rules.FIGHTER_SKINS))
        self.assertTrue(all(f["unlocked"] for f in save["fighters"].values()))
        self.assertGreater(save["bolts"], 0)

    def test_split_pieces_are_never_scrap(self):
        rng = random.Random(3)
        tiers = {rules.open_drop({}, boosted=True, luck=0, rng=rng)["tier"] for _ in range(300)}
        self.assertNotIn("SCRAP", tiers)


class Economy(unittest.TestCase):
    def test_prices_match_the_client(self):
        self.assertEqual([economy.upgrade_cost(level) for level in (1, 5, 9, 10, 11)], [10, 95, 400, 450, 500])
        self.assertEqual(economy.upgrade_cost(3, 0.0), 0)
        self.assertEqual(economy.upgrade_cost(3, 99), 105)  # the factor is capped at x3
        self.assertEqual(economy.shop_item("crate_l"), ({"type": "bolts", "amount": 3000}, 50))
        self.assertEqual(economy.shop_item("fighter_KITO"), ({"type": "fighter", "fighter": "KITO"}, 90))
        self.assertEqual(economy.shop_item("skin_MIRA_2"), ({"type": "skin", "fighter": "MIRA", "skin": 2}, 20))
        for missing in ("fighter_JUNO", "skin_MIRA_0", "skin_MIRA_3", "skin_NOBODY_1", "crate_xl", ""):
            self.assertIsNone(economy.shop_item(missing))
        self.assertEqual(economy.match_bolts("KNOCKOUT_RUSH", "VICTORY", 0, 2, "NORMAL"), 28)
        self.assertEqual(economy.match_bolts("KNOCKOUT_RUSH", "DEFEAT", 0, 9, "ELITE"), 33)
        self.assertEqual(economy.match_bolts("LAST_SPARK", "DEFEAT", 10, 0, "EASY"), 4)
        self.assertEqual(economy.match_bolts("TRAINING", "VICTORY", 0, 5, "NORMAL"), 0)
        self.assertEqual(sorted(economy.CUP_TRACK), list(economy.CUP_TRACK))

    def test_upgrades(self):
        p = economy.new_profile()
        self.assertEqual(economy.upgrade(p, "JUNO"), 10)
        self.assertEqual((p["bolts"], p["fighters"]["JUNO"]["level"]), (50, 2))
        with self.assertRaises(Refused) as caught:
            economy.upgrade(p, "BRAKK")  # locked
        self.assertEqual(caught.exception.status, 409)
        p["bolts"] = 5
        with self.assertRaises(Refused) as caught:
            economy.upgrade(p, "JUNO")
        self.assertEqual(caught.exception.status, 402)
        self.assertEqual((p["bolts"], p["fighters"]["JUNO"]["level"]), (5, 2))
        p["bolts"], p["fighters"]["JUNO"]["level"] = 99999, economy.MAX_LEVEL
        with self.assertRaises(Refused):
            economy.upgrade(p, "JUNO")
        self.assertEqual(economy.upgrade(p, "JUNO", no_cap=True), 450)

    def test_shop_gift_and_track(self):
        p = economy.new_profile()
        p["prisms"] = 100
        with self.assertRaises(Refused):
            economy.buy(p, "skin_BRAKK_1")  # the fighter comes first
        self.assertEqual(economy.buy(p, "fighter_BRAKK"), {"type": "fighter", "fighter": "BRAKK"})
        self.assertEqual(p["prisms"], 60)
        with self.assertRaises(Refused):
            economy.buy(p, "fighter_BRAKK")  # already owned, and not charged again
        self.assertEqual(p["prisms"], 60)
        economy.buy(p, "skin_BRAKK_1")
        self.assertEqual((p["prisms"], p["fighters"]["BRAKK"]["ownedSkins"]), (40, [0, 1]))
        with self.assertRaises(Refused) as caught:
            economy.buy(p, "fighter_KITO")
        self.assertEqual(caught.exception.status, 402)
        economy.buy(p, "crate_s")
        self.assertEqual((p["prisms"], p["bolts"]), (30, 460))

        self.assertEqual(economy.claim_gift(p, 20000), {"type": "bolts", "amount": 40})
        with self.assertRaises(Refused):
            economy.claim_gift(p, 20000)
        self.assertEqual(economy.claim_gift(p, 20001), {"type": "prisms", "amount": 8})

        with self.assertRaises(Refused):
            economy.claim_milestone(p, 100)  # not reached
        p["bestCups"] = 120
        # Brakk is already owned, so the track pays out instead.
        self.assertEqual(economy.claim_milestone(p, 100), {"type": "bolts", "amount": 300})
        with self.assertRaises(Refused):
            economy.claim_milestone(p, 100)
        with self.assertRaises(Refused):
            economy.claim_milestone(p, 55)  # no such reward
        self.assertEqual(p["claimedMilestones"], [100])

    def test_profiles_start_from_a_save(self):
        save = {"bolts": 900, "prisms": "lots", "bestCups": 77, "claimedMilestones": [10, 11, 25], "lastDailyGiftDay": 5,
                "fighters": {"MIRA": {"unlocked": True, "level": 4, "ownedSkins": [0, 2, 9]}, "JUNO": {"unlocked": False, "level": -3}}}
        p = economy.profile_from_save(save)
        self.assertEqual((p["bolts"], p["prisms"], p["bestCups"], p["claimedMilestones"], p["lastDailyGiftDay"]), (900, 0, 77, [10, 25], 5))
        self.assertEqual(p["fighters"]["MIRA"], {"unlocked": True, "level": 4, "ownedSkins": [0, 2]})
        self.assertEqual(p["fighters"]["JUNO"], {"unlocked": True, "level": 1, "ownedSkins": [0]})
        self.assertFalse(p["fighters"]["KITO"]["unlocked"])
        self.assertEqual(economy.profile_from_save({}), economy.new_profile())


class Api(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name in ("versions_not_supported.cfg", "notices.cfg", "bots.cfg", "game.cfg"):
            shutil.copy(os.path.join(HERE, name), self.dir)
        self.httpd = serve(self.dir, "127.0.0.1", 0, quiet=True)
        self.base = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.store = self.httpd.game.store
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.store.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def call(self, method, path, body=None, token=None, version=VERSION):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        if token:
            request.add_header("Authorization", "Bearer " + token)
        if version:
            request.add_header("X-Client-Version", version)
        try:
            with urllib.request.urlopen(request, timeout=5) as reply:
                return reply.status, json.loads(reply.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def write_cfg(self, name, text):
        path = os.path.join(self.dir, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        self.stamp = getattr(self, "stamp", 2_000_000_000) + 10
        os.utime(path, (self.stamp, self.stamp))  # make sure the timestamp differs

    def make_developer(self, player_id):
        self.write_cfg("game.cfg", "[players]\ndifficulty = EASY\n[developers]\neveryone = no\nids = someone-else, %s\n" % player_id)

    def age_matches(self, seconds=120):
        """Pretends every open match started a while ago, so a win is believable."""
        with self.store._lock, self.store._db:
            self.store._db.execute("UPDATE matches SET started_at = started_at - ?", (seconds,))

    def player(self, name="Tester", save=None):
        _, me = self.call("POST", "/v1/players", {"name": name, "version": VERSION})
        self.call("PUT", "/v1/save", {"save": save or {"cups": 0, "capsules": 1}}, me["token"])
        return me

    def test_health_and_status(self):
        status, body = self.call("GET", "/v1/health")
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        _, old = self.call("GET", "/v1/status?version=8")
        self.assertFalse(old["supported"])
        self.assertIn("no longer supported", old["message"])
        _, new = self.call("GET", "/v1/status?version=9")
        self.assertTrue(new["supported"])
        self.assertEqual(new["notice"], "Welcome to the AstroArena servers!")

    def test_cfg_edits_apply_without_restart(self):
        self.assertTrue(self.call("GET", "/v1/status?version=9")[1]["supported"])
        self.write_cfg("versions_not_supported.cfg", "<=9 | Time to move on.\n")
        _, body = self.call("GET", "/v1/status?version=9")
        self.assertFalse(body["supported"])
        self.assertEqual(body["message"], "Time to move on.")

    def test_unsupported_and_unnamed_versions_are_refused_everywhere(self):
        me = self.player()
        self.assertEqual(self.call("GET", "/v1/me", token=me["token"])[0], 200)
        status, body = self.call("GET", "/v1/me", token=me["token"], version="8")
        self.assertEqual(status, 426)
        self.assertIn("no longer supported", body["error"])
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "LAST_SPARK"}, me["token"], version="0.5.1-preview")[0], 426)
        self.assertEqual(self.call("POST", "/v1/drops/open", {}, me["token"], version=None)[0], 426)

    def test_bot_config(self):
        _, body = self.call("GET", "/v1/config")
        self.assertEqual(set(body["bots"]), {"EASY", "NORMAL", "HARD", "ELITE"})
        self.assertEqual(body["bots"]["ELITE"]["reactiontime"], 0.12)
        self.assertIs(body["bots"]["EASY"]["shotdiscipline"], False)

    def test_a_new_player_is_saved_under_the_name_they_chose(self):
        _, me = self.call("POST", "/v1/players", {"name": "  Nova_Fox!!  ", "version": VERSION})
        account = self.call("GET", "/v1/me", token=me["token"])[1]["account"]
        self.assertEqual((account["name"], account["rank"], account["players"]), ("Nova_Fox", 1, 1))
        self.assertEqual(self.store.player(me["id"])["name"], "Nova_Fox")
        self.assertEqual(self.call("GET", "/v1/leaderboard")[1]["players"], [{"id": me["id"], "name": "Nova_Fox", "cups": 0, "fighter": "JUNO"}])

    def test_accounts_saves_and_leaderboard(self):
        self.assertEqual(self.call("GET", "/v1/save")[0], 401)
        self.assertEqual(self.call("GET", "/v1/save", token="nope")[0], 401)
        status, me = self.call("POST", "/v1/players", {"name": "Tester", "version": "7"})
        self.assertEqual(status, 201)
        self.assertEqual(self.call("GET", "/v1/save", token=me["token"])[0], 404)
        save = {"cups": 321, "capsules": 4, "selectedFighter": "KITO", "settings": {"playerName": "Ace <script>"}, "bolts": 5}
        status, body = self.call("PUT", "/v1/save", {"save": save}, me["token"])
        self.assertEqual((status, body["revision"]), (200, 1))
        # The first save sets the account's starting Cups and drops...
        self.assertEqual((body["account"]["cups"], body["account"]["drops"]), (321, 4))
        # ...and after that a save can't change them.
        cheat = {**save, "cups": 99999, "capsules": 500}
        body = self.call("PUT", "/v1/save", {"save": cheat}, me["token"])[1]
        self.assertEqual((body["revision"], body["account"]["cups"], body["account"]["drops"]), (2, 321, 4))
        _, stored = self.call("GET", "/v1/save", token=me["token"])
        self.assertEqual(stored["save"], cheat)
        # One player can't read another's save.
        _, other = self.call("POST", "/v1/players", {"name": "Other"})
        self.assertEqual(self.call("GET", "/v1/save", token=other["token"])[0], 404)
        _, board = self.call("GET", "/v1/leaderboard?limit=10")
        self.assertEqual(board["players"][0]["cups"], 321)
        self.assertEqual(board["players"][0]["name"], "Ace script")  # names are cleaned
        self.assertEqual(board["players"][0]["fighter"], "KITO")
        self.assertNotIn("token", board["players"][0])

    def test_new_accounts_can_be_made_to_start_from_zero(self):
        self.write_cfg("game.cfg", "[accounts]\nimport_saves = no\nper_address_per_hour = 2\n")
        me = self.player(save={"cups": 5000, "capsules": 40})
        account = self.call("GET", "/v1/me", token=me["token"])[1]["account"]
        self.assertEqual((account["cups"], account["drops"]), (0, rules.STARTING_DROPS))
        # And one address can only make so many accounts.
        self.assertEqual(self.call("POST", "/v1/players", {"name": "Two"})[0], 201)
        self.assertEqual(self.call("POST", "/v1/players", {"name": "Three"})[0], 429)

    def test_the_server_awards_cups_and_drops(self):
        me = self.player()
        other = self.player("Other")
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "NOPE"}, me["token"])[0], 400)
        # An ordinary player asks for Elite bots and gets the server's difficulty.
        status, plan = self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "fighter": "JUNO", "level": 3, "difficulty": "ELITE"}, me["token"])
        self.assertEqual((status, plan["difficulty"]), (201, "EASY"))
        self.assertEqual(len(plan["botNames"]), 9)
        self.assertEqual(len(set(plan["botNames"])), 9)
        self.assertGreater(plan["seed"], 0)
        win = {"outcome": "VICTORY", "placement": 1, "kos": 4, "deaths": 0, "damage": 12000}
        path = "/v1/matches/%d/result" % plan["matchId"]
        self.age_matches()
        self.assertEqual(self.call("POST", path, win, other["token"])[0], 409)  # not their match
        status, body = self.call("POST", path, win, me["token"])
        self.assertEqual(status, 200)
        self.assertEqual((body["cupDelta"], body["cups"], body["drop"]), (8, 8, True))  # 1st place on Easy
        # Bolts and the first-win Prisms are the server's to give too: (30 + 2 x 4 KOs) x 0.75 on Easy.
        self.assertEqual((body["bolts"], body["firstWinPrisms"]), (29, 10))
        self.assertEqual((body["account"]["profile"]["bolts"], body["account"]["profile"]["prisms"]), (60 + 29, 10))
        self.assertEqual(body["account"]["profile"]["bestCups"], 8)
        self.assertEqual((body["account"]["drops"], body["account"]["dropsLeftToday"]), (2, 2))
        self.assertEqual(self.call("POST", path, win, me["token"])[0], 409)  # only once
        # Three drops a day: the fourth good finish earns Cups but no drop.
        for expected_drop in (True, True, False):
            _, plan = self.call("POST", "/v1/matches", {"mode": "KNOCKOUT_RUSH"}, me["token"])
            self.assertEqual(len(plan["botNames"]), 5)
            self.age_matches()
            _, body = self.call("POST", "/v1/matches/%d/result" % plan["matchId"], {**win, "placement": 0, "mvp": True}, me["token"])
            self.assertEqual((body["cupDelta"], body["drop"]), (8, expected_drop))
            self.assertEqual(body["firstWinPrisms"], 0)  # only the first win of the day
        self.assertEqual((body["account"]["cups"], body["account"]["drops"], body["account"]["dropsLeftToday"]), (32, 4, 0))
        _, board = self.call("GET", "/v1/leaderboard")
        self.assertEqual((board["players"][0]["name"], board["players"][0]["cups"]), ("Player", 32))
        # The leaderboard is the real accounts and nobody else, and each account knows its place on it.
        self.assertEqual(len(board["players"]), 2)
        self.assertEqual((body["account"]["rank"], body["account"]["players"]), (1, 2))
        theirs = self.call("GET", "/v1/me", token=other["token"])[1]["account"]
        self.assertEqual((theirs["rank"], theirs["players"]), (2, 2))

    def test_a_claimed_instant_win_is_refused(self):
        me = self.player()
        _, plan = self.call("POST", "/v1/matches", {"mode": "LAST_SPARK"}, me["token"])
        win = {"outcome": "VICTORY", "placement": 1, "kos": 9, "deaths": 0, "damage": 30000}
        path = "/v1/matches/%d/result" % plan["matchId"]
        status, body = self.call("POST", path, win, me["token"])
        self.assertEqual(status, 422)
        self.assertIn("faster", body["error"])
        self.assertEqual(self.call("POST", path, win, me["token"])[0], 409)  # and the match is closed
        account = self.call("GET", "/v1/me", token=me["token"])[1]["account"]
        self.assertEqual((account["cups"], account["drops"]), (0, 1))
        self.assertEqual(self.store.player(me["id"])["flags"], 1)
        # A result for a match the server never planned gets nothing either.
        self.assertEqual(self.call("POST", "/v1/matches/9999/result", win, me["token"])[0], 409)

    def test_the_server_opens_drops(self):
        save = {"cups": 0, "capsules": 2, "bolts": 100, "prisms": 10}
        me = self.player(save=save)
        status, first = self.call("POST", "/v1/drops/open", {"luck": 14, "free": True}, me["token"])
        self.assertEqual(status, 200)
        self.assertIn(first["tier"], [name for name, _ in rules.TIERS])
        self.assertIn(first["pieces"], (1, 2, 4, 8))
        # `free` was ignored: this player is no developer, so the drop was used up.
        self.assertEqual(first["account"]["drops"], 2 - 1 + first["pieces"] - 1)
        # The reward went into the profile the server keeps; the uploaded save is left as it was.
        expected = economy.profile_from_save(save)
        economy.grant(expected, first["reward"])
        self.assertEqual(first["account"]["profile"], expected)
        self.assertEqual(self.call("GET", "/v1/save", token=me["token"])[1]["save"], save)
        # Open until there are none left; then the server says no.
        left = first["account"]["drops"]
        while left > 0:
            left = self.call("POST", "/v1/drops/open", {}, me["token"])[1]["account"]["drops"]
        self.assertEqual(self.call("POST", "/v1/drops/open", {}, me["token"])[0], 409)

    def test_developers_are_chosen_by_the_server(self):
        me = self.player()
        account = self.call("GET", "/v1/me", token=me["token"])[1]["account"]
        self.assertFalse(account["developer"])
        self.assertEqual(account["difficulty"], "EASY")
        self.assertEqual(self.call("POST", "/v1/dev/grant", {"cups": 500}, me["token"])[0], 403)
        self.make_developer(me["id"])
        self.assertTrue(self.call("GET", "/v1/me", token=me["token"])[1]["account"]["developer"])
        _, body = self.call("POST", "/v1/dev/grant", {"cups": 500, "drops": 5}, me["token"])
        self.assertEqual((body["account"]["cups"], body["account"]["drops"]), (500, 6))
        # A developer picks the difficulty, and can open drops for free with luck.
        _, plan = self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "difficulty": "ELITE"}, me["token"])
        self.assertEqual(plan["difficulty"], "ELITE")
        _, drop = self.call("POST", "/v1/drops/open", {"luck": 14, "free": True}, me["token"])
        self.assertEqual(drop["account"]["drops"], 6 + drop["pieces"] - 1)  # free: none used up
        # Someone else is still an ordinary player.
        other = self.player("Other")
        self.assertFalse(self.call("GET", "/v1/me", token=other["token"])[1]["account"]["developer"])

    def test_the_server_keeps_the_economy(self):
        me = self.player(save={"cups": 0, "capsules": 0, "bolts": 500, "prisms": 100, "bestCups": 30})
        token = me["token"]
        profile = self.call("GET", "/v1/me", token=token)[1]["account"]["profile"]
        self.assertEqual((profile["bolts"], profile["prisms"], profile["bestCups"]), (500, 100, 30))
        # A later save can't change any of it.
        body = self.call("PUT", "/v1/save", {"save": {"bolts": 999999, "prisms": 999999, "fighters": {"KITO": {"unlocked": True, "level": 50}}}}, token)[1]
        self.assertEqual((body["account"]["profile"]["bolts"], body["account"]["profile"]["prisms"]), (500, 100))
        self.assertFalse(body["account"]["profile"]["fighters"]["KITO"]["unlocked"])

        status, body = self.call("POST", "/v1/fighters/upgrade", {"fighter": "JUNO", "costFactor": 0, "noCap": True}, token)
        self.assertEqual((status, body["cost"]), (200, 10))  # an ordinary player's cost factor is ignored
        self.assertEqual((body["account"]["profile"]["bolts"], body["account"]["profile"]["fighters"]["JUNO"]["level"]), (490, 2))
        self.assertEqual(self.call("POST", "/v1/fighters/upgrade", {"fighter": "KITO"}, token)[0], 409)

        status, body = self.call("POST", "/v1/shop/buy", {"item": "fighter_MIRA"}, token)
        self.assertEqual((status, body["reward"], body["account"]["profile"]["prisms"]), (200, {"type": "fighter", "fighter": "MIRA"}, 30))
        self.assertEqual(self.call("POST", "/v1/shop/buy", {"item": "fighter_KITO"}, token)[0], 402)
        self.assertEqual(self.call("POST", "/v1/shop/buy", {"item": "fighter_MIRA"}, token)[0], 409)
        self.assertEqual(self.call("POST", "/v1/shop/buy", {"item": "everything"}, token)[0], 404)

        status, body = self.call("POST", "/v1/shop/gift", {}, token)
        self.assertEqual(status, 200)
        self.assertIn(body["reward"]["type"], ("bolts", "prisms"))
        self.assertEqual(self.call("POST", "/v1/shop/gift", {}, token)[0], 409)

        status, body = self.call("POST", "/v1/track/claim", {"cups": 25}, token)
        self.assertEqual((status, body["reward"]), (200, {"type": "prisms", "amount": 10}))
        self.assertEqual(body["account"]["profile"]["claimedMilestones"], [25])
        self.assertEqual(self.call("POST", "/v1/track/claim", {"cups": 25}, token)[0], 409)
        self.assertEqual(self.call("POST", "/v1/track/claim", {"cups": 40}, token)[0], 409)  # best is 30

        # Hand-outs are for developers.
        self.assertEqual(self.call("POST", "/v1/dev/grant", {"bolts": 1000}, token)[0], 403)
        self.make_developer(me["id"])
        body = self.call("POST", "/v1/dev/grant", {"bolts": 1000, "prisms": 5}, token)[1]
        before = body["account"]["profile"]["bolts"]
        body = self.call("POST", "/v1/fighters/upgrade", {"fighter": "JUNO", "costFactor": 0}, token)[1]
        self.assertEqual((body["cost"], body["account"]["profile"]["bolts"]), (0, before))  # a developer's free upgrade

        # Starting over wipes progress and keeps the account.
        body = self.call("POST", "/v1/reset", {}, token)[1]
        self.assertEqual(body["account"]["profile"], economy.new_profile())
        self.assertEqual((body["account"]["cups"], body["account"]["drops"], body["account"]["name"]), (0, rules.STARTING_DROPS, "Player"))

    def test_deals_are_made_by_developers_and_bought_by_everyone(self):
        dev = self.player("Dev")
        buyer = self.player("Buyer", save={"cups": 0, "capsules": 0, "bolts": 100, "prisms": 50})
        deal = {"title": "Starter pack", "bolts": 500, "fighter": "BRAKK", "currency": "PRISMS", "price": 30, "wasPrice": 60, "limit": 1}
        self.assertEqual(self.call("POST", "/v1/dev/deals", deal, buyer["token"])[0], 403)
        self.make_developer(dev["id"])
        self.assertEqual(self.call("POST", "/v1/dev/deals", {"title": "Nothing"}, dev["token"])[0], 400)
        status, body = self.call("POST", "/v1/dev/deals", deal, dev["token"])
        self.assertEqual(status, 200)
        deal_id = body["id"]
        # Everyone sees it, each with their own purchase count.
        shown = self.call("GET", "/v1/me", token=buyer["token"])[1]["account"]["deals"]
        self.assertEqual([(d["id"], d["title"], d["price"], d["purchased"]) for d in shown], [(deal_id, "Starter pack", 30, 0)])
        status, body = self.call("POST", "/v1/shop/deals/%d/buy" % deal_id, {}, buyer["token"])
        self.assertEqual(status, 200)
        self.assertEqual(body["reward"], {"type": "bundle", "items": [{"type": "bolts", "amount": 500}, {"type": "fighter", "fighter": "BRAKK"}]})
        profile = body["account"]["profile"]
        self.assertEqual((profile["bolts"], profile["prisms"], profile["fighters"]["BRAKK"]["unlocked"]), (600, 20, True))
        self.assertEqual(body["account"]["deals"][0]["purchased"], 1)
        self.assertEqual(self.call("POST", "/v1/shop/deals/%d/buy" % deal_id, {}, buyer["token"])[0], 409)  # limit 1
        self.assertEqual(self.call("GET", "/v1/me", token=dev["token"])[1]["account"]["deals"][0]["purchased"], 0)
        # Too dear, ended, and gone.
        dear = self.call("POST", "/v1/dev/deals", {"prisms": 5, "currency": "BOLTS", "price": 100000}, dev["token"])[1]["id"]
        self.assertEqual(self.call("POST", "/v1/shop/deals/%d/buy" % dear, {}, buyer["token"])[0], 402)
        self.call("POST", "/v1/dev/deals", {"bolts": 5, "currency": "FREE", "expiresAt": 1000}, dev["token"])
        self.assertEqual(len(self.call("GET", "/v1/me", token=buyer["token"])[1]["account"]["deals"]), 2)  # the ended one isn't shown
        self.assertEqual(self.call("POST", "/v1/dev/deals/%d/delete" % deal_id, {}, buyer["token"])[0], 403)
        self.assertTrue(self.call("POST", "/v1/dev/deals/%d/delete" % deal_id, {}, dev["token"])[1]["deleted"])
        self.assertEqual(self.call("POST", "/v1/shop/deals/%d/buy" % deal_id, {}, buyer["token"])[0], 404)

    def test_old_databases_are_upgraded(self):
        import sqlite3
        path = os.path.join(self.dir, "old.db")
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE players (id TEXT PRIMARY KEY, token TEXT NOT NULL UNIQUE, name TEXT NOT NULL DEFAULT 'Player', "
                   "cups INTEGER NOT NULL DEFAULT 0, fighter TEXT NOT NULL DEFAULT 'JUNO', version TEXT NOT NULL DEFAULT '', "
                   "created_at REAL NOT NULL, last_seen REAL NOT NULL)")
        db.execute("INSERT INTO players VALUES ('abc', 'tok', 'Old', 40, 'JUNO', '6', 1, 1)")
        db.commit()
        db.close()
        from astro.store import Store
        store = Store(path)
        try:
            row = store.player("abc")
            self.assertEqual((row["cups"], row["drops"], row["imported"]), (40, rules.STARTING_DROPS, 0))
            store.put_save("abc", {"cups": 70, "capsules": 3})
            self.assertEqual((store.player("abc")["cups"], store.player("abc")["drops"]), (70, 3))
        finally:
            store.close()

    def test_bad_requests(self):
        self.assertEqual(self.call("GET", "/v1/nothing")[0], 404)
        request = urllib.request.Request(self.base + "/v1/players", data=b"not json", method="POST")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=5)
        self.assertEqual(caught.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
