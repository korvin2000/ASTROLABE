"""Hidden acceptance of large-tree: run from the root of a copy of the finished workspace.

Every place that treats a parcel as bulky must follow the new line (over 120 cm on the longest side); the postal
carrier's contract limit and the customs rule keep their 100 cm.
"""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from parcelhub.carriers import postal  # noqa: E402
from parcelhub.carriers.selection import choose, plan  # noqa: E402
from parcelhub.cli.commands import describe  # noqa: E402
from parcelhub.cli.formatting import flags  # noqa: E402
from parcelhub.customs import declaration_lines, needs_extra_form  # noqa: E402
from parcelhub.domain.address import Address  # noqa: E402
from parcelhub.domain.parcel import Parcel, SizeClass  # noqa: E402
from parcelhub.labels import render_label  # noqa: E402
from parcelhub.labels.warnings import warnings_for  # noqa: E402
from parcelhub.rating import quote  # noqa: E402
from parcelhub.reports import summarize  # noqa: E402
from parcelhub.reports.exceptions import needing_attention  # noqa: E402

# Longest sides in cm: never bulky / bulky before the change only / on the new line / just over it / far over.
NEVER, MOVED, LINE, OVER, FAR = 99.9, 110.0, 120.0, 120.1, 150.0


def p(longest_cm, weight_g=800, country="DE", id="P"):
    return Parcel(id, longest_cm, 30.0, 20.0, weight_g, Address(country, "10115"))


class TheDomain(unittest.TestCase):
    def test_bulky_is_over_120_cm_on_the_longest_side(self):
        for cm, bulky in ((NEVER, False), (MOVED, False), (LINE, False), (OVER, True), (FAR, True)):
            self.assertEqual(p(cm).is_bulky, bulky, "%s cm" % cm)

    def test_the_size_classes_follow_the_line(self):
        self.assertEqual(p(25).size_class, SizeClass.S)
        self.assertEqual(p(45).size_class, SizeClass.M)
        self.assertEqual(p(MOVED).size_class, SizeClass.L)
        self.assertEqual(p(LINE).size_class, SizeClass.L)
        self.assertEqual(p(OVER).size_class, SizeClass.XL)


class TheRateCard(unittest.TestCase):
    def test_the_bulky_surcharge_follows_the_line(self):
        for cm, bulky in ((NEVER, False), (MOVED, False), (LINE, False), (OVER, True), (FAR, True)):
            self.assertEqual("bulky" in quote(p(cm)).lines, bulky, "%s cm" % cm)

    def test_totals(self):
        self.assertEqual(quote(p(MOVED)).total_cents, 525)
        self.assertEqual(quote(p(FAR)).lines, {"base": 490, "bulky": 2500, "fuel": 34})
        self.assertEqual(quote(p(FAR)).total_cents, 3025)


class TheCarrierChoice(unittest.TestCase):
    def test_freight_is_for_the_bulky(self):
        for cm, carrier in ((MOVED, "courier"), (LINE, "courier"), (OVER, "freight"), (FAR, "freight")):
            self.assertEqual(choose(p(cm)), carrier, "%s cm" % cm)

    def test_a_heavy_parcel_below_the_line_goes_by_courier(self):
        self.assertEqual(choose(p(60, weight_g=9000)), "courier")

    def test_the_plan_groups_by_carrier(self):
        groups = plan([p(MOVED, id="A"), p(30, id="B"), p(OVER, id="C"), p(LINE, id="D")])
        self.assertEqual({name: [x.id for x in items] for name, items in groups.items()}, {"courier": ["A", "D"], "postal": ["B"], "freight": ["C"]})


class TheLabels(unittest.TestCase):
    def test_the_large_item_note_follows_the_line(self):
        for cm, noted in ((NEVER, False), (MOVED, False), (LINE, False), (OVER, True), (FAR, True)):
            notes = [n for n in warnings_for(p(cm)) if "LARGE ITEM" in n]
            self.assertEqual(bool(notes), noted, "%s cm" % cm)
            self.assertEqual("LARGE ITEM" in render_label(p(cm), "courier"), noted, "%s cm (rendered)" % cm)

    def test_the_heavy_note_is_untouched(self):
        self.assertEqual(warnings_for(p(30, weight_g=26000)), ["HEAVY - TWO PERSON LIFT"])


class TheReports(unittest.TestCase):
    def test_the_weekly_numbers_count_large_over_120(self):
        week = [p(100.5, id="a"), p(MOVED, id="b"), p(LINE, id="c"), p(OVER, id="d"), p(FAR, id="e"), p(30, 26000, id="f")]
        self.assertEqual(summarize(week), {"parcels": 6, "large": 2, "heavy": 1})

    def test_the_parcels_that_need_attention(self):
        week = [p(MOVED, id="b"), p(LINE, id="c"), p(OVER, id="d"), p(30, 26000, id="f")]
        self.assertEqual(needing_attention(week), ["d", "f"])

    def test_a_given_config_still_decides(self):
        self.assertEqual(summarize([p(50)], {"large_over_cm": 40, "heavy_over_kg": 25})["large"], 1)


class TheCommandLine(unittest.TestCase):
    def test_the_bulky_flag(self):
        self.assertEqual(flags(p(MOVED)), "")
        self.assertEqual(flags(p(OVER)), "B")
        self.assertTrue(describe(p(OVER)).rstrip().endswith("B"))
        self.assertTrue(describe(p(MOVED)).rstrip().endswith("-"))


class WhatDoesNotMove(unittest.TestCase):
    def test_the_postal_contract_keeps_its_100_cm(self):
        self.assertEqual(postal.POSTAL.max_edge_mm, 1000)
        self.assertTrue(postal.fits(p(100.0)))
        self.assertFalse(postal.fits(p(100.1)))
        self.assertEqual(choose(p(100.0)), "postal")
        self.assertEqual(choose(p(100.1)), "courier")

    def test_customs_keeps_its_100_cm(self):
        for cm, needed in ((NEVER, False), (100.1, True), (MOVED, True), (LINE, True)):
            self.assertEqual(needs_extra_form(p(cm)), needed, "%s cm" % cm)
        self.assertIn("FORM C-17", declaration_lines(p(MOVED, country="US"), "books", 5000))


if __name__ == "__main__":
    unittest.main(verbosity=2)
