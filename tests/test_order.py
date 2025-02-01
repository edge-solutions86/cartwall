from datetime import date

import pytest

from cartwall.broadcast import BroadcastWeek
from cartwall.domain.order import (
    Flight,
    Line,
    Order,
    OrderError,
    OrderStatus,
)
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import Money

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE

# 2025-08-04 is a Monday. 2025-08-07 is the Thursday of that week.
MONDAY = date(2025, 8, 4)
THURSDAY = date(2025, 8, 7)


def a_flight(first=MONDAY, last=date(2025, 8, 29), days="M-F", per_week=12):
    return Flight.build(first, last, days, per_week)


def a_line(number=1, flight=None, rate="60.00", **kw):
    return Line(
        number=number,
        station=kw.pop("station", "KWLO"),
        daypart=kw.pop("daypart", "AMD"),
        grade=kw.pop("grade", FIXED),
        length=kw.pop("length", THIRTY),
        rate=Money.parse(rate),
        flight=flight if flight is not None else a_flight(),
        **kw,
    )


def an_order(lines=None, **kw):
    return Order(
        number=kw.pop("number", "25-1044"),
        advertiser=kw.pop("advertiser", "HYVEE"),
        written=kw.pop("written", date(2025, 7, 21)),
        salesperson=kw.pop("salesperson", "DHOLM"),
        lines=lines if lines is not None else (a_line(),),
        **kw,
    )


def test_a_flight_that_cannot_be_written():
    with pytest.raises(OrderError, match="runs backwards"):
        Flight.build(date(2025, 8, 29), MONDAY, "M-F", 12)
    with pytest.raises(OrderError, match="runs on no days"):
        Flight(MONDAY, date(2025, 8, 29), frozenset(), 12)
    with pytest.raises(OrderError, match="is not a schedule"):
        Flight.build(MONDAY, date(2025, 8, 29), "M-F", 0)


def test_a_flight_only_runs_on_the_days_it_was_sold():
    flight = a_flight()
    assert flight.runs_on(MONDAY)
    assert flight.runs_on(date(2025, 8, 8))
    assert not flight.runs_on(date(2025, 8, 9))
    assert not flight.runs_on(date(2025, 8, 3))
    assert not flight.runs_on(date(2025, 9, 1))


def test_a_flight_lists_its_air_days_in_order():
    flight = Flight.build(MONDAY, date(2025, 8, 10), "M,W,F", 3)
    assert flight.air_days() == [
        date(2025, 8, 4),
        date(2025, 8, 6),
        date(2025, 8, 8),
    ]


def test_four_whole_weeks_of_twelve_a_week_is_forty_eight():
    flight = a_flight()
    assert len(flight.weeks) == 4
    assert [count for _, count in flight.weekly_counts()] == [12, 12, 12, 12]
    assert flight.spots == 48


def test_a_stub_week_is_prorated_down_by_the_days_it_actually_has():
    # Starts Thursday, so two of the five weekday air days are left.
    flight = a_flight(first=THURSDAY)
    counts = [count for _, count in flight.weekly_counts()]
    assert counts == [4, 12, 12, 12]
    assert flight.spots == 40


def test_the_stub_week_never_rounds_up():
    flight = Flight.build(date(2025, 8, 8), date(2025, 8, 29), "M-F", 6)
    # One day of five, six a week. Six fifths is 1.2 and the client gets one.
    assert flight.count_in(BroadcastWeek(MONDAY)) == 1


def test_a_paid_week_is_never_left_empty():
    flight = Flight.build(date(2025, 8, 8), date(2025, 8, 29), "M-F", 2)
    # Two fifths of a spot rounds to nothing, and nothing is not an answer.
    assert flight.count_in(BroadcastWeek(MONDAY)) == 1


def test_a_week_the_flight_does_not_touch_gets_nothing():
    flight = a_flight()
    assert flight.count_in(BroadcastWeek(date(2025, 7, 28))) == 0
    assert flight.count_in(BroadcastWeek(date(2025, 9, 1))) == 0


def test_a_weekend_flight_prorates_against_its_own_two_days():
    flight = Flight.build(date(2025, 8, 10), date(2025, 8, 24), "Sa,Su", 4)
    # Starts on a Sunday, so the first week has one of its two air days.
    counts = [count for _, count in flight.weekly_counts()]
    assert counts == [2, 4, 4]


def test_a_flight_reads_the_way_it_prints_on_a_confirmation():
    assert str(a_flight()) == "08/04-08/29 M-F 12x"


def test_a_line_that_cannot_be_written():
    with pytest.raises(OrderError, match="not a line number"):
        a_line(number=0)
    with pytest.raises(OrderError, match="names no station"):
        a_line(station="")
    with pytest.raises(OrderError, match="names no daypart"):
        a_line(daypart="")
    with pytest.raises(OrderError, match="priced at -"):
        a_line(rate="-1.00")


def test_a_line_is_worth_its_rate_times_the_spots_that_will_run():
    line = a_line(rate="60.00")
    assert line.spots == 48
    assert line.value == Money.parse("2,880.00")
    assert line.value_in(BroadcastWeek(MONDAY)) == Money.parse("720.00")


def test_a_line_knows_whether_production_has_dubbed_anything_for_it():
    assert not a_line().has_copy
    assert a_line(carts=("C1204", "C1205")).has_copy


def test_an_order_that_cannot_be_written():
    with pytest.raises(OrderError, match="needs a number"):
        an_order(number="")
    with pytest.raises(OrderError, match="names no advertiser"):
        an_order(advertiser="")
    with pytest.raises(OrderError, match="numbers line 1 twice"):
        an_order(lines=(a_line(1), a_line(1, station="KCRR")))


def test_an_order_adds_up_its_lines():
    order = an_order(
        lines=(
            a_line(1, rate="60.00"),
            a_line(2, station="KCRR", rate="40.00", grade=NOTICE, length=SIXTY),
        )
    )
    assert len(order) == 2
    assert order.spots == 96
    assert order.value == Money.parse("4,800.00")
    assert order.value_in(BroadcastWeek(MONDAY)) == Money.parse("1,200.00")
    assert str(order) == "25-1044 HYVEE $4,800.00"


def test_an_order_finds_its_own_lines():
    order = an_order(lines=(a_line(1), a_line(2, station="KCRR")))
    assert order.line(2).station == "KCRR"
    assert [line.number for line in order] == [1, 2]
    with pytest.raises(OrderError, match="has no line 9"):
        order.line(9)


def test_an_order_reports_which_stations_it_touches_once_each():
    order = an_order(
        lines=(a_line(1, station="KWLO"), a_line(2, station="KCRR"), a_line(3, station="KWLO"))
    )
    assert order.stations() == ("KWLO", "KCRR")
    assert [line.number for line in order.lines_on("KWLO")] == [1, 3]
    assert order.lines_on("KXEL") == ()


def test_an_order_spans_the_earliest_and_latest_air_dates_of_its_lines():
    order = an_order(
        lines=(
            a_line(1, flight=a_flight(first=MONDAY, last=date(2025, 8, 15))),
            a_line(2, flight=a_flight(first=date(2025, 8, 11), last=date(2025, 8, 29))),
        )
    )
    assert order.first_air == MONDAY
    assert order.last_air == date(2025, 8, 29)


def test_an_order_with_no_lines_has_no_dates_to_report():
    order = an_order(lines=())
    with pytest.raises(OrderError, match="has no lines"):
        order.first_air
    with pytest.raises(OrderError, match="has no lines"):
        order.last_air


def test_only_a_confirmed_order_may_be_scheduled():
    order = an_order()
    assert order.status is OrderStatus.PENDING
    assert not order.status.may_schedule
    assert order.confirmed().status.may_schedule
    assert not order.cancelled().status.may_schedule


def test_an_empty_order_cannot_be_confirmed():
    with pytest.raises(OrderError, match="no lines to confirm"):
        an_order(lines=()).confirmed()


def test_a_cancelled_order_does_not_come_back():
    order = an_order().cancelled()
    with pytest.raises(OrderError, match="was cancelled"):
        order.confirmed()


def test_changing_an_order_leaves_the_original_alone():
    order = an_order()
    revised = order.with_lines((a_line(1), a_line(2, station="KCRR")))
    assert len(order) == 1
    assert len(revised) == 2
    assert revised.number == order.number
    assert revised.status is order.status
