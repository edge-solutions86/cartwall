from datetime import date
from decimal import Decimal

import pytest

from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.errors import LookupFailure
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.models.billing import InvoiceRow
from cartwall.persistence.models.credits import Reason
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.services.crediting import (
    CreditError,
    OverCredited,
    by_reason,
    credit_the_rebate,
    credits_against,
    outstanding,
    raise_credit,
    standing,
)
from cartwall.services.numbering import Numbering

FIXED = ClassOfTime.FIXED
MONDAY = date(2025, 8, 4)
RAISED = date(2025, 8, 25)


@pytest.fixture
def engine(tmp_path, cluster, accounts):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'credit.sqlite3'}")
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for number, advertiser in (("25-1044", "HYVEE"), ("25-2000", "DEALF")):
            uow.orders.save(
                Order(
                    number=number,
                    advertiser=advertiser,
                    written=date(2025, 7, 21),
                    salesperson="DHOLM",
                    lines=(
                        Line(
                            number=1,
                            station="KWLO",
                            daypart="AMD",
                            grade=FIXED,
                            length=THIRTY,
                            rate=Money.dollars(60),
                            flight=Flight.build(MONDAY, date(2025, 8, 8), "M-F", 5),
                        ),
                    ),
                )
            )
        uow.commit()
    return engine


def an_invoice(uow, number="25-1001", order="25-1044", advertiser="HYVEE", **kw):
    gross = kw.pop("gross", Money.dollars(300))
    rate = kw.pop("rate", Decimal("0"))
    row = InvoiceRow(
        number=number,
        order_number=order,
        advertiser=advertiser,
        payer=kw.pop("payer", advertiser),
        year=2025,
        month=kw.pop("month", 8),
        raised=RAISED,
        spots=5,
        gross=gross,
        commission_rate=rate,
        commission=gross.scale(rate),
        net=gross - gross.scale(rate),
    )
    uow.session.add(row)
    uow.session.flush()
    return row


def test_an_invoice_with_no_credits_owes_all_of_it(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        found = standing(uow, "25-1001")
    assert found.billed == Money.dollars(300)
    assert found.credited == Money(0)
    assert found.owing == Money.dollars(300)
    assert not found.is_settled
    assert str(found) == "25-1001 billed $300.00 less $0.00 leaves $300.00"


def test_a_credit_comes_off_what_is_owing(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        raise_credit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED, spots=1)
        assert standing(uow, "25-1001").owing == Money.dollars(240)


def test_a_credit_takes_a_number_off_its_own_counter(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        first = raise_credit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED)
        second = raise_credit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED)
        assert (first.number, second.number) == ("25-1001", "25-1002")
        # The invoice series is untouched by the credit series.
        assert Numbering(uow.session).peek("INV", 2025) == 1000


def test_a_credit_on_an_agency_invoice_takes_the_commission_off_too(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow, order="25-2000", advertiser="DEALF", payer="MOP", rate=Decimal("0.15"))
        credit = raise_credit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED)
    assert credit.gross == Money.dollars(60)
    assert credit.commission == Money.dollars(9)
    assert credit.net == Money.dollars(51)
    assert credit.payer == "MOP"


def test_a_credit_records_what_it_was_for(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        credit = raise_credit(
            uow, "25-1001", Money.dollars(60), Reason.WRONG_DAYPART, RAISED, spots=1
        )
    assert credit.reason is Reason.WRONG_DAYPART
    assert credit.spots == 1
    assert credit.note == ""
    assert not credit.sent
    assert repr(credit) == "<CreditRow 25-1001 against 25-1001>"


def test_a_credit_for_some_other_reason_needs_a_note(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        with pytest.raises(CreditError, match="needs a note"):
            raise_credit(uow, "25-1001", Money.dollars(60), Reason.OTHER, RAISED)
        credit = raise_credit(
            uow, "25-1001", Money.dollars(60), Reason.OTHER, RAISED, note=" goodwill "
        )
        assert credit.note == "goodwill"


def test_a_credit_of_nothing_or_less_is_not_a_credit(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        for amount in (Money(0), Money(-100)):
            with pytest.raises(CreditError, match="is not a credit"):
                raise_credit(uow, "25-1001", amount, Reason.DID_NOT_RUN, RAISED)


def test_a_credit_against_an_invoice_that_is_not_on_file(engine):
    with unit_of_work(engine) as uow:
        with pytest.raises(LookupFailure, match="invoice 25-9999 is not on file"):
            raise_credit(uow, "25-9999", Money.dollars(60), Reason.DID_NOT_RUN, RAISED)
        with pytest.raises(LookupFailure, match="is not on file"):
            standing(uow, "25-9999")


def test_more_than_is_left_is_refused_and_says_what_is_left(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        raise_credit(uow, "25-1001", Money.dollars(250), Reason.DID_NOT_RUN, RAISED)
        with pytest.raises(OverCredited, match="has \\$50.00 left") as caught:
            raise_credit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED)
    assert caught.value.context["left"] == "$50.00"
    assert caught.value.context["asked"] == "$60.00"


def test_crediting_the_whole_invoice_settles_it(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        raise_credit(uow, "25-1001", Money.dollars(300), Reason.DID_NOT_RUN, RAISED)
        assert standing(uow, "25-1001").is_settled


def test_the_credits_against_an_invoice_come_back_in_number_order(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        raise_credit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED)
        raise_credit(uow, "25-1001", Money.dollars(60), Reason.RATE_ERROR, RAISED)
        found = credits_against(uow, "25-1001")
        assert [row.number for row in found] == ["25-1001", "25-1002"]
        assert [row.reason for row in found] == [Reason.DID_NOT_RUN, Reason.RATE_ERROR]


def test_a_political_rebate_has_its_own_reason_and_needs_no_note(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        credit = credit_the_rebate(uow, "25-1001", Money.dollars(18), RAISED)
    assert credit.reason is Reason.POLITICAL_REBATE
    assert credit.gross == Money.dollars(18)


def test_the_month_end_groups_credits_by_reason_worst_first(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        raise_credit(uow, "25-1001", Money.dollars(30), Reason.DID_NOT_RUN, RAISED)
        raise_credit(uow, "25-1001", Money.dollars(90), Reason.RATE_ERROR, RAISED)
        raise_credit(uow, "25-1001", Money.dollars(20), Reason.DID_NOT_RUN, RAISED)
        tally = by_reason(uow, MONDAY, date(2025, 8, 31))
    assert list(tally) == ["rate error", "did not run"]
    assert tally["rate error"] == Money.dollars(90)
    assert tally["did not run"] == Money.dollars(50)


def test_a_credit_outside_the_span_is_not_counted(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow)
        raise_credit(uow, "25-1001", Money.dollars(30), Reason.DID_NOT_RUN, date(2025, 9, 2))
        assert by_reason(uow, MONDAY, date(2025, 8, 31)) == {}


def test_a_span_that_runs_backwards_is_refused(engine):
    with (
        unit_of_work(engine) as uow,
        pytest.raises(CreditError, match="runs backwards"),
    ):
        by_reason(uow, date(2025, 8, 31), MONDAY)


def test_the_outstanding_list_is_what_is_still_owing_most_first(engine):
    with unit_of_work(engine) as uow:
        an_invoice(uow, "25-1001", gross=Money.dollars(300))
        an_invoice(uow, "25-1002", order="25-2000", advertiser="DEALF", gross=Money.dollars(500))
        # A September invoice for the same order. One invoice per order a month.
        an_invoice(uow, "25-1003", gross=Money.dollars(100), month=9)
        raise_credit(uow, "25-1003", Money.dollars(100), Reason.DID_NOT_RUN, RAISED)
        found = outstanding(uow)
    assert [entry.invoice for entry in found] == ["25-1002", "25-1001"]
    assert found[0].owing == Money.dollars(500)
