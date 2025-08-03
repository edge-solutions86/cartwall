"""Reading and writing the makegood ledger.

The ledger in :mod:`cartwall.services.makegood` is a value object. It knows the
rules and it knows nothing about a database, which is the right split until
somebody wants last month's open debt after the process has restarted.

This module is the join, and it does not follow the replace-everything habit
the order repository uses. An order is one document owned by one person and
rewriting it whole is honest. The makegood ledger is not: debts are raised by
the nightly posting run, offers are made by a seller on the phone, and answers
come back days later. Loading the whole ledger, changing one offer and writing
it all back would silently undo whatever landed in between.

So the writes here are narrow on purpose. Raise one debt, make one offer,
settle one offer. The only bulk operation is expiring offers whose air date has
gone by, and that one is a bulk operation in the domain too.

**Raising the same debt twice is not an error.** The posting run is re-run by
hand more often than anybody admits, usually because the first as-run file was
the wrong day. A second raise of a debt already on file returns the row that is
there, so re-posting a night does not double what a client is owed.

That is why :meth:`MakegoodRepository.raise_all` exists and why callers should
use it rather than looping over :meth:`raise_debt`. A gather can hand back
several debts that are identical in every column, because a line short four
spots in a week is four debts with nothing to tell them apart. ``raise_all``
numbers them as it walks, so four stay four, and gathering the same span again
numbers them the same way and changes nothing.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from ..money import Money
from ..services.makegood import (
    Debt,
    Ledger,
    Makegood,
    MakegoodError,
    NotOwed,
    Offer,
    offer,
)
from .models.makegoods import DebtRow, OfferRow


def debt_from(row: DebtRow) -> Debt:
    return Debt(
        order=row.order_number,
        line=row.line,
        advertiser=row.advertiser,
        station=row.station,
        daypart=row.daypart,
        grade=row.grade,
        length=row.length,
        rate=row.rate,
        day=row.day,
        cause=row.cause,
    )


def offer_from(row: OfferRow) -> Makegood:
    return Makegood(
        debt=debt_from(row.debt),
        day=row.day,
        daypart=row.daypart,
        rate=row.rate,
        state=row.state,
        short=row.short,
    )


class MakegoodRepository:
    """One debt at a time, one offer at a time."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def raise_debt(self, debt: Debt, sequence: int = 1) -> DebtRow:
        """Put a debt on file. Raising one that is already there is a no-op.

        ``sequence`` tells identical debts apart. Leave it alone unless you are
        raising several at once, and then prefer :meth:`raise_all`.
        """
        found = self._find(debt, sequence)
        if found is not None:
            return found
        row = DebtRow(
            order_number=debt.order,
            line=debt.line,
            advertiser=debt.advertiser,
            station=debt.station,
            daypart=debt.daypart,
            grade=debt.grade,
            length=debt.length,
            rate=debt.rate,
            day=debt.day,
            cause=debt.cause,
            sequence=sequence,
        )
        self.session.add(row)
        self.session.flush()
        return row

    def raise_all(self, debts: list[Debt]) -> int:
        """The nightly run's entry point. Answers how many were new.

        Identical debts are numbered in the order they arrive, so a line owed
        four spots gets four rows rather than one.
        """
        before = self.count()
        seen: dict[tuple[str, int, date, str], int] = {}
        for debt in debts:
            key = (debt.order, debt.line, debt.day, debt.cause.value)
            seen[key] = seen.get(key, 0) + 1
            self.raise_debt(debt, seen[key])
        return self.count() - before

    def propose(
        self, debt: Debt, day: date, daypart: str, rate: Money, sequence: int = 1
    ) -> OfferRow:
        """Offer one spot back against one of the spots owed on a debt."""
        found = self._find(debt, sequence)
        if found is None:
            raise NotOwed(
                f"nothing is owed on {debt.order}-{debt.line} for {debt.day}",
                order=debt.order,
                line=debt.line,
            )
        if any(not row.state.is_settled for row in found.offers):
            raise MakegoodError(
                f"{debt.order}-{debt.line} already has an offer open for {debt.day}",
                order=debt.order,
                line=debt.line,
            )
        made = offer(debt, day, daypart, rate)
        row = OfferRow(
            debt=found,
            day=made.day,
            daypart=made.daypart,
            rate=made.rate,
            state=made.state,
            short=made.short,
        )
        self.session.add(row)
        self.session.flush()
        return row

    def settle(self, offer_id: int, state: Offer) -> OfferRow:
        """Record the client's answer against one offer."""
        row = self.session.get(OfferRow, offer_id)
        if row is None:
            raise NotOwed(f"there is no offer {offer_id}", offer=offer_id)
        if row.state.is_settled:
            raise MakegoodError(f"that offer was already {row.state.value}", state=row.state.value)
        if state is Offer.OPEN:
            raise MakegoodError("an offer cannot be settled as open")
        row.state = state
        self.session.flush()
        return row

    def expire_before(self, day: date) -> list[OfferRow]:
        """Withdraw every open offer whose air date has gone by."""
        rows = list(
            self.session.scalars(
                select(OfferRow)
                .where(OfferRow.state == Offer.OPEN, OfferRow.day < day)
                .order_by(OfferRow.day, OfferRow.id)
            )
        )
        for row in rows:
            row.state = Offer.EXPIRED
        self.session.flush()
        return rows

    def count(self) -> int:
        return len(list(self.session.scalars(select(DebtRow.id))))

    def ledger(self, order: str | None = None, station: str | None = None) -> Ledger:
        """The whole ledger, or one order's, or one station's.

        Read once with the offers eagerly joined. The report walks every debt
        looking for an accepted offer against it, and letting that lazy load per
        debt turns a month end report into a few thousand queries.
        """
        query = select(DebtRow).options(selectinload(DebtRow.offers))
        if order is not None:
            query = query.where(DebtRow.order_number == order)
        if station is not None:
            query = query.where(DebtRow.station == station)
        rows = list(
            self.session.scalars(
                query.order_by(DebtRow.day, DebtRow.order_number, DebtRow.line, DebtRow.id)
            )
        )
        built = Ledger()
        for row in rows:
            built.owe(
                debt_from(row),
                [offer_from(made) for made in sorted(row.offers, key=lambda one: one.id)],
            )
        return built

    def open_offers(self, order: str | None = None) -> list[OfferRow]:
        """Offers nobody has answered yet, soonest air date first."""
        query = (
            select(OfferRow)
            .join(DebtRow)
            .options(selectinload(OfferRow.debt))
            .where(OfferRow.state == Offer.OPEN)
        )
        if order is not None:
            query = query.where(DebtRow.order_number == order)
        return list(self.session.scalars(query.order_by(OfferRow.day, OfferRow.id)))

    def owed_between(self, first: date, last: date, station: str | None = None) -> list[DebtRow]:
        """Debts raised in a span, whether or not anything was offered back."""
        query = select(DebtRow).where(DebtRow.day >= first, DebtRow.day <= last)
        if station is not None:
            query = query.where(DebtRow.station == station)
        return list(
            self.session.scalars(query.order_by(DebtRow.day, DebtRow.order_number, DebtRow.line))
        )

    def _find(self, debt: Debt, sequence: int = 1) -> DebtRow | None:
        return self.session.scalars(
            select(DebtRow)
            .options(selectinload(DebtRow.offers))
            .where(
                DebtRow.order_number == debt.order,
                DebtRow.line == debt.line,
                DebtRow.day == debt.day,
                DebtRow.cause == debt.cause,
                DebtRow.sequence == sequence,
            )
        ).first()
