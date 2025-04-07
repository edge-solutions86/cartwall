"""Storing a built day and reading it back as a board.

Kept apart from the other repositories because it is a different kind of write.
Stations and orders are small, change rarely and are saved whole. A day is a
thousand rows, is written once a night for six stations, and is the only thing
in the system that will ever be big enough to care about.

The round trip is board to rows to board, not log to rows to log. The log is a
view over the board plus the clock, and rebuilding it from the grid is both
cheaper and more honest than storing the legal IDs and the holes. What is
stored is what was sold and where it went.

One thing is stored that the board can recompute: the air time of every spot.
See the note on ``SpotRow``. The board's own arithmetic is what the log said
when it was built, and the stored value is what it said when it was released.
Reading a released day gives back the released times.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import delete, select
from sqlalchemy.orm import Session, selectinload

from ..domain.programming import ClockGrid
from ..errors import ConflictError, LookupFailure
from ..logs.approval import LogState, LogStatus
from ..scheduling.bumping import Bumped, BumpQueue
from ..scheduling.inventory import DayBoard, Placement
from .models.logs import BumpRow, LogRow, SpotRow


class LogAlreadyOut(ConflictError):
    """The stored log has gone to air and will not be written over."""

    code = "log_already_out"


def spot_rows(board: DayBoard) -> list[SpotRow]:
    """Every placement on a board, with its position and its air time."""
    rows = []
    for avail in sorted(board.avails, key=lambda avail: (avail.hour, avail.position)):
        for position, (placement, at) in enumerate(avail.air_times()):
            rows.append(
                SpotRow(
                    hour=avail.hour,
                    break_number=avail.number,
                    position=position,
                    at=at,
                    order_number=placement.order,
                    line=placement.line,
                    advertiser=placement.advertiser,
                    length=placement.length,
                    grade=placement.grade,
                    cart=placement.cart,
                    fixed=placement.fixed,
                )
            )
    return rows


def board_from(grid: ClockGrid, day: date, rows: list[SpotRow]) -> DayBoard:
    """Lay the day out from the grid, then put the stored spots back in it."""
    board = DayBoard.build(grid, day)
    for row in sorted(rows, key=lambda row: (row.hour, row.break_number, row.position)):
        board.avail(row.hour, row.break_number).add(
            Placement(
                order=row.order_number,
                line=row.line,
                advertiser=row.advertiser,
                length=row.length,
                grade=row.grade,
                cart=row.cart,
                fixed=row.fixed,
            )
        )
    return board


def status_from(row: LogRow) -> LogStatus:
    return LogStatus(
        station=row.station,
        day=row.day,
        state=row.state,
        revision=row.revision,
        approved_by=row.approved_by,
        approved_at=row.approved_at,
        released_at=row.released_at,
    )


class LogRepository:
    """Days, as they were built and as they went out."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def _row(self, station: str, day: date) -> LogRow | None:
        return self.session.scalars(
            select(LogRow)
            .options(selectinload(LogRow.spots))
            .where(LogRow.station == station, LogRow.day == day)
        ).first()

    def exists(self, station: str, day: date) -> bool:
        return self._row(station, day) is not None

    def status(self, station: str, day: date) -> LogStatus:
        row = self._row(station, day)
        if row is None:
            raise LookupFailure(
                f"{station} has no log for {day}", station=station, day=str(day)
            )
        return status_from(row)

    def save(self, board: DayBoard, force: bool = False) -> LogStatus:
        """Write a board over whatever is stored, unless it has gone out."""
        row = self._row(board.station, board.day)
        if row is None:
            row = LogRow(station=board.station, day=board.day)
            self.session.add(row)
        elif row.state.is_out and not force:
            raise LogAlreadyOut(
                f"{board.station} {board.day} is {row.state.value} at revision "
                f"{row.revision}, revise it before writing over it",
                station=board.station,
                day=str(board.day),
                state=row.state.value,
                revision=row.revision,
            )
        # Clear and flush before writing the new rows. Assigning the collection
        # in one go leaves the deletes and the inserts in the same flush, and
        # the database applies the inserts first, which trips the unique
        # constraint on (log, hour, break, position) with a stale row.
        row.spots = []
        self.session.flush()
        row.spots = spot_rows(board)
        self.session.flush()
        return status_from(row)

    def board(self, grid: ClockGrid, day: date) -> DayBoard:
        row = self._row(grid.station, day)
        if row is None:
            raise LookupFailure(
                f"{grid.station} has no log for {day}", station=grid.station, day=str(day)
            )
        return board_from(grid, day, list(row.spots))

    def approve(self, station: str, day: date, who: str, when: datetime) -> LogStatus:
        row = self._require(station, day)
        row.state = LogState.APPROVED
        row.approved_by = who
        row.approved_at = when
        self.session.flush()
        return status_from(row)

    def release(self, station: str, day: date, when: datetime) -> LogStatus:
        row = self._require(station, day)
        row.state = LogState.RELEASED
        row.released_at = when
        self.session.flush()
        return status_from(row)

    def revise(self, station: str, day: date) -> LogStatus:
        row = self._require(station, day)
        row.state = LogState.DRAFT
        row.revision += 1
        row.approved_by = ""
        row.approved_at = None
        row.released_at = None
        self.session.flush()
        return status_from(row)

    def _require(self, station: str, day: date) -> LogRow:
        row = self._row(station, day)
        if row is None:
            raise LookupFailure(
                f"{station} has no log for {day}", station=station, day=str(day)
            )
        return row

    def between(self, station: str, first: date, last: date) -> list[LogStatus]:
        rows = self.session.scalars(
            select(LogRow)
            .where(LogRow.station == station, LogRow.day >= first, LogRow.day <= last)
            .order_by(LogRow.day)
        ).all()
        return [status_from(row) for row in rows]

    def spots_for(self, order: str, first: date, last: date) -> list[SpotRow]:
        """Every scheduled spot of one order in a span, in air order."""
        return list(
            self.session.scalars(
                select(SpotRow)
                .join(LogRow)
                .where(
                    SpotRow.order_number == order,
                    LogRow.day >= first,
                    LogRow.day <= last,
                )
                .order_by(LogRow.day, SpotRow.at)
            ).all()
        )


class BumpRepository:
    """What came off, and whether anything has been done about it."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def record(self, station: str, bumped: Bumped) -> BumpRow:
        row = BumpRow(
            station=station,
            day=bumped.day,
            hour=bumped.hour,
            break_number=bumped.number,
            order_number=bumped.placement.order,
            line=bumped.placement.line,
            advertiser=bumped.placement.advertiser,
            length=bumped.placement.length,
            grade=bumped.placement.grade,
            by_order=bumped.by_order,
            by_line=bumped.by_line,
        )
        self.session.add(row)
        return row

    def record_queue(self, station: str, queue: BumpQueue) -> list[BumpRow]:
        return [self.record(station, bumped) for bumped in queue]

    def outstanding(self, order: str | None = None) -> list[BumpRow]:
        """Bumps nobody has made good yet, oldest first."""
        query = select(BumpRow).where(BumpRow.made_good.is_(False))
        if order is not None:
            query = query.where(BumpRow.order_number == order)
        return list(
            self.session.scalars(
                query.order_by(BumpRow.day, BumpRow.hour, BumpRow.break_number)
            ).all()
        )

    def settle(self, row_id: int) -> BumpRow:
        row = self.session.get(BumpRow, row_id)
        if row is None:
            raise LookupFailure(f"bump {row_id} is not on file", bump=row_id)
        row.made_good = True
        self.session.flush()
        return row

    def owed(self) -> dict[tuple[str, int], int]:
        tally: dict[tuple[str, int], int] = {}
        for row in self.outstanding():
            tally[row.key] = tally.get(row.key, 0) + 1
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))

    def clear_day(self, station: str, day: date) -> int:
        """Drop the bumps for a day that is being rebuilt from scratch."""
        result = self.session.execute(
            delete(BumpRow).where(BumpRow.station == station, BumpRow.day == day)
        )
        return result.rowcount or 0
