from datetime import date
from decimal import Decimal

import pytest

from cartwall.billing.invoice import (
    Detail,
    Invoice,
    InvoiceError,
    Summary,
    empty,
    running_total,
    summarise,
)
from cartwall.broadcast import BroadcastMonth
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import ZERO, Money

AUGUST = BroadcastMonth(2025, 8)
MONDAY = date(2025, 8, 4)


def a_detail(day=MONDAY, at="7:10:00a", line=1, rate="60.00", length=THIRTY, **kw):
    return Detail(
        day=day, at=at, line=line, length=length, rate=Money.parse(rate),
        cart=kw.pop("cart", "C1204"), moved=kw.pop("moved", False),
    )


def an_invoice(details=None, rate="0", **kw):
    invoice = Invoice(
        number=kw.pop("number", "25-1001"),
        order=kw.pop("order", "25-1044"),
        advertiser=kw.pop("advertiser", "HYVEE"),
        payer=kw.pop("payer", "HYVEE"),
        month=AUGUST,
        commission_rate=Decimal(rate),
        details=details if details is not None else [a_detail()],
    )
    invoice.summaries = summarise(invoice.details, {1: ("KWLO", "AMD"), 2: ("KCRR", "MID")})
    return invoice


def test_an_invoice_that_cannot_be_raised():
    with pytest.raises(InvoiceError, match="needs a number"):
        Invoice(number="", order="25-1044", advertiser="H", payer="H", month=AUGUST)
    with pytest.raises(InvoiceError, match="names no order"):
        Invoice(number="25-1001", order="", advertiser="H", payer="H", month=AUGUST)
    with pytest.raises(InvoiceError, match="not a commission"):
        Invoice(
            number="25-1001", order="25-1044", advertiser="H", payer="H",
            month=AUGUST, commission_rate=Decimal("1"),
        )


def test_the_gross_is_the_spots_at_the_rate_on_the_order():
    invoice = an_invoice([a_detail(), a_detail(day=date(2025, 8, 5))])
    assert invoice.spots == 2
    assert invoice.gross == Money.parse("120.00")


def test_direct_business_takes_no_commission():
    invoice = an_invoice()
    assert invoice.commission == ZERO
    assert invoice.net == invoice.gross


def test_agency_business_takes_fifteen_percent_off_the_gross():
    invoice = an_invoice(rate="0.15")
    assert invoice.gross == Money.parse("60.00")
    assert invoice.commission == Money.parse("9.00")
    assert invoice.net == Money.parse("51.00")


def test_the_commission_comes_off_the_gross_and_not_off_each_spot():
    # A cent of rounding on every one of thirteen spots would show here.
    details = [a_detail(rate="0.07") for _ in range(13)]
    invoice = an_invoice(details, rate="0.15")
    assert invoice.gross == Money.parse("0.91")
    assert invoice.commission == Money.parse("0.14")
    assert invoice.net == Money.parse("0.77")


def test_an_invoice_with_nothing_on_it_is_worth_nothing():
    invoice = empty("25-1001", "25-1044", "HYVEE", "HYVEE", AUGUST)
    assert invoice.is_empty
    assert invoice.spots == 0
    assert invoice.gross == ZERO
    assert invoice.net == ZERO


def test_the_detail_carries_the_time_it_really_ran():
    detail = a_detail(at="7:10:04a")
    assert detail.at == "7:10:04a"
    assert str(detail) == "08/04 7:10:04a :30 $60.00"


def test_a_moved_spot_is_marked_and_still_billed():
    invoice = an_invoice([a_detail(moved=True)])
    assert invoice.details[0].moved
    assert invoice.gross == Money.parse("60.00")


def test_the_summary_rolls_the_detail_up_by_line():
    details = [
        a_detail(line=1),
        a_detail(line=1, day=date(2025, 8, 5)),
        a_detail(line=2, rate="22.00", length=SIXTY),
    ]
    invoice = an_invoice(details)
    assert [summary.line for summary in invoice.summaries] == [1, 2]
    first = invoice.summary_for(1)
    assert first.spots == 2
    assert first.gross == Money.parse("120.00")
    assert first.station == "KWLO"
    assert first.daypart == "AMD"
    second = invoice.summary_for(2)
    assert second.length == SIXTY
    assert second.gross == Money.parse("22.00")
    assert invoice.summary_for(9) is None


def test_a_summary_reads_the_way_the_seller_reads_it():
    summary = Summary(1, "KWLO", "AMD", THIRTY, Money.dollars(60), 12)
    assert str(summary) == "line 1 KWLO AMD 12 at $60.00"
    assert summary.gross == Money.dollars(720)


def test_two_rates_on_one_line_in_one_month_is_an_error_not_an_average():
    details = [a_detail(line=1, rate="60.00"), a_detail(line=1, rate="55.00")]
    with pytest.raises(InvoiceError, match="different rates on one invoice"):
        summarise(details, {})


def test_a_line_nobody_named_a_station_for_still_summarises():
    summary = summarise([a_detail(line=7)], {})[0]
    assert summary.line == 7
    assert summary.station == ""


def test_the_summary_and_the_detail_have_to_agree():
    invoice = an_invoice([a_detail(), a_detail(day=date(2025, 8, 5))])
    invoice.check_adds_up()
    invoice.summaries = [Summary(1, "KWLO", "AMD", THIRTY, Money.dollars(60), 1)]
    with pytest.raises(InvoiceError, match="details come to \\$120.00") as caught:
        invoice.check_adds_up()
    assert caught.value.context["summary"] == "$60.00"


def test_a_summary_that_counts_a_different_number_of_spots_is_caught():
    invoice = an_invoice([a_detail(rate="60.00")])
    invoice.summaries = [Summary(1, "KWLO", "AMD", THIRTY, Money.dollars(30), 2)]
    with pytest.raises(InvoiceError, match="1 spots in detail and 2 in the summary"):
        invoice.check_adds_up()


def test_an_invoice_lists_the_days_it_covers_once_each():
    details = [
        a_detail(day=date(2025, 8, 5)),
        a_detail(day=MONDAY),
        a_detail(day=MONDAY),
    ]
    assert an_invoice(details).days() == [MONDAY, date(2025, 8, 5)]


def test_an_invoice_finds_the_detail_of_one_line():
    details = [a_detail(line=1), a_detail(line=2, rate="22.00"), a_detail(line=1)]
    invoice = an_invoice(details)
    assert len(invoice.for_line(1)) == 2
    assert len(invoice.for_line(2)) == 1
    assert invoice.for_line(9) == []


def test_an_invoice_reads_as_a_sentence():
    invoice = an_invoice(rate="0.15", payer="MOP")
    assert str(invoice) == "25-1001 MOP 2025-08 1 spots $51.00"


def test_a_batch_totals_gross_commission_and_net():
    batch = [
        an_invoice([a_detail()], rate="0.15"),
        an_invoice([a_detail(), a_detail()], number="25-1002"),
    ]
    gross, comm, net = running_total(batch)
    assert gross == Money.parse("180.00")
    assert comm == Money.parse("9.00")
    assert net == Money.parse("171.00")


def test_an_empty_batch_totals_nothing():
    assert running_total([]) == (ZERO, ZERO, ZERO)
