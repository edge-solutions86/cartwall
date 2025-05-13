"""Approving a log, releasing it, and what happens when it changes afterwards.

A log goes through four states and only forward:

* **Draft** while traffic is still working on it.
* **Approved** once the traffic director has signed it off. Approval is a
  person's name against a date, not a flag, because when a spot does not run
  the first question is who approved the log it was on.
* **Released** once it has gone to the automation. This is the point of no
  return: the operator is now working from a copy we do not control.
* **Aired** once the day is over.

Changing a released log is normal. A client kills copy on Tuesday morning for a
log that went out Friday. What is not normal is changing it quietly, so a
change after release makes a **revision**: the number goes up, the log goes back
to draft, and it has to be approved and released again. The revision number is
what the operator checks against the paper on the wall.

Approval is gated on problems, not on warnings. A hole in a break is a warning
and every log has them. A spot with no cart is a problem, because the operator
will reach the break and have nothing to play, and an hour with no legal ID is
a problem because that is the licence.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from enum import Enum

from ..errors import ConflictError, ValidationError
from .day import DayLog, Element


class ApprovalError(ValidationError):
    """An approval that cannot be recorded."""

    code = "bad_approval"


class LogNotReady(ConflictError):
    """The log has problems that have to be fixed before anybody signs it."""

    code = "log_not_ready"


class LogFrozen(ConflictError):
    """The log has gone out and cannot be changed in place."""

    code = "log_frozen"


class LogState(Enum):
    DRAFT = "draft"
    APPROVED = "approved"
    RELEASED = "released"
    AIRED = "aired"

    @property
    def may_edit(self) -> bool:
        return self is LogState.DRAFT

    @property
    def is_out(self) -> bool:
        return self in (LogState.RELEASED, LogState.AIRED)


@dataclass(frozen=True, slots=True)
class Problem:
    """One reason a log cannot be approved."""

    code: str
    where: str
    detail: str

    def __str__(self) -> str:
        return f"{self.code} {self.where}: {self.detail}"


def problems(log: DayLog) -> list[Problem]:
    """Everything that has to be fixed before this log can be signed."""
    found: list[Problem] = []
    for spot in log.spots():
        if not spot.cart:
            found.append(
                Problem(
                    "missing_copy",
                    f"{spot.at.twelve_hour()}",
                    f"{spot.order}-{spot.line} for {spot.advertiser} has no cart",
                )
            )
    with_ids = {entry.hour for entry in log.legal_ids()}
    for hour in log.hours():
        if hour not in with_ids:
            found.append(
                Problem("no_legal_id", f"hour {hour}", "the hour carries no legal identification")
            )
    return found


def warnings(log: DayLog) -> list[Problem]:
    """Worth saying, not worth stopping for."""
    found = []
    holes = log.open_units
    if holes:
        found.append(Problem("unsold", f"{log.station} {log.day}", f"{holes} units did not sell"))
    return found


@dataclass
class LogStatus:
    """Where one station day has got to."""

    station: str
    day: date
    state: LogState = LogState.DRAFT
    revision: int = 0
    approved_by: str = ""
    approved_at: datetime | None = None
    released_at: datetime | None = None

    @property
    def key(self) -> tuple[str, date]:
        return (self.station, self.day)

    def __str__(self) -> str:
        stamp = f" rev {self.revision}" if self.revision else ""
        return f"{self.station} {self.day} {self.state.value}{stamp}"


@dataclass
class LogRegister:
    """Which logs have been signed, by whom, and how many times."""

    entries: dict[tuple[str, date], LogStatus] = field(default_factory=dict)

    def status(self, station: str, day: date) -> LogStatus:
        key = (station, day)
        if key not in self.entries:
            self.entries[key] = LogStatus(station, day)
        return self.entries[key]

    def may_edit(self, station: str, day: date) -> bool:
        return self.status(station, day).state.may_edit

    def check_editable(self, station: str, day: date) -> None:
        status = self.status(station, day)
        if not status.state.may_edit:
            raise LogFrozen(
                f"{station} {day} is {status.state.value} at revision {status.revision}",
                station=station,
                day=str(day),
                state=status.state.value,
                revision=status.revision,
            )

    def approve(self, log: DayLog, who: str, when: datetime) -> LogStatus:
        if not who:
            raise ApprovalError("a log has to be approved by somebody")
        status = self.status(log.station, log.day)
        if status.state is not LogState.DRAFT:
            raise LogFrozen(
                f"{log.station} {log.day} is already {status.state.value}",
                station=log.station,
                state=status.state.value,
            )
        found = problems(log)
        if found:
            raise LogNotReady(
                f"{log.station} {log.day} has {len(found)} problem(s) to fix first",
                station=log.station,
                day=str(log.day),
                problems=[str(problem) for problem in found],
            )
        status.state = LogState.APPROVED
        status.approved_by = who
        status.approved_at = when
        return status

    def release(self, station: str, day: date, when: datetime) -> LogStatus:
        status = self.status(station, day)
        if status.state is not LogState.APPROVED:
            raise LogFrozen(
                f"{station} {day} is {status.state.value} and cannot be released",
                station=station,
                state=status.state.value,
            )
        status.state = LogState.RELEASED
        status.released_at = when
        return status

    def mark_aired(self, station: str, day: date) -> LogStatus:
        status = self.status(station, day)
        if status.state is not LogState.RELEASED:
            raise LogFrozen(
                f"{station} {day} is {status.state.value} and did not go to air from here",
                station=station,
                state=status.state.value,
            )
        status.state = LogState.AIRED
        return status

    def revise(self, station: str, day: date) -> LogStatus:
        """Open a released log back up, one revision higher."""
        status = self.status(station, day)
        if not status.state.is_out:
            raise ApprovalError(
                f"{station} {day} is {status.state.value} and does not need a revision"
            )
        status.state = LogState.DRAFT
        status.revision += 1
        status.approved_by = ""
        status.approved_at = None
        status.released_at = None
        return status

    def released(self) -> list[LogStatus]:
        return sorted(
            (status for status in self.entries.values() if status.state.is_out),
            key=lambda status: (status.day, status.station),
        )

    def waiting(self) -> list[LogStatus]:
        return sorted(
            (status for status in self.entries.values() if status.state is LogState.DRAFT),
            key=lambda status: (status.day, status.station),
        )

    def __len__(self) -> int:
        return len(self.entries)


def missing_copy(log: DayLog) -> list[str]:
    """Order and line of every spot the operator would have nothing to play for."""
    return sorted(
        {
            f"{entry.order}-{entry.line}"
            for entry in log.lines
            if entry.element is Element.SPOT and not entry.cart
        }
    )
