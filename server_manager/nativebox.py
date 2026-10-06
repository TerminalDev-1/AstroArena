"""A text box that Windows voice typing (Win+H) can talk into.

Tk draws its own text boxes and doesn't take part in the Windows text services that voice typing writes
through, so dictated words only reach a Tk box when dictation is switched off. A real Windows edit control
does take part. This puts one inside the manager's rounded field outline, for the boxes worth dictating
into. Anywhere but Windows it is an ordinary box.
"""

from __future__ import annotations

import sys
import tkinter as tk
from tkinter import ttk

import theme
from theme import px

if sys.platform == "win32":
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
    _gdi.CreateFontW.restype = wintypes.HFONT
    _gdi.CreateFontW.argtypes = [ctypes.c_int] * 5 + [wintypes.DWORD] * 8 + [wintypes.LPCWSTR]
    _CHILD, _VISIBLE, _SCROLLS_SIDEWAYS, _SET_FONT, _SET_LIMIT = 0x40000000, 0x10000000, 0x0080, 0x0030, 0x00C5


class DictationEntry(ttk.Frame):
    """One line of text, with `get()`, `set(text)` and `enable(on)`."""

    def __init__(self, parent: tk.Misc, limit: int = 200):
        super().__init__(parent, style="Field.TFrame", height=px(31))
        self._box = None   # the Windows edit control, once the frame is on screen
        self._text, self._on, self._limit = "", True, limit
        if sys.platform == "win32":
            self.bind("<Map>", self._make, add="+")
            self.bind("<Configure>", self._fit, add="+")
        else:
            self._var = tk.StringVar()
            ttk.Entry(self, textvariable=self._var).pack(fill="both", expand=True)

    def _make(self, _event=None) -> None:
        if self._box is not None:
            return
        self._box = _user.CreateWindowExW(0, "EDIT", self._text, _CHILD | _VISIBLE | _SCROLLS_SIDEWAYS, 0, 0, 10, 10, self.winfo_id(), None, None, None)
        self._font = _gdi.CreateFontW(-round(10 * self.winfo_fpixels("1i") / 72), 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, "Segoe UI")
        _user.SendMessageW(self._box, _SET_FONT, self._font, 1)
        _user.SendMessageW(self._box, _SET_LIMIT, self._limit, 0)
        _user.EnableWindow(self._box, self._on)
        self._fit()

    def _fit(self, _event=None) -> None:
        if self._box is not None:
            edge, high = px(9), round(18 * self.winfo_fpixels("1i") / 96)
            _user.MoveWindow(self._box, edge, max(1, (self.winfo_height() - high) // 2), max(10, self.winfo_width() - 2 * edge), high, True)

    def get(self) -> str:
        if sys.platform != "win32":
            return self._var.get()
        if self._box is None:
            return self._text
        buffer = ctypes.create_unicode_buffer(_user.GetWindowTextLengthW(self._box) + 1)
        _user.GetWindowTextW(self._box, buffer, len(buffer))
        return buffer.value

    def set(self, text: str) -> None:
        self._text = str(text)
        if sys.platform != "win32":
            self._var.set(self._text)
        elif self._box is not None:
            _user.SetWindowTextW(self._box, self._text)

    def enable(self, on: bool) -> None:
        self._on = bool(on)
        if sys.platform != "win32":
            for child in self.winfo_children():
                child.state(["!disabled" if on else "disabled"])
        elif self._box is not None:
            _user.EnableWindow(self._box, self._on)
        self.state(["!disabled" if on else "disabled"])

