from datetime import date

import pytest

from cartwall.airtime import Duration
from cartwall.domain.spot import (
    SIXTY,
    THIRTY,
    Cut,
    CutLibrary,
    SpotError,
    SpotLength,
)

JANUARY = date(2025, 1, 6)
MARCH = date(2025, 3, 3)


def a_cut(cart="C1204", length=THIRTY, actual=None, **kw):
    return Cut(
        cart=cart,
        isci=kw.pop("isci", "HYVE2501"),
        advertiser=kw.pop("advertiser", "HYVEE"),
        length=length,
        actual=actual if actual is not None else length.air,
        first_air=kw.pop("first_air", JANUARY),
        **kw,
    )


def test_only_the_lengths_on_the_card_can_be_sold():
    assert SpotLength(30).seconds == 30
    assert SpotLength.parse(":60") == SIXTY
    assert SpotLength.parse("1:00") == SIXTY
    for bad in (25, 35, 0, 300):
        with pytest.raises(SpotError, match="is not sold here"):
            SpotLength(bad)


def test_a_unit_is_a_thirty_and_anything_shorter_still_costs_one():
    assert SpotLength(10).units == 1
    assert SpotLength(15).units == 1
    assert SpotLength(30).units == 1
    assert SpotLength(45).units == 2
    assert SpotLength(60).units == 2
    assert SpotLength(120).units == 4


def test_lengths_print_and_sort_the_way_the_log_shows_them():
    assert str(THIRTY) == ":30"
    assert str(SIXTY) == "1:00"
    assert str(SpotLength(90)) == "1:30"
    assert sorted([SIXTY, THIRTY, SpotLength(10)])[0] == SpotLength(10)


def test_a_cut_may_run_half_a_second_either_side_of_its_sold_length():
    assert THIRTY.fits(Duration(300))
    assert THIRTY.fits(Duration(295))
    assert THIRTY.fits(Duration(305))
    assert not THIRTY.fits(Duration(294))
    assert not THIRTY.fits(Duration(306))


def test_a_cut_that_runs_long_is_refused_when_it_is_filed():
    assert a_cut(actual=Duration(304)).is_long
    assert a_cut(actual=Duration(296)).is_short
    with pytest.raises(SpotError, match="runs :32 against a :30"):
        a_cut(actual=Duration(320))


def test_cart_numbers_and_isci_codes_have_a_shape():
    assert a_cut(cart="C1204").cart == "C1204"
    assert a_cut(cart="H900").cart == "H900"
    for bad in ("1204", "CC1204", "C12", "c1204"):
        with pytest.raises(SpotError, match="not a cart number"):
            a_cut(cart=bad)
    for bad in ("HYV2501", "hyve2501", "HYVE", "HYVE250120255"):
        with pytest.raises(SpotError, match="not an ISCI code"):
            a_cut(isci=bad)


def test_a_cut_has_to_belong_to_somebody():
    with pytest.raises(SpotError, match="names no advertiser"):
        a_cut(advertiser="")


def test_a_cut_cannot_be_killed_before_it_starts():
    with pytest.raises(SpotError, match="killed before it starts"):
        a_cut(first_air=MARCH, last_air=JANUARY)


def test_a_cut_runs_between_its_dates_and_not_outside_them():
    cut = a_cut(first_air=JANUARY, last_air=date(2025, 1, 31))
    assert not cut.runs_on(date(2025, 1, 5))
    assert cut.runs_on(JANUARY)
    assert cut.runs_on(date(2025, 1, 31))
    assert not cut.runs_on(date(2025, 2, 1))


def test_a_cut_with_no_kill_date_runs_until_somebody_stops_it():
    cut = a_cut()
    assert cut.runs_on(date(2030, 1, 1))


def test_filing_the_same_cart_twice_is_refused():
    library = CutLibrary()
    library.file(a_cut())
    with pytest.raises(SpotError, match="already filed"):
        library.file(a_cut())
    assert len(library) == 1
    assert "C1204" in library


def test_a_redub_replaces_the_cart_in_place():
    library = CutLibrary()
    library.file(a_cut(actual=Duration(299)))
    library.refile(a_cut(actual=Duration(301)))
    assert library.cut("C1204").actual == Duration(301)
    assert len(library) == 1
    with pytest.raises(SpotError, match="never been filed"):
        library.refile(a_cut(cart="C9999"))


def test_asking_for_a_cart_nobody_dubbed():
    with pytest.raises(SpotError, match="not in the library"):
        CutLibrary().cut("C1204")


def test_killing_a_cut_leaves_it_on_file_and_stops_it_running():
    library = CutLibrary()
    library.file(a_cut())
    killed = library.kill("C1204", date(2025, 2, 28))
    assert killed.killed
    assert killed.last_air == date(2025, 2, 28)
    assert not killed.runs_on(date(2025, 3, 1))
    # It still ran in February, and the affidavit has to be able to say so.
    assert killed.runs_on(date(2025, 2, 1))
    assert library.cut("C1204").killed
    assert len(library) == 1


def test_the_library_lists_what_can_run_on_a_day_in_cart_order():
    library = CutLibrary()
    library.file(a_cut(cart="C1300", advertiser="FAREWAY", isci="FARE2502"))
    library.file(a_cut(cart="C1204"))
    library.file(a_cut(cart="C1205", first_air=MARCH))
    assert [cut.cart for cut in library.running(JANUARY)] == ["C1204", "C1300"]
    assert [cut.cart for cut in library.running(MARCH)] == ["C1204", "C1205", "C1300"]
    assert [cut.cart for cut in library.running(JANUARY, "HYVEE")] == ["C1204"]


def test_the_library_lists_everything_one_advertiser_ever_had():
    library = CutLibrary()
    library.file(a_cut(cart="C1204"))
    library.file(a_cut(cart="C1205"))
    library.kill("C1205", date(2025, 1, 20))
    library.file(a_cut(cart="C1300", advertiser="FAREWAY", isci="FARE2502"))
    assert [cut.cart for cut in library.for_advertiser("HYVEE")] == ["C1204", "C1205"]
    assert library.for_advertiser("NOBODY") == []


def test_which_lengths_an_advertiser_can_actually_be_scheduled_in():
    library = CutLibrary()
    library.file(a_cut(cart="C1204", length=THIRTY))
    library.file(a_cut(cart="C1205", length=SIXTY, actual=Duration(600)))
    library.file(a_cut(cart="C1206", length=SpotLength(15), actual=Duration(150)))
    library.kill("C1206", date(2025, 1, 10))
    assert library.lengths_on_hand("HYVEE", MARCH) == {THIRTY, SIXTY}
    assert library.lengths_on_hand("HYVEE", JANUARY) == {THIRTY, SIXTY, SpotLength(15)}
    assert library.lengths_on_hand("NOBODY", MARCH) == set()
