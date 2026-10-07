"""A note: a title, a Markdown body and tags."""

from dataclasses import dataclass

MAX_TITLE = 120


@dataclass(frozen=True)
class Note:
    id: int
    title: str
    # Markdown source, as the user wrote it.
    body: str = ""
    tags: tuple = ()

    def validate(self):
        if not self.title:
            raise ValueError("a note needs a title")
        if len(self.title) > MAX_TITLE:
            raise ValueError(f"a title has at most {MAX_TITLE} characters")

    def to_json(self):
        return {"id": self.id, "title": self.title, "body": self.body, "tags": list(self.tags)}

    @staticmethod
    def from_json(data):
        return Note(int(data["id"]), data["title"], data.get("body", ""), tuple(data.get("tags", ())))
