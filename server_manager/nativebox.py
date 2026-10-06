"""Text boxes that Windows voice typing (Win+H) can talk into.

Tk draws its own text boxes and doesn't take part in the Windows text services that voice typing writes
through, so dictated words only reach a Tk box when dictation is switched off. A real Windows edit control
does take part. These put one inside the manager's rounded field outline, for every box that holds words
(names, reasons, headlines, messages); boxes for numbers and dates stay ordinary. Anywhere but Windows these
are ordinary boxes too.

A Windows control inside a Tk window lives outside Tk: Tk can't see its text change or its key presses. So
one slow heartbeat per window looks after all of them: it copies what was typed into each box's variable, and
passes on the keys the rest of the manager listens for (Ctrl+S, Ctrl+1 to 8, Tab) while one of them has the cursor.
"""

from __future__ import annotations

import sys
import tkinter as tk
from tkinter import ttk

from theme import px

WINDOWS = sys.platform == "win32"
if WINDOWS:
    import ctypes
    from ctypes import wintypes

    _user, _gdi = ctypes.windll.user32, ctypes.windll.gdi32
    _user.CreateWindowExW.restype = wintypes.HWND
    _user.CreateWindowExW.argtypes = [wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_int, ctypes.c_int,
                                      ctypes.c_int, ctypes.c_int, wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID]
    _user.SendMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    _user.MoveWindow.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.BOOL]
    _user.SetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPCWSTR]
    _user.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    _user.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    _user.EnableWindow.argtypes = [wintypes.HWND, wintypes.BOOL]
    _user.SetFocus.argtypes = [wintypes.HWND]
    _user.GetFocus.restype = wintypes.HWND
    _user.GetAsyncKeyState.argtypes = [ctypes.c_int]
    _user.GetAsyncKeyState.restype = ctypes.c_short
    _gdi.CreateFontW.restype = wintypes.HFONT
    _gdi.CreateFontW.argtypes = [ctypes.c_int] * 5 + [wintypes.DWORD] * 8 + [wintypes.LPCWSTR]
    _CHILD, _VISIBLE, _SCROLLBAR = 0x40000000, 0x10000000, 0x00200000
    _SIDEWAYS, _MANY_LINES, _DOWNWARDS, _ENTER_IS_A_LINE = 0x0080, 0x0004, 0x0040, 0x1000
    _SET_FONT, _SET_LIMIT, _SELECT = 0x0030, 0x00C5, 0x00B1
    _CTRL, _SHIFT, _TAB = 0x11, 0x10, 0x09

HEARTBEAT_MS = 60


class DictationEntry(ttk.Frame):
    """One line of text. Give it a `variable` to keep in step with, or use `get()` and `set(text)`.
    `chars` is roughly how many characters wide it asks to be; packed with fill="x" it takes what it is given."""

    many_lines = False

    def __init__(self, parent: tk.Misc, variable: tk.StringVar | None = None, chars: int = 20, limit: int = 200, height: int = 31):
        super().__init__(parent, style="Field.TFrame", width=px(chars * 7.4 + 22), height=px(height), takefocus=WINDOWS)
        self.pack_propagate(False)
        self._box = None  # the Windows edit control, once the frame is on screen
        self._var = variable if variable is not None else tk.StringVar(self)
        self._seen = self._var.get()  # the control's text as last looked at
        self._on, self._limit = True, limit
        if WINDOWS:
            self.bind("<Map>", self._make, add="+")
            self.bind("<Configure>", self._fit, add="+")
            self.bind("<FocusIn>", lambda e: self._box and _user.SetFocus(self._box), add="+")
            self._var.trace_add("write", lambda *_: self._push())
        else:
            self._plain = ttk.Entry(self, textvariable=self._var)
            self._plain.pack(fill="both", expand=True)

    # ------------------------------------------------------------------ the Windows control

    def _make(self, _event=None) -> None:
        if self._box is not None:
            return
        look = _CHILD | _VISIBLE | ((_MANY_LINES | _DOWNWARDS | _ENTER_IS_A_LINE | _SCROLLBAR) if self.many_lines else _SIDEWAYS)
        self._box = _user.CreateWindowExW(0, "EDIT", self._var.get().replace("\n", "\r\n"), look, 0, 0, 10, 10, self.winfo_id(), None, None, None)
        self._font = _gdi.CreateFontW(-round(10 * self.winfo_fpixels("1i") / 72), 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, "Segoe UI")
        _user.SendMessageW(self._box, _SET_FONT, self._font, 1)
        _user.SendMessageW(self._box, _SET_LIMIT, self._limit, 0)
        _user.EnableWindow(self._box, self._on)
        self._seen = self._var.get()
        self._fit()
        _watch(self)

    def _fit(self, _event=None) -> None:
        if self._box is None:
            return
        edge = px(9)
        if self.many_lines:
            _user.MoveWindow(self._box, edge, px(7), max(10, self.winfo_width() - edge - px(3)), max(10, self.winfo_height() - px(14)), True)
        else:
            high = round(18 * self.winfo_fpixels("1i") / 96)
            _user.MoveWindow(self._box, edge, max(1, (self.winfo_height() - high) // 2), max(10, self.winfo_width() - 2 * edge), high, True)

    def _typed(self) -> str:
        buffer = ctypes.create_unicode_buffer(_user.GetWindowTextLengthW(self._box) + 1)
        _user.GetWindowTextW(self._box, buffer, len(buffer))
        return buffer.value.replace("\r\n", "\n")

    def _pull(self) -> None:
        """What has been typed (or dictated) since the last look goes into the variable."""
        if self._box is not None:
            text = self._typed()
            if text != self._seen:
                self._seen = text
                self._var.set(text)

    def _push(self) -> None:
        """The variable was set from elsewhere: show it."""
        text = self._var.get()
        if self._box is not None and text != self._seen:
            self._seen = text
            _user.SetWindowTextW(self._box, text.replace("\n", "\r\n"))

    # ------------------------------------------------------------------ for the pages

    def get(self) -> str:
        self._pull()
        return self._var.get()

    def set(self, text: str) -> None:
        self._var.set(str(text))

    def enable(self, on: bool) -> None:
        self._on = bool(on)
        if not WINDOWS:
            self._plain.state(["!disabled" if on else "disabled"])
        elif self._box is not None:
            _user.EnableWindow(self._box, self._on)
        ttk.Frame.state(self, ["!disabled" if on else "disabled"])

    def focus_set(self) -> None:
        if WINDOWS and self._box is not None:
            _user.SetFocus(self._box)
        else:
            super().focus_set()


class DictationText(DictationEntry):
    """Several lines of text, with a scrollbar when they don't fit."""

    many_lines = True

    def __init__(self, parent: tk.Misc, variable: tk.StringVar | None = None, limit: int = 1200, height: int = 150):
        super().__init__(parent, variable, 30, limit, height)
        if not WINDOWS:
            self._plain.destroy()
            self._plain = tk.Text(self, wrap="word", relief="flat", height=4, width=20)
            self._plain.pack(fill="both", expand=True, padx=px(8), pady=px(6))
            self._plain.bind("<KeyRelease>", lambda e: self._var.set(self._plain.get("1.0", "end-1c")))
            self._var.trace_add("write", lambda *_: self._plain.get("1.0", "end-1c") != self._var.get() and self._plain.replace("1.0", "end", self._var.get()))


# ---------------------------------------------------------------------------- the heartbeat

def _watch(box: DictationEntry) -> None:
    """Adds a box to its window's heartbeat, starting it if this is the window's first."""
    window = box.winfo_toplevel()
    if not hasattr(window, "_dictation_boxes"):
        window._dictation_boxes, window._dictation_keys = [], {}
        window.after(HEARTBEAT_MS, lambda: _beat(window))
    window._dictation_boxes.append(box)


def _beat(window: tk.Misc) -> None:
    boxes = window._dictation_boxes = [box for box in window._dictation_boxes if box.winfo_exists()]
    for box in boxes:
        box._pull()
    # Keys pressed in a Windows control never reach Tk. While one of ours has the cursor, the ones that matter are passed on.
    focus = _user.GetFocus()
    here = next((box for box in boxes if box._box == focus), None) if focus else None
    held = (lambda key: bool(_user.GetAsyncKeyState(key) & 0x8000)) if here is not None else (lambda key: False)
    was, now = window._dictation_keys, {}
    for key, event in [(ord("S"), "<Control-s>")] + [(ord(str(n)), "<Control-Key-%d>" % n) for n in range(1, 9)]:
        now[key] = held(_CTRL) and held(key)
        if now[key] and not was.get(key):
            window.event_generate(event)
    now[_TAB] = held(_TAB) and not held(_CTRL)
    if now[_TAB] and not was.get(_TAB) and here is not None:
        other = here.tk_focusPrev() if held(_SHIFT) else here.tk_focusNext()
        if other is not None:
            other.focus_set()
    window._dictation_keys = now
    window.after(HEARTBEAT_MS, lambda: _beat(window))
