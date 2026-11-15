"""A full reset of the game, by the owner's hand: every account is deleted and every device starts over as a new
player (a name, then the opening), however long ago it last played.

Run it with the server stopped, from this folder:   python reset_game.py
It first copies the database to astroarena.backup-before-full-reset-<date>.db, keeps what each account held in
deleted_accounts.log, and marks a new generation of the game, which tells every device to wipe itself and start over.
"""
import os
import shutil
import sqlite3
import sys
import time

from astro.accounts import Accounts
from astro.store import Store

DB = "astroarena.db"


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    os.chdir(here)
    if not os.path.exists(DB):
        sys.exit("No %s here: nothing to reset." % DB)
    if "--yes" not in sys.argv and input("Delete EVERY account and start the game over for everyone? Type RESET: ").strip() != "RESET":
        sys.exit("Nothing was changed.")
    backup = "astroarena.backup-before-full-reset-%s.db" % time.strftime("%Y-%m-%d-%H%M%S")
    shutil.copyfile(DB, backup)
    print("Backed up to", backup)
    store = Store(DB)
    accounts = Accounts(store, here, quiet=True)
    ids = [row[0] for row in sqlite3.connect(DB).execute("SELECT id FROM players")]
    for player_id in ids:
        accounts.delete(player_id, "full game reset")
    accounts.sync()
    print("Deleted %d accounts (what they held is in deleted_accounts.log)." % len(ids))
    print("New generation:", store.new_generation())


if __name__ == "__main__":
    main()
