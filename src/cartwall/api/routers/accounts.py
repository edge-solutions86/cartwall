"""Advertisers, agencies, conflict codes and the copy library.

Reference data, and it moves about once a week. That makes it the one part of
the surface where the reads matter more than the writes, and where the read
everybody actually wants is the awkward one:

    GET /accounts/DEALF/conflicts

Who may this advertiser not sit beside. It is the question order entry asks
before quoting a fixed position, and it is the question the traffic director
asks when a schedule will not place. Working it out means walking every account
on file against this one's codes, which is why it is a route and not something
a client is expected to assemble.

Filing copy is a write and it belongs to production rather than to sales, so it
sits behind traffic. A seller who could file copy could name a cart on a line
that nobody has dubbed, and the log would go out with a hole in it.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Query, status

from ...airtime import Duration
from ...domain.account import Advertiser, Credit
from ...domain.spot import Cut, SpotLength
from ...errors import ConflictError
from ...persistence.unitofwork import UnitOfWork
from ...services.audit import try_record
from ..deps import Work
from ..schemas import Length, Shape, length_of
from ..security import Caller, Manager, Reader, Traffic

router = APIRouter(prefix="/accounts", tags=["accounts"])


class AdvertiserIn(Shape):
    code: str
    name: str
    categories: list[str] = []
    agency: str | None = None
    parent: str | None = None
    credit: Credit = Credit.OPEN


class AdvertiserOut(Shape):
    code: str
    name: str
    categories: list[str]
    agency: str | None
    parent: str | None
    credit: Credit
    #: What comes off this account's gross. Zero for direct business.
    commission_rate: str
    #: Whose name the invoice carries.
    payer: str


class AgencyOut(Shape):
    code: str
    name: str
    rate: str
    advertiser_liable: bool


class ConflictOut(Shape):
    code: str
    name: str
    #: The codes the two accounts collide through, broadest first.
    through: list[str]


class CutIn(Shape):
    cart: str
    isci: str
    advertiser: str
    length: Length
    #: What it really runs, in tenths. Production measures it.
    actual_tenths: int
    first_air: date
    last_air: date | None = None


class CutOut(Shape):
    cart: str
    isci: str
    advertiser: str
    length: Length
    actual_tenths: int
    first_air: date
    last_air: date | None
    killed: bool


def _advertiser_out(uow: UnitOfWork, advertiser: Advertiser) -> AdvertiserOut:
    book = uow.accounts.book()
    return AdvertiserOut(
        code=advertiser.code,
        name=advertiser.name,
        categories=sorted(str(code) for code in advertiser.categories),
        agency=advertiser.agency,
        parent=advertiser.parent,
        credit=advertiser.credit,
        commission_rate=_rate(book.commission_rate(advertiser.code)),
        payer=book.liable_party(advertiser.code),
    )


@router.get("/{code}", response_model=AdvertiserOut)
def read_advertiser(code: str, uow: UnitOfWork = Work, caller: Caller = Reader) -> AdvertiserOut:
    return _advertiser_out(uow, uow.accounts.book().advertiser(code))


@router.post("", response_model=AdvertiserOut, status_code=status.HTTP_201_CREATED)
def write_advertiser(
    body: AdvertiserIn, uow: UnitOfWork = Work, caller: Caller = Manager
) -> AdvertiserOut:
    """Open an account. Setting up who may be billed is a manager's job."""
    book = uow.accounts.book()
    if body.code in book.advertisers:
        raise ConflictError(f"advertiser {body.code} is already on file", code=body.code)
    book.add_advertiser(
        Advertiser.build(
            body.code,
            body.name,
            ",".join(body.categories),
            agency=body.agency,
            parent=body.parent,
            credit=body.credit,
        )
    )
    uow.accounts.save_book(book)
    try_record(
        uow.session,
        "advertiser.opened",
        caller.name,
        "advertiser",
        body.code,
        name=body.name,
        agency=body.agency,
    )
    return _advertiser_out(uow, book.advertiser(body.code))


@router.get("", response_model=list[AgencyOut])
def list_agencies(uow: UnitOfWork = Work, caller: Caller = Reader) -> list[AgencyOut]:
    book = uow.accounts.book()
    return [
        AgencyOut(
            code=agency.code,
            name=agency.name,
            rate=_rate(agency.rate),
            advertiser_liable=agency.advertiser_liable,
        )
        for agency in sorted(book.agencies.values(), key=lambda row: row.code)
    ]


@router.get("/{code}/conflicts", response_model=list[ConflictOut])
def read_conflicts(code: str, uow: UnitOfWork = Work, caller: Caller = Reader) -> list[ConflictOut]:
    """Everybody on file this advertiser may not sit beside, in code order."""
    book = uow.accounts.book()
    subject = book.advertiser(code)
    return [
        ConflictOut(
            code=other.code,
            name=other.name,
            through=sorted(str(shared) for shared in subject.shared_categories(other)),
        )
        for other in book.conflicting(code)
    ]


@router.put("/{code}/credit", response_model=AdvertiserOut)
def set_credit(
    code: str,
    standing: Credit = Query(description="open, cash in advance, or hold"),
    uow: UnitOfWork = Work,
    caller: Caller = Manager,
) -> AdvertiserOut:
    """Put an account on hold, or take it off. A manager's decision."""
    book = uow.accounts.book()
    was = book.advertiser(code)
    book.advertisers[code] = Advertiser(
        code=was.code,
        name=was.name,
        categories=was.categories,
        agency=was.agency,
        credit=standing,
        parent=was.parent,
    )
    uow.accounts.save_book(book)
    try_record(
        uow.session,
        "advertiser.credit",
        caller.name,
        "advertiser",
        code,
        was=was.credit.value,
        now=standing.value,
    )
    return _advertiser_out(uow, book.advertiser(code))


@router.post("/copy", response_model=CutOut, status_code=status.HTTP_201_CREATED)
def file_copy(body: CutIn, uow: UnitOfWork = Work, caller: Caller = Traffic) -> CutOut:
    """File a dubbed cut. Production's job, so it sits behind traffic."""
    cut = Cut(
        cart=body.cart,
        isci=body.isci,
        advertiser=body.advertiser,
        length=length_of(body.length),
        actual=Duration(body.actual_tenths),
        first_air=body.first_air,
        last_air=body.last_air,
    )
    uow.accounts.file_cut(cut)
    try_record(
        uow.session,
        "copy.filed",
        caller.name,
        "cart",
        cut.cart,
        advertiser=cut.advertiser,
        length=str(cut.length),
        runs=str(cut.actual),
    )
    return _cut_out(cut)


@router.get("/{code}/copy", response_model=list[CutOut])
def list_copy(code: str, uow: UnitOfWork = Work, caller: Caller = Reader) -> list[CutOut]:
    library = uow.accounts.library(code)
    return [_cut_out(cut) for cut in library.for_advertiser(code)]


def _cut_out(cut: Cut) -> CutOut:
    return CutOut(
        cart=cut.cart,
        isci=cut.isci,
        advertiser=cut.advertiser,
        length=str(cut.length),
        actual_tenths=cut.actual.tenths,
        first_air=cut.first_air,
        last_air=cut.last_air,
        killed=cut.killed,
    )


def _rate(rate: Decimal) -> str:
    """``0.1500`` off a Numeric(5,4) column reads back as ``0.15``."""
    return str(Decimal(rate).normalize())


def a_length(text: str) -> SpotLength:
    return length_of(text)
