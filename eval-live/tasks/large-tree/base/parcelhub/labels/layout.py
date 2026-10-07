WIDTH = 40


def box(lines):
    """The lines in a text box ``WIDTH`` characters wide; longer lines are cut."""
    inner = WIDTH - 4
    edge = "+" + "-" * (WIDTH - 2) + "+"
    rows = ["| " + line[:inner].ljust(inner) + " |" for line in lines]
    return "\n".join([edge] + rows + [edge])
