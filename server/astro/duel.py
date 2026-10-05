"""The 1v1 lobby: pairs two players and passes each one's inputs to the other.

A 1v1 is played on both devices at once. The simulation is deterministic, so two devices that start from the same
seed and feed it the same inputs on the same ticks stay in step: neither sends the other its state, only what its
player did. This module is the meeting point. It listens on its own TCP port (the game server's port + 1), and:

  * a device connects and says who it is:       'H' + text {token, version, fighter, skin}
  * when two are waiting, each is told to start:  'S' + text {seed, side, level, opponent: {name, fighter, level, skin}}
  * from then on every input frame one sends ('I' + 17 bytes) is passed to the other, untouched
  * when one leaves, the other is told:           'X'
  * a device that can't play is told why:         'E' + text

"text" is a 2-byte big-endian length followed by that many bytes of UTF-8 (what Java's writeUTF sends; the JSON is
kept to ASCII so the two agree). Who the player is, which fighter they may bring and what level it is are the
server's to say, as everywhere else: the device's own word for its level is not asked for.

Nothing is earned in a 1v1 yet. This is the first mode against real players and it is here to be tested, on a home
network; the server doesn't referee these matches, and a device that sent false inputs would only put the two out
of step.

For testing with one device there is a sparring partner (`python run.py --sparring`): a player who has waited five
seconds without anyone joining is paired with a dummy that stands still, so the whole path can be played alone.
"""

from __future__ import annotations

import json
import random
import socket
import socketserver
import struct
import threading

from . import rules

FRAME_BYTES = 17  # flags (1) + moveX, moveY, aimX, aimY (4 floats)


class _Seat:
    """One connected player."""

    def __init__(self, sock: socket.socket, name: str, fighter: str, level: int, skin: int):
        self.sock = sock
        self.name, self.fighter, self.level, self.skin = name, fighter, level, skin
        self.peer: _Seat | None = None
        self.sparring = False  # paired with the lobby's own stand-still dummy
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

    def __init__(self, game, host: str, port: int, quiet: bool = False, sparring: bool = False):
        self.game = game
        self.quiet = quiet
        self.sparring = sparring
        self._lock = threading.Lock()
        self._waiting: _Seat | None = None
        self._random = random.SystemRandom()
        super().__init__((host, port), _Handler)

    def start(self) -> "DuelLobby":
        threading.Thread(target=self.serve_forever, name="duel-lobby", daemon=True).start()
        return self

    def log(self, message: str) -> None:
        if not self.quiet:
            print("  1v1: " + message)

    def seat(self, hello: dict) -> _Seat | str:
        """Who is asking, and with which fighter; or why they can't play."""
        store = self.game.store
        player = store.player_for(str(hello.get("token") or ""), str(hello.get("version") or ""))
        if player is None:
            return "Sign in to the server first."
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
        return _Seat(None, player["name"], fighter, int(entry["level"]), skin)  # type: ignore[arg-type]

    def join(self, me: _Seat) -> None:
        """Pairs [me] with whoever is waiting, or leaves them waiting."""
        with self._lock:
            other = self._waiting
            if other is None:
                self._waiting = me
                self.log(f"{me.name} ({me.fighter}) is waiting for an opponent")
                if self.sparring:
                    threading.Timer(5.0, self.spar, [me]).start()
                return
            self._waiting = None
            other.peer, me.peer = me, other
        seed = self._random.getrandbits(62)
        self.log(f"{other.name} ({other.fighter}) v {me.name} ({me.fighter})")
        for side, (seat, opponent) in enumerate(((other, me), (me, other))):
            seat.send(_text(b"S", {"seed": seed, "side": side, "level": seat.level, "opponent": opponent.describe()}))

    def spar(self, me: _Seat) -> None:
        """Nobody came: [me] gets the stand-still dummy. Every frame they send is answered with an idle one."""
        with self._lock:
            if self._waiting is not me:
                return
            self._waiting = None
            me.sparring = True
        self.log(f"{me.name} ({me.fighter}) v the sparring dummy")
        dummy = {"name": "Sparring Dummy", "fighter": rules.STARTING_FIGHTER, "level": 1, "skin": 0}
        me.send(_text(b"S", {"seed": self._random.getrandbits(62), "side": 0, "level": me.level, "opponent": dummy}))

    def leave(self, me: _Seat) -> None:
        with self._lock:
            if self._waiting is me:
                self._waiting = None
            peer, me.peer = me.peer, None
            if peer is not None:
                peer.peer = None
        if peer is not None:
            peer.send(b"X")
            self.log(f"{me.name} left the match with {peer.name}")


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
                if kind != b"I":
                    break
                frame = _read(sock, FRAME_BYTES)
                if frame is None:
                    break
                peer = me.peer
                if peer is not None:
                    peer.send(b"I" + frame)
                elif me.sparring:
                    me.send(b"I" + bytes(FRAME_BYTES))
        finally:
            lobby.leave(me)
