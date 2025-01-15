import pytest

from cartwall.airtime import (
    MIDNIGHT,
    AirTime,
    Duration,
    TimeError,
    backtime,
    total_length,
)


def test_a_length_can_be_written_the_five_ways_the_house_writes_it():
    assert Duration.parse(":30") == Duration.of_seconds(30)
    assert Duration.parse("30") == Duration.of_seconds(30)
    assert Duration.parse("1:00") == Duration.of_seconds(60)
    assert Duration.parse("00:01:30") == Duration.of_seconds(90)
    assert Duration.parse("1:00.5") == Duration(605)


def test_a_length_that_is_not_a_length():
    for text in ("", "thirty", "1:2:3:4", "-30", "30.55"):
        with pytest.raises(TimeError, match="not a length"):
            Duration.parse(text)


def test_lengths_print_the_way_the_log_prints_them():
    assert str(Duration.of_seconds(30)) == ":30"
    assert str(Duration.of_seconds(60)) == "1:00"
    assert str(Duration.of_seconds(90)) == "1:30"
    assert str(Duration(605)) == "1:00.5"
    assert str(Duration.of_seconds(3725)) == "1:02:05"
    assert str(Duration(0)) == ":00"


def test_lengths_add_subtract_and_multiply():
    thirty = Duration.of_seconds(30)
    assert thirty + thirty == Duration.of_seconds(60)
    assert thirty * 4 == Duration.of_seconds(120)
    assert 4 * thirty == Duration.of_seconds(120)
    assert Duration.of_seconds(60) - thirty == thirty
    assert not Duration(0)
    assert thirty


def test_a_length_cannot_go_negative_however_it_is_reached():
    with pytest.raises(TimeError, match="backwards"):
        Duration(-1)
    with pytest.raises(TimeError, match="backwards"):
        Duration.of_seconds(30) - Duration.of_seconds(31)


def test_tenths_have_to_be_whole():
    with pytest.raises(TimeError, match="whole number"):
        Duration(29.7)


def test_a_part_second_cut_still_eats_a_whole_second_of_the_break():
    assert Duration(301).whole_seconds == 31
    assert Duration(300).whole_seconds == 30
    assert Duration(299).whole_seconds == 30
    assert Duration(301).seconds == 30.1


def test_a_break_full_of_odd_cuts_does_not_drift():
    # Sixty cuts that each run 30.1 come to exactly 30:06, not 30:05.999...
    cuts = [Duration(301)] * 60
    assert total_length(cuts) == Duration(18060)
    assert str(total_length(cuts)) == "30:06"


def test_air_times_parse_in_both_clocks():
    assert AirTime.parse("6:00a") == AirTime.at(6)
    assert AirTime.parse("6:00:00") == AirTime.at(6)
    assert AirTime.parse("12:00a") == MIDNIGHT
    assert AirTime.parse("12:00p") == AirTime.at(12)
    assert AirTime.parse("2:15:30p") == AirTime.at(14, 15, 30)
    assert AirTime.parse("14:15:30") == AirTime.at(14, 15, 30)
    assert AirTime.parse("11:59:59p") == AirTime.at(23, 59, 59)


def test_air_times_that_are_not_air_times():
    for text in ("", "6a", "25:00:00", "6:60:00", "noon"):
        with pytest.raises(TimeError):
            AirTime.parse(text)
    with pytest.raises(TimeError, match="twelve hour"):
        AirTime.parse("14:00p")


def test_air_times_print_both_ways():
    assert str(AirTime.at(6)) == "06:00:00"
    assert str(AirTime.at(14, 15, 30)) == "14:15:30"
    assert AirTime.at(14, 15, 30).twelve_hour() == "2:15:30p"
    assert AirTime.at(0, 5).twelve_hour() == "12:05:00a"
    assert AirTime.at(12).twelve_hour() == "12:00:00p"
    assert str(AirTime(216001)) == "06:00:00.1"


def test_an_air_time_knows_where_it_sits_in_its_hour():
    spot = AirTime.at(7, 22, 30)
    assert spot.hour == 7
    assert spot.minute == 22
    assert spot.second == 30
    assert spot.top_of_hour == AirTime.at(7)
    assert spot.into_hour == Duration.of_seconds(22 * 60 + 30)
    assert AirTime.at(7).into_hour == Duration(0)


def test_a_length_added_to_a_position_gives_a_position():
    assert AirTime.at(7, 22) + Duration.of_seconds(30) == AirTime.at(7, 22, 30)
    assert AirTime.at(7, 22, 30) - Duration.of_seconds(30) == AirTime.at(7, 22)


def test_two_positions_subtract_to_a_length():
    gap = AirTime.at(7, 30) - AirTime.at(7, 22)
    assert isinstance(gap, Duration)
    assert gap == Duration.of_seconds(480)


def test_nothing_may_run_past_midnight_in_either_direction():
    with pytest.raises(TimeError, match="past midnight"):
        AirTime.at(23, 59, 30) + Duration.of_seconds(31)
    with pytest.raises(TimeError, match="before midnight"):
        MIDNIGHT - Duration.of_seconds(1)
    with pytest.raises(TimeError, match="outside the broadcast day"):
        AirTime(864000)


def test_air_times_sort_by_when_they_air():
    times = [AirTime.at(14), AirTime.at(6), AirTime.at(9, 30)]
    assert sorted(times) == [AirTime.at(6), AirTime.at(9, 30), AirTime.at(14)]


def test_a_break_backtimed_off_the_network_join_ends_exactly_on_it():
    join = AirTime.at(8, 59, 30)
    lengths = [
        Duration.of_seconds(30),
        Duration.of_seconds(60),
        Duration.of_seconds(30),
        Duration.of_seconds(10),
    ]
    starts = backtime(join, lengths)
    assert starts[0] == AirTime.at(8, 57, 20)
    assert starts == [
        AirTime.at(8, 57, 20),
        AirTime.at(8, 57, 50),
        AirTime.at(8, 58, 50),
        AirTime.at(8, 59, 20),
    ]
    assert starts[-1] + lengths[-1] == join


def test_backtiming_nothing_starts_nothing():
    assert backtime(AirTime.at(8), []) == []


def test_backtiming_more_than_fits_before_midnight_refuses():
    with pytest.raises(TimeError, match="before midnight"):
        backtime(AirTime.at(0, 0, 20), [Duration.of_seconds(30)])
