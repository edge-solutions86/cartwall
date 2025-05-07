from datetime import date, datetime

import pytest

from cartwall.airtime import AirTime, Duration
from cartwall.playout.asrun import (
    AsRunError,
    Played,
    by_status,
    filename,
    parse,
    spots_only,
    stamp,
)

MONDAY = date(2025, 8, 4)

HEADER = "date,time,cart,title,length,status,reference"


def a_file(*lines: str) -> str:
    return "\n".join([HEADER, *lines]) + "\n"


def a_line(
    at="07:10:00", cart="C1204", title="Hy-Vee sale", length="30.0",
    status="played", reference="25-1044-1",
):
    return f"2025-08-04,{at},{cart},{title},{length},{status},{reference}"


def test_a_file_reads_back_line_by_line():
    rows = parse(a_file(a_line(), a_line(at="09:38:00", reference="25-1044-2")))
    assert len(rows) == 2
    assert rows[0].day == MONDAY
    assert rows[0].at == AirTime.at(7, 10)
    assert rows[0].cart == "C1204"
    assert rows[0].title == "Hy-Vee sale"
    assert rows[0].length == Duration(300)
    assert rows[0].status is Played.PLAYED
    assert rows[0].reference == "25-1044-1"


def test_the_header_is_checked_rather_than_assumed():
    with pytest.raises(AsRunError, match="the as-run header is"):
        parse("date,time,cart\n2025-08-04,07:10:00,C1204\n")
    with pytest.raises(AsRunError, match="is empty"):
        parse("")


def test_a_header_with_odd_spacing_or_case_is_still_the_header():
    text = "Date, Time ,CART,Title,Length,Status,Reference\n" + a_line() + "\n"
    assert len(parse(text)) == 1


def test_the_statuses_the_automation_writes_all_map():
    for written, expected in (
        ("play", Played.PLAYED),
        ("PLAYED", Played.PLAYED),
        ("ok", Played.PLAYED),
        ("skip", Played.SKIPPED),
        ("error", Played.ERROR),
        ("trunc", Played.TRUNCATED),
    ):
        assert parse(a_file(a_line(status=written)))[0].status is expected


def test_a_status_nobody_has_seen_is_kept_rather_than_dropped():
    row = parse(a_file(a_line(status="deferred")))[0]
    assert row.status is Played.UNKNOWN
    assert not row.aired


def test_only_played_and_truncated_count_as_having_gone_out():
    assert Played.PLAYED.aired
    assert Played.TRUNCATED.aired
    assert not Played.SKIPPED.aired
    assert not Played.ERROR.aired
    assert not Played.UNKNOWN.aired


def test_a_length_comes_in_as_seconds_and_a_tenth_or_as_bare_tenths():
    assert parse(a_file(a_line(length="30.0")))[0].length == Duration(300)
    assert parse(a_file(a_line(length="29.7")))[0].length == Duration(297)
    assert parse(a_file(a_line(length="300")))[0].length == Duration(300)
    assert parse(a_file(a_line(length="0.0")))[0].length == Duration(0)


def test_a_length_that_is_not_a_length():
    for bad in ("thirty", "30.00", "30.", "-30", ""):
        with pytest.raises(AsRunError):
            parse(a_file(a_line(length=bad)))


def test_a_date_or_a_time_that_is_not_one():
    with pytest.raises(AsRunError, match="is not a date"):
        parse(a_file("not a date,07:10:00,C1204,t,30.0,played,25-1044-1"))
    with pytest.raises(AsRunError, match="is not an air time"):
        parse(a_file(a_line(at="half seven")))


def test_a_reference_reads_back_as_an_order_and_a_line():
    assert parse(a_file(a_line(reference="25-1044-1")))[0].key == ("25-1044", 1)
    assert parse(a_file(a_line(reference="25-1044-12")))[0].key == ("25-1044", 12)


def test_a_line_that_is_not_ours_has_no_key():
    assert parse(a_file(a_line(reference="")))[0].key is None
    assert parse(a_file(a_line(reference="LEGAL ID")))[0].key is None
    assert parse(a_file(a_line(reference="MUSIC")))[0].key is None


def test_the_programming_lines_are_filtered_out_by_reference():
    rows = parse(
        a_file(
            a_line(reference="LEGAL ID", cart="LID01"),
            a_line(at="07:10:30", reference="25-1044-1"),
            a_line(at="07:11:00", reference="", cart="SONG"),
        )
    )
    assert len(rows) == 3
    ours = spots_only(rows)
    assert len(ours) == 1
    assert ours[0].key == ("25-1044", 1)


def test_drift_is_measured_against_what_the_log_said():
    row = parse(a_file(a_line(at="07:10:04")))[0]
    assert row.drift_from(AirTime.at(7, 10)) == Duration.of_seconds(4)
    assert row.drift_from(AirTime.at(7, 10, 8)) == Duration.of_seconds(4)
    assert row.drift_from(AirTime.at(7, 10, 4)) == Duration(0)


def test_a_run_is_counted_by_status_for_the_morning_report():
    rows = parse(
        a_file(
            a_line(reference="25-1044-1"),
            a_line(at="08:10:00", reference="25-1044-2", status="skip"),
            a_line(at="09:10:00", reference="25-1044-3", status="error"),
            a_line(at="10:10:00", reference="25-1044-4"),
        )
    )
    assert by_status(rows) == {Played.ERROR: 1, Played.PLAYED: 2, Played.SKIPPED: 1}


def test_a_line_reads_the_way_the_operator_would_say_it():
    assert str(parse(a_file(a_line(status="skip")))[0]) == "7:10:00a C1204 skipped"


def test_the_file_is_named_the_way_the_watch_folder_writes_it():
    assert filename("KWLO", MONDAY) == "KWLO250804.ASR"
    assert filename("KOEL-FM", MONDAY) == "KOELFM250804.ASR"
    with pytest.raises(AsRunError, match="needs a station"):
        filename("", MONDAY)


def test_a_row_can_be_stamped_with_a_wall_clock_moment_for_sorting():
    row = parse(a_file(a_line(at="07:10:04")))[0]
    assert stamp(row.day, row.at) == datetime(2025, 8, 4, 7, 10, 4)


def test_a_file_with_a_header_and_nothing_else_reads_as_nothing():
    assert parse(HEADER + "\n") == []


def test_a_line_missing_a_column_says_which_one():
    text = "date,time,cart,title,length,status,reference\n2025-08-04,07:10:00,C1204\n"
    with pytest.raises(AsRunError, match="is missing title, length, status, reference"):
        parse(text)
