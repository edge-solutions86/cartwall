"""Tables the business office owns rather than traffic or sales.

At the moment that is one table, and it lives here rather than beside the
service that uses it for a reason worth writing down. A table declared inside a
service module is only registered on the metadata once something imports that
module, so ``create_all`` builds it or does not depending on what else the
process happened to touch. That is not a bug anybody finds quickly: the tests
that use the service import it and pass, and the migration check compares two
schemas that were both built after the import and agrees with itself.

Every table is declared under ``persistence.models``, and the package imports
all of them. Then there is one answer to what the schema is.
"""

from __future__ import annotations

from sqlalchemy import Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from ..base import CODE_LENGTH, Base

#: Document numbers open here so a four digit number always reads as one.
FIRST_NUMBER = 1000


class CounterRow(Base):
    """One row per series per year. The only row anybody locks."""

    __tablename__ = "counter"

    series: Mapped[str] = mapped_column(String(CODE_LENGTH), primary_key=True)
    year: Mapped[int] = mapped_column(Integer, primary_key=True)
    last: Mapped[int] = mapped_column(Integer, default=FIRST_NUMBER)

    def __repr__(self) -> str:
        return f"<CounterRow {self.series} {self.year} at {self.last}>"
