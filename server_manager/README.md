# AstroArena Server Manager

A window for whoever runs the server, so nothing has to be edited by hand.

Double-click **Server Manager.bat**, or run `python manager.py` in this folder. It needs Python (the one the
server already uses) and nothing else.

| Page | What it does | What it changes |
|---|---|---|
| **Server** | Start, stop and restart the server, and read what it prints | the server process; its log goes to `server/logs/server.log` |
| **Accounts** | Every player: Cups, Crystals, Power Ups, each fighter's lock, level and Cups; disable an account with a reason and for how long | the account database, at once |
| **Trophies** | The Cups each mode pays | `server/trophies.cfg` |
| **Game rules** | Bot difficulties players may pick, who the developers are, how new accounts start | `server/game.cfg` |
| **Shop** | The pool the daily offers are picked from | `server/shop.cfg` |
| **News** | The game's News tab | `server/news.cfg` |
| **Messages** | The home screen notice, and which builds are turned away | `server/notices.cfg`, `server/versions_not_supported.cfg` |
| **Bots** | How bots play at each difficulty | `server/bots.cfg` |

The server re-reads its `.cfg` files when they change, so a save here applies without a restart. The comments
in those files are kept.

A server started from here runs on its own in the background and keeps running when the manager is closed.
The manager can also stop a server that was started some other way (`run.bat`), but it can only show the log
of one it started itself.

`python -m unittest` in this folder checks that the files the manager writes still mean the same to the server.
