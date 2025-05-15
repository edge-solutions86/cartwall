"""The nightly run: read the orders, place the week, write the days down.

This is the job that ties everything together, and it is the one place where
the order of operations is a business decision rather than a technical one.

1. **Load the house** for the week: cluster, accounts, cards, grids. Once, not
   per station, because a rule book built twice in one run can be built from
   two different reads.
2. **Take the orders running that week**, confirmed only.
3. **Restore what is already on the boards.** A rebuild is not a fresh start.
   Pinned spots stay where the traffic director put them, and a day that has
   already gone out is not touched at all.
4. **Place**, dearest order first, sharing one board per station per date.
5. **Write** every day that was touched.

Step three is the one that gets missed. A run that lays out empty boards and
places everything from scratch produces a better looking log and throws away
every hand placement made since the last run, which is the single fastest way
to lose the confidence of a traffic department.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..broadcast import BroadcastWeek
from ..domain.rulebook import House
from ..errors import ValidationError
from ..logging import event, warn
from ..logs.approval import LogState
from ..persistence.logstore import LogRepository
from ..persistence.unitofwork import UnitOfWork
from ..scheduling.inventory import DayBoard, Placement
from ..scheduling.lay import Lay
from ..scheduling.run import Boards, OrderResult, schedule_all


class BuildError(ValidationError):
    """A run that could not be started."""

    code = "bad_build"


@dataclass
class BuildReport:
    """What one run of the builder did."""

    week: BroadcastWeek
    orders: list[OrderResult] = field(default_factory=list)
    written: list[tuple[str, date]] = field(default_factory=list)
    skipped: list[tuple[str, date, str]] = field(default_factory=list)

    @property
    def placed(self) -> int:
        return sum(result.placed for result in self.orders)

    @property
    def wanted(self) -> int:
        return sum(result.wanted for result in self.orders)

    @property
    def is_complete(self) -> bool:
        return all(result.is_complete for result in self.orders)

    def shortfalls(self) -> list[OrderResult]:
        return [result for result in self.orders if not result.is_complete]

    def __str__(self) -> str:
        return (
            f"{self.week}: {self.placed} of {self.wanted} placed, "
            f"{len(self.written)} logs written, {len(self.skipped)} left alone"
        )


class PinnedBoards(Boards):
    """A board cache that starts from the hand placed spots, not from empty."""

    def __init__(self, house: House, logs: LogRepository) -> None:
        super().__init__(house)
        self.logs = logs

    def board(self, call: str, day: date) -> DayBoard:
        key = (call, day)
        if key not in self._boards:
            self._boards[key] = self._restored(call, day)
        return self._boards[key]

    def _restored(self, call: str, day: date) -> DayBoard:
        """A fresh board carrying only what somebody put there by hand.

        Everything the builder itself placed last time is thrown away, because
        the orders it placed from may have changed and a spot that is still on
        the board for an order that has been cancelled is a spot that will air.
        """
        grid = self.house.programming[call]
        fresh = DayBoard.build(grid, day)
        if self.logs.exists(call, day):
            restore_pins(fresh, self.logs.board(grid, day))
        return fresh


def restore_pins(board: DayBoard, stored: DayBoard) -> list[Placement]:
    """Copy the hand placed spots off a stored board onto a fresh one."""
    kept = []
    for avail in stored.avails:
        for placement in avail.placements:
            if placement.fixed:
                board.avail(avail.hour, avail.number).add(placement)
                kept.append(placement)
    return kept


def build_week(uow: UnitOfWork, house: House, week: BroadcastWeek) -> BuildReport:
    """Place and store one broadcast week across the whole cluster.

    Every logged station gets a board for every day of the week, whether an
    order touches it or not. A day with nothing on it is still a day the
    automation needs a log for, and a day that used to have something on it has
    to be written empty rather than left holding last week's answer.
    """
    logs = LogRepository(uow.session)
    boards = PinnedBoards(house, logs)
    for station in house.cluster.logged:
        for day in week.days():
            boards.board(station.call, day)
    accounts = uow.accounts.book()
    orders = [
        order
        for order in uow.orders.running_on(week.start) + uow.orders.running_on(week.end)
        if order.status.may_schedule
    ]
    unique = {order.number: order for order in orders}
    report = BuildReport(week)
    report.orders = schedule_all(boards, accounts, list(unique.values()), week)

    for call, day in boards.built():
        state = _state_of(logs, call, day)
        if state is not None and state.is_out:
            report.skipped.append((call, day, state.value))
            warn("build.skipped", station=call, day=day, state=state.value)
            continue
        logs.save(boards.board(call, day))
        report.written.append((call, day))

    event(
        "build.finished",
        week=str(week),
        orders=len(report.orders),
        placed=report.placed,
        wanted=report.wanted,
        written=len(report.written),
        skipped=len(report.skipped),
    )
    for shortfall in report.shortfalls():
        warn(
            "build.short",
            order=shortfall.order.number,
            placed=shortfall.placed,
            wanted=shortfall.wanted,
        )
    return report


def _state_of(logs: LogRepository, call: str, day: date) -> LogState | None:
    if not logs.exists(call, day):
        return None
    return logs.status(call, day).state


def lays_for(house: House, boards: Boards, call: str, days: list[date]) -> list[Lay]:
    """The board and rules for each day, for a caller placing one line by hand."""
    logging_call = house.logging_call(call)
    return [boards.lay(logging_call, day) for day in days]
