def round_up_to_five(cents):
    """Prices end in 0 or 5 cents: round up to the next multiple of five."""
    return -(-cents // 5) * 5
