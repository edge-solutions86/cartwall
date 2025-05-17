"""Writing the trail, and reading it back when somebody asks.

Two functions do the writing and they are deliberately narrow. ``record`` takes
who, what and about what, and there is no way to pass anything that would let a
caller write a row nobody can find later: the kind and the reference are
required and neither may be blank.

The reads are the interesting part, because there are only three questions
anybody ever asks:

* What happened to this order. Everything about one document, oldest first.
* What did this person do. Everything by one actor in a span.
* What happened on this day. Everything, oldest first, which is what somebody
  reads when they have a complaint and a date and nothing else.

Writing to the trail never fails a caller's work. A record that cannot be
written is logged and swallowed, because losing an audit row is bad and
refusing to confirm an order because the audit table is full is worse. That is
a real trade and it is made here rather than at every call site.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..errors import ValidationError
from ..logging import failed, pairs
from ..persistence.models.audit import AuditRow


class AuditError(ValidationError):
    """An entry that cannot be written."""

    code = "bad_audit"


#: The job names used when nothing human did it.
NIGHTLY = "log builder"
BILLING = "invoice run"
RECONCILER = "as run posting"


@dataclass(frozen=True, slots=True)
class Entry:
    """One row of the trail, as a caller sees it."""

    at: datetime
    action: str
    actor: str
    kind: str
    reference: str
    detail: str = ""

    def __str__(self) -> str:
        stamp = self.at.strftime("%Y-%m-%dT%H:%M:%S")
        tail = f" {self.detail}" if self.detail else ""
        return f"{stamp} {self.actor} {self.action} {self.kind}:{self.reference}{tail}"


def entry_from(row: AuditRow) -> Entry:
    return Entry(
        at=row.at,
        action=row.action,
        actor=row.actor,
        kind=row.kind,
        reference=row.reference,
        detail=row.detail,
    )


def record(
    session: Session,
    action: str,
    actor: str,
    kind: str,
    reference: str,
    at: datetime | None = None,
    **detail,
) -> AuditRow:
    """Append one entry. Refuses an entry nobody could find again."""
    if not action:
        raise AuditError("an audit entry needs an action")
    if not actor:
        raise AuditError(f"{action} was done by nobody")
    if not kind or not reference:
        raise AuditError(f"{action} is about nothing in particular")
    row = AuditRow(
        at=at or datetime.now(),
        action=action,
        actor=actor,
        kind=kind,
        reference=str(reference),
        detail=pairs(**detail),
    )
    session.add(row)
    return row


def try_record(session: Session, action: str, actor: str, kind: str, reference: str, **detail):
    """Append, and swallow a failure rather than losing the caller's work."""
    try:
        return record(session, action, actor, kind, reference, **detail)
    except Exception as trouble:  # pragma: no cover - only on a broken database
        failed("audit.lost", action=action, kind=kind, reference=reference, why=str(trouble))
        return None


def about(session: Session, kind: str, reference: str) -> list[Entry]:
    """Everything that happened to one document, oldest first."""
    rows = session.scalars(
        select(AuditRow)
        .where(AuditRow.kind == kind, AuditRow.reference == str(reference))
        .order_by(AuditRow.at, AuditRow.id)
    ).all()
    return [entry_from(row) for row in rows]


def by_actor(session: Session, actor: str, first: date, last: date) -> list[Entry]:
    """Everything one person did in a span, oldest first."""
    if last < first:
        raise AuditError("the span runs backwards")
    rows = session.scalars(
        select(AuditRow)
        .where(
            AuditRow.actor == actor,
            AuditRow.at >= datetime.combine(first, datetime.min.time()),
            AuditRow.at <= datetime.combine(last, datetime.max.time()),
        )
        .order_by(AuditRow.at, AuditRow.id)
    ).all()
    return [entry_from(row) for row in rows]


def on_day(session: Session, day: date) -> list[Entry]:
    """Everything, oldest first, for somebody with a complaint and a date."""
    rows = session.scalars(
        select(AuditRow)
        .where(
            AuditRow.at >= datetime.combine(day, datetime.min.time()),
            AuditRow.at <= datetime.combine(day, datetime.max.time()),
        )
        .order_by(AuditRow.at, AuditRow.id)
    ).all()
    return [entry_from(row) for row in rows]


def count(session: Session) -> int:
    return len(session.scalars(select(AuditRow.id)).all())
