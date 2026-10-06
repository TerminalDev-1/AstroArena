"""Reading and writing the server's .cfg files without disturbing what the owner wrote in them.

Files with a fixed set of settings (game, trophies, bots) are edited in place, one value at a time, so every
comment stays where it is. Files that are a list of things (shop offers, news items, notices) keep the
comment block at their top and have the list under it written out again.
"""

from __future__ import annotations

import configparser
import os
import textwrap


def read_text(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def write_text(path: str, text: str) -> None:
    """Replaces the file in one step, so the server never reads half of it."""
    scratch = path + ".new"
    with open(scratch, "w", encoding="utf-8", newline="\n") as f:
        f.write(text if text.endswith("\n") else text + "\n")
    os.replace(scratch, path)


def read_ini(path: str) -> configparser.ConfigParser:
    """The file's sections. Section names keep their case (they are often titles); keys are lower case."""
    parser = configparser.ConfigParser(interpolation=None)
    try:
        parser.read_string(read_text(path))
    except configparser.Error:
        pass
    return parser


def set_values(path: str, section: str, values: dict[str, object]) -> None:
    """Sets `key = value` lines in one section, in place. A key or section that isn't there yet is added."""
    values = {key.lower(): str(value) for key, value in values.items()}
    out: list[str] = []
    current, found, done, end = None, False, set(), 0
    for line in read_text(path).splitlines():
        bare = line.strip()
        if bare.startswith("[") and bare.endswith("]"):
            current = bare[1:-1].strip().lower()
            found = found or current == section.lower()
        elif current == section.lower() and bare and bare[0] not in "#;" and "=" in bare:
            key = bare.split("=", 1)[0].strip()
            if key.lower() in values:
                line = "%s = %s" % (key, values[key.lower()])
                done.add(key.lower())
        out.append(line)
        if current == section.lower() and bare:
            end = len(out)
    missing = ["%s = %s" % (key, value) for key, value in values.items() if key not in done]
    if missing and found:
        out[end:end] = missing
    elif missing:
        out += ["", "[%s]" % section] + missing
    write_text(path, "\n".join(out))


def head_comment(path: str) -> str:
    """The comment block at the top of a file: everything before its first section or first line of content."""
    head: list[str] = []
    for line in read_text(path).splitlines():
        bare = line.strip()
        if bare and not bare.startswith("#"):
            break
        head.append(line)
    while head and not head[-1].strip():
        head.pop()
    return "\n".join(head)


def write_sections(path: str, sections: list[tuple[str, dict[str, object]]]) -> None:
    """The file's top comment, then each (name, values) as a section. Long values carry on over indented lines."""
    parts = [head_comment(path)] if head_comment(path) else []
    for name, values in sections:
        lines = ["[%s]" % name]
        for key, value in values.items():
            wrapped = textwrap.wrap(str(value), 104, break_long_words=False) or [""]
            lines.append("%s = %s" % (key, "\n    ".join(wrapped)))
        parts.append("\n".join(lines))
    write_text(path, "\n\n".join(parts))


def read_rules(path: str) -> list[tuple[str, str]]:
    """Lines of the form `rule | text`."""
    rules = []
    for line in read_text(path).splitlines():
        bare = line.strip()
        if bare and not bare.startswith("#"):
            rule, _, text = bare.partition("|")
            rules.append((rule.strip(), text.strip()))
    return rules


def write_rules(path: str, rules: list[tuple[str, str]]) -> None:
    """Every comment the file has, in order, then the rules."""
    comments = [line for line in read_text(path).splitlines() if not line.strip() or line.strip().startswith("#")]
    while comments and not comments[-1].strip():
        comments.pop()
    write_text(path, "\n".join(comments + ([""] if comments else []) + ["%s | %s" % rule for rule in rules]))
