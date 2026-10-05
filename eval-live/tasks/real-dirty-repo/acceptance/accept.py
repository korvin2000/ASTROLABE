"""Hidden acceptance of real-dirty-repo: run from the root of a copy of the finished workspace."""

import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from ledger.balance import balance  # noqa: E402


class Balance(unittest.TestCase):
    def test_a_refund_counts_once(self):
        self.assertEqual(balance([("deposit", 100), ("refund", 30)]), 70)

    def test_refunds_and_withdrawals_mixed(self):
        entries = [("deposit", 200), ("withdrawal", 50), ("refund", 25), ("deposit", 10), ("refund", 5)]
        self.assertEqual(balance(entries), 130)

    def test_existing_behaviour_kept(self):
        self.assertEqual(balance([("deposit", 100), ("withdrawal", 40)]), 60)
        self.assertEqual(balance([]), 0)
        with self.assertRaises(ValueError):
            balance([("gift", 5)])
        with self.assertRaises(ValueError):
            balance([("refund", -1)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
