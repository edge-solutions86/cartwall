from decimal import Decimal

import pytest

from cartwall.money import (
    ZERO,
    Money,
    MoneyError,
    commission,
    round_trips,
    to_gross,
    to_net,
    total,
)


def test_amounts_parse_the_way_they_are_written_on_an_order():
    assert Money.parse("$1,250.00") == Money(125000)
    assert Money.parse("1250") == Money(125000)
    assert Money.parse("45.5") == Money(4550)
    assert Money.parse("-45.50") == Money(-4550)
    assert Money.parse("  $0.07 ") == Money(7)


def test_amounts_that_are_not_amounts():
    for text in ("", "free", "1.234", "$1.2.3", "1,25o"):
        with pytest.raises(MoneyError, match="not an amount"):
            Money.parse(text)


def test_dollars_come_in_as_decimal_and_round_half_up_to_the_cent():
    assert Money.dollars(Decimal("12.345")) == Money(1235)
    assert Money.dollars(Decimal("12.344")) == Money(1234)
    assert Money.dollars(75) == Money(7500)
    assert Money.dollars("18.50") == Money(1850)


def test_no_float_ever_becomes_money():
    with pytest.raises(MoneyError, match="whole cents"):
        Money(12.5)


def test_amounts_print_with_a_thousands_comma():
    assert str(Money(125000)) == "$1,250.00"
    assert str(Money(7)) == "$0.07"
    assert str(Money(-4550)) == "-$45.50"
    assert str(Money(0)) == "$0.00"
    assert str(Money(123456789)) == "$1,234,567.89"


def test_amounts_add_subtract_negate_and_multiply_by_a_count():
    assert Money(1000) + Money(250) == Money(1250)
    assert Money(1000) - Money(1250) == Money(-250)
    assert -Money(250) == Money(-250)
    assert abs(Money(-250)) == Money(250)
    assert Money(1250) * 4 == Money(5000)
    assert 4 * Money(1250) == Money(5000)
    assert not ZERO
    assert Money(1)


def test_money_does_not_multiply_by_a_rate_by_accident():
    with pytest.raises(MoneyError, match="by a count"):
        Money(1000) * Decimal("0.15")


def test_a_percentage_of_an_amount_rounds_half_up():
    assert Money.parse("100.00").scale(Decimal("0.15")) == Money.parse("15.00")
    assert Money.parse("117.65").scale(Decimal("0.15")) == Money.parse("17.65")
    assert Money.parse("0.03").scale(Decimal("0.5")) == Money.parse("0.02")


def test_totalling_a_column():
    assert total([Money(100), Money(250), Money(-50)]) == Money(300)
    assert total([]) == ZERO


def test_an_even_split_that_does_not_divide_still_adds_back_up():
    pieces = Money.parse("1000.00").split(3)
    assert pieces == [Money(33334), Money(33333), Money(33333)]
    assert total(pieces) == Money.parse("1000.00")


def test_a_weighted_split_gives_the_leftover_cents_to_the_worst_robbed():
    pieces = Money.parse("100.00").allocate([1, 1, 1])
    assert [str(piece) for piece in pieces] == ["$33.34", "$33.33", "$33.33"]
    weighted = Money.parse("100.00").allocate([5, 3, 2])
    assert weighted == [Money(5000), Money(3000), Money(2000)]
    assert total(weighted) == Money.parse("100.00")


def test_a_zero_weight_gets_nothing_and_costs_nobody_a_cent():
    pieces = Money.parse("10.00").allocate([1, 0, 1])
    assert pieces == [Money(500), Money(0), Money(500)]


def test_a_negative_amount_allocates_the_same_way():
    pieces = Money.parse("-10.00").allocate([1, 1, 1])
    assert total(pieces) == Money.parse("-10.00")
    assert all(piece.cents < 0 for piece in pieces)


def test_allocations_that_cannot_be_made():
    with pytest.raises(MoneyError, match="nothing to allocate"):
        Money(100).allocate([])
    with pytest.raises(MoneyError, match="cannot be negative"):
        Money(100).allocate([1, -1])
    with pytest.raises(MoneyError, match="all zero"):
        Money(100).allocate([0, 0])
    with pytest.raises(MoneyError, match="fewer than one way"):
        Money(100).split(0)


def test_a_four_week_flight_split_over_five_weeks_still_adds_back_up():
    for cents in range(9990, 10010):
        for ways in (2, 3, 4, 5, 6, 7, 13):
            assert total(Money(cents).split(ways)) == Money(cents)


def test_the_commission_comes_out_of_the_gross_not_off_the_net():
    gross = Money.parse("1,000.00")
    assert commission(gross) == Money.parse("150.00")
    assert to_net(gross) == Money.parse("850.00")


def test_grossing_a_net_back_up_divides_rather_than_adding_fifteen_percent():
    net = Money.parse("100.00")
    assert to_gross(net) == Money.parse("117.65")
    assert net + commission(net) == Money.parse("115.00")
    assert to_gross(net) != net + commission(net)


def test_a_gross_on_the_rate_card_comes_home_from_net():
    for text in ("100.00", "1,000.00", "18.00", "425.00"):
        assert round_trips(Money.parse(text)), text


def test_a_gross_off_the_card_does_not_always_come_home():
    assert to_net(Money(23)) == Money(20)
    assert to_gross(Money(20)) == Money(24)
    assert not round_trips(Money(23))
    broken = [cents for cents in range(1, 700) if not round_trips(Money(cents))]
    assert len(broken) == 105, "about one in seven, and the warning depends on it"


def test_grossing_up_is_the_direction_that_never_loses_a_cent():
    # Net to gross to net always comes home, because the gross is spread out.
    for cents in range(1, 2000):
        assert to_net(to_gross(Money(cents))) == Money(cents)


def test_a_house_rate_of_zero_commission_is_exact_both_ways():
    direct = Decimal("0")
    for cents in range(1, 200):
        assert to_gross(Money(cents), direct) == Money(cents)
        assert round_trips(Money(cents), direct)


def test_a_commission_of_everything_cannot_be_grossed_up():
    with pytest.raises(MoneyError, match="commission of everything"):
        to_gross(Money(1000), Decimal("1"))
