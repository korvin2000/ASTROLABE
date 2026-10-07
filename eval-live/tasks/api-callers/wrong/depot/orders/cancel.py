"""Cancelling a placed order gives its reservations back."""

from depot.orders.model import CANCELLED, PLACED


def cancel(inventory, order):
    if order.status != PLACED:
        raise ValueError(f"order {order.id} is {order.status}; only a placed order can be cancelled")
    for reservation in order.reservations:
        inventory.release(reservation)
    order.reservations = []
    order.move(CANCELLED)
