import os
import sys
import unittest

MARK = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_interpreter.txt")


class InterpreterProbe(unittest.TestCase):
    def test_records_the_interpreter(self):
        with open(MARK, "a", encoding="utf-8") as f:
            f.write(sys.prefix + "\n")
