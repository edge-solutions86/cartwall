package io.github.rkaushikdev.cartwall.persistence;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.models.DebtRow;
import io.github.rkaushikdev.cartwall.persistence.models.OfferRow;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import io.github.rkaushikdev.cartwall.services.Cause;
import io.github.rkaushikdev.cartwall.services.Debt;
import io.github.rkaushikdev.cartwall.services.Ledger;
import io.github.rkaushikdev.cartwall.services.Makegood;
import io.github.rkaushikdev.cartwall.services.MakegoodError;
import io.github.rkaushikdev.cartwall.services.NotOwed;
import io.github.rkaushikdev.cartwall.services.Offer;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MakegoodstoreTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final LocalDate LATER = LocalDate.of(2025, 8, 18);

    private Database engine;

    private static Line aLine(int number, String station) {
        return new Line(
                number,
                station,
                "AMD",
                ClassOfTime.FIXED,
                THIRTY,
                Money.dollars(60),
                Flight.build(MONDAY, LocalDate.of(2025, 8, 29), "M-F", 12));
    }

    /** A debt points at a real order, so one has to be on file first. */
    @BeforeEach
    void putAnOrderOnFile() {
        engine = TestDatabase.fresh();
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            uow.orders()
                    .save(new Order(
                            "25-1044",
                            "HYVEE",
                            LocalDate.of(2025, 7, 21),
                            "DHOLM",
                            List.of(aLine(1, "KWLO"), aLine(2, "KWLO"), aLine(3, "KCRR"))));
            uow.commit();
        }
    }

    private static Debt aDebt(int line, LocalDate day, Cause cause, String rate) {
        return new Debt(
                "25-1044", line, "HYVEE", "KWLO", "AMD", ClassOfTime.FIXED, THIRTY, Money.parse(rate), day, cause);
    }

    private static Debt aDebt(int line, LocalDate day) {
        return aDebt(line, day, Cause.DID_NOT_RUN, "60.00");
    }

    private static Debt aDebt(int line) {
        return aDebt(line, MONDAY);
    }

    private static Debt aDebt(Cause cause) {
        return aDebt(1, MONDAY, cause, "60.00");
    }

    private static Debt aDebt(int line, String rate) {
        return aDebt(line, MONDAY, Cause.DID_NOT_RUN, rate);
    }

    private static Debt aDebt() {
        return aDebt(1);
    }

    private static List<Debt> four() {
        return IntStream.range(0, 4).mapToObj(n -> aDebt(Cause.NEVER_SCHEDULED)).toList();
    }

    @Test
    void aRaisedDebtReadsBackTheWayItWentIn() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            uow.commit();
        }
        List<Debt> found;
        try (UnitOfWork uow = engine.unitOfWork()) {
            found = uow.makegoods().ledger().debts();
        }
        assertThat(found).containsExactly(aDebt());
    }

    @Test
    void theRateSurvivesTheRoundTripAsCents() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            DebtRow row = uow.makegoods().raiseDebt(aDebt(1, "62.50"));
            assertThat(row.getRate()).isEqualTo(new Money(6250));
            assertThat(MakegoodRepository.debtFrom(row).rate()).isEqualTo(Money.parse("62.50"));
        }
    }

    @Test
    void raisingTheSameDebtTwiceDoesNotOweItTwice() {
        DebtRow first;
        DebtRow again;
        try (UnitOfWork uow = engine.unitOfWork()) {
            first = uow.makegoods().raiseDebt(aDebt());
            again = uow.makegoods().raiseDebt(aDebt());
            uow.commit();
        }
        assertThat(first.getId()).isEqualTo(again.getId());
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(1);
        }
    }

    @Test
    void theSameSpotOwedForTwoReasonsIsTwoDebts() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(Cause.DID_NOT_RUN));
            uow.makegoods().raiseDebt(aDebt(Cause.BUMPED));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(2);
            assertThat(uow.makegoods().ledger().owedByLine()).isEqualTo(Map.of(new LineKey("25-1044", 1), 2));
        }
    }

    @Test
    void aBatchAnswersHowManyWereNew() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().raiseAll(List.of(aDebt(1), aDebt(2)))).isEqualTo(2);
            assertThat(uow.makegoods().raiseAll(List.of(aDebt(2), aDebt(3)))).isEqualTo(1);
            uow.commit();
        }
    }

    @Test
    void anOfferAgainstADebtNobodyRaisedIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> uow.makegoods().propose(aDebt(), FRIDAY, "AMD", Money.parse("60.00")))
                    .isInstanceOf(NotOwed.class)
                    .hasMessageContaining("nothing is owed");
        }
    }

    @Test
    void anOfferReadsBackAgainstItsDebt() {
        OfferRow row;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            row = uow.makegoods().propose(aDebt(), FRIDAY, "AAA", Money.parse("60.00"));
            uow.commit();
        }
        Makegood made;
        try (UnitOfWork uow = engine.unitOfWork()) {
            made = MakegoodRepository.offerFrom(uow.makegoods().openOffers().get(0));
        }
        assertThat(made.debt()).isEqualTo(aDebt());
        assertThat(made.daypart()).isEqualTo("AAA");
        assertThat(made.state()).isSameAs(Offer.OPEN);
        assertThat(row.isShort()).isFalse();
    }

    @Test
    void anOfferWorthLessThanTheSpotIsMarkedShort() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1, "60.00"));
            OfferRow row = uow.makegoods().propose(aDebt(1, "60.00"), FRIDAY, "MID", Money.parse("18.00"));
            assertThat(row.isShort()).isTrue();
            assertThat(MakegoodRepository.offerFrom(row).shortfall()).isEqualTo(Money.parse("42.00"));
        }
    }

    @Test
    void aSecondOfferWhileOneIsOpenIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            uow.makegoods().propose(aDebt(), FRIDAY, "AAA", Money.parse("60.00"));
            assertThatThrownBy(() -> uow.makegoods().propose(aDebt(), LATER, "AMD", Money.parse("60.00")))
                    .isInstanceOf(MakegoodError.class)
                    .hasMessageContaining("already has an offer open");
        }
    }

    @Test
    void aRefusedOfferLeavesTheDebtOpenForAnother() {
        OfferRow second;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            OfferRow first = uow.makegoods().propose(aDebt(), FRIDAY, "MID", Money.parse("18.00"));
            uow.makegoods().settle(first.getId(), Offer.REFUSED);
            second = uow.makegoods().propose(aDebt(), LATER, "AMD", Money.parse("60.00"));
            uow.commit();
        }
        assertThat(second.getState()).isSameAs(Offer.OPEN);
        Ledger ledger;
        try (UnitOfWork uow = engine.unitOfWork()) {
            ledger = uow.makegoods().ledger();
        }
        assertThat(ledger.offers()).hasSize(2);
        assertThat(ledger.stillOwed()).containsExactly(aDebt());
    }

    @Test
    void anAcceptedOfferClosesTheDebt() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            OfferRow made = uow.makegoods().propose(aDebt(), FRIDAY, "AMD", Money.parse("60.00"));
            uow.makegoods().settle(made.getId(), Offer.ACCEPTED);
            uow.commit();
        }
        Ledger ledger;
        try (UnitOfWork uow = engine.unitOfWork()) {
            ledger = uow.makegoods().ledger();
        }
        assertThat(ledger.stillOwed()).isEmpty();
        assertThat(ledger.owedValue()).isEqualTo(new Money(0));
    }

    @Test
    void settlingTheSameOfferTwiceIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            OfferRow made = uow.makegoods().propose(aDebt(), FRIDAY, "AMD", Money.parse("60.00"));
            uow.makegoods().settle(made.getId(), Offer.ACCEPTED);
            assertThatThrownBy(() -> uow.makegoods().settle(made.getId(), Offer.REFUSED))
                    .isInstanceOf(MakegoodError.class)
                    .hasMessageContaining("already accepted");
        }
    }

    @Test
    void settlingAnOfferAsOpenIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            OfferRow made = uow.makegoods().propose(aDebt(), FRIDAY, "AMD", Money.parse("60.00"));
            assertThatThrownBy(() -> uow.makegoods().settle(made.getId(), Offer.OPEN))
                    .isInstanceOf(MakegoodError.class)
                    .hasMessageContaining("cannot be settled as open");
        }
    }

    @Test
    void settlingAnOfferNobodyMadeIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> uow.makegoods().settle(404, Offer.ACCEPTED))
                    .isInstanceOf(NotOwed.class)
                    .hasMessageContaining("there is no offer 404");
        }
    }

    @Test
    void anOfferWhoseAirDateHasGoneByIsWithdrawn() {
        List<OfferRow> withdrawn;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1));
            uow.makegoods().raiseDebt(aDebt(2));
            uow.makegoods().propose(aDebt(1), FRIDAY, "AMD", Money.parse("60.00"));
            uow.makegoods().propose(aDebt(2), LATER, "AMD", Money.parse("60.00"));
            withdrawn = uow.makegoods().expireBefore(LocalDate.of(2025, 8, 11));
            uow.commit();
        }
        assertThat(withdrawn).extracting(OfferRow::getDay).containsExactly(FRIDAY);
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().openOffers())
                    .extracting(OfferRow::getDay)
                    .containsExactly(LATER);
        }
    }

    @Test
    void expiringLeavesTheDebtOwed() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt());
            uow.makegoods().propose(aDebt(), FRIDAY, "AMD", Money.parse("60.00"));
            uow.makegoods().expireBefore(LocalDate.of(2025, 8, 11));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().ledger().stillOwed()).containsExactly(aDebt());
        }
    }

    @Test
    void theLedgerCanBeReadForOneStation() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1));
            uow.makegoods()
                    .raiseDebt(new Debt(
                            "25-1044",
                            2,
                            "HYVEE",
                            "KCRR",
                            "PMD",
                            ClassOfTime.FIXED,
                            THIRTY,
                            Money.parse("40.00"),
                            MONDAY,
                            Cause.DID_NOT_RUN));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods()
                            .ledger(Optional.empty(), Optional.of("KWLO"))
                            .debts())
                    .extracting(Debt::line)
                    .containsExactly(1);
            assertThat(uow.makegoods()
                            .ledger(Optional.empty(), Optional.of("KCRR"))
                            .debts())
                    .extracting(Debt::line)
                    .containsExactly(2);
            assertThat(uow.makegoods().ledger().debts()).hasSize(2);
        }
    }

    @Test
    void theLedgerComesBackOldestFirst() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(2, FRIDAY));
            uow.makegoods().raiseDebt(aDebt(1, MONDAY));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().ledger().debts()).extracting(Debt::day).containsExactly(MONDAY, FRIDAY);
        }
    }

    @Test
    void whatWasOwedInASpanReadsBackByDay() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1, MONDAY));
            uow.makegoods().raiseDebt(aDebt(2, FRIDAY));
            uow.makegoods().raiseDebt(aDebt(3, LATER));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            // Rows, not domain objects, so they are read while the unit of work is open.
            assertThat(uow.makegoods().owedBetween(MONDAY, FRIDAY))
                    .extracting(DebtRow::getLine)
                    .containsExactly(1, 2);
            assertThat(uow.makegoods().owedBetween(MONDAY, LATER))
                    .extracting(DebtRow::getLine)
                    .containsExactly(1, 2, 3);
        }
    }

    @Test
    void theOpenDebtIsWorthWhatItWasSoldAt() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1, "60.00"));
            uow.makegoods().raiseDebt(aDebt(2, "45.00"));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().ledger().owedValue()).isEqualTo(Money.parse("105.00"));
        }
    }

    @Test
    void theCausesAreCountedWorstFirst() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1, MONDAY, Cause.DID_NOT_RUN, "60.00"));
            uow.makegoods().raiseDebt(aDebt(2, MONDAY, Cause.DID_NOT_RUN, "60.00"));
            uow.makegoods().raiseDebt(aDebt(3, MONDAY, Cause.BUMPED, "60.00"));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().ledger().byCause()).isEqualTo(Map.of("did not run", 2, "bumped", 1));
        }
    }

    @Test
    void openOffersCanBeReadForOneOrder() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseDebt(aDebt(1));
            uow.makegoods().propose(aDebt(1), FRIDAY, "AMD", Money.parse("60.00"));
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().openOffers(Optional.of("25-1044"))).hasSize(1);
            assertThat(uow.makegoods().openOffers(Optional.of("25-9999"))).isEmpty();
        }
    }

    /** A line short four spots in a week is owed four, not one. */
    @Test
    void fourIdenticalDebtsStayFour() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().raiseAll(four())).isEqualTo(4);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(4);
            assertThat(uow.makegoods().ledger().owedByLine()).isEqualTo(Map.of(new LineKey("25-1044", 1), 4));
        }
    }

    @Test
    void gatheringTheSameFourAgainChangesNothing() {
        List<Debt> four = four();
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseAll(four);
            assertThat(uow.makegoods().raiseAll(four)).isZero();
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(4);
        }
    }

    /** Four owed last night and two tonight does not cancel the other two. */
    @Test
    void aShorterGatherLeavesTheRestOnFile() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseAll(four());
            assertThat(uow.makegoods().raiseAll(Collections.nCopies(2, aDebt(Cause.NEVER_SCHEDULED))))
                    .isZero();
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(4);
        }
    }

    @Test
    void eachOfTheFourIsOfferedAgainstOnItsOwn() {
        OfferRow first;
        OfferRow second;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseAll(four());
            Debt debt = aDebt(Cause.NEVER_SCHEDULED);
            first = uow.makegoods().propose(debt, FRIDAY, "AMD", Money.parse("60.00"), 1);
            second = uow.makegoods().propose(debt, LATER, "AMD", Money.parse("60.00"), 2);
            uow.commit();
        }
        assertThat(first.getDebt().getId()).isNotEqualTo(second.getDebt().getId());
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().openOffers()).hasSize(2);
            assertThat(uow.makegoods().ledger().stillOwed()).hasSize(4);
        }
    }

    /** The collapse this guards against was real, and it read as zero owed. */
    @Test
    void oneAcceptedMakegoodClosesOneOfFourAndNotAllFour() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.makegoods().raiseAll(four());
            OfferRow made = uow.makegoods().propose(aDebt(Cause.NEVER_SCHEDULED), FRIDAY, "AMD", Money.parse("60.00"));
            uow.makegoods().settle(made.getId(), Offer.ACCEPTED);
            uow.commit();
        }
        Ledger ledger;
        try (UnitOfWork uow = engine.unitOfWork()) {
            ledger = uow.makegoods().ledger();
        }
        assertThat(ledger.stillOwed()).hasSize(3);
        assertThat(ledger.owedValue()).isEqualTo(Money.parse("180.00"));
        assertThat(ledger.owedByLine()).isEqualTo(Map.of(new LineKey("25-1044", 1), 3));
    }
}
