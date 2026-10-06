"""Run with:  python -m unittest  (from the server_manager directory)

The manager writes the same files the server reads, so what matters is that a file it has written still
means to the server exactly what was meant, and still holds the comments its owner wrote.
"""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backend
import cfgfile
import page_accounts

FILES = ("game.cfg", "trophies.cfg", "bots.cfg", "shop.cfg", "news.cfg", "notices.cfg", "versions_not_supported.cfg")


class Files(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name in FILES:
            shutil.copy(os.path.join(backend.SERVER, name), self.dir)
        self.config = backend.Config(self.dir)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def path(self, name):
        return os.path.join(self.dir, name)

    def comments(self, name):
        return [line for line in cfgfile.read_text(self.path(name)).splitlines() if line.strip().startswith("#")]

    def test_settings_change_in_place(self):
        before = self.comments("game.cfg")
        cfgfile.set_values(self.path("game.cfg"), "players", {"difficulty": "HARD", "allowed": "NORMAL, HARD"})
        cfgfile.set_values(self.path("game.cfg"), "developers", {"ids": "aaaaaaaa11111111, bbbbbbbb22222222"})
        cfgfile.set_values(self.path("game.cfg"), "accounts", {"import_saves": "no", "brand_new": 5})
        config = backend.Config(self.dir)
        self.assertEqual((config.default_difficulty(), config.allowed_difficulties()), ("HARD", ["NORMAL", "HARD"]))
        self.assertTrue(config.is_developer("bbbbbbbb22222222"))
        self.assertFalse(config.import_saves())
        self.assertEqual(self.comments("game.cfg"), before)  # every comment is where it was
        # A key that wasn't there goes at the end of its own section; a section that wasn't there is added.
        game = cfgfile.read_ini(self.path("game.cfg"))
        self.assertEqual(game.get("accounts", "brand_new"), "5")
        cfgfile.set_values(self.path("game.cfg"), "extras", {"one": 1})
        self.assertEqual(cfgfile.read_ini(self.path("game.cfg")).get("extras", "one"), "1")

    def test_trophies_reach_the_server(self):
        cfgfile.set_values(self.path("trophies.cfg"), "LAST_SPARK", {"places": "9, 8, 7"})
        cfgfile.set_values(self.path("trophies.cfg"), "BOSS", {"win": 40, "max_loss": 3})
        cups = backend.Config(self.dir).cups()
        self.assertEqual(cups["LAST_SPARK"]["places"], [9, 8, 7])
        self.assertEqual((cups["BOSS"]["win"], cups["BOSS"]["max_loss"], cups["KNOCKOUT_RUSH"]["win"]), (40, 3, 8))

    def test_lists_are_written_under_the_files_own_comment(self):
        pool_before, per_day = self.config.daily_pool()
        shop = cfgfile.read_ini(self.path("shop.cfg"))
        sections = [(name, dict(shop[name])) for name in shop.sections()]
        head = cfgfile.head_comment(self.path("shop.cfg"))
        cfgfile.write_sections(self.path("shop.cfg"), sections)
        self.assertEqual(backend.Config(self.dir).daily_pool(), (pool_before, per_day))
        self.assertTrue(cfgfile.read_text(self.path("shop.cfg")).startswith(head + "\n\n[settings]"))

        news_before = self.config.news()
        news = cfgfile.read_ini(self.path("news.cfg"))
        items = [(s, {"date": news.get(s, "date"), "tag": news.get(s, "tag"), "text": " ".join(news.get(s, "text").split())}) for s in news.sections()]
        long = "A sentence that goes on. " * 30 + "100% done; # not a comment"
        cfgfile.write_sections(self.path("news.cfg"), [("Brand new", {"date": "2026-10-06", "tag": "NEW", "text": long})] + items)
        after = backend.Config(self.dir).news()
        self.assertEqual(after[1:], news_before[:29])
        self.assertEqual((after[0]["title"], after[0]["text"]), ("Brand new", long))

    def test_rules_keep_their_comments(self):
        for name in ("notices.cfg", "versions_not_supported.cfg"):
            before, rules = self.comments(name), cfgfile.read_rules(self.path(name))
            cfgfile.write_rules(self.path(name), rules + [(">=99", "Hello | there")])
            self.assertEqual(cfgfile.read_rules(self.path(name)), rules + [(">=99", "Hello | there")])
            self.assertEqual(self.comments(name), before)
        config = backend.Config(self.dir)
        self.assertIsNotNone(config.unsupported_message("46"))
        self.assertIsNone(config.unsupported_message("50"))


class Pages(unittest.TestCase):
    """The pages themselves, driven the way a hand on the mouse would, against a copy of the server's folder."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name in FILES:
            shutil.copy(os.path.join(backend.SERVER, name), self.dir)
        self.real = (backend.SERVER, backend._store)
        backend.SERVER, backend._store = self.dir, backend.Store(os.path.join(self.dir, "test.db"))
        self.player = backend._store.register("Tester", "50")["id"]
        import manager
        try:
            self.app = manager.App("Accounts")
        except Exception as problem:  # no display to open a window on
            self.skipTest(str(problem))

    def tearDown(self):
        if hasattr(self, "app"):
            self.app.destroy()
        backend._store.close()
        backend.SERVER, backend._store = self.real
        shutil.rmtree(self.dir, ignore_errors=True)

    def page(self, name):
        self.app.show(name)
        self.app.update()
        return self.app.pages[name]

    def test_an_account_is_changed_and_disabled(self):
        page = self.page("Accounts")
        self.assertEqual(page.current["id"], self.player)
        page.numbers["cups"].set("1,250")
        page.fighters["KITO"][0].set(True)
        page.fighters["KITO"][1].set("7")
        page.disabled.set(True)
        page.reason.set("Testing the manager")
        page.length.set("3 days")
        page._apply()
        account = backend._store.accounts()[0]
        self.assertEqual((account["cups"], account["fighters"]["KITO"]["unlocked"], account["fighters"]["KITO"]["level"]), (1250, True, 7))
        self.assertEqual((account["disabled"], account["disabled_reason"]), (True, "Testing the manager"))
        self.assertAlmostEqual(account["disabled_until"] - backend.when("3 days"), 0, delta=5)
        # Applying again with nothing touched changes nothing, and the end it already has is left alone.
        self.assertTrue(page.length.get().startswith("Leave it: ends "))
        page._apply()
        self.assertEqual(backend._store.accounts()[0], account)
        self.assertRegex(page.length.get(), r"ends \d\d/\d\d/20\d\d \d\d:\d\d$")  # day/month/year
        # A date and time picked the UK way round: the 5th of November, not the 11th of May.
        import datetime
        year = datetime.date.today().year + 1
        page.length.set(page_accounts.PICKED)
        for var, value in ((page.day, "05"), (page.month, "11"), (page.year, str(year)), (page.hour, "18"), (page.minute, "30")):
            var.set(value)
        page._apply()
        self.assertEqual(backend._store.accounts()[0]["disabled_until"], datetime.datetime(year, 11, 5, 18, 30).timestamp())
        # A day that doesn't exist, or one that has gone, is refused and nothing changes.
        page.length.set(page_accounts.PICKED)
        page.day.set("31")
        page._apply()
        page.length.set(page_accounts.PICKED)
        page.day.set("05")
        page.year.set("2020")
        page._apply()
        self.assertEqual(backend._store.accounts()[0]["disabled_until"], datetime.datetime(year, 11, 5, 18, 30).timestamp())
        # The reason box is a Windows text box; what is in it is still what gets saved.
        page._show(page.current)
        self.app.update()
        page.reason.set("Said  out loud")
        self.assertEqual(page.reason.get(), "Said  out loud")
        page._apply()
        self.assertEqual(backend._store.accounts()[0]["disabled_reason"], "Said out loud")
        page.disabled.set(False)
        page._apply()
        account = backend._store.accounts()[0]
        self.assertEqual((account["disabled"], account["disabled_reason"], account["disabled_until"], account["cups"]), (False, "", 0, 1250))

    def test_the_settings_pages_save_what_the_server_reads(self):
        page = self.page("Trophies")
        page.modes["BOSS"]["win"].set("75")
        page.places[0].set("30")
        page._save()
        cups = backend.Config(self.dir).cups()
        self.assertEqual((cups["BOSS"]["win"], cups["LAST_SPARK"]["places"][:2]), (75, [30, 22]))

        page = self.page("Game rules")
        page.allowed["ELITE"].set(False)
        page.pick.set("Tester (%s)" % self.player)
        page._add()
        page._save()
        config = backend.Config(self.dir)
        self.assertEqual(config.allowed_difficulties(), ["EASY", "NORMAL", "HARD"])
        self.assertTrue(config.is_developer(self.player))

        page = self.page("Bots")
        page.values[("EASY", "reactiontime")].set("1.5")
        page.values[("EASY", "focusweakest")].set(True)
        page._save()
        easy = backend.Config(self.dir).bots()["EASY"]
        self.assertEqual((easy["reactiontime"], easy["focusweakest"]), (1.5, True))

    def test_the_list_pages_save_what_the_server_reads(self):
        before, _ = backend.Config(self.dir).daily_pool()
        page = self.page("Shop")
        page.per_day.set("5")
        page.editor._new()
        self.app.update()
        page.v["title"].set("Manager Special")
        page.v["prisms"].set("40")
        page.v["currency"].set("Power Ups")
        page.v["price"].set("900")
        page._save()
        pool, per_day = backend.Config(self.dir).daily_pool()
        self.assertEqual((per_day, pool[:-1]), (5, before))  # every offer that was there is unchanged
        self.assertEqual((pool[-1]["title"], int(pool[-1]["prisms"]), pool[-1]["currency"], int(pool[-1]["price"])), ("Manager Special", 40, "BOLTS", 900))

        before = backend.Config(self.dir).news()
        page = self.page("News")
        page.editor._new()
        self.app.update()
        page.headline.set("From the manager")
        page.tag.set("EVENT")
        page.text.set("Two lines\nbecome one.")
        page._save()
        news = backend.Config(self.dir).news()
        self.assertEqual((news[0]["title"], news[0]["tag"], news[0]["text"]), ("From the manager", "EVENT", "Two lines become one."))
        self.assertEqual(news[1:], before[:29])

        page = self.page("Messages")
        page.refused._new()
        self.app.update()
        page._save()
        self.assertEqual(cfgfile.read_rules(os.path.join(self.dir, "versions_not_supported.cfg"))[-1], ("*", ""))


if __name__ == "__main__":
    unittest.main()
