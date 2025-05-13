import pytest

from cartwall.airtime import Duration
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.logs.day import DayLog
from cartwall.playout.asrun import parse
from cartwall.reconcile.matcher import (
    ON_TIME,
    ReconcileError,
    missed_by_status,
    reconcile,
    worst_drift,
)
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY, a_grid

FIXED = ClassOfTime.FIXED
HEADER = "date,time,cart,title,length,status,reference"


@pytest.fixture
def grid():
    return a_grid("KWLO")


@pytest.fixture
def board(grid):
    return DayBoard.build(grid, MONDAY)


def put(board, hour, number, cart="C1204", line=1, length=THIRTY, code="HYVEE"):
    board.avail(hour, number).add(
        Placement(
            order="25-1044",
            line=line,
            advertiser=code,
            length=length,
            grade=FIXED,
            cart=cart,
        )
    )


def a_file(*lines):
    return "\n".join([HEADER, *lines]) + "\n"


def ran(at, cart="C1204", length="30.0", status="played", reference="25-1044-1", day="2025-08-04"):
    return f"{day},{at},{cart},a title,{length},{status},{reference}"


def test_a_clean_day_matches_everything_by_reference(grid, board):
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2, cart="C1205")
    log = DayLog.build(grid, board)
    rows = parse(
        a_file(
            ran("07:10:00", reference="25-1044-1"),
            ran("09:38:00", cart="C1205", reference="25-1044-2"),
        )
    )
    result = reconcile(log, rows)
    assert result.scheduled == 2
    assert result.aired == 2
    assert result.missed == []
    assert result.extra == []
    assert result.is_clean
    assert all(match.by_reference for match in result.matched)


def test_a_few_seconds_of_drift_is_still_on_time(grid, board):
    put(board, 7, 2)
    rows = parse(a_file(ran("07:10:04")))
    result = reconcile(DayLog.build(grid, board), rows)
    match = result.matched[0]
    assert match.drift == Duration.of_seconds(4)
    assert match.on_time
    assert not match.moved
    assert result.is_clean
    assert str(match) == "25-1044-1 on time"


def test_a_spot_that_ran_an_hour_late_is_moved_and_still_billable(grid, board):
    put(board, 7, 2)
    result = reconcile(DayLog.build(grid, board), parse(a_file(ran("08:10:00"))))
    match = result.matched[0]
    assert match.moved
    assert match.billable
    assert match.drift == Duration.of_seconds(3600)
    assert not result.is_clean
    assert result.moved == [match]
    assert "moved 1:00:00" in str(match)


def test_the_line_between_on_time_and_moved_is_five_minutes(grid, board):
    put(board, 7, 2)
    log = DayLog.build(grid, board)
    assert reconcile(log, parse(a_file(ran("07:15:00")))).matched[0].on_time
    assert reconcile(log, parse(a_file(ran("07:15:01")))).matched[0].moved
    assert Duration.of_seconds(300) == ON_TIME


def test_a_spot_the_automation_skipped_is_missed_with_its_own_reason(grid, board):
    put(board, 7, 2)
    rows = parse(a_file(ran("07:10:00", status="skip")))
    result = reconcile(DayLog.build(grid, board), rows)
    assert result.matched == []
    assert len(result.missed) == 1
    assert result.missed[0].reason == "skipped"
    assert "25-1044-1 missed: skipped" in str(result.missed[0])
    assert result.aired == 0


def test_a_spot_with_no_as_run_line_at_all_is_missed(grid, board):
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2, cart="C1205")
    rows = parse(a_file(ran("07:10:00", reference="25-1044-1")))
    result = reconcile(DayLog.build(grid, board), rows)
    assert [miss.key for miss in result.missed] == [("25-1044", 2)]
    assert result.missed[0].reason == "no as-run line"


def test_an_as_run_line_nobody_logged_is_extra(grid, board):
    put(board, 7, 2)
    rows = parse(
        a_file(
            ran("07:10:00"),
            ran("15:22:00", cart="C9999", reference="25-9999-1"),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    assert len(result.extra) == 1
    assert result.extra[0].aired.cart == "C9999"
    assert "was not on the log" in str(result.extra[0])


def test_a_line_with_no_reference_matches_on_cart_and_time(grid, board):
    put(board, 7, 2)
    rows = parse(a_file(ran("07:10:03", reference="")))
    result = reconcile(DayLog.build(grid, board), rows)
    assert len(result.matched) == 1
    assert not result.matched[0].by_reference
    assert result.matched[0].on_time


def test_two_runs_of_one_cart_match_the_nearer_scheduled_spot_each(grid, board):
    put(board, 7, 2, line=1)
    put(board, 15, 2, line=2)
    rows = parse(
        a_file(
            ran("15:10:05", reference=""),
            ran("07:10:05", reference=""),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    assert len(result.matched) == 2
    by_line = {match.scheduled.line: match for match in result.matched}
    assert by_line[1].aired.at.hour == 7
    assert by_line[2].aired.at.hour == 15
    assert all(match.on_time for match in result.matched)


def test_a_cart_match_outside_the_window_is_not_a_match(grid, board):
    put(board, 7, 2)
    rows = parse(a_file(ran("15:10:00", reference="")))
    result = reconcile(DayLog.build(grid, board), rows)
    assert result.matched == []
    assert len(result.missed) == 1
    assert len(result.extra) == 1


def test_the_window_can_be_widened_when_a_day_ran_badly_late(grid, board):
    put(board, 7, 2)
    rows = parse(a_file(ran("09:10:00", reference="")))
    tight = reconcile(DayLog.build(grid, board), rows)
    wide = reconcile(DayLog.build(grid, board), rows, window=Duration.of_seconds(3 * 3600))
    assert tight.matched == []
    assert len(wide.matched) == 1
    assert wide.matched[0].moved


def test_the_reference_pass_wins_over_the_cart_pass(grid, board):
    put(board, 7, 2, line=1)
    put(board, 7, 3, line=2)
    rows = parse(
        a_file(
            ran("07:22:05", reference="25-1044-1"),
            ran("07:10:05", reference=""),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    by_line = {match.scheduled.line: match for match in result.matched}
    assert by_line[1].by_reference
    assert by_line[1].aired.at.hour == 7
    assert by_line[1].aired.at.minute == 22
    assert not by_line[2].by_reference


def test_a_spot_that_ran_short_is_flagged_without_being_missed(grid, board):
    put(board, 7, 2, length=SIXTY)
    rows = parse(a_file(ran("07:10:00", length="19.0", status="trunc")))
    result = reconcile(DayLog.build(grid, board), rows)
    match = result.matched[0]
    assert match.short
    assert match.billable
    assert match.aired.status.aired


def test_an_as_run_for_another_day_is_refused(grid, board):
    put(board, 7, 2)
    rows = parse(a_file(ran("07:10:00", day="2025-08-05")))
    with pytest.raises(ReconcileError, match="line\\(s\\) for another day") as caught:
        reconcile(DayLog.build(grid, board), rows)
    assert caught.value.context["log_day"] == "2025-08-04"


def test_an_empty_day_reconciles_to_nothing(grid, board):
    result = reconcile(DayLog.build(grid, board), [])
    assert result.scheduled == 0
    assert result.is_clean
    assert worst_drift(result) == Duration(0)


def test_the_result_reads_as_a_sentence(grid, board):
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2, cart="C1205")
    rows = parse(a_file(ran("07:10:00", reference="25-1044-1")))
    result = reconcile(DayLog.build(grid, board), rows)
    assert str(result) == "KWLO 2025-08-04: 1 of 2 aired, 1 missed, 0 unlogged"


def test_what_a_line_is_owed_comes_back_worst_first(grid, board):
    for hour, line in ((7, 1), (9, 1), (11, 2), (13, 1)):
        put(board, hour, 2, line=line, cart=f"C120{line}")
    result = reconcile(DayLog.build(grid, board), [])
    owed = result.owed()
    assert owed == {("25-1044", 1): 3, ("25-1044", 2): 1}
    assert list(owed) == [("25-1044", 1), ("25-1044", 2)]


def test_the_missed_are_counted_by_the_reason_the_automation_gave(grid, board):
    put(board, 7, 2, line=1, cart="C1201")
    put(board, 9, 2, line=2, cart="C1202")
    put(board, 11, 2, line=3, cart="C1203")
    rows = parse(
        a_file(
            ran("07:10:00", cart="C1201", status="skip", reference="25-1044-1"),
            ran("09:10:00", cart="C1202", status="error", reference="25-1044-2"),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    assert missed_by_status(result) == {"error": 1, "no as-run line": 1, "skipped": 1}


def test_the_worst_drift_of_a_day_is_reported_for_the_morning_meeting(grid, board):
    put(board, 7, 2, line=1, cart="C1201")
    put(board, 9, 2, line=2, cart="C1202")
    rows = parse(
        a_file(
            ran("07:10:04", cart="C1201", reference="25-1044-1"),
            ran("09:22:00", cart="C1202", reference="25-1044-2"),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    assert worst_drift(result) == Duration.of_seconds(12 * 60)


def test_everything_comes_back_in_air_order(grid, board):
    for hour, line in ((15, 3), (7, 1), (9, 2)):
        put(board, hour, 2, line=line, cart=f"C120{line}")
    rows = parse(
        a_file(
            ran("15:10:00", cart="C1203", reference="25-1044-3"),
            ran("07:10:00", cart="C1201", reference="25-1044-1"),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    assert [match.aired.at.hour for match in result.matched] == [7, 15]
    assert [miss.scheduled.at.hour for miss in result.missed] == [9]


def test_the_result_finds_every_run_of_one_line(grid, board):
    put(board, 7, 2, line=1)
    put(board, 9, 2, line=1)
    rows = parse(
        a_file(
            ran("07:10:00", reference="25-1044-1"),
            ran("09:10:00", reference="25-1044-1"),
        )
    )
    result = reconcile(DayLog.build(grid, board), rows)
    assert len(result.for_line("25-1044", 1)) == 2
    assert result.for_line("25-1044", 9) == []
