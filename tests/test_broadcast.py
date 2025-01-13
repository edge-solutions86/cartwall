"""The 2025 numbers here are the published broadcast calendar, not our output.

If a change makes these fail, the change is wrong. Sales quotes flights against
this calendar and the buyer's agency is holding the same one.
"""

from datetime import date, timedelta

import pytest

from cartwall.broadcast import (
    BroadcastMonth,
    BroadcastQuarter,
    BroadcastWeek,
    week_start,
    weeks_between,
    weeks_in_year,
    year_of,
    year_start,
)

# month, opens, closes, weeks
CALENDAR_2025 = [
    (1, date(2024, 12, 30), date(2025, 1, 26), 4),
    (2, date(2025, 1, 27), date(2025, 2, 23), 4),
    (3, date(2025, 2, 24), date(2025, 3, 30), 5),
    (4, date(2025, 3, 31), date(2025, 4, 27), 4),
    (5, date(2025, 4, 28), date(2025, 5, 25), 4),
    (6, date(2025, 5, 26), date(2025, 6, 29), 5),
    (7, date(2025, 6, 30), date(2025, 7, 27), 4),
    (8, date(2025, 7, 28), date(2025, 8, 24), 4),
    (9, date(2025, 8, 25), date(2025, 9, 28), 5),
    (10, date(2025, 9, 29), date(2025, 10, 26), 4),
    (11, date(2025, 10, 27), date(2025, 11, 23), 4),
    (12, date(2025, 11, 24), date(2025, 12, 28), 5),
]


@pytest.mark.parametrize(("month", "opens", "closes", "weeks"), CALENDAR_2025)
def test_the_2025_broadcast_months_match_the_published_calendar(month, opens, closes, weeks):
    bcast = BroadcastMonth(2025, month)
    assert bcast.start == opens
    assert bcast.end == closes
    assert bcast.week_count == weeks


def test_broadcast_august_2025_ends_on_the_twenty_fourth():
    # The one everybody in sales gets wrong.
    assert BroadcastMonth(2025, 8).end == date(2025, 8, 24)
    assert date(2025, 8, 26) in BroadcastMonth(2025, 9)


def test_the_year_opens_on_the_monday_of_the_week_holding_january_first():
    assert year_start(2025) == date(2024, 12, 30)
    assert year_start(2024) == date(2024, 1, 1)
    assert year_start(2026) == date(2025, 12, 29)


def test_every_month_opens_on_a_monday_and_closes_on_a_sunday():
    for year in range(2020, 2036):
        for month in range(1, 13):
            bcast = BroadcastMonth(year, month)
            assert bcast.start.weekday() == 0
            assert bcast.end.weekday() == 6


def test_the_months_tile_the_year_with_no_gap_and_no_overlap():
    for year in range(2020, 2036):
        day = year_start(year)
        for month in range(1, 13):
            bcast = BroadcastMonth(year, month)
            assert bcast.start == day
            day = bcast.end + timedelta(days=1)
        assert day == year_start(year + 1)


def test_a_year_is_52_or_53_weeks_and_the_long_ones_are_where_we_expect():
    counts = {year: weeks_in_year(year) for year in range(2015, 2040)}
    assert set(counts.values()) == {52, 53}
    assert [year for year, weeks in counts.items() if weeks == 53] == [2017, 2023, 2028, 2034]


def test_the_extra_week_of_a_long_year_hangs_on_december():
    assert BroadcastMonth(2023, 12).week_count == 5 + 1
    assert BroadcastQuarter(2023, 4).week_count == 14
    for quarter in (1, 2, 3):
        assert BroadcastQuarter(2023, quarter).week_count == 13


def test_quarters_are_thirteen_weeks_in_an_ordinary_year():
    for quarter in range(1, 5):
        assert BroadcastQuarter(2025, quarter).week_count == 13


def test_a_week_has_to_start_on_a_monday():
    with pytest.raises(ValueError, match="Monday"):
        BroadcastWeek(date(2025, 8, 6))


def test_a_week_knows_its_seven_days():
    week = BroadcastWeek.of(date(2025, 8, 8))
    assert week.start == date(2025, 8, 4)
    assert week.end == date(2025, 8, 10)
    assert list(week.days()) == [date(2025, 8, day) for day in range(4, 11)]
    assert date(2025, 8, 10) in week
    assert date(2025, 8, 11) not in week
    assert str(week) == "week of 2025-08-04"


def test_shifting_a_week_moves_whole_weeks_in_both_directions():
    week = BroadcastWeek(date(2025, 8, 4))
    assert week.shift(3).start == date(2025, 8, 25)
    assert week.shift(-1).start == date(2025, 7, 28)
    assert week.shift(0) == week


def test_week_start_pins_any_day_to_its_monday():
    assert week_start(date(2025, 8, 4)) == date(2025, 8, 4)
    assert week_start(date(2025, 8, 10)) == date(2025, 8, 4)


def test_the_year_a_day_belongs_to_is_not_always_its_calendar_year():
    assert year_of(date(2024, 12, 30)) == 2025
    assert year_of(date(2025, 1, 1)) == 2025
    assert year_of(date(2025, 12, 28)) == 2025
    assert year_of(date(2025, 12, 29)) == 2026


def test_finding_the_month_a_day_falls_in():
    assert BroadcastMonth.of(date(2025, 8, 24)) == BroadcastMonth(2025, 8)
    assert BroadcastMonth.of(date(2025, 8, 25)) == BroadcastMonth(2025, 9)
    assert BroadcastMonth.of(date(2024, 12, 30)) == BroadcastMonth(2025, 1)
    assert BroadcastMonth.of(date(2025, 12, 29)) == BroadcastMonth(2026, 1)


def test_every_day_of_a_long_stretch_lands_in_the_month_that_claims_it():
    day = date(2022, 1, 3)
    while day < date(2030, 1, 1):
        assert day in BroadcastMonth.of(day)
        day = date.fromordinal(day.toordinal() + 1)


def test_walking_months_forward_and_back_over_the_year_boundary():
    december = BroadcastMonth(2025, 12)
    assert december.next() == BroadcastMonth(2026, 1)
    assert december.next().previous() == december
    assert BroadcastMonth(2025, 1).previous() == BroadcastMonth(2024, 12)


def test_a_month_lists_its_weeks_in_order():
    weeks = list(BroadcastMonth(2025, 9).weeks())
    assert len(weeks) == 5
    assert weeks[0].start == date(2025, 8, 25)
    assert weeks[-1].end == date(2025, 9, 28)
    pairs = zip(weeks, weeks[1:], strict=False)
    assert all(later.start > earlier.start for earlier, later in pairs)


def test_months_parse_the_way_sales_writes_them():
    assert BroadcastMonth.parse("2025-08") == BroadcastMonth(2025, 8)
    assert str(BroadcastMonth.parse("2025-08")) == "2025-08"
    with pytest.raises(ValueError, match="not a broadcast month"):
        BroadcastMonth.parse("August")
    with pytest.raises(ValueError, match="not a month"):
        BroadcastMonth(2025, 13)


def test_quarters_know_their_months_and_their_span():
    third = BroadcastQuarter(2025, 3)
    assert [month.month for month in third.months()] == [7, 8, 9]
    assert third.start == date(2025, 6, 30)
    assert third.end == date(2025, 9, 28)
    assert str(third) == "2025Q3"
    assert BroadcastQuarter.of(date(2025, 8, 25)) == third
    with pytest.raises(ValueError, match="not a quarter"):
        BroadcastQuarter(2025, 5)


def test_the_weeks_a_flight_touches_include_both_partial_ends():
    weeks = weeks_between(date(2025, 8, 6), date(2025, 8, 19))
    assert [week.start for week in weeks] == [
        date(2025, 8, 4),
        date(2025, 8, 11),
        date(2025, 8, 18),
    ]
    assert weeks_between(date(2025, 8, 6), date(2025, 8, 6))[0].start == date(2025, 8, 4)
    with pytest.raises(ValueError, match="backwards"):
        weeks_between(date(2025, 8, 19), date(2025, 8, 6))


def test_weeks_and_months_sort_by_when_they_air():
    assert BroadcastWeek(date(2025, 8, 4)) < BroadcastWeek(date(2025, 8, 11))
    assert BroadcastMonth(2025, 3) < BroadcastMonth(2025, 11)
    assert BroadcastMonth(2024, 12) < BroadcastMonth(2025, 1)
    assert sorted([BroadcastQuarter(2025, 4), BroadcastQuarter(2025, 1)])[0].quarter == 1
