from miniasgi import HTTPException, JSONResponse, Response

from .models import parse_book

MAX_LIMIT = 100


def _first(request, name):
    values = request.query_params.getlist(name)
    return values[0] if values else None


def _int_arg(request, name, default, low, high):
    raw = _first(request, name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise HTTPException(400, "%s must be an integer" % name) from None
    if value < low or (high is not None and value > high):
        raise HTTPException(400, "%s out of range" % name)
    return value


async def _body(request):
    try:
        return await request.json()
    except ValueError:
        raise HTTPException(400, "invalid json") from None


def register(app, store):
    @app.get("/health")
    async def health(request):
        return JSONResponse({"status": "ok"})

    @app.get("/books")
    async def list_books(request):
        limit = _int_arg(request, "limit", 20, 1, MAX_LIMIT)
        offset = _int_arg(request, "offset", 0, 0, None)
        tags = [tag.lower() for tag in request.query_params.getlist("tag")]
        items, total = store.search(author=_first(request, "author"), tags=tags, limit=limit, offset=offset)
        return JSONResponse({"items": [book.to_dict() for book in items], "total": total}, headers={"X-Total-Count": str(total)})

    @app.post("/books")
    async def create_book(request):
        book = store.add(**parse_book(await _body(request)))
        return JSONResponse(book.to_dict(), 201, {"Location": "/books/%d" % book.id})

    @app.get("/books/{id:int}")
    async def get_book(request):
        book = store.get(request.path_params["id"])
        if book is None:
            raise HTTPException(404, "not found")
        return JSONResponse(book.to_dict())

    @app.put("/books/{id:int}")
    async def replace_book(request):
        if store.get(request.path_params["id"]) is None:
            raise HTTPException(404, "not found")
        book = store.replace(request.path_params["id"], **parse_book(await _body(request)))
        return JSONResponse(book.to_dict())

    @app.delete("/books/{id:int}")
    async def delete_book(request):
        if not store.delete(request.path_params["id"]):
            raise HTTPException(404, "not found")
        return Response(status_code=204)
