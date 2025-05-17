"""The audit trail: append only, and pointing at nothing.

One table, written by everything, read by nobody in the ordinary course of a
day. It exists for the two conversations that eventually happen: the agency
that says a spot was moved and nobody told them, and the auditor who wants to
know who confirmed an order for an account on credit hold.

The design decision that matters is the one that looks wrong. **There are no
foreign keys out of this table.** The document it names is written down as a
kind and an identifier, in text, and nothing enforces that the row still
exists. That is on purpose. An audit entry has to outlive what it describes: an
order that is deleted, a log that is revised away, an advertiser closed and
purged. A trail that cascades away with the thing it recorded is not a trail.

Nothing updates a row here and nothing deletes one. The only operation is
append. There is no revision, no correction and no soft delete, because an
audit entry that can be edited is worth what an unsigned log is worth.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..base import CODE_LENGTH, NAME_LENGTH, Base


class AuditRow(Base):
    """One thing somebody did, and enough to find it again."""

    __tablename__ = "audit"
    __table_args__ = (
        Index("ix_audit_subject", "kind", "reference"),
        Index("ix_audit_at", "at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    at: Mapped[datetime] = mapped_column(DateTime)
    #: The event name, dotted, matching the log line. ``order.confirmed``.
    action: Mapped[str] = mapped_column(String(NAME_LENGTH))
    #: Who. A person's initials, or the name of the job that ran unattended.
    actor: Mapped[str] = mapped_column(String(NAME_LENGTH))
    #: What kind of thing it was about. ``order``, ``log``, ``invoice``.
    kind: Mapped[str] = mapped_column(String(CODE_LENGTH))
    #: Its identifier, as text, with no foreign key. See the note above.
    reference: Mapped[str] = mapped_column(String(64))
    #: Key equals value, the same shape as a log line.
    detail: Mapped[str] = mapped_column(Text, default="")

    def __repr__(self) -> str:
        return f"<AuditRow {self.action} {self.kind}:{self.reference}>"
