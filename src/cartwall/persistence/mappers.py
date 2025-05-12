"""Domain objects to rows and back, in one place and in both directions.

The domain has no idea the database exists and the rows have no behaviour. That
is the whole point of keeping them apart, and the cost of it is this module.

Two rules it holds itself to.

**Every mapping is a pair.** A function that writes a row without one that
reads it back is how a field quietly stops being persisted: the writer keeps
working, the reader keeps returning the default, and nobody notices until a
report is wrong six weeks later. The round trip tests are the check on that and
they compare whole objects rather than named fields, so a new field that is not
mapped fails them.

**Nothing here validates.** Validation belongs to the domain constructors and
they run on the way out of this module, which means a row that has gone bad in
the database raises the same error as a bad keystroke on order entry. That is
correct: both mean the same thing to whoever has to fix it.
"""

from __future__ import annotations

from cartwall.domain.account import (
    AccountBook,
    Advertiser,
    Agency,
    Category,
)
from cartwall.domain.clock import Break, Clock
from cartwall.domain.daypart import Daypart, DaypartGrid, parse_days, show_days
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.programming import Assignment, ClockGrid
from cartwall.domain.ratecard import Quote, RateCard, RateCardBook
from cartwall.domain.rotation import Rotation, RotationKind, Slice
from cartwall.domain.spot import Cut, CutLibrary
from cartwall.domain.station import Cluster, Station

from .models.accounts import (
    AdvertiserRow,
    AgencyRow,
    CategoryRow,
    CutRow,
    QuoteRow,
    RateCardRow,
)
from .models.reference import (
    AssignmentRow,
    BreakRow,
    ClockRow,
    DaypartRow,
    StationRow,
)
from .models.trading import LineRow, OrderRow, RotationRow


def station_row(station: Station, market: str) -> StationRow:
    return StationRow(
        call=station.call,
        band=station.band,
        frequency=station.frequency,
        city_of_license=station.city_of_license,
        service=station.service,
        market=market,
        daytimer=station.daytimer,
        carries=station.carries,
        commercial_load=station.commercial_load,
    )


def station_from(row: StationRow) -> Station:
    return Station(
        call=row.call,
        band=row.band,
        frequency=row.frequency,
        city_of_license=row.city_of_license,
        service=row.service,
        daytimer=row.daytimer,
        carries=row.carries,
        commercial_load=row.commercial_load,
    )


def cluster_rows(cluster: Cluster) -> list[StationRow]:
    return [station_row(station, cluster.market) for station in cluster.stations]


def cluster_from(rows: list[StationRow]) -> Cluster:
    """Rows in, cluster out, in the order given.

    The order is not load bearing: the cluster resolves a simulcast by looking
    the parent up rather than by relying on it coming first. Keeping the given
    order means a round trip compares equal, which is what the tests need.
    """
    if not rows:
        return Cluster(market="")
    return Cluster(
        market=rows[0].market,
        stations=tuple(station_from(row) for row in rows),
    )


def daypart_row(daypart: Daypart, station: str) -> DaypartRow:
    return DaypartRow(
        station=station,
        code=daypart.code,
        name=daypart.name,
        days=show_days(daypart.days),
        first_hour=daypart.first_hour,
        last_hour=daypart.last_hour,
    )


def daypart_from(row: DaypartRow) -> Daypart:
    return Daypart(
        code=row.code,
        name=row.name,
        days=parse_days(row.days),
        first_hour=row.first_hour,
        last_hour=row.last_hour,
    )


def daypart_grid_from(rows: list[DaypartRow]) -> DaypartGrid:
    return DaypartGrid(tuple(daypart_from(row) for row in rows))


def break_row(brk: Break) -> BreakRow:
    return BreakRow(
        number=brk.number,
        kind=brk.kind,
        position=brk.position,
        length=brk.length,
        units=brk.units,
        paid=brk.paid,
    )


def break_from(row: BreakRow) -> Break:
    return Break(
        position=row.position,
        number=row.number,
        kind=row.kind,
        length=row.length,
        units=row.units,
        paid=row.paid,
    )


def clock_row(clock: Clock, station: str, effective) -> ClockRow:
    row = ClockRow(station=station, code=clock.code, name=clock.name, effective=effective)
    row.breaks = [break_row(brk) for brk in clock.breaks]
    return row


def clock_from(row: ClockRow) -> Clock:
    return Clock.build(row.code, row.name, [break_from(brk) for brk in row.breaks])


def assignment_rows(grid: ClockGrid) -> list[AssignmentRow]:
    """The stack, with its order written down. Ordinal is load bearing."""
    return [
        AssignmentRow(
            station=grid.station,
            effective=grid.effective,
            ordinal=index,
            label=assignment.label,
            days=show_days(assignment.days),
            first_hour=assignment.first_hour,
            last_hour=assignment.last_hour,
            clock_code=assignment.clock,
            source=assignment.source,
        )
        for index, assignment in enumerate(grid.assignments)
    ]


def assignment_from(row: AssignmentRow) -> Assignment:
    return Assignment(
        label=row.label,
        days=parse_days(row.days),
        first_hour=row.first_hour,
        last_hour=row.last_hour,
        clock=row.clock_code,
        source=row.source,
    )


def clock_grid_from(
    station: str, effective, clocks: list[ClockRow], assignments: list[AssignmentRow]
) -> ClockGrid:
    grid = ClockGrid(station, effective)
    for clock in clocks:
        grid.file_clock(clock_from(clock))
    for assignment in sorted(assignments, key=lambda row: row.ordinal):
        grid.assign(assignment_from(assignment))
    return grid


def agency_row(agency: Agency) -> AgencyRow:
    return AgencyRow(
        code=agency.code,
        name=agency.name,
        rate=agency.rate,
        advertiser_liable=agency.advertiser_liable,
    )


def agency_from(row: AgencyRow) -> Agency:
    return Agency(
        code=row.code,
        name=row.name,
        rate=row.rate,
        advertiser_liable=row.advertiser_liable,
    )


def advertiser_row(advertiser: Advertiser) -> AdvertiserRow:
    row = AdvertiserRow(
        code=advertiser.code,
        name=advertiser.name,
        agency=advertiser.agency,
        parent=advertiser.parent,
        credit=advertiser.credit,
    )
    row.categories = [
        CategoryRow(path=code.path, root=code.parts[0])
        for code in sorted(advertiser.categories)
    ]
    return row


def advertiser_from(row: AdvertiserRow) -> Advertiser:
    return Advertiser(
        code=row.code,
        name=row.name,
        categories=frozenset(Category(entry.path) for entry in row.categories),
        agency=row.agency,
        credit=row.credit,
        parent=row.parent,
    )


def account_book_from(
    agencies: list[AgencyRow], advertisers: list[AdvertiserRow]
) -> AccountBook:
    """Parents before children, so the book's own checks can run."""
    book = AccountBook()
    for agency in agencies:
        book.add_agency(agency_from(agency))
    ordered = sorted(advertisers, key=lambda row: (row.parent is not None, row.code))
    for advertiser in ordered:
        book.add_advertiser(advertiser_from(advertiser))
    return book


def cut_row(cut: Cut) -> CutRow:
    return CutRow(
        cart=cut.cart,
        isci=cut.isci,
        advertiser=cut.advertiser,
        length=cut.length,
        actual=cut.actual,
        first_air=cut.first_air,
        last_air=cut.last_air,
        killed=cut.killed,
    )


def cut_from(row: CutRow) -> Cut:
    return Cut(
        cart=row.cart,
        isci=row.isci,
        advertiser=row.advertiser,
        length=row.length,
        actual=row.actual,
        first_air=row.first_air,
        last_air=row.last_air,
        killed=row.killed,
    )


def cut_library_from(rows: list[CutRow]) -> CutLibrary:
    library = CutLibrary()
    for row in rows:
        library.file(cut_from(row))
    return library


def rate_card_row(card: RateCard) -> RateCardRow:
    row = RateCardRow(code=card.code, station=card.station, effective=card.effective)
    row.quotes = [
        QuoteRow(daypart=quote.daypart, grade=quote.grade, thirty=quote.thirty)
        for quote in card.quotes
    ]
    return row


def rate_card_from(row: RateCardRow) -> RateCard:
    return RateCard(
        code=row.code,
        station=row.station,
        effective=row.effective,
        quotes=tuple(
            Quote(quote.daypart, quote.grade, quote.thirty) for quote in row.quotes
        ),
    )


def rate_card_book_from(rows: list[RateCardRow]) -> RateCardBook:
    book = RateCardBook()
    for row in rows:
        book.file(rate_card_from(row))
    return book


def line_row(line: Line, card_code: str = "", rotation: Rotation | None = None) -> LineRow:
    row = LineRow(
        number=line.number,
        station=line.station,
        daypart=line.daypart,
        grade=line.grade,
        length=line.length,
        rate=line.rate,
        card_code=card_code,
        first_air=line.flight.first,
        last_air=line.flight.last,
        days=show_days(line.flight.days),
        per_week=line.flight.per_week,
    )
    carts = rotation.slices if rotation else tuple(Slice(cart) for cart in line.carts)
    kind = rotation.kind if rotation else RotationKind.EVEN
    row.rotation = [
        RotationRow(position=index, cart=piece.cart, weight=piece.weight, kind=kind)
        for index, piece in enumerate(carts)
    ]
    return row


def line_from(row: LineRow) -> Line:
    return Line(
        number=row.number,
        station=row.station,
        daypart=row.daypart,
        grade=row.grade,
        length=row.length,
        rate=row.rate,
        flight=Flight(
            first=row.first_air,
            last=row.last_air,
            days=parse_days(row.days),
            per_week=row.per_week,
        ),
        carts=tuple(piece.cart for piece in row.rotation),
    )


def rotation_from(row: LineRow) -> Rotation | None:
    """None when the line has no copy dubbed against it yet."""
    if not row.rotation:
        return None
    kind = row.rotation[0].kind
    return Rotation(
        tuple(Slice(piece.cart, piece.weight) for piece in row.rotation),
        kind,
    )


def order_row(order: Order, cards: dict[int, str] | None = None) -> OrderRow:
    cards = cards or {}
    row = OrderRow(
        number=order.number,
        advertiser=order.advertiser,
        agency=order.agency,
        written=order.written,
        salesperson=order.salesperson,
        campaign=order.campaign,
        status=order.status,
    )
    row.lines = [line_row(line, cards.get(line.number, "")) for line in order.lines]
    return row


def order_from(row: OrderRow) -> Order:
    return Order(
        number=row.number,
        advertiser=row.advertiser,
        written=row.written,
        salesperson=row.salesperson,
        lines=tuple(line_from(line) for line in row.lines),
        status=row.status,
        agency=row.agency,
        campaign=row.campaign,
    )
