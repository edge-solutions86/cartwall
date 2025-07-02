from datetime import date

import pytest

from cartwall.money import ZERO, Money
from cartwall.reports.sellout import (
    Period,
    SelloutError,
    against_budget,
    busiest,
    pace,
    rolled_up,
    thinnest,
)

MONDAY = date(2025, 8, 4)
SUNDAY = date(2025, 8, 10)


def a_period(label="this week", units=1000, sold=400, revenue="8000.00", first=MONDAY, last=SUNDAY):
    return Period(
        label=label,
        first=first,
        last=last,
        units=units,
        sold=sold,
        revenue=Money.parse(revenue),
    )


def test_a_period_works_out_its_own_sellout():
    period = a_period(units=1000, sold=400)
    assert period.open == 600
    assert period.sellout == 0.4


def test_a_period_with_no_inventory_does_not_divide_by_zero():
    assert a_period(units=0, sold=0, revenue="0").sellout == 0.0


def test_a_period_that_sold_more_than_it_had_is_refused():
    with pytest.raises(SelloutError, match="which is oversold") as caught:
        a_period(units=100, sold=101)
    assert caught.value.context["sold"] == 101


def test_a_period_that_runs_backwards_is_refused():
    with pytest.raises(SelloutError, match="runs backwards"):
        a_period(first=SUNDAY, last=MONDAY)


def test_the_average_unit_rate_is_revenue_over_what_sold():
    assert a_period(sold=400, revenue="8000.00").rate == Money.dollars(20)
    assert a_period(sold=0, revenue="0").rate == ZERO


def test_a_period_reads_as_a_line_of_a_report():
    printed = str(a_period())
    assert "this week" in printed
    assert "400 of 1000" in printed
    assert "40.0%" in printed
    assert "$8,000.00" in printed


def test_pacing_reports_the_change_in_units_money_and_sellout():
    now = a_period("this week", units=1000, sold=500, revenue="10000.00")
    then = a_period("last week", units=1000, sold=400, revenue="8000.00")
    found = pace(now, then)
    assert found.unit_change == 100
    assert found.revenue_change == Money.dollars(2000)
    assert abs(found.sellout_change - 0.1) < 1e-9
    assert found.comparable


def test_a_week_with_a_ballgame_in_it_is_flagged_as_not_comparable():
    now = a_period("this week", units=800, sold=400, revenue="8000.00")
    then = a_period("last week", units=1000, sold=400, revenue="8000.00")
    found = pace(now, then)
    assert not found.comparable
    assert found.unit_change == 0
    assert found.sellout_change > 0
    assert "different inventory" in str(found)


def test_the_index_is_revenue_now_over_revenue_then():
    now = a_period("this week", revenue="10000.00")
    then = a_period("last week", revenue="8000.00")
    assert pace(now, then).index == 1.25


def test_pacing_against_a_period_that_earned_nothing_has_no_index():
    now = a_period("this week", revenue="10000.00")
    then = a_period("last week", sold=0, revenue="0")
    assert pace(now, then).index is None


def test_a_pace_reads_as_a_sentence():
    now = a_period("this week", units=1000, sold=500, revenue="10000.00")
    then = a_period("last week", units=1000, sold=400, revenue="8000.00")
    printed = str(pace(now, then))
    assert "this week against last week" in printed
    assert "+100 units" in printed
    assert "$2,000.00" in printed
    assert "+10.0%" in printed


def test_a_pace_that_went_backwards_says_so_with_a_sign():
    now = a_period("this week", units=1000, sold=300, revenue="6000.00")
    then = a_period("last week", units=1000, sold=400, revenue="8000.00")
    printed = str(pace(now, then))
    assert "-100 units" in printed
    assert "-$2,000.00" in printed


def test_budget_reports_the_gap_and_the_fraction():
    now = a_period(revenue="8000.00")
    gap, fraction = against_budget(now, Money.dollars(10000))
    assert gap == Money.dollars(-2000)
    assert fraction == 0.8


def test_a_budget_of_nothing_has_no_fraction_to_report():
    gap, fraction = against_budget(a_period(revenue="8000.00"), ZERO)
    assert gap == Money.dollars(8000)
    assert fraction is None


def test_the_fullest_and_thinnest_are_by_sellout_and_not_by_units():
    weeks = [
        a_period("week one", units=1000, sold=400, revenue="8000.00"),
        a_period("week two", units=500, sold=300, revenue="6000.00"),
        a_period("week three", units=1000, sold=200, revenue="4000.00"),
    ]
    assert busiest(weeks).label == "week two"
    assert thinnest(weeks).label == "week three"


def test_a_period_with_no_inventory_is_left_out_of_the_comparison():
    weeks = [
        a_period("off air", units=0, sold=0, revenue="0"),
        a_period("on air", units=100, sold=10, revenue="200.00"),
    ]
    assert busiest(weeks).label == "on air"
    assert thinnest(weeks).label == "on air"
    assert busiest([]) is None
    assert thinnest([]) is None


def test_rolling_several_periods_up_adds_the_denominators_too():
    weeks = [
        a_period("week one", units=1000, sold=400, revenue="8000.00", first=MONDAY, last=SUNDAY),
        a_period(
            "week two",
            units=800,
            sold=500,
            revenue="12000.00",
            first=date(2025, 8, 11),
            last=date(2025, 8, 17),
        ),
    ]
    month = rolled_up(weeks, "August")
    assert month.units == 1800
    assert month.sold == 900
    assert month.revenue == Money.dollars(20000)
    assert month.first == MONDAY
    assert month.last == date(2025, 8, 17)
    assert month.sellout == 0.5


def test_rolling_up_nothing_is_refused():
    with pytest.raises(SelloutError, match="nothing to roll up"):
        rolled_up([], "August")
