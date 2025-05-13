"""Round trips, compared whole. A field that stops being mapped fails here."""

from datetime import date

import pytest

from cartwall.airtime import Duration
from cartwall.domain.account import Advertiser, Agency, Credit
from cartwall.domain.clock import music_clock, talk_clock
from cartwall.domain.daypart import standard_grid
from cartwall.domain.order import Flight, Line, Order, OrderStatus
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.rotation import Rotation, RotationKind
from cartwall.domain.spot import THIRTY, Cut, SpotLength
from cartwall.money import Money
from cartwall.persistence.mappers import (
    account_book_from,
    advertiser_from,
    advertiser_row,
    agency_from,
    agency_row,
    assignment_rows,
    clock_from,
    clock_grid_from,
    clock_row,
    cluster_from,
    cluster_rows,
    cut_from,
    cut_library_from,
    cut_row,
    daypart_from,
    daypart_grid_from,
    daypart_row,
    line_from,
    line_row,
    order_from,
    order_row,
    rate_card_book_from,
    rate_card_from,
    rate_card_row,
    rotation_from,
    station_from,
    station_row,
)
from support import CARD_DATE, MONDAY, a_card, a_grid, farm_clock

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE


def test_a_station_survives_the_round_trip_whole(cluster):
    for station in cluster.stations:
        assert station_from(station_row(station, cluster.market)) == station


def test_a_cluster_survives_the_round_trip_whole(cluster):
    assert cluster_from(cluster_rows(cluster)) == cluster


def test_an_empty_cluster_maps_to_nothing_and_back():
    assert len(cluster_from([])) == 0


def test_a_simulcast_resolves_however_the_rows_come_back(cluster):
    rows = list(reversed(cluster_rows(cluster)))
    back = cluster_from(rows)
    assert back.logging_station("KWLO-LP").call == "KWLO"
    assert len(back) == len(cluster)


def test_a_daypart_survives_the_round_trip_whole():
    for daypart in standard_grid().dayparts:
        assert daypart_from(daypart_row(daypart, "KWLO")) == daypart


def test_a_daypart_grid_survives_the_round_trip_whole():
    grid = standard_grid()
    rows = [daypart_row(daypart, "KWLO") for daypart in grid.dayparts]
    assert daypart_grid_from(rows) == grid


def test_a_clock_survives_the_round_trip_with_its_breaks(cluster):
    for clock in (music_clock(), talk_clock(), farm_clock()):
        assert clock_from(clock_row(clock, "KWLO", CARD_DATE)) == clock


def test_a_programming_grid_survives_the_round_trip_in_order():
    grid = a_grid("KCNZ", "TLK")
    rows = assignment_rows(grid)
    assert [row.ordinal for row in rows] == list(range(len(grid.assignments)))
    back = clock_grid_from(
        "KCNZ", CARD_DATE, [clock_row(c, "KCNZ", CARD_DATE) for c in grid.clocks.values()], rows
    )
    assert [a.label for a in back.assignments] == [a.label for a in grid.assignments]
    any_day = next(iter(back.assignments[0].days))
    assert back.clock_for(any_day, 7).code == "TLK"


def test_the_assignment_order_survives_even_when_the_rows_come_back_shuffled():
    grid = a_grid("KCNZ", "TLK")
    grid.assign(
        type(grid.assignments[0])(
            label="farm block",
            days=grid.assignments[0].days,
            first_hour=5,
            last_hour=7,
            clock="FRM",
        )
    )
    rows = list(reversed(assignment_rows(grid)))
    back = clock_grid_from(
        "KCNZ", CARD_DATE, [clock_row(c, "KCNZ", CARD_DATE) for c in grid.clocks.values()], rows
    )
    assert [a.label for a in back.assignments] == ["all week", "farm block"]


def test_an_agency_survives_the_round_trip_whole():
    for agency in (Agency("MOP", "Mopar Media"), Agency("HSE", "House", advertiser_liable=True)):
        assert agency_from(agency_row(agency)) == agency


def test_an_advertiser_survives_the_round_trip_with_all_its_codes(accounts):
    for code in accounts.advertisers:
        advertiser = accounts.advertiser(code)
        assert advertiser_from(advertiser_row(advertiser)) == advertiser


def test_the_root_of_each_code_is_written_out_for_the_index(accounts):
    row = advertiser_row(accounts.advertiser("HYVEE"))
    assert sorted((entry.path, entry.root) for entry in row.categories) == [
        ("FUEL.GAS", "FUEL"),
        ("GROCERY", "GROCERY"),
    ]


def test_a_whole_account_book_survives_the_round_trip(accounts):
    rows_a = [agency_row(agency) for agency in accounts.agencies.values()]
    rows_v = [advertiser_row(a) for a in accounts.advertisers.values()]
    back = account_book_from(rows_a, rows_v)
    assert back.advertisers == accounts.advertisers
    assert back.agencies == accounts.agencies


def test_a_chain_store_is_read_back_after_its_parent():
    book_rows = [
        advertiser_row(Advertiser.build("HYVEE1", "Hy-Vee Kimball", "GROCERY", parent="HYVEE")),
        advertiser_row(Advertiser.build("HYVEE", "Hy-Vee", "GROCERY")),
    ]
    back = account_book_from([], book_rows)
    assert back.billing_group("HYVEE1") == "HYVEE"


def test_an_account_on_hold_stays_on_hold():
    advertiser = Advertiser.build("SLOW", "Slow Pay", credit=Credit.HOLD)
    assert advertiser_from(advertiser_row(advertiser)).credit is Credit.HOLD


def test_a_cut_survives_the_round_trip_with_its_running_time():
    cut = Cut(
        cart="C1204",
        isci="HYVE2501",
        advertiser="HYVEE",
        length=THIRTY,
        actual=Duration(301),
        first_air=MONDAY,
        last_air=date(2025, 9, 1),
        killed=True,
    )
    assert cut_from(cut_row(cut)) == cut


def test_a_cut_library_survives_the_round_trip():
    cuts = [
        Cut(
            cart="C1204",
            isci="HYVE2501",
            advertiser="HYVEE",
            length=THIRTY,
            actual=Duration(300),
            first_air=MONDAY,
        ),
        Cut(
            cart="C1205",
            isci="HYVE2502",
            advertiser="HYVEE",
            length=SpotLength(60),
            actual=Duration(600),
            first_air=MONDAY,
        ),
    ]
    library = cut_library_from([cut_row(cut) for cut in cuts])
    assert len(library) == 2
    assert library.cut("C1205").length == SpotLength(60)


def test_a_rate_card_survives_the_round_trip_whole():
    card = a_card("KWLO-25", "KWLO", 60, 32)
    assert rate_card_from(rate_card_row(card)) == card


def test_a_card_book_survives_the_round_trip_in_effective_order():
    cards = [
        a_card("KWLO-25A", "KWLO", 60, 32),
        a_card("KCRR-25", "KCRR", 40, 22),
    ]
    book = rate_card_book_from([rate_card_row(card) for card in cards])
    assert book.in_force("KWLO", MONDAY).code == "KWLO-25A"
    assert book.in_force("KCRR", MONDAY).code == "KCRR-25"


def a_line(number=1, carts=()):
    return Line(
        number=number,
        station="KWLO",
        daypart="AMD",
        grade=FIXED,
        length=THIRTY,
        rate=Money.dollars(60),
        flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
        carts=carts,
    )


def test_a_line_survives_the_round_trip_with_its_flight():
    line = a_line()
    back = line_from(line_row(line))
    assert back == line
    assert back.flight.per_week == 12
    assert back.spots == line.spots


def test_a_lines_carts_survive_the_round_trip_in_order():
    line = a_line(carts=("C1204", "C1205", "C1206"))
    assert line_from(line_row(line)).carts == ("C1204", "C1205", "C1206")


def test_a_weighted_rotation_survives_the_round_trip():
    rotation = Rotation.weighted([("C1204", 3), ("C1205", 1)])
    row = line_row(a_line(), rotation=rotation)
    assert rotation_from(row) == rotation
    assert rotation_from(row).kind is RotationKind.WEIGHTED


def test_a_line_with_no_copy_has_no_rotation_to_read_back():
    assert rotation_from(line_row(a_line())) is None


def test_the_card_the_rate_came_off_is_written_beside_it():
    assert line_row(a_line(), card_code="KWLO-25").card_code == "KWLO-25"


def test_an_order_survives_the_round_trip_whole():
    order = Order(
        number="25-1044",
        advertiser="HYVEE",
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=(a_line(1), a_line(2, carts=("C1204",))),
        status=OrderStatus.CONFIRMED,
        agency="MOP",
        campaign="back to school",
    )
    back = order_from(order_row(order))
    assert back == order
    assert back.status is OrderStatus.CONFIRMED
    assert back.campaign == "back to school"
    assert back.value == order.value


def test_each_line_can_name_the_card_it_was_priced_from():
    order = Order(
        number="25-1044",
        advertiser="HYVEE",
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=(a_line(1), a_line(2)),
    )
    row = order_row(order, {1: "KWLO-25", 2: "KCRR-25"})
    assert [line.card_code for line in row.lines] == ["KWLO-25", "KCRR-25"]


def test_an_empty_order_survives_the_round_trip():
    order = Order(
        number="25-1044", advertiser="HYVEE", written=date(2025, 7, 21), salesperson="DHOLM"
    )
    assert order_from(order_row(order)) == order


@pytest.mark.parametrize("grade", list(ClassOfTime))
def test_every_class_of_time_survives_the_round_trip(grade):
    line = Line(
        number=1,
        station="KWLO",
        daypart="AMD",
        grade=grade,
        length=THIRTY,
        rate=Money.dollars(60),
        flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
    )
    assert line_from(line_row(line)).grade is grade
