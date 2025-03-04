"""What is left to sell, which is the only report the sales manager opens.

Three shapes of the same question, and they are wanted at different moments:

* **By daypart.** What is open in morning drive next week. This is what a
  seller asks before quoting, and it has to price the hole as well as count it,
  because forty open units in overnight and four in drive are not the same
  inventory however the units add up.
* **By hour.** Where the day is thin. This is what the traffic director reads
  when a schedule will not place, and the answer is usually one hour that
  everybody wants.
* **By day.** Which days of the week are sold out. Fridays go first, Mondays
  last, and a station that does not know that prices them the same.

Dayparts overlap on purpose, so a unit in morning drive is also a unit in AAA
and in ROS, and the three rows will not add up to the day. That is correct and
it is the first thing anybody reading this report gets wrong. Every row is
counted against its own daypart independently, and the report says so.

Value is at the card, at the class the daypart is normally sold in, for a
thirty. It is what the hole would fetch at rate card and not what it will
actually fetch, and nobody should mistake the two. It is a ceiling.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ..domain.daypart import Weekday
from ..domain.ratecard import ClassOfTime, RateError
from ..domain.rulebook import RuleBook
from ..domain.spot import THIRTY
from ..errors import ValidationError
from ..money import ZERO, Money, total
from ..scheduling.inventory import DayBoard


class ReportError(ValidationError):
    """A report that cannot be run as asked."""

    code = "bad_report"


@dataclass(frozen=True, slots=True)
class Row:
    """One line of an avails report."""

    label: str
    units: int
    sold: int
    value: Money = ZERO

    @property
    def open(self) -> int:
        return self.units - self.sold

    @property
    def sellout(self) -> float:
        if not self.units:
            return 0.0
        return self.sold / self.units

    def __str__(self) -> str:
        counted = f"{self.sold:>5} of {self.units:<5}"
        return f"{self.label:<8} {counted} {self.sellout:>6.1%} {self.value}"


def hour_label(hour: int) -> str:
    """``12:00a``, ``7:00a``, ``1:00p``. The way the report prints an hour."""
    meridiem = "a" if hour < 12 else "p"
    return f"{hour % 12 or 12}:00{meridiem}"


def _rate_for(book: RuleBook, daypart: str) -> Money:
    """What a thirty in this daypart lists at, cheapest class the card quotes."""
    for grade in (ClassOfTime.IMMEDIATE, ClassOfTime.NOTICE, ClassOfTime.FIXED):
        try:
            return book.rate(daypart, grade, THIRTY)
        except RateError:
            continue
    return ZERO


def by_daypart(board: DayBoard, book: RuleBook) -> list[Row]:
    """One row per daypart the station sells, narrowest first.

    Rows overlap. Morning drive is inside AAA is inside ROS, and adding the
    three together counts the same unit three times.
    """
    if board.station != book.call:
        raise ReportError(f"{book.call}'s card was handed {board.station}'s board")
    weekday = Weekday(board.day.weekday())
    rows = []
    for daypart in sorted(book.dayparts.dayparts, key=lambda part: len(part.cells)):
        units = sold = 0
        for avail in board.avails:
            if not daypart.holds(weekday, avail.starts_at):
                continue
            units += avail.units
            sold += avail.used_units
        if not units:
            continue
        rows.append(
            Row(daypart.code, units, sold, _rate_for(book, daypart.code) * (units - sold))
        )
    return rows


def by_hour(board: DayBoard) -> list[Row]:
    """One row per hour of the day the station is selling, in air order."""
    rows = []
    for hour in board.hours():
        avails = board.in_hour(hour)
        units = sum(avail.units for avail in avails)
        sold = sum(avail.used_units for avail in avails)
        rows.append(Row(hour_label(hour), units, sold))
    return rows


def by_day(boards: list[DayBoard]) -> list[Row]:
    """One row per date, in order. The boards must all be one station."""
    stations = {board.station for board in boards}
    if len(stations) > 1:
        raise ReportError(f"a day report covers one station, not {len(stations)}")
    return [
        Row(f"{board.day:%a %m/%d}", board.total_units, board.used_units)
        for board in sorted(boards, key=lambda board: board.day)
    ]


def thinnest_hours(board: DayBoard, how_many: int = 3) -> list[Row]:
    """The hours with the least left, which is where a schedule will jam."""
    rows = [row for row in by_hour(board) if row.units]
    rows.sort(key=lambda row: (row.open, -row.sellout, row.label))
    return rows[:how_many]


def week_open_units(boards: list[DayBoard]) -> int:
    return sum(board.free_units for board in boards)


def week_value(boards: list[DayBoard], books: dict[date, RuleBook], daypart: str) -> Money:
    """What the open units in one daypart would fetch at card across a week."""
    amounts = []
    for board in boards:
        book = books.get(board.day)
        if book is None:
            raise ReportError(f"no rules were given for {board.day}")
        for row in by_daypart(board, book):
            if row.label == daypart:
                amounts.append(row.value)
    return total(amounts)
