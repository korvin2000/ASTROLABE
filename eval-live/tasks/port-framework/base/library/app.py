import itertools

from tinyhttp import App, json_response

from .auth import require_token
from .errors import ValidationError
from .store import BookStore
from .views import register


def create_app(store=None, tokens=("dev-token",)):
    """The shelf service over ``store`` (a fresh one by default); writes need one of ``tokens``."""
    store = store if store is not None else BookStore()
    app = App()
    counter = itertools.count(1)

    @app.before_request
    def assign_request_id(request):
        request.state["request_id"] = request.headers.get("x-request-id") or "req-%d" % next(counter)

    app.before_request(require_token(set(tokens)))

    @app.after_request
    def echo_request_id(request, response):
        response.headers["x-request-id"] = request.state["request_id"]

    @app.errorhandler(ValidationError)
    def invalid(exc):
        return json_response({"error": "validation", "fields": exc.fields}, 422)

    register(app, store)
    return app
