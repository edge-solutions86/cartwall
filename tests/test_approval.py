from datetime import datetime

import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logs.approval import (
    ApprovalError,
    LogFrozen,
    LogNotReady,
    LogRegister,
    LogState,
    missing_copy,
    problems,
    warnings,
)
from cartwall.logs.day import DayLog
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY

FIXED = ClassOfTime.FIXED

SIGNED = datetime(2025, 8, 1, 16, 30)
SENT = datetime(2025, 8, 1, 17, 5)


@pytest.fixture
def grid(house):
    return house.programming["KWLO"]


@pytest.fixture
def board(grid):
    return DayBoard.build(grid, MONDAY)


def put(board, hour, number, code="HYVEE", cart="C1204", order="25-1044", line=1):
    board.avail(hour, number).add(
        Placement(
            order=order,
            line=line,
            advertiser=code,
            length=THIRTY,
            grade=FIXED,
            cart=cart,
        )
    )


def test_an_empty_log_has_nothing_wrong_with_it(grid, board):
    log = DayLog.build(grid, board)
    assert problems(log) == []


def test_a_spot_with_no_cart_stops_the_log(grid, board):
    put(board, 7, 2, cart="")
    found = problems(DayLog.build(grid, board))
    assert len(found) == 1
    assert found[0].code == "missing_copy"
    assert "25-1044-1" in found[0].detail
    assert "7:10:00a" in str(found[0])


def test_every_spot_without_copy_is_listed_once_per_order_line(grid, board):
    put(board, 7, 2, cart="", line=1)
    put(board, 8, 2, cart="", line=1)
    put(board, 9, 2, cart="", line=2)
    put(board, 9, 3, cart="C1204", line=3)
    log = DayLog.build(grid, board)
    assert len(problems(log)) == 3
    assert missing_copy(log) == ["25-1044-1", "25-1044-2"]


def test_unsold_units_are_a_warning_and_not_a_problem(grid, board):
    put(board, 7, 2)
    log = DayLog.build(grid, board)
    assert problems(log) == []
    said = warnings(log)
    assert len(said) == 1
    assert said[0].code == "unsold"
    assert "units did not sell" in str(said[0])


def test_a_full_day_warns_about_nothing(grid, board):
    for avail in board.avails:
        for line in range(avail.units):
            put(board, avail.hour, avail.number, line=line + 1)
    assert warnings(DayLog.build(grid, board)) == []


def test_a_new_station_day_starts_in_draft():
    register = LogRegister()
    status = register.status("KWLO", MONDAY)
    assert status.state is LogState.DRAFT
    assert status.revision == 0
    assert register.may_edit("KWLO", MONDAY)
    assert str(status) == "KWLO 2025-08-04 draft"


def test_approving_records_who_signed_it_and_when(grid, board):
    register = LogRegister()
    put(board, 7, 2)
    status = register.approve(DayLog.build(grid, board), "T. Ronan", SIGNED)
    assert status.state is LogState.APPROVED
    assert status.approved_by == "T. Ronan"
    assert status.approved_at == SIGNED
    assert not register.may_edit("KWLO", MONDAY)


def test_a_log_with_problems_is_not_signed(grid, board):
    register = LogRegister()
    put(board, 7, 2, cart="")
    with pytest.raises(LogNotReady, match="1 problem") as caught:
        register.approve(DayLog.build(grid, board), "T. Ronan", SIGNED)
    assert caught.value.code == "log_not_ready"
    assert "missing_copy" in caught.value.context["problems"][0]
    assert register.status("KWLO", MONDAY).state is LogState.DRAFT


def test_a_log_has_to_be_signed_by_a_person(grid, board):
    register = LogRegister()
    with pytest.raises(ApprovalError, match="approved by somebody"):
        register.approve(DayLog.build(grid, board), "", SIGNED)


def test_a_log_is_not_signed_twice(grid, board):
    register = LogRegister()
    log = DayLog.build(grid, board)
    register.approve(log, "T. Ronan", SIGNED)
    with pytest.raises(LogFrozen, match="already approved"):
        register.approve(log, "T. Ronan", SIGNED)


def test_the_states_only_run_forward(grid, board):
    register = LogRegister()
    log = DayLog.build(grid, board)
    with pytest.raises(LogFrozen, match="is draft and cannot be released"):
        register.release("KWLO", MONDAY, SENT)
    register.approve(log, "T. Ronan", SIGNED)
    with pytest.raises(LogFrozen, match="did not go to air from here"):
        register.mark_aired("KWLO", MONDAY)
    register.release("KWLO", MONDAY, SENT)
    assert register.status("KWLO", MONDAY).released_at == SENT
    register.mark_aired("KWLO", MONDAY)
    assert register.status("KWLO", MONDAY).state is LogState.AIRED


def test_a_released_log_cannot_be_edited_in_place(grid, board):
    register = LogRegister()
    register.approve(DayLog.build(grid, board), "T. Ronan", SIGNED)
    register.release("KWLO", MONDAY, SENT)
    with pytest.raises(LogFrozen, match="is released at revision 0") as caught:
        register.check_editable("KWLO", MONDAY)
    assert caught.value.context["revision"] == 0


def test_a_draft_log_can_be_edited(grid, board):
    register = LogRegister()
    register.check_editable("KWLO", MONDAY)
    assert register.may_edit("KWLO", MONDAY)


def test_revising_a_released_log_puts_it_back_in_draft_one_higher(grid, board):
    register = LogRegister()
    log = DayLog.build(grid, board)
    register.approve(log, "T. Ronan", SIGNED)
    register.release("KWLO", MONDAY, SENT)
    status = register.revise("KWLO", MONDAY)
    assert status.state is LogState.DRAFT
    assert status.revision == 1
    assert status.approved_by == ""
    assert status.approved_at is None
    assert status.released_at is None
    assert register.may_edit("KWLO", MONDAY)
    assert str(status) == "KWLO 2025-08-04 draft rev 1"


def test_a_revision_has_to_be_approved_and_released_again(grid, board):
    register = LogRegister()
    log = DayLog.build(grid, board)
    register.approve(log, "T. Ronan", SIGNED)
    register.release("KWLO", MONDAY, SENT)
    register.revise("KWLO", MONDAY)
    register.approve(log, "T. Ronan", SIGNED)
    status = register.release("KWLO", MONDAY, SENT)
    assert status.state is LogState.RELEASED
    assert status.revision == 1


def test_a_log_that_never_went_out_does_not_need_a_revision(grid, board):
    register = LogRegister()
    with pytest.raises(ApprovalError, match="does not need a revision"):
        register.revise("KWLO", MONDAY)
    register.approve(DayLog.build(grid, board), "T. Ronan", SIGNED)
    with pytest.raises(ApprovalError, match="does not need a revision"):
        register.revise("KWLO", MONDAY)


def test_an_aired_log_can_still_be_revised_because_the_billing_reads_it(grid, board):
    register = LogRegister()
    register.approve(DayLog.build(grid, board), "T. Ronan", SIGNED)
    register.release("KWLO", MONDAY, SENT)
    register.mark_aired("KWLO", MONDAY)
    assert register.revise("KWLO", MONDAY).revision == 1


def test_the_register_lists_what_has_gone_out_and_what_is_still_waiting(grid, house):
    register = LogRegister()
    for call in ("KWLO", "KCRR"):
        grid = house.programming[call]
        log = DayLog.build(grid, DayBoard.build(grid, MONDAY))
        register.approve(log, "T. Ronan", SIGNED)
    register.release("KWLO", MONDAY, SENT)
    register.status("KXEL", MONDAY)
    assert [status.station for status in register.released()] == ["KWLO"]
    assert [status.station for status in register.waiting()] == ["KXEL"]
    assert len(register) == 3
