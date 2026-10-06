"""AstroArena Server Manager: run it with  python manager.py  (or double-click "Server Manager.bat").

A window for whoever runs the server: start and stop it, and change everything the .cfg files and the
account database hold through forms, without editing a file by hand. Python's standard library only.
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

PAGES = [("Server", ServerPage), ("Accounts", AccountsPage), ("Trophies", TrophiesPage), ("Game rules", GamePage),
         ("Shop", ShopPage), ("News", NewsPage), ("Messages", MessagesPage), ("Bots", BotsPage)]


class App(tk.Tk):
    def __init__(self, start: str = "Server"):
        super().__init__()
        self.title("AstroArena Server Manager")
        theme.apply(self)
        # As big as it likes to be, but never bigger than the screen has room for (taskbar and title bar included).
        wide = min(px(1220), self.winfo_screenwidth() - px(30))
        high = min(px(740), self.winfo_screenheight() - px(96))
        self.geometry("%dx%d+%d+%d" % (wide, high, (self.winfo_screenwidth() - wide) // 2, max(0, (self.winfo_screenheight() - high) // 2 - px(34))))
        self.minsize(min(wide, px(1040)), min(high, px(620)))
        self._dark_title()
        self.report_callback_exception = self._failed

        # Whether the server is up is asked on another thread and handed over through a queue.
        self.health: dict | None = backend.health()
        self.started_here = self.health is not None and backend.started_here()
        self._news: queue.Queue = queue.Queue()

        side = ttk.Frame(self, style="Side.TFrame", width=px(196))
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        ttk.Label(side, text="AstroArena", style="Brand.TLabel").pack(anchor="w", padx=px(18), pady=(px(20), 0))
        ttk.Label(side, text="Server Manager", style="SideDim.TLabel").pack(anchor="w", padx=px(18), pady=(0, px(18)))
        self.nav: dict[str, ttk.Button] = {}
        for name, _ in PAGES:
            self.nav[name] = ttk.Button(side, text=name, style="Nav.TButton", command=lambda n=name: self.show(n), takefocus=False)
            self.nav[name].pack(fill="x", padx=px(8), pady=px(1))
        status = ttk.Frame(side, style="Side.TFrame")
        status.pack(side="bottom", fill="x", padx=px(18), pady=px(16))
        self.dot = ttk.Label(status, text="●", style="Side.TLabel")
        self.dot.pack(side="left")
        self.status = ttk.Label(status, text="", style="SideDim.TLabel")
        self.status.pack(side="left", padx=(px(6), 0))
        ttk.Frame(self, style="Line.TFrame", width=1).pack(side="left", fill="y")

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

    def _dark_title(self) -> None:
        """Windows draws the title bar; ask it for the dark one, to go with the rest."""
        try:
            self.update_idletasks()
            window = ctypes.windll.user32.GetParent(self.winfo_id())
            ctypes.windll.dwmapi.DwmSetWindowAttribute(window, 20, ctypes.byref(ctypes.c_int(1)), 4)
        except (AttributeError, OSError):
            pass

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
            self.nav[self.current].configure(style="Nav.TButton")
        self.pages[name].pack(fill="both", expand=True)
        self.nav[name].configure(style="NavOn.TButton")
        self.current = name

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
        self.dot.configure(foreground=theme.GOOD if up else theme.BAD)
        self.status.configure(text="Server running" if up else "Server stopped")

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
