from datetime import date, datetime

import pytest

from cartwall.airtime import AirTime
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.errors import LookupFailure
from cartwall.logs.approval import LogState
from cartwall.persistence.base import create_all, drop_all, engine_for, session_factory
from cartwall.persistence.logstore import (
    BumpRepository,
    LogAlreadyOut,
    LogRepository,
    board_from,
    spot_rows,
)
from cartwall.persistence.repositories import AccountRepository, StationRepository
from cartwall.scheduling.bumping import Bumped, BumpQueue
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY, a_grid

FIXED = ClassOfTime.FIXED
IMMEDIATE = ClassOfTime.IMMEDIATE

SIGNED = datetime(2025, 8, 1, 16, 30)
SENT = datetime(2025, 8, 1, 17, 5)
TUESDAY = date(2025, 8, 5)


@pytest.fixture
def session(tmp_path, cluster, accounts):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'logstore.sqlite3'}")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        StationRepository(session).save_cluster(cluster)
        AccountRepository(session).save_book(accounts)
        session.commit()
        yield session
    drop_all(engine)


@pytest.fixture
def grid():
    return a_grid("KWLO")


@pytest.fixture
def board(grid):
    return DayBoard.build(grid, MONDAY)


def put(board, hour, number, code="HYVEE", length=THIRTY, order="25-1044", line=1, **kw):
    board.avail(hour, number).add(
        Placement(
            order=order,
            line=line,
            advertiser=code,
            length=length,
            grade=kw.pop("grade", FIXED),
            cart=kw.pop("cart", "C1204"),
            fixed=kw.pop("fixed", False),
        )
    )


def a_bump(day=MONDAY, line=3):
    return Bumped(
        placement=Placement(
            order="25-0100", line=line, advertiser="SLUMB", length=THIRTY, grade=IMMEDIATE
        ),
        day=day,
        hour=7,
        number=2,
        by_order="25-9000",
        by_line=1,
    )


def test_a_board_turns_into_rows_in_air_order(board):
    put(board, 9, 4, line=3)
    put(board, 7, 2, line=1)
    put(board, 7, 2, line=2, length=SIXTY)
    rows = spot_rows(board)
    assert [row.line for row in rows] == [1, 2, 3]
    assert [row.position for row in rows] == [0, 1, 0]
    assert [row.at for row in rows] == [
        AirTime.at(7, 10),
        AirTime.at(7, 10, 30),
        AirTime.at(9, 38),
    ]


def test_rows_come_back_as_a_board_with_the_same_spots(grid, board):
    put(board, 7, 2, line=1)
    put(board, 7, 2, line=2, length=SIXTY)
    put(board, 15, 3, line=3)
    back = board_from(grid, MONDAY, spot_rows(board))
    assert back.used_units == board.used_units
    assert back.avail(7, 2).used_units == 3
    assert [p.line for p, _ in back.placements()] == [1, 2, 3]


def test_rows_out_of_order_still_rebuild_the_break_in_order(grid, board):
    put(board, 7, 2, line=1, length=SIXTY)
    put(board, 7, 2, line=2)
    back = board_from(grid, MONDAY, list(reversed(spot_rows(board))))
    assert [p.line for p in back.avail(7, 2).placements] == [1, 2]


def test_a_pinned_spot_stays_pinned_through_the_round_trip(grid, board):
    put(board, 7, 2, fixed=True)
    back = board_from(grid, MONDAY, spot_rows(board))
    assert back.avail(7, 2).placements[0].fixed


def test_a_board_goes_in_and_comes_back(session, grid, board):
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2)
    repo = LogRepository(session)
    repo.save(board)
    session.commit()
    back = repo.board(grid, MONDAY)
    assert back.used_units == 2
    assert [p.line for p, _ in back.placements()] == [1, 2]


def test_a_saved_log_starts_in_draft(session, board):
    repo = LogRepository(session)
    status = repo.save(board)
    assert status.state is LogState.DRAFT
    assert status.revision == 0
    assert repo.exists("KWLO", MONDAY)
    assert not repo.exists("KWLO", TUESDAY)


def test_saving_again_writes_over_the_draft(session, grid, board):
    repo = LogRepository(session)
    put(board, 7, 2, line=1)
    repo.save(board)
    session.commit()
    put(board, 7, 3, line=2)
    repo.save(board)
    session.commit()
    assert repo.board(grid, MONDAY).used_units == 2


def test_a_released_log_is_not_written_over(session, board):
    repo = LogRepository(session)
    repo.save(board)
    repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
    repo.release("KWLO", MONDAY, SENT)
    session.commit()
    with pytest.raises(LogAlreadyOut, match="revise it before writing over it") as caught:
        repo.save(board)
    assert caught.value.context["state"] == "released"


def test_a_released_log_can_be_written_over_when_somebody_insists(session, board):
    repo = LogRepository(session)
    repo.save(board)
    repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
    repo.release("KWLO", MONDAY, SENT)
    put(board, 7, 2)
    assert repo.save(board, force=True).state is LogState.RELEASED


def test_revising_opens_it_back_up_and_clears_the_signature(session, board):
    repo = LogRepository(session)
    repo.save(board)
    repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
    repo.release("KWLO", MONDAY, SENT)
    status = repo.revise("KWLO", MONDAY)
    assert status.state is LogState.DRAFT
    assert status.revision == 1
    assert status.approved_by == ""
    assert status.released_at is None
    repo.save(board)


def test_the_signature_and_the_moment_survive_a_commit(session, board):
    repo = LogRepository(session)
    repo.save(board)
    repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
    session.commit()
    session.expire_all()
    status = repo.status("KWLO", MONDAY)
    assert status.approved_by == "T. Ronan"
    assert status.approved_at == SIGNED


def test_asking_for_a_day_that_was_never_built(session, grid):
    repo = LogRepository(session)
    with pytest.raises(LookupFailure, match="has no log for 2025-08-05"):
        repo.status("KWLO", TUESDAY)
    with pytest.raises(LookupFailure, match="has no log for"):
        repo.board(grid, TUESDAY)
    with pytest.raises(LookupFailure, match="has no log for"):
        repo.approve("KWLO", TUESDAY, "T. Ronan", SIGNED)


def test_a_span_of_days_comes_back_in_date_order(session, grid):
    repo = LogRepository(session)
    for day in (TUESDAY, MONDAY, date(2025, 8, 6)):
        repo.save(DayBoard.build(grid, day))
    session.commit()
    span = repo.between("KWLO", MONDAY, date(2025, 8, 6))
    assert [status.day for status in span] == [MONDAY, TUESDAY, date(2025, 8, 6)]
    assert repo.between("KWLO", date(2025, 9, 1), date(2025, 9, 30)) == []


def test_every_spot_of_one_order_across_a_span_comes_back_in_air_order(session, grid):
    repo = LogRepository(session)
    for day, hour in ((MONDAY, 9), (MONDAY, 7), (TUESDAY, 8)):
        board = repo.board(grid, day) if repo.exists("KWLO", day) else DayBoard.build(grid, day)
        put(board, hour, 2, order="25-1044", line=1)
        repo.save(board)
    put_other = DayBoard.build(grid, date(2025, 8, 6))
    put(put_other, 7, 2, order="25-2000", line=1)
    repo.save(put_other)
    session.commit()

    found = repo.spots_for("25-1044", MONDAY, date(2025, 8, 10))
    assert [(row.log.day, row.hour) for row in found] == [
        (MONDAY, 7),
        (MONDAY, 9),
        (TUESDAY, 8),
    ]
    assert repo.spots_for("25-9999", MONDAY, TUESDAY) == []


def test_a_bump_is_recorded_against_the_station_and_the_day(session):
    repo = BumpRepository(session)
    row = repo.record("KWLO", a_bump())
    session.commit()
    assert row.station == "KWLO"
    assert row.key == ("25-0100", 3)
    assert not row.made_good


def test_a_whole_queue_is_recorded_at_once(session):
    queue = BumpQueue()
    queue.add(a_bump(line=1))
    queue.add(a_bump(line=1))
    queue.add(a_bump(day=TUESDAY, line=2))
    repo = BumpRepository(session)
    assert len(repo.record_queue("KWLO", queue)) == 3
    session.commit()
    assert len(repo.outstanding()) == 3
    assert repo.owed() == {("25-0100", 1): 2, ("25-0100", 2): 1}


def test_outstanding_bumps_come_back_oldest_first(session):
    repo = BumpRepository(session)
    repo.record("KWLO", a_bump(day=TUESDAY, line=2))
    repo.record("KWLO", a_bump(day=MONDAY, line=1))
    session.commit()
    assert [row.day for row in repo.outstanding()] == [MONDAY, TUESDAY]


def test_settling_a_bump_takes_it_off_the_outstanding_list(session):
    repo = BumpRepository(session)
    row = repo.record("KWLO", a_bump())
    session.commit()
    repo.settle(row.id)
    session.commit()
    assert repo.outstanding() == []
    assert repo.owed() == {}


def test_settling_a_bump_that_is_not_on_file(session):
    with pytest.raises(LookupFailure, match="bump 99 is not on file"):
        BumpRepository(session).settle(99)


def test_the_outstanding_list_can_be_narrowed_to_one_order(session):
    repo = BumpRepository(session)
    repo.record("KWLO", a_bump(line=1))
    other = a_bump(line=1)
    session.add(repo.record("KWLO", other))
    session.commit()
    assert len(repo.outstanding("25-0100")) == 2
    assert repo.outstanding("25-9999") == []


def test_rebuilding_a_day_clears_the_bumps_it_had(session):
    repo = BumpRepository(session)
    repo.record("KWLO", a_bump(day=MONDAY))
    repo.record("KWLO", a_bump(day=TUESDAY))
    session.commit()
    assert repo.clear_day("KWLO", MONDAY) == 1
    session.commit()
    assert [row.day for row in repo.outstanding()] == [TUESDAY]
