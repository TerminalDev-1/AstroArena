"""Start, stop and watch the server."""

from __future__ import annotations

import os
import threading
from tkinter import messagebox, ttk

import backend
from theme import BAD, DIM, GOOD, Page, heading, px, text_box


class ServerPage(Page):
    title = "Server"
    about = "The game server itself. Started from here it runs on its own in the background and keeps running if you close this window."

    def __init__(self, parent, app):
        super().__init__(parent, app)
        top = ttk.Frame(self.body)
        top.pack(fill="x")
        self.dot = ttk.Label(top, text="●", font=("Segoe UI", 20), foreground=DIM)
        self.dot.pack(side="left")
        words = ttk.Frame(top)
        words.pack(side="left", padx=(px(10), 0))
        self.state = ttk.Label(words, text="Checking…", font=("Segoe UI Semibold", 13))
        self.state.pack(anchor="w")
        self.detail = ttk.Label(words, text="", style="Dim.TLabel")
        self.detail.pack(anchor="w")

        address = ttk.Frame(self.body)
        address.pack(fill="x", pady=(px(16), 0))
        ttk.Label(address, text="Address for the game", style="Dim.TLabel").pack(side="left")
        self.url = "http://%s:%d" % (backend.lan_address(), backend.PORT)
        ttk.Label(address, text=self.url, font=("Consolas", 11)).pack(side="left", padx=px(10))
        ttk.Button(address, text="Copy", command=self._copy).pack(side="left")

        heading(self.body, "Log", "what the server prints: requests, matches the referee judged, forced account changes").pack(anchor="w", pady=(px(20), px(6)))
        self.log = text_box(self.body, height=8, mono=True)
        self.log.pack(fill="both", expand=True)
        self.log.configure(state="disabled")
        self._offset, self._showing = 0, None

        self.start = self.action("Start", lambda: self._do(True, backend.start_server), "Accent.TButton")
        self.stop = self.action("Stop", self._stop, "Danger.TButton")
        self.restart = self.action("Restart", self._restart)
        self._busy = False
        self.update_state(app.health, app.started_here)
        self._tail()

    def _copy(self) -> None:
        self.clipboard_clear()
        self.clipboard_append(self.url)
        self.say("Address copied.")

    # ------------------------------------------------------------------ state

    def update_state(self, health: dict | None, here: bool) -> None:
        if self._busy:
            return
        running = health is not None
        self.dot.configure(foreground=GOOD if running else BAD)
        self.state.configure(text="Running" if running else "Stopped")
        if running:
            players = health.get("players")
            self.detail.configure(text="Port %d · %s" % (backend.PORT, "1 account" if players == 1 else "%s accounts" % players))
        else:
            self.detail.configure(text="Nobody can play online until it is started.")
        self.start.state(["disabled" if running else "!disabled"])
        self.stop.state(["!disabled" if running else "disabled"])
        self.restart.state(["!disabled" if running else "disabled"])
        self._here = here

    def _do(self, ends_running: bool, *steps) -> None:
        """Runs the steps off the window's thread, then waits for the server to be up (or gone)."""
        self._busy = True
        for button in (self.start, self.stop, self.restart):
            button.state(["disabled"])
        self.state.configure(text="Working…")

        def work() -> None:
            for step in steps:
                step()
            self.app.wait_until(ends_running)
            self.after(400, done)  # by then the window has taken in the answer

        def done() -> None:
            self._busy = False
            self.update_state(self.app.health, self.app.started_here)
            if (self.app.health is not None) != ends_running:
                self.say("The server didn't %s. The log below may say why." % ("start" if ends_running else "stop"), bad=True)

        threading.Thread(target=work, daemon=True).start()

    def _stop(self) -> None:
        if messagebox.askokcancel("Stop the server", "Anyone in a match right now will lose it, and nobody can play online until the server is started again.", icon="warning", parent=self):
            self._do(False, backend.stop_server)

    def _restart(self) -> None:
        if messagebox.askokcancel("Restart the server", "Anyone in a match right now will lose it. The server is back in a few seconds.", icon="warning", parent=self):
            self._do(True, backend.stop_server, lambda: self.app.wait_until(False), backend.start_server)

    # ------------------------------------------------------------------ the log

    def _show(self, text: str, replace: bool) -> None:
        at_end = self.log.yview()[1] > 0.99
        self.log.configure(state="normal")
        if replace:
            self.log.delete("1.0", "end")
        self.log.insert("end", text)
        if int(self.log.index("end-1c").split(".")[0]) > 3000:
            self.log.delete("1.0", "1000.0")
        self.log.configure(state="disabled")
        if at_end or replace:
            self.log.see("end")

    def _tail(self) -> None:
        running, here = self.app.health is not None, getattr(self, "_here", False)
        if running and not here:
            note = "This server was started outside the manager, so what it prints is in its own window.\nRestart it here to see its log."
            if self._showing != note:
                self._show(note, True)
                self._showing, self._offset = note, 0
        else:
            try:
                size = os.path.getsize(backend.LOG)
                if self._showing != "log" or size < self._offset:
                    self._show("", True)
                    self._showing, self._offset = "log", 0
                if size > self._offset:
                    with open(backend.LOG, encoding="utf-8", errors="replace") as f:
                        f.seek(self._offset)
                        self._show(f.read(), False)
                        self._offset = f.tell()
            except OSError:
                pass
        self.after(800, self._tail)
