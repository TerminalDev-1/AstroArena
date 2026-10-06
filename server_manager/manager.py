"""AstroArena Server Manager: run it with  python manager.py  (or double-click "Server Manager.bat").

A window for whoever runs the server: start and stop it, and change everything the .cfg files and the
account database hold through forms, without editing a file by hand. Python's standard library only.

Getting around: the sidebar, or Ctrl+1 to Ctrl+8 for its pages in order. Ctrl+S presses the page's main button.
"""

from __future__ import annotations

import ctypes
import os
import queue
import sys
import threading
import time
import tkinter as tk
import traceback
from tkinter import messagebox, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backend  # noqa: E402
import theme  # noqa: E402
from page_accounts import AccountsPage  # noqa: E402
from page_lists import MessagesPage, NewsPage, ShopPage  # noqa: E402
from page_rules import BotsPage, GamePage, TrophiesPage  # noqa: E402
from page_server import ServerPage  # noqa: E402
from theme import px  # noqa: E402

# The sidebar, top to bottom: (group, [(page, its class, its icon in the Segoe icon fonts)]).
GROUPS = [
    ("", [("Server", ServerPage, "")]),
    ("Players", [("Accounts", AccountsPage, "")]),
    ("Rules", [("Trophies", TrophiesPage, ""), ("Game rules", GamePage, ""), ("Bots", BotsPage, "")]),
    ("In the game", [("Shop", ShopPage, ""), ("News", NewsPage, ""), ("Messages", MessagesPage, "")]),
]
PAGES = [(name, page) for _, items in GROUPS for name, page, _ in items]
SIDE_WIDTH = 216


class NavItem(tk.Canvas):
    """One line of the sidebar: an icon and a name on a pill that shows when it is pointed at or chosen."""

    def __init__(self, parent: tk.Misc, text: str, icon: str, shortcut: str, command):
        w, h = px(SIDE_WIDTH - 20), px(34)
        super().__init__(parent, width=w, height=h, bg=theme.SIDE, highlightthickness=0, cursor="hand2")
        self._pills = {"hover": theme.rounded(self, w, h, px(8), theme.HOVER, back=theme.SIDE),
                       "on": theme.rounded(self, w, h, px(8), theme.BG, theme.LINE, back=theme.SIDE)}
        self._pill = self.create_image(0, 0, anchor="nw", image="")
        left = px(14)
        if theme.ICONS:
            self._icon = self.create_text(px(24), h // 2, text=icon, font=theme.ICONS, fill=theme.DIM)
            left = px(44)
        else:
            self._icon = None
        self._text = self.create_text(left, h // 2 - 1, anchor="w", text=text, font=theme.FONT, fill=theme.TEXT)
        self._hint = self.create_text(w - px(12), h // 2, anchor="e", text=shortcut, font=theme.SMALL, fill=theme.FAINT, state="hidden")
        self._on = False
        self.bind("<Enter>", lambda e: self._look(True))
        self.bind("<Leave>", lambda e: self._look(False))
        self.bind("<Button-1>", lambda e: command())

    def _look(self, over: bool) -> None:
        self.itemconfigure(self._pill, image=self._pills["on"] if self._on else self._pills["hover"] if over else "")
        self.itemconfigure(self._hint, state="normal" if over else "hidden")

    def choose(self, on: bool) -> None:
        self._on = on
        self.itemconfigure(self._text, font=theme.BOLD if on else theme.FONT)
        if self._icon:
            self.itemconfigure(self._icon, fill=theme.ACCENT if on else theme.DIM)
        self._look(False)


class App(tk.Tk):
    def __init__(self, start: str = "Server"):
        super().__init__()
        self.title("AstroArena Server Manager")
        theme.apply(self)
        # As big as it likes to be, but never bigger than the screen has room for (taskbar and title bar included).
        wide = min(px(1240), self.winfo_screenwidth() - px(30))
        high = min(px(760), self.winfo_screenheight() - px(96))
        self.geometry("%dx%d+%d+%d" % (wide, high, (self.winfo_screenwidth() - wide) // 2, max(0, (self.winfo_screenheight() - high) // 2 - px(34))))
        self.minsize(min(wide, px(1060)), min(high, px(640)))
        self.report_callback_exception = self._failed

        # Whether the server is up is asked on another thread and handed over through a queue.
        self.health: dict | None = backend.health()
        self.started_here = self.health is not None and backend.started_here()
        self._news: queue.Queue = queue.Queue()

        side = tk.Frame(self, bg=theme.SIDE, width=px(SIDE_WIDTH))
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        tk.Label(side, text="AstroArena", bg=theme.SIDE, fg=theme.TEXT, font=("Segoe UI Semibold", 13)).pack(anchor="w", padx=px(22), pady=(px(22), 0))
        tk.Label(side, text="Server Manager", bg=theme.SIDE, fg=theme.DIM, font=theme.SMALL).pack(anchor="w", padx=px(22), pady=(0, px(14)))
        self.nav: dict[str, NavItem] = {}
        for group, items in GROUPS:
            if group:
                tk.Label(side, text=group.upper(), bg=theme.SIDE, fg=theme.FAINT, font=("Segoe UI Semibold", 8)).pack(anchor="w", padx=px(24), pady=(px(14), px(4)))
            for name, _, icon in items:
                number = len(self.nav) + 1
                self.nav[name] = NavItem(side, name, icon, "Ctrl+%d" % number, lambda n=name: self.show(n))
                self.nav[name].pack(padx=px(10), pady=px(1))
                self.bind_all("<Control-Key-%d>" % number, lambda e, n=name: self.show(n))
        self.bind_all("<Control-s>", self._press_primary)

        # Always in view: is the server up? Clicking it goes to the Server page.
        self.status = tk.Canvas(side, width=px(SIDE_WIDTH - 20), height=px(40), bg=theme.SIDE, highlightthickness=0, cursor="hand2")
        self.status.pack(side="bottom", padx=px(10), pady=px(12))
        self.status.create_image(0, 0, anchor="nw", image=theme.rounded(self.status, px(SIDE_WIDTH - 20), px(40), px(8), theme.BG, theme.LINE, back=theme.SIDE))
        self._dot = self.status.create_oval(px(14), px(15), px(24), px(25), outline="")
        self._state = self.status.create_text(px(34), px(19), anchor="w", font=theme.BOLD, fill=theme.TEXT)
        self.status.bind("<Button-1>", lambda e: self.show("Server"))
        tk.Frame(self, bg=theme.LINE, width=1).pack(side="left", fill="y")

        self.holder = ttk.Frame(self)
        self.holder.pack(side="left", fill="both", expand=True)
        self.pages: dict[str, theme.Page] = {}
        self.current = ""
        self._status()
        self.show(start if start in dict(PAGES) else "Server")
        threading.Thread(target=self._watch, daemon=True).start()
        self.after(300, self._drain)

    def destroy(self) -> None:
        for job in self.tk.splitlist(self.tk.call("after", "info")):  # nothing may fire into a window that is gone
            self.tk.call("after", "cancel", job)
        super().destroy()

    # ------------------------------------------------------------------ pages

    def show(self, name: str) -> None:
        """Brings a page to the front, building it the first time it is asked for."""
        if name not in self.pages:
            try:
                self.pages[name] = dict(PAGES)[name](self.holder, self)
            except Exception:
                self._failed(*sys.exc_info())
                return
        elif name != self.current:
            self.pages[name].shown()
        if self.current and self.current != name:
            self.pages[self.current].pack_forget()
            self.nav[self.current].choose(False)
        self.pages[name].pack(fill="both", expand=True)
        self.nav[name].choose(True)
        self.current = name

    def _press_primary(self, _event=None) -> str:
        button = self.pages[self.current].primary if self.current else None
        if button is not None and button.instate(["!disabled"]):
            self.focus_set()  # so a box still being typed in hands over its value first
            button.invoke()
        return "break"

    # ------------------------------------------------------------------ is the server up?

    def wait_until(self, running: bool) -> None:
        """Waits (a few seconds at most) for the server to be up, or to be gone. Call it off the window's thread."""
        for _ in range(20):
            health = backend.health()
            if (health is not None) == running:
                break
            time.sleep(0.4)
        self._news.put((health, health is not None and backend.started_here()))

    def _watch(self) -> None:
        was_up = self.health is not None
        while True:
            health = backend.health()
            here = self.started_here
            if (health is not None) != was_up:  # who started it only needs asking when it comes or goes
                here = health is not None and backend.started_here()
                was_up = health is not None
            self._news.put((health, here))
            time.sleep(2.0)

    def _drain(self) -> None:
        changed = False
        while not self._news.empty():
            self.health, self.started_here = self._news.get()
            changed = True
        if changed:
            self._status()
            page = self.pages.get("Server")
            if page is not None:
                page.update_state(self.health, self.started_here)
        self.after(300, self._drain)

    def _status(self) -> None:
        up = self.health is not None
        self.status.itemconfigure(self._dot, fill=theme.GOOD if up else theme.BAD)
        self.status.itemconfigure(self._state, text="Server running" if up else "Server stopped")

    def _failed(self, kind, value, trace) -> None:
        """Something went wrong inside a button or a page: say what, rather than doing nothing."""
        messagebox.showerror("Something went wrong", "".join(traceback.format_exception_only(kind, value)).strip() +
                             "\n\n" + "".join(traceback.format_tb(trace)[-3:]), parent=self)


def main() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # sharp text on scaled displays
    except (AttributeError, OSError):
        pass
    try:
        App(sys.argv[1] if len(sys.argv) > 1 else "Server").mainloop()
    except Exception:
        # Started without a console (pythonw), a crash would otherwise be silent.
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("AstroArena Server Manager couldn't start", traceback.format_exc())
        raise


if __name__ == "__main__":
    main()
