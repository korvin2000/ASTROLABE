"""Fulfilment: a placed order's reservations are shipped."""

from depot.orders.model import PLACED, SHIPPED


def fulfil(inventory, order):
    if order.status != PLACED:
        raise ValueError(f"order {order.id} is {order.status}; only a placed order ships")
    for reservation in order.reservations:
        inventory.ship(reservation)
    order.reservations = []
    order.move(SHIPPED)
