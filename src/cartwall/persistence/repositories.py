"""Repositories: domain objects in, domain objects out, rows in the middle.

Everything above this line works with stations and orders. Everything below it
works with rows. A repository is the only place both vocabularies appear, and
callers get no session, no query and no row.

Two habits worth keeping.

**Reads are ordered.** Every list comes back in a stated order, always. An
unordered read is a test that passes on SQLite and fails on Postgres eighteen
months later, and the failure looks like a business rule breaking rather than
like a missing ORDER BY.

**Writes replace rather than patch.** Saving an order deletes the lines that
are gone and writes what is there now. Patching means working out a diff at
every call site, and the call sites will get it wrong. Orders are small, and
the tables that are not small do not go through here.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import delete, select
from sqlalchemy.orm import Session, selectinload

from ..domain.account import AccountBook
from ..domain.order import Order
from ..domain.programming import ClockGrid
from ..domain.ratecard import RateCard, RateCardBook
from ..domain.spot import Cut, CutLibrary
from ..domain.station import Cluster, Station
from ..errors import LookupFailure
from . import mappers
from .models.accounts import (
    AdvertiserRow,
    AgencyRow,
    CutRow,
    RateCardRow,
)
from .models.reference import (
    AssignmentRow,
    ClockRow,
    DaypartRow,
    StationRow,
)
from .models.trading import LineRow, OrderRow


class StationRepository:
    """Signals, dayparts and programming grids."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def save_cluster(self, cluster: Cluster) -> None:
        for row in mappers.cluster_rows(cluster):
            self.session.merge(row)

    def cluster(self) -> Cluster:
        rows = self.session.scalars(
            select(StationRow).order_by(StationRow.carries.is_not(None), StationRow.call)
        ).all()
        return mappers.cluster_from(list(rows))

    def station(self, call: str) -> Station:
        row = self.session.get(StationRow, call)
        if row is None:
            raise LookupFailure(f"{call} is not on file", call=call)
        return mappers.station_from(row)

    def save_dayparts(self, call: str, grid) -> None:
        self.session.execute(delete(DaypartRow).where(DaypartRow.station == call))
        for daypart in grid.dayparts:
            self.session.add(mappers.daypart_row(daypart, call))

    def dayparts(self, call: str):
        rows = self.session.scalars(
            select(DaypartRow).where(DaypartRow.station == call).order_by(DaypartRow.code)
        ).all()
        return mappers.daypart_grid_from(list(rows))

    def save_grid(self, grid: ClockGrid) -> None:
        self.session.execute(
            delete(AssignmentRow).where(
                AssignmentRow.station == grid.station,
                AssignmentRow.effective == grid.effective,
            )
        )
        for clock in grid.clocks.values():
            existing = self.session.scalars(
                select(ClockRow).where(
                    ClockRow.station == grid.station,
                    ClockRow.code == clock.code,
                    ClockRow.effective == grid.effective,
                )
            ).first()
            if existing is not None:
                self.session.delete(existing)
                self.session.flush()
            self.session.add(mappers.clock_row(clock, grid.station, grid.effective))
        for row in mappers.assignment_rows(grid):
            self.session.add(row)

    def grid(self, call: str, effective: date) -> ClockGrid:
        clocks = self.session.scalars(
            select(ClockRow)
            .options(selectinload(ClockRow.breaks))
            .where(ClockRow.station == call, ClockRow.effective == effective)
            .order_by(ClockRow.code)
        ).all()
        assignments = self.session.scalars(
            select(AssignmentRow)
            .where(AssignmentRow.station == call, AssignmentRow.effective == effective)
            .order_by(AssignmentRow.ordinal)
        ).all()
        if not clocks and not assignments:
            raise LookupFailure(f"{call} has no grid effective {effective}", call=call)
        return mappers.clock_grid_from(call, effective, list(clocks), list(assignments))


class AccountRepository:
    """Advertisers, agencies and the copy library."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def save_book(self, book: AccountBook) -> None:
        for agency in book.agencies.values():
            self.session.merge(mappers.agency_row(agency))
        self.session.flush()
        for advertiser in book.advertisers.values():
            row = self.session.get(AdvertiserRow, advertiser.code)
            if row is not None:
                self.session.delete(row)
                self.session.flush()
        for advertiser in sorted(
            book.advertisers.values(), key=lambda a: (a.parent is not None, a.code)
        ):
            self.session.add(mappers.advertiser_row(advertiser))

    def book(self) -> AccountBook:
        agencies = self.session.scalars(select(AgencyRow).order_by(AgencyRow.code)).all()
        advertisers = self.session.scalars(
            select(AdvertiserRow)
            .options(selectinload(AdvertiserRow.categories))
            .order_by(AdvertiserRow.parent.is_not(None), AdvertiserRow.code)
        ).all()
        return mappers.account_book_from(list(agencies), list(advertisers))

    def file_cut(self, cut: Cut) -> None:
        self.session.merge(mappers.cut_row(cut))

    def cut(self, cart: str) -> Cut:
        row = self.session.get(CutRow, cart)
        if row is None:
            raise LookupFailure(f"cart {cart} is not in the library", cart=cart)
        return mappers.cut_from(row)

    def library(self, advertiser: str | None = None) -> CutLibrary:
        query = select(CutRow).order_by(CutRow.cart)
        if advertiser is not None:
            query = query.where(CutRow.advertiser == advertiser)
        return mappers.cut_library_from(list(self.session.scalars(query).all()))


class RateCardRepository:
    """Every card ever filed."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def file(self, card: RateCard) -> None:
        existing = self.session.scalars(
            select(RateCardRow).where(
                RateCardRow.station == card.station, RateCardRow.effective == card.effective
            )
        ).first()
        if existing is not None:
            self.session.delete(existing)
            self.session.flush()
        self.session.add(mappers.rate_card_row(card))

    def card(self, station: str, effective: date) -> RateCard:
        row = self.session.scalars(
            select(RateCardRow)
            .options(selectinload(RateCardRow.quotes))
            .where(RateCardRow.station == station, RateCardRow.effective == effective)
        ).first()
        if row is None:
            raise LookupFailure(f"{station} has no card effective {effective}", station=station)
        return mappers.rate_card_from(row)

    def book(self) -> RateCardBook:
        rows = self.session.scalars(
            select(RateCardRow)
            .options(selectinload(RateCardRow.quotes))
            .order_by(RateCardRow.station, RateCardRow.effective)
        ).all()
        return mappers.rate_card_book_from(list(rows))


class OrderRepository:
    """Orders and the lines on them."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def save(self, order: Order, cards: dict[int, str] | None = None) -> None:
        existing = self.session.get(OrderRow, order.number)
        if existing is not None:
            self.session.delete(existing)
            self.session.flush()
        self.session.add(mappers.order_row(order, cards))

    def get(self, number: str) -> Order:
        row = self._row(number)
        if row is None:
            raise LookupFailure(f"order {number} is not on file", order=number)
        return mappers.order_from(row)

    def _row(self, number: str) -> OrderRow | None:
        return self.session.scalars(
            select(OrderRow)
            .options(selectinload(OrderRow.lines).selectinload(LineRow.rotation))
            .where(OrderRow.number == number)
        ).first()

    def exists(self, number: str) -> bool:
        return self.session.get(OrderRow, number) is not None

    def for_advertiser(self, advertiser: str) -> list[Order]:
        rows = self.session.scalars(
            select(OrderRow)
            .options(selectinload(OrderRow.lines).selectinload(LineRow.rotation))
            .where(OrderRow.advertiser == advertiser)
            .order_by(OrderRow.number)
        ).all()
        return [mappers.order_from(row) for row in rows]

    def running_on(self, day: date) -> list[Order]:
        """Every order with a line whose flight covers ``day``, by number."""
        numbers = self.session.scalars(
            select(LineRow.order_number)
            .where(LineRow.first_air <= day, LineRow.last_air >= day)
            .distinct()
        ).all()
        return sorted(
            (self.get(number) for number in numbers),
            key=lambda order: order.number,
        )

    def delete(self, number: str) -> None:
        row = self.session.get(OrderRow, number)
        if row is None:
            raise LookupFailure(f"order {number} is not on file", order=number)
        self.session.delete(row)
