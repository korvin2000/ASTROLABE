"""Finding notes by words and tags."""


def matches(note, words=(), tag=None):
    if tag is not None and tag not in note.tags:
        return False
    haystack = f"{note.title}\n{note.body}".lower()
    return all(word.lower() in haystack for word in words)


def search(notes, query="", tag=None):
    words = [w for w in query.split() if w]
    return [n for n in notes if matches(n, words, tag)]
