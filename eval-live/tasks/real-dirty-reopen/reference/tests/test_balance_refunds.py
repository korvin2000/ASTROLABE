import unittest

from ledger.balance import balance


class RefundTest(unittest.TestCase):
    def test_a_refund_counts_once(self):
        self.assertEqual(balance([("deposit", 100), ("refund", 30)]), 70)


if __name__ == "__main__":
    unittest.main(verbosity=2)
