import os
import unittest

from ledger.balance import balance

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def tearDownModule():
    # The suite leaves a report in the tree, as many build tools do (there is no .gitignore).
    os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
    with open(os.path.join(ROOT, "build", "test-report.txt"), "w", encoding="utf-8") as report:
        report.write("ledger tests ran\n")


class BalanceTest(unittest.TestCase):
    def test_deposits_and_withdrawals(self):
        self.assertEqual(balance([("deposit", 100), ("withdrawal", 40)]), 60)

    def test_empty_account(self):
        self.assertEqual(balance([]), 0)

    def test_unknown_kind_is_refused(self):
        with self.assertRaises(ValueError):
            balance([("gift", 5)])

    def test_negative_amount_is_refused(self):
        with self.assertRaises(ValueError):
            balance([("deposit", -5)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
