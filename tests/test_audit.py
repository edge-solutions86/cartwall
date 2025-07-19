from datetime import date, datetime

import pytest
from sqlalchemy import select

from cartwall.persistence.base import create_all, engine_for, session_factory
from cartwall.persistence.models.audit import AuditRow
from cartwall.services.audit import (
    NIGHTLY,
    AuditError,
    about,
    by_actor,
    count,
    on_day,
    record,
    try_record,
)
from support import MEMORY

MONDAY = date(2025, 8, 4)
NINE = datetime(2025, 8, 4, 9, 0, 0)
TEN = datetime(2025, 8, 4, 10, 0, 0)


@pytest.fixture
def session():
    engine = engine_for(MEMORY)
    create_all(engine)
    with session_factory(engine)() as session:
        yield session


def test_an_entry_records_who_did_what_to_which_document(session):
    record(session, "order.confirmed", "DHOLM", "order", "25-1044", at=NINE, value="$2,880.00")
    session.commit()
    row = session.scalars(select(AuditRow)).one()
    assert row.action == "order.confirmed"
    assert row.actor == "DHOLM"
    assert row.kind == "order"
    assert row.reference == "25-1044"
    assert row.detail == "value=$2,880.00"
    assert repr(row) == "<AuditRow order.confirmed order:25-1044>"


def test_an_entry_with_no_detail_is_still_an_entry(session):
    record(session, "log.released", NIGHTLY, "log", "KWLO 2025-08-04", at=NINE)
    session.commit()
    assert session.scalars(select(AuditRow)).one().detail == ""


def test_an_entry_nobody_could_find_again_is_refused(session):
    with pytest.raises(AuditError, match="needs an action"):
        record(session, "", "DHOLM", "order", "25-1044")
    with pytest.raises(AuditError, match="was done by nobody"):
        record(session, "order.confirmed", "", "order", "25-1044")
    with pytest.raises(AuditError, match="about nothing in particular"):
        record(session, "order.confirmed", "DHOLM", "", "25-1044")
    with pytest.raises(AuditError, match="about nothing in particular"):
        record(session, "order.confirmed", "DHOLM", "order", "")


def test_the_reference_is_written_as_text_whatever_it_came_in_as(session):
    record(session, "invoice.raised", "billing", "invoice", 1044, at=NINE)
    session.commit()
    assert session.scalars(select(AuditRow)).one().reference == "1044"


def test_a_detail_value_with_a_space_does_not_split_a_field(session):
    record(
        session, "order.written", "DHOLM", "order", "25-1044", at=NINE, advertiser="Black Hawk Bank"
    )
    session.commit()
    assert session.scalars(select(AuditRow)).one().detail == 'advertiser="Black Hawk Bank"'


def test_nothing_points_at_anything_so_an_entry_outlives_its_document(session):
    table = AuditRow.__table__
    assert list(table.foreign_keys) == []
    record(session, "order.deleted", "DHOLM", "order", "25-9999", at=NINE)
    session.commit()
    # There has never been an order 25-9999 and the entry is still there.
    assert count(session) == 1


def test_the_trail_of_one_document_comes_back_oldest_first(session):
    record(session, "order.written", "DHOLM", "order", "25-1044", at=TEN)
    record(session, "order.confirmed", "DHOLM", "order", "25-1044", at=NINE)
    record(session, "order.written", "DHOLM", "order", "25-2000", at=NINE)
    session.commit()
    trail = about(session, "order", "25-1044")
    assert [entry.action for entry in trail] == ["order.confirmed", "order.written"]
    assert about(session, "order", "25-9999") == []


def test_two_entries_at_the_same_moment_come_back_in_the_order_written(session):
    record(session, "first", "DHOLM", "order", "25-1044", at=NINE)
    record(session, "second", "DHOLM", "order", "25-1044", at=NINE)
    session.commit()
    assert [entry.action for entry in about(session, "order", "25-1044")] == [
        "first",
        "second",
    ]


def test_what_one_person_did_in_a_span(session):
    record(session, "order.written", "DHOLM", "order", "25-1044", at=NINE)
    record(session, "order.written", "DHOLM", "order", "25-2000", at=datetime(2025, 8, 6, 9, 0))
    record(session, "order.written", "TRONAN", "order", "25-3000", at=NINE)
    session.commit()
    assert len(by_actor(session, "DHOLM", MONDAY, date(2025, 8, 10))) == 2
    assert len(by_actor(session, "DHOLM", MONDAY, MONDAY)) == 1
    assert by_actor(session, "NOBODY", MONDAY, date(2025, 8, 10)) == []


def test_a_span_that_runs_backwards_is_refused(session):
    with pytest.raises(AuditError, match="runs backwards"):
        by_actor(session, "DHOLM", date(2025, 8, 10), MONDAY)


def test_everything_that_happened_on_one_day(session):
    record(session, "a", "DHOLM", "order", "1", at=datetime(2025, 8, 4, 0, 0, 0))
    record(session, "b", "TRONAN", "log", "2", at=datetime(2025, 8, 4, 23, 59, 59))
    record(session, "c", "DHOLM", "order", "3", at=datetime(2025, 8, 5, 0, 0, 0))
    session.commit()
    assert [entry.action for entry in on_day(session, MONDAY)] == ["a", "b"]
    assert [entry.action for entry in on_day(session, date(2025, 8, 5))] == ["c"]
    assert on_day(session, date(2025, 8, 6)) == []


def test_an_entry_reads_as_a_sentence(session):
    record(session, "order.confirmed", "DHOLM", "order", "25-1044", at=NINE, spots=48)
    session.commit()
    printed = str(about(session, "order", "25-1044")[0])
    assert printed == "2025-08-04T09:00:00 DHOLM order.confirmed order:25-1044 spots=48"


def test_a_failed_write_does_not_take_the_callers_work_with_it(session):
    # A bad entry raises through record and is swallowed by try_record.
    with pytest.raises(AuditError):
        record(session, "", "DHOLM", "order", "25-1044")
    assert try_record(session, "", "DHOLM", "order", "25-1044") is None
    record(session, "order.confirmed", "DHOLM", "order", "25-1044", at=NINE)
    session.commit()
    assert count(session) == 1


def test_the_good_case_of_try_record_writes_the_row(session):
    assert try_record(session, "order.written", "DHOLM", "order", "25-1044") is not None
    session.commit()
    assert count(session) == 1


def test_the_unattended_jobs_have_names_of_their_own(session):
    record(session, "log.built", NIGHTLY, "log", "KWLO 2025-08-04", at=NINE)
    session.commit()
    assert by_actor(session, NIGHTLY, MONDAY, MONDAY)[0].actor == "log builder"
