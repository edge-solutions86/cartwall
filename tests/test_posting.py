from datetime import date, datetime

import pytest

from cartwall.airtime import AirTime, Duration
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logs.day import DayLog
from cartwall.persistence.base import create_all, engine_for, session_factory
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.repositories import AccountRepository, StationRepository
from cartwall.playout.asrun import Played, parse
from cartwall.reconcile.matcher import reconcile
from cartwall.reconcile.posting import NotAired, aired_spots, missed_spots, post
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY, a_grid

FIXED = ClassOfTime.FIXED
HEADER = "date,time,cart,title,length,status,reference"
SIGNED = datetime(2025, 8, 1, 16, 30)
TUESDAY = date(2025, 8, 5)


@pytest.fixture
def session(tmp_path, cluster, accounts):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'post.sqlite3'}")
    create_all(engine)
    with session_factory(engine)() as session:
        StationRepository(session).save_cluster(cluster)
        AccountRepository(session).save_book(accounts)
        session.commit()
        yield session


@pytest.fixture
def grid():
    return a_grid("KWLO")


def put(board, hour, number, cart="C1204", line=1, code="HYVEE"):
    board.avail(hour, number).add(
        Placement(
            order="25-1044",
            line=line,
            advertiser=code,
            length=THIRTY,
            grade=FIXED,
            cart=cart,
        )
    )


def a_file(*lines):
    return "\n".join([HEADER, *lines]) + "\n"


def ran(at, cart="C1204", status="played", reference="25-1044-1", length="30.0"):
    return f"2025-08-04,{at},{cart},a title,{length},{status},{reference}"


def aired_day(session, grid, board, day=MONDAY):
    """Walk a day all the way through to aired, which is where posting starts."""
    logs = LogRepository(session)
    logs.save(board)
    logs.approve("KWLO", day, "T. Ronan", SIGNED)
    logs.release("KWLO", day, SIGNED)
    logs.mark_aired("KWLO", day)
    return logs


def test_a_day_that_has_not_aired_cannot_be_posted(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    LogRepository(session).save(board)
    result = reconcile(DayLog.build(grid, board), parse(a_file(ran("07:10:00"))))
    with pytest.raises(NotAired, match="is draft, not aired") as caught:
        post(session, result)
    assert caught.value.context["state"] == "draft"


def test_a_day_that_was_never_built_cannot_be_posted(session, grid):
    board = DayBoard.build(grid, MONDAY)
    result = reconcile(DayLog.build(grid, board), [])
    with pytest.raises(NotAired, match="has no log for"):
        post(session, result)


def test_a_draft_day_can_be_posted_when_somebody_insists(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    LogRepository(session).save(board)
    result = reconcile(DayLog.build(grid, board), parse(a_file(ran("07:10:00"))))
    assert post(session, result, force=True).aired == 1


def test_a_spot_that_aired_is_written_back_with_when_and_how_long(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    logs = aired_day(session, grid, board)
    result = reconcile(DayLog.build(grid, board), parse(a_file(ran("07:10:04", length="29.7"))))
    report = post(session, result)
    assert report.aired == 1
    assert report.missed == 0

    spot = logs._require("KWLO", MONDAY).spots[0]
    assert spot.aired
    assert spot.aired_at == AirTime.at(7, 10, 4)
    assert spot.aired_length == Duration(297)
    assert spot.aired_status is Played.PLAYED


def test_a_spot_that_was_skipped_is_written_back_as_not_aired(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    logs = aired_day(session, grid, board)
    result = reconcile(DayLog.build(grid, board), parse(a_file(ran("07:10:00", status="skip"))))
    report = post(session, result)
    assert report.aired == 0
    assert report.missed == 1
    spot = logs._require("KWLO", MONDAY).spots[0]
    assert not spot.aired
    assert spot.aired_status is Played.SKIPPED
    assert spot.aired_at is None


def test_a_spot_with_no_as_run_line_is_left_unknown(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    logs = aired_day(session, grid, board)
    post(session, reconcile(DayLog.build(grid, board), []))
    spot = logs._require("KWLO", MONDAY).spots[0]
    assert not spot.aired
    assert spot.aired_status is Played.UNKNOWN


def test_a_moved_spot_is_counted_and_still_aired(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    logs = aired_day(session, grid, board)
    result = reconcile(DayLog.build(grid, board), parse(a_file(ran("08:22:00"))))
    report = post(session, result)
    assert report.aired == 1
    assert report.moved == 1
    assert logs._require("KWLO", MONDAY).spots[0].aired


def test_an_unlogged_as_run_line_is_counted_and_written_nowhere(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    aired_day(session, grid, board)
    rows = parse(a_file(ran("07:10:00"), ran("15:10:00", cart="C9999", reference="25-9999-1")))
    report = post(session, reconcile(DayLog.build(grid, board), rows))
    assert report.unmatched == 1
    assert report.aired == 1


def test_posting_twice_gives_the_same_answer(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2, cart="C1205")
    logs = aired_day(session, grid, board)
    rows = parse(a_file(ran("07:10:00", reference="25-1044-1")))
    first = post(session, reconcile(DayLog.build(grid, board), rows))
    second = post(session, reconcile(DayLog.build(grid, board), rows))
    assert (first.aired, first.missed) == (second.aired, second.missed)
    assert [spot.aired for spot in logs._require("KWLO", MONDAY).spots] == [True, False]


def test_a_corrected_as_run_posted_later_wins(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    logs = aired_day(session, grid, board)
    skipped = parse(a_file(ran("07:10:00", status="skip")))
    post(session, reconcile(DayLog.build(grid, board), skipped))
    assert not logs._require("KWLO", MONDAY).spots[0].aired
    post(session, reconcile(DayLog.build(grid, board), parse(a_file(ran("07:10:00")))))
    assert logs._require("KWLO", MONDAY).spots[0].aired


def test_the_report_reads_as_a_sentence(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2, cart="C1205")
    aired_day(session, grid, board)
    rows = parse(a_file(ran("07:10:00", reference="25-1044-1")))
    report = post(session, reconcile(DayLog.build(grid, board), rows))
    assert str(report) == ("KWLO 2025-08-04: 1 aired, 1 missed, 0 moved, 0 not on the log")


def test_billing_reads_only_what_aired(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2, cart="C1205")
    put(board, 15, 2, line=3, cart="C1206")
    aired_day(session, grid, board)
    rows = parse(
        a_file(
            ran("07:10:00", reference="25-1044-1"),
            ran("09:38:00", cart="C1205", reference="25-1044-2"),
            ran("15:10:00", cart="C1206", status="error", reference="25-1044-3"),
        )
    )
    post(session, reconcile(DayLog.build(grid, board), rows))
    session.commit()

    billable = aired_spots(session, "25-1044", MONDAY, TUESDAY)
    assert [spot.line for spot in billable] == [1, 2]
    owed = missed_spots(session, "25-1044", MONDAY, TUESDAY)
    assert [spot.line for spot in owed] == [3]


def test_a_day_that_has_not_aired_is_not_owed_anything_yet(session, grid):
    board = DayBoard.build(grid, MONDAY)
    put(board, 7, 2)
    LogRepository(session).save(board)
    session.commit()
    assert missed_spots(session, "25-1044", MONDAY, TUESDAY) == []
    assert aired_spots(session, "25-1044", MONDAY, TUESDAY) == []
