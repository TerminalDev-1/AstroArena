"""How the manager looks: one palette, one set of ttk styles, and the few widgets every page shares."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk

BG = "#17122b"
SIDE = "#1f1838"
FIELD = "#110d22"
RAISED = "#2d2552"
HOVER = "#3a3069"
LINE = "#3b3266"
TEXT = "#ece9f7"
DIM = "#9d96bd"
GOLD = "#ffc93c"
GOLD_INK = "#231a05"
GOOD = "#4cd787"
BAD = "#ff5a6b"
SELECT = "#43377f"

FONT = ("Segoe UI", 10)
BOLD = ("Segoe UI Semibold", 10)
SMALL = ("Segoe UI", 9)
TITLE = ("Segoe UI Semibold", 17)
HEAD = ("Segoe UI Semibold", 11)
MONO = ("Consolas", 9)

_scale = 1.0


def px(n: float) -> int:
    """`n` pixels at 100% display scaling, as pixels on this display."""
    return int(round(n * _scale))


def apply(root: tk.Tk) -> None:
    global _scale
    _scale = root.winfo_fpixels("1i") / 96.0
    root.configure(bg=BG)
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure(".", background=BG, foreground=TEXT, font=FONT, bordercolor=LINE, lightcolor=BG, darkcolor=BG,
                    troughcolor=FIELD, focuscolor=BG, selectbackground=SELECT, selectforeground=TEXT, insertcolor=TEXT)

    style.configure("TLabel", background=BG, foreground=TEXT)
    style.configure("Dim.TLabel", foreground=DIM)
    style.configure("Small.TLabel", foreground=DIM, font=SMALL)
    style.configure("Title.TLabel", font=TITLE)
    style.configure("Head.TLabel", font=HEAD, foreground=GOLD)
    style.configure("Good.TLabel", foreground=GOOD)
    style.configure("Bad.TLabel", foreground=BAD)
    style.configure("Side.TFrame", background=SIDE)
    style.configure("Side.TLabel", background=SIDE)
    style.configure("SideDim.TLabel", background=SIDE, foreground=DIM, font=SMALL)
    style.configure("Brand.TLabel", background=SIDE, foreground=GOLD, font=("Segoe UI Semibold", 13))
    style.configure("Line.TFrame", background=LINE)

    def button(name: str, back: str, fore: str, hover: str, **more) -> None:
        style.configure(name, background=back, foreground=fore, bordercolor=back, lightcolor=back, darkcolor=back,
                        padding=(px(14), px(6)), relief="flat", font=BOLD, **more)
        style.map(name, background=[("disabled", BG), ("pressed", hover), ("active", hover)],
                  bordercolor=[("disabled", LINE), ("active", hover)], lightcolor=[("disabled", BG), ("active", hover)],
                  darkcolor=[("disabled", BG), ("active", hover)], foreground=[("disabled", LINE)])

    button("TButton", RAISED, TEXT, HOVER)
    button("Accent.TButton", GOLD, GOLD_INK, "#ffd766")
    button("Danger.TButton", RAISED, BAD, HOVER)
    button("Nav.TButton", SIDE, DIM, RAISED, anchor="w")
    button("NavOn.TButton", RAISED, GOLD, RAISED, anchor="w")
    style.configure("Nav.TButton", padding=(px(16), px(9)), font=FONT)
    style.configure("NavOn.TButton", padding=(px(16), px(9)))

    for name in ("TEntry", "TSpinbox", "TCombobox"):
        style.configure(name, fieldbackground=FIELD, foreground=TEXT, bordercolor=LINE, lightcolor=FIELD, darkcolor=FIELD,
                        padding=(px(6), px(4)), arrowcolor=DIM, background=RAISED, arrowsize=px(13))
        style.map(name, bordercolor=[("focus", GOLD)], fieldbackground=[("disabled", BG), ("readonly", FIELD)],
                  foreground=[("disabled", LINE)], selectbackground=[("readonly", FIELD)], selectforeground=[("readonly", TEXT)],
                  background=[("active", HOVER)], arrowcolor=[("disabled", LINE)])
    # The list a combobox drops down is a plain Tk listbox: it takes its colours from the option database.
    for key, value in (("background", FIELD), ("foreground", TEXT), ("selectBackground", SELECT), ("selectForeground", TEXT), ("font", FONT)):
        root.option_add("*TCombobox*Listbox." + key, value)

    # The theme's own checkbox marks "on" with a cross, which reads as "no". These are drawn here instead: a tick.
    boxes = root._check_boxes = {  # kept on the window: Tk forgets an image nobody holds
        "off": _check_box(root, FIELD, LINE, None), "on": _check_box(root, GOLD, GOLD, GOLD_INK),
        "off_dim": _check_box(root, BG, LINE, None), "on_dim": _check_box(root, LINE, LINE, DIM),
    }
    style.element_create("Tick.indicator", "image", boxes["off"], ("disabled", "selected", boxes["on_dim"]),
                         ("selected", boxes["on"]), ("disabled", boxes["off_dim"]), sticky="w")
    style.layout("TCheckbutton", [("Checkbutton.padding", {"sticky": "nswe", "children": [
        ("Tick.indicator", {"side": "left", "sticky": ""}), ("Checkbutton.label", {"side": "left", "sticky": "nswe"})]})])
    style.configure("TCheckbutton", background=BG, foreground=TEXT, padding=(0, px(3)))
    style.map("TCheckbutton", background=[("active", BG)], foreground=[("disabled", DIM)])

    style.configure("Treeview", background=FIELD, fieldbackground=FIELD, foreground=TEXT, rowheight=px(27), borderwidth=0,
                    bordercolor=LINE, lightcolor=LINE, darkcolor=LINE)
    style.map("Treeview", background=[("selected", SELECT)], foreground=[("selected", TEXT)])
    style.configure("Treeview.Heading", background=RAISED, foreground=DIM, relief="flat", font=SMALL, padding=(px(6), px(5)),
                    bordercolor=RAISED, lightcolor=RAISED, darkcolor=RAISED)
    style.map("Treeview.Heading", background=[("active", RAISED)])

    style.configure("Vertical.TScrollbar", background=LINE, troughcolor=BG, bordercolor=BG, lightcolor=LINE, darkcolor=LINE,
                    arrowcolor=DIM, arrowsize=px(12), relief="flat")
    style.map("Vertical.TScrollbar", background=[("active", HOVER), ("disabled", BG)])


def _check_box(root: tk.Tk, fill: str, border: str, tick: str | None) -> tk.PhotoImage:
    """A checkbox as a picture, with a little room to its right for the label that follows."""
    n, line = px(16), max(1, px(1))
    image = tk.PhotoImage(master=root, width=n + px(8), height=n)
    image.put(BG, to=(0, 0, n + px(8), n))
    image.put(border, to=(0, 0, n, n))
    image.put(fill, to=(line, line, n - line, n - line))
    if tick:
        thick = max(2, px(2))
        for (x0, y0), (x1, y1) in (((0.24, 0.52), (0.42, 0.70)), ((0.42, 0.70), (0.76, 0.30))):
            steps = n * 2
            for i in range(steps + 1):
                x, y = int((x0 + (x1 - x0) * i / steps) * n), int((y0 + (y1 - y0) * i / steps) * n)
                image.put(tick, to=(x, y, x + thick, y + thick))
    return image


# ---------------------------------------------------------------------------- shared widgets

class Page(ttk.Frame):
    """One screen of the manager: a title, a line saying what it is for, a body, and a bar along the bottom
    for its buttons and for saying what just happened."""

    title = ""
    about = ""

    def __init__(self, parent: tk.Misc, app):
        super().__init__(parent, padding=(px(26), px(16), px(26), px(14)))
        self.app = app
        ttk.Label(self, text=self.title, style="Title.TLabel").pack(anchor="w")
        ttk.Label(self, text=self.about, style="Dim.TLabel", wraplength=px(900), justify="left").pack(anchor="w", pady=(0, px(12)))
        self.bar = ttk.Frame(self)
        self.bar.pack(side="bottom", fill="x", pady=(px(12), 0))
        self._said = ttk.Label(self.bar, text="", style="Dim.TLabel")
        self._said.pack(side="right")
        self._clear = None
        self.body = ttk.Frame(self)
        self.body.pack(fill="both", expand=True)

    def action(self, text: str, command, style: str = "TButton") -> ttk.Button:
        button = ttk.Button(self.bar, text=text, command=command, style=style)
        button.pack(side="left", padx=(0, px(8)))
        return button

    def say(self, text: str, bad: bool = False) -> None:
        """A line at the bottom right that goes away by itself."""
        self._said.configure(text=text, style="Bad.TLabel" if bad else "Good.TLabel")
        if self._clear:
            self.after_cancel(self._clear)
        self._clear = self.after(6000, lambda: self._said.configure(text=""))

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


def number(parent: tk.Misc, var: tk.Variable, low: int = 0, high: int = 2_000_000_000, width: int = 9) -> ttk.Spinbox:
    return ttk.Spinbox(parent, textvariable=var, from_=low, to=high, width=width)


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


def table(parent: tk.Misc, columns: list[tuple[str, str, int, str]], height: int = 10) -> tuple[ttk.Frame, ttk.Treeview]:
    """A list with headed columns and a scrollbar. Each column is (key, heading, width at 100%, anchor)."""
    frame = ttk.Frame(parent)
    tree = ttk.Treeview(frame, columns=[c[0] for c in columns], show="headings", height=height, selectmode="browse")
    for key, text, width, anchor in columns:
        tree.heading(key, text=text, anchor=anchor)
        tree.column(key, width=px(width), minwidth=px(30), anchor=anchor, stretch=key == columns[-1][0] or width == 0)
    bar = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
    tree.configure(yscrollcommand=bar.set)
    tree.pack(side="left", fill="both", expand=True)
    bar.pack(side="right", fill="y")
    return frame, tree


def text_box(parent: tk.Misc, height: int = 6, mono: bool = False) -> tk.Text:
    return tk.Text(parent, height=height, width=40, wrap="word", bg=FIELD, fg=TEXT, insertbackground=TEXT, selectbackground=SELECT,
                   selectforeground=TEXT, relief="flat", highlightthickness=1, highlightbackground=LINE, highlightcolor=GOLD,
                   font=MONO if mono else FONT, padx=px(8), pady=px(6), undo=True)
