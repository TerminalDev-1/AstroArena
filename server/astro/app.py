"""The AstroArena game server: a small JSON-over-HTTP API in front of a SQLite database.

Only the Python standard library is used, so there is nothing to install.

    GET  /v1/health                     is anything there?
    GET  /v1/status?version=7           is this client version supported, and is there a notice for it?
    GET  /v1/config                     live settings: bot behaviour per difficulty
    POST /v1/players        {name}      create an account -> {id, token}
    GET  /v1/me                         this account as the server sees it                    (token)
    GET  /v1/save                       the stored save                                       (token)
    PUT  /v1/save           {save}      store the save -> {revision, account}                 (token)
    POST /v1/matches        {...}       plan a match -> {matchId, seed, botNames, difficulty} (token)
    POST /v1/matches/<id>/result {inputs}  hand in the match's inputs; the server replays it and
                                        answers with the result and what it earned            (token)
    POST /v1/drops/open     {...}       open an Arena Box -> {items: [{tier, reward}], account}  (token)
    POST /v1/drops/open-all {...}       open every Arena Box held -> {results: [{items: [{tier, reward}]}], account}  (token)
    POST /v1/fighters/upgrade {fighter} level a fighter up with Bolts                         (token)
    POST /v1/shop/buy       {item}      buy a standing shop item with Prisms -> {reward}      (token)
    POST /v1/shop/gift                  claim the daily gift -> {reward}                      (token)
    POST /v1/shop/deals/<id>/buy        buy a deal -> {reward}                                (token)
    POST /v1/track/claim    {cups}      claim a Cup Track reward -> {reward}                  (token)
    POST /v1/shop/daily/<n>/buy {day}   buy one of today's offers -> {reward}                 (token)
    POST /v1/settings/difficulty {difficulty}  choose the bot difficulty; the server says yes or no  (token)
    POST /v1/reset                      start this account's progress over                    (token, developer)
    POST /v1/dev/grant      {...}       developer hand-outs                                   (token, developer)
    POST /v1/dev/deals      {...}       put a deal in everyone's shop -> {id}                 (token, developer)
    POST /v1/dev/deals/<id>/delete      take a deal out of the shop                           (token, developer)
    GET  /v1/leaderboard?limit=50       players by Cups
    GET  /v1/news                       the News tab's items (news.cfg)

A token goes in the `Authorization: Bearer <token>` header, and every request with a token must also say which
version of the game is asking (`X-Client-Version`); versions listed in versions_not_supported.cfg are refused.

The server owns each player's Cups, Arena Boxes, Bolts, Prisms, fighters and claimed rewards: it works out what a
match is worth, rolls what comes out of a drop, and is the only place anything is bought, upgraded or claimed.
Matches are played on the device and then replayed here from the player's inputs (referee.py): the result is
the server's own. Every reply to a signed-in request carries the `account`, which is what the game shows.
"""

from __future__ import annotations

import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import economy, rules
from .accounts import Accounts
from .config import Config, parse_version
from .economy import Refused
from .referee import Referee, TICKS_PER_SECOND, count_ticks, decode_inputs
from .store import Store, clock, today

API = 2
MAX_BODY = 2 * 1024 * 1024  # a match's input log rides along with its result
_MATCH_RESULT = re.compile(r"^/v1/matches/(\d+)/result$")
_DEAL_BUY = re.compile(r"^/v1/shop/deals/(\d+)/buy$")
_DEAL_DELETE = re.compile(r"^/v1/dev/deals/(\d+)/delete$")
_DAILY_BUY = re.compile(r"^/v1/shop/daily/(\d+)/buy$")


DISABLED = "This account has been disabled by the server's owner."


class Game:
    """What the HTTP layer talks to: the config files plus the database."""

    def __init__(self, directory: str, db_path: str | None = None, referee: Referee | None = None):
        self.config = Config(directory)
        # The server's own run of each match. Without one (no Java, no jar) results are only checked for being believable.
        self.referee = referee if referee is not None and referee.available else None
        self.store = Store(db_path or os.path.join(directory, "astroarena.db"))
        self.store.import_progress = self.config.import_saves
        self.store.cups = self.config.cups
        # accounts.cfg, the operator's view of every account. Whoever starts the server starts it running (run.py).
        self.accounts = Accounts(self.store, directory)
        self.started = time.time()
        self._signups: dict[str, list[float]] = {}
        self._signup_lock = threading.Lock()

    def may_sign_up(self, address: str) -> bool:
        """Limits how many accounts one address can create per hour."""
        now = time.time()
        with self._signup_lock:
            recent = [t for t in self._signups.get(address, []) if now - t < 3600]
            allowed = len(recent) < self.config.accounts_per_hour()
            if allowed:
                recent.append(now)
            self._signups[address] = recent
        return allowed

    def difficulty(self, player) -> str:
        """The bot difficulty this player plays at: the one they picked if it is still allowed, else the default."""
        picked = player["difficulty"]
        if picked in rules.DIFFICULTIES and (picked in self.config.allowed_difficulties() or self.config.is_developer(player["id"])):
            return picked
        return self.config.default_difficulty()

    def daily_offers(self) -> list[dict]:
        pool, count = self.config.daily_pool()
        return economy.daily_offers(pool, count, today())

    def account(self, player_id: str) -> dict:
        """A player as the server sees them: what the client shows and is allowed to do."""
        player = self.store.player(player_id)
        rank, players = self.store.rank(player_id)
        profile = self.store.profile(player_id)
        developer = self.config.is_developer(player["id"])
        time_now = clock()
        return {
            "id": player["id"],
            "name": player["name"],
            "developer": developer,
            "cups": player["cups"],
            "rank": rank,
            "players": players,
            "drops": player["drops"],
            "dropsLeftToday": self.store.drops_left_today(player),
            "difficulty": self.difficulty(player),
            "difficulties": list(rules.DIFFICULTIES) if developer else self.config.allowed_difficulties(),
            "profile": profile,
            # The Spark Road: fighters in order, and the Credits each takes.
            "road": {
                "steps": [{"fighter": name, "cost": cost, "rarity": economy.FIGHTER_RARITY[name]} for name, cost in economy.SPARK_ROAD],
                "target": (economy.road_next(profile) or ("", 0))[0],
            },
            "deals": self.store.deals(player_id),
            # The day's offers and the clock they run on. Times are the server's: the game counts down from these.
            "time": time_now,
            "giftAvailable": profile.get("lastDailyGiftDay") != time_now["day"],
            "dailyOffers": [
                {"id": i, **offer, "expiresAt": time_now["dayEndsAt"], "purchased": 1 if economy.bought_today(profile, offer["title"], time_now["day"]) else 0}
                for i, offer in enumerate(self.daily_offers())
            ],
        }


def make_handler(game: Game, quiet: bool = False):
    class Handler(BaseHTTPRequestHandler):
        server_version = "AstroArena/2"
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
            """The signed-in player, or None after an error reply has been sent."""
            header = self.headers.get("Authorization") or ""
            token = header[7:].strip() if header.startswith("Bearer ") else ""
            version = (self.headers.get("X-Client-Version") or "").strip()
            player = game.store.player_for(token, version)
            if player is None:
                self._error(401, "unknown or missing token")
                return None
            # An account the operator has disabled (accounts.cfg) is told so, and gets nothing else.
            if player["disabled"]:
                game.store.lift_expired()
                player = game.store.player(player["id"])
            if player["disabled"]:
                # `until` is 0 when only the operator ends it; `now` lets the game count down on its own clock.
                self._send(403, {
                    "error": DISABLED, "disabled": True, "reason": player["disabled_reason"],
                    "until": int(player["disabled_until"] * 1000), "now": int(time.time() * 1000),
                })
                return None
            # The version gate is enforced here too, not just advertised by /v1/status.
            if parse_version(version) is None:
                self._error(426, "this request doesn't say which version of the game sent it")
                return None
            message = game.config.unsupported_message(version)
            if message is not None:
                self._error(426, message)
                return None
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
            if url.path == "/v1/news":
                return self._send(200, {"news": game.config.news()})
            if url.path == "/v1/leaderboard":
                try:
                    limit = int((query.get("limit") or ["50"])[0])
                except ValueError:
                    limit = 50
                return self._send(200, {"players": game.store.leaderboard(limit)})
            if url.path == "/v1/me":
                player = self._player()
                return None if player is None else self._send(200, {"account": game.account(player["id"])})
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
                if not game.may_sign_up(self.client_address[0]):
                    return self._error(429, "too many new accounts from this address; try again later")
                return self._send(201, game.store.register(data.get("name"), str(data.get("version") or "")))
            if url.path == "/v1/matches":
                return self._plan_match()
            m = _MATCH_RESULT.match(url.path)
            if m:
                return self._finish_match(int(m.group(1)))
            if url.path == "/v1/drops/open":
                return self._open_drop()
            if url.path == "/v1/drops/open-all":
                return self._act(self._open_all_drops)
            if url.path == "/v1/dev/grant":
                return self._act(lambda p, d: game.store.grant(
                    p["id"], int(d.get("cups") or 0), int(d.get("drops") or 0), int(d.get("bolts") or 0), int(d.get("prisms") or 0),
                    int(d.get("credits") or 0)
                ), developer=True)
            if url.path == "/v1/fighters/upgrade":
                return self._act(self._upgrade)
            if url.path == "/v1/shop/buy":
                return self._act(lambda p, d: {"reward": game.store.buy(p["id"], str(d.get("item") or ""))})
            if url.path == "/v1/shop/gift":
                return self._act(lambda p, d: {"reward": game.store.claim_gift(p["id"])})
            if url.path == "/v1/track/claim":
                return self._act(lambda p, d: {"reward": game.store.claim_milestone(p["id"], int(d.get("cups") or 0))})
            if url.path == "/v1/reset":
                return self._act(lambda p, d: game.store.reset(p["id"]), developer=True)
            if url.path == "/v1/settings/difficulty":
                return self._act(self._set_difficulty)
            m = _DAILY_BUY.match(url.path)
            if m:
                index = int(m.group(1))
                return self._act(lambda p, d: {"reward": game.store.buy_daily(p["id"], game.daily_offers(), index, int(d.get("day", -1)))})
            if url.path == "/v1/dev/deals":
                return self._act(lambda p, d: {"id": game.store.create_deal(p["id"], d)}, developer=True)
            m = _DEAL_BUY.match(url.path)
            if m:
                deal_id = int(m.group(1))
                return self._act(lambda p, d: {"reward": game.store.buy_deal(p["id"], deal_id)})
            m = _DEAL_DELETE.match(url.path)
            if m:
                deal_id = int(m.group(1))
                return self._act(lambda p, d: {"deleted": game.store.delete_deal(deal_id)}, developer=True)
            return self._error(404, "no such endpoint")

        def _act(self, action, developer: bool = False):
            """A signed-in request that changes the player's account. `action(player, body)` returns extra reply
            fields (or None) or raises Refused; the reply always carries the account as it now stands."""
            data = self._body()
            if data is None:
                return None
            player = self._player()
            if player is None:
                return None
            if developer and not game.config.is_developer(player["id"]):
                return self._error(403, "developers only")
            try:
                extra = action(player, data) or {}
            except Refused as refused:
                return self._error(refused.status, refused.message)
            except (TypeError, ValueError):
                return self._error(400, "a field has the wrong type")
            return self._send(200, {**extra, "account": game.account(player["id"])})

        def _set_difficulty(self, player, data):
            wanted = str(data.get("difficulty") or "").upper()
            if wanted not in rules.DIFFICULTIES:
                raise Refused(400, "unknown difficulty")
            if wanted not in game.config.allowed_difficulties() and not game.config.is_developer(player["id"]):
                raise Refused(403, "the server doesn't allow that difficulty")
            game.store.set_difficulty(player["id"], wanted)
            return {"ok": True}

        def _open_all_drops(self, player, data):
            # Luck is the debug menu's, so only a developer's counts.
            luck = float(data.get("luck") or 0) if game.config.is_developer(player["id"]) else 0.0
            results = game.store.open_all_drops(player["id"], luck)
            if not results:
                raise Refused(409, "no Arena Boxes to open")
            return {"results": results}

        def _upgrade(self, player, data):
            # The cost slider and the level cap switch in the debug menu are for developers only.
            factor, no_cap = 1.0, False
            if game.config.is_developer(player["id"]):
                factor = float(data["costFactor"]) if "costFactor" in data else 1.0
                no_cap = data.get("noCap") is True
            return {"cost": game.store.upgrade(player["id"], str(data.get("fighter") or ""), factor, no_cap)}

        def _plan_match(self):
            player = self._player()
            if player is None:
                return None
            data = self._body()
            if data is None:
                return None
            mode = str(data.get("mode") or "")
            if mode not in rules.MODES:
                return self._error(400, "unknown mode")
            # The difficulty is the one the player picked earlier and the server approved, not whatever this request says;
            # the fighter's level is the server's; and the bots behave as bots.cfg says right now.
            difficulty = game.difficulty(player)
            # Boss Mode: which boss to fight is the player's to choose. Anything else means "let the seed pick".
            boss = str(data.get("boss") or "").upper()
            boss = boss if mode == "BOSS" and boss in rules.BOSSES else ""
            try:
                plan = game.store.plan_match(
                    player["id"], mode, str(data.get("fighter") or "BYTE"), difficulty, rules.MODES[mode], game.config.bots().get(difficulty, {}), boss
                )
            except Refused as refused:
                return self._error(refused.status, refused.message)
            return self._send(201, {**plan, "refereed": game.referee is not None})

        def _finish_match(self, match_id: int):
            player = self._player()
            if player is None:
                return None
            data = self._body()
            if data is None:
                return None
            match = game.store.open_match(player["id"], match_id)
            # A 1v1 is settled by the lobby, which saw both players' inputs; nothing a device hands in here closes one.
            if match is None or match["mode"] == "DUEL":
                return self._error(409, "no open match with that id")
            result, judged = data, None
            if game.referee is not None and match["mode"] != "TRAINING":
                # The server plays the match again itself. What the device says the result was is not used.
                try:
                    raw = decode_inputs(data.get("inputs"))
                    # Nobody can have played more of a match than the time that has passed since it was set up.
                    if count_ticks(raw) / TICKS_PER_SECOND > time.time() - match["started_at"] + 5:
                        raise Refused(422, "result refused: more match than time")
                    judged = game.referee.judge(
                        match["mode"], match["fighter"], match["level"], match["difficulty"], match["seed"], match["names"], match["bots"], raw,
                        match.get("boss") or "",
                    )
                except Refused as refused:
                    if refused.status != 503:  # 503: the referee itself broke; that isn't the player's doing
                        game.store.refuse_match(player["id"], match_id)
                    return self._error(refused.status, refused.message)
                result = judged
                # For whoever runs the server: did the device's own account of the match agree with the replay?
                claimed = {k: data.get(k) for k in ("outcome", "placement", "kos", "deaths", "damage")}
                agrees = all(judged[k] == v for k, v in claimed.items())
                sys.stderr.write("%s  referee: match %d, %d ticks, %s place %d, %d KOs; the device %s\n" % (
                    time.strftime("%H:%M:%S"), match_id, judged["ticks"], judged["outcome"], judged["placement"], judged["kos"],
                    "agrees" if agrees else "said %s" % claimed))
            try:
                verdict = game.store.finish_match(player["id"], match_id, result, verified=judged is not None)
            except (TypeError, ValueError):
                return self._error(400, "result fields must be numbers")
            if verdict is None:
                return self._error(409, "no open match with that id")
            if "rejected" in verdict:
                return self._error(422, "result refused: " + verdict["rejected"])
            if judged is not None:
                verdict["report"] = {k: judged[k] for k in ("outcome", "placement", "kos", "deaths", "damage", "mvp")}
            return self._send(200, {"ok": True, "verified": judged is not None, **verdict, "account": game.account(player["id"])})

        def _open_drop(self):
            player = self._player()
            if player is None:
                return None
            data = self._body()
            if data is None:
                return None
            luck, free = 0.0, False
            if game.config.is_developer(player["id"]):
                try:
                    luck = float(data.get("luck") or 0)
                except (TypeError, ValueError):
                    return self._error(400, "luck must be a number")
                free = data.get("free") is True
            result = game.store.open_drop(player["id"], luck, free)
            if result is None:
                return self._error(409, "no Arena Boxes to open")
            return self._send(200, {**result, "account": game.account(player["id"])})

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
                revision = game.store.put_save(player["id"], save, game.config.import_saves())
            except (TypeError, ValueError):
                return self._error(400, "save has fields of the wrong type")
            return self._send(200, {"revision": revision, "account": game.account(player["id"])})

    return Handler


def serve(
    directory: str, host: str = "0.0.0.0", port: int = 8765, db_path: str | None = None, quiet: bool = False,
    referee: Referee | None | bool = True,
) -> ThreadingHTTPServer:
    """Builds the server (call .serve_forever() on the result). `referee`: True uses referee/referee.jar next to
    the config files, None or False runs without one, or pass a Referee."""
    if referee is True:
        referee = Referee(os.path.join(directory, "referee", "referee.jar"))
    game = Game(directory, db_path, referee or None)
    httpd = ThreadingHTTPServer((host, port), make_handler(game, quiet))
    httpd.daemon_threads = True
    httpd.game = game  # type: ignore[attr-defined]
    # The 1v1 lobby listens one port above the game server (see duel.py). Without it the game still runs.
    httpd.duel = None  # type: ignore[attr-defined]
    try:
        from .duel import DuelLobby
        httpd.duel = DuelLobby(game, host, httpd.server_address[1] + 1, quiet).start()  # type: ignore[attr-defined]
    except OSError:
        pass
    return httpd
