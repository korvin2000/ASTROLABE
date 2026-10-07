import unittest

from library.app import create_app
from library.store import BookStore
from tinyhttp.testing import Client

AUTH = {"Authorization": "Bearer dev-token"}


class Api(unittest.TestCase):
    def setUp(self):
        self.client = Client(create_app(BookStore()))

    def add(self, **fields):
        return self.client.post("/books", json=fields, headers=AUTH)

    def test_health_is_open(self):
        answer = self.client.get("/health")
        self.assertEqual((answer.status, answer.json()), (200, {"status": "ok"}))
        self.assertEqual(answer.headers["content-type"], "application/json")

    def test_a_write_needs_a_token(self):
        answer = self.client.post("/books", json={"title": "Dune", "author": "Herbert"})
        self.assertEqual((answer.status, answer.json()), (401, {"error": "unauthorized"}))
        self.assertEqual(answer.headers["www-authenticate"], "Bearer")
        self.assertEqual(self.client.delete("/books/1").status, 401)

    def test_create_then_read(self):
        created = self.add(title="Dune", author="Herbert", year=1965, tags=["SF"])
        self.assertEqual(created.status, 201)
        self.assertEqual(created.headers["location"], "/books/1")
        self.assertEqual(created.json(), {"id": 1, "title": "Dune", "author": "Herbert", "year": 1965, "tags": ["sf"]})
        self.assertEqual(self.client.get("/books/1").json(), created.json())

    def test_validation_errors_name_the_fields(self):
        answer = self.add(author="Herbert")
        self.assertEqual((answer.status, answer.json()), (422, {"error": "validation", "fields": {"title": "required"}}))

    def test_a_body_that_is_not_json(self):
        answer = self.client.post("/books", data=b"{nope", headers=AUTH)
        self.assertEqual((answer.status, answer.json()), (400, {"error": "invalid json"}))

    def test_unknown_book_route_and_method(self):
        self.assertEqual((self.client.get("/books/9").status, self.client.get("/books/9").json()), (404, {"error": "not found"}))
        self.assertEqual(self.client.get("/shelves").status, 404)
        wrong = self.client.request("PATCH", "/books/1", headers=AUTH)
        self.assertEqual((wrong.status, wrong.json()), (405, {"error": "method not allowed"}))

    def test_listing_filters_and_pages(self):
        self.add(title="A", author="ann", tags=["x", "y"])
        self.add(title="B", author="ann", tags=["x"])
        self.add(title="C", author="bob", tags=["x", "y"])
        answer = self.client.get("/books?tag=x&tag=y")
        self.assertEqual([b["title"] for b in answer.json()["items"]], ["A", "C"])
        self.assertEqual(answer.headers["x-total-count"], "2")
        paged = self.client.get("/books?author=ann&limit=1&offset=1").json()
        self.assertEqual(([b["title"] for b in paged["items"]], paged["total"]), (["B"], 2))
        self.assertEqual(self.client.get("/books?limit=0").status, 400)

    def test_replace_and_delete(self):
        self.add(title="A", author="ann")
        replaced = self.client.put("/books/1", json={"title": "B", "author": "bob"}, headers=AUTH)
        self.assertEqual((replaced.status, replaced.json()["title"]), (200, "B"))
        deleted = self.client.delete("/books/1", headers=AUTH)
        self.assertEqual((deleted.status, deleted.body), (204, b""))
        self.assertEqual(self.client.delete("/books/1", headers=AUTH).status, 404)

    def test_every_answer_carries_a_request_id(self):
        self.assertTrue(self.client.get("/health").headers["x-request-id"].startswith("req-"))
        self.assertTrue(self.client.get("/nope").headers["x-request-id"].startswith("req-"))
        self.assertTrue(self.client.post("/books").headers["x-request-id"].startswith("req-"))
        echoed = self.client.get("/health", headers={"X-Request-Id": "abc-1"})
        self.assertEqual(echoed.headers["x-request-id"], "abc-1")


if __name__ == "__main__":
    unittest.main()
