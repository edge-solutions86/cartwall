package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.ChargeBook.lowestUnitCharge;
import static io.github.rkaushikdev.cartwall.domain.ChargeBook.owedBack;
import static io.github.rkaushikdev.cartwall.domain.Election.GENERAL_WINDOW;
import static io.github.rkaushikdev.cartwall.domain.Election.PRIMARY_WINDOW;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PoliticalTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime NOTICE = ClassOfTime.NOTICE;
    private static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;

    // The 2026 Iowa primary and general, which is what the house is looking at.
    private static final LocalDate PRIMARY_DAY = LocalDate.of(2026, 6, 2);
    private static final LocalDate GENERAL_DAY = LocalDate.of(2026, 11, 3);
    private static final LocalDate WINDOW_OPENS = LocalDate.of(2026, 9, 4);

    static Race aRace() {
        return new Race("Iowa House 63", Election.GENERAL, GENERAL_DAY);
    }

    /** The charge most of these tests measure, with the one thing each test cares about changed. */
    static final class ACharge {
        private String station = "KWLO";
        private String daypart = "AMD";
        private ClassOfTime grade = IMMEDIATE;
        private SpotLength length = THIRTY;
        private LocalDate day = LocalDate.of(2026, 10, 1);
        private String advertiser = "HYVEE";
        private final String rate;

        ACharge(String rate) {
            this.rate = rate;
        }

        ACharge station(String station) {
            this.station = station;
            return this;
        }

        ACharge daypart(String daypart) {
            this.daypart = daypart;
            return this;
        }

        ACharge grade(ClassOfTime grade) {
            this.grade = grade;
            return this;
        }

        ACharge length(SpotLength length) {
            this.length = length;
            return this;
        }

        ACharge day(LocalDate day) {
            this.day = day;
            return this;
        }

        ACharge advertiser(String advertiser) {
            this.advertiser = advertiser;
            return this;
        }

        Charge build() {
            return new Charge(station, daypart, grade, length, Money.parse(rate), day, advertiser);
        }
    }

    static ACharge aCharge(String rate) {
        return new ACharge(rate);
    }

    @Test
    void aPrimaryOpensAFortyFiveDayWindow() {
        Race race = new Race("Iowa House 63", Election.PRIMARY, PRIMARY_DAY);
        assertThat(race.kind().windowDays()).isEqualTo(PRIMARY_WINDOW);
        assertThat(race.windowOpens()).isEqualTo(LocalDate.of(2026, 4, 18));
        assertThat(race.inWindow(LocalDate.of(2026, 4, 18))).isTrue();
        assertThat(race.inWindow(LocalDate.of(2026, 4, 17))).isFalse();
    }

    @Test
    void aGeneralOpensASixtyDayWindow() {
        Race race = aRace();
        assertThat(race.kind().windowDays()).isEqualTo(GENERAL_WINDOW);
        assertThat(race.windowOpens()).isEqualTo(WINDOW_OPENS);
        assertThat(race.inWindow(WINDOW_OPENS)).isTrue();
        assertThat(race.inWindow(LocalDate.of(2026, 9, 3))).isFalse();
    }

    @Test
    void aRunoffGetsThePrimaryWindowAndASpecialGetsTheGeneralOne() {
        assertThat(Election.RUNOFF.windowDays()).isEqualTo(PRIMARY_WINDOW);
        assertThat(Election.SPECIAL.windowDays()).isEqualTo(GENERAL_WINDOW);
    }

    @Test
    void electionDayItselfIsInsideTheWindow() {
        Race race = aRace();
        assertThat(race.inWindow(GENERAL_DAY)).isTrue();
        assertThat(race.inWindow(LocalDate.of(2026, 11, 4))).isFalse();
        assertThat(race.daysLeft(LocalDate.of(2026, 10, 4))).isEqualTo(30);
    }

    @Test
    void aRaceNeedsAnOffice() {
        assertThatThrownBy(() -> new Race("", Election.GENERAL, GENERAL_DAY))
                .isInstanceOf(PoliticalError.class)
                .hasMessageContaining("needs an office");
    }

    @Test
    void aRaceReadsTheWayThePublicFilePrintsIt() {
        assertThat(aRace()).hasToString("Iowa House 63 general 11/03/2026");
    }

    @Test
    void aCandidacyNeedsACandidateAndACommittee() {
        assertThatThrownBy(() -> new Candidacy("", aRace(), "Friends of Nobody"))
                .isInstanceOf(PoliticalError.class)
                .hasMessageContaining("needs a candidate");
        assertThatThrownBy(() -> new Candidacy("R. Merrick", aRace(), ""))
                .isInstanceOf(PoliticalError.class)
                .hasMessageContaining("names no committee");
    }

    @Test
    void aCandidateInsideTheWindowIsEntitledToTheChargeRule() {
        Candidacy candidacy = new Candidacy("R. Merrick", aRace(), "Merrick for Iowa");
        assertThat(candidacy.entitledOn(LocalDate.of(2026, 10, 1), Use.CANDIDATE))
                .isTrue();
        assertThat(candidacy).hasToString("R. Merrick for Iowa House 63");
    }

    @Test
    void aCandidateOutsideTheWindowIsAnOrdinaryAdvertiser() {
        Candidacy candidacy = new Candidacy("R. Merrick", aRace(), "Merrick for Iowa");
        assertThat(candidacy.entitledOn(LocalDate.of(2026, 8, 1), Use.CANDIDATE))
                .isFalse();
    }

    @Test
    void issueAdvertisingIsNeverEntitledHoweverPoliticalItSounds() {
        Candidacy candidacy = new Candidacy("R. Merrick", aRace(), "Merrick for Iowa");
        assertThat(candidacy.entitledOn(LocalDate.of(2026, 10, 1), Use.ISSUE)).isFalse();
        assertThat(Use.ISSUE.entitled()).isFalse();
        assertThat(Use.CANDIDATE.entitled()).isTrue();
    }

    @Test
    void theLowestChargeIsWhatSomebodyPaidAndNotWhatTheCardSays() {
        List<Charge> charges = List.of(
                aCharge("36.00").advertiser("HYVEE").build(),
                aCharge("22.50").advertiser("SLUMB").build(),
                aCharge("30.00").advertiser("CASEY").build());
        assertThat(lowestUnitCharge(charges, "KWLO", "AMD", IMMEDIATE, THIRTY, WINDOW_OPENS, GENERAL_DAY))
                .contains(Money.parse("22.50"));
    }

    @Test
    void aClassNobodyBoughtHasNoLowestChargeRatherThanAZero() {
        List<Charge> charges = List.of(aCharge("36.00").grade(IMMEDIATE).build());
        assertThat(lowestUnitCharge(charges, "KWLO", "AMD", FIXED, THIRTY, WINDOW_OPENS, GENERAL_DAY))
                .isEmpty();
    }

    @Test
    void theComparisonIsByStationDaypartClassAndLength() {
        List<Charge> charges = List.of(
                aCharge("10.00").station("KCRR").build(),
                aCharge("11.00").daypart("MID").build(),
                aCharge("12.00").grade(FIXED).build(),
                aCharge("13.00").length(SIXTY).build(),
                aCharge("36.00").build());
        assertThat(lowestUnitCharge(charges, "KWLO", "AMD", IMMEDIATE, THIRTY, WINDOW_OPENS, GENERAL_DAY))
                .contains(Money.parse("36.00"));
    }

    @Test
    void aChargeOutsideTheWindowDoesNotCount() {
        List<Charge> charges = List.of(
                aCharge("10.00").day(LocalDate.of(2026, 8, 1)).build(),
                aCharge("36.00").day(LocalDate.of(2026, 10, 1)).build());
        assertThat(lowestUnitCharge(charges, "KWLO", "AMD", IMMEDIATE, THIRTY, WINDOW_OPENS, GENERAL_DAY))
                .contains(Money.parse("36.00"));
    }

    @Test
    void aWindowThatRunsBackwardsIsRefused() {
        assertThatThrownBy(
                        () -> lowestUnitCharge(List.of(), "KWLO", "AMD", IMMEDIATE, THIRTY, GENERAL_DAY, WINDOW_OPENS))
                .isInstanceOf(PoliticalError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void twoChargesAreComparableWhenTheyAreTheSameKindOfTime() {
        Charge one = aCharge("36.00").build();
        Charge same = aCharge("22.00").build();
        assertThat(one.comparableTo(same)).isTrue();
        assertThat(one.comparableTo(aCharge("36.00").daypart("MID").build())).isFalse();
        assertThat(one.comparableTo(aCharge("36.00").length(SIXTY).build())).isFalse();
        assertThat(one).hasToString("KWLO AMD immediately preemptible :30 $36.00");
    }

    @Test
    void aCandidateChargedAboveTheLowestIsOwedTheDifference() {
        assertThat(owedBack(Money.dollars(36), Optional.of(Money.parse("22.50"))))
                .isEqualTo(Money.parse("13.50"));
    }

    @Test
    void aCandidateChargedAtOrBelowTheLowestIsOwedNothing() {
        assertThat(owedBack(Money.parse("22.50"), Optional.of(Money.parse("22.50"))))
                .isEqualTo(new Money(0));
        assertThat(owedBack(Money.parse("20.00"), Optional.of(Money.parse("22.50"))))
                .isEqualTo(new Money(0));
    }

    @Test
    void nothingIsOwedWhenThereIsNoLowestChargeToMeasureAgainst() {
        assertThat(owedBack(Money.dollars(36), Optional.empty())).isEqualTo(new Money(0));
    }

    @Test
    void theBookAnswersForAWholeRaceWithoutBeingToldTheDates() {
        ChargeBook book = new ChargeBook(
                List.of(aCharge("36.00").build(), aCharge("22.50").build()));
        assertThat(book.forRace(aRace(), "KWLO", "AMD", IMMEDIATE, THIRTY)).contains(Money.parse("22.50"));
        assertThat(book.size()).isEqualTo(2);
    }

    @Test
    void aChargeAddedLaterMovesTheAnswer() {
        ChargeBook book = new ChargeBook(List.of(aCharge("36.00").build()));
        Race race = aRace();
        assertThat(book.forRace(race, "KWLO", "AMD", IMMEDIATE, THIRTY)).contains(Money.dollars(36));
        book.add(aCharge("18.00").advertiser("SLUMB").build());
        assertThat(book.forRace(race, "KWLO", "AMD", IMMEDIATE, THIRTY)).contains(Money.dollars(18));
    }

    @Test
    void aBuyerWhoNamesNoClassIsShownTheCheapestOneThatTraded() {
        ChargeBook book = new ChargeBook(List.of(
                aCharge("60.00").grade(FIXED).build(),
                aCharge("48.00").grade(NOTICE).build(),
                aCharge("36.00").grade(IMMEDIATE).build()));
        assertThat(book.cheapestClass("KWLO", "AMD", THIRTY, WINDOW_OPENS, GENERAL_DAY))
                .contains(new ChargeBook.Cheapest(IMMEDIATE, Money.dollars(36)));
    }

    @Test
    void theDearestClassWinsATieBecauseItIsTheBetterPromise() {
        ChargeBook book = new ChargeBook(List.of(
                aCharge("36.00").grade(FIXED).build(),
                aCharge("36.00").grade(IMMEDIATE).build()));
        ChargeBook.Cheapest found = book.cheapestClass("KWLO", "AMD", THIRTY, WINDOW_OPENS, GENERAL_DAY)
                .orElseThrow();
        assertThat(found.grade()).isSameAs(FIXED);
        assertThat(found.lowest()).isEqualTo(Money.dollars(36));
    }

    @Test
    void aStationThatTradedNothingHasNoCheapestClass() {
        assertThat(new ChargeBook(List.of()).cheapestClass("KWLO", "AMD", THIRTY, WINDOW_OPENS, GENERAL_DAY))
                .isEmpty();
    }
}
