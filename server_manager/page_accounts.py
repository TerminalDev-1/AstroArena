"""Every account: what it has, its fighters, and whether it is allowed in."""

from __future__ import annotations

import time
import tkinter as tk
from tkinter import ttk

import backend
from theme import BAD, Page, as_int, choice, entry, heading, number, px, table

NUMBERS = [("cups", "Cups"), ("best_cups", "Best Cups"), ("drops", "Spark Drops"), ("bolts", "Power Ups"),
           ("prisms", "Crystals"), ("credits", "Credits"), ("glory", "Glory")]
FOREVER = "Until I let them back in"
TYPED = "A date or length I type →"
LENGTHS = ["15 minutes", "1 hour", "12 hours", "1 day", "3 days", "7 days", "30 days"]


class AccountsPage(Page):
    title = "Accounts"
    about = "Pick a player, change what you like, then apply. It is forced onto the account at once; the player sees it the next time their game talks to the server."

    def __init__(self, parent, app):
        super().__init__(parent, app)
        self.accounts: list[dict] = []
        self.current: dict | None = None

        left = ttk.Frame(self.body, width=px(318))
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        find = ttk.Frame(left)
        find.pack(fill="x", pady=(0, px(8)))
        ttk.Label(find, text="Find", style="Dim.TLabel").pack(side="left")
        self.query = tk.StringVar()
        self.query.trace_add("write", lambda *_: self._fill())
        entry(find, self.query).pack(side="left", fill="x", expand=True, padx=(px(8), 0))
        frame, self.tree = table(left, [("place", "#", 30, "e"), ("name", "Player", 128, "w"), ("cups", "Cups", 62, "e"), ("state", "", 70, "w")], height=14)
        frame.pack(fill="both", expand=True)
        self.tree.bind("<<TreeviewSelect>>", self._picked)
        self.tree.tag_configure("disabled", foreground=BAD)

        right = ttk.Frame(self.body)
        right.pack(side="left", fill="both", expand=True, padx=(px(22), 0))
        who = ttk.Frame(right)
        who.pack(fill="x")
        self.name = ttk.Label(who, text="", font=("Segoe UI Semibold", 14))
        self.name.pack(side="left")
        self.ident = ttk.Label(who, text="", style="Dim.TLabel", font=("Consolas", 10))
        self.ident.pack(side="left", padx=px(12), pady=(px(3), 0))
        ttk.Button(who, text="Copy id", command=self._copy).pack(side="right")

        heading(right, "Has").pack(anchor="w", pady=(px(10), px(4)))
        grid = ttk.Frame(right)
        grid.pack(fill="x")
        self.numbers: dict[str, tk.StringVar] = {}
        for i, (key, label) in enumerate(NUMBERS):
            cell = ttk.Frame(grid)
            cell.grid(row=i // 4, column=i % 4, sticky="w", padx=(0, px(16)), pady=(0, px(6)))
            ttk.Label(cell, text=label, style="Small.TLabel").pack(anchor="w")
            self.numbers[key] = tk.StringVar()
            entry(cell, self.numbers[key], width=13).pack(anchor="w")

        heading(right, "Fighters", "locked, level and the fighter's own Cups (its rank follows them)").pack(anchor="w", pady=(px(6), px(4)))
        grid = ttk.Frame(right)
        grid.pack(fill="x")
        self.fighters: dict[str, tuple[tk.BooleanVar, tk.StringVar, tk.StringVar]] = {}
        for row, fighter in enumerate(backend.FIGHTERS):
            unlocked, level, cups = tk.BooleanVar(), tk.StringVar(), tk.StringVar()
            self.fighters[fighter] = (unlocked, level, cups)
            ttk.Label(grid, text=fighter.title(), font=("Segoe UI Semibold", 10), width=8).grid(row=row, column=0, sticky="w", pady=px(1))
            box = ttk.Checkbutton(grid, text="Unlocked", variable=unlocked)
            box.grid(row=row, column=1, padx=(0, px(18)))
            if fighter == backend.rules.STARTING_FIGHTER:
                box.state(["disabled"])  # everyone starts with it
            ttk.Label(grid, text="Level", style="Dim.TLabel").grid(row=row, column=2, padx=(0, px(6)))
            number(grid, level, 1, backend.economy.LEVEL_LIMIT, width=6).grid(row=row, column=3, padx=(0, px(18)))
            ttk.Label(grid, text="Cups", style="Dim.TLabel").grid(row=row, column=4, padx=(0, px(6)))
            entry(grid, cups, width=9).grid(row=row, column=5)

        heading(right, "Access", "a disabled account is kept, with all it has; in the game it can only go to Jail").pack(anchor="w", pady=(px(10), px(4)))
        access = ttk.Frame(right)
        access.pack(fill="x")
        self.disabled = tk.BooleanVar()
        self.reason, self.length, self.typed = tk.StringVar(), tk.StringVar(), tk.StringVar()
        ttk.Checkbutton(access, text="Disabled", variable=self.disabled, command=self._access).grid(row=0, column=0, sticky="w", padx=(0, px(18)))
        ttk.Label(access, text="Reason shown to the player", style="Dim.TLabel").grid(row=0, column=1, sticky="w", padx=(0, px(8)))
        self.reason_box = entry(access, self.reason, width=36)
        self.reason_box.grid(row=0, column=2, columnspan=2, sticky="w")
        ttk.Label(access, text="For", style="Dim.TLabel").grid(row=1, column=1, sticky="e", padx=(0, px(8)), pady=(px(8), 0))
        self.length_box = choice(access, self.length, [], width=26)
        self.length_box.grid(row=1, column=2, sticky="w", pady=(px(8), 0))
        self.length_box.bind("<<ComboboxSelected>>", lambda e: self._access(), add="+")
        self.typed_box = entry(access, self.typed, width=18)
        self.typed_box.grid(row=1, column=3, sticky="w", padx=(px(8), 0), pady=(px(8), 0))
        ttk.Label(right, text="Typed: 45 minutes, 2 weeks, 1 day 6 hours, 2026-10-20 or 2026-10-20 18:00 (this PC's time).", style="Small.TLabel").pack(anchor="w", pady=(px(6), 0))

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
        if account is not None and (self.current is None or account["id"] != self.current["id"] or account != self.current):
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
        self._kept = "Leave it: ends " + time.strftime("%Y-%m-%d %H:%M", time.localtime(until)) if until > 0 else None
        self.length_box.configure(values=([self._kept] if self._kept else []) + [FOREVER] + LENGTHS + [TYPED])
        self.length.set(self._kept or FOREVER)
        self._access()

    def _access(self) -> None:
        on = self.disabled.get()
        self.reason_box.state(["!disabled" if on else "disabled"])
        self.length_box.state(["!disabled" if on else "disabled"])
        self.typed_box.state(["!disabled" if on and self.length.get() == TYPED else "disabled"])

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
            if self.reason.get().strip() != was["disabled_reason"]:
                change["disabled_reason"] = self.reason.get().strip()
            length = self.length.get()
            if length != self._kept:
                until = 0.0 if length == FOREVER else backend.when(self.typed.get() if length == TYPED else length)
                if until is None:
                    self.say("I can't read that length or date. Try 3 days, or 2026-10-20 18:00.", bad=True)
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
