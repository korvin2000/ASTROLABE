"""Unit conversions used by the weather station."""

FACTORS_TO_METRES = {"m": 1.0, "km": 1000.0, "ft": 0.3048, "mi": 1609.344}


def celsius_to_fahrenheit(celsius):
    return celsius * 9 / 5 + 32


def fahrenheit_to_celsius(fahrenheit):
    return (fahrenheit - 32) * 5 / 9


def convert_length(value, unit_from, unit_to):
    """`value` in `unit_from` expressed in `unit_to`; units are m, km, ft and mi."""
    for unit in (unit_from, unit_to):
        if unit not in FACTORS_TO_METRES:
            raise ValueError(f"unknown unit {unit!r}")
    return value * FACTORS_TO_METRES[unit_from] / FACTORS_TO_METRES[unit_to]
