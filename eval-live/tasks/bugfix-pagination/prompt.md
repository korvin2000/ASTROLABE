The catalog listing cannot show its last page when that page is only partly filled: with 23 products and 10 per
page, opening page 3 fails with "page 3 is out of range 1..2", and the header of the listing says "Page 1 of 2".
`python repro.py` reproduces it. Fix the bug so that every page of the listing can be opened and the header shows the
right number of pages. Keep the existing tests passing and add a test for this case.
