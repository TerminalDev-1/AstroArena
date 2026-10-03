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
    args = parser.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    httpd = serve(here, args.host, args.port)
    print(f"AstroArena server listening on port {args.port}")
    print(f"  In the game: Settings > Data > Server address:  http://{lan_address()}:{args.port}")
    print(f"  Database: {os.path.join(here, 'astroarena.db')}")
    print("  Edit versions_not_supported.cfg, notices.cfg and bots.cfg while it runs; changes apply at once.")
    print("  Ctrl+C to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        httpd.server_close()
        httpd.game.store.close()


if __name__ == "__main__":
    main()
