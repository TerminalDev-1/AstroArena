"""The pages that are a list of things: the daily shop's offers, the News tab, and the server's messages."""

from __future__ import annotations

import time
import tkinter as tk
from tkinter import ttk

import backend
import cfgfile
from listeditor import ListEditor
from nativebox import DictationEntry, DictationText
from theme import Page, as_int, choice, entry, heading, number, px

NONE = "None"
PAID_WITH = {"FREE": "Free", "PRISMS": "Crystals", "BOLTS": "Power Ups"}
COLOURS = ["0", "1", "2", "3", "4", "5"]
TAGS = ["NEW", "BALANCE", "EVENT", "FIX", "NEWS"]


def _row(parent, row: int, label: str, widget_maker, note: str = "") -> None:
    ttk.Label(parent, text=label, style="Dim.TLabel").grid(row=row, column=0, sticky="w", padx=(0, px(14)), pady=px(3))
    widget_maker(parent).grid(row=row, column=1, sticky="w", columnspan=1 if note else 2, pady=px(3))
    if note:
        ttk.Label(parent, text=note, style="Small.TLabel").grid(row=row, column=2, sticky="w", padx=(px(10), 0))


def _unique(items: list[dict], key: str, what: str) -> str | None:
    """Why these can't be saved as sections of a .cfg file, or None if they can."""
    seen = set()
    for item in items:
        name = item[key].strip()
        if not name:
            return "Every %s needs a %s." % (what, key)
        if "]" in name or "[" in name:
            return "A %s can't have [ or ] in it." % key
        if name.lower() in seen or name.lower() in ("settings", "default"):
            return "Two %ss can't share the %s \"%s\"." % (what, key, name)
        seen.add(name.lower())
    return None


class ShopPage(Page):
    title = "Shop"
    about = "The pool the daily offers are picked from. Every day the server picks a few, the same for everyone; each player can buy each once that day."

    def __init__(self, parent, app):
        super().__init__(parent, app)
        top = ttk.Frame(self.body)
        top.pack(anchor="w", pady=(0, px(12)))
        ttk.Label(top, text="Offers in the shop each day").pack(side="left")
        self.per_day = tk.StringVar()
        number(top, self.per_day, 0, 12, width=4).pack(side="left", padx=px(10))
        self.v = {key: tk.StringVar() for key in ("title", "bolts", "prisms", "fighter", "skin_fighter", "skin", "currency", "price", "was", "theme")}
        self.editor = ListEditor(self.body, [("title", "Offer", 140, "w"), ("gives", "Gives", 150, "w"), ("cost", "Cost", 0, "w")],
                                 self._summary, self._blank, self._build, self._show, self._keep, height=13, list_width=400, noun="offer")
        self.editor.pack(fill="both", expand=True)
        self.action("Save", self._save, "Accent.TButton")
        self.action("Reload", self._load)
        self._load()

    @staticmethod
    def _blank() -> dict:
        return {"title": "New offer", "bolts": 0, "prisms": 0, "fighter": "", "skin_fighter": "", "skin": 1, "currency": "FREE", "price": 0, "was": 0, "theme": 0}

    @staticmethod
    def _summary(o: dict) -> tuple:
        gives = [("{:,} Power Ups".format(o["bolts"]) if o["bolts"] else ""), ("{:,} Crystals".format(o["prisms"]) if o["prisms"] else ""),
                 o["fighter"].title(), ("%s colourway %s" % (o["skin_fighter"].title(), o["skin"]) if o["skin_fighter"] else "")]
        cost = "Free" if o["currency"] == "FREE" else "{:,} {}".format(o["price"], PAID_WITH.get(o["currency"], o["currency"]))
        return o["title"], ", ".join(g for g in gives if g) or "nothing yet", cost

    def _build(self, form) -> None:
        fighters = [NONE] + [f.title() for f in backend.FIGHTERS]
        heading(form, "The offer").grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, px(6)))
        _row(form, 1, "Title", lambda p: DictationEntry(p, self.v["title"], chars=28, limit=24), "24 characters at most")
        heading(form, "It gives").grid(row=2, column=0, columnspan=3, sticky="w", pady=(px(10), px(4)))
        _row(form, 3, "Power Ups", lambda p: number(p, self.v["bolts"]))
        _row(form, 4, "Crystals", lambda p: number(p, self.v["prisms"]))
        _row(form, 5, "A fighter", lambda p: choice(p, self.v["fighter"], fighters, width=12))

        def colourway(parent):
            row = ttk.Frame(parent)
            choice(row, self.v["skin_fighter"], fighters, width=12).pack(side="left")
            ttk.Label(row, text="number", style="Dim.TLabel").pack(side="left", padx=px(8))
            choice(row, self.v["skin"], ["1", "2"], width=3).pack(side="left")
            return row
        _row(form, 6, "A colourway of", colourway)
        heading(form, "It costs").grid(row=7, column=0, columnspan=3, sticky="w", pady=(px(10), px(4)))
        _row(form, 8, "Paid with", lambda p: choice(p, self.v["currency"], list(PAID_WITH.values()), width=12))
        _row(form, 9, "Price", lambda p: number(p, self.v["price"]))
        _row(form, 10, "Was", lambda p: number(p, self.v["was"]), "shown crossed out · 0 for none")
        _row(form, 11, "Card colour", lambda p: choice(p, self.v["theme"], COLOURS, width=3))

    def _show(self, o: dict) -> None:
        self.v["title"].set(o["title"])
        for key in ("bolts", "prisms", "price", "was"):
            self.v[key].set(o[key])
        self.v["fighter"].set(o["fighter"].title() or NONE)
        self.v["skin_fighter"].set(o["skin_fighter"].title() or NONE)
        self.v["skin"].set(str(o["skin"] or 1))
        self.v["currency"].set(PAID_WITH.get(o["currency"], "Free"))
        self.v["theme"].set(str(o["theme"]))

    def _keep(self, o: dict) -> None:
        def name(var) -> str:
            return "" if var.get() == NONE else var.get().upper()
        o["title"] = self.v["title"].get().strip()[:24]
        for key in ("bolts", "prisms", "price", "was"):
            o[key] = as_int(self.v[key])
        o["fighter"], o["skin_fighter"] = name(self.v["fighter"]), name(self.v["skin_fighter"])
        o["skin"], o["theme"] = as_int(self.v["skin"], 1, 1, 2), as_int(self.v["theme"], 0, 0, 5)
        o["currency"] = next((code for code, label in PAID_WITH.items() if label == self.v["currency"].get()), "FREE")

    def _load(self) -> None:
        shop = cfgfile.read_ini(backend.cfg("shop.cfg"))
        offers = []
        for section in shop.sections():
            if section.lower() == "settings":
                continue
            s = shop[section]

            def n(key: str, default: int = 0) -> int:
                try:
                    return int(s.get(key, default))
                except ValueError:
                    return default
            offers.append({"title": section, "bolts": n("bolts"), "prisms": n("prisms"), "fighter": s.get("fighter", "").upper(),
                           "skin_fighter": s.get("skin_fighter", "").upper(), "skin": n("skin", 1), "currency": s.get("currency", "PRISMS").upper(),
                           "price": n("price"), "was": n("was"), "theme": n("theme")})
        self.per_day.set(shop.get("settings", "offers_per_day", fallback="3"))
        self.editor.set_items(offers)

    def _save(self) -> None:
        offers = self.editor.items()
        problem = _unique(offers, "title", "offer")
        if problem:
            self.say(problem, bad=True)
            return
        sections: list[tuple[str, dict]] = [("settings", {"offers_per_day": as_int(self.per_day, 3, 0, 12)})]
        for o in offers:
            values: dict = {}
            for key in ("bolts", "prisms"):
                if o[key]:
                    values[key] = o[key]
            if o["fighter"]:
                values["fighter"] = o["fighter"]
            if o["skin_fighter"]:
                values["skin_fighter"], values["skin"] = o["skin_fighter"], o["skin"]
            values["currency"] = o["currency"]
            if o["currency"] != "FREE":
                values["price"] = o["price"]
                if o["was"] > o["price"]:
                    values["was"] = o["was"]
            if o["theme"]:
                values["theme"] = o["theme"]
            sections.append((o["title"], values))
        cfgfile.write_sections(backend.cfg("shop.cfg"), sections)
        self.say("Saved. The shop follows at once.")


class NewsPage(Page):
    title = "News"
    about = "The News tab in the game, newest first. Add an item when something changes that players will notice."

    def __init__(self, parent, app):
        super().__init__(parent, app)
        self.headline, self.date, self.tag = tk.StringVar(), tk.StringVar(), tk.StringVar()
        self.editor = ListEditor(self.body, [("date", "Date", 88, "w"), ("tag", "Tag", 72, "w"), ("title", "Headline", 0, "w")],
                                 lambda n: (n["date"], n["tag"], n["title"]), self._blank, self._build, self._show, self._keep,
                                 height=13, list_width=400, new_on_top=True, noun="item")
        self.editor.pack(fill="both", expand=True)
        self.action("Save", self._save, "Accent.TButton")
        self.action("Reload", self._load)
        self._load()

    @staticmethod
    def _blank() -> dict:
        return {"title": "New headline", "date": time.strftime("%Y-%m-%d"), "tag": "NEWS", "text": ""}

    def _build(self, form) -> None:
        ttk.Label(form, text="Headline", style="Dim.TLabel").pack(anchor="w")
        DictationEntry(form, self.headline, limit=80).pack(fill="x", pady=(px(2), px(10)))
        row = ttk.Frame(form)
        row.pack(anchor="w", pady=(0, px(10)))
        ttk.Label(row, text="Date", style="Dim.TLabel").pack(side="left")
        entry(row, self.date, width=12).pack(side="left", padx=(px(8), px(22)))
        ttk.Label(row, text="Tag", style="Dim.TLabel").pack(side="left")
        choice(row, self.tag, TAGS, width=10).pack(side="left", padx=px(8))
        ttk.Label(form, text="Text", style="Dim.TLabel").pack(anchor="w")
        self.text = DictationText(form)
        self.text.pack(fill="both", expand=True, pady=(px(2), 0))

    def _show(self, n: dict) -> None:
        self.headline.set(n["title"])
        self.date.set(n["date"])
        self.tag.set(n["tag"] if n["tag"] in TAGS else "NEWS")
        self.text.set(n["text"])

    def _keep(self, n: dict) -> None:
        n["title"] = " ".join(self.headline.get().split())[:80]
        n["date"], n["tag"] = self.date.get().strip()[:20], self.tag.get()
        n["text"] = " ".join(self.text.get().split())

    def _load(self) -> None:
        news = cfgfile.read_ini(backend.cfg("news.cfg"))
        self.editor.set_items([
            {"title": s, "date": news.get(s, "date", fallback=""), "tag": news.get(s, "tag", fallback="NEWS").strip().upper(),
             "text": " ".join(news.get(s, "text", fallback="").split())}
            for s in news.sections()])

    def _save(self) -> None:
        items = self.editor.items()
        problem = _unique(items, "title", "item")
        if problem:
            self.say(problem.replace("title", "headline"), bad=True)
            return
        cfgfile.write_sections(backend.cfg("news.cfg"), [(n["title"], {"date": n["date"], "tag": n["tag"], "text": n["text"]}) for n in items])
        self.say("Saved. Players see it the next time they open News.")


class MessagesPage(Page):
    title = "Messages"
    about = "The line on the home screen, and which builds are turned away. The first rule that fits a player's build wins."

    WHO = "*  everyone      50  only build 50      <50  older than 50      >=50  build 50 and newer"

    def __init__(self, parent, app):
        super().__init__(parent, app)
        self.forms: list[tuple[tk.StringVar, tk.StringVar]] = []
        heading(self.body, "Home screen notice", "one short line under the top bar").pack(anchor="w", pady=(0, px(6)))
        self.notices = self._editor("notice")
        heading(self.body, "Builds turned away", "they see the message and can't play online").pack(anchor="w", pady=(px(14), px(6)))
        self.refused = self._editor("rule")
        ttk.Label(self.body, style="Small.TLabel", justify="left", text=(
            self.WHO + "\nKeep a rule that turns away everything below 47: build 46 let players put updates off.")).pack(anchor="w", pady=(px(10), 0))
        self.action("Save", self._save, "Accent.TButton")
        self.action("Reload", self._load)
        self._load()

    def _editor(self, noun: str) -> ListEditor:
        who, text = tk.StringVar(), tk.StringVar()

        def build(form) -> None:
            ttk.Label(form, text="For", style="Dim.TLabel").pack(side="left")
            entry(form, who, width=10).pack(side="left", padx=(px(8), px(18)))
            ttk.Label(form, text="Message", style="Dim.TLabel").pack(side="left")
            DictationEntry(form, text, limit=300).pack(side="left", fill="x", expand=True, padx=(px(8), 0))

        def show(item: dict) -> None:
            who.set(item["rule"])
            text.set(item["text"])

        def keep(item: dict) -> None:
            item["rule"], item["text"] = who.get().strip() or "*", " ".join(text.get().split())

        editor = ListEditor(self.body, [("rule", "For", 90, "w"), ("text", "Message", 0, "w")], lambda r: (r["rule"], r["text"]),
                            lambda: {"rule": "*", "text": ""}, build, show, keep, height=3, form_below=True, noun=noun)
        editor.pack(fill="x")
        return editor

    def _load(self) -> None:
        for editor, name in ((self.notices, "notices.cfg"), (self.refused, "versions_not_supported.cfg")):
            editor.set_items([{"rule": rule, "text": text} for rule, text in cfgfile.read_rules(backend.cfg(name))])

    def _save(self) -> None:
        for editor, name in ((self.notices, "notices.cfg"), (self.refused, "versions_not_supported.cfg")):
            rules = editor.items()
            if any("|" in r["rule"] or " " in r["rule"] for r in rules):
                self.say("A rule is one word, like * or <50.", bad=True)
                return
            cfgfile.write_rules(backend.cfg(name), [(r["rule"], r["text"]) for r in rules])
        self.say("Saved. It applies at once.")
