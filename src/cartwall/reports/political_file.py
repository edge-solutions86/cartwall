"""The political file, which is a legal obligation and not a report.

Every request for political time goes in it, whether the station took the order
or turned it down, and it goes in **immediately**. Not at month end, not when
the spots run. The rule is that a request is on file the day it arrives, and a
station that batches this up has already broken it by the time anybody notices.

What each entry has to say:

* Who asked, which office, which election.
* Whether the station accepted or rejected the request, and if it was accepted
  in part, which part.
* The classes and dayparts bought, the rate charged for each, and the total.
* The dates and times the spots actually ran.

The last one is why this cannot be written when the order is taken and left
alone. The entry is opened on the day of the request and the air times are
added as the as-run comes in, so an entry has two ages: when it was opened and
when it was last touched.

Nothing here decides anything. It renders what is on file, and the one piece of
judgement it exercises is refusing to render an entry that is missing something
the rule requires, rather than filing a blank where the rate should be.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..domain.political import Candidacy, Use
from ..domain.ratecard import ClassOfTime
from ..domain.spot import SpotLength
from ..errors import ValidationError
from ..money import ZERO, Money, total


class FileError(ValidationError):
    """An entry that cannot be filed as it stands."""

    code = "bad_political_file"


@dataclass(frozen=True, slots=True)
class Aired:
    """One spot that ran, as the file has to record it."""

    day: date
    at: str
    station: str

    def __str__(self) -> str:
        return f"{self.day:%m/%d/%Y} {self.at} {self.station}"


@dataclass(frozen=True, slots=True)
class Bought:
    """One class of time bought, at one rate."""

    station: str
    daypart: str
    grade: ClassOfTime
    length: SpotLength
    rate: Money
    spots: int

    @property
    def value(self) -> Money:
        return self.rate * self.spots

    def __str__(self) -> str:
        return (
            f"{self.station} {self.daypart} {self.grade.value} {self.length} "
            f"{self.spots} at {self.rate}"
        )


@dataclass
class Entry:
    """One request for political time, and what became of it."""

    requested: date
    candidacy: Candidacy
    use: Use
    accepted: bool
    order: str = ""
    #: Why, when the station said no or said no to part of it.
    disposition: str = ""
    bought: list[Bought] = field(default_factory=list)
    aired: list[Aired] = field(default_factory=list)
    touched: date | None = None

    @property
    def spots(self) -> int:
        return sum(row.spots for row in self.bought)

    @property
    def value(self) -> Money:
        return total([row.value for row in self.bought])

    @property
    def is_complete(self) -> bool:
        return not self.missing()

    def missing(self) -> list[str]:
        """What the rule requires that this entry does not yet have."""
        gaps = []
        if not self.candidacy.authorised_by and self.use is Use.CANDIDATE:
            gaps.append("the authorised by disclaimer")
        if self.accepted and not self.order:
            gaps.append("the order number")
        if self.accepted and not self.bought:
            gaps.append("what was bought")
        if not self.accepted and not self.disposition:
            gaps.append("why it was turned down")
        return gaps

    def note_air(self, aired: Aired, on: date) -> None:
        self.aired.append(aired)
        self.touched = on

    def __str__(self) -> str:
        answer = "accepted" if self.accepted else "rejected"
        return (
            f"{self.requested:%m/%d/%Y} {self.candidacy} {answer} {self.spots} spots {self.value}"
        )


def render(entry: Entry) -> str:
    """One entry, as it goes into the file. Refuses an incomplete one."""
    gaps = entry.missing()
    if gaps:
        raise FileError(
            f"the entry for {entry.candidacy.candidate} is missing {', '.join(gaps)}",
            candidate=entry.candidacy.candidate,
            missing=gaps,
        )
    race = entry.candidacy.race
    lines = [
        f"REQUEST      {entry.requested:%m/%d/%Y}",
        f"CANDIDATE    {entry.candidacy.candidate}",
        f"OFFICE       {race.office}",
        f"ELECTION     {race.kind.value} {race.election_day:%m/%d/%Y}",
        f"COMMITTEE    {entry.candidacy.committee}",
        f"USE          {entry.use.value}",
        f"DISPOSITION  {'accepted' if entry.accepted else 'rejected'}",
    ]
    if entry.disposition:
        lines.append(f"NOTE         {entry.disposition}")
    if entry.accepted:
        lines.append(f"ORDER        {entry.order}")
        lines.append(f"AUTHORISED   {entry.candidacy.authorised_by}")
        for row in entry.bought:
            lines.append(f"BOUGHT       {row}")
        lines.append(f"TOTAL        {entry.value}")
        for spot in entry.aired:
            lines.append(f"AIRED        {spot}")
    return "\n".join(lines) + "\n"


@dataclass
class PoliticalFile:
    """Everything on file, in the order it was requested."""

    entries: list[Entry] = field(default_factory=list)

    def file(self, entry: Entry) -> None:
        self.entries.append(entry)
        self.entries.sort(key=lambda row: (row.requested, row.candidacy.candidate))

    def for_candidate(self, candidate: str) -> list[Entry]:
        return [row for row in self.entries if row.candidacy.candidate == candidate]

    def for_race(self, office: str) -> list[Entry]:
        return [row for row in self.entries if row.candidacy.race.office == office]

    def incomplete(self) -> list[Entry]:
        """Entries that cannot be filed yet, which somebody has to chase."""
        return [row for row in self.entries if not row.is_complete]

    def rejected(self) -> list[Entry]:
        return [row for row in self.entries if not row.accepted]

    def spend_by_candidate(self) -> dict[str, Money]:
        tally: dict[str, Money] = {}
        for row in self.entries:
            if not row.accepted:
                continue
            name = row.candidacy.candidate
            tally[name] = tally.get(name, ZERO) + row.value
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1].cents, pair[0])))

    def render_all(self) -> str:
        return "\n".join(render(entry) for entry in self.entries if entry.is_complete)

    def __len__(self) -> int:
        return len(self.entries)
