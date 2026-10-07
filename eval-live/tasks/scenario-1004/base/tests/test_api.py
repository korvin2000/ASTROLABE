import io
import unittest

from notesclient.cli import main
from notesclient.client import NotesClient, NotFound
from tests.support import running


class ApiTest(unittest.TestCase):
    def test_round_trip(self):
        with running() as (store, url):
            client = NotesClient(url)
            note = client.add("Trip", "book the *train*", ["travel"])
            self.assertEqual(note["id"], 1)
            self.assertEqual(client.get(1)["body"], "book the *train*")
            self.assertEqual([n["title"] for n in client.list("train")], ["Trip"])
            client.delete(1)
            with self.assertRaises(NotFound):
                client.get(1)

    def test_bad_note_is_400(self):
        with running() as (store, url):
            with self.assertRaises(Exception) as raised:
                NotesClient(url).add("")
            self.assertEqual(getattr(raised.exception, "code", None), 400)

    def test_cli(self):
        with running() as (store, url):
            store.add("Trip", "book the *train*", ["travel"])
            out = io.StringIO()
            self.assertEqual(main(["--url", url, "list"], out), 0)
            self.assertEqual(out.getvalue(), "   1  Trip  #travel\n")
            out = io.StringIO()
            main(["--url", url, "show", "1"], out)
            self.assertEqual(out.getvalue(), "# Trip\n\nbook the *train*\n")
            out = io.StringIO()
            self.assertEqual(main(["--url", url, "show", "9"], out), 1)
            self.assertEqual(out.getvalue(), "no note 9\n")


if __name__ == "__main__":
    unittest.main()
