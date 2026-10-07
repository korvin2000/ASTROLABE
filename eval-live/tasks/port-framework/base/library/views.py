from tinyhttp import HTTPError, Response, json_response

from .models import parse_book

MAX_LIMIT = 100


def _first(request, name):
    values = request.args.get(name)
    return values[0] if values else None


def _int_arg(request, name, default, low, high):
    raw = _first(request, name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise HTTPError(400, "%s must be an integer" % name) from None
    if value < low or (high is not None and value > high):
        raise HTTPError(400, "%s out of range" % name)
    return value


def register(app, store):
    @app.route("GET", "/health")
    def health(request):
        return json_response({"status": "ok"})

    @app.route("GET", "/books")
    def list_books(request):
        limit = _int_arg(request, "limit", 20, 1, MAX_LIMIT)
        offset = _int_arg(request, "offset", 0, 0, None)
        tags = [tag.lower() for tag in request.args.get("tag", [])]
        items, total = store.search(author=_first(request, "author"), tags=tags, limit=limit, offset=offset)
        return json_response({"items": [book.to_dict() for book in items], "total": total}, headers={"X-Total-Count": str(total)})

    @app.route("POST", "/books")
    def create_book(request):
        book = store.add(**parse_book(request.json()))
        return json_response(book.to_dict(), 201, {"Location": "/books/%d" % book.id})

    @app.route("GET", "/books/<int:id>")
    def get_book(request):
        book = store.get(request.params["id"])
        if book is None:
            raise HTTPError(404, "not found")
        return json_response(book.to_dict())

    @app.route("PUT", "/books/<int:id>")
    def replace_book(request):
        if store.get(request.params["id"]) is None:
            raise HTTPError(404, "not found")
        book = store.replace(request.params["id"], **parse_book(request.json()))
        return json_response(book.to_dict())

    @app.route("DELETE", "/books/<int:id>")
    def delete_book(request):
        if not store.delete(request.params["id"]):
            raise HTTPError(404, "not found")
        return Response(204)
