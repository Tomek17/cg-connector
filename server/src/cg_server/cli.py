"""Command line entry point (`cg-server ...`)."""

import argparse

import uvicorn

from cg_server.crypto import generate_key


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cg-server")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve", help="uruchom serwer HTTP")
    serve.add_argument("--host", default="0.0.0.0")  # noqa: S104 - inside container only
    serve.add_argument("--port", type=int, default=8000)
    sub.add_parser("gen-key", help="wypisz nowy klucz szyfrowania (CG_ENCRYPTION_KEY)")
    args = parser.parse_args(argv)

    if args.command == "serve":
        uvicorn.run("cg_server.app:app", host=args.host, port=args.port, proxy_headers=True)
    elif args.command == "gen-key":
        print(generate_key())
    return 0
