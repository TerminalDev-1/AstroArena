"""The AstroArena game server: a small JSON-over-HTTP API in front of a SQLite database.

Only the Python standard library is used, so there is nothing to install.

    GET  /v1/health                     is anything there?
    GET  /v1/status?version=6           is this client version supported, and is there a notice for it?
    GET  /v1/config                     live settings: bot behaviour per difficulty
    POST /v1/players        {name}      create an account -> {id, token}
    GET  /v1/save                       the stored save                         (needs a token)
    PUT  /v1/save           {save}      store the save -> {revision}            (needs a token)
    POST /v1/matches        {...}       plan a match -> {matchId, seed, bots}   (needs a token)
    POST /v1/matches/<id>/result {...}  report how it went                      (needs a token)
    GET  /v1/leaderboard?limit=50       players by Cups

A token goes in the `Authorization: Bearer <token>` header.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .config import Config
from .store import Store

API = 1
MAX_BODY = 512 * 1024
_MATCH_RESULT = re.compile(r"^/v1/matches/(\d+)/result$")
_MODES = {"LAST_SPARK": 9, "KNOCKOUT_RUSH": 5, "BOSS": 0, "TRAINING": 0}


class Game:
    """What the HTTP layer talks to: the config files plus the database."""

    def __init__(self, directory: str, db_path: str | None = None):
        self.config = Config(directory)
        self.store = Store(db_path or os.path.join(directory, "astroarena.db"))
        self.started = time.time()


def make_handler(game: Game, quiet: bool = False):
    class Handler(BaseHTTPRequestHandler):
        server_version = "AstroArena/1"
        protocol_version = "HTTP/1.1"

        # ------------------------------------------------------------ plumbing

        def log_message(self, fmt, *args):  # noqa: N802 (name fixed by the base class)
            if not quiet:
                sys.stderr.write("%s  %s  %s\n" % (time.strftime("%H:%M:%S"), self.address_string(), fmt % args))

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _error(self, status: int, message: str) -> None:
            self._send(status, {"error": message})

        def _body(self) -> dict | None:
            """The request's JSON object, or None after an error reply has been sent."""
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                length = -1
            if length < 0 or length > MAX_BODY:
                self._error(413, "request too large")
                return None
            try:
                data = json.loads(self.rfile.read(length) or b"{}")
            except (ValueError, UnicodeDecodeError):
                self._error(400, "body is not valid JSON")
                return None
            if not isinstance(data, dict):
                self._error(400, "body must be a JSON object")
                return None
            return data

        def _player(self):
            """The signed-in player, or None after a 401 has been sent."""
            header = self.headers.get("Authorization") or ""
            token = header[7:].strip() if header.startswith("Bearer ") else ""
            player = game.store.player_for(token, self.headers.get("X-Client-Version") or "")
            if player is None:
                self._error(401, "unknown or missing token")
            return player

        # ------------------------------------------------------------ routes

        def do_GET(self):  # noqa: N802
            url = urlparse(self.path)
            query = parse_qs(url.query)
            if url.path == "/v1/health":
                return self._send(200, {"ok": True, "server": "astroarena", "api": API, **game.store.stats()})
            if url.path == "/v1/status":
                version = (query.get("version") or [""])[0]
                message = game.config.unsupported_message(version)
                return self._send(200, {
                    "supported": message is None,
                    "message": message or "",
                    "notice": game.config.notice(version),
                })
            if url.path == "/v1/config":
                return self._send(200, {"bots": game.config.bots()})
            if url.path == "/v1/leaderboard":
                try:
                    limit = int((query.get("limit") or ["50"])[0])
                except ValueError:
                    limit = 50
                return self._send(200, {"players": game.store.leaderboard(limit)})
            if url.path == "/v1/save":
                player = self._player()
                if player is None:
                    return None
                saved = game.store.get_save(player["id"])
                return self._send(200, saved) if saved else self._error(404, "no save stored yet")
            return self._error(404, "no such endpoint")

        def do_POST(self):  # noqa: N802
            url = urlparse(self.path)
            if url.path == "/v1/players":
                data = self._body()
                if data is None:
                    return None
                return self._send(201, game.store.register(data.get("name"), str(data.get("version") or "")))
            if url.path == "/v1/matches":
                player = self._player()
                if player is None:
                    return None
                data = self._body()
                if data is None:
                    return None
                mode = str(data.get("mode") or "")
                if mode not in _MODES:
                    return self._error(400, "unknown mode")
                try:
                    level = int(data.get("level") or 1)
                except (TypeError, ValueError):
                    return self._error(400, "level must be a number")
                plan = game.store.plan_match(
                    player["id"], mode, str(data.get("fighter") or "JUNO"), level, str(data.get("difficulty") or "NORMAL"), _MODES[mode]
                )
                return self._send(201, plan)
            m = _MATCH_RESULT.match(url.path)
            if m:
                player = self._player()
                if player is None:
                    return None
                data = self._body()
                if data is None:
                    return None
                try:
                    ok = game.store.finish_match(player["id"], int(m.group(1)), data)
                except (TypeError, ValueError):
                    return self._error(400, "result fields must be numbers")
                return self._send(200, {"ok": True}) if ok else self._error(409, "no open match with that id")
            return self._error(404, "no such endpoint")

        def do_PUT(self):  # noqa: N802
            if urlparse(self.path).path != "/v1/save":
                return self._error(404, "no such endpoint")
            player = self._player()
            if player is None:
                return None
            data = self._body()
            if data is None:
                return None
            save = data.get("save")
            if not isinstance(save, dict):
                return self._error(400, "save must be a JSON object")
            try:
                revision = game.store.put_save(player["id"], save)
            except (TypeError, ValueError):
                return self._error(400, "save has fields of the wrong type")
            return self._send(200, {"revision": revision})

    return Handler


def serve(directory: str, host: str = "0.0.0.0", port: int = 8765, db_path: str | None = None, quiet: bool = False) -> ThreadingHTTPServer:
    """Builds the server (call .serve_forever() on the result)."""
    game = Game(directory, db_path)
    httpd = ThreadingHTTPServer((host, port), make_handler(game, quiet))
    httpd.daemon_threads = True
    httpd.game = game  # type: ignore[attr-defined]
    return httpd
