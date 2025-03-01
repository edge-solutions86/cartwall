"""Putting a week of one line onto the board.

The line says twelve a week Monday to Friday in morning drive. This works out
which day each of the twelve goes on, then which break inside that day, then
puts them there.

Spreading is the whole job and it happens twice:

* **Across days.** Twelve over five days is two and two fifths, and the client
  did not buy a fractional Tuesday. Largest remainder again, with the extras
  going to the earliest days, because a schedule that is heavier at the front
  of the week is what a client expects when they see an odd number.
* **Across hours.** Three spots in morning drive should not all land at
  seventeen past. Each one goes to the hour of the daypart that is carrying the
  fewest of this line so far, and within an hour to the emptiest break. That is
  what makes a schedule sound bought rather than dumped.

When a day cannot take its share, the leftovers spill onto the days that can
rather than being dropped. A spot the client paid for goes somewhere in the
week or it goes on the miss list with a reason, and there is no third outcome.

Nothing here bumps anything. A break that is full stays full and this pass
gives up on it. Preemption is a separate decision made by somebody with the
authority to make it, and it is the next module along.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..domain.account import AccountBook
from ..domain.rotation import Rotation
from .candidates import Ask, Opening, openings, why_blocked
from .inventory import DayBoard
from .lay import Landed, Lay, PlacerError
from .pinning import put_spot


def spread(count: int, buckets: int) -> list[int]:
    """Split ``count`` over ``buckets``, extras to the front. Never negative."""
    if buckets < 1:
        raise PlacerError("cannot spread over no buckets")
    if count < 0:
        raise PlacerError("cannot spread a negative count")
    base, extra = divmod(count, buckets)
    return [base + (1 if index < extra else 0) for index in range(buckets)]


@dataclass(frozen=True, slots=True)
class Miss:
    """One spot that could not be placed, and the nearest thing to a reason."""

    day: date | None
    reason: str
    blocked: dict[str, int] = field(default_factory=dict)

    def __str__(self) -> str:
        where = self.day or "the week"
        return f"{where}: {self.reason}"


@dataclass
class Result:
    """What one line's week came to."""

    ask: Ask
    landed: list[Landed] = field(default_factory=list)
    missed: list[Miss] = field(default_factory=list)

    @property
    def placed(self) -> int:
        return len(self.landed)

    @property
    def short(self) -> int:
        return len(self.missed)

    @property
    def is_complete(self) -> bool:
        return not self.missed

    def by_day(self) -> dict[date, int]:
        tally: dict[date, int] = {}
        for spot in self.landed:
            tally[spot.day] = tally.get(spot.day, 0) + 1
        return tally

    def hours(self) -> list[int]:
        return sorted(spot.hour for spot in self.landed)


def _pick(found: list[Opening], lay: Lay, ask: Ask) -> Opening | None:
    """The emptiest hour of the daypart, then the emptiest break in it."""
    clean = [opening for opening in found if opening.is_clean]
    if not clean:
        return None
    mine = _mine_by_hour(lay.board, ask)
    return min(
        clean,
        key=lambda opening: (
            mine.get(opening.hour, 0),
            opening.avail.used_units,
            opening.hour,
            opening.number,
        ),
    )


def _mine_by_hour(board: DayBoard, ask: Ask) -> dict[int, int]:
    tally: dict[int, int] = {}
    for avail in board.avails:
        for placement in avail.placements:
            if placement.key == ask.key:
                tally[avail.hour] = tally.get(avail.hour, 0) + 1
    return tally


def place_week(
    lays: list[Lay],
    accounts: AccountBook,
    ask: Ask,
    count: int,
    rotation: Rotation | None = None,
) -> Result:
    """Place ``count`` spots of ``ask`` across the days in ``lays``."""
    if count < 0:
        raise PlacerError("cannot place a negative number of spots")
    result = Result(ask)
    if not lays:
        result.missed.append(Miss(None, "the flight has no air days in this week"))
        return result
    for lay in lays:
        lay.prime(accounts)

    carts = rotation.assign(count) if rotation else [ask.cart] * count
    wanted = spread(count, len(lays))
    queue = [(lay, share) for lay, share in zip(lays, wanted, strict=True)]
    spilled: list[tuple[Lay, int]] = []
    index = 0

    for lay, share in queue:
        for _ in range(share):
            landed = _try_one(lay, accounts, ask, carts[index])
            if landed is None:
                spilled.append((lay, index))
            else:
                result.landed.append(landed)
            index += 1

    for origin, position in spilled:
        landed = _spill(lays, origin, accounts, ask, carts[position])
        if landed is None:
            result.missed.append(_miss(origin, accounts, ask))
        else:
            result.landed.append(landed)

    result.landed.sort(key=lambda spot: (spot.day, spot.hour, spot.number))
    return result


def _try_one(lay: Lay, accounts: AccountBook, ask: Ask, cart: str) -> Landed | None:
    seeking = Ask(**{**_ask_fields(ask), "cart": cart})
    found = openings(lay.board, lay.book, accounts, seeking, lay.neighbours)
    chosen = _pick(found, lay, ask)
    if chosen is None:
        return None
    return put_spot(lay, chosen.avail, seeking, cart)


def _spill(
    lays: list[Lay], origin: Lay, accounts: AccountBook, ask: Ask, cart: str
) -> Landed | None:
    """Try the other days, nearest first, when one day cannot take its share."""
    others = sorted(
        (lay for lay in lays if lay is not origin),
        key=lambda lay: (abs((lay.day - origin.day).days), lay.day),
    )
    for lay in others:
        landed = _try_one(lay, accounts, ask, cart)
        if landed is not None:
            return landed
    return None


def _miss(lay: Lay, accounts: AccountBook, ask: Ask) -> Miss:
    found = openings(lay.board, lay.book, accounts, ask, lay.neighbours)
    if not found:
        return Miss(lay.day, "no break in the daypart had room")
    return Miss(lay.day, "every open break was blocked", why_blocked(found))


def _ask_fields(ask: Ask) -> dict:
    return {
        "order": ask.order,
        "line": ask.line,
        "advertiser": ask.advertiser,
        "daypart": ask.daypart,
        "length": ask.length,
        "grade": ask.grade,
        "cart": ask.cart,
        "agency": ask.agency,
    }
