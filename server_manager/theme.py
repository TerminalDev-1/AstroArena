"""How the manager looks: one palette, the ttk styles, and the few widgets every page shares.

A light, quiet look: a white work area, a warm grey sidebar, hairlines instead of boxes, and one accent (the
orange of the game's PLAY button) kept for the thing you are meant to press. ttk has no rounded corners of its
own, so the rounded shapes (buttons, fields, checkboxes, the sidebar's pills) are small pictures drawn here,
pixel by pixel with soft edges, and stretched by ttk from their middles.
"""

from __future__ import annotations

import math
import tkinter as tk
from tkinter import font as tkfont
from tkinter import ttk

BG = "#ffffff"
SIDE = "#f5f3ee"
SOFT = "#faf9f6"
HOVER = "#f1eee8"
DOWN = "#e8e4dc"
LINE = "#e6e2da"
EDGE = "#d3cec4"
TEXT = "#1f1d1a"
DIM = "#78736a"
FAINT = "#aaa59b"
ACCENT = "#e8590c"
ACCENT_HOVER = "#d14f0a"
ACCENT_DOWN = "#b94608"
ACCENT_SOFT = "#fdebdd"
GOOD = "#1a9d55"
BAD = "#d6303f"
INK = "#1f1d1a"
INK_TEXT = "#e9e6df"

FONT = ("Segoe UI", 10)
BOLD = ("Segoe UI Semibold", 10)
SMALL = ("Segoe UI", 9)
SMALL_BOLD = ("Segoe UI Semibold", 9)
TITLE = ("Segoe UI Semibold", 19)
HEAD = ("Segoe UI Semibold", 11)
BIG = ("Segoe UI Semibold", 14)
MONO = ("Consolas", 10)
ICONS: tuple | None = None  # the icon font, if this PC has one

_scale = 1.0


def px(n: float) -> int:
    """`n` pixels at 100% display scaling, as pixels on this display."""
    return int(round(n * _scale))


# ---------------------------------------------------------------------------- drawing

def _rgb(colour: str) -> tuple[int, int, int]:
    return int(colour[1:3], 16), int(colour[3:5], 16), int(colour[5:7], 16)


def _mix(a: tuple, b: tuple, t: float) -> tuple:
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def _cover(distance: float) -> float:
    """How much of a pixel a shape covers when its edge is `distance` away (negative = inside)."""
    return min(max(0.5 - distance, 0.0), 1.0)


def _box(w: int, h: int, r: float, fill: str, edge: str, back: str, line: float = 1.0) -> list[list[tuple]]:
    """A rounded rectangle on `back`, with an `edge` line round a `fill`, as rows of colours."""
    fill_c, edge_c, back_c = _rgb(fill), _rgb(edge), _rgb(back)
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            qx, qy = abs(x + 0.5 - w / 2) - (w / 2 - r), abs(y + 0.5 - h / 2) - (h / 2 - r)
            d = math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r
            row.append(_mix(_mix(back_c, edge_c, _cover(d)), fill_c, _cover(d + line)))
        rows.append(row)
    return rows


def _stroke(rows: list[list[tuple]], points: list[tuple[float, float]], width: float, colour: str) -> None:
    """Draws a line through `points` (in pixels) onto `rows`, with soft edges."""
    ink = _rgb(colour)
    for y, row in enumerate(rows):
        for x in range(len(row)):
            cx, cy, best = x + 0.5, y + 0.5, 1e9
            for (ax, ay), (bx, by) in zip(points, points[1:]):
                dx, dy = bx - ax, by - ay
                t = min(max(((cx - ax) * dx + (cy - ay) * dy) / (dx * dx + dy * dy), 0.0), 1.0)
                best = min(best, math.hypot(cx - ax - dx * t, cy - ay - dy * t))
            cover = _cover(best - width / 2)
            if cover:
                row[x] = _mix(row[x], ink, cover)


def _stretch(rows: list[list[tuple]], w: int, h: int) -> list[list[tuple]]:
    """A small box grown to w x h by repeating its middle column and middle row. (The rows it repeats are the
    same list, which `_picture` notices and only works out once.)"""
    mid = len(rows) // 2
    wide = [row[:mid] + [row[mid]] * (w - len(row) + 1) + row[mid + 1:] for row in rows]
    return wide[:mid] + [wide[mid]] * (h - len(rows) + 1) + wide[mid + 1:]


_kept: list[tk.PhotoImage] = []  # Tk forgets a picture nobody holds


def _picture(root: tk.Misc, rows: list[list[tuple]]) -> tk.PhotoImage:
    image = tk.PhotoImage(master=root, width=len(rows[0]), height=len(rows))
    done: dict[int, str] = {}  # rows and pixels that repeat are only turned into text once

    def text(row: list[tuple]) -> str:
        if id(row) not in done:
            colours: dict[tuple, str] = {}
            done[id(row)] = "{" + " ".join(colours.setdefault(p, "#%02x%02x%02x" % tuple(int(round(c)) for c in p)) for p in row) + "}"
        return done[id(row)]

    image.put(" ".join(text(row) for row in rows))
    _kept.append(image)
    return image


def rounded(root: tk.Misc, w: int, h: int, r: float, fill: str, edge: str | None = None, back: str = BG) -> tk.PhotoImage:
    """A rounded box as a picture, for canvases (the sidebar's pills, the overview's tiles)."""
    small = int(r) * 2 + 3
    return _picture(root, _stretch(_box(small, small, r, fill, edge or fill, back), w, h))


def apply(root: tk.Tk) -> None:
    global _scale, ICONS
    _scale = root.winfo_fpixels("1i") / 96.0
    families = set(tkfont.families(root))
    ICONS = next(((name, 11) for name in ("Segoe Fluent Icons", "Segoe MDL2 Assets") if name in families), None)
    root.configure(bg=BG)
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure(".", background=BG, foreground=TEXT, font=FONT, bordercolor=LINE, lightcolor=BG, darkcolor=BG,
                    troughcolor=BG, focuscolor=BG, selectbackground=ACCENT_SOFT, selectforeground=TEXT, insertcolor=TEXT)

    style.configure("TLabel", background=BG, foreground=TEXT)
    style.configure("Dim.TLabel", foreground=DIM)
    style.configure("Small.TLabel", foreground=DIM, font=SMALL)
    style.configure("Title.TLabel", font=TITLE)
    style.configure("Head.TLabel", font=HEAD)
    style.configure("Good.TLabel", foreground=GOOD)
    style.configure("Bad.TLabel", foreground=BAD)
    style.configure("Side.TFrame", background=SIDE)
    style.configure("Line.TFrame", background=LINE)

    r, side = px(7), px(7) * 2 + 3

    def shape(fill: str, edge: str, line: float = 1.0) -> tk.PhotoImage:
        """A rounded box for ttk to stretch. It is made wide and tall, because ttk fills a bigger widget by
        repeating the picture's middle, and repeating a middle one pixel wide takes it seconds."""
        return _picture(root, _stretch(_box(side, side, r, fill, edge, BG, line), px(260), px(52)))

    def button(name: str, element: str, text: str, normal, hover, down, off, off_text: str = FAINT) -> None:
        style.element_create(element, "image", normal, ("disabled", off), ("pressed", down), ("active", hover), border=r + 1, padding=0, sticky="nsew", width=side, height=side)
        style.layout(name, [(element, {"sticky": "nswe", "children": [
            ("Button.padding", {"sticky": "nswe", "children": [("Button.label", {"sticky": "nswe"})]})]})])
        style.configure(name, foreground=text, padding=(px(18), px(8)), font=BOLD, anchor="center", background=BG)
        style.map(name, foreground=[("disabled", off_text)], background=[("active", BG)])

    plain = (shape(BG, EDGE), shape(HOVER, EDGE), shape(DOWN, EDGE), shape(BG, LINE))
    button("TButton", "Plain.button", TEXT, *plain)
    button("Danger.TButton", "Danger.button", BAD, *plain)
    button("Accent.TButton", "Accent.button", "#ffffff", shape(ACCENT, ACCENT), shape(ACCENT_HOVER, ACCENT_HOVER),
           shape(ACCENT_DOWN, ACCENT_DOWN), shape(HOVER, HOVER), FAINT)

    # Fields: one rounded outline, orange while typing in it.
    style.element_create("Round.field", "image", shape(BG, EDGE), ("disabled", shape(SOFT, LINE)), ("focus", shape(BG, ACCENT, 1.6)),
                         border=r + 1, padding=0, sticky="nsew", width=side, height=side)
    style.layout("TEntry", [("Round.field", {"sticky": "nswe", "children": [
        ("Entry.padding", {"sticky": "nswe", "children": [("Entry.textarea", {"sticky": "nswe"})]})]})])
    n = px(18)
    arrow = [[_rgb(BG)] * n for _ in range(n)]
    _stroke(arrow, [(n * 0.28, n * 0.40), (n * 0.50, n * 0.62), (n * 0.72, n * 0.40)], max(1.4, px(1.5)), DIM)
    style.element_create("Round.arrow", "image", _picture(root, arrow), sticky="")
    style.layout("TCombobox", [("Round.field", {"sticky": "nswe", "children": [
        ("Round.arrow", {"side": "right", "sticky": ""}),
        ("Combobox.padding", {"sticky": "nswe", "children": [("Combobox.textarea", {"sticky": "nswe"})]})]})])
    for name in ("TEntry", "TCombobox"):
        style.configure(name, foreground=TEXT, padding=(px(9), px(6)), fieldbackground=BG, background=BG)
        style.map(name, foreground=[("disabled", FAINT)], fieldbackground=[("disabled", SOFT), ("readonly", BG)],
                  selectbackground=[("readonly", BG), ("!focus", BG)], selectforeground=[("readonly", TEXT), ("!focus", TEXT)])
    style.configure("TCombobox", padding=(px(9), px(6), px(6), px(6)))
    # The list a combobox drops down is a plain Tk listbox: it takes its colours from the option database.
    for key, value in (("background", BG), ("foreground", TEXT), ("selectBackground", ACCENT_SOFT), ("selectForeground", TEXT),
                       ("font", FONT), ("borderWidth", 0), ("relief", "flat")):
        root.option_add("*TCombobox*Listbox." + key, value)

    style.layout("Field.TFrame", [("Round.field", {"sticky": "nswe"})])  # the outline round a DictationEntry (nativebox.py)
    style.configure("Field.TFrame", background=BG)

    # Checkboxes: the theme's own marks "on" with a cross, which reads as "no". These get a tick.
    def check(fill: str, edge: str, tick: str | None) -> tk.PhotoImage:
        size = px(17)
        rows = [row + [_rgb(BG)] * px(8) for row in _box(size, size, px(4), fill, edge, BG)]  # room before the label
        if tick:
            _stroke(rows, [(size * 0.27, size * 0.52), (size * 0.44, size * 0.68), (size * 0.74, size * 0.34)], max(1.6, px(1.8)), tick)
        return _picture(root, rows)

    style.element_create("Tick.indicator", "image", check(BG, EDGE, None), ("disabled", "selected", check(DOWN, DOWN, DIM)),
                         ("selected", check(ACCENT, ACCENT, "#ffffff")), ("disabled", check(SOFT, LINE, None)), sticky="w")
    style.layout("TCheckbutton", [("Checkbutton.padding", {"sticky": "nswe", "children": [
        ("Tick.indicator", {"side": "left", "sticky": ""}), ("Checkbutton.label", {"side": "left", "sticky": "nswe"})]})])
    style.configure("TCheckbutton", background=BG, foreground=TEXT, padding=(0, px(3)))
    style.map("TCheckbutton", background=[("active", BG)], foreground=[("disabled", DIM)])

    style.configure("Treeview", background=BG, fieldbackground=BG, foreground=TEXT, rowheight=px(28), borderwidth=0, relief="flat")
    style.layout("Treeview", [("Treeview.treearea", {"sticky": "nswe"})])
    style.map("Treeview", background=[("selected", ACCENT_SOFT)], foreground=[("selected", TEXT)])
    style.configure("Treeview.Heading", background=SOFT, foreground=DIM, relief="flat", font=SMALL_BOLD, padding=(px(8), px(6)),
                    bordercolor=SOFT, lightcolor=SOFT, darkcolor=SOFT)
    style.map("Treeview.Heading", background=[("active", SOFT)])

    # A slim scrollbar with no arrows.
    style.layout("Vertical.TScrollbar", [("Vertical.Scrollbar.trough", {"sticky": "ns", "children": [
        ("Vertical.Scrollbar.thumb", {"expand": "1", "sticky": "nswe"})]})])
    style.configure("Vertical.TScrollbar", background=EDGE, troughcolor=BG, bordercolor=BG, lightcolor=EDGE, darkcolor=EDGE,
                    arrowsize=px(9), relief="flat", gripcount=0)
    style.map("Vertical.TScrollbar", background=[("active", FAINT), ("disabled", BG)], lightcolor=[("active", FAINT), ("disabled", BG)],
              darkcolor=[("active", FAINT), ("disabled", BG)])


# ---------------------------------------------------------------------------- shared widgets

class Page(ttk.Frame):
    """One screen of the manager: a title, a line saying what it is for, a body, and a bar along the bottom
    with what just happened on the left and the page's buttons on the right."""

    title = ""
    about = ""

    def __init__(self, parent: tk.Misc, app):
        super().__init__(parent)
        self.app = app
        self.primary = None  # what Ctrl+S does here
        head = ttk.Frame(self, padding=(px(30), px(18), px(30), 0))
        head.pack(fill="x")
        ttk.Label(head, text=self.title, style="Title.TLabel").pack(anchor="w")
        ttk.Label(head, text=self.about, style="Dim.TLabel", wraplength=px(880), justify="left").pack(anchor="w", pady=(px(1), 0))
        foot = ttk.Frame(self)
        foot.pack(side="bottom", fill="x")
        ttk.Frame(foot, style="Line.TFrame", height=1).pack(fill="x")
        self.bar = ttk.Frame(foot, padding=(px(30), px(10)))
        self.bar.pack(fill="x")
        self._said = ttk.Label(self.bar, text="", style="Small.TLabel")
        self._said.pack(side="left")
        self._clear = None
        self.body = ttk.Frame(self, padding=(px(30), px(14), px(30), px(10)))
        self.body.pack(fill="both", expand=True)

    def action(self, text: str, command, style: str = "TButton") -> ttk.Button:
        """A button in the bar. The first one added sits furthest right; the orange one is what Ctrl+S presses."""
        button = ttk.Button(self.bar, text=text, command=command, style=style)
        button.pack(side="right", padx=(px(8), 0))
        if style == "Accent.TButton" and self.primary is None:
            self.primary = button
            self._quiet()
        return button

    def _quiet(self) -> None:
        self._said.configure(text="Ctrl+S: %s" % self.primary.cget("text").lower() if self.primary is not None else "", style="Small.TLabel")

    def say(self, text: str, bad: bool = False) -> None:
        """A line at the bottom left that goes away by itself."""
        self._said.configure(text=text, style="Bad.TLabel" if bad else "Good.TLabel")
        if self._clear:
            self.after_cancel(self._clear)
        self._clear = self.after(6000, self._quiet)

    def shown(self) -> None:
        """Called each time the page comes to the front."""


def heading(parent: tk.Misc, text: str, note: str = "") -> ttk.Frame:
    row = ttk.Frame(parent)
    ttk.Label(row, text=text, style="Head.TLabel").pack(side="left")
    if note:
        ttk.Label(row, text=note, style="Small.TLabel").pack(side="left", padx=(px(10), 0), pady=(px(2), 0))
    return row


def entry(parent: tk.Misc, var: tk.Variable, width: int = 12, **more) -> ttk.Entry:
    return ttk.Entry(parent, textvariable=var, width=width, **more)


def number(parent: tk.Misc, var: tk.Variable, low: int = 0, high: int = 2_000_000_000, width: int = 9) -> ttk.Entry:
    """A box for a whole number. (The limits are applied when it is read, with `as_int`.)"""
    return ttk.Entry(parent, textvariable=var, width=width)


def choice(parent: tk.Misc, var: tk.Variable, values: list[str], width: int = 16) -> ttk.Combobox:
    box = ttk.Combobox(parent, textvariable=var, values=values, state="readonly", width=width)
    box.bind("<<ComboboxSelected>>", lambda e: box.selection_clear(), add="+")
    return box


def as_int(var: tk.Variable, default: int = 0, low: int = 0, high: int = 2_000_000_000) -> int:
    """What a number box holds, however it was typed (1,000 and 1 000 are fine); `default` if it isn't a number."""
    try:
        return min(max(int(float(str(var.get()).replace(",", "").replace(" ", "").replace("_", ""))), low), high)
    except (ValueError, tk.TclError):
        return default


def table(parent: tk.Misc, columns: list[tuple[str, str, int, str]], height: int = 10) -> tuple[tk.Frame, ttk.Treeview]:
    """A list with headed columns inside a hairline, with a scrollbar. Each column is (key, heading, width at 100%, anchor)."""
    frame = tk.Frame(parent, bg=BG, highlightthickness=1, highlightbackground=LINE, highlightcolor=LINE)
    tree = ttk.Treeview(frame, columns=[c[0] for c in columns], show="headings", height=height, selectmode="browse")
    for key, text, width, anchor in columns:
        tree.heading(key, text=text, anchor=anchor)
        tree.column(key, width=px(width), minwidth=px(30), anchor=anchor, stretch=key == columns[-1][0] or width == 0)
    bar = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
    tree.configure(yscrollcommand=bar.set)
    tree.pack(side="left", fill="both", expand=True)
    bar.pack(side="right", fill="y", pady=px(2))
    return frame, tree


def text_box(parent: tk.Misc, height: int = 6, mono: bool = False) -> tk.Text:
    """A box for longer text. The monospaced one is dark, like the console it stands in for."""
    back, fore, edge = (INK, INK_TEXT, INK) if mono else (BG, TEXT, EDGE)
    return tk.Text(parent, height=height, width=40, wrap="word", bg=back, fg=fore, insertbackground=fore,
                   selectbackground=ACCENT if mono else ACCENT_SOFT, selectforeground="#ffffff" if mono else TEXT, relief="flat",
                   highlightthickness=1, highlightbackground=edge, highlightcolor=INK if mono else ACCENT,
                   font=MONO if mono else FONT, padx=px(12), pady=px(10), undo=True, spacing1=px(1))


class Tile(tk.Canvas):
    """A small rounded panel with a caption and one value: the overview's at-a-glance numbers."""

    def __init__(self, parent: tk.Misc, caption: str, width: int = 200):
        w, h = px(width), px(74)
        super().__init__(parent, width=w, height=h, bg=BG, highlightthickness=0)
        self.create_image(0, 0, anchor="nw", image=rounded(self, w, h, px(10), SOFT, LINE))
        self.create_text(px(16), px(21), anchor="w", text=caption, fill=DIM, font=SMALL)
        self._dot = self.create_oval(0, 0, 0, 0, outline="")
        self._value = self.create_text(px(16), px(48), anchor="w", text="", fill=TEXT, font=BIG)

    def set(self, value: str, dot: str | None = None) -> None:
        """The value, with a coloured dot in front of it if one is given."""
        left = px(16)
        if dot:
            self.coords(self._dot, left, px(43), left + px(10), px(53))
            self.itemconfigure(self._dot, fill=dot)
            left += px(18)
        else:
            self.coords(self._dot, 0, 0, 0, 0)
        self.coords(self._value, left, px(48))
        self.itemconfigure(self._value, text=value)
