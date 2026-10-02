The monthly invoice report is wrong: the monthly figures do not add up to its total line (`python repro.py` shows it).
It looks like a floating-point rounding error somewhere in the pipeline. Find the cause and fix it. Keep the existing
tests passing and add a test that would have caught the problem.
