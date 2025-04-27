"""Order entry over HTTP.

The routes are thin. Everything they do is turn a body into a domain object,
hand it to a repository and turn the answer back into a body. The rules live in
the domain and the transaction lives in the dependency, and a route that starts
growing a rule is a route that has taken something from the wrong place.

Confirming and cancelling are their own routes rather than a PATCH of a status
field. They are events, not edits: confirming an order is what makes it
schedulable and cancelling it is what stops the log builder from touching it
again, and both are the sort of thing an audit trail eventually wants to see as
a distinct act. A generic PATCH would let a client set the status to anything
and would give the audit trail nothing to record.
"""

from __future__ import annotations

from fastapi import APIRouter, Query, Response, status

from ...domain.order import OrderError
from ...errors import ConflictError
from ...persistence.unitofwork import UnitOfWork
from ..deps import Work
from ..schemas import OrderIn, OrderOut

router = APIRouter(prefix="/orders", tags=["orders"])


@router.post("", response_model=OrderOut, status_code=status.HTTP_201_CREATED)
def write_order(body: OrderIn, uow: UnitOfWork = Work) -> OrderOut:
    """Write a new order. The number comes from the caller and must be free."""
    if uow.orders.exists(body.number):
        raise ConflictError(
            f"order {body.number} is already on file", order=body.number
        )
    order = body.to_domain()
    uow.orders.save(order)
    return OrderOut.of(order)


@router.get("/{number}", response_model=OrderOut)
def read_order(number: str, uow: UnitOfWork = Work) -> OrderOut:
    return OrderOut.of(uow.orders.get(number))


@router.put("/{number}", response_model=OrderOut)
def rewrite_order(number: str, body: OrderIn, uow: UnitOfWork = Work) -> OrderOut:
    """Replace an order that has not been confirmed yet."""
    if number != body.number:
        raise OrderError(
            f"the body says {body.number} and the path says {number}",
            path=number,
            body=body.number,
        )
    existing = uow.orders.get(number)
    if existing.status.may_schedule:
        raise ConflictError(
            f"order {number} is confirmed, cancel it rather than rewriting it",
            order=number,
        )
    order = body.to_domain()
    uow.orders.save(order)
    return OrderOut.of(order)


@router.post("/{number}/confirm", response_model=OrderOut)
def confirm_order(number: str, uow: UnitOfWork = Work) -> OrderOut:
    """Make an order schedulable. Refused if the advertiser is on hold."""
    order = uow.orders.get(number)
    if not uow.accounts.book().may_schedule(order.advertiser):
        raise ConflictError(
            f"{order.advertiser} is on credit hold",
            order=number,
            advertiser=order.advertiser,
        )
    confirmed = order.confirmed()
    uow.orders.save(confirmed)
    return OrderOut.of(confirmed)


@router.post("/{number}/cancel", response_model=OrderOut)
def cancel_order(number: str, uow: UnitOfWork = Work) -> OrderOut:
    cancelled = uow.orders.get(number).cancelled()
    uow.orders.save(cancelled)
    return OrderOut.of(cancelled)


@router.get("", response_model=list[OrderOut])
def list_orders(
    advertiser: str = Query(description="Account code the orders belong to"),
    uow: UnitOfWork = Work,
) -> list[OrderOut]:
    return [OrderOut.of(order) for order in uow.orders.for_advertiser(advertiser)]


@router.delete(
    "/{number}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
    # A None return annotation is a response model of NoneType as far as the
    # framework is concerned, and a 204 is not allowed to have one at all.
    response_model=None,
)
def delete_order(number: str, uow: UnitOfWork = Work) -> None:
    """Take an order off the file. Confirmed orders are cancelled, not deleted."""
    order = uow.orders.get(number)
    if order.status.may_schedule:
        raise ConflictError(
            f"order {number} is confirmed, cancel it rather than deleting it",
            order=number,
        )
    uow.orders.delete(number)
