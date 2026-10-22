# AstroArena server

A small game server in Python: a JSON API over HTTP in front of a SQLite database. It uses only the Python
standard library (3.10 or newer), so there is nothing to install.

To referee matches it also needs **Java 17 or newer** (it runs the game's own simulation, `referee/referee.jar`).
When it starts it says whether the referee is on. Without Java it still runs, but can only check that results
are believable.

## Run it

Double-click `run.bat`, or:

```bash
python run.py
```

It prints the address to type into the game (Settings > Data > Server address), for example
`http://192.168.1.103:8765`. The device and this computer have to be on the same network, and Windows may ask
once whether Python is allowed through the firewall: say yes for private networks.

Leave it running while you play. The game works without it (it switches to an offline profile of its own, kept on
the device: Chaos Mode), so stopping the server never locks anyone out. It also opens a second port, the game port
+ 1, for the 1v1 and team lobby.

Players see "Upgrade Credits", "CPU Chips" and "Arena Boxes"; the server's code, database and API still call them
bolts, prisms and drops.

## What it does

| | |
|---|---|
| **Version gate** | `versions_not_supported.cfg` lists client versions that are refused, with the message they see |
| **Notices** | `notices.cfg` holds short messages shown on the home screen |
| **News** | `news.cfg` is the News tab: one section per item, newest first |
| **Bots** | `bots.cfg` sets how bots behave at each difficulty; `game.cfg` sets which difficulties players may pick. The game asks, the server approves |
| **Cups** | `trophies.cfg` sets the Cups each mode pays: by place, or for a win, a draw and a defeat |
| **Accounts** | `accounts.cfg` is written by the server: every account, leaderboard first. Change a value (Cups, a fighter's level, CPU Chips...) and save to force it, or `disabled = yes` (with a reason and an end, if you like) to shut an account out |
| **Daily offers** | `shop.cfg` is the pool; the server picks a few each day, the same for everyone, and says when the day ends |
| **Time** | the day, when it ends, and every countdown come from the server's clock |
| **Developers** | `game.cfg` lists the players who get the Chaos Command Center (every tweak, always on), can make shop deals and can reset an account |
| **Accounts** | a new player picks a name, then the install registers once and gets an id and a secret token |
| **Cups** | the server works out what each match is worth and keeps the total |
| **Credits and the Spark Road** | Credits (from matches, drops, the Cup Track and the shop) are not a wallet: they go straight into the Spark Road, toward the next fighter along it (a fixed order, the cheapest rarity first). The moment they cover it the fighter is unlocked; once every fighter is unlocked they are paid as Upgrade Credits |
| **Upgrade Credits and CPU Chips** | kept by the server: match pay, upgrades, shop purchases, the daily gift and Cup Track rewards all happen there |
| **Deals** | shop offers made by developers in the game's Offer Creator, stored here and shown to every player |
| **Leaderboard** | the real accounts on this server, ranked by Cups; there are no made-up names |
| **Arena Boxes** | the server decides when one is earned (three a day) and rolls each of the items inside when it is opened |
| **1v1 and teams** | the lobby on the game port + 1 pairs 1v1 players and gathers teams by a four-digit code, passes their inputs between devices, replays the match through the referee and pays each player by the replay |
| **Matches** | the server plans each match (seed, bots, difficulty, fighter level). The game hands in what the player did, and the server replays the whole match to get the result |
| **Saves** | the game uploads its save (settings and local statistics) after every change; a fresh install restores it |

The `.cfg` files are re-read when they change, so edits apply without a restart.

### Making someone a developer

Their player id is shown in the game under Settings > Data. Add it to `ids` in `game.cfg`:

```
[developers]
everyone = no
ids = f5c75a1aa787e6e1, another-id
```

Settings > Chaos Command Center appears for them the next time the game talks to the server, and disappears again
when the id is removed. It holds every tweak (drop luck, free drops, upgrade cost, no level cap, hand-outs) and is
always on; there is no switch. `everyone = yes` makes every player a developer. A debug build is not enough: the
server ignores the tweaks from anyone it doesn't list. (Offline, everyone has the tweaks, because they only touch
the offline profile.)

### What the server can and can't stop

The fight is played on the device, which then hands in the player's inputs, tick by tick. The server replays
the match with those inputs, the seed and the bots it chose, using the game's own simulation, and takes the
result from its replay. So:

- A device can't claim a result. Whatever it says about who won, the knockouts or the damage is ignored.
- Changed health, damage, speed or cooldowns on the device change nothing: the replay uses the real numbers.
- A match that is handed in unfinished counts as walking out: a defeat, in last place.
- A match can't be handed in faster than it could have been played.

- A save file can't set Cups, Arena Boxes, Upgrade Credits, CPU Chips, levels or what is owned. They are read from a save
  once, when an account first uploads one (so earlier progress carries over); set `import_saves = no` in
  `game.cfg` to stop even that.
- Buying, upgrading and claiming are checked on the server: the price is the server's, the player has to be
  able to afford it, and nothing is granted twice.
- A result is refused if the server didn't plan the match, if it was already reported, or if the inputs are
  missing or can't be replayed. Refused results are counted in the `flags` column of the `players` table.
- What comes out of an Arena Box is rolled on the server, so a client can't choose its rewards or open boxes it
  doesn't have.
- A 1v1 or team match is judged by the lobby from every player's inputs; nobody's device is asked who won.
- Unsupported versions are refused on every request, not just told to update.
- It can't tell how the inputs were made. A program that plays for the player (an aimbot, a bot script) hands
  in inputs that replay perfectly well. Seeing through walls is also possible, since the device has to know
  where everyone is in order to draw the match.
- The server's log says, for every match, whether the device's own result agreed with the replay. They should
  always agree for an honest game; "the device said ..." means that device is lying or its game is modified.

## Data

Everything is in `astroarena.db` next to this file (created on first run, not committed to git). Delete it to
start over. To look inside:

```bash
python -c "import sqlite3; db = sqlite3.connect('astroarena.db'); print(db.execute('select id, name, cups, drops, flags, version, profile from players').fetchall())"
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
| `GET /v1/status?version=11` | `{supported, message, notice}` |
| `GET /v1/config` | `{bots: {EASY: {...}, ...}}` |
| `GET /v1/news` | `{news: [...]}`: the items in `news.cfg`, newest first |
| `POST /v1/players` `{name, version}` | `{id, token}` |
| `GET /v1/me` * | `{account}` |
| `GET /v1/save` * | `{revision, updatedAt, save}` or 404 |
| `PUT /v1/save` * `{save}` | `{revision, account}` |
| `POST /v1/matches` * `{mode, fighter, boss}` (`boss` is optional: which Boss Mode boss to fight) | `{matchId, seed, botNames, difficulty, fighter, level, bots, boss, refereed}`; 409 if the fighter isn't unlocked |
| `POST /v1/matches/<id>/result` * `{inputs}` (base64 of the gzipped input log; the device's own result fields are only used when there is no referee) | `{ok, verified, report, cupDelta, mvpCups, cups, drop, bolts, credits, unlocked, fighter, fighterCups, fighterCupsBefore, fighterRank, firstWinPrisms, account}`, or 422 if refused |
| `POST /v1/drops/open` * `{luck, free}` (`luck` and `free` count for developers only) | `{items: [{tier, reward}, ...], account}`: one Arena Box and every item in it; 409 if there are none |
| `POST /v1/drops/open-all` * `{luck}` | `{results: [{items}, ...], account}`: every box the player holds, in the order opened; 409 if there are none |
| `POST /v1/fighters/upgrade` * `{fighter}` (`costFactor` and `noCap` count for developers only) | `{cost, account}`; 402 if it can't be afforded, 409 if it can't be upgraded |
| `POST /v1/shop/buy` * `{item}` | `{reward, account}`; items are `fighter_<ID>` (e.g. `fighter_KIRA`), `skin_<ID>_<n>`, `crate_s` / `crate_m` / `crate_l` (Upgrade Credits) and `credits_s` / `credits_m` / `credits_l` (Credits for the Spark Road), paid in CPU Chips |
| `POST /v1/shop/gift` * | `{reward, account}`; 409 once claimed today |
| `POST /v1/shop/deals/<id>/buy` * | `{reward, account}` |
| `POST /v1/track/claim` * `{cups}` | `{reward, account}` |
| `POST /v1/shop/daily/<n>/buy` * `{day}` | `{reward, account}`: one of today's offers; 409 if bought already or the day has changed |
| `POST /v1/settings/difficulty` * `{difficulty}` | `{ok, account}`, or 403 if the server doesn't allow it |
| `POST /v1/reset` * | `{account}`: starts this account's progress over (developers only) |
| `POST /v1/dev/grant` * `{cups, drops, bolts, prisms, credits}` | `{account}` (developers only) |
| `POST /v1/dev/deals` * `{title, bolts, prisms, fighter, skinFighter, skinIndex, currency, price, wasPrice, expiresAt, limit, theme}` | `{id, account}` (developers only) |
| `POST /v1/dev/deals/<id>/delete` * | `{deleted, account}` (developers only) |
| `GET /v1/leaderboard?limit=50` | `{players: [{id, name, cups, fighter}]}` |

`account` is `{id, name, developer, cups, rank, players, drops, dropsLeftToday, difficulty, difficulties, profile,
road, deals, dailyOffers, giftAvailable, time}`; `road` is `{steps: [{fighter, cost, rarity}], target}`, the Spark
Road in order and the fighter the Credits are filling; `time` is `{now, day, dayEndsAt}` on the server's clock.
`profile` is `{bolts, prisms, credits, bestCups, fighters, claimedMilestones, lastDailyGiftDay, lastFirstWinDay}`;
each fighter is `{unlocked, level, ownedSkins, cups}`. Fighter ids are the code's: `BYTE` is Spark and `BRAKK` is
Bark.

Traffic is plain HTTP, which is fine on a home network and not fine on the open internet. Put it behind HTTPS
before exposing it beyond your own network.
