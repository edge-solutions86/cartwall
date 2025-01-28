from decimal import Decimal

import pytest

from cartwall.domain.account import (
    AccountBook,
    AccountError,
    Advertiser,
    Agency,
    Category,
    Credit,
)


def test_a_conflict_code_is_a_dotted_path():
    code = Category("AUTO.DOMESTIC.FORD")
    assert code.parts == ("AUTO", "DOMESTIC", "FORD")
    assert code.depth == 3
    assert code.parent() == Category("AUTO.DOMESTIC")
    assert Category("AUTO").parent() is None
    assert str(code) == "AUTO.DOMESTIC.FORD"


def test_codes_that_are_not_codes():
    for bad in ("", "auto", "AUTO.", ".AUTO", "AUTO..FORD", "AUTO FORD"):
        with pytest.raises(AccountError, match="not a conflict code"):
            Category(bad)


def test_a_broad_code_catches_everything_under_it():
    auto = Category("AUTO")
    domestic = Category("AUTO.DOMESTIC")
    ford = Category("AUTO.DOMESTIC.FORD")
    assert auto.covers(ford)
    assert domestic.covers(ford)
    assert not ford.covers(auto)
    assert auto.conflicts_with(ford)
    assert ford.conflicts_with(auto)


def test_a_code_does_not_catch_a_sibling_by_accident():
    assert not Category("AUTO.DOMESTIC").conflicts_with(Category("AUTO.IMPORT"))
    assert not Category("AUTOMOTIVE").conflicts_with(Category("AUTO"))
    assert Category("AUTO").conflicts_with(Category("AUTO"))


def test_an_agency_keeps_a_commission_that_is_less_than_everything():
    assert Agency("MOP", "Mopar Media").rate == Decimal("0.15")
    assert Agency("HSE", "House", Decimal("0")).rate == Decimal("0")
    with pytest.raises(AccountError, match="not a commission"):
        Agency("BAD", "All of it", Decimal("1"))
    with pytest.raises(AccountError, match="has no name"):
        Agency("MOP", "")
    with pytest.raises(AccountError, match="not an account code"):
        Agency("m", "Mopar Media")


def test_advertisers_carry_more_than_one_conflict_code():
    grocer = Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS,PHARM")
    assert len(grocer.categories) == 3
    assert Category("FUEL.GAS") in grocer.categories
    assert Advertiser.build("SOLO", "Solo", "").categories == frozenset()


def test_two_advertisers_conflict_through_the_broadest_code_they_share():
    ford = Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD")
    chevy = Advertiser.build("DEALC", "Community Chevrolet", "AUTO.DOMESTIC.CHEV")
    toyota = Advertiser.build("DEALT", "Toyota of Waterloo", "AUTO.IMPORT.TOYO")
    couch = Advertiser.build("FURN", "Slumberland", "FURNITURE")
    assert not ford.conflicts_with(chevy)
    assert not ford.conflicts_with(toyota)
    assert not ford.conflicts_with(couch)

    broad = Advertiser.build("DEALG", "Dahl Auto Group", "AUTO")
    assert broad.conflicts_with(ford)
    assert broad.conflicts_with(toyota)
    assert broad.shared_categories(ford) == frozenset({Category("AUTO")})


def test_an_advertiser_always_conflicts_with_itself():
    ford = Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD")
    assert ford.conflicts_with(ford)
    assert Advertiser.build("SOLO", "Solo").conflicts_with(Advertiser.build("SOLO", "Solo"))


def test_an_advertiser_that_cannot_be_set_up():
    with pytest.raises(AccountError, match="has no name"):
        Advertiser.build("HYVEE", "")
    with pytest.raises(AccountError, match="not an account code"):
        Advertiser.build("hyvee", "Hy-Vee")
    with pytest.raises(AccountError, match="its own parent"):
        Advertiser.build("HYVEE", "Hy-Vee", parent="HYVEE")


@pytest.fixture
def book():
    book = AccountBook()
    book.add_agency(Agency("MOP", "Mopar Media"))
    book.add_agency(Agency("PAP", "Paperwork Only", Decimal("0.15"), advertiser_liable=True))
    book.add_advertiser(Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS"))
    book.add_advertiser(Advertiser.build("HYVEE1", "Hy-Vee Kimball", "GROCERY", parent="HYVEE"))
    book.add_advertiser(Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD", agency="MOP"))
    book.add_advertiser(Advertiser.build("DEALG", "Dahl Auto Group", "AUTO", agency="PAP"))
    book.add_advertiser(Advertiser.build("CASEY", "Caseys", "FUEL,GROCERY.CONV"))
    return book


def test_the_book_refuses_a_second_account_on_the_same_code(book):
    with pytest.raises(AccountError, match="already on file"):
        book.add_advertiser(Advertiser.build("HYVEE", "Hy-Vee again"))
    with pytest.raises(AccountError, match="already on file"):
        book.add_agency(Agency("MOP", "Mopar Media again"))


def test_the_book_refuses_a_pointer_at_something_that_is_not_there(book):
    with pytest.raises(AccountError, match="which is not on file"):
        book.add_advertiser(Advertiser.build("NEW", "New", agency="NOPE"))
    with pytest.raises(AccountError, match="which is not on file"):
        book.add_advertiser(Advertiser.build("NEW", "New", parent="NOPE"))


def test_looking_up_something_that_is_not_there(book):
    with pytest.raises(AccountError, match="advertiser NOPE is not on file"):
        book.advertiser("NOPE")
    with pytest.raises(AccountError, match="agency NOPE is not on file"):
        book.agency("NOPE")


def test_direct_business_pays_no_commission(book):
    assert book.commission_rate("HYVEE") == Decimal("0")
    assert book.commission_rate("DEALF") == Decimal("0.15")
    assert book.advertiser("HYVEE").is_direct
    assert not book.advertiser("DEALF").is_direct


def test_the_invoice_goes_to_the_agency_unless_the_advertiser_stays_liable(book):
    assert book.liable_party("HYVEE") == "HYVEE"
    assert book.liable_party("DEALF") == "MOP"
    assert book.liable_party("DEALG") == "DEALG"


def test_money_rolls_up_to_the_account_that_owns_the_chain(book):
    assert book.billing_group("HYVEE1") == "HYVEE"
    assert book.billing_group("HYVEE") == "HYVEE"
    assert book.billing_group("CASEY") == "CASEY"


def test_a_hold_stops_an_account_being_scheduled(book):
    book.add_advertiser(Advertiser.build("SLOW", "Slow Pay", credit=Credit.HOLD))
    book.add_advertiser(Advertiser.build("CASH", "Cash Up Front", credit=Credit.PREPAY))
    assert book.may_schedule("HYVEE")
    assert book.may_schedule("CASH")
    assert not book.may_schedule("SLOW")


def test_the_book_lists_who_an_advertiser_may_not_sit_beside(book):
    assert [other.code for other in book.conflicting("DEALF")] == ["DEALG"]
    assert [other.code for other in book.conflicting("HYVEE")] == ["CASEY", "HYVEE1"]
    assert [other.code for other in book.conflicting("CASEY")] == ["HYVEE", "HYVEE1"]


def test_a_grocery_conflict_and_a_fuel_conflict_are_both_reported(book):
    hyvee = book.advertiser("HYVEE")
    casey = book.advertiser("CASEY")
    shared = {str(code) for code in hyvee.shared_categories(casey)}
    assert shared == {"GROCERY", "FUEL"}
