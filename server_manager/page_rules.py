"""The pages that are a fixed set of settings: Cups per mode, who may do what, and how the bots play."""

from __future__ import annotations

import re
import tkinter as tk
from tkinter import ttk

import backend
import cfgfile
from theme import Page, as_int, choice, entry, heading, number, px, table


class TrophiesPage(Page):
    title = "Trophies"
    about = "The Cups each mode pays. A fighter wins and loses the same Cups as the player who plays it. Saved changes count from the next match that finishes; no restart, no new build."

    FIELDS = [("win", "A win pays", -1000), ("mvp_bonus", "Extra as the MVP", -1000), ("draw", "A draw pays", -1000),
              ("max_loss", "A defeat costs, at most", 0), ("loss_step", "…1 Cup for every this many Cups held", 0)]

    def __init__(self, parent, app):
        super().__init__(parent, app)
        heading(self.body, "Last Spark", "Cups by finishing place").pack(anchor="w", pady=(0, px(6)))
        row = ttk.Frame(self.body)
        row.pack(anchor="w")
        self.places = [tk.StringVar() for _ in range(10)]
        for i, var in enumerate(self.places):
            cell = ttk.Frame(row)
            cell.pack(side="left", padx=(0, px(8)))
            ttk.Label(cell, text="%d%s" % (i + 1, ("st", "nd", "rd")[i] if i < 3 else "th"), style="Small.TLabel").pack(anchor="w")
            number(cell, var, -1000, 1000, width=5).pack()

        self.modes: dict[str, dict[str, tk.StringVar]] = {}
        columns = ttk.Frame(self.body)
        columns.pack(anchor="w", pady=(px(22), 0))
        for column, (mode, label, note) in enumerate((("KNOCKOUT_RUSH", "Knockout Rush", "3v3"), ("BOSS", "Boss Mode", "you against a boss"))):
            side = ttk.Frame(columns)
            side.grid(row=0, column=column, sticky="nw", padx=(0, px(60)))
            heading(side, label, note).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, px(6)))
            self.modes[mode] = {}
            for r, (key, text, low) in enumerate(self.FIELDS, start=1):
                ttk.Label(side, text=text).grid(row=r, column=0, sticky="w", padx=(0, px(14)), pady=px(3))
                self.modes[mode][key] = tk.StringVar()
                number(side, self.modes[mode][key], low, 100000, width=7).grid(row=r, column=1, sticky="w")
        ttk.Label(self.body, style="Small.TLabel", justify="left", text=(
            "Leave \"every this many Cups held\" at 0 and every defeat costs the full amount.\n"
            "The Training Area never pays Cups, 1v1 earns nothing yet, and Cups never go below zero.")).pack(anchor="w", pady=(px(18), 0))
        self.action("Save", self._save, "Accent.TButton")
        self.action("Reload", self._load)
        self._load()

    def _load(self) -> None:
        cups = backend.config().cups()
        places = (cups["LAST_SPARK"].get("places") or []) + [0] * 10
        for var, value in zip(self.places, places):
            var.set(value)
        for mode, fields in self.modes.items():
            for key, var in fields.items():
                var.set(cups[mode].get(key, 0))

    def _save(self) -> None:
        path = backend.cfg("trophies.cfg")
        cfgfile.set_values(path, "LAST_SPARK", {"places": ", ".join(str(as_int(v, 0, -1000, 1000)) for v in self.places)})
        for mode, fields in self.modes.items():
            cfgfile.set_values(path, mode, {key: as_int(var, 0, -1000, 100000) for key, var in fields.items()})
        self._load()
        self.say("Saved. It counts from the next match that finishes.")


class GamePage(Page):
    title = "Game rules"
    about = "Which bot difficulties players may pick, who the developers are, and how new accounts start."

    def __init__(self, parent, app):
        super().__init__(parent, app)
        heading(self.body, "Bot difficulty").pack(anchor="w", pady=(0, px(6)))
        row = ttk.Frame(self.body)
        row.pack(anchor="w")
        ttk.Label(row, text="New players start on").pack(side="left")
        self.default = tk.StringVar()
        choice(row, self.default, backend.DIFFICULTIES, width=10).pack(side="left", padx=(px(8), px(30)))
        ttk.Label(row, text="Players may pick").pack(side="left", padx=(0, px(10)))
        self.allowed = {name: tk.BooleanVar() for name in backend.DIFFICULTIES}
        for name, var in self.allowed.items():
            ttk.Checkbutton(row, text=name.title(), variable=var).pack(side="left", padx=(0, px(14)))

        heading(self.body, "Developers", "they get the debug menu, can make shop deals and can reset an account").pack(anchor="w", pady=(px(16), px(4)))
        self.everyone = tk.BooleanVar()
        ttk.Checkbutton(self.body, text="Everyone is a developer (only for a private test server)", variable=self.everyone).pack(anchor="w")
        frame, self.devs = table(self.body, [("name", "Player", 190, "w"), ("id", "Player id", 0, "w")], height=4)
        frame.pack(anchor="w", fill="x", pady=(px(6), 0))
        row = ttk.Frame(self.body)
        row.pack(anchor="w", pady=(px(8), 0))
        self.pick = tk.StringVar()
        self.picker = ttk.Combobox(row, textvariable=self.pick, width=38)
        self.picker.pack(side="left")
        ttk.Button(row, text="Add", command=self._add).pack(side="left", padx=px(8))
        ttk.Button(row, text="Remove selected", command=self._remove, style="Danger.TButton").pack(side="left")
        ttk.Label(self.body, text="Pick a player, or paste a player id (the game shows it under Settings > Data).", style="Small.TLabel").pack(anchor="w", pady=(px(4), 0))

        heading(self.body, "New accounts").pack(anchor="w", pady=(px(16), px(4)))
        self.imports = tk.BooleanVar()
        ttk.Checkbutton(self.body, text="A new account starts with the Cups and Spark Drops in the first save it uploads", variable=self.imports).pack(anchor="w")
        row = ttk.Frame(self.body)
        row.pack(anchor="w", pady=(px(6), 0))
        ttk.Label(row, text="New accounts one address may make per hour").pack(side="left")
        self.per_hour = tk.StringVar()
        number(row, self.per_hour, 1, 1000, width=5).pack(side="left", padx=px(10))
        self.action("Save", self._save, "Accent.TButton")
        self.action("Reload", self._load)
        self._load()

    def shown(self) -> None:
        self._names()

    def _names(self) -> dict[str, str]:
        try:
            names = {a["id"]: a["name"] for a in backend.store().accounts()}
        except Exception:
            names = {}
        self.picker.configure(values=["%s (%s)" % (name, ident) for ident, name in names.items()])
        return names

    def _load(self) -> None:
        game = cfgfile.read_ini(backend.cfg("game.cfg"))

        def get(section: str, key: str, default: str) -> str:
            return game.get(section, key, fallback=default).strip()

        yes = ("yes", "true", "on", "1")
        allowed = re.split(r"[,\s]+", get("players", "allowed", "EASY, NORMAL, HARD, ELITE").upper())
        self.default.set(get("players", "difficulty", "EASY").upper())
        for name, var in self.allowed.items():
            var.set(name in allowed)
        self.everyone.set(get("developers", "everyone", "no").lower() in yes)
        self.imports.set(get("accounts", "import_saves", "yes").lower() in yes)
        self.per_hour.set(get("accounts", "per_address_per_hour", "10"))
        names = self._names()
        self.devs.delete(*self.devs.get_children())
        for ident in [i for i in re.split(r"[,\s]+", get("developers", "ids", "")) if i]:
            self.devs.insert("", "end", iid=ident, values=(names.get(ident, "(no account with this id)"), ident))

    def _add(self) -> None:
        text = self.pick.get().strip()
        found = re.search(r"\(([0-9a-f]+)\)\s*$", text)
        ident = found.group(1) if found else text
        if not re.fullmatch(r"[0-9a-f]{8,}", ident):
            self.say("That isn't a player id.", bad=True)
        elif not self.devs.exists(ident):
            self.devs.insert("", "end", iid=ident, values=(self._names().get(ident, "(no account with this id)"), ident))
            self.pick.set("")

    def _remove(self) -> None:
        for ident in self.devs.selection():
            self.devs.delete(ident)

    def _save(self) -> None:
        allowed = [name for name, var in self.allowed.items() if var.get()]
        if self.default.get() not in allowed:
            self.say("The difficulty new players start on has to be one they may pick.", bad=True)
            return
        path = backend.cfg("game.cfg")
        cfgfile.set_values(path, "players", {"difficulty": self.default.get(), "allowed": ", ".join(allowed)})
        cfgfile.set_values(path, "developers", {"everyone": "yes" if self.everyone.get() else "no", "ids": ", ".join(self.devs.get_children())})
        cfgfile.set_values(path, "accounts", {"import_saves": "yes" if self.imports.get() else "no", "per_address_per_hour": as_int(self.per_hour, 10, 1, 1000)})
        self.say("Saved. It applies at once.")


class BotsPage(Page):
    title = "Bots"
    about = "How bots behave at each difficulty. Difficulty never changes a bot's health or damage, only how well it plays. Saved changes reach each game the next time it connects."

    LABELS = {
        "reactiontime": ("Reaction time", "seconds a target must be in view before it shoots"),
        "thinkinterval": ("Thinking gap", "seconds between decisions"),
        "aimerrordegrees": ("Aim error", "degrees its aim can be off by"),
        "leadfactor": ("Leads its shots", "0 aims where you are, 1 where you will be"),
        "dodgechance": ("Dodge chance", "0 to 1: how often it sidesteps a shot"),
        "rangediscipline": ("Keeps its distance", "0 runs straight at you, 1 holds its weapon's range"),
        "retreatbelow": ("Retreats below", "fraction of health; 0 never retreats"),
        "focusweakest": ("Picks on the hurt", "prefers targets that are already hurt"),
        "shotdiscipline": ("Holds its fire", "only shoots when the shot can land"),
        "superskill": ("Super timing", "0 to 1: how well it times its super"),
        "firehesitation": ("Hesitation", "extra random seconds between shots"),
        "wander": ("Wanders", "aimless drift"),
        "teamwork": ("Teamwork", "how closely it sticks with teammates"),
    }

    def __init__(self, parent, app):
        super().__init__(parent, app)
        self.grid_frame = ttk.Frame(self.body)
        self.grid_frame.pack(anchor="w")
        self.values: dict[tuple[str, str], tk.Variable] = {}
        self.action("Save", self._save, "Accent.TButton")
        self.action("Reload", self._load)
        self._load()

    def _load(self) -> None:
        for child in self.grid_frame.winfo_children():
            child.destroy()
        self.values.clear()
        bots = cfgfile.read_ini(backend.cfg("bots.cfg"))
        levels = [s for s in bots.sections() if s.upper() in backend.DIFFICULTIES]
        keys: list[str] = []
        for level in levels:
            keys += [key for key in bots[level] if key not in keys]
        for column, level in enumerate(levels, start=1):
            ttk.Label(self.grid_frame, text=level.title(), style="Head.TLabel").grid(row=0, column=column, sticky="w", padx=(0, px(22)), pady=(0, px(6)))
        for row, key in enumerate(keys, start=1):
            label, hint = self.LABELS.get(key, (key, ""))
            ttk.Label(self.grid_frame, text=label).grid(row=row, column=0, sticky="w", padx=(0, px(20)), pady=px(2))
            for column, level in enumerate(levels, start=1):
                raw = bots.get(level, key, fallback="").strip()
                if raw.lower() in ("true", "false"):
                    var: tk.Variable = tk.BooleanVar(value=raw.lower() == "true")
                    ttk.Checkbutton(self.grid_frame, variable=var).grid(row=row, column=column, sticky="w")
                else:
                    var = tk.StringVar(value=raw)
                    entry(self.grid_frame, var, width=8).grid(row=row, column=column, sticky="w", padx=(0, px(22)))
                self.values[(level, key)] = var
            ttk.Label(self.grid_frame, text=hint, style="Small.TLabel").grid(row=row, column=len(levels) + 1, sticky="w")

    def _save(self) -> None:
        by_level: dict[str, dict[str, str]] = {}
        for (level, key), var in self.values.items():
            if isinstance(var, tk.BooleanVar):
                value = "true" if var.get() else "false"
            else:
                value = var.get().strip()
                if not value:
                    continue  # left empty: the game's built-in number is used
                try:
                    float(value)
                except ValueError:
                    self.say("%s for %s isn't a number." % (self.LABELS.get(key, (key,))[0], level.title()), bad=True)
                    return
            by_level.setdefault(level, {})[key] = value
        for level, values in by_level.items():
            cfgfile.set_values(backend.cfg("bots.cfg"), level, values)
        self.say("Saved. Each game picks it up the next time it connects.")
