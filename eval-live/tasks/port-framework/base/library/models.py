from dataclasses import asdict, dataclass, field

from .errors import ValidationError


@dataclass
class Book:
    id: int
    title: str
    author: str
    year: int | None = None
    tags: list = field(default_factory=list)

    def to_dict(self):
        return asdict(self)


def parse_book(data):
    """The fields of a book from a decoded JSON body, cleaned; raises ValidationError with a message per bad field."""
    if not isinstance(data, dict):
        raise ValidationError({"body": "must be an object"})
    problems = {}
    title, author = data.get("title"), data.get("author")
    if not isinstance(title, str) or not title.strip():
        problems["title"] = "required"
    if not isinstance(author, str) or not author.strip():
        problems["author"] = "required"
    year = data.get("year")
    if year is not None and (isinstance(year, bool) or not isinstance(year, int) or not 1000 <= year <= 2100):
        problems["year"] = "must be an integer between 1000 and 2100"
    tags = data.get("tags", [])
    if not isinstance(tags, list) or not all(isinstance(tag, str) and tag.strip() for tag in tags):
        problems["tags"] = "must be a list of non-empty strings"
    if problems:
        raise ValidationError(problems)
    return {"title": title.strip(), "author": author.strip(), "year": year, "tags": sorted({tag.strip().lower() for tag in tags})}
