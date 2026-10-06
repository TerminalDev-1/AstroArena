"""A list of things beside a form for the one that is selected: shop offers, news items, notices."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk

from theme import px, table


class ListEditor(ttk.Frame):
    """Holds a list of dicts. The page supplies the columns, what a row shows (`summary`), a new item (`blank`),
    and three functions for its form: `build(frame)` makes it, `load(item)` fills it, `keep(item)` reads it back.

    Edits stay in the form until the selection moves or `items()` is asked for, so nothing is half-saved.
    """

    def __init__(self, parent: tk.Misc, columns, summary, blank, build, load, keep, height: int = 12,
                 list_width: int = 400, new_on_top: bool = False, form_below: bool = False, noun: str = "item"):
        super().__init__(parent)
        self._summary, self._blank, self._load, self._keep = summary, blank, load, keep
        self._new_on_top = new_on_top
        self._items: list[dict] = []
        self._at = -1

        left = ttk.Frame(self)
        frame, self.tree = table(left, columns, height=height)
        frame.pack(fill="both", expand=True)
        buttons = ttk.Frame(left)
        buttons.pack(fill="x", pady=(px(8), 0))
        ttk.Button(buttons, text="New " + noun, command=self._new).pack(side="left")
        ttk.Button(buttons, text="Delete", command=self._delete, style="Danger.TButton").pack(side="left", padx=px(8))
        ttk.Button(buttons, text="▼", width=3, command=lambda: self._move(1)).pack(side="right")
        ttk.Button(buttons, text="▲", width=3, command=lambda: self._move(-1)).pack(side="right", padx=px(6))
        self.form = ttk.Frame(self)
        if form_below:
            left.pack(fill="both", expand=True)
            self.form.pack(fill="x", pady=(px(10), 0))
        else:
            left.pack(side="left", fill="y")
            left.configure(width=px(list_width))
            left.pack_propagate(False)
            self.form.pack(side="left", fill="both", expand=True, padx=(px(22), 0))
        build(self.form)
        self.tree.bind("<<TreeviewSelect>>", self._picked)

    # ------------------------------------------------------------------ the list

    def set_items(self, items: list[dict]) -> None:
        self._items, self._at = [dict(item) for item in items], -1
        self._fill(0)

    def items(self) -> list[dict]:
        self.commit()
        return [dict(item) for item in self._items]

    def commit(self) -> None:
        """Reads the form back into the item it was showing."""
        if 0 <= self._at < len(self._items):
            self._keep(self._items[self._at])
            self.tree.item(str(self._at), values=self._summary(self._items[self._at]))

    def _fill(self, select: int) -> None:
        self.tree.delete(*self.tree.get_children())
        for i, item in enumerate(self._items):
            self.tree.insert("", "end", iid=str(i), values=self._summary(item))
        self._at = -1
        if self._items:
            select = min(max(select, 0), len(self._items) - 1)
            self.tree.selection_set(str(select))
            self.tree.see(str(select))
        else:
            self._load(self._blank())

    def _picked(self, _event=None) -> None:
        chosen = self.tree.selection()
        if not chosen or int(chosen[0]) == self._at:
            return
        self.commit()
        self._at = int(chosen[0])
        self._load(self._items[self._at])

    def _new(self) -> None:
        self.commit()
        at = 0 if self._new_on_top else len(self._items)
        self._items.insert(at, self._blank())
        self._fill(at)

    def _delete(self) -> None:
        if 0 <= self._at < len(self._items):
            at = self._at
            del self._items[at]
            self._fill(at)

    def _move(self, by: int) -> None:
        self.commit()
        a, b = self._at, self._at + by
        if 0 <= a < len(self._items) and 0 <= b < len(self._items):
            self._items[a], self._items[b] = self._items[b], self._items[a]
            self._fill(b)
