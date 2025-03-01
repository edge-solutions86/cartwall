"""A board and the rules that go with it, which every pass needs together.

Small module, and it exists for one reason. The placer and the pinning code
both put spots on a board, both have to keep the separation neighbour list
level with what they put there, and each needs the other's types. Left in
either one, that is an import cycle papered over with a function level import.

So the pair lives here, on its own, importing nothing from the passes that use
it. A board without its rule book is half a thing: it can say a break has room
and cannot say whether the spot is allowed in it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..domain.account import AccountBook
from ..domain.rulebook import RuleBook
from ..domain.separation import Candidate
from ..errors import ValidationError
from .candidates import board_candidates
from .inventory import DayBoard


class PlacerError(ValidationError):
    """A run that could not be started."""

    code = "bad_placer"


@dataclass
class Lay:
    """One date's board and the rules that go with it."""

    board: DayBoard
    book: RuleBook
    neighbours: list[Candidate] = field(default_factory=list)

    def __post_init__(self) -> None:
        if self.board.day != self.book.on:
            raise PlacerError(f"a board for {self.board.day} came with rules for {self.book.on}")
        if self.board.station != self.book.call:
            raise PlacerError(f"{self.board.station}'s board came with {self.book.call}'s rules")

    @property
    def day(self) -> date:
        return self.board.day

    def prime(self, accounts: AccountBook) -> None:
        """Read the board once. After this the list is kept up to date by hand."""
        self.neighbours = board_candidates(self.board, accounts)


@dataclass(frozen=True, slots=True)
class Landed:
    """One spot, and where it ended up."""

    day: date
    hour: int
    number: int
    cart: str

    def __str__(self) -> str:
        return f"{self.day} hour {self.hour} break {self.number}"
