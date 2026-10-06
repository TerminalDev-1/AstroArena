"""The 1v1 lobby: pairs two players and passes each one's inputs to the other.

A 1v1 is played on both devices at once. The simulation is deterministic, so two devices that start from the same
seed and feed it the same inputs on the same ticks stay in step: neither sends the other its state, only what its
player did. This module is the meeting point. It listens on its own TCP port (the game server's port + 1), and:

  * a device connects and says who it is:       'H' + text {token, version, fighter, skin}
  * when two are waiting, each is told to start:  'S' + text {seed, side, level, opponent: {name, fighter, level, skin}}
  * from then on every input frame one sends ('I' + 17 bytes) is passed to the other, untouched (and so is 'C' + 8)
  * when one leaves, the other is told:           'X'   (the other left: you win)
  * a player whose inputs stopped is told:        'L'   (you were dropped: you lose), and the other gets 'X'
  * if both stopped at the same moment:           'D'   (called off: a draw)
  * a device whose match has ended says so:       'F'
  * and each is told what the match was worth:    'V' + text (the verdict, as for any match; {} if nothing)
  * while waiting, a note may be sent:            'N' + text
  * a device that can't play is told why:         'E' + text

"text" is a 2-byte big-endian length followed by that many bytes of UTF-8 (what Java's writeUTF sends; the JSON is
kept to ASCII so the two agree). Who the player is, which fighter they may bring and what level it is are the
server's to say, as everywhere else: the device's own word for its level is not asked for.

A 1v1 is played for Cups (trophies.cfg, [DUEL]), and who won is the server's to say. The lobby keeps every input
frame it passes on, so when a match ends it has both players' whole matches: it replays them through the referee
(the game's own simulation, as for matches against bots) and pays each player by what the replay shows. Neither
device is asked how it went. A match that stops before the replay reaches its end is lost by whoever stopped it:
the player who left, went quiet, or whose device disagreed with the replay about the match. Without a referee
(no Java on the server) nothing can say who won, so a 1v1 is still played but pays nothing.

A player who joins waits for another real player, for as long as that takes. There is no stand-in opponent.

What happened when a match stops moving is the lobby's call, not the devices': each of them only knows that the
other's inputs stopped arriving, which looks the same whether the other player left, their device was put down, or
its own Wi-Fi dropped. The lobby sees both. A match is in step, so when one player's inputs stop the other's stop a
few ticks later: whoever has sent fewer frames is the one who stopped. After `stall_seconds` of silence that player
is dropped and loses, and the other wins. Before either has sent anything (both are still on the line-up screen)
they get `start_seconds` instead.

Every so often each device also sends a check ('C' + 8 bytes: a tick and a number worked out from where everything
is on that tick). It is passed on like an input; the devices compare, and stop if they disagree. The lobby compares
them too, and when they differ the replay says which device had it wrong: that one loses.
"""

from __future__ import annotations

import json
import random
import socket
import socketserver
import struct
import threading
import time

from . import rules
from .economy import Refused

FRAME_BYTES = 17  # flags (1) + moveX, moveY, aimX, aimY (4 floats)
CHECK_BYTES = 8   # tick (int) + the number the device worked out for that tick (int)
MAX_FRAMES = 60 * 60 * 20  # as much of a match as is kept for the replay (20 minutes; a 1v1 lasts two)


class _Seat:
    """One connected player."""

    def __init__(self, sock: socket.socket, name: str, fighter: str, level: int, skin: int, version: str = "", player_id: str = ""):
        self.sock = sock
        self.version = version
        self.player_id = player_id
        self.frames = 0          # input frames received from this player
        self.heard = 0.0         # when the last one arrived (or when the match was made)
        self.record = bytearray()          # those frames, kept for the replay
        self.checks: dict[int, int] = {}   # tick -> the number this device worked out, until the other's arrives
        self.side = 0
        self.seed = 0
        self.match_id = 0        # this player's row for the match (0: nothing is at stake)
        self.name, self.fighter, self.level, self.skin = name, fighter, level, skin
        self.peer: _Seat | None = None
        self._write = threading.Lock()

    def send(self, data: bytes) -> bool:
        with self._write:
            try:
                self.sock.sendall(data)
                return True
            except OSError:
                return False

    def describe(self) -> dict:
        return {"name": self.name, "fighter": self.fighter, "level": self.level, "skin": self.skin}


def _text(kind: bytes, value: dict | str) -> bytes:
    body = (value if isinstance(value, str) else json.dumps(value, ensure_ascii=True)).encode("ascii", "replace")
    return kind + struct.pack(">H", len(body)) + body


def _read(sock: socket.socket, count: int) -> bytes | None:
    data = b""
    while len(data) < count:
        try:
            chunk = sock.recv(count - len(data))
        except OSError:
            return None
        if not chunk:
            return None
        data += chunk
    return data


class DuelLobby(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, game, host: str, port: int, quiet: bool = False):
        self.game = game
        self.quiet = quiet
        self._lock = threading.Lock()
        # Who is waiting, by the build they are playing: two builds may not compute a match the same way, so a
        # player is only ever paired with someone on the same one.
        self._waiting: dict[str, _Seat] = {}
        self._random = random.SystemRandom()
        self._pairs: list[tuple[_Seat, _Seat]] = []
        self.stall_seconds = 8.0
        self.start_seconds = 45.0
        self._closing = False
        super().__init__((host, port), _Handler)

    def start(self) -> "DuelLobby":
        threading.Thread(target=self.serve_forever, name="duel-lobby", daemon=True).start()
        threading.Thread(target=self._watch_forever, name="duel-watch", daemon=True).start()
        return self

    def server_close(self) -> None:
        self._closing = True
        super().server_close()

    def _watch_forever(self) -> None:
        while not self._closing:
            time.sleep(0.25)
            self.watch()

    def watch(self, now: float | None = None) -> None:
        """Ends any match that has stopped moving, and says who stopped."""
        now = time.monotonic() if now is None else now
        ended = []
        with self._lock:
            for pair in list(self._pairs):
                a, b = pair
                if a.peer is not b or b.peer is not a:
                    self._pairs.remove(pair)  # one of them has left; that is dealt with in leave()
                    continue
                limit = self.stall_seconds if (a.frames and b.frames) else self.start_seconds
                if now - max(a.heard, b.heard) < limit:
                    continue
                self._pairs.remove(pair)
                a.peer = b.peer = None
                ended.append(pair)
        for a, b in ended:
            if a.frames == b.frames:
                self.log(f"{a.name} v {b.name}: both went quiet together, called off")
                a.send(b"D"); b.send(b"D")
            else:
                gone, there = (a, b) if a.frames < b.frames else (b, a)
                self.log(f"{gone.name} stopped responding: {there.name} wins")
                gone.send(b"L"); there.send(b"X")
                self.settle(gone, there, loser=gone)
            self._hang_up(a, b)

    @staticmethod
    def _hang_up(*seats: _Seat) -> None:
        for seat in seats:
            try:
                seat.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def _part(self, me: _Seat) -> _Seat | None:
        """Ends the match [me] is in, if it is still going: whoever gets here first settles it. Returns the other player."""
        with self._lock:
            peer, me.peer = me.peer, None
            if peer is not None:
                peer.peer = None
        return peer

    def settle(self, a: _Seat, b: _Seat, loser: _Seat | None = None, check: tuple[int, int, int] | None = None) -> bool:
        """Works out how the match between [a] and [b] went, pays both, and tells whoever is still there ('V').

        The replay decides. If it doesn't reach the end of the match, [loser] (the player who stopped it) loses;
        failing that, with `check` (a tick the devices disagreed on: tick, side 0's number, side 1's), the one the
        replay says had it wrong. False if nothing could be decided: the match is called off and pays nothing.
        """
        first, second = (a, b) if a.side == 0 else (b, a)
        referee = getattr(self.game, "referee", None)
        winner, judged = None, None
        if referee is not None and first.match_id and second.match_id:
            frames = [bytes(seat.record[:len(seat.record) // FRAME_BYTES * FRAME_BYTES]) for seat in (first, second)]
            try:
                judged = referee.judge_duel(first.seed, [first.fighter, second.fighter], [first.level, second.level], frames, check)
            except Refused as refused:
                self.log(f"{a.name} v {b.name}: {refused.message}")
        if self._closing:
            return False
        if judged is not None:
            if judged["finished"]:
                winner = judged["winner"]
            elif loser is not None:
                winner = 1 - loser.side
            elif judged["wrong"][0] != judged["wrong"][1]:
                winner = 1 if judged["wrong"][0] else 0
        if winner is None:
            for seat in (a, b):
                seat.send(_text(b"V", {}))
            return False
        for seat in (first, second):
            stats = judged["sides"][seat.side]
            outcome = "DRAW" if winner < 0 else "VICTORY" if winner == seat.side else "DEFEAT"
            report = {"outcome": outcome, "placement": 0, "kos": stats["kos"], "deaths": stats["deaths"], "damage": stats["damage"], "mvp": False}
            verdict = self.game.store.finish_match(seat.player_id, seat.match_id, {**report, "ticks": judged["ticks"]}, verified=True)
            if not verdict or "rejected" in verdict:
                seat.send(_text(b"V", {}))
                continue
            account = self.game.account(seat.player_id)
            seat.send(_text(b"V", {**verdict, "report": report, "account": {k: account[k] for k in ("drops", "dropsLeftToday")}}))
            self.log(f"{seat.name}: {outcome}, {verdict['cupDelta']:+d} Cups" + ("" if judged["finished"] else " (the match was not played out)"))
        return True

    def finished(self, me: _Seat) -> None:
        """[me]'s device says the match is over. The replay will show whether it is; if not, they have walked out."""
        peer = self._part(me)
        if peer is not None:
            self.settle(me, peer, loser=me)

    def checked(self, me: _Seat, tick: int, value: int) -> None:
        """A device's number for [tick]. If the other's is in and differs, the match can't go on: the replay says who had it wrong."""
        with self._lock:
            peer = me.peer
            if peer is None:
                return
            theirs = peer.checks.pop(tick, None)
            if theirs is None:
                if len(me.checks) < 64:
                    me.checks[tick] = value
                return
            if theirs == value:
                return
            me.peer = peer.peer = None
        self.log(f"{me.name} v {peer.name}: their devices disagree about the match at tick {tick}")
        numbers = (value, theirs) if me.side == 0 else (theirs, value)
        if not self.settle(me, peer, check=(tick, *numbers)):
            me.send(b"D"); peer.send(b"D")
        self._hang_up(me, peer)

    def log(self, message: str) -> None:
        if not self.quiet:
            print("  1v1: " + message)

    def seat(self, hello: dict) -> _Seat | str:
        """Who is asking, and with which fighter; or why they can't play."""
        store = self.game.store
        player = store.player_for(str(hello.get("token") or ""), str(hello.get("version") or ""))
        if player is None:
            return "Sign in to the server first."
        store.lift_expired()
        if store.player(player["id"])["disabled"]:
            return "This account has been disabled."
        fighter = str(hello.get("fighter") or "")
        entry = store.profile(player["id"])["fighters"].get(fighter)
        if fighter not in rules.FIGHTER_SKINS or not entry or not entry.get("unlocked"):
            return "That fighter isn't unlocked."
        try:
            skin = int(hello.get("skin") or 0)
        except (TypeError, ValueError):
            skin = 0
        if skin not in entry.get("ownedSkins", [0]):
            skin = 0
        return _Seat(None, player["name"], fighter, int(entry["level"]), skin, str(hello.get("version") or "").strip(), player["id"])  # type: ignore[arg-type]

    def join(self, me: _Seat) -> None:
        """Pairs [me] with whoever is waiting, or leaves them waiting."""
        with self._lock:
            other = self._waiting.get(me.version)
            if other is not None and other.player_id == me.player_id:
                # The same account on a second device: it takes over the wait, it doesn't play itself.
                other.send(_text(b"E", "This account started waiting for a 1v1 on another device."))
                try:
                    other.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                other = None
            if other is None:
                self._waiting[me.version] = me
                self.log(f"{me.name} ({me.fighter}, build {me.version or '?'}) is waiting for an opponent")
                # Someone waiting on another build can't be this player's opponent, and both should know why.
                others = [seat for version, seat in self._waiting.items() if version != me.version]
                if others:
                    note = "Another player is waiting, but on a different build of the game. Update both to the newest one."
                    for seat in others + [me]:
                        seat.send(_text(b"N", note))
                return
            del self._waiting[me.version]
            other.peer, me.peer = me, other
            other.heard = me.heard = time.monotonic()
            self._pairs.append((other, me))
            seed = self._random.getrandbits(62)
            for side, seat in enumerate((other, me)):
                seat.side, seat.seed = side, seed
                # Each player's side of the match is kept like any other match, to be closed when it is settled.
                try:
                    seat.match_id = self.game.store.plan_match(seat.player_id, "DUEL", seat.fighter, "", 0, seed=seed)["matchId"]
                except Refused:
                    seat.match_id = 0
        self.log(f"{other.name} ({other.fighter}) v {me.name} ({me.fighter})")
        for side, (seat, opponent) in enumerate(((other, me), (me, other))):
            seat.send(_text(b"S", {"seed": seed, "side": side, "level": seat.level, "opponent": opponent.describe()}))

    def leave(self, me: _Seat) -> None:
        with self._lock:
            if self._waiting.get(me.version) is me:
                del self._waiting[me.version]
            peer, me.peer = me.peer, None
            if peer is not None:
                peer.peer = None
        if peer is not None:
            peer.send(b"X")
            self.log(f"{me.name} left the match with {peer.name}")
            self.settle(me, peer, loser=me)


class _Handler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        lobby: DuelLobby = self.server  # type: ignore[assignment]
        sock: socket.socket = self.request
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(10)
        head = _read(sock, 3)
        if head is None or head[:1] != b"H":
            return
        body = _read(sock, struct.unpack(">H", head[1:])[0])
        try:
            hello = json.loads(body or b"")
        except ValueError:
            return
        me = lobby.seat(hello if isinstance(hello, dict) else {})
        if isinstance(me, str):
            sock.sendall(_text(b"E", me))
            return
        me.sock = sock
        sock.settimeout(None)
        lobby.join(me)
        try:
            # Everything this device sends from here on is an input frame for its opponent. (While it waits for
            # one it sends nothing, so this read is also how a cancelled search is noticed.)
            while True:
                kind = _read(sock, 1)
                if kind == b"F":
                    lobby.finished(me)
                    continue
                if kind not in (b"I", b"C"):
                    break
                frame = _read(sock, FRAME_BYTES if kind == b"I" else CHECK_BYTES)
                if frame is None:
                    break
                if kind == b"I":
                    me.frames += 1
                    me.heard = time.monotonic()
                    if me.peer is not None and me.frames <= MAX_FRAMES:
                        me.record += frame
                peer = me.peer
                if peer is not None:
                    peer.send(kind + frame)
                if kind == b"C":
                    lobby.checked(me, *struct.unpack(">ii", frame))
        finally:
            lobby.leave(me)
