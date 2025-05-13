"""Document numbers, taken off a locked counter row so there are no gaps.

An invoice number with a hole in it is an audit finding. Not a serious one, but
the conversation costs more than getting this right, and getting it right is
not hard as long as nobody is clever.

The clever thing that does not work is taking the highest number on file and
adding one. Two sellers writing an order at the same moment both read 1043 and
both write 1044. It works in every test and fails on the busiest day of the
year, which is the worst possible failure schedule.

So: one row per series per year, updated in the same transaction as the
document it numbers, with the row locked while it is read. If the transaction
rolls back the number goes back with it, which is what makes the sequence
gapless rather than merely unique.

Series are per year because that is how the office reads them. ``25-1044`` is
the forty fourth order of 2025 after the first thousand, and in January the
number goes back to 1001 rather than carrying on into six digits.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..errors import ConflictError, ValidationError
from ..persistence.models.office import FIRST_NUMBER, CounterRow

#: Order numbers start here so a four digit number always reads as one.
FIRST = FIRST_NUMBER


class NumberingError(ValidationError):
    """A series that cannot be drawn from."""

    code = "bad_numbering"


class SeriesExhausted(ConflictError):
    """A year's worth of numbers has run out."""

    code = "series_exhausted"


#: Four digits after the dash, so 8999 documents of one kind in one year.
CEILING = 9999


class Numbering:
    """Hands out the next number in a series, inside the caller's transaction."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def _counter(self, series: str, year: int) -> CounterRow:
        if not series:
            raise NumberingError("a series needs a code")
        if not 2000 <= year <= 2999:
            raise NumberingError(f"{year} is not a year this house trades in")
        row = self.session.scalars(
            select(CounterRow)
            .where(CounterRow.series == series, CounterRow.year == year)
            .with_for_update()
        ).first()
        if row is None:
            row = CounterRow(series=series, year=year, last=FIRST)
            self.session.add(row)
            self.session.flush()
        return row

    def next(self, series: str, year: int) -> str:
        """The next number, as it is written on the document."""
        row = self._counter(series, year)
        if row.last >= CEILING:
            raise SeriesExhausted(
                f"series {series} has used every number in {year}",
                series=series,
                year=year,
            )
        row.last += 1
        self.session.flush()
        return f"{year % 100:02d}-{row.last}"

    def peek(self, series: str, year: int) -> int:
        """What the counter stands at now. Reads without taking a number."""
        row = self.session.get(CounterRow, (series, year))
        return FIRST if row is None else row.last

    def reserve(self, series: str, year: int, count: int) -> list[str]:
        """A block of numbers, for a run that writes many documents at once."""
        if count < 1:
            raise NumberingError(f"{count} is not a number of documents")
        row = self._counter(series, year)
        if row.last + count > CEILING:
            raise SeriesExhausted(
                f"series {series} has {CEILING - row.last} numbers left in {year}, "
                f"and {count} were asked for",
                series=series,
                year=year,
                left=CEILING - row.last,
            )
        first = row.last + 1
        row.last += count
        self.session.flush()
        return [f"{year % 100:02d}-{number}" for number in range(first, row.last + 1)]

    def open_at(self, series: str, year: int, at: int) -> None:
        """Start a series somewhere other than the default. Migrations only."""
        if at < FIRST:
            raise NumberingError(f"a series cannot open below {FIRST}")
        row = self._counter(series, year)
        if row.last > FIRST:
            raise NumberingError(f"series {series} has already issued numbers in {year}")
        row.last = at
        self.session.flush()


def parse_number(number: str) -> tuple[int, int]:
    """``25-1044`` back into a year and a sequence."""
    year, _, sequence = number.partition("-")
    if not sequence or not year.isdigit() or not sequence.isdigit():
        raise NumberingError(f"{number!r} is not a document number")
    return 2000 + int(year), int(sequence)
