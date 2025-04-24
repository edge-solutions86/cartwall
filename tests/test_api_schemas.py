from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError as PydanticError

from cartwall.api.schemas import (
    Amount,
    LineIn,
    LineOut,
    OrderIn,
    OrderOut,
    length_of,
    money_of,
)
from cartwall.domain.order import Flight, Line, Order, OrderStatus
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import Money
from support import MONDAY

FIXED = ClassOfTime.FIXED


def a_line_body(number=1, **kw):
    return {
        "number": number,
        "station": kw.pop("station", "KWLO"),
        "daypart": kw.pop("daypart", "AMD"),
        "grade": kw.pop("grade", "fixed"),
        "length": kw.pop("length", ":30"),
        "rate": kw.pop("rate", "60.00"),
        "flight": {
            "first": "2025-08-04",
            "last": "2025-08-29",
            "days": "M-F",
            "per_week": 12,
        },
        **kw,
    }


def an_order_body(**kw):
    return {
        "number": kw.pop("number", "25-1044"),
        "advertiser": kw.pop("advertiser", "HYVEE"),
        "written": "2025-07-21",
        "salesperson": "DHOLM",
        "lines": kw.pop("lines", [a_line_body()]),
        **kw,
    }


def test_money_crosses_as_a_string_in_dollars():
    line = LineIn(**a_line_body(rate="60.00"))
    assert line.rate == "60.00"
    assert money_of(line.rate) == Money.dollars(60)


def test_a_json_number_for_money_is_refused_loudly():
    with pytest.raises(PydanticError, match="send money as a string"):
        LineIn(**a_line_body(rate=60.0))


def test_a_whole_dollar_integer_is_accepted_and_normalised():
    assert LineIn(**a_line_body(rate=60)).rate == "60.00"
    assert LineIn(**a_line_body(rate=Decimal("60.5"))).rate == "60.50"


def test_something_that_is_not_an_amount():
    with pytest.raises(PydanticError, match="is not an amount"):
        LineIn(**a_line_body(rate="sixty dollars"))
    with pytest.raises(PydanticError, match="is not an amount"):
        LineIn(**a_line_body(rate=None))


def test_an_amount_with_a_comma_or_a_sign_is_still_an_amount():
    assert money_of(LineIn(**a_line_body(rate="$1,250.00")).rate) == Money.dollars(1250)


def test_a_length_crosses_the_way_the_log_prints_it():
    assert LineIn(**a_line_body(length=":30")).length == ":30"
    assert LineIn(**a_line_body(length="1:00")).length == "1:00"
    assert LineIn(**a_line_body(length=30)).length == ":30"
    assert length_of(":60") == SIXTY


def test_a_length_that_is_not_a_length_is_refused_at_the_boundary():
    with pytest.raises(PydanticError):
        LineIn(**a_line_body(length=None))
    body = LineIn(**a_line_body(length=":25"))
    with pytest.raises(Exception, match="not sold here"):
        body.to_domain()


def test_a_line_body_turns_into_a_line():
    line = LineIn(**a_line_body()).to_domain()
    assert isinstance(line, Line)
    assert line.rate == Money.dollars(60)
    assert line.length == THIRTY
    assert line.grade is FIXED
    assert line.spots == 48


def test_a_flight_needs_at_least_one_a_week():
    body = a_line_body()
    body["flight"]["per_week"] = 0
    with pytest.raises(PydanticError):
        LineIn(**body)


def test_a_line_number_below_one_is_refused():
    with pytest.raises(PydanticError):
        LineIn(**a_line_body(number=0))


def test_a_field_nobody_declared_is_an_error_and_not_a_shrug():
    with pytest.raises(PydanticError, match="Extra inputs are not permitted"):
        LineIn(**a_line_body(colour="blue"))
    with pytest.raises(PydanticError, match="Extra inputs are not permitted"):
        OrderIn(**an_order_body(notes="call me"))


def test_an_order_body_turns_into_an_order():
    order = OrderIn(**an_order_body(lines=[a_line_body(1), a_line_body(2, station="KCRR")]))
    domain = order.to_domain()
    assert isinstance(domain, Order)
    assert len(domain) == 2
    assert domain.status is OrderStatus.PENDING
    assert domain.value == Money.dollars(60 * 96)


def test_an_order_needs_a_number_that_is_not_blank():
    with pytest.raises(PydanticError, match="an order needs a number"):
        OrderIn(**an_order_body(number="   "))


def test_an_order_with_no_lines_is_a_legitimate_body():
    order = OrderIn(**an_order_body(lines=[])).to_domain()
    assert len(order) == 0


def a_line(number=1, **kw):
    return Line(
        number=number,
        station=kw.pop("station", "KWLO"),
        daypart=kw.pop("daypart", "AMD"),
        grade=FIXED,
        length=kw.pop("length", THIRTY),
        rate=kw.pop("rate", Money.dollars(60)),
        flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
        carts=kw.pop("carts", ()),
    )


def test_a_line_comes_back_with_its_money_as_strings():
    out = LineOut.of(a_line())
    assert out.rate == "60.00"
    assert out.value == "2880.00"
    assert out.length == ":30"
    assert out.spots == 48


def test_a_line_comes_back_with_its_flight_written_out():
    out = LineOut.of(a_line())
    assert out.flight.days == "M-F"
    assert out.flight.per_week == 12
    assert out.flight.spots == 48
    assert out.flight.first == MONDAY


def test_a_lines_carts_come_back_in_order():
    assert LineOut.of(a_line(carts=("C1204", "C1205"))).carts == ["C1204", "C1205"]


def test_an_order_comes_back_with_its_total_as_a_string():
    order = Order(
        number="25-1044", advertiser="HYVEE", written=date(2025, 7, 21),
        salesperson="DHOLM", lines=(a_line(1), a_line(2, rate=Money.parse("32.50"))),
        agency="MOP", campaign="back to school",
    )
    out = OrderOut.of(order)
    assert out.value == "4440.00"
    assert out.spots == 96
    assert out.campaign == "back to school"
    assert out.agency == "MOP"
    assert [line.number for line in out.lines] == [1, 2]


def test_the_serialised_body_has_no_json_numbers_where_money_belongs():
    order = Order(
        number="25-1044", advertiser="HYVEE", written=date(2025, 7, 21),
        salesperson="DHOLM", lines=(a_line(),),
    )
    body = OrderOut.of(order).model_dump(mode="json")
    assert isinstance(body["value"], str)
    assert isinstance(body["lines"][0]["rate"], str)
    assert isinstance(body["lines"][0]["spots"], int)


def test_the_annotation_is_reusable_wherever_an_amount_appears():
    assert Amount is not None
    assert LineOut.model_fields["rate"].annotation is str
