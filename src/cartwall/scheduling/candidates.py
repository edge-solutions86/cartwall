"""Where one spot could go, and which of those places are actually clean.

Placement is a search and this is the part that generates the moves. Given a
line that needs a spot on a date, it answers two questions in one pass:

* Which avails are even eligible? The break has to be in the line's daypart,
  have room by units and by seconds, and be on an hour the station is selling.
* Of those, which are clean? An avail with room can still be the wrong place
  because of what is already in it, or because of something two breaks away.

Both answers come back rather than just the clean ones. The builder needs the
dirty openings too: when a line cannot be placed anywhere, the traffic director
wants to know it was blocked by separation and not by a full day, and those are
two different problems with two different fixes.

Turning a board into separation candidates is the fiddly part. The rules are
written about advertisers and their conflict codes, and the board only holds
account codes, so every check needs the account book. That lookup is done once
per board rather than once per rule, because a heavy day is twelve hundred
placements and the rules are four.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..airtime import AirTime
from ..domain.account import AccountBook, Advertiser
from ..domain.daypart import Weekday
from ..domain.ratecard import ClassOfTime
from ..domain.rulebook import RuleBook
from ..domain.separation import Candidate, Violation
from ..domain.spot import SpotLength
from ..errors import ValidationError
from .inventory import Avail, DayBoard


class PlacementError(ValidationError):
    """A request that cannot be considered at all."""

    code = "bad_placement"


@dataclass(frozen=True, slots=True)
class Ask:
    """One spot that needs somewhere to go."""

    order: str
    line: int
    advertiser: Advertiser
    daypart: str
    length: SpotLength
    grade: ClassOfTime
    cart: str = ""
    agency: str | None = None

    def __post_init__(self) -> None:
        if not self.order:
            raise PlacementError("an ask names no order")
        if self.line < 1:
            raise PlacementError(f"order {self.order} line {self.line} is not a line number")

    @property
    def key(self) -> tuple[str, int]:
        return (self.order, self.line)

    def __str__(self) -> str:
        return f"{self.order}-{self.line} {self.advertiser.code} {self.length} {self.daypart}"


@dataclass(frozen=True, slots=True)
class Opening:
    """One avail this ask could use, and what it would cost in violations."""

    avail: Avail
    violations: tuple[Violation, ...] = ()

    @property
    def is_clean(self) -> bool:
        return not self.violations

    @property
    def hour(self) -> int:
        return self.avail.hour

    @property
    def number(self) -> int:
        return self.avail.number

    def reasons(self) -> tuple[str, ...]:
        return tuple(violation.rule.code for violation in self.violations)

    def __str__(self) -> str:
        if self.is_clean:
            return f"{self.avail} clean"
        return f"{self.avail} blocked by {', '.join(self.reasons())}"


def board_candidates(board: DayBoard, accounts: AccountBook) -> list[Candidate]:
    """Everything already on the board, as the separation rules see it.

    One account lookup per distinct advertiser, not one per placement. A busy
    Friday has four hundred spots and about forty advertisers.
    """
    known: dict[str, Advertiser] = {}
    found: list[Candidate] = []
    for avail in board.avails:
        for placement, at in avail.air_times():
            if placement.advertiser not in known:
                known[placement.advertiser] = accounts.advertiser(placement.advertiser)
            advertiser = known[placement.advertiser]
            found.append(
                Candidate(
                    day=board.day,
                    at=at,
                    break_number=avail.number,
                    advertiser=advertiser,
                    cart=placement.cart,
                    agency=advertiser.agency,
                    fixed=placement.fixed,
                )
            )
    return found


def would_land_at(avail: Avail, length: SpotLength) -> AirTime | None:
    """The air time a spot of this length would get if it went in at the back.

    None when it would not fit. Spots go in at the back because that is what
    the automation does, and a separation check against the wrong air time is
    worse than no check at all.
    """
    if not avail.accepts(length):
        return None
    cursor = avail.starts_at
    for placement in avail.placements:
        cursor = cursor + placement.length.air
    return cursor


def eligible_avails(board: DayBoard, book: RuleBook, ask: Ask) -> list[Avail]:
    """Avails in the ask's daypart with room for it, in air order."""
    if not book.dayparts.has(ask.daypart):
        raise PlacementError(f"{book.call} does not sell {ask.daypart}")
    daypart = book.dayparts.of(ask.daypart)
    weekday = Weekday(board.day.weekday())
    found = [
        avail
        for avail in board.avails
        if daypart.holds(weekday, avail.starts_at) and avail.accepts(ask.length)
    ]
    found.sort(key=lambda avail: (avail.hour, avail.position))
    return found


def openings(
    board: DayBoard,
    book: RuleBook,
    accounts: AccountBook,
    ask: Ask,
    neighbours: list[Candidate] | None = None,
) -> list[Opening]:
    """Every avail the ask could use, clean or not, in air order."""
    if board.station != book.call:
        raise PlacementError(f"{book.call}'s rules were handed {board.station}'s board")
    if board.day != book.on:
        raise PlacementError(f"a book for {book.on} was handed a board for {board.day}")
    around = board_candidates(board, accounts) if neighbours is None else neighbours
    found = []
    for avail in eligible_avails(board, book, ask):
        at = would_land_at(avail, ask.length)
        if at is None:
            continue
        candidate = Candidate(
            day=board.day,
            at=at,
            break_number=avail.number,
            advertiser=ask.advertiser,
            cart=ask.cart,
            agency=ask.agency,
        )
        found.append(Opening(avail, tuple(book.separation.violations(candidate, around))))
    return found


def clean_openings(
    board: DayBoard,
    book: RuleBook,
    accounts: AccountBook,
    ask: Ask,
    neighbours: list[Candidate] | None = None,
) -> list[Opening]:
    return [
        opening
        for opening in openings(board, book, accounts, ask, neighbours)
        if opening.is_clean
    ]


def why_blocked(found: list[Opening]) -> dict[str, int]:
    """How many openings each rule cost us, worst first, for the exception report."""
    tally: dict[str, int] = {}
    for opening in found:
        for code in set(opening.reasons()):
            tally[code] = tally.get(code, 0) + 1
    return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))
