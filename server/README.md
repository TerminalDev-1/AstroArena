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
| **Version gate** | `versions_not_supported.cfg` lists client versions that are turned away, with the message they see |
| **Notices** | `notices.cfg` holds short messages shown on the home screen |
| **Bots** | `bots.cfg` sets how bots behave at each difficulty; the game uses its built-in numbers when offline |
| **Accounts** | each install registers once and gets an id and a secret token |
| **Saves** | the game uploads its save after every change; a fresh install restores it |
| **Matches** | the server plans each match (its seed and the bots' names) and records the result |
| **Leaderboard** | real players ranked by Cups |

The three `.cfg` files are re-read when they change, so edits apply without a restart.

The match itself still runs on the device. The server decides the setup and keeps the record; it does not
simulate the fight.

## Data

Everything is in `astroarena.db` next to this file (created on first run, not committed to git). Delete it to
start over. To look inside:

```bash
python -c "import sqlite3; db = sqlite3.connect('astroarena.db'); print(db.execute('select name, cups, version from players').fetchall())"
```

## Tests

```bash
python -m unittest
```

## API

All bodies are JSON. Endpoints marked * need `Authorization: Bearer <token>`.

| | |
|---|---|
| `GET /v1/health` | `{ok, api, players, matches, finished}` |
| `GET /v1/status?version=6` | `{supported, message, notice}` |
| `GET /v1/config` | `{bots: {EASY: {...}, ...}}` |
| `POST /v1/players` `{name, version}` | `{id, token}` |
| `GET /v1/save` * | `{revision, updatedAt, save}` or 404 |
| `PUT /v1/save` * `{save}` | `{revision}` |
| `POST /v1/matches` * `{mode, fighter, level, difficulty}` | `{matchId, seed, botNames}` |
| `POST /v1/matches/<id>/result` * `{outcome, placement, kos, deaths, damage}` | `{ok}` |
| `GET /v1/leaderboard?limit=50` | `{players: [{id, name, cups, fighter}]}` |

Traffic is plain HTTP, which is fine on a home network and not fine on the open internet. Put it behind HTTPS
before exposing it beyond your own network.
