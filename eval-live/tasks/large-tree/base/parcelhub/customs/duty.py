DUTY_FREE_BELOW_CENTS = 15000
DUTY_PERCENT = 12


def duty_cents(value_cents, country):
    """Duty on goods sent to a country outside the EU: twelve percent of the value, none below 150.00."""
    if country in ("DE", "AT", "FR", "NL", "PL", "ES", "IT") or value_cents < DUTY_FREE_BELOW_CENTS:
        return 0
    return value_cents * DUTY_PERCENT // 100
