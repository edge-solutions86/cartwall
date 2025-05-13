"""Putting a spot exactly where somebody said, and keeping it there.

Most spots are placed by the builder and can be moved by it. Some are not. A
client who bought the 7:50 break in morning drive bought that break, and the
next run of the log must not helpfully improve it into the 7:22.

Pinning does two things at once and they are separate ideas:

* It puts the spot in one named break rather than searching.
* It marks the placement fixed, which is what stops every later pass moving it.

A pin is checked against the separation rules like anything else, and normally
refused when it breaks one. Normally, because the traffic director sometimes
has to override: two grocery stores in one break because the second one bought
a fixed position that the first one already had, and the alternative is telling
a client no. An override is allowed, it takes a written reason, and the reason
goes on the record where the exception report will find it.

``put_spot`` lives here rather than in the placer because both need it and the
board and the neighbour list have to move together. A placement added to the
board without a matching candidate on the neighbour list is a separation check
that silently stops working.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ..domain.account import AccountBook
from ..domain.separation import Candidate, Violation
from ..errors import ConflictError, ValidationError
from .candidates import Ask, would_land_at
from .inventory import Avail, Placement
from .lay import Landed, Lay


class PinError(ValidationError):
    """A pin that cannot be made."""

    code = "bad_pin"


class PinRefused(ConflictError):
    """The named break will not take this spot."""

    code = "pin_refused"


@dataclass(frozen=True, slots=True)
class Pinned:
    """One hand placed spot and why it was allowed."""

    landed: Landed
    override: str = ""
    broke: tuple[Violation, ...] = ()

    @property
    def was_forced(self) -> bool:
        return bool(self.override)

    def __str__(self) -> str:
        if not self.was_forced:
            return f"pinned to {self.landed}"
        return f"pinned to {self.landed} over {len(self.broke)} rule(s): {self.override}"


def put_spot(lay: Lay, avail: Avail, ask: Ask, cart: str = "", fixed: bool = False) -> Landed:
    """Add a spot to a break and keep the neighbour list level with it."""
    at = would_land_at(avail, ask.length)
    if at is None:
        raise PinRefused(
            f"{avail.station} hour {avail.hour} break {avail.number} on {avail.day} "
            f"will not take a {ask.length}",
            station=avail.station,
            hour=avail.hour,
            number=avail.number,
        )
    avail.add(
        Placement(
            order=ask.order,
            line=ask.line,
            advertiser=ask.advertiser.code,
            length=ask.length,
            grade=ask.grade,
            cart=cart or ask.cart,
            fixed=fixed,
        )
    )
    lay.neighbours.append(
        Candidate(
            day=lay.day,
            at=at,
            break_number=avail.number,
            advertiser=ask.advertiser,
            cart=cart or ask.cart,
            agency=ask.agency,
            fixed=fixed,
        )
    )
    return Landed(lay.day, avail.hour, avail.number, cart or ask.cart)


def pin(
    lay: Lay,
    accounts: AccountBook,
    ask: Ask,
    hour: int,
    number: int,
    cart: str = "",
    override: str = "",
) -> Pinned:
    """Put ``ask`` in one named break and mark it unmovable."""
    if not lay.neighbours:
        lay.prime(accounts)
    avail = lay.board.avail(hour, number)
    at = would_land_at(avail, ask.length)
    if at is None:
        raise PinRefused(
            f"{lay.board.station} hour {hour} break {number} on {lay.day} "
            f"has no room for a {ask.length}",
            station=lay.board.station,
            hour=hour,
            number=number,
        )
    candidate = Candidate(
        day=lay.day,
        at=at,
        break_number=number,
        advertiser=ask.advertiser,
        cart=cart or ask.cart,
        agency=ask.agency,
        fixed=True,
    )
    broke = tuple(lay.book.separation.violations(candidate, lay.neighbours))
    if broke and not override:
        raise PinRefused(
            f"{lay.board.station} hour {hour} break {number} on {lay.day} breaks "
            f"{', '.join(sorted({v.rule.code for v in broke}))}",
            station=lay.board.station,
            hour=hour,
            number=number,
            rules=sorted({violation.rule.code for violation in broke}),
        )
    landed = put_spot(lay, avail, ask, cart, fixed=True)
    return Pinned(landed, override, broke)


def unpin(lay: Lay, order: str, line: int, hour: int, number: int) -> Placement:
    """Take a hand placed spot back off, so the builder can have the unit."""
    avail = lay.board.avail(hour, number)
    if not avail.holds(order, line):
        raise PinError(f"{order}-{line} is not in {lay.board.station} hour {hour} break {number}")
    dropped = avail.drop(order, line)
    lay.neighbours = []
    return dropped


def pinned_on(lay: Lay) -> list[tuple[int, int, Placement]]:
    """Every hand placed spot on a day, in air order."""
    found = []
    for avail in sorted(lay.board.avails, key=lambda avail: (avail.hour, avail.position)):
        for placement in avail.placements:
            if placement.fixed:
                found.append((avail.hour, avail.number, placement))
    return found


def pinned_count(lay: Lay, day: date | None = None) -> int:
    if day is not None and lay.day != day:
        return 0
    return len(pinned_on(lay))
