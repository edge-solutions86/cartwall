import pytest

from cartwall.airtime import AirTime, Duration
from cartwall.domain.clock import (
    Break,
    BreakKind,
    Clock,
    ClockError,
    local,
    music_clock,
    talk_clock,
)


def test_a_break_sits_at_an_offset_not_a_wall_clock_time():
    brk = local(2, 22, 180, 6)
    assert brk.position == Duration.of_seconds(22 * 60)
    assert brk.ends == Duration.of_seconds(25 * 60)
    assert brk.starts_at(7) == AirTime.at(7, 22)
    assert brk.starts_at(15) == AirTime.at(15, 22)
    assert str(brk) == "break 2 at 22:00"


def test_a_break_that_cannot_be_drawn():
    with pytest.raises(ClockError, match="not a break number"):
        local(0, 22, 180, 6)
    with pytest.raises(ClockError, match="past the hour"):
        local(2, 60, 180, 6)
    with pytest.raises(ClockError, match="has no length"):
        local(2, 22, 0, 6)
    with pytest.raises(ClockError, match="runs past the top"):
        local(2, 58, 180, 6)
    with pytest.raises(ClockError, match="holds no spots"):
        local(2, 22, 180, 0)


def test_a_break_the_network_owns_cannot_be_sold():
    with pytest.raises(ClockError, match="cannot be paid"):
        Break(
            position=Duration.of_seconds(1800),
            number=4,
            kind=BreakKind.NETWORK,
            length=Duration.of_seconds(300),
            units=1,
        )


def test_breaks_know_when_they_run_into_each_other():
    first = local(1, 10, 180, 6)
    touching = local(2, 13, 180, 6)
    running_in = local(3, 12, 180, 6)
    assert not first.overlaps(touching)
    assert first.overlaps(running_in)
    assert running_in.overlaps(first)


def test_the_music_clock_is_twelve_minutes_in_four_stopsets():
    clock = music_clock()
    assert clock.commercial_minutes == 12
    assert len(clock.sellable) == 4
    assert clock.unit_count == 24
    assert len(clock) == 5
    assert str(clock) == "MUS (12 minutes)"


def test_the_talk_clock_carries_more_and_gives_the_half_hour_to_the_network():
    clock = talk_clock()
    assert clock.commercial_minutes == 15
    assert clock.numbered(4).kind is BreakKind.NETWORK
    assert clock.numbered(4) not in clock.sellable
    assert clock.unit_count == 30


def test_the_legal_id_is_in_the_clock_but_not_for_sale():
    clock = music_clock()
    legal = clock.numbered(1)
    assert legal.kind is BreakKind.LEGAL_ID
    assert not legal.kind.sells
    assert legal not in clock.sellable


def test_breaks_come_back_in_air_order_however_they_were_handed_in():
    clock = Clock.build(
        "ODD",
        "out of order",
        [local(3, 40, 60, 2), local(1, 10, 60, 2), local(2, 25, 60, 2)],
    )
    assert [brk.number for brk in clock] == [1, 2, 3]
    assert [brk.position.seconds for brk in clock] == [600, 1500, 2400]


def test_a_clock_that_cannot_be_run():
    with pytest.raises(ClockError, match="needs a code"):
        Clock.build("", "nameless", [local(1, 10, 60, 2)])
    with pytest.raises(ClockError, match="numbers a break twice"):
        Clock.build("DUP", "twice", [local(1, 10, 60, 2), local(1, 30, 60, 2)])
    with pytest.raises(ClockError, match="running into"):
        Clock.build("RUN", "overlapping", [local(1, 10, 180, 6), local(2, 12, 180, 6)])


def test_a_clock_may_only_call_for_one_legal_id_and_it_sits_near_the_top():
    legal = Break(
        position=Duration(0),
        number=1,
        kind=BreakKind.LEGAL_ID,
        length=Duration.of_seconds(10),
        units=1,
        paid=False,
    )
    late = Break(
        position=Duration.of_seconds(20 * 60),
        number=2,
        kind=BreakKind.LEGAL_ID,
        length=Duration.of_seconds(10),
        units=1,
        paid=False,
    )
    with pytest.raises(ClockError, match="two legal IDs"):
        Clock.build("TWO", "two ids", [legal, late])
    with pytest.raises(ClockError, match="puts the legal ID"):
        Clock.build("LATE", "late id", [late])


def test_a_legal_id_just_before_the_top_of_the_hour_is_allowed():
    late_but_close = Break(
        position=Duration.of_seconds(59 * 60),
        number=1,
        kind=BreakKind.LEGAL_ID,
        length=Duration.of_seconds(10),
        units=1,
        paid=False,
    )
    assert Clock.build("PRE", "id before the join", [late_but_close]).numbered(1)


def test_asking_for_a_break_the_clock_does_not_have():
    with pytest.raises(ClockError, match="no break 9"):
        music_clock().numbered(9)


def test_seconds_and_units_are_two_different_limits():
    clock = music_clock()
    # Twelve minutes of air, but only twenty four spots will be taken.
    assert clock.commercial_length == Duration.of_seconds(720)
    assert clock.unit_count == 24
    assert clock.numbered(2).length == Duration.of_seconds(180)
    assert clock.numbered(2).units == 6


def test_a_clock_is_checked_against_the_load_the_station_says_it_carries():
    assert music_clock().fits_load(12)
    assert music_clock().fits_load(18)
    assert not music_clock().fits_load(10)
    assert not talk_clock().fits_load(14)
    assert talk_clock().fits_load(15)
