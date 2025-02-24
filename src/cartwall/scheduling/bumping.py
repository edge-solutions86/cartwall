"""Taking a unit off somebody who has one so somebody else can have it.

This is the part of traffic that is a promise rather than an algorithm. The
class of time a spot was sold at is the station saying, in writing, what it
will do when the break is full and a better order arrives. Three answers:

* **Fixed** was sold as unmovable. It is not bumped. If a political order needs
  that unit the station has a conversation, not a computation.
* **Preemptible with notice** can be bumped if the client was told at least a
  fortnight ago. That makes bumping depend on today's date and not just on the
  board, which is why every function here takes ``today``.
* **Immediately preemptible** can be bumped the morning of. That is what it was
  discounted for.

A bump is never free and never silent. Whatever comes off goes on the bump
queue, and the queue is the input to the makegood run. A spot that is bumped
and forgotten is a spot the client paid for and did not get, which turns into
a credit at the end of the month and a phone call before that.

Only a strictly higher class may bump. Equal class does not, however much more
money is on the incoming order, because the two clients were sold the same
promise and picking between them on price is exactly what the classes exist to
stop.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..domain.ratecard import ClassOfTime
from ..errors import ConflictError, ValidationError
from .candidates import Ask
from .inventory import Avail, Placement


class BumpError(ValidationError):
    """A bump that could not be considered."""

    code = "bad_bump"


class NothingToBump(ConflictError):
    """The break is full and none of what is in it may be moved."""

    code = "nothing_to_bump"


@dataclass(frozen=True, slots=True)
class Bumped:
    """One spot taken off the air, and who took it."""

    placement: Placement
    day: date
    hour: int
    number: int
    by_order: str
    by_line: int

    @property
    def key(self) -> tuple[str, int]:
        return self.placement.key

    def __str__(self) -> str:
        return (
            f"{self.placement.order}-{self.placement.line} out of {self.day} "
            f"hour {self.hour} break {self.number} for {self.by_order}-{self.by_line}"
        )


def may_bump(sitting: ClassOfTime, incoming: ClassOfTime, air: date, today: date) -> bool:
    """Whether ``incoming`` is entitled to take a unit off ``sitting``."""
    if incoming.rank <= sitting.rank:
        return False
    if not sitting.may_be_preempted:
        return False
    return (air - today).days >= sitting.notice_days


def preemptible(avail: Avail, ask: Ask, today: date) -> list[Placement]:
    """What in this break the ask could take, cheapest promise first."""
    movable = [
        placement
        for placement in avail.placements
        if not placement.fixed and may_bump(placement.grade, ask.grade, avail.day, today)
    ]
    movable.sort(key=lambda placement: (placement.grade.rank, -placement.length.units))
    return movable


def enough_to_bump(avail: Avail, ask: Ask, today: date) -> list[Placement] | None:
    """The fewest spots to move so the ask fits, or None if it cannot be done.

    Cheapest promise first, and inside a class the longest spot first, because
    one sixty coming off is one client to apologise to instead of two thirties.
    """
    if avail.accepts(ask.length):
        return []
    movable = preemptible(avail, ask, today)
    taken: list[Placement] = []
    units = avail.free_units
    seconds = avail.free_length
    for placement in movable:
        if units >= ask.length.units and seconds >= ask.length.air:
            break
        taken.append(placement)
        units += placement.length.units
        seconds = seconds + placement.length.air
    if units < ask.length.units or seconds < ask.length.air:
        return None
    return taken


@dataclass
class BumpQueue:
    """Everything that came off, waiting for the makegood run."""

    entries: list[Bumped] = field(default_factory=list)

    def add(self, bumped: Bumped) -> None:
        self.entries.append(bumped)

    def for_order(self, order: str) -> list[Bumped]:
        return [entry for entry in self.entries if entry.placement.order == order]

    def for_line(self, order: str, line: int) -> list[Bumped]:
        return [entry for entry in self.entries if entry.key == (order, line)]

    def owed(self) -> dict[tuple[str, int], int]:
        """How many spots each line is owed, worst first."""
        tally: dict[tuple[str, int], int] = {}
        for entry in self.entries:
            tally[entry.key] = tally.get(entry.key, 0) + 1
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))

    def on(self, day: date) -> list[Bumped]:
        return [entry for entry in self.entries if entry.day == day]

    def __len__(self) -> int:
        return len(self.entries)

    def __iter__(self):
        return iter(self.entries)


def bump_into(
    avail: Avail,
    ask: Ask,
    today: date,
    cart: str = "",
    queue: BumpQueue | None = None,
) -> list[Bumped]:
    """Make room in ``avail`` for ``ask`` and put it in. Returns what came off."""
    if avail.day < today:
        raise BumpError(
            f"{avail.day} has already aired and cannot be rescheduled",
            day=str(avail.day),
            today=str(today),
        )
    taken = enough_to_bump(avail, ask, today)
    if taken is None:
        raise NothingToBump(
            f"nothing in {avail.station} hour {avail.hour} break {avail.number} "
            f"on {avail.day} may be moved for a {ask.grade.value} spot",
            station=avail.station,
            hour=avail.hour,
            number=avail.number,
            grade=ask.grade.value,
        )
    removed = []
    for placement in taken:
        avail.drop(placement.order, placement.line)
        bumped = Bumped(
            placement=placement,
            day=avail.day,
            hour=avail.hour,
            number=avail.number,
            by_order=ask.order,
            by_line=ask.line,
        )
        removed.append(bumped)
        if queue is not None:
            queue.add(bumped)
    avail.add(
        Placement(
            order=ask.order,
            line=ask.line,
            advertiser=ask.advertiser.code,
            length=ask.length,
            grade=ask.grade,
            cart=cart or ask.cart,
        )
    )
    return removed
