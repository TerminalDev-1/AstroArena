"""What the manager works on: the server's folder, its database, and the server process itself.

The manager is a second program beside the server, not part of it. It edits the same .cfg files the owner
would edit by hand (the server re-reads them when they change), reaches accounts through the server's own
`Store`, and starts and stops the server as a separate process that keeps running if the manager is closed.
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, "server")
LOGS = os.path.join(SERVER, "logs")
LOG = os.path.join(LOGS, "server.log")
PID = os.path.join(LOGS, "server.pid")
PORT = 8765

sys.path.insert(0, SERVER)
from astro import economy, rules  # noqa: E402  (the server's own tables: fighters, difficulties, limits)
from astro.accounts import when  # noqa: E402
from astro.config import Config  # noqa: E402
from astro.store import Store  # noqa: E402

FIGHTERS = list(rules.FIGHTER_SKINS)
DIFFICULTIES = list(rules.DIFFICULTIES)

_store: Store | None = None


def cfg(name: str) -> str:
    return os.path.join(SERVER, name)


def store() -> Store:
    """The account database, opened once. The server may have it open too: SQLite lets both in."""
    global _store
    if _store is None:
        _store = Store(os.path.join(SERVER, "astroarena.db"))
    return _store


def config() -> Config:
    return Config(SERVER)


def lan_address() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("10.255.255.255", 1))  # nothing is sent; this only picks the outgoing interface
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


# ---------------------------------------------------------------------------- the server process

_HIDDEN = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def health() -> dict | None:
    """What the running server says about itself, or None if nothing answers. Quick, but not instant:
    call it off the window's thread."""
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/v1/health" % PORT, timeout=0.6) as reply:
            return json.loads(reply.read())
    except (OSError, ValueError):
        return None


def server_pid() -> int | None:
    """The process listening on the server's port, whoever started it."""
    try:
        out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True, creationflags=_HIDDEN, timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[1].endswith(":%d" % PORT) and parts[3] == "LISTENING":
            return int(parts[4])
    return None


def started_here() -> bool:
    """Is the running server one the manager started (so its output is in the log file)?"""
    try:
        with open(PID, encoding="utf-8") as f:
            return int(f.read().strip()) == server_pid()
    except (OSError, ValueError):
        return False


def start_server() -> None:
    """Starts the server on its own, writing to the log file. It outlives the manager."""
    os.makedirs(LOGS, exist_ok=True)
    python = sys.executable
    if os.path.basename(python).lower() == "pythonw.exe":  # the server prints; give it the console flavour
        python = os.path.join(os.path.dirname(python), "python.exe")
    flags = _HIDDEN | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    with open(LOG, "w", encoding="utf-8") as log:
        process = subprocess.Popen([python, "-u", "run.py"], cwd=SERVER, stdout=log, stderr=subprocess.STDOUT,
                                   stdin=subprocess.DEVNULL, creationflags=flags)
    with open(PID, "w", encoding="utf-8") as f:
        f.write(str(process.pid))


def stop_server() -> bool:
    pid = server_pid()
    if pid is None:
        return False
    try:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, creationflags=_HIDDEN, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return False
    return True
