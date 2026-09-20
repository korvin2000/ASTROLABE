import unittest

from pay.handlers.user import handle_user

# `test_smoke` also exists in tests/test_router.py (D-27, IX-13).


class HandlersTest(unittest.TestCase):
    def test_smoke(self):
        self.assertEqual(handle_user({"user_id": 7})["status"], 200)

    def test_missing_user_id_is_400(self):
        self.assertEqual(handle_user({})["status"], 400)


if __name__ == "__main__":
    unittest.main()
