"""Matching what the automation played against what the log said it would.

This is the step that turns a schedule into an invoice. Nothing is billed off
the log. Everything is billed off what actually aired, and the two are never
quite the same document.

Matching runs in two passes, and the order matters:

1. **By reference.** The automation echoes the order and line back on most
   lines, and when it does that is the match. It is unambiguous, it survives
   a break being moved, and it survives the same cart running twice.
2. **By cart and time.** Older automation versions and hand keyed entries lose
   the reference. Then the only handle is the cart, and the only way to tell
   two runs of the same cart apart is which scheduled spot each is closest to.
   Nearest first, one to one, inside a window.

Everything left over is one of two kinds of problem, and they are opposite:

* **Missed**: on the log, not on the as-run. The client did not get it and is
  owed a makegood. This is the expensive one.
* **Extra**: on the as-run, not on the log. Somebody keyed a spot in by hand,
  or the automation played the previous day's file. Nobody has been billed and
  nobody is owed, but somewhere there is inventory that was given away.

A spot that aired well away from where it was logged is **moved**, not missed.
It ran, it is billable, and the client may still have a complaint about the
daypart, which is why the drift is kept rather than being rounded away.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..airtime import Duration
from ..errors import ValidationError
from ..logs.day import DayLog, LogLine
from ..playout.asrun import AsRun, Played

#: How far a spot can drift and still count as having run where it was sold.
ON_TIME = Duration.of_seconds(5 * 60)

#: How far the cart matcher will look for a partner when there is no reference.
WINDOW = Duration.of_seconds(30 * 60)


class ReconcileError(ValidationError):
    """A reconciliation that cannot be run as asked."""

    code = "bad_reconcile"


@dataclass(frozen=True, slots=True)
class Match:
    """One scheduled spot and the as-run line that answers for it."""

    scheduled: LogLine
    aired: AsRun
    by_reference: bool

    @property
    def key(self) -> tuple[str, int]:
        return (self.scheduled.order, self.scheduled.line)

    @property
    def drift(self) -> Duration:
        return self.aired.drift_from(self.scheduled.at)

    @property
    def on_time(self) -> bool:
        return self.drift <= ON_TIME

    @property
    def moved(self) -> bool:
        return not self.on_time

    @property
    def short(self) -> bool:
        """Ran, but for less than it was sold for."""
        return self.aired.length < self.scheduled.length

    @property
    def billable(self) -> bool:
        """Anything that went out is billable. Whether it is right is separate."""
        return self.aired.aired

    def __str__(self) -> str:
        where = "on time" if self.on_time else f"moved {self.drift}"
        return f"{self.scheduled.order}-{self.scheduled.line} {where}"


@dataclass(frozen=True, slots=True)
class Missed:
    """On the log, not on the as-run."""

    scheduled: LogLine
    #: Set when the as-run has a line for it that says it did not go out.
    reason: str = "no as-run line"

    @property
    def key(self) -> tuple[str, int]:
        return (self.scheduled.order, self.scheduled.line)

    def __str__(self) -> str:
        return f"{self.scheduled.order}-{self.scheduled.line} missed: {self.reason}"


@dataclass(frozen=True, slots=True)
class Extra:
    """On the as-run, not on the log."""

    aired: AsRun

    def __str__(self) -> str:
        return f"{self.aired.cart} at {self.aired.at.twelve_hour()} was not on the log"


@dataclass
class Reconciliation:
    """What one station day came to when the two documents were compared."""

    station: str
    day: date
    matched: list[Match] = field(default_factory=list)
    missed: list[Missed] = field(default_factory=list)
    extra: list[Extra] = field(default_factory=list)

    @property
    def scheduled(self) -> int:
        return len(self.matched) + len(self.missed)

    @property
    def aired(self) -> int:
        return sum(1 for match in self.matched if match.billable)

    @property
    def moved(self) -> list[Match]:
        return [match for match in self.matched if match.moved]

    @property
    def is_clean(self) -> bool:
        return not self.missed and not self.extra and not self.moved

    def for_line(self, order: str, line: int) -> list[Match]:
        return [match for match in self.matched if match.key == (order, line)]

    def owed(self) -> dict[tuple[str, int], int]:
        """How many spots each line is short, worst first."""
        tally: dict[tuple[str, int], int] = {}
        for miss in self.missed:
            tally[miss.key] = tally.get(miss.key, 0) + 1
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))

    def __str__(self) -> str:
        return (
            f"{self.station} {self.day}: {self.aired} of {self.scheduled} aired, "
            f"{len(self.missed)} missed, {len(self.extra)} unlogged"
        )


def reconcile(log: DayLog, rows: list[AsRun], window: Duration = WINDOW) -> Reconciliation:
    """Compare one day's log against one day's as-run."""
    wrong_day = [row for row in rows if row.day != log.day]
    if wrong_day:
        raise ReconcileError(
            f"the as-run carries {len(wrong_day)} line(s) for another day",
            log_day=str(log.day),
            first=str(wrong_day[0].day),
        )
    result = Reconciliation(log.station, log.day)
    pending = list(log.spots())
    left = list(rows)

    _by_reference(pending, left, result)
    _by_cart(pending, left, result, window)

    for entry in pending:
        result.missed.append(Missed(entry, _why(entry, rows)))
    result.extra = [Extra(row) for row in left if row.key is not None or row.cart]
    result.matched.sort(key=lambda match: match.aired.at)
    result.missed.sort(key=lambda miss: miss.scheduled.at)
    result.extra.sort(key=lambda extra: extra.aired.at)
    return result


def _by_reference(pending: list[LogLine], left: list[AsRun], result: Reconciliation) -> None:
    """The unambiguous pass. Same order, same line, first come first served."""
    for row in list(left):
        if row.key is None or not row.aired:
            continue
        for entry in pending:
            if (entry.order, entry.line) == row.key:
                result.matched.append(Match(entry, row, by_reference=True))
                pending.remove(entry)
                left.remove(row)
                break


def _by_cart(
    pending: list[LogLine], left: list[AsRun], result: Reconciliation, window: Duration
) -> None:
    """The best guess pass. Same cart, nearest in time, inside the window."""
    for row in list(left):
        if not row.cart or not row.aired:
            continue
        near = [entry for entry in pending if entry.cart == row.cart]
        near = [entry for entry in near if row.drift_from(entry.at) <= window]
        if not near:
            continue
        best = min(near, key=lambda entry: row.drift_from(entry.at).tenths)
        result.matched.append(Match(best, row, by_reference=False))
        pending.remove(best)
        left.remove(row)


def _why(entry: LogLine, rows: list[AsRun]) -> str:
    """The as-run's own word for it, when there is a line saying it failed."""
    for row in rows:
        if row.key == (entry.order, entry.line) and not row.aired:
            return row.status.value
        if row.cart and row.cart == entry.cart and not row.aired:
            return row.status.value
    return "no as-run line"


def missed_by_status(result: Reconciliation) -> dict[str, int]:
    tally: dict[str, int] = {}
    for miss in result.missed:
        tally[miss.reason] = tally.get(miss.reason, 0) + 1
    return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))


def worst_drift(result: Reconciliation) -> Duration:
    if not result.matched:
        return Duration(0)
    return max(match.drift for match in result.matched)


def status_of(row: AsRun) -> Played:
    return row.status
