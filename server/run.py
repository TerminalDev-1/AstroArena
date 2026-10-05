"""Start the AstroArena server:  python run.py  [--port 8765] [--host 0.0.0.0]"""

import argparse
import os
import socket

from astro.app import serve


def lan_address() -> str:
    """This computer's address on the local network (what a phone or tablet should connect to)."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("10.255.255.255", 1))  # no packet is sent; this only picks the outgoing interface
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="AstroArena game server")
    parser.add_argument("--host", default="0.0.0.0", help="address to listen on (default: every interface)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--sparring", action="store_true", help="1v1: pair a player who waits 5 seconds alone with a stand-still dummy (for testing with one device)")
    args = parser.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    httpd = serve(here, args.host, args.port, sparring=args.sparring)
    print(f"AstroArena server listening on port {args.port}")
    print(f"  In the game: Settings > Data > Server address:  http://{lan_address()}:{args.port}")
    print(f"  Database: {os.path.join(here, 'astroarena.db')}")
    print("  Edit the .cfg files (versions_not_supported, notices, bots, game, shop) while it runs; changes apply at once.")
    print(f"  1v1 lobby: port {args.port + 1}" + (" (with the sparring dummy)" if args.sparring else "") if httpd.duel is not None else f"  1v1 lobby: OFF, port {args.port + 1} is in use")
    referee = httpd.game.referee
    if referee is not None:
        print("  Referee: on. Every match is replayed here to decide its result.")
    else:
        from astro.referee import Referee
        print("  Referee: OFF, results are only checked for being believable: " + Referee(os.path.join(here, "referee", "referee.jar")).why_not())
    print("  Ctrl+C to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        if httpd.duel is not None:
            httpd.duel.shutdown()
            httpd.duel.server_close()
        httpd.server_close()
        httpd.game.store.close()


if __name__ == "__main__":
    main()
