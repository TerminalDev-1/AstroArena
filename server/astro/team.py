"""Teams: two or three real players in one match, in Boss Mode or Knockout Rush (3v3).

A team is made by one player, who is given a short code; friends type the code in to join, and the one who made
it presses Play. They then share one match the way a 1v1's two players do (duel.py): every device runs the same
simulation from the same seed, bots included, and only the players' inputs cross the network. In Boss Mode the
team fights the boss together; in Knockout Rush they are one side of the 3v3, with a bot making up the numbers
if there are only two of them, against three bots.

Teams live on the 1v1 lobby's port (the game port + 1): a device that opens with 'T' instead of 'H' is here.

  * a device makes or joins a team:              'T' + text {token, version, fighter, skin, action: create|join,
                                                             mode (create), boss (create), code (join)}
  * everyone in it is told who is in it:         'R' + text {code, mode, boss, you, members: [{name, fighter, level, skin}]}
                                                 (again whenever that changes; member 0 leads)
  * the leader starts the match:                 'G'
  * each is told to start:                       'S' + text {seed, slot, mode, boss, difficulty, botNames, bots, players}
  * an input frame ('I' + 17 bytes) is passed to the others with its sender's slot:  'I' + slot + 17 bytes
  * a check ('C' + tick + number) is kept here and compared with the others'
  * a player who leaves or stops responding:     the others get 'Q' + slot + how many of their frames count (int);
                                                 their fighter stands still from then on. One who was dropped is told 'L'.
  * a device whose match has ended says so:      'F'
  * and each is told what it was worth:          'V' + text (the verdict, as for any match; {} if nothing)
  * a match that can't go on is called off:      'D'
  * a device that can't be in a team is told why: 'E' + text

As with a 1v1, the result is the server's: the lobby keeps every frame, replays the match through the referee and
pays each player by what the replay shows (trophies.cfg, for the mode they played). A player who walked out takes
a defeat whatever the team went on to do. Without a referee a team match is played but pays nothing.

After a match the team is still together, and its leader can start another.
"""

from __future__ import annotations

import random
import socket
import struct
import threading
import time

from . import rules
from .economy import Refused

FRAME_BYTES = 17
CHECK_BYTES = 8
MAX_FRAMES = 60 * 60 * 20
MODES = ("BOSS", "KNOCKOUT_RUSH")
MAX_PLAYERS = 3


class _Team:
    def __init__(self, code: str, mode: str, boss: str, version: str):
        self.code, self.mode, self.boss, self.version = code, mode, boss, version
        self.members: list = []          # seats, the leader first; a member's slot is its place here
        self.playing = False
        self.settling = False
        self.seed = 0
        self.difficulty = ""
        self.names: list[str] = []
        self.bots: dict = {}
        self.checks: dict[int, dict[int, int]] = {}   # tick -> slot -> the number that device worked out


class TeamLobby:
    def __init__(self, game, duel):
        self.game = game
        self.duel = duel   # the 1v1 lobby, whose port, log and rules for who may play this shares
        self._lock = threading.Lock()
        self._teams: dict[str, _Team] = {}
        self._random = random.SystemRandom()

    # ------------------------------------------------------------------ making and joining

    def _roster(self, team: _Team) -> None:
        """(Holding the lock.) Tells everyone in [team] who is in it."""
        members = [seat.describe() for seat in team.members]
        for slot, seat in enumerate(team.members):
            seat.slot = slot
            seat.send(self.duel.text(b"R", {"code": team.code, "mode": team.mode, "boss": team.boss, "you": slot, "members": members}))

    def enter(self, me, hello: dict) -> _Team | str:
        """Puts [me] in a new team or the one whose code they gave; or says why not."""
        action = str(hello.get("action") or "")
        with self._lock:
            if action == "create":
                mode = str(hello.get("mode") or "").upper()
                if mode not in MODES:
                    return "Teams play Boss Mode or Knockout Rush."
                boss = str(hello.get("boss") or "").upper()
                boss = boss if mode == "BOSS" and boss in rules.BOSSES else ""
                code = next(c for c in iter(lambda: "%04d" % self._random.randrange(10000), None) if c not in self._teams)
                team = self._teams[code] = _Team(code, mode, boss, me.version)
            else:
                team = self._teams.get(str(hello.get("code") or "").strip())
                if team is None:
                    return "There is no team with that code."
                if team.version != me.version:
                    return "That team is on a different build of the game. Update both to the newest one."
                if team.playing:
                    return "That team is in a match. Try again when it is over."
                if len(team.members) >= MAX_PLAYERS:
                    return "That team is full."
                if any(seat.player_id == me.player_id for seat in team.members):
                    return "This account is already in that team, on another device."
            me.team = team
            team.members.append(me)
            self._roster(team)
        self.duel.log(f"team {team.code} ({team.mode}): {', '.join(seat.name for seat in team.members)}")
        return team

    # ------------------------------------------------------------------ a match

    def start(self, me) -> None:
        """The leader presses Play."""
        team = me.team
        with self._lock:
            if team.playing or not team.members or team.members[0] is not me:
                return
            if len(team.members) < 2:
                me.send(self.duel.text(b"N", "A team needs at least two players. Give a friend the code."))
                return
            team.playing, team.settling = True, False
            team.seed = self._random.getrandbits(62)
            team.checks.clear()
            store = self.game.store
            team.difficulty = self.game.difficulty(store.player(me.player_id))
            team.bots = self.game.config.bots().get(team.difficulty, {})
            bots = 2 * 3 - len(team.members) if team.mode == "KNOCKOUT_RUSH" else 0
            now = time.monotonic()
            for seat in team.members:
                seat.frames, seat.record, seat.gone, seat.heard = 0, bytearray(), False, now
                # The level is the server's, as it stands now (it may have gone up since they joined).
                entry = store.profile(seat.player_id)["fighters"].get(seat.fighter) or {}
                seat.level = int(entry.get("level") or seat.level)
                try:
                    plan = store.plan_match(seat.player_id, team.mode, seat.fighter, team.difficulty, bots, team.bots, team.boss, seed=team.seed)
                    seat.match_id, team.names = plan["matchId"], plan["botNames"]
                except Refused:
                    seat.match_id = 0
            players = [seat.describe() for seat in team.members]
            for slot, seat in enumerate(team.members):
                seat.send(self.duel.text(b"S", {
                    "seed": team.seed, "slot": slot, "mode": team.mode, "boss": team.boss, "difficulty": team.difficulty,
                    "botNames": team.names, "bots": team.bots, "players": players,
                }))
        self.duel.log(f"team {team.code}: a {team.mode} match starts for {', '.join(p['name'] for p in players)}")

    def frame(self, me, frame: bytes) -> None:
        team = me.team
        if not team.playing or me.gone:
            return
        me.frames += 1
        me.heard = time.monotonic()
        if me.frames <= MAX_FRAMES:
            me.record += frame
        packet = b"I" + bytes([me.slot]) + frame
        for seat in list(team.members):
            if seat is not me and not seat.gone:
                seat.send(packet)

    def checked(self, me, tick: int, value: int) -> None:
        """A device's number for [tick]. Once everyone's is in they must agree, or the match can't go on."""
        team = me.team
        with self._lock:
            if not team.playing or me.gone:
                return
            numbers = team.checks.setdefault(tick, {})
            numbers[me.slot] = value
            present = [seat.slot for seat in team.members if not seat.gone]
            if any(slot not in numbers for slot in present):
                for old in [t for t in team.checks if t < tick - 600]:
                    del team.checks[old]
                return
            del team.checks[tick]
            if len({numbers[slot] for slot in present}) == 1:
                return
        self.duel.log(f"team {team.code}: its devices disagree about the match at tick {tick}; called off")
        self._call_off(team)

    def _call_off(self, team: _Team) -> None:
        """The match stops with nothing decided and nothing paid; the team stays together."""
        with self._lock:
            if not team.playing or team.settling:
                return
            team.settling = True
        for seat in list(team.members):
            if not seat.gone:
                seat.send(b"D")
        self._after(team)

    def _after(self, team: _Team) -> None:
        """Back to waiting for the leader, without whoever left during the match."""
        with self._lock:
            team.playing = team.settling = False
            team.members = [seat for seat in team.members if not seat.gone and seat.team is team]
            if team.members:
                self._roster(team)
            else:
                self._teams.pop(team.code, None)

    def _gone(self, team: _Team, me, dropped: bool = False) -> bool:
        """[me] is out of the match that is being played. True if nobody is left in it."""
        with self._lock:
            if me.gone or not team.playing:
                return False
            me.gone = True
            others = [seat for seat in team.members if not seat.gone]
        if dropped:
            # Their match is over; what it cost them is on their account the next time they look.
            me.send(b"L")
            me.send(self.duel.text(b"V", {}))
            self.duel._hang_up(me)
        packet = b"Q" + struct.pack(">Bi", me.slot, len(me.record) // FRAME_BYTES)
        for seat in others:
            seat.send(packet)
        self.duel.log(f"team {team.code}: {me.name} {'stopped responding' if dropped else 'left the match'}")
        return not others

    def finished(self, me) -> None:
        """[me]'s device says the match is over. The replay shows whether it is; if not, they have walked out of it."""
        team = me.team
        with self._lock:
            if not team.playing or team.settling or me.gone:
                return
            team.settling = True
        if not self._settle(team):
            with self._lock:
                team.settling = False
            me.send(self.duel.text(b"V", {}))
            everyone = self._gone(team, me)
            self.duel._hang_up(me)
            if everyone:
                self._settle(team, force=True)

    def _settle(self, team: _Team, force: bool = False) -> bool:
        """Replays the match and pays everyone who played in it. False if the replay hasn't reached its end
        (unless `force`: then it is settled as it stands, which is how a match everyone walked out of ends)."""
        members = list(team.members)
        referee = getattr(self.game, "referee", None)
        judged = None
        if referee is not None and all(seat.match_id for seat in members):
            try:
                judged = referee.judge_team(
                    team.mode, team.seed, team.difficulty, team.names, team.bots, team.boss,
                    [seat.fighter for seat in members], [seat.level for seat in members],
                    [bytes(seat.record[:len(seat.record) // FRAME_BYTES * FRAME_BYTES]) for seat in members],
                    [seat.slot for seat in members if seat.gone],
                )
            except Refused as refused:
                self.duel.log(f"team {team.code}: {refused.message}")
                force = True
        if judged is not None and not judged["finished"] and not force:
            return False
        for slot, seat in enumerate(members):
            if judged is None:
                if not seat.gone:
                    seat.send(self.duel.text(b"V", {}))
                continue
            stats = judged["sides"][slot]
            won = judged["finished"] and judged["winner"] == 0
            # Whoever walked out has lost, whatever the others went on to do.
            outcome = "DEFEAT" if seat.gone or not judged["finished"] else "VICTORY" if won else "DRAW" if judged["winner"] < 0 else "DEFEAT"
            report = {"outcome": outcome, "placement": 0, "kos": stats["kos"], "deaths": stats["deaths"], "damage": stats["damage"],
                      "mvp": outcome == "VICTORY" and judged["mvp"] == slot}
            verdict = self.game.store.finish_match(seat.player_id, seat.match_id, {**report, "ticks": judged["ticks"]}, verified=True)
            if seat.gone:
                continue
            if not verdict or "rejected" in verdict:
                seat.send(self.duel.text(b"V", {}))
                continue
            account = self.game.account(seat.player_id)
            seat.send(self.duel.text(b"V", {**verdict, "report": report, "account": {k: account[k] for k in ("drops", "dropsLeftToday")}}))
            self.duel.log(f"team {team.code}: {seat.name}: {outcome}, {verdict['cupDelta']:+d} Cups")
        self._after(team)
        return True

    def leave(self, me) -> None:
        """[me]'s device has hung up."""
        team = me.team
        if team is None:
            return
        if team.playing:
            if self._gone(team, me):
                with self._lock:
                    if team.settling:
                        return
                    team.settling = True
                self._settle(team, force=True)
            return
        with self._lock:
            me.team = None
            if me in team.members:
                team.members.remove(me)
            if team.members:
                self._roster(team)
            else:
                self._teams.pop(team.code, None)

    def watch(self, now: float, stall_seconds: float, start_seconds: float) -> None:
        """Drops whoever has stopped a match from moving: the player whose inputs stopped first."""
        with self._lock:
            playing = [team for team in self._teams.values() if team.playing and not team.settling]
        for team in playing:
            present = [seat for seat in team.members if not seat.gone]
            if not present:
                continue
            limit = stall_seconds if all(seat.frames for seat in present) else start_seconds
            if now - max(seat.heard for seat in present) < limit:
                continue
            fewest = min(seat.frames for seat in present)
            behind = [seat for seat in present if seat.frames == fewest]
            if len(behind) == len(present):
                self.duel.log(f"team {team.code}: everyone went quiet together; called off")
                self._call_off(team)
                continue
            for seat in present:
                seat.heard = now   # those still there get the time again, now that they can move
            everyone = False
            for seat in behind:
                everyone = self._gone(team, seat, dropped=True)
            if everyone:
                self._settle(team, force=True)

    # ------------------------------------------------------------------ one device

    def handle(self, sock: socket.socket, hello: dict, read) -> None:
        """Runs one device's connection, from its 'T' to the moment it hangs up."""
        me = self.duel.seat(hello)
        if isinstance(me, str):
            sock.sendall(self.duel.text(b"E", me))
            return
        me.sock, me.team, me.gone, me.slot = sock, None, False, 0
        sock.settimeout(None)
        team = self.enter(me, hello)
        if isinstance(team, str):
            me.send(self.duel.text(b"E", team))
            return
        try:
            while True:
                kind = read(sock, 1)
                if kind == b"G":
                    self.start(me)
                elif kind == b"F":
                    self.finished(me)
                elif kind == b"I":
                    frame = read(sock, FRAME_BYTES)
                    if frame is None:
                        break
                    self.frame(me, frame)
                elif kind == b"C":
                    check = read(sock, CHECK_BYTES)
                    if check is None:
                        break
                    self.checked(me, *struct.unpack(">ii", check))
                else:
                    break
        finally:
            self.leave(me)
