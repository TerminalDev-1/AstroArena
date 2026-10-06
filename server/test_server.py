"""Run with:  python -m unittest  (from the server directory)"""

import base64
import gzip
import json
import os
import random
import shutil
import struct
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from astro import economy, rules
from astro.economy import Refused
from astro.referee import Referee, count_ticks, decode_inputs
from astro.app import serve
from astro.accounts import when
from astro.config import matches, parse_version

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "11"


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
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "VICTORY", 0, 0, False), 8)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "VICTORY", 0, 0, True), 10)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "DRAW", 0, 0, False), 1)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "DEFEAT", 0, 3, False), 0)
        self.assertEqual(rules.cup_delta("KNOCKOUT_RUSH", "DEFEAT", 0, 500, False), -6)
        # Last Spark pays by place, whatever the bots' difficulty, and last place costs nothing.
        self.assertEqual([rules.cup_delta("LAST_SPARK", "DEFEAT", place, 100, False) for place in range(1, 11)], [25, 22, 20, 17, 14, 12, 7, 3, 0, 0])
        # Boss Mode pays for a win and costs nothing; the Training Area is never played for Cups.
        self.assertEqual([rules.cup_delta("BOSS", o, 0, 500, True) for o in ("VICTORY", "DRAW", "DEFEAT")], [5, 0, 0])
        self.assertEqual(rules.cup_delta("TRAINING", "VICTORY", 0, 100, True, {"TRAINING": {"win": 50}}), 0)
        # trophies.cfg's numbers are used when there are some, and Cups never go below zero.
        table = {"BOSS": {"win": 12, "mvp_bonus": 3, "draw": 2, "max_loss": 4}, "KNOCKOUT_RUSH": {"places": [9, -2]}}
        self.assertEqual([rules.cup_delta("BOSS", o, 0, 3, True, table) for o in ("VICTORY", "DRAW", "DEFEAT")], [15, 2, -3])
        self.assertEqual([rules.cup_delta("KNOCKOUT_RUSH", "DEFEAT", p, 50, False, table) for p in (1, 2, 7)], [9, -2, -2])
        # A fighter's rank follows its own Cups; the top one starts at 1000.
        self.assertEqual([rules.fighter_rank(c) for c in (0, 9, 10, 999, 1000, 5000)], [1, 1, 2, 19, 20, 20])
        self.assertEqual(len(rules.FIGHTER_RANK_CUPS), 20)

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
        # Drops hand out colourways for the fighters a player has, and never a fighter: those come from the Spark Road.
        save = {"bolts": 0, "prisms": 0, "fighters": {f: {"unlocked": True, "level": 1, "skin": 0, "ownedSkins": [0]} for f in rules.FIGHTER_SKINS}}
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
        self.assertEqual(len([k for k in seen if k[0] == "fighter"]), 0)
        self.assertGreater(save["credits"], 0)
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
        self.assertEqual(economy.shop_item("fighter_VARUN"), ({"type": "fighter", "fighter": "VARUN"}, 160))
        self.assertEqual(economy.SPARK_ROAD[-1], ("VARUN", 1600))
        self.assertEqual(economy.shop_item("skin_VARUN_2"), ({"type": "skin", "fighter": "VARUN", "skin": 2}, 20))
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
        # The track pays Credits where it used to hand out a fighter.
        self.assertEqual(economy.claim_milestone(p, 100), {"type": "credits", "amount": 80})
        self.assertEqual(p["credits"], 80)
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
        self.assertEqual(p["fighters"]["MIRA"], {"unlocked": True, "level": 4, "ownedSkins": [0, 2], "cups": 0})
        self.assertEqual(p["fighters"]["JUNO"], {"unlocked": True, "level": 1, "ownedSkins": [0], "cups": 0})
        self.assertFalse(p["fighters"]["KITO"]["unlocked"])
        self.assertEqual(economy.profile_from_save({}), economy.new_profile())


class Api(unittest.TestCase):
    # These tests are about the rules around a result; they run without the referee, so a result is what the device says.
    referee = None

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name in ("versions_not_supported.cfg", "notices.cfg", "bots.cfg", "game.cfg", "shop.cfg"):
            shutil.copy(os.path.join(HERE, name), self.dir)
        # The tests' own cut-off, so that raising the real one (it moves with releases) doesn't turn them away.
        with open(os.path.join(self.dir, "versions_not_supported.cfg"), "w", encoding="utf-8") as cfg:
            cfg.write("<11 | This version of AstroArena is no longer supported. Please update to v11 or later.\n")
        # And the tests' own shop: the real one is the owner's to change as they please.
        with open(os.path.join(self.dir, "shop.cfg"), "w", encoding="utf-8") as cfg:
            cfg.write("[settings]\noffers_per_day = 3\n" + "".join(
                "[Test Offer %d]\nbolts = %d\nprisms = %d\ncurrency = %s\nprice = %d\n" % offer for offer in (
                    (1, 60, 0, "FREE", 0), (2, 0, 3, "FREE", 0), (3, 300, 0, "PRISMS", 6), (4, 1000, 0, "PRISMS", 18), (5, 0, 12, "BOLTS", 500))))
        self.httpd = serve(self.dir, "127.0.0.1", 0, quiet=True, referee=self.referee)
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
        _, old = self.call("GET", "/v1/status?version=10")
        self.assertFalse(old["supported"])
        self.assertIn("no longer supported", old["message"])
        _, new = self.call("GET", "/v1/status?version=11")
        self.assertTrue(new["supported"])
        self.assertEqual(new["notice"], "Welcome to the AstroArena servers!")

    def test_cfg_edits_apply_without_restart(self):
        self.assertTrue(self.call("GET", "/v1/status?version=11")[1]["supported"])
        self.write_cfg("versions_not_supported.cfg", "<=11 | Time to move on.\n")
        _, body = self.call("GET", "/v1/status?version=11")
        self.assertFalse(body["supported"])
        self.assertEqual(body["message"], "Time to move on.")

    def test_unsupported_and_unnamed_versions_are_refused_everywhere(self):
        me = self.player()
        self.assertEqual(self.call("GET", "/v1/me", token=me["token"])[0], 200)
        status, body = self.call("GET", "/v1/me", token=me["token"], version="10")
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
        self.assertEqual(self.call("GET", "/v1/leaderboard")[1]["players"], [{"id": me["id"], "name": "Nova_Fox", "cups": 0, "fighter": "JUNO", "glory": 0}])

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

    def test_the_1v1_lobby_pairs_two_players_and_passes_their_inputs(self):
        import json as _json, socket, struct

        def text(sock):
            (length,) = struct.unpack(">H", sock.recv(2))
            return sock.recv(length).decode()

        def join(player, fighter="JUNO"):
            sock = socket.create_connection(("127.0.0.1", self.httpd.duel.server_address[1]), timeout=5)
            body = _json.dumps({"token": player["token"], "version": VERSION, "fighter": fighter, "skin": 0}).encode()
            sock.sendall(b"H" + struct.pack(">H", len(body)) + body)
            return sock

        self.assertIsNotNone(self.httpd.duel)
        one, two = self.player("Ada"), self.player("Bo")
        # A fighter that isn't unlocked is turned away, with the reason.
        cheat = join(one, "VARUN")
        self.assertEqual(cheat.recv(1), b"E")
        self.assertIn("unlocked", text(cheat))
        cheat.close()
        a = join(one)
        b = join(two)
        starts = []
        for sock in (a, b):
            self.assertEqual(sock.recv(1), b"S")
            starts.append(_json.loads(text(sock)))
        self.assertEqual(starts[0]["seed"], starts[1]["seed"])
        self.assertEqual({starts[0]["side"], starts[1]["side"]}, {0, 1})
        self.assertTrue(all(isinstance(s["opponent"]["name"], str) and s["opponent"]["name"] for s in starts))
        self.assertEqual((starts[0]["level"], starts[0]["opponent"]["fighter"]), (1, "JUNO"))
        # A frame one sends reaches the other untouched.
        frame = b"I" + bytes(range(17))
        a.sendall(frame)
        self.assertEqual(b.recv(18), frame)
        # So is a check, either way.
        check = b"C" + bytes(range(8))
        b.sendall(check)
        self.assertEqual(a.recv(9), check)
        # A player on another build isn't paired with one on this build: they wait for their own kind.
        def join_as(player, version):
            sock = socket.create_connection(("127.0.0.1", self.httpd.duel.server_address[1]), timeout=5)
            body = _json.dumps({"token": player["token"], "version": version, "fighter": "JUNO", "skin": 0}).encode()
            sock.sendall(b"H" + struct.pack(">H", len(body)) + body)
            return sock
        old, new = join_as(self.player("Di"), "46"), join_as(self.player("Ed"), "47")
        for sock in (old, new):
            # Not a start: only a note saying why nobody is being found.
            self.assertEqual(sock.recv(1), b"N")
            self.assertIn("different build", text(sock))
        mate = join_as(self.player("Flo"), "46")
        self.assertEqual((old.recv(1), mate.recv(1)), (b"S", b"S"))
        for sock in (old, new, mate):
            sock.close()
        # The same account on a second device takes over the wait instead of playing itself.
        gil = self.player("Gil")
        first, second = join_as(gil, "50"), join_as(gil, "50")
        self.assertEqual(first.recv(1), b"E")
        second.settimeout(1.0)
        with self.assertRaises(socket.timeout):
            second.recv(1)
        # Someone waiting on a different build is no opponent, and both are told why nobody is being found.
        other_build = join_as(self.player("Hal"), "51")
        self.assertEqual((second.recv(1), other_build.recv(1)), (b"N", b"N"))
        self.assertIn("different build", text(second))
        for sock in (first, second, other_build):
            sock.close()
        # A third player waits: nobody stands in for a real opponent, however long it takes.
        c = join(self.player("Cy"))
        c.settimeout(1.5)
        try:
            self.assertNotEqual(c.recv(1), b"S")
        except socket.timeout:
            pass
        c.close()
        # When one hangs up, the other is told.
        a.close()
        self.assertEqual(b.recv(1), b"X")
        b.close()

    def test_the_1v1_lobby_says_who_stopped(self):
        import json as _json, socket, struct, time as _time

        def join(player):
            sock = socket.create_connection(("127.0.0.1", self.httpd.duel.server_address[1]), timeout=5)
            body = _json.dumps({"token": player["token"], "version": VERSION, "fighter": "JUNO", "skin": 0}).encode()
            sock.sendall(b"H" + struct.pack(">H", len(body)) + body)
            return sock

        def started(*socks):
            for sock in socks:
                self.assertEqual(sock.recv(1), b"S")
                (length,) = struct.unpack(">H", sock.recv(2))
                sock.recv(length)

        lobby = self.httpd.duel
        lobby.stall_seconds, lobby.start_seconds = 0.6, 1.5
        frame = b"I" + bytes(17)
        # One player's inputs stop (their game was put down); the other's stop a few frames later, as they wait.
        a, b = join(self.player("Ada")), join(self.player("Bo"))
        started(a, b)
        for _ in range(5):
            a.sendall(frame); b.sendall(frame)
        for _ in range(4):
            b.sendall(frame)
        _time.sleep(0.2)
        self.assertEqual(a.recv(18 * 9), frame * 9)
        self.assertEqual(b.recv(18 * 5), frame * 5)
        self.assertEqual(a.recv(1), b"L")   # the one who stopped first loses...
        self.assertEqual(b.recv(1), b"X")   # ...and the other wins
        a.close(); b.close()
        # Neither has sent anything yet (both still on the line-up): they get longer before it is called off.
        c, d = join(self.player("Cy")), join(self.player("Di"))
        started(c, d)
        c.settimeout(0.9)
        with self.assertRaises(socket.timeout):
            c.recv(1)
        c.settimeout(5)
        self.assertEqual((c.recv(1), d.recv(1)), (b"D", b"D"))
        c.close(); d.close()

    def test_the_news_tab_reads_news_cfg(self):
        status, body = self.call("GET", "/v1/news")
        self.assertEqual(status, 200)
        self.assertIsInstance(body["news"], list)
        for item in body["news"]:
            self.assertEqual(set(item), {"title", "date", "tag", "text"})
            self.assertTrue(item["title"] and item["text"])

    def test_how_long_an_account_is_disabled_for(self):
        self.assertEqual([when(text, 1000.0) for text in ("", "forever", "30 minutes", "12h", "3 days", "2 weeks", "1 day, 6 hours")],
                         [0.0, 0.0, 2800.0, 44200.0, 260200.0, 1210600.0, 109000.0])
        self.assertEqual(when("2026-10-20 18:00") - when("2026-10-20"), 18 * 3600)
        # Day first, the UK way, means the same day.
        self.assertEqual((when("20/10/2026 18:00"), when("05/11/2026")), (when("2026-10-20 18:00"), when("2026-11-05")))
        self.assertEqual([when(text) for text in ("soon", "3", "3 dys", "3 days maybe")], [None] * 4)

    def test_accounts_cfg_forces_what_the_operator_changes(self):
        cheat, fair = self.player("Cheat"), self.player("Fair")
        self.store.grant(cheat["id"], cups=900, prisms=5000)
        self.store.grant(fair["id"], cups=40)
        accounts = self.httpd.game.accounts
        accounts.quiet = True
        path = os.path.join(self.dir, "accounts.cfg")

        def edit(*swaps):
            with open(path, encoding="utf-8") as f:
                text = f.read()
            for old, new in swaps:
                self.assertIn(old, text)
                text = text.replace(old, new, 1)
            return text

        def save(text):
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            accounts.sync()

        accounts.sync()
        text = edit()
        # Every account is there, top of the leaderboard first, and nobody's token is.
        self.assertLess(text.index("(%s)]" % cheat["id"]), text.index("(%s)]" % fair["id"]))
        self.assertNotIn(cheat["token"], text)
        # The operator takes the cheat down a peg. While the file was open, the other player won some Cups.
        text = edit(("cups = 900", "cups = 12"), ("prisms = 5000", "prisms = 1,000"),
                    ("juno = unlocked, level 1, cups 0", "juno = locked, level 3, cups 7"), ("kito = locked, level 1, cups 0", "kito = unlocked, level 99999"))
        self.store.grant(fair["id"], cups=25)
        accounts.sync()  # the server writes the file again; the operator's editor still has the older one
        save(text)
        account = self.call("GET", "/v1/me", token=cheat["token"])[1]["account"]
        self.assertEqual((account["cups"], account["profile"]["prisms"]), (12, 1000))
        # The fighter everyone starts with stays unlocked; levels stop at the limit; what wasn't written stays.
        juno, kito = account["profile"]["fighters"]["JUNO"], account["profile"]["fighters"]["KITO"]
        self.assertEqual((juno["unlocked"], juno["level"], juno["cups"]), (True, 3, 7))
        self.assertEqual((kito["unlocked"], kito["level"], kito["cups"]), (True, economy.LEVEL_LIMIT, 0))
        # Only what was changed is forced: the other player keeps the Cups won in the meantime.
        self.assertEqual(self.store.player(fair["id"])["cups"], 65)
        # The file is written out again as things stand now, with the other player on top.
        text = edit()
        self.assertLess(text.index("(%s)]" % fair["id"]), text.index("(%s)]" % cheat["id"]))
        self.assertIn("[Player (%s)]\n# 2 on the leaderboard" % cheat["id"], text)
        # Disabling an account shuts it out everywhere and takes it off the leaderboard; enabling it lets it back in.
        # (The account stays in the database, with all it has.) The first account in the file is the fair player.
        save(edit(("\ndisabled = no", "\ndisabled = yes"), ("disabled_reason = ", "disabled_reason = Being   too fair")))
        status, body = self.call("GET", "/v1/me", token=fair["token"])
        self.assertEqual((status, body["disabled"], body["reason"], body["until"]), (403, True, "Being too fair", 0))
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "LAST_SPARK"}, fair["token"])[0], 403)
        board = self.call("GET", "/v1/leaderboard", token=cheat["token"])[1]
        self.assertEqual([row["id"] for row in board["players"]], [cheat["id"]])
        account = self.call("GET", "/v1/me", token=cheat["token"])[1]["account"]
        self.assertEqual((account["rank"], account["players"]), (1, 1))
        self.assertIn("# disabled: off the leaderboard\ndisabled = yes\ndisabled_reason = Being too fair\ndisabled_until = \n", edit())
        save(edit(("\ndisabled = yes", "\ndisabled = no")))
        self.assertEqual(self.call("GET", "/v1/me", token=fair["token"])[0], 200)
        self.assertEqual(self.store.player(fair["id"])["cups"], 65)
        self.assertIn("disabled = no\ndisabled_reason = \n", edit())  # the reason goes when the account is let back in
        # With a timer it ends by itself: the server turns "how long" into a date, and tells the game when.
        save(edit(("\ndisabled = no", "\ndisabled = yes"), ("disabled_until = ", "disabled_until = 1 day 6 hours")))
        body = self.call("GET", "/v1/me", token=fair["token"])[1]
        self.assertAlmostEqual((body["until"] - body["now"]) / 3600000, 30, delta=0.01)
        self.assertRegex(edit(), r"disabled_until = 20\d\d-\d\d-\d\d \d\d:\d\d")
        self.store.lift_expired(now=body["until"] / 1000 - 5)
        self.assertEqual(self.call("GET", "/v1/me", token=fair["token"])[0], 403)
        self.store.lift_expired(now=body["until"] / 1000 + 5)
        self.assertEqual(self.call("GET", "/v1/me", token=fair["token"])[0], 200)
        accounts.sync()
        text = edit()
        # Deleting an account takes delete = yes and two confirmations in the same save: its name, and the word DELETE.
        spare = self.player("Spare")
        accounts.sync()
        section = "(%s)]" % spare["id"]

        def ask(*lines):
            text = edit()
            at = text.index("\ndelete = no", text.index(section))
            end = text.index("delete_confirm = ", at) + len("delete_confirm = ")
            save(text[:at] + "\n" + "\n".join(lines) + text[end:])

        for attempt, why in (
                (("delete = yes", "delete_name = ", "delete_confirm = "), "delete_name has to be this account's name, Player."),
                (("delete = yes", "delete_name = Somebody", "delete_confirm = DELETE"), "delete_name has to be this account's name, Player."),
                (("delete = yes", "delete_name = Player", "delete_confirm = "), "delete_confirm has to be the word DELETE, in capitals."),
                (("delete = yes", "delete_name = Player", "delete_confirm = delete"), "delete_confirm has to be the word DELETE, in capitals.")):
            ask(*attempt)
            self.assertIsNotNone(self.store.player(spare["id"]))
            # The file is put back as it was, with a line saying why nothing was deleted.
            self.assertIn(section + "\n# 3 on the leaderboard\n# NOT DELETED: " + why, edit())
            self.assertIn("delete = no\ndelete_name = \ndelete_confirm = \n", edit()[edit().index(section):])
        # The confirmations alone, without delete = yes, do nothing either (and there is nothing to explain).
        ask("delete = no", "delete_name = Player", "delete_confirm = DELETE")
        self.assertIsNotNone(self.store.player(spare["id"]))
        self.assertNotIn("NOT DELETED", edit())
        ask("delete = yes", "delete_name = player", "delete_confirm = DELETE")
        self.assertIsNone(self.store.player(spare["id"]))
        self.assertEqual(self.call("GET", "/v1/me", token=spare["token"])[0], 401)
        self.assertNotIn(section, edit())
        with open(os.path.join(self.dir, "deleted_accounts.log"), encoding="utf-8") as log:
            record = json.loads(log.read().splitlines()[-1])
        self.assertEqual((record["id"], record["profile"]["bolts"], "token" in record), (spare["id"], 60, False))
        # Nobody else is touched, and taking a section out of the file deletes nothing.
        self.assertEqual((self.store.player(fair["id"])["cups"], self.store.player(cheat["id"])["cups"]), (65, 12))
        text = edit()
        save(text[:text.index("[Player (%s)]" % cheat["id"])])
        self.assertIsNotNone(self.store.player(cheat["id"]))
        text = edit()
        # A file that can't be read changes nothing and is left for the operator to fix; so are lines that make no sense.
        save(text.replace("cups = 12", "cups = 5\n[Player (%s)]\ncups = 1" % cheat["id"], 1))
        self.assertEqual(self.store.player(cheat["id"])["cups"], 12)
        save(text.replace("cups = 12", "cups = lots", 1).replace("mira = locked", "mira = gone", 1))
        self.assertEqual(self.store.player(cheat["id"])["cups"], 12)
        # A copy from before the server started can't say what was changed: nothing is forced.
        save(text.replace("cups = 12", "cups = 3", 1).replace("revision = ", "revision = 9", 1))
        self.assertEqual(self.store.player(cheat["id"])["cups"], 12)

    def test_trophies_cfg_sets_the_cups(self):
        me = self.player()
        win = {"outcome": "VICTORY", "kos": 1, "deaths": 0, "damage": 9000, "mvp": True}

        def play(mode, result):
            _, plan = self.call("POST", "/v1/matches", {"mode": mode}, me["token"])
            self.age_matches()
            return self.call("POST", "/v1/matches/%d/result" % plan["matchId"], result, me["token"])[1]

        # With no trophies.cfg the built-in numbers apply: a boss is worth 5, and the fighter gets them too.
        body = play("BOSS", win)
        self.assertEqual((body["cupDelta"], body["cups"], body["fighterCups"], body["mvpCups"], body["drop"]), (5, 5, 5, 0, False))
        # The operator writes one: it applies from the next match, and what it leaves out stays as it was.
        self.write_cfg("trophies.cfg", "[boss]\nwin = 12\nmvp_bonus = 3\nmax_loss = 4\ndraw = lots\n[LAST_SPARK]\nplaces = 40, 30 20\n[TRAINING]\nwin = 99\n")
        body = play("BOSS", win)
        self.assertEqual((body["cupDelta"], body["cups"], body["mvpCups"]), (15, 20, 3))
        self.assertEqual(play("BOSS", {"outcome": "DEFEAT"})["cups"], 16)
        self.assertEqual(play("LAST_SPARK", {"outcome": "DEFEAT", "placement": 9, "deaths": 1})["cupDelta"], 20)
        self.assertEqual(play("TRAINING", win)["cupDelta"], 0)
        body = play("KNOCKOUT_RUSH", win)
        self.assertEqual((body["cupDelta"], body["mvpCups"]), (10, 2))

    def test_the_server_awards_cups_and_drops(self):
        me = self.player()
        other = self.player("Other")
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "NOPE"}, me["token"])[0], 400)
        # An ordinary player asks for Elite bots and gets the server's difficulty.
        status, plan = self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "fighter": "JUNO", "level": 3, "difficulty": "ELITE"}, me["token"])
        self.assertEqual((status, plan["difficulty"]), (201, "EASY"))
        # The level is the server's (1 here), not the 3 the device asked for, and a locked fighter can't be played.
        self.assertEqual((plan["fighter"], plan["level"], plan["refereed"]), ("JUNO", 1, False))
        self.assertEqual(plan["bots"]["reactiontime"], 0.9)
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "fighter": "KITO"}, me["token"])[0], 409)
        self.assertEqual(len(plan["botNames"]), 9)
        self.assertEqual(len(set(plan["botNames"])), 9)
        self.assertGreater(plan["seed"], 0)
        win = {"outcome": "VICTORY", "placement": 1, "kos": 4, "deaths": 0, "damage": 12000}
        path = "/v1/matches/%d/result" % plan["matchId"]
        self.age_matches()
        self.assertEqual(self.call("POST", path, win, other["token"])[0], 409)  # not their match
        status, body = self.call("POST", path, win, me["token"])
        self.assertEqual(status, 200)
        self.assertEqual((body["cupDelta"], body["cups"], body["drop"]), (25, 25, True))  # 1st place, whatever the difficulty
        # Bolts and the first-win Prisms are the server's to give too: (30 + 2 x 4 KOs) x 0.75 on Easy.
        self.assertEqual((body["bolts"], body["firstWinPrisms"]), (29, 10))
        self.assertEqual((body["account"]["profile"]["bolts"], body["account"]["profile"]["prisms"]), (60 + 29, 10))
        self.assertEqual(body["account"]["profile"]["bestCups"], 25)
        self.assertEqual((body["account"]["drops"], body["account"]["dropsLeftToday"]), (2, 2))
        self.assertEqual(self.call("POST", path, win, me["token"])[0], 409)  # only once
        # Three drops a day: the fourth good finish earns Cups but no drop.
        for expected_drop in (True, True, False):
            _, plan = self.call("POST", "/v1/matches", {"mode": "KNOCKOUT_RUSH"}, me["token"])
            self.assertEqual(len(plan["botNames"]), 5)
            self.age_matches()
            _, body = self.call("POST", "/v1/matches/%d/result" % plan["matchId"], {**win, "placement": 0, "mvp": True}, me["token"])
            self.assertEqual((body["cupDelta"], body["drop"]), (10, expected_drop))
            self.assertEqual(body["firstWinPrisms"], 0)  # only the first win of the day
        self.assertEqual((body["account"]["cups"], body["account"]["drops"], body["account"]["dropsLeftToday"]), (55, 4, 0))
        _, board = self.call("GET", "/v1/leaderboard")
        self.assertEqual((board["players"][0]["name"], board["players"][0]["cups"]), ("Player", 55))
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

    def test_the_player_picks_the_boss(self):
        me = self.player()
        token = me["token"]
        # Asked for by name; anything else, or any other mode, leaves it to the seed.
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "BOSS", "boss": "sweeper"}, token)[1]["boss"], "SWEEPER")
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "BOSS", "boss": "NOPE"}, token)[1]["boss"], "")
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "BOSS"}, token)[1]["boss"], "")
        _, plan = self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "boss": "SWEEPER"}, token)
        self.assertEqual(plan["boss"], "")
        # The choice is kept with the match, for the referee.
        _, plan = self.call("POST", "/v1/matches", {"mode": "BOSS", "boss": "STAMPEDE"}, token)
        self.assertEqual(self.store.open_match(me["id"], plan["matchId"])["boss"], "STAMPEDE")

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

    def test_credits_unlock_fighters_along_the_spark_road(self):
        me = self.player()
        token = me["token"]
        road = self.call("GET", "/v1/me", token=token)[1]["account"]["road"]
        order = [f for f, _ in economy.SPARK_ROAD]
        self.assertEqual([s["fighter"] for s in road["steps"]], order)
        self.assertEqual(road["steps"][0], {"fighter": "BRAKK", "cost": 160, "rarity": "RARE"})
        self.assertEqual(len(road["steps"]), len(rules.FIGHTER_SKINS) - 1)
        # The road has a fixed order: the Credits go toward the first fighter along it that is still locked.
        self.assertEqual(road["target"], "BRAKK")
        self.assertEqual(self.call("POST", "/v1/road/target", {"fighter": "PIP"}, token)[0], 404)
        # Not covered yet.
        status, body = self.call("POST", "/v1/road/unlock", {}, token)
        self.assertEqual((status, body["error"]), (402, "not enough Credits"))
        # The shop sells Credits for Prisms; they go onto the road, and what is left over stays there.
        self.store.grant(me["id"], prisms=5000, credits=110)
        status, body = self.call("POST", "/v1/shop/buy", {"item": "credits_s"}, token)
        self.assertEqual((status, body["reward"]), (200, {"type": "credits", "amount": 60}))
        status, body = self.call("POST", "/v1/road/unlock", {}, token)
        self.assertEqual((status, body["reward"]), (200, {"type": "fighter", "fighter": "BRAKK"}))
        self.assertEqual((body["account"]["profile"]["credits"], body["account"]["road"]["target"]), (10, order[1]))
        # A fighter bought in the shop is simply skipped on the road.
        self.assertEqual(self.call("POST", "/v1/shop/buy", {"item": "fighter_" + order[1]}, token)[0], 200)
        self.assertEqual(self.call("GET", "/v1/me", token=token)[1]["account"]["road"]["target"], order[2])
        # Enough for all the rest: the road is claimed one fighter at a time, in order.
        self.store.grant(me["id"], credits=sum(cost for name, cost in economy.SPARK_ROAD[2:]))
        claimed = []
        while True:
            status, body = self.call("POST", "/v1/road/unlock", {}, token)
            if status != 200:
                break
            claimed.append(body["reward"]["fighter"])
            last = body
        self.assertEqual((claimed, status), (order[2:], 409))
        # The road is finished: the Credits left on it became Glory, and so do any earned from here on.
        profile = last["account"]["profile"]
        self.assertEqual((profile["credits"], profile["glory"]), (0, 10))
        self.assertTrue(all(f["unlocked"] for f in profile["fighters"].values()))
        self.assertEqual(last["account"]["road"]["target"], "")
        status, body = self.call("POST", "/v1/shop/buy", {"item": "credits_s"}, token)
        self.assertEqual(body["reward"], {"type": "glory", "amount": 60})
        self.assertEqual((body["account"]["profile"]["credits"], body["account"]["profile"]["glory"]), (0, 70))
        self.assertEqual(self.call("GET", "/v1/leaderboard")[1]["players"][0]["glory"], 70)

    def test_fighters_that_were_taken_out_are_paid_back(self):
        # A profile from the builds that had more fighters: one it unlocked, one it never did.
        profile = economy.new_profile()
        profile["fighters"]["PIP"] = {"unlocked": True, "level": 4, "ownedSkins": [0]}
        profile["fighters"]["ZERO"] = {"unlocked": False, "level": 1, "ownedSkins": [0]}
        economy.complete(profile)
        self.assertEqual(sorted(profile["fighters"]), sorted(rules.FIGHTER_SKINS))
        self.assertEqual((profile["credits"], profile["glory"]), (160, 0))
        # With the road already finished, the Credits come back as Glory.
        done = economy.new_profile()
        for entry in done["fighters"].values():
            entry["unlocked"] = True
        done["fighters"]["AURA"] = {"unlocked": True, "level": 1, "ownedSkins": [0]}
        economy.complete(done)
        self.assertEqual((done["credits"], done["glory"]), (0, 1600))

    def test_matches_fill_the_spark_pass(self):
        me = self.player()
        token = me["token"]
        season = self.call("GET", "/v1/me", token=token)[1]["account"]["pass"]
        self.assertEqual((season["points"], season["claimed"], season["tierPoints"], len(season["tiers"])), (0, [], 100, economy.PASS_TIERS))
        self.assertEqual(season["tiers"][0], economy.pass_reward(1))
        self.assertEqual(self.call("POST", "/v1/pass/claim", {"tier": 1}, token)[0], 409)  # not reached
        # Three wins: Credits for each, and enough pass points for the first tier.
        win = {"outcome": "VICTORY", "placement": 0, "kos": 3, "deaths": 1, "damage": 4000}
        for _ in range(3):
            _, plan = self.call("POST", "/v1/matches", {"mode": "KNOCKOUT_RUSH"}, token)
            self.age_matches()
            _, body = self.call("POST", "/v1/matches/%d/result" % plan["matchId"], win, token)
            self.assertEqual((body["credits"], body["passPoints"]), (6, 40))
        account = body["account"]
        self.assertEqual((account["profile"]["credits"], account["pass"]["points"]), (18, 120))
        status, body = self.call("POST", "/v1/pass/claim", {"tier": 1}, token)
        self.assertEqual((status, body["reward"]), (200, economy.pass_reward(1)))
        self.assertEqual(body["account"]["pass"]["claimed"], [1])
        self.assertEqual(self.call("POST", "/v1/pass/claim", {"tier": 1}, token)[0], 409)  # only once
        self.assertEqual(self.call("POST", "/v1/pass/claim", {"tier": 2}, token)[0], 409)  # not reached
        self.assertEqual(self.call("POST", "/v1/pass/claim", {"tier": 99}, token)[0], 404)
        # A new season starts everyone from nothing.
        profile = self.store.profile(me["id"])
        day = (profile["pass"]["season"] + 1) * economy.PASS_SEASON_DAYS
        self.assertEqual(economy.pass_view(profile, day), {"season": profile["pass"]["season"] + 1, "points": 0, "claimed": []})

    def test_the_server_opens_every_drop_at_once(self):
        save = {"cups": 0, "capsules": 5, "bolts": 0, "prisms": 0}
        me = self.player(save=save)
        status, body = self.call("POST", "/v1/drops/open-all", {"luck": 14}, me["token"])
        self.assertEqual(status, 200)
        results = body["results"]
        # `luck` was ignored (no developer). The five drops they held were opened; the pieces that split off on
        # the way are theirs to open next.
        self.assertEqual(len(results), 5)
        left = sum(r["pieces"] - 1 for r in results)
        self.assertEqual(body["account"]["drops"], left)
        # Everything that came out is in the profile the server keeps.
        expected = economy.profile_from_save(save)
        for r in results:
            economy.grant(expected, r["reward"])
        self.assertEqual(body["account"]["profile"], expected)
        while left > 0:
            left = self.call("POST", "/v1/drops/open-all", {}, me["token"])[1]["account"]["drops"]
        self.assertEqual(self.call("POST", "/v1/drops/open-all", {}, me["token"])[0], 409)

    def test_drops_pay_three_times_over(self):
        rng = random.Random(5)
        for _ in range(200):
            reward = rules.roll_reward(0, {}, rng)  # Scrap: 60 to 120 Bolts before the buff
            self.assertEqual(reward["type"], "bolts")
            self.assertTrue(180 <= reward["amount"] <= 360 and reward["amount"] % 15 == 0)

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
        # A developer can open drops for free with luck.
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

    def test_the_server_approves_the_difficulty(self):
        me = self.player()
        token = me["token"]
        account = self.call("GET", "/v1/me", token=token)[1]["account"]
        self.assertEqual((account["difficulty"], account["difficulties"]), ("EASY", ["EASY", "NORMAL", "HARD", "ELITE"]))
        # Picking one the server allows: it says yes, remembers it, and plans matches with it.
        status, body = self.call("POST", "/v1/settings/difficulty", {"difficulty": "hard"}, token)
        self.assertEqual((status, body["ok"], body["account"]["difficulty"]), (200, True, "HARD"))
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "difficulty": "ELITE"}, token)[1]["difficulty"], "HARD")
        self.assertEqual(self.call("POST", "/v1/settings/difficulty", {"difficulty": "IMPOSSIBLE"}, token)[0], 400)
        # The operator narrows the choice: the server says no, and a choice that is no longer allowed falls back.
        self.write_cfg("game.cfg", "[players]\ndifficulty = NORMAL\nallowed = EASY, NORMAL\n")
        status, body = self.call("POST", "/v1/settings/difficulty", {"difficulty": "ELITE"}, token)
        self.assertEqual(status, 403)
        self.assertIn("doesn't allow", body["error"])
        account = self.call("GET", "/v1/me", token=token)[1]["account"]
        self.assertEqual((account["difficulty"], account["difficulties"]), ("NORMAL", ["EASY", "NORMAL"]))
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "LAST_SPARK"}, token)[1]["difficulty"], "NORMAL")
        # Developers may pick any.
        self.write_cfg("game.cfg", "[players]\ndifficulty = NORMAL\nallowed = EASY, NORMAL\n[developers]\nids = %s\n" % me["id"])
        self.assertEqual(self.call("POST", "/v1/settings/difficulty", {"difficulty": "ELITE"}, token)[1]["account"]["difficulty"], "ELITE")

    def test_only_developers_start_an_account_over(self):
        me = self.player(save={"cups": 50, "capsules": 3, "bolts": 700})
        self.assertEqual(self.call("POST", "/v1/reset", {}, me["token"])[0], 403)
        self.assertEqual(self.call("GET", "/v1/me", token=me["token"])[1]["account"]["profile"]["bolts"], 700)

    def test_daily_offers_and_the_clock_are_the_servers(self):
        me = self.player(save={"cups": 0, "capsules": 0, "bolts": 5000, "prisms": 500})
        other = self.player("Other")
        account = self.call("GET", "/v1/me", token=me["token"])[1]["account"]
        clock = account["time"]
        self.assertGreater(clock["dayEndsAt"], clock["now"])
        self.assertLessEqual(clock["dayEndsAt"] - clock["now"], 25 * 3600 * 1000)
        self.assertTrue(account["giftAvailable"])
        offers = account["dailyOffers"]
        self.assertEqual(len(offers), 3)
        self.assertEqual(len({o["title"] for o in offers}), 3)
        self.assertTrue(all(o["expiresAt"] == clock["dayEndsAt"] and o["purchased"] == 0 and o["limit"] == 1 for o in offers))
        # Everyone gets the same offers today.
        theirs = self.call("GET", "/v1/me", token=other["token"])[1]["account"]["dailyOffers"]
        self.assertEqual([o["title"] for o in theirs], [o["title"] for o in offers])
        # Buying one: once a day, and only for the day it was shown.
        before = account["profile"]
        self.assertEqual(self.call("POST", "/v1/shop/daily/0/buy", {"day": clock["day"] - 1}, me["token"])[0], 409)
        status, body = self.call("POST", "/v1/shop/daily/0/buy", {"day": clock["day"]}, me["token"])
        self.assertEqual(status, 200)
        offer, after = offers[0], body["account"]["profile"]
        wallet = {"BOLTS": "bolts", "PRISMS": "prisms"}.get(offer["currency"])
        expected = dict(bolts=before["bolts"] + offer["bolts"], prisms=before["prisms"] + offer["prisms"])
        if wallet:
            expected[wallet] -= offer["price"]
        self.assertEqual((after["bolts"], after["prisms"]), (expected["bolts"], expected["prisms"]))
        self.assertEqual([o["purchased"] for o in body["account"]["dailyOffers"]], [1, 0, 0])
        self.assertEqual(self.call("POST", "/v1/shop/daily/0/buy", {"day": clock["day"]}, me["token"])[0], 409)
        self.assertEqual(self.call("POST", "/v1/shop/daily/7/buy", {"day": clock["day"]}, me["token"])[0], 404)
        # The other player's copy is untouched, and the gift flag follows the claim.
        self.assertEqual(self.call("GET", "/v1/me", token=other["token"])[1]["account"]["dailyOffers"][0]["purchased"], 0)
        self.assertFalse(self.call("POST", "/v1/shop/gift", {}, me["token"])[1]["account"]["giftAvailable"])
        # The operator edits shop.cfg: the shop follows, and a broken entry is left out.
        self.write_cfg("shop.cfg", "[settings]\noffers_per_day = 5\n[Only One]\nbolts = 10\ncurrency = FREE\n[Broken]\nskin_fighter = JUNO\nskin = 9\n[Empty]\nprice = 5\n")
        offers = self.call("GET", "/v1/me", token=me["token"])[1]["account"]["dailyOffers"]
        self.assertEqual([o["title"] for o in offers], ["Only One"])

    def test_daily_offers_change_each_day(self):
        pool = [{"title": "Offer %d" % i, "bolts": 10 * (i + 1), "currency": "FREE"} for i in range(8)]
        days = [tuple(o["title"] for o in economy.daily_offers(pool, 3, day)) for day in range(20000, 20030)]
        self.assertTrue(all(len(set(d)) == 3 for d in days))
        self.assertEqual(days[0], tuple(o["title"] for o in economy.daily_offers(pool, 3, 20000)))  # the same all day
        self.assertGreater(len(set(days)), 20)  # and different from day to day
        # Something already owned isn't sold again.
        p = economy.new_profile()
        owned = economy.daily_offers([{"title": "Skin", "skinFighter": "JUNO", "skinIndex": 0, "currency": "FREE"}], 1, 5)
        with self.assertRaises(Refused):
            economy.buy_daily(p, owned, 0, 5)

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


REFEREE = Referee(os.path.join(HERE, "referee", "referee.jar"))


def log(*runs):
    """An input log as the game sends it. Each run is (ticks, flags, moveX, moveY, aimX, aimY)."""
    raw = b"".join(struct.pack(">HBffff", *run) for run in runs)
    return base64.b64encode(gzip.compress(raw)).decode("ascii")


class InputLogs(unittest.TestCase):
    def test_reading(self):
        raw = decode_inputs(log((600, 0, 0, 0, 0, 0), (65535, 3, 1.0, -1.0, 0.5, 0.5)))
        self.assertEqual((len(raw), count_ticks(raw)), (38, 600 + 65535))
        self.assertEqual(count_ticks(raw[:-3]), 600)  # a record cut short doesn't count
        for bad in (None, "", 5, "not base64!", base64.b64encode(b"not gzip").decode()):
            with self.assertRaises(Refused):
                decode_inputs(bad)


@unittest.skipUnless(REFEREE.available, "needs Java and server/referee/referee.jar")
class Refereed(Api):
    """The same server with the referee on: results come from replaying the match, not from the device."""
    referee = REFEREE

    def run_match(self, token, mode, inputs, claim=None, age=600):
        _, plan = self.call("POST", "/v1/matches", {"mode": mode}, token)
        self.assertTrue(plan["refereed"])
        self.age_matches(age)
        body = dict(claim or {})
        if inputs is not None:
            body["inputs"] = inputs
        return plan, self.call("POST", "/v1/matches/%d/result" % plan["matchId"], body, token)

    # The inherited tests that hand in bare results don't apply here: with a referee a bare result is refused (see below).
    def test_the_server_awards_cups_and_drops(self):
        pass

    def test_a_claimed_instant_win_is_refused(self):
        pass

    def test_trophies_cfg_sets_the_cups(self):
        pass

    def test_matches_fill_the_spark_pass(self):
        pass

    def test_the_referee_decides_the_result(self):
        me = self.player()
        lie = {"outcome": "VICTORY", "placement": 1, "kos": 9, "deaths": 0, "damage": 99999, "mvp": True}
        # The player stood still for three minutes of Last Spark. The device says they won.
        still = log((60 * 180, 0, 0, 0, 0, 0))
        plan, (status, body) = self.run_match(me["token"], "LAST_SPARK", still, lie)
        self.assertEqual(status, 200)
        self.assertTrue(body["verified"])
        report = body["report"]
        # The server's result is its own replay of the match, the same as running the referee by hand...
        mine = REFEREE.judge("LAST_SPARK", "JUNO", 1, "EASY", plan["seed"], plan["botNames"], plan["bots"], decode_inputs(still))
        self.assertEqual(report, {k: mine[k] for k in ("outcome", "placement", "kos", "deaths", "damage", "mvp")})
        # ...and nothing like what the device claimed.
        self.assertEqual((report["kos"], report["damage"]), (0, 0))
        self.assertNotEqual((report["outcome"], report["placement"]), ("VICTORY", 1))
        self.assertEqual(body["cupDelta"], rules.cup_delta("LAST_SPARK", report["outcome"], report["placement"], 0, False))
        self.assertEqual((body["fighterCupsBefore"], body["fighterCups"]), (0, body["cupDelta"]))
        self.assertEqual(body["account"]["profile"]["fighters"][body["fighter"]]["cups"], body["cupDelta"])
        self.assertEqual(body["bolts"], economy.match_bolts("LAST_SPARK", report["outcome"], report["placement"], 0, "EASY"))
        row = self.store._db.execute("SELECT outcome, placement, verified, ticks FROM matches WHERE id = ?", (plan["matchId"],)).fetchone()
        self.assertEqual((row["outcome"], row["placement"], row["verified"]), (report["outcome"], report["placement"], 1))
        self.assertGreater(row["ticks"], 0)

    def test_walking_out_is_a_defeat_whatever_is_claimed(self):
        me = self.player()
        win = {"outcome": "VICTORY", "placement": 1, "kos": 5, "deaths": 0, "damage": 20000}
        # Two seconds of inputs and then nothing: the match never finished.
        _, (status, body) = self.run_match(me["token"], "LAST_SPARK", log((120, 0, 0, 0, 0, 0)), win)
        self.assertEqual((status, body["report"]["outcome"], body["report"]["placement"]), (200, "DEFEAT", 10))
        self.assertEqual((body["cupDelta"], body["drop"]), (0, False))
        _, (status, body) = self.run_match(me["token"], "KNOCKOUT_RUSH", log((120, 0, 0, 0, 0, 0)), win)
        self.assertEqual((status, body["report"]["outcome"]), (200, "DEFEAT"))
        self.assertEqual(body["account"]["cups"], 0)

    def test_results_without_a_playable_match_are_refused(self):
        me = self.player()
        win = {"outcome": "VICTORY", "placement": 1, "kos": 5, "deaths": 0, "damage": 20000}
        # No inputs at all.
        plan, (status, body) = self.run_match(me["token"], "LAST_SPARK", None, win)
        self.assertEqual(status, 422)
        self.assertIn("inputs are missing", body["error"])
        self.assertEqual(self.call("POST", "/v1/matches/%d/result" % plan["matchId"], win, me["token"])[0], 409)  # and the match is closed
        # Inputs that aren't a log.
        self.assertEqual(self.run_match(me["token"], "LAST_SPARK", "garbage", win)[1][0], 400)
        # Three minutes of match handed in two seconds after it was set up.
        _, (status, body) = self.run_match(me["token"], "LAST_SPARK", log((60 * 180, 0, 0, 0, 0, 0)), win, age=2)
        self.assertEqual(status, 422)
        self.assertIn("more match than time", body["error"])
        account = self.call("GET", "/v1/me", token=me["token"])[1]["account"]
        self.assertEqual((account["cups"], account["drops"], account["profile"]["bolts"]), (0, 1, 60))
        self.assertEqual(self.store.player(me["id"])["flags"], 3)

    def test_boss_mode_pays_what_trophies_cfg_says(self):
        me = self.player()
        # Left alone, the boss wins (or the clock runs out): nothing is lost for that.
        _, (status, body) = self.run_match(me["token"], "BOSS", log((60 * 400, 0, 0, 0, 0, 0)))
        self.assertEqual((status, body["verified"], body["cupDelta"]), (200, True, 0))
        self.assertNotEqual(body["report"]["outcome"], "VICTORY")

    def test_the_training_area_needs_no_referee(self):
        me = self.player()
        _, (status, body) = self.run_match(me["token"], "TRAINING", None, {"outcome": "DEFEAT"})
        self.assertEqual((status, body["verified"], body["cupDelta"], body["bolts"]), (200, False, 0, 0))


if __name__ == "__main__":
    unittest.main()
