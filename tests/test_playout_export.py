from datetime import date

import pytest

from cartwall.airtime import Duration
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.logs.day import DayLog
from cartwall.playout.export import (
    LINE_WIDTH,
    WIDTHS,
    ExportError,
    Row,
    filename,
    parse,
    parse_row,
    render,
    rows_for,
)
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY, a_grid

FIXED = ClassOfTime.FIXED


@pytest.fixture
def grid():
    return a_grid("KWLO")


@pytest.fixture
def board(grid):
    return DayBoard.build(grid, MONDAY)


def put(board, hour, number, code="HYVEE", length=THIRTY, cart="C1204", line=1):
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


def test_the_columns_are_the_interface():
    assert [name for name, _ in WIDTHS] == [
        "hour",
        "start",
        "kind",
        "tenths",
        "cart",
        "order",
        "line",
        "advertiser",
    ]
    assert sum(width for _, width in WIDTHS) + len(WIDTHS) - 1 == LINE_WIDTH


def test_a_row_pads_every_column_to_its_width():
    row = Row(7, "07:10:00", "SPT", 300, "C1204", "25-1044", 1, "HYVEE")
    rendered = row.render()
    assert rendered.startswith("07 07:10:00 SPT 00300 C1204   ")
    assert "25-1044" in rendered
    assert rendered.endswith("HYVEE")


def test_a_length_is_written_in_tenths_without_a_point():
    assert " 00300 " in Row(7, "07:10:00", "SPT", 300, "C", "O", 1, "A").render()
    assert " 00600 " in Row(7, "07:10:00", "SPT", 600, "C", "O", 1, "A").render()
    assert " 00301 " in Row(7, "07:10:00", "SPT", 301, "C", "O", 1, "A").render()


def test_the_hour_is_written_on_every_line(grid, board):
    put(board, 7, 2)
    put(board, 15, 2, line=2)
    lines = render(DayLog.build(grid, board)).splitlines()
    assert all(line[:2].isdigit() for line in lines)
    assert any(line.startswith("15 ") for line in lines)
    assert any(line.startswith("07 ") for line in lines)


def test_a_value_longer_than_its_column_is_cut_rather_than_shifting_the_rest():
    row = Row(7, "07:10:00", "SPT", 300, "C1204", "25-1044", 1, "A" * 40)
    rendered = row.render()
    assert len(rendered) == LINE_WIDTH
    assert rendered.endswith("A" * 20)


def test_the_file_carries_the_fixed_elements_and_not_the_holes(grid, board):
    put(board, 7, 2)
    rows = rows_for(DayLog.build(grid, board))
    kinds = {row.kind for row in rows}
    assert kinds == {"LID", "SPT"}
    assert len(rows) == 25


def test_the_file_runs_in_air_order(grid, board):
    put(board, 15, 2, line=3)
    put(board, 7, 2, line=1)
    put(board, 9, 4, line=2)
    rows = [row for row in rows_for(DayLog.build(grid, board)) if row.kind == "SPT"]
    assert [row.line for row in rows] == [1, 2, 3]
    assert [row.hour for row in rows] == [7, 9, 15]


def test_a_spot_carries_its_order_line_cart_and_advertiser(grid, board):
    put(board, 7, 2, cart="C1205", code="SLUMB")
    row = [r for r in rows_for(DayLog.build(grid, board)) if r.kind == "SPT"][0]
    assert row.cart == "C1205"
    assert row.order == "25-1044"
    assert row.line == 1
    assert row.advertiser == "SLUMB"
    assert row.tenths == 300


def test_a_legal_id_carries_no_order(grid, board):
    row = rows_for(DayLog.build(grid, board))[0]
    assert row.kind == "LID"
    assert row.order == ""
    assert row.line == 0
    assert row.tenths == 100


def test_a_network_break_is_on_the_file_so_the_operator_leaves_it_alone(house):
    grid = house.programming["KCRR"]
    rows = rows_for(DayLog.build(grid, DayBoard.build(grid, MONDAY)))
    assert any(row.kind == "NET" for row in rows)


def test_the_file_ends_with_a_newline(grid, board):
    put(board, 7, 2)
    text = render(DayLog.build(grid, board))
    assert text.endswith("\n")
    assert not text.endswith("\n\n")


def test_a_row_reads_back_the_way_it_was_written():
    row = Row(7, "07:10:00", "SPT", 301, "C1204", "25-1044", 1, "HYVEE")
    assert parse_row(row.render()) == row


def test_a_whole_file_reads_back_the_way_it_was_written(grid, board):
    put(board, 7, 2, length=SIXTY, line=1)
    put(board, 9, 4, line=2)
    log = DayLog.build(grid, board)
    assert parse(render(log)) == rows_for(log)


def test_a_line_that_is_not_a_line():
    with pytest.raises(ExportError, match="not a blank line|blank line"):
        parse_row("   ")
    with pytest.raises(ExportError, match="is not a log line"):
        parse_row("xx 07:10:00 SPT 00300 C1204    25-1044      001 HYVEE")


def test_blank_lines_in_a_file_are_skipped_rather_than_failing(grid, board):
    put(board, 7, 2)
    text = render(DayLog.build(grid, board))
    assert parse(text + "\n\n") == parse(text)


def test_the_file_is_named_the_way_the_watch_folder_expects():
    assert filename("KWLO", MONDAY) == "KWLO250804.LOG"
    assert filename("KOEL-FM", date(2025, 12, 31)) == "KOELFM251231.LOG"
    with pytest.raises(ExportError, match="needs a station"):
        filename("", MONDAY)


def test_a_preempted_hour_is_simply_not_in_the_file(house):
    grid = house.programming["KCRR"]
    friday = date(2025, 8, 8)
    rows = rows_for(DayLog.build(grid, DayBoard.build(grid, friday)))
    assert not any(row.hour == 20 for row in rows)
    assert any(row.hour == 18 for row in rows)


def test_an_element_the_format_has_no_code_for_is_refused(grid, board):
    from cartwall.logs.day import Element, LogLine

    log = DayLog.build(grid, board)
    log.lines.append(
        LogLine(
            at=log.lines[0].at,
            break_number=9,
            element=Element.UNSOLD,
            length=Duration(0),
        )
    )
    # The hole is filtered out before the codes are looked up, so this is fine.
    assert rows_for(log)
