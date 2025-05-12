"""What goes over the wire, and why money is a string on it.

JSON numbers are IEEE doubles in every client anybody will point at this. A
rate of ``60.00`` survives that and a running total of ``4835.15`` does not
always, and the failure is a cent that appears in one client and not another
against the same response. So every amount crosses as a string in dollars,
every length crosses as a string in the form the log prints, and the boundary
converts.

That means a client cannot do arithmetic on what it is given without parsing
first, which is the point. The arithmetic is done here, where it is done in
integer cents, and the response carries the answer.

The shapes are deliberately not the domain objects. An ``Order`` knows how to
confirm itself and an ``OrderOut`` does not, and a schema that grew methods
would be a second domain model in a place nobody looks for one.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Annotated, Any

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, field_validator

from ..airtime import AirTime, Duration
from ..domain.order import Flight, Line, Order, OrderStatus
from ..domain.ratecard import ClassOfTime
from ..domain.spot import SpotLength
from ..money import Money


def _as_amount(value: Any) -> str:
    """Accept a string, a Decimal or an int. Refuse a float, loudly."""
    if isinstance(value, Money):
        return str(value.amount)
    if isinstance(value, float):
        raise ValueError("send money as a string, not as a JSON number")
    if isinstance(value, int | Decimal):
        return str(Decimal(value).quantize(Decimal("0.01")))
    if isinstance(value, str):
        try:
            Decimal(value.replace(",", "").lstrip("$"))
        except InvalidOperation:
            raise ValueError(f"{value!r} is not an amount") from None
        return value
    raise ValueError(f"{value!r} is not an amount")


Amount = Annotated[str, BeforeValidator(_as_amount)]


def money_of(text: str) -> Money:
    return Money.parse(text)


def _as_length(value: Any) -> str:
    if isinstance(value, SpotLength):
        return str(value)
    if isinstance(value, int):
        return str(SpotLength(value))
    if isinstance(value, str):
        return value
    raise ValueError(f"{value!r} is not a length")


Length = Annotated[str, BeforeValidator(_as_length)]


def length_of(text: str) -> SpotLength:
    return SpotLength.parse(text)


def _as_clock(value: Any) -> str:
    if isinstance(value, AirTime):
        return value.twelve_hour()
    if isinstance(value, str):
        return value
    raise ValueError(f"{value!r} is not an air time")


Clock = Annotated[str, BeforeValidator(_as_clock)]


class Shape(BaseModel):
    """Common configuration. Unknown fields are an error, not a shrug."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class FlightIn(Shape):
    first: date
    last: date
    days: str = Field(examples=["M-F", "Sa,Su"])
    per_week: int = Field(ge=1, le=500)


class LineIn(Shape):
    number: int = Field(ge=1)
    station: str
    daypart: str
    grade: ClassOfTime
    length: Length
    rate: Amount
    flight: FlightIn
    carts: list[str] = Field(default_factory=list)

    def to_domain(self) -> Line:
        return Line(
            number=self.number,
            station=self.station,
            daypart=self.daypart,
            grade=self.grade,
            length=length_of(self.length),
            rate=money_of(self.rate),
            flight=Flight.build(
                self.flight.first, self.flight.last, self.flight.days, self.flight.per_week
            ),
            carts=tuple(self.carts),
        )


class OrderIn(Shape):
    number: str
    advertiser: str
    written: date
    salesperson: str
    agency: str | None = None
    campaign: str = ""
    lines: list[LineIn] = Field(default_factory=list)

    @field_validator("number")
    @classmethod
    def _numbered(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("an order needs a number")
        return value

    def to_domain(self) -> Order:
        return Order(
            number=self.number,
            advertiser=self.advertiser,
            written=self.written,
            salesperson=self.salesperson,
            lines=tuple(line.to_domain() for line in self.lines),
            agency=self.agency,
            campaign=self.campaign,
        )


class FlightOut(Shape):
    first: date
    last: date
    days: str
    per_week: int
    spots: int


class LineOut(Shape):
    number: int
    station: str
    daypart: str
    grade: ClassOfTime
    length: Length
    rate: Amount
    value: Amount
    spots: int
    flight: FlightOut
    carts: list[str]

    @classmethod
    def of(cls, line: Line) -> LineOut:
        from ..domain.daypart import show_days

        return cls(
            number=line.number,
            station=line.station,
            daypart=line.daypart,
            grade=line.grade,
            length=str(line.length),
            rate=str(line.rate.amount),
            value=str(line.value.amount),
            spots=line.spots,
            flight=FlightOut(
                first=line.flight.first,
                last=line.flight.last,
                days=show_days(line.flight.days),
                per_week=line.flight.per_week,
                spots=line.flight.spots,
            ),
            carts=list(line.carts),
        )


class OrderOut(Shape):
    number: str
    advertiser: str
    agency: str | None
    written: date
    salesperson: str
    campaign: str
    status: OrderStatus
    spots: int
    value: Amount
    lines: list[LineOut]

    @classmethod
    def of(cls, order: Order) -> OrderOut:
        return cls(
            number=order.number,
            advertiser=order.advertiser,
            agency=order.agency,
            written=order.written,
            salesperson=order.salesperson,
            campaign=order.campaign,
            status=order.status,
            spots=order.spots,
            value=str(order.value.amount),
            lines=[LineOut.of(line) for line in order.lines],
        )


class SpotOut(Shape):
    at: Clock
    hour: int
    break_number: int
    order: str
    line: int
    advertiser: str
    length: Length
    cart: str


class AvailOut(Shape):
    label: str
    units: int
    sold: int
    open: int
    sellout: float
    value: Amount


def duration_text(length: Duration) -> str:
    return str(length)
