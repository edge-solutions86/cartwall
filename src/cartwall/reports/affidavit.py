"""The affidavit, which is a sworn statement and not a printout.

An agency that buys political or co-op time asks for proof of performance, and
what they mean is a document somebody signs saying these spots ran at these
times. It is notarised for co-op, because a manufacturer reimbursing a dealer
wants something they can put in front of an auditor.

That changes what the document may contain in one important way. Everything on
it has to be a fact somebody is prepared to swear to, which means:

* **It comes off the as-run, never off the log.** The log says what was
  supposed to happen. Swearing to that is swearing to an intention.
* **A spot that did not run is not on it.** Not listed as missed, not listed at
  all. The affidavit says what ran; a separate letter explains what did not.
* **A spot with no as-run line at all cannot go on it.** Not knowing whether
  something ran is not the same as knowing it did, and the difference is the
  whole point of the document.

It is refused rather than rendered when any of that is missing. A blank line
where a time belongs is exactly the sort of thing that turns a routine audit
into a real one.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..domain.spot import SpotLength
from ..errors import ValidationError
from ..money import ZERO, Money, total


class AffidavitError(ValidationError):
    """A statement that cannot be sworn to as it stands."""

    code = "bad_affidavit"


@dataclass(frozen=True, slots=True)
class Ran:
    """One spot, as it actually went out."""

    day: date
    at: str
    station: str
    length: SpotLength
    cart: str
    rate: Money

    def __post_init__(self) -> None:
        if not self.at:
            raise AffidavitError(
                f"a spot on {self.day} has no time and cannot be sworn to",
                day=str(self.day),
            )
        if not self.station:
            raise AffidavitError(f"a spot on {self.day} names no station")

    def __str__(self) -> str:
        return f"{self.day:%m/%d/%Y}  {self.at:>10}  {self.station:<8} {self.length}  {self.cart}"


@dataclass
class Affidavit:
    """One order, one period, and what ran on it."""

    order: str
    advertiser: str
    first: date
    last: date
    spots: list[Ran] = field(default_factory=list)
    #: Who is swearing to it, and when. Both required before it renders.
    sworn_by: str = ""
    sworn_on: date | None = None
    notary: str = ""

    def __post_init__(self) -> None:
        if self.last < self.first:
            raise AffidavitError(f"affidavit for {self.order} runs backwards")

    @property
    def count(self) -> int:
        return len(self.spots)

    @property
    def value(self) -> Money:
        return total([spot.rate for spot in self.spots])

    @property
    def stations(self) -> list[str]:
        found: list[str] = []
        for spot in self.spots:
            if spot.station not in found:
                found.append(spot.station)
        return sorted(found)

    def missing(self) -> list[str]:
        gaps = []
        if not self.spots:
            gaps.append("any spots to swear to")
        if not self.sworn_by:
            gaps.append("who is swearing to it")
        if self.sworn_on is None:
            gaps.append("when it was sworn")
        return gaps

    @property
    def is_ready(self) -> bool:
        return not self.missing()

    def outside(self) -> list[Ran]:
        """Spots that ran outside the period the affidavit covers."""
        return [spot for spot in self.spots if not self.first <= spot.day <= self.last]

    def swear(self, who: str, on: date, notary: str = "") -> None:
        if not who:
            raise AffidavitError("somebody has to swear to it")
        self.sworn_by = who
        self.sworn_on = on
        self.notary = notary

    def __str__(self) -> str:
        return (
            f"{self.order} {self.advertiser} {self.first:%m/%d}-{self.last:%m/%d}: "
            f"{self.count} spots {self.value}"
        )


def render(statement: Affidavit) -> str:
    """The document. Refuses to render one that cannot be sworn to."""
    gaps = statement.missing()
    if gaps:
        raise AffidavitError(
            f"the affidavit for {statement.order} is missing {', '.join(gaps)}",
            order=statement.order,
            missing=gaps,
        )
    stray = statement.outside()
    if stray:
        raise AffidavitError(
            f"{len(stray)} spot(s) ran outside {statement.first} to {statement.last}",
            order=statement.order,
            first=str(stray[0].day),
        )
    lines = [
        "AFFIDAVIT OF PERFORMANCE",
        "",
        f"ORDER        {statement.order}",
        f"ADVERTISER   {statement.advertiser}",
        f"PERIOD       {statement.first:%m/%d/%Y} to {statement.last:%m/%d/%Y}",
        f"STATIONS     {', '.join(statement.stations)}",
        "",
        "The spots below were broadcast at the dates and times shown.",
        "",
    ]
    for spot in sorted(statement.spots, key=lambda row: (row.day, row.at)):
        lines.append(f"  {spot}")
    lines.extend(
        [
            "",
            f"TOTAL        {statement.count} spots, {statement.value}",
            "",
            f"SWORN BY     {statement.sworn_by}",
            f"ON           {statement.sworn_on:%m/%d/%Y}",
        ]
    )
    if statement.notary:
        lines.append(f"NOTARY       {statement.notary}")
    return "\n".join(lines) + "\n"


def for_period(order: str, advertiser: str, first: date, last: date, spots: list[Ran]) -> Affidavit:
    """Build one, keeping only the spots that ran inside the period."""
    inside = [spot for spot in spots if first <= spot.day <= last]
    return Affidavit(
        order=order,
        advertiser=advertiser,
        first=first,
        last=last,
        spots=sorted(inside, key=lambda row: (row.day, row.at)),
    )


def by_station(statement: Affidavit) -> dict[str, tuple[int, Money]]:
    """Spots and money per station, for a client who bought several."""
    tally: dict[str, tuple[int, Money]] = {}
    for spot in statement.spots:
        count, worth = tally.get(spot.station, (0, ZERO))
        tally[spot.station] = (count + 1, worth + spot.rate)
    return dict(sorted(tally.items()))
