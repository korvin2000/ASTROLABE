import unittest


class RedProbe(unittest.TestCase):
    def test_fails_on_purpose(self):
        self.assertEqual(1 + 1, 3, "this test fails on purpose: the launcher must report it")
