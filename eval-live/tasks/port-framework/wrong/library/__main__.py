import os

from miniasgi.server import serve

from .app import create_app


def main():
    tokens = [token.strip() for token in os.environ.get("SHELF_TOKENS", "dev-token").split(",") if token.strip()]
    serve(create_app(tokens=tokens), port=int(os.environ.get("SHELF_PORT", "8000")))


if __name__ == "__main__":
    main()
