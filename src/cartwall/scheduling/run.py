"""Scheduling a whole order, which is where the week runs stop being independent.

One line at a time is the easy case and the previous module does it. An order
has several lines, they often share an advertiser, and they are frequently on
the same station in overlapping dayparts. Line two has to see what line one
did, or the two of them will sit four minutes apart and the client will hear
their own commercial twice.

That means one board per station per date, shared by every line that touches
it, held for the length of the run. The cache is the point of this module. A
run that rebuilds the board per line is not slow, it is wrong.

Line order matters and it is not the order they were typed. Fixed position
lines go first because they have nowhere else to go, then the rest by class,
dearest first. A cheap run of schedule line placed first will sit in the drive
break that the fixed line needed, and the fixed line has no second choice.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..broadcast import BroadcastWeek
from ..domain.account import AccountBook
from ..domain.order import Line, Order
from ..domain.rotation import Rotation
from ..domain.rulebook import House, RuleBook
from ..errors import ValidationError
from .candidates import Ask
from .inventory import DayBoard
from .placer import Lay, Result, place_week


class RunError(ValidationError):
    """A run that could not be started."""

    code = "bad_run"


@dataclass
class Boards:
    """One board per station per date, built once and shared for the run."""

    house: House
    _boards: dict[tuple[str, date], DayBoard] = field(default_factory=dict)
    _books: dict[tuple[str, date], RuleBook] = field(default_factory=dict)
    _lays: dict[tuple[str, date], Lay] = field(default_factory=dict)

    def book(self, call: str, day: date) -> RuleBook:
        key = (call, day)
        if key not in self._books:
            self._books[key] = self.house.book(call, day)
        return self._books[key]

    def board(self, call: str, day: date) -> DayBoard:
        key = (call, day)
        if key not in self._boards:
            self._boards[key] = DayBoard.build(self.house.programming[call], day)
        return self._boards[key]

    def lay(self, call: str, day: date) -> Lay:
        key = (call, day)
        if key not in self._lays:
            self._lays[key] = Lay(self.board(call, day), self.book(call, day))
        return self._lays[key]

    def days(self) -> list[date]:
        return sorted({day for _, day in self._boards})

    def stations(self) -> list[str]:
        return sorted({call for call, _ in self._boards})

    def __len__(self) -> int:
        return len(self._boards)


@dataclass
class LineResult:
    """What one line of an order came to in one week."""

    line: Line
    week: BroadcastWeek
    result: Result

    @property
    def wanted(self) -> int:
        return self.line.flight.count_in(self.week)

    @property
    def placed(self) -> int:
        return self.result.placed

    @property
    def is_complete(self) -> bool:
        return self.result.is_complete

    def __str__(self) -> str:
        return f"line {self.line.number} {self.placed} of {self.wanted}"


@dataclass
class OrderResult:
    """What a whole order came to in one week."""

    order: Order
    week: BroadcastWeek
    lines: list[LineResult] = field(default_factory=list)

    @property
    def wanted(self) -> int:
        return sum(line.wanted for line in self.lines)

    @property
    def placed(self) -> int:
        return sum(line.placed for line in self.lines)

    @property
    def is_complete(self) -> bool:
        return all(line.is_complete for line in self.lines)

    def shortfalls(self) -> list[LineResult]:
        return [line for line in self.lines if not line.is_complete]

    def __str__(self) -> str:
        return f"{self.order.number} {self.placed} of {self.wanted} in {self.week}"


def placement_order(lines: tuple[Line, ...]) -> list[Line]:
    """Hardest to place first: fixed position, then dearest, then as typed."""
    return sorted(
        lines,
        key=lambda line: (-line.grade.rank, -line.rate.cents, line.number),
    )


def schedule_order(
    boards: Boards,
    accounts: AccountBook,
    order: Order,
    week: BroadcastWeek,
    rotations: dict[int, Rotation] | None = None,
) -> OrderResult:
    """Place every line of ``order`` that runs in ``week``."""
    if not order.status.may_schedule:
        raise RunError(
            f"order {order.number} is {order.status.value} and cannot be scheduled",
            order=order.number,
            status=order.status.value,
        )
    if not accounts.may_schedule(order.advertiser):
        raise RunError(
            f"{order.advertiser} is on credit hold",
            order=order.number,
            advertiser=order.advertiser,
        )
    rotations = rotations or {}
    result = OrderResult(order, week)
    for line in placement_order(order.lines):
        count = line.flight.count_in(week)
        days = line.flight.days_in(week)
        call = boards.house.logging_call(line.station)
        lays = [boards.lay(call, day) for day in days]
        ask = Ask(
            order=order.number,
            line=line.number,
            advertiser=accounts.advertiser(order.advertiser),
            daypart=line.daypart,
            length=line.length,
            grade=line.grade,
            cart=line.carts[0] if line.carts else "",
            agency=order.agency,
        )
        rotation = rotations.get(line.number)
        if rotation is None and len(line.carts) > 1:
            rotation = Rotation.even(*line.carts)
        placed = place_week(lays, accounts, ask, count, rotation)
        result.lines.append(LineResult(line, week, placed))
    result.lines.sort(key=lambda entry: entry.line.number)
    return result


def schedule_all(
    boards: Boards,
    accounts: AccountBook,
    orders: list[Order],
    week: BroadcastWeek,
) -> list[OrderResult]:
    """Run a week's book. Confirmed orders only, dearest order first."""
    live = [order for order in orders if order.status.may_schedule]
    live.sort(key=lambda order: (-order.value_in(week).cents, order.number))
    return [schedule_order(boards, accounts, order, week) for order in live]
