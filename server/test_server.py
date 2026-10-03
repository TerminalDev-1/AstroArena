"""Run with:  python -m unittest  (from the server directory)"""

import json
import os
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from astro.app import serve
from astro.config import matches, parse_version

HERE = os.path.dirname(os.path.abspath(__file__))


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


class Api(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name in ("versions_not_supported.cfg", "notices.cfg", "bots.cfg"):
            shutil.copy(os.path.join(HERE, name), self.dir)
        self.httpd = serve(self.dir, "127.0.0.1", 0, quiet=True)
        self.base = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.httpd.game.store.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def call(self, method, path, body=None, token=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        if token:
            request.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(request, timeout=5) as reply:
                return reply.status, json.loads(reply.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_health_and_status(self):
        status, body = self.call("GET", "/v1/health")
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        _, old = self.call("GET", "/v1/status?version=0.5.1-preview")
        self.assertFalse(old["supported"])
        self.assertIn("no longer supported", old["message"])
        _, new = self.call("GET", "/v1/status?version=6")
        self.assertTrue(new["supported"])
        self.assertEqual(new["notice"], "Welcome to the AstroArena servers!")

    def test_cfg_edits_apply_without_restart(self):
        self.assertTrue(self.call("GET", "/v1/status?version=6")[1]["supported"])
        path = os.path.join(self.dir, "versions_not_supported.cfg")
        with open(path, "w", encoding="utf-8") as f:
            f.write("<=6 | Time to move on.\n")
        os.utime(path, (2_000_000_000, 2_000_000_000))  # make sure the timestamp differs
        _, body = self.call("GET", "/v1/status?version=6")
        self.assertFalse(body["supported"])
        self.assertEqual(body["message"], "Time to move on.")

    def test_bot_config(self):
        _, body = self.call("GET", "/v1/config")
        self.assertEqual(set(body["bots"]), {"EASY", "NORMAL", "HARD", "ELITE"})
        self.assertEqual(body["bots"]["ELITE"]["reactiontime"], 0.12)
        self.assertIs(body["bots"]["EASY"]["shotdiscipline"], False)

    def test_accounts_saves_and_leaderboard(self):
        self.assertEqual(self.call("GET", "/v1/save")[0], 401)
        self.assertEqual(self.call("GET", "/v1/save", token="nope")[0], 401)
        status, me = self.call("POST", "/v1/players", {"name": "Tester", "version": "6"})
        self.assertEqual(status, 201)
        self.assertEqual(self.call("GET", "/v1/save", token=me["token"])[0], 404)
        save = {"cups": 321, "selectedFighter": "KITO", "settings": {"playerName": "Ace <script>"}, "bolts": 5}
        status, body = self.call("PUT", "/v1/save", {"save": save}, me["token"])
        self.assertEqual((status, body["revision"]), (200, 1))
        self.assertEqual(self.call("PUT", "/v1/save", {"save": save}, me["token"])[1]["revision"], 2)
        _, stored = self.call("GET", "/v1/save", token=me["token"])
        self.assertEqual(stored["save"], save)
        self.assertEqual(stored["revision"], 2)
        # One player can't read another's save.
        _, other = self.call("POST", "/v1/players", {"name": "Other"})
        self.assertEqual(self.call("GET", "/v1/save", token=other["token"])[0], 404)
        _, board = self.call("GET", "/v1/leaderboard?limit=10")
        self.assertEqual(board["players"][0]["cups"], 321)
        self.assertEqual(board["players"][0]["name"], "Ace script")  # names are cleaned
        self.assertEqual(board["players"][0]["fighter"], "KITO")
        self.assertNotIn("token", board["players"][0])

    def test_matches(self):
        _, me = self.call("POST", "/v1/players", {"name": "Tester"})
        _, other = self.call("POST", "/v1/players", {"name": "Other"})
        self.assertEqual(self.call("POST", "/v1/matches", {"mode": "NOPE"}, me["token"])[0], 400)
        status, plan = self.call("POST", "/v1/matches", {"mode": "LAST_SPARK", "fighter": "JUNO", "level": 3, "difficulty": "HARD"}, me["token"])
        self.assertEqual(status, 201)
        self.assertEqual(len(plan["botNames"]), 9)
        self.assertEqual(len(set(plan["botNames"])), 9)
        self.assertGreater(plan["seed"], 0)
        _, second = self.call("POST", "/v1/matches", {"mode": "KNOCKOUT_RUSH"}, me["token"])
        self.assertNotEqual(plan["seed"], second["seed"])
        self.assertEqual(len(second["botNames"]), 5)
        result = {"outcome": "VICTORY", "placement": 1, "kos": 4, "deaths": 0, "damage": 12000}
        path = "/v1/matches/%d/result" % plan["matchId"]
        self.assertEqual(self.call("POST", path, result, other["token"])[0], 409)  # not their match
        self.assertEqual(self.call("POST", path, result, me["token"])[0], 200)
        self.assertEqual(self.call("POST", path, result, me["token"])[0], 409)  # only once
        _, health = self.call("GET", "/v1/health")
        self.assertEqual((health["players"], health["matches"], health["finished"]), (2, 2, 1))

    def test_bad_requests(self):
        self.assertEqual(self.call("GET", "/v1/nothing")[0], 404)
        request = urllib.request.Request(self.base + "/v1/players", data=b"not json", method="POST")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=5)
        self.assertEqual(caught.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
