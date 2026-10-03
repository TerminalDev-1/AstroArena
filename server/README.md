# AstroArena server

A small game server in Python: a JSON API over HTTP in front of a SQLite database. It uses only the Python
standard library (3.10 or newer), so there is nothing to install.

## Run it

Double-click `run.bat`, or:

```bash
python run.py
```

It prints the address to type into the game (Settings > Data > Server address), for example
`http://192.168.1.103:8765`. The device and this computer have to be on the same network, and Windows may ask
once whether Python is allowed through the firewall: say yes for private networks.

Leave it running while you play. The game works without it (it falls back to playing locally), so stopping the
server never locks anyone out.

## What it does

| | |
|---|---|
| **Version gate** | `versions_not_supported.cfg` lists client versions that are refused, with the message they see |
| **Notices** | `notices.cfg` holds short messages shown on the home screen |
| **Bots** | `bots.cfg` sets how bots behave at each difficulty; `game.cfg` sets which difficulty players get |
| **Developers** | `game.cfg` lists the players who get the debug menu and the difficulty choice |
| **Accounts** | each install registers once and gets an id and a secret token |
| **Cups** | the server works out what each match is worth and keeps the total; the leaderboard ranks by it |
| **Spark Drops** | the server decides when one is earned (three a day) and rolls what comes out when it is opened |
| **Matches** | the server plans each match (seed, bot names, difficulty) and checks the result it is sent |
| **Saves** | the game uploads its save after every change; a fresh install restores it |

The `.cfg` files are re-read when they change, so edits apply without a restart.

### Making someone a developer

Their player id is shown in the game under Settings > Data. Add it to `ids` in `game.cfg`:

```
[developers]
everyone = no
ids = f5c75a1aa787e6e1, another-id
```

The debug menu appears for them the next time the game talks to the server, and disappears again when the id is
removed. `everyone = yes` turns it on for all players. Debug builds of the game always show the menu, but the
server still ignores its drop luck, free drops, difficulty choice and hand-outs unless the id is listed.

### What the server can and can't stop

The fight itself runs on the device; the server only sees the summary. So:

- A save file can't set Cups or Spark Drops. They are read from a save once, when an account first uploads one
  (so earlier progress carries over); set `import_saves = no` in `game.cfg` to stop even that.
- A result is refused if the server didn't plan the match, if it was already reported, if it is finished faster
  than a match can be played, or if its numbers are impossible for the mode. Refused results are counted in the
  `flags` column of the `players` table.
- What comes out of a drop is rolled on the server, so a client can't choose its reward or open drops it
  doesn't have.
- Unsupported versions are refused on every request, not just told to update.
- It can't catch a modified client that actually plays the match with cheats (aimbot, extra damage) and reports
  a plausible result. Only running the fight on the server would stop that.
- Bolts, Prisms, fighter levels and skins bought in the shop are still kept in the save on the device.

## Data

Everything is in `astroarena.db` next to this file (created on first run, not committed to git). Delete it to
start over. To look inside:

```bash
python -c "import sqlite3; db = sqlite3.connect('astroarena.db'); print(db.execute('select id, name, cups, drops, flags, version from players').fetchall())"
```

## Tests

```bash
python -m unittest
```

## API

All bodies are JSON. Endpoints marked * need `Authorization: Bearer <token>` and an `X-Client-Version` header.

| | |
|---|---|
| `GET /v1/health` | `{ok, api, players, matches, finished}` |
| `GET /v1/status?version=7` | `{supported, message, notice}` |
| `GET /v1/config` | `{bots: {EASY: {...}, ...}}` |
| `POST /v1/players` `{name, version}` | `{id, token}` |
| `GET /v1/me` * | `{account}` |
| `GET /v1/save` * | `{revision, updatedAt, save}` or 404 |
| `PUT /v1/save` * `{save}` | `{revision, account}` |
| `POST /v1/matches` * `{mode, fighter, level, difficulty}` | `{matchId, seed, botNames, difficulty}` |
| `POST /v1/matches/<id>/result` * `{outcome, placement, kos, deaths, damage, mvp}` | `{cupDelta, cups, drop, account}`, or 422 if refused |
| `POST /v1/drops/open` * `{luck, free}` | `{tier, pieces, reward, account}`, or 409 if there are none |
| `POST /v1/dev/grant` * `{cups, drops}` | `{account}` (developers only) |
| `GET /v1/leaderboard?limit=50` | `{players: [{id, name, cups, fighter}]}` |

`account` is `{id, name, developer, cups, drops, dropsLeftToday, difficulty}`.

Traffic is plain HTTP, which is fine on a home network and not fine on the open internet. Put it behind HTTPS
before exposing it beyond your own network.
