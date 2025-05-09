"""Building an invoice out of what aired, one order, one broadcast month.

The shape of the document is set by what the agency's payables clerk needs to
match it against their own paperwork, and that is not negotiable either:

* **One invoice per order per broadcast month.** Not per advertiser, because an
  agency reconciles against the order they placed. Not per calendar month,
  because the order was sold on the broadcast calendar.
* **One detail line per spot**, in air order, with the date, the time it really
  ran, the length and the rate. An agency that cannot see the times will not
  pay, and the time they want is the one off the as-run, not the log.
* **A summary by line**, because the seller reads that and the clerk does not
  read the detail at all when the summary agrees with the order.

The money runs in one direction and only one. Gross is the sum of the spots at
the rate on the order. Commission comes off the gross. Net is what the agency
remits. A spot that did not air is not on the invoice at any of those three
numbers, and a spot that ran short still bills in full unless somebody writes a
credit, because the client got a spot and a shortfall is an argument rather
than an arithmetic.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..broadcast import BroadcastMonth
from ..domain.spot import SpotLength
from ..errors import ValidationError
from ..money import ZERO, Money, commission, to_net, total


class InvoiceError(ValidationError):
    """An invoice that cannot be raised."""

    code = "bad_invoice"


@dataclass(frozen=True, slots=True)
class Detail:
    """One spot on the invoice, as it aired."""

    day: date
    at: str
    line: int
    length: SpotLength
    rate: Money
    cart: str = ""
    #: Set when the spot ran somewhere other than where it was sold.
    moved: bool = False

    def __str__(self) -> str:
        return f"{self.day:%m/%d} {self.at} {self.length} {self.rate}"


@dataclass(frozen=True, slots=True)
class Summary:
    """One order line's month, which is what the seller reads."""

    line: int
    station: str
    daypart: str
    length: SpotLength
    rate: Money
    spots: int

    @property
    def gross(self) -> Money:
        return self.rate * self.spots

    def __str__(self) -> str:
        return f"line {self.line} {self.station} {self.daypart} {self.spots} at {self.rate}"


@dataclass
class Invoice:
    """One order, one broadcast month, what aired and what it comes to."""

    number: str
    order: str
    advertiser: str
    payer: str
    month: BroadcastMonth
    #: What comes off the gross. Zero for direct business.
    commission_rate: Decimal = Decimal("0")
    details: list[Detail] = field(default_factory=list)
    summaries: list[Summary] = field(default_factory=list)
    raised: date | None = None

    def __post_init__(self) -> None:
        if not self.number:
            raise InvoiceError("an invoice needs a number")
        if not self.order:
            raise InvoiceError(f"invoice {self.number} names no order")
        if not 0 <= self.commission_rate < 1:
            raise InvoiceError(
                f"invoice {self.number} takes {self.commission_rate} off, "
                "which is not a commission"
            )

    @property
    def spots(self) -> int:
        return len(self.details)

    @property
    def gross(self) -> Money:
        return total([detail.rate for detail in self.details])

    @property
    def commission(self) -> Money:
        return commission(self.gross, self.commission_rate)

    @property
    def net(self) -> Money:
        return to_net(self.gross, self.commission_rate)

    @property
    def is_empty(self) -> bool:
        return not self.details

    def for_line(self, line: int) -> list[Detail]:
        return [detail for detail in self.details if detail.line == line]

    def summary_for(self, line: int) -> Summary | None:
        for summary in self.summaries:
            if summary.line == line:
                return summary
        return None

    def days(self) -> list[date]:
        return sorted({detail.day for detail in self.details})

    def check_adds_up(self) -> None:
        """The summary and the detail have to agree, or nobody pays either."""
        detail = self.gross
        summed = total([summary.gross for summary in self.summaries])
        if detail != summed:
            raise InvoiceError(
                f"invoice {self.number} details come to {detail} and the summary "
                f"comes to {summed}",
                invoice=self.number,
                detail=str(detail),
                summary=str(summed),
            )
        counted = sum(summary.spots for summary in self.summaries)
        if counted != self.spots:
            raise InvoiceError(
                f"invoice {self.number} has {self.spots} spots in detail and "
                f"{counted} in the summary",
                invoice=self.number,
            )

    def __str__(self) -> str:
        return f"{self.number} {self.payer} {self.month} {self.spots} spots {self.net}"


def summarise(details: list[Detail], lines: dict[int, tuple[str, str]]) -> list[Summary]:
    """Roll the detail up by order line, in line number order.

    ``lines`` maps a line number to its station and daypart, which the detail
    does not carry because it would be the same value on every row.
    """
    counted: dict[int, list[Detail]] = {}
    for detail in details:
        counted.setdefault(detail.line, []).append(detail)
    summaries = []
    for number in sorted(counted):
        rows = counted[number]
        rates = {detail.rate for detail in rows}
        if len(rates) > 1:
            raise InvoiceError(
                f"line {number} has {len(rates)} different rates on one invoice",
                line=number,
            )
        station, daypart = lines.get(number, ("", ""))
        summaries.append(
            Summary(
                line=number,
                station=station,
                daypart=daypart,
                length=rows[0].length,
                rate=rows[0].rate,
                spots=len(rows),
            )
        )
    return summaries


def empty(number: str, order: str, advertiser: str, payer: str, month: BroadcastMonth) -> Invoice:
    """An invoice with nothing on it. Raised, then never sent."""
    return Invoice(
        number=number,
        order=order,
        advertiser=advertiser,
        payer=payer,
        month=month,
    )


def running_total(invoices: list[Invoice]) -> tuple[Money, Money, Money]:
    """Gross, commission and net across a batch, for the run's own footer."""
    if not invoices:
        return (ZERO, ZERO, ZERO)
    return (
        total([invoice.gross for invoice in invoices]),
        total([invoice.commission for invoice in invoices]),
        total([invoice.net for invoice in invoices]),
    )
