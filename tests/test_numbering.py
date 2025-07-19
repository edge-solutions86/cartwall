import pytest

from cartwall.persistence.base import create_all, drop_all, engine_for, session_factory
from cartwall.services.numbering import (
    CEILING,
    FIRST,
    Numbering,
    NumberingError,
    SeriesExhausted,
    parse_number,
)
from support import MEMORY


@pytest.fixture
def session():
    engine = engine_for(MEMORY)
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        yield session
    drop_all(engine)


@pytest.fixture
def numbers(session):
    return Numbering(session)


def test_the_first_number_of_a_year_is_one_past_the_floor(numbers):
    assert numbers.next("ORD", 2025) == "25-1001"


def test_numbers_run_on_without_gaps(numbers):
    issued = [numbers.next("ORD", 2025) for _ in range(5)]
    assert issued == ["25-1001", "25-1002", "25-1003", "25-1004", "25-1005"]


def test_two_series_do_not_share_a_counter(numbers):
    assert numbers.next("ORD", 2025) == "25-1001"
    assert numbers.next("INV", 2025) == "25-1001"
    assert numbers.next("ORD", 2025) == "25-1002"
    assert numbers.next("INV", 2025) == "25-1002"


def test_a_new_year_starts_the_series_again(numbers):
    for _ in range(3):
        numbers.next("ORD", 2025)
    assert numbers.next("ORD", 2026) == "26-1001"
    assert numbers.next("ORD", 2025) == "25-1004"


def test_the_counter_can_be_read_without_taking_a_number(numbers):
    assert numbers.peek("ORD", 2025) == FIRST
    numbers.next("ORD", 2025)
    assert numbers.peek("ORD", 2025) == FIRST + 1
    assert numbers.peek("ORD", 2025) == FIRST + 1


def test_a_series_that_cannot_be_drawn_from(numbers):
    with pytest.raises(NumberingError, match="needs a code"):
        numbers.next("", 2025)
    with pytest.raises(NumberingError, match="not a year this house trades in"):
        numbers.next("ORD", 1999)
    with pytest.raises(NumberingError, match="not a year this house trades in"):
        numbers.next("ORD", 3000)


def test_a_block_can_be_reserved_for_a_run(numbers):
    block = numbers.reserve("INV", 2025, 4)
    assert block == ["25-1001", "25-1002", "25-1003", "25-1004"]
    assert numbers.next("INV", 2025) == "25-1005"


def test_a_block_of_nothing_is_not_a_block(numbers):
    with pytest.raises(NumberingError, match="not a number of documents"):
        numbers.reserve("INV", 2025, 0)


def test_a_series_that_runs_out_says_how_many_are_left(numbers):
    numbers.open_at("INV", 2025, CEILING - 2)
    assert numbers.reserve("INV", 2025, 2) == ["25-9998", "25-9999"]
    with pytest.raises(SeriesExhausted, match="every number in 2025") as caught:
        numbers.next("INV", 2025)
    assert caught.value.code == "series_exhausted"


def test_a_block_bigger_than_what_is_left_is_refused_before_it_starts(numbers):
    numbers.open_at("INV", 2025, CEILING - 3)
    with pytest.raises(SeriesExhausted, match="has 3 numbers left") as caught:
        numbers.reserve("INV", 2025, 4)
    assert caught.value.context["left"] == 3
    # And nothing was taken, so the three that are left are still there.
    assert numbers.reserve("INV", 2025, 3) == ["25-9997", "25-9998", "25-9999"]


def test_a_series_can_be_opened_somewhere_else_before_it_is_used(numbers):
    numbers.open_at("ORD", 2025, 4200)
    assert numbers.next("ORD", 2025) == "25-4201"


def test_a_series_that_has_already_issued_cannot_be_reopened(numbers):
    numbers.next("ORD", 2025)
    with pytest.raises(NumberingError, match="already issued numbers"):
        numbers.open_at("ORD", 2025, 4200)


def test_a_series_cannot_open_below_the_floor(numbers):
    with pytest.raises(NumberingError, match="cannot open below 1000"):
        numbers.open_at("ORD", 2025, 500)


def test_rolling_back_puts_the_number_back(session):
    numbers = Numbering(session)
    assert numbers.next("ORD", 2025) == "25-1001"
    session.commit()
    assert numbers.next("ORD", 2025) == "25-1002"
    session.rollback()
    assert Numbering(session).next("ORD", 2025) == "25-1002"


def test_a_committed_number_stays_taken(session):
    Numbering(session).next("ORD", 2025)
    session.commit()
    assert Numbering(session).peek("ORD", 2025) == 1001


def test_a_number_reads_back_as_a_year_and_a_sequence():
    assert parse_number("25-1044") == (2025, 1044)
    assert parse_number("26-1001") == (2026, 1001)


def test_something_that_is_not_a_document_number():
    for text in ("", "1044", "25-", "-1044", "AB-1044", "25-10x4"):
        with pytest.raises(NumberingError, match="not a document number"):
            parse_number(text)


def test_the_counter_row_prints_where_it_stands(session):
    Numbering(session).next("ORD", 2025)
    from cartwall.persistence.models.office import CounterRow

    row = session.get(CounterRow, ("ORD", 2025))
    assert repr(row) == "<CounterRow ORD 2025 at 1001>"
