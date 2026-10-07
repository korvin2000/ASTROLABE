"""The notes, kept in memory and saved to a JSON file when one is given."""

import json
import os
import threading

from notes.model import Note


class NoteStore:
    def __init__(self, path=None):
        self._path = path
        self._lock = threading.Lock()
        self._notes = {}
        self._next = 1
        if path and os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            self._notes = {n["id"]: Note.from_json(n) for n in data["notes"]}
            self._next = data["next"]

    def add(self, title, body="", tags=()):
        with self._lock:
            note = Note(self._next, title.strip(), body, tuple(sorted(set(tags))))
            note.validate()
            self._notes[note.id] = note
            self._next += 1
            self._save()
            return note

    def get(self, note_id):
        return self._notes.get(note_id)

    def all(self):
        return [self._notes[k] for k in sorted(self._notes)]

    def delete(self, note_id):
        with self._lock:
            removed = self._notes.pop(note_id, None) is not None
            if removed:
                self._save()
            return removed

    def _save(self):
        if not self._path:
            return
        tmp = self._path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"next": self._next, "notes": [n.to_json() for n in self.all()]}, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self._path)
