import unittest

from pay.router import Router, dispatch, normalize_currency

# `test_smoke` also exists in tests/test_handlers.py: two modules, one test
# name, two distinct namespaced identities (D-27, IX-13).


class RouterTest(unittest.TestCase):
    def test_smoke(self):
        self.assertEqual(dispatch({"path": "/user", "user_id": 7})["status"], 200)

    def test_unknown_path_is_404(self):
        self.assertEqual(dispatch({"path": "/nope"})["status"], 404)

    def test_router_lists_its_paths(self):
        self.assertEqual(Router().paths(), ["/user"])


CURRENCY_CASES = (("EUR", "eur"), ("Usd", "usd"), (" gbp ", "gbp"))


class NormalizeCurrencyTest(unittest.TestCase):
    """Both parameterization shapes the shaping parsers must read (D-27).

    `test_normalize_currency` reports one name with three sub-cases; the
    generated `test_normalize_currency_<code>` methods report three names.
    """

    def test_normalize_currency(self):
        for raw, expected in CURRENCY_CASES:
            with self.subTest(currency=raw):
                self.assertEqual(normalize_currency(raw), expected)


def _currency_case(raw, expected):
    def case(self):
        self.assertEqual(normalize_currency(raw), expected)

    return case


for _raw, _expected in CURRENCY_CASES:
    setattr(
        NormalizeCurrencyTest,
        "test_normalize_currency_" + _expected,
        _currency_case(_raw, _expected),
    )


if __name__ == "__main__":
    unittest.main()
