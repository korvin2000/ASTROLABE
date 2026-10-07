from .models import Book


class BookStore:
    """The catalogue in memory; ids are handed out in order and never reused."""

    def __init__(self):
        self._books = {}
        self._next = 1

    def add(self, title, author, year=None, tags=()):
        book = Book(self._next, title, author, year, list(tags))
        self._books[book.id] = book
        self._next += 1
        return book

    def get(self, book_id):
        return self._books.get(book_id)

    def replace(self, book_id, title, author, year=None, tags=()):
        if book_id not in self._books:
            return None
        book = Book(book_id, title, author, year, list(tags))
        self._books[book_id] = book
        return book

    def delete(self, book_id):
        return self._books.pop(book_id, None) is not None

    def search(self, author=None, tags=(), limit=20, offset=0):
        """``(page, total)``: the books by ``author`` (exactly) that carry every one of ``tags``, by id."""
        found = [
            book
            for book in sorted(self._books.values(), key=lambda b: b.id)
            if (author is None or book.author == author) and all(tag in book.tags for tag in tags)
        ]
        return found[offset:offset + limit], len(found)
