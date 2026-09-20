import unittest

from pay.handlers.user import handle_user

# `test_smoke` also exists in tests/test_router.py (D-27, IX-13).


class HandlersTest(unittest.TestCase):
    def test_smoke(self):
        self.assertEqual(handle_user({"user_id": 7})["status"], 200)

    def test_missing_user_id_is_400(self):
        self.assertEqual(handle_user({})["status"], 400)

    def test_pre_existing_failure(self):
        """Red before the harness touches anything (P1.7.5 baseline ledger).

        The failure signature must stay stable across runs, so it asserts on a
        literal rather than on anything host-dependent.
        """
        self.assertEqual(handle_user({})["body"], "user_id is optional")

    @unittest.skip("waiting on the pre-existing failure above")
    def test_skipped_until_fixed(self):
        self.fail("never executed while the skip marker is in place")


if __name__ == "__main__":
    unittest.main()
