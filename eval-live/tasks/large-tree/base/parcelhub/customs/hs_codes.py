HS_CODES = {"books": "4901", "toys": "9503", "clothing": "6109", "electronics": "8517"}


def lookup(kind):
    try:
        return HS_CODES[kind]
    except KeyError:
        raise KeyError("no customs code for %r" % kind) from None
