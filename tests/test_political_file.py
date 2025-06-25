from datetime import date

import pytest

from cartwall.domain.political import Candidacy, Election, Race, Use
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.money import Money
from cartwall.reports.political_file import (
    Aired,
    Bought,
    Entry,
    FileError,
    PoliticalFile,
    render,
)

IMMEDIATE = ClassOfTime.IMMEDIATE
FIXED = ClassOfTime.FIXED

RACE = Race("Iowa House 63", Election.GENERAL, date(2026, 11, 3))
ASKED = date(2026, 9, 8)


def a_candidacy(candidate="R. Merrick", authorised="Merrick for Iowa"):
    return Candidacy(
        candidate=candidate,
        race=RACE,
        committee="Merrick for Iowa",
        authorised_by=authorised,
    )


def a_bought(spots=12, rate="18.00", grade=IMMEDIATE, daypart="AMD"):
    return Bought(
        station="KWLO",
        daypart=daypart,
        grade=grade,
        length=THIRTY,
        rate=Money.parse(rate),
        spots=spots,
    )


def an_entry(accepted=True, bought=None, **kw):
    return Entry(
        requested=kw.pop("requested", ASKED),
        candidacy=kw.pop("candidacy", a_candidacy()),
        use=kw.pop("use", Use.CANDIDATE),
        accepted=accepted,
        order=kw.pop("order", "25-1044" if accepted else ""),
        disposition=kw.pop("disposition", ""),
        bought=bought if bought is not None else ([a_bought()] if accepted else []),
    )


def test_an_accepted_entry_adds_up_what_was_bought():
    entry = an_entry(bought=[a_bought(12, "18.00"), a_bought(6, "12.00", daypart="MID")])
    assert entry.spots == 18
    assert entry.value == Money.dollars(12 * 18 + 6 * 12)
    assert entry.is_complete


def test_an_entry_reads_as_a_sentence():
    assert str(an_entry()) == ("09/08/2026 R. Merrick for Iowa House 63 accepted 12 spots $216.00")
    rejected = an_entry(accepted=False, disposition="no inventory in drive")
    assert "rejected 0 spots $0.00" in str(rejected)


def test_a_candidate_entry_without_the_disclaimer_is_not_filed():
    entry = an_entry(candidacy=a_candidacy(authorised=""))
    assert not entry.is_complete
    assert "the authorised by disclaimer" in entry.missing()
    with pytest.raises(FileError, match="missing the authorised by disclaimer"):
        render(entry)


def test_an_issue_entry_does_not_need_the_candidate_disclaimer():
    entry = an_entry(candidacy=a_candidacy(authorised=""), use=Use.ISSUE)
    assert entry.is_complete


def test_an_accepted_entry_needs_an_order_and_what_was_bought():
    assert "the order number" in an_entry(order="").missing()
    assert "what was bought" in an_entry(bought=[]).missing()


def test_a_rejected_entry_needs_a_reason():
    entry = an_entry(accepted=False)
    assert "why it was turned down" in entry.missing()
    assert an_entry(accepted=False, disposition="sold out").is_complete


def test_a_rejected_entry_needs_neither_an_order_nor_a_purchase():
    entry = an_entry(accepted=False, disposition="sold out")
    assert entry.missing() == []
    assert entry.spots == 0
    assert entry.value == Money(0)


def test_a_rendered_entry_carries_everything_the_rule_asks_for():
    entry = an_entry()
    entry.note_air(Aired(date(2026, 10, 1), "7:10:04a", "KWLO"), date(2026, 10, 2))
    text = render(entry)
    assert "CANDIDATE    R. Merrick" in text
    assert "OFFICE       Iowa House 63" in text
    assert "ELECTION     general 11/03/2026" in text
    assert "COMMITTEE    Merrick for Iowa" in text
    assert "USE          candidate" in text
    assert "DISPOSITION  accepted" in text
    assert "ORDER        25-1044" in text
    assert "AUTHORISED   Merrick for Iowa" in text
    assert "BOUGHT       KWLO AMD immediately preemptible :30 12 at $18.00" in text
    assert "TOTAL        $216.00" in text
    assert "AIRED        10/01/2026 7:10:04a KWLO" in text


def test_a_rejected_entry_renders_the_reason_and_nothing_about_money():
    text = render(an_entry(accepted=False, disposition="no drive time left"))
    assert "DISPOSITION  rejected" in text
    assert "NOTE         no drive time left" in text
    assert "TOTAL" not in text
    assert "BOUGHT" not in text


def test_air_times_are_added_as_the_as_run_comes_in():
    entry = an_entry()
    assert entry.aired == []
    assert entry.touched is None
    entry.note_air(Aired(date(2026, 10, 1), "7:10:04a", "KWLO"), date(2026, 10, 2))
    entry.note_air(Aired(date(2026, 10, 2), "9:38:00a", "KWLO"), date(2026, 10, 3))
    assert len(entry.aired) == 2
    assert entry.touched == date(2026, 10, 3)


def test_the_file_keeps_entries_in_the_order_they_were_requested():
    file = PoliticalFile()
    file.file(an_entry(requested=date(2026, 9, 20)))
    file.file(an_entry(requested=ASKED))
    file.file(an_entry(requested=ASKED, candidacy=a_candidacy("A. Hollis")))
    assert [(row.requested, row.candidacy.candidate) for row in file.entries] == [
        (ASKED, "A. Hollis"),
        (ASKED, "R. Merrick"),
        (date(2026, 9, 20), "R. Merrick"),
    ]
    assert len(file) == 3


def test_the_file_finds_a_candidate_and_a_race():
    file = PoliticalFile()
    file.file(an_entry())
    file.file(an_entry(candidacy=a_candidacy("A. Hollis")))
    assert len(file.for_candidate("R. Merrick")) == 1
    assert len(file.for_race("Iowa House 63")) == 2
    assert file.for_candidate("NOBODY") == []


def test_the_file_lists_what_somebody_has_to_chase():
    file = PoliticalFile()
    file.file(an_entry())
    file.file(an_entry(order="", candidacy=a_candidacy("A. Hollis")))
    incomplete = file.incomplete()
    assert [row.candidacy.candidate for row in incomplete] == ["A. Hollis"]


def test_the_file_lists_what_was_turned_down():
    file = PoliticalFile()
    file.file(an_entry())
    file.file(an_entry(accepted=False, disposition="sold out"))
    assert len(file.rejected()) == 1


def test_the_file_totals_spend_by_candidate_biggest_first():
    file = PoliticalFile()
    file.file(an_entry(bought=[a_bought(12, "18.00")]))
    file.file(
        an_entry(
            candidacy=a_candidacy("A. Hollis"),
            bought=[a_bought(30, "18.00")],
        )
    )
    file.file(an_entry(accepted=False, disposition="sold out"))
    tally = file.spend_by_candidate()
    assert list(tally) == ["A. Hollis", "R. Merrick"]
    assert tally["A. Hollis"] == Money.dollars(540)


def test_rendering_the_whole_file_leaves_the_incomplete_entries_out():
    file = PoliticalFile()
    file.file(an_entry())
    file.file(an_entry(order="", candidacy=a_candidacy("A. Hollis")))
    text = file.render_all()
    assert "R. Merrick" in text
    assert "A. Hollis" not in text
