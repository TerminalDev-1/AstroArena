"""Every account: what it has, its fighters, and whether it is allowed in."""

from __future__ import annotations

import datetime
import time
import tkinter as tk
from tkinter import ttk

import backend
from nativebox import DictationEntry
from theme import BAD, Page, as_int, choice, entry, heading, number, px, table

NUMBERS = [("cups", "Cups"), ("best_cups", "Best Cups"), ("drops", "Spark Drops"), ("bolts", "Power Ups"),
           ("prisms", "Crystals"), ("credits", "Credits"), ("glory", "Glory")]
FOREVER = "Until I let them back in"
TYPED = "Another length →"
PICKED = "Until a date and time ↓"
LENGTHS = ["15 minutes", "1 hour", "12 hours", "1 day", "3 days", "7 days", "30 days"]
UK = "%d/%m/%Y %H:%M"  # dates are shown and picked the UK way: day, month, year, 24-hour clock


class AccountsPage(Page):
    title = "Accounts"
    about = "Pick a player, change what you like, then apply. The player sees it the next time their game talks to the server."

    def __init__(self, parent, app):
        super().__init__(parent, app)
        self.accounts: list[dict] = []
        self.current: dict | None = None

        left = ttk.Frame(self.body, width=px(304))
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        find = ttk.Frame(left)
        find.pack(fill="x", pady=(0, px(8)))
        ttk.Label(find, text="Find", style="Dim.TLabel").pack(side="left")
        self.query = tk.StringVar()
        self.query.trace_add("write", lambda *_: self._fill())
        DictationEntry(find, self.query).pack(side="left", fill="x", expand=True, padx=(px(8), 0))
        frame, self.tree = table(left, [("place", "#", 30, "e"), ("name", "Player", 120, "w"), ("cups", "Cups", 60, "e"), ("state", "", 70, "w")], height=14)
        frame.pack(fill="both", expand=True)
        self.tree.bind("<<TreeviewSelect>>", self._picked)
        self.tree.tag_configure("disabled", foreground=BAD)

        right = ttk.Frame(self.body)
        right.pack(side="left", fill="both", expand=True, padx=(px(20), 0))
        who = ttk.Frame(right)
        who.pack(fill="x")
        self.name = ttk.Label(who, text="", font=("Segoe UI Semibold", 14))
        self.name.pack(side="left")
        self.ident = ttk.Label(who, text="", style="Dim.TLabel", font=("Consolas", 10))
        self.ident.pack(side="left", padx=px(12), pady=(px(3), 0))
        ttk.Button(who, text="Copy id", command=self._copy).pack(side="right")

        heading(right, "Has").pack(anchor="w", pady=(px(12), px(4)))
        grid = ttk.Frame(right)
        grid.pack(fill="x")
        self.numbers: dict[str, tk.StringVar] = {}
        for i, (key, label) in enumerate(NUMBERS):
            cell = ttk.Frame(grid)
            cell.grid(row=i // 4, column=i % 4, sticky="w", padx=(0, px(12)), pady=(0, px(6)))
            ttk.Label(cell, text=label, style="Small.TLabel").pack(anchor="w")
            self.numbers[key] = tk.StringVar()
            entry(cell, self.numbers[key], width=12).pack(anchor="w")

        heading(right, "Fighters", "locked, level and the fighter's own Cups (its rank follows them)").pack(anchor="w", pady=(px(8), px(4)))
        grid = ttk.Frame(right)
        grid.pack(fill="x")
        self.fighters: dict[str, tuple[tk.BooleanVar, tk.StringVar, tk.StringVar]] = {}
        for row, fighter in enumerate(backend.FIGHTERS):
            unlocked, level, cups = tk.BooleanVar(), tk.StringVar(), tk.StringVar()
            self.fighters[fighter] = (unlocked, level, cups)
            ttk.Label(grid, text=fighter.title(), font=("Segoe UI Semibold", 10), width=8).grid(row=row, column=0, sticky="w", pady=px(3))
            box = ttk.Checkbutton(grid, text="Unlocked", variable=unlocked)
            box.grid(row=row, column=1, padx=(0, px(18)))
            if fighter == backend.rules.STARTING_FIGHTER:
                box.state(["disabled"])  # everyone starts with it
            ttk.Label(grid, text="Level", style="Dim.TLabel").grid(row=row, column=2, padx=(0, px(6)))
            number(grid, level, 1, backend.economy.LEVEL_LIMIT, width=6).grid(row=row, column=3, padx=(0, px(18)), pady=px(2))
            ttk.Label(grid, text="Cups", style="Dim.TLabel").grid(row=row, column=4, padx=(0, px(6)))
            entry(grid, cups, width=9).grid(row=row, column=5)

        heading(right, "Access", "a disabled account is kept, with all it has; the game shows the player a notice and nothing else").pack(anchor="w", pady=(px(12), px(4)))
        self.disabled = tk.BooleanVar()
        self.length, self.typed = tk.StringVar(), tk.StringVar()
        first = ttk.Frame(right)
        first.pack(fill="x")
        ttk.Checkbutton(first, text="Disabled", variable=self.disabled, command=self._access).pack(side="left", padx=(0, px(22)))
        ttk.Label(first, text="For", style="Dim.TLabel").pack(side="left", padx=(0, px(8)))
        self.length_box = choice(first, self.length, [], width=27)
        self.length_box.pack(side="left")
        self.length_box.bind("<<ComboboxSelected>>", lambda e: self._access(), add="+")
        self.typed_box = entry(first, self.typed, width=16)
        self.typed_box.pack(side="left", padx=(px(8), 0))

        # The reason gets the full width, and is a real Windows text box so that voice typing (Win+H) works in it.
        second = ttk.Frame(right)
        second.pack(fill="x", pady=(px(8), 0))
        ttk.Label(second, text="Reason shown to the player", style="Dim.TLabel").pack(side="left", padx=(0, px(8)))
        self.reason = DictationEntry(second)
        self.reason.pack(side="left", fill="x", expand=True)

        # A date and time, picked the UK way round. Only shown when that is what "For" says.
        self.when_row = ttk.Frame(right)
        self.day, self.month, self.year, self.hour, self.minute = (tk.StringVar() for _ in range(5))
        this_year = datetime.date.today().year
        ttk.Label(self.when_row, text="Ends on", style="Dim.TLabel").pack(side="left", padx=(0, px(8)))
        for var, values, width, after in (
                (self.day, ["%02d" % d for d in range(1, 32)], 3, "/"), (self.month, ["%02d" % m for m in range(1, 13)], 3, "/"),
                (self.year, [str(this_year + y) for y in range(0, 6)], 5, "at"),
                (self.hour, ["%02d" % h for h in range(24)], 3, ":"), (self.minute, ["%02d" % m for m in range(0, 60, 5)], 3, "")):
            choice(self.when_row, var, values, width=width).pack(side="left")
            if after:
                ttk.Label(self.when_row, text=after, style="Dim.TLabel").pack(side="left", padx=px(6))

        self.action("Apply changes", self._apply, "Accent.TButton")
        self.action("Undo edits", lambda: self._show(self.current))
        self.action("Refresh", self.reload)
        self.reload()

    def shown(self) -> None:
        self.reload()

    # ------------------------------------------------------------------ the list

    def reload(self, keep: str | None = None) -> None:
        try:
            self.accounts = backend.store().accounts()
        except Exception as problem:  # a locked or missing database is worth saying, not crashing over
            self.say("Couldn't read the accounts: %s" % problem, bad=True)
            return
        self._fill(keep or (self.current or {}).get("id"))

    def _fill(self, select: str | None = None) -> None:
        select = select or (self.tree.selection() or [None])[0]
        words = self.query.get().strip().lower()
        self.tree.delete(*self.tree.get_children())
        place = 0
        for account in self.accounts:
            place += 0 if account["disabled"] else 1
            if words and words not in account["name"].lower() and words not in account["id"]:
                continue
            self.tree.insert("", "end", iid=account["id"], values=(
                "" if account["disabled"] else place, account["name"], "{:,}".format(account["cups"]), "Disabled" if account["disabled"] else ""),
                tags=("disabled",) if account["disabled"] else ())
        rows = self.tree.get_children()
        if rows:
            self.tree.selection_set(select if select in rows else rows[0])
            self._picked()
        else:
            self._show(None)

    def _picked(self, _event=None) -> None:
        chosen = self.tree.selection()
        account = next((a for a in self.accounts if chosen and a["id"] == chosen[0]), None)
        if account is not None and account != self.current:
            self._show(account)

    def _copy(self) -> None:
        if self.current:
            self.clipboard_clear()
            self.clipboard_append(self.current["id"])
            self.say("Player id copied.")

    # ------------------------------------------------------------------ the form

    def _show(self, account: dict | None) -> None:
        self.current = account
        self.name.configure(text=account["name"] if account else "No account selected")
        self.ident.configure(text=account["id"] if account else "")
        for key, var in self.numbers.items():
            var.set(account[key] if account else "")
        for fighter, (unlocked, level, cups) in self.fighters.items():
            has = (account or {}).get("fighters", {}).get(fighter, {})
            unlocked.set(bool(has.get("unlocked")))
            level.set(has.get("level", ""))
            cups.set(has.get("cups", ""))
        self.disabled.set(bool(account and account["disabled"]))
        self.reason.set(account["disabled_reason"] if account else "")
        self.typed.set("")
        until = account["disabled_until"] if account else 0
        self._kept = "Leave it: ends " + time.strftime(UK, time.localtime(until)) if until > 0 else None
        self.length_box.configure(values=([self._kept] if self._kept else []) + [FOREVER] + LENGTHS + [TYPED, PICKED])
        self.length.set(self._kept or FOREVER)
        # The picker starts on the end it already has, or this time tomorrow, to the next five minutes.
        start = datetime.datetime.fromtimestamp(until) if until > 0 else datetime.datetime.now() + datetime.timedelta(days=1)
        start += datetime.timedelta(minutes=(-start.minute) % 5)
        for var, value in ((self.day, start.day), (self.month, start.month), (self.hour, start.hour), (self.minute, start.minute)):
            var.set("%02d" % value)
        self.year.set(str(start.year))
        self._access()

    def _access(self) -> None:
        on = self.disabled.get()
        self.reason.enable(on)
        self.length_box.state(["!disabled" if on else "disabled"])
        typing = on and self.length.get() == TYPED
        self.typed_box.state(["!disabled" if typing else "disabled"])
        if typing:
            self.say("Type a length: 45 minutes, 2 weeks, 1 day 6 hours.")
        if on and self.length.get() == PICKED:
            self.when_row.pack(fill="x", pady=(px(8), 0))
            self.say("Day / month / year, on the 24-hour clock, in this PC's time.")
        else:
            self.when_row.pack_forget()

    def _until(self) -> float | str:
        """When the account is let back in, as "For" has it (0 = never by itself); or, as text, why it can't be read."""
        length = self.length.get()
        if length == FOREVER:
            return 0.0
        if length == PICKED:
            try:
                end = datetime.datetime(int(self.year.get()), int(self.month.get()), int(self.day.get()), int(self.hour.get()), int(self.minute.get()))
            except ValueError:
                return "There is no %s/%s/%s." % (self.day.get(), self.month.get(), self.year.get())
            return end.timestamp() if end > datetime.datetime.now() else "That date and time has already passed."
        until = backend.when(self.typed.get() if length == TYPED else length)
        return "I can't read that length. Try 3 days, or 1 day 6 hours." if until is None or (length == TYPED and until == 0) else until

    def _apply(self) -> None:
        was = self.current
        if was is None:
            return
        change: dict = {}
        for key, var in self.numbers.items():
            value = as_int(var, was[key])
            if value != was[key]:
                change[key] = value
        for fighter, (unlocked, level, cups) in self.fighters.items():
            has = was["fighters"].get(fighter)
            if has is None:
                continue
            now = {"unlocked": unlocked.get(), "level": as_int(level, has["level"], 1, backend.economy.LEVEL_LIMIT), "cups": as_int(cups, has["cups"])}
            parts = {k: v for k, v in now.items() if v != has[k]}
            if parts:
                change.setdefault("fighters", {})[fighter] = parts
        disabled = self.disabled.get()
        if disabled != was["disabled"]:
            change["disabled"] = disabled
        if disabled:
            reason = " ".join(self.reason.get().split())
            if reason != was["disabled_reason"]:
                change["disabled_reason"] = reason
            if self.length.get() != self._kept:
                until = self._until()
                if isinstance(until, str):
                    self.say(until, bad=True)
                    return
                if until != was["disabled_until"]:
                    change["disabled_until"] = until
        if not change:
            self.say("Nothing was changed.")
            return
        try:
            backend.store().force(was["id"], change)
        except Exception as problem:
            self.say("That didn't go through: %s" % problem, bad=True)
            return
        self.current = None
        self.reload(keep=was["id"])
        self.say("Applied to %s." % was["name"])
