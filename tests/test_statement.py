from datetime import date, timedelta

import pytest

from cartwall.money import ZERO, Money
from cartwall.reports.statement import (
    Bucket,
    Item,
    Statement,
    StatementError,
    render,
    worst_first,
)

TODAY = date(2025, 11, 3)


def an_item(invoice="25-1001", days_old=0, billed="300.00", credited="0.00"):
    return Item(
        invoice=invoice,
        order="25-1044",
        raised=TODAY - timedelta(days=days_old),
        billed=Money.parse(billed),
        credited=Money.parse(credited),
    )


def a_statement(items=None, payer="MOP", on=TODAY):
    return Statement(payer=payer, on=on, items=items if items is not None else [an_item()])


def test_a_bucket_opens_at_its_own_day_count():
    assert Bucket.of(0) is Bucket.CURRENT
    assert Bucket.of(29) is Bucket.CURRENT
    assert Bucket.of(30) is Bucket.THIRTY
    assert Bucket.of(59) is Bucket.THIRTY
    assert Bucket.of(60) is Bucket.SIXTY
    assert Bucket.of(89) is Bucket.SIXTY
    assert Bucket.of(90) is Bucket.NINETY
    assert Bucket.of(400) is Bucket.NINETY


def test_the_buckets_read_the_way_the_office_says_them():
    assert [bucket.label for bucket in Bucket] == ["current", "30 days", "60 days", "90 plus"]


def test_an_item_owes_what_is_left_after_its_credits():
    item = an_item(billed="300.00", credited="60.00")
    assert item.owing == Money.dollars(240)


def test_an_item_is_aged_from_the_day_it_was_raised():
    assert an_item(days_old=45).age(TODAY) == 45
    assert an_item(days_old=45).bucket(TODAY) is Bucket.THIRTY


def test_an_invoice_raised_after_the_statement_date_is_refused():
    item = Item(
        invoice="25-1001",
        order="25-1044",
        raised=date(2025, 12, 1),
        billed=Money.dollars(300),
        credited=ZERO,
    )
    with pytest.raises(StatementError, match="after 2025-11-03") as caught:
        item.age(TODAY)
    assert caught.value.context["invoice"] == "25-1001"


def test_a_statement_totals_what_is_open():
    statement = a_statement([an_item("25-1001"), an_item("25-1002", billed="200.00")])
    assert statement.total == Money.dollars(500)
    assert not statement.is_clear


def test_a_fully_credited_invoice_drops_off_the_list():
    statement = a_statement(
        [an_item("25-1001", billed="300.00", credited="300.00"), an_item("25-1002")]
    )
    assert [item.invoice for item in statement.open_items()] == ["25-1002"]
    assert statement.total == Money.dollars(300)


def test_a_statement_with_nothing_open_is_clear():
    statement = a_statement([an_item(billed="300.00", credited="300.00")])
    assert statement.is_clear
    assert statement.open_items() == []
    assert statement.oldest() is None


def test_the_open_items_come_back_oldest_first():
    statement = a_statement(
        [
            an_item("25-1003", days_old=10),
            an_item("25-1001", days_old=95),
            an_item("25-1002", days_old=40),
        ]
    )
    assert [item.invoice for item in statement.open_items()] == [
        "25-1001",
        "25-1002",
        "25-1003",
    ]
    assert statement.oldest().invoice == "25-1001"


def test_the_aging_always_carries_all_four_buckets():
    statement = a_statement([an_item(days_old=5)])
    aging = statement.aging()
    assert list(aging) == list(Bucket)
    assert aging[Bucket.CURRENT] == Money.dollars(300)
    assert aging[Bucket.NINETY] == ZERO


def test_each_invoice_lands_in_the_bucket_its_age_says():
    statement = a_statement(
        [
            an_item("25-1001", days_old=5, billed="100.00"),
            an_item("25-1002", days_old=35, billed="200.00"),
            an_item("25-1003", days_old=65, billed="400.00"),
            an_item("25-1004", days_old=120, billed="800.00"),
        ]
    )
    aging = statement.aging()
    assert aging[Bucket.CURRENT] == Money.dollars(100)
    assert aging[Bucket.THIRTY] == Money.dollars(200)
    assert aging[Bucket.SIXTY] == Money.dollars(400)
    assert aging[Bucket.NINETY] == Money.dollars(800)


def test_a_credit_written_today_does_not_make_old_money_current():
    statement = a_statement([an_item("25-1001", days_old=95, billed="300.00", credited="100.00")])
    aging = statement.aging()
    assert aging[Bucket.NINETY] == Money.dollars(200)
    assert aging[Bucket.CURRENT] == ZERO


def test_what_is_past_due_is_everything_older_than_the_line():
    statement = a_statement(
        [
            an_item("25-1001", days_old=5, billed="100.00"),
            an_item("25-1002", days_old=35, billed="200.00"),
            an_item("25-1003", days_old=95, billed="400.00"),
        ]
    )
    assert statement.past_due(30) == Money.dollars(600)
    assert statement.past_due(60) == Money.dollars(400)
    assert statement.past_due(120) == ZERO


def test_a_statement_reads_as_a_sentence():
    statement = a_statement([an_item(), an_item("25-1002", billed="200.00")])
    assert str(statement) == "MOP as of 11/03/2025: $500.00 on 2"


def test_a_rendered_statement_puts_the_aging_at_the_foot():
    statement = a_statement(
        [
            an_item("25-1001", days_old=5, billed="100.00"),
            an_item("25-1002", days_old=95, billed="400.00"),
        ]
    )
    text = render(statement)
    assert "STATEMENT   MOP" in text
    assert "AS OF       11/03/2025" in text
    assert "25-1002" in text
    assert "TOTAL       $500.00" in text
    assert "current    $100.00" in text
    assert "90 plus    $400.00" in text
    assert "30 days    $0.00" in text


def test_a_clear_statement_still_renders():
    text = render(a_statement([an_item(billed="300.00", credited="300.00")]))
    assert "nothing outstanding" in text
    assert "TOTAL       $0.00" in text


def test_the_mondays_list_is_worst_past_due_first():
    statements = [
        a_statement([an_item(days_old=95, billed="100.00")], payer="MOP"),
        a_statement([an_item(days_old=95, billed="900.00")], payer="CRS"),
        a_statement([an_item(days_old=5, billed="900.00")], payer="HYVEE"),
    ]
    found = worst_first(statements, days=60)
    assert [entry.payer for entry in found] == ["CRS", "MOP"]


def test_nobody_past_due_makes_for_a_short_list():
    statements = [a_statement([an_item(days_old=5)], payer="MOP")]
    assert worst_first(statements, days=60) == []
