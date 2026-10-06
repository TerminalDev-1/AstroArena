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

Leave it running while you play. The game works without it (it falls back to playing locally), so stopping the
server never locks anyone out.

## What it does

| | |
|---|---|
| **Version gate** | `versions_not_supported.cfg` lists client versions that are refused, with the message they see |
| **Notices** | `notices.cfg` holds short messages shown on the home screen |
| **Bots** | `bots.cfg` sets how bots behave at each difficulty; `game.cfg` sets which difficulties players may pick. The game asks, the server approves |
| **Cups** | `trophies.cfg` sets the Cups each mode pays: by place, or for a win, a draw and a defeat |
| **Accounts** | `accounts.cfg` is written by the server: every account, leaderboard first. Change a value (Cups, a fighter's level, Crystals...) and save to force it |
| **Daily offers** | `shop.cfg` is the pool; the server picks a few each day, the same for everyone, and says when the day ends |
| **Time** | the day, when it ends, and every countdown come from the server's clock |
| **Developers** | `game.cfg` lists the players who can switch on the debug menu, make shop deals and reset an account |
| **Accounts** | a new player picks a name, then the install registers once and gets an id and a secret token |
| **Cups** | the server works out what each match is worth and keeps the total |
| **Credits and the Spark Road** | Credits (from matches, drops, the Cup Track, the Spark Pass and the shop) are not a wallet: they go straight into the Spark Road, toward the next fighter along it (a fixed order, the cheapest rarity first) |
| **Glory** | once every fighter is unlocked, Credits are earned as Glory instead: a rank shown beside the player's name, which buys nothing |
| **Spark Pass** | a 28-day season of 30 tiers; matches earn pass points, each tier has a reward to claim |
| **Bolts and Prisms** | kept by the server: match pay, upgrades, shop purchases, the daily gift and Cup Track rewards all happen there |
| **Deals** | shop offers made by developers in the game's Offer Creator, stored here and shown to every player |
| **Leaderboard** | the real accounts on this server, ranked by Cups; there are no made-up names |
| **Spark Drops** | the server decides when one is earned (three a day) and rolls what comes out when it is opened |
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

Settings > Developer appears for them the next time the game talks to the server, and disappears again when
the id is removed. That is where they switch the debug menu on; it is off by default. `everyone = yes` makes
every player a developer. Debug builds of the game always show the Developer tab, but the server still ignores
the debug menu's cheats unless the id is listed.

### What the server can and can't stop

The fight is played on the device, which then hands in the player's inputs, tick by tick. The server replays
the match with those inputs, the seed and the bots it chose, using the game's own simulation, and takes the
result from its replay. So:

- A device can't claim a result. Whatever it says about who won, the knockouts or the damage is ignored.
- Changed health, damage, speed or cooldowns on the device change nothing: the replay uses the real numbers.
- A match that is handed in unfinished counts as walking out: a defeat, in last place.
- A match can't be handed in faster than it could have been played.

- A save file can't set Cups, Spark Drops, Bolts, Prisms, levels or what is owned. They are read from a save
  once, when an account first uploads one (so earlier progress carries over); set `import_saves = no` in
  `game.cfg` to stop even that.
- Buying, upgrading and claiming are checked on the server: the price is the server's, the player has to be
  able to afford it, and nothing is granted twice.
- A result is refused if the server didn't plan the match, if it was already reported, or if the inputs are
  missing or can't be replayed. Refused results are counted in the `flags` column of the `players` table.
- What comes out of a drop is rolled on the server, so a client can't choose its reward or open drops it
  doesn't have.
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
| `POST /v1/players` `{name, version}` | `{id, token}` |
| `GET /v1/me` * | `{account}` |
| `GET /v1/save` * | `{revision, updatedAt, save}` or 404 |
| `PUT /v1/save` * `{save}` | `{revision, account}` |
| `POST /v1/matches` * `{mode, fighter, boss}` (`boss` is optional: which Boss Mode boss to fight) | `{matchId, seed, botNames, difficulty, fighter, level, bots, boss, refereed}`; 409 if the fighter isn't unlocked |
| `POST /v1/matches/<id>/result` * `{inputs}` (base64 of the gzipped input log; the device's own result fields are only used when there is no referee) | `{verified, report, cupDelta, cups, drop, bolts, firstWinPrisms, account}`, or 422 if refused |
| `POST /v1/drops/open` * `{luck, free}` | `{tier, pieces, reward, account}`, or 409 if there are none |
| `POST /v1/drops/open-all` * `{luck}` | `{results: [{tier, pieces, reward}, ...], account}`: every drop the player holds, in the order opened (pieces that split off are left to open next); 409 if there are none |
| `POST /v1/fighters/upgrade` * `{fighter}` | `{cost, account}`; 402 if it can't be afforded, 409 if it can't be upgraded |
| `POST /v1/shop/buy` * `{item}` | `{reward, account}`; items are `fighter_MIRA`, `skin_MIRA_1`, `crate_s` / `crate_m` / `crate_l` |
| `POST /v1/shop/gift` * | `{reward, account}`; 409 once claimed today |
| `POST /v1/shop/deals/<id>/buy` * | `{reward, account}` |
| `POST /v1/track/claim` * `{cups}` | `{reward, account}` |
| `POST /v1/road/unlock` * | `{reward, account}`: claims the Spark Road fighter the Credits have covered; 402 if they haven't yet, 409 when the road is finished |
| `POST /v1/pass/claim` * `{tier}` | `{reward, account}`: a Spark Pass tier; 409 if not reached or already claimed |
| `POST /v1/shop/daily/<n>/buy` * `{day}` | `{reward, account}`: one of today's offers; 409 if bought already or the day has changed |
| `POST /v1/settings/difficulty` * `{difficulty}` | `{ok, account}`, or 403 if the server doesn't allow it |
| `POST /v1/reset` * | `{account}`: starts this account's progress over (developers only) |
| `POST /v1/dev/grant` * `{cups, drops, bolts, prisms}` | `{account}` (developers only) |
| `POST /v1/dev/deals` * `{title, bolts, prisms, fighter, skinFighter, skinIndex, currency, price, wasPrice, expiresAt, limit, theme}` | `{id, account}` (developers only) |
| `POST /v1/dev/deals/<id>/delete` * | `{deleted, account}` (developers only) |
| `GET /v1/leaderboard?limit=50` | `{players: [{id, name, cups, fighter, glory}]}` |

`account` is `{id, name, developer, cups, rank, players, drops, dropsLeftToday, difficulty, difficulties, profile,
deals, dailyOffers, giftAvailable, time}`; `time` is `{now, day, dayEndsAt}` on the server's clock.
`profile` is `{bolts, prisms, bestCups, fighters, claimedMilestones, lastDailyGiftDay, lastFirstWinDay}`.

Traffic is plain HTTP, which is fine on a home network and not fine on the open internet. Put it behind HTTPS
before exposing it beyond your own network.
