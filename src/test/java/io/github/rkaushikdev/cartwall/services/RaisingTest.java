package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.AiredDays.aDay;
import static io.github.rkaushikdev.cartwall.services.AiredDays.ran;
import static io.github.rkaushikdev.cartwall.services.Raising.causes;
import static io.github.rkaushikdev.cartwall.services.Raising.owedValue;
import static io.github.rkaushikdev.cartwall.services.Raising.worstLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.OfferRow;
import io.github.rkaushikdev.cartwall.services.AiredDays.Spot;
import io.github.rkaushikdev.cartwall.services.Raising.Raised;
import io.github.rkaushikdev.cartwall.services.Raising.WorstLine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RaisingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final LocalDate SUNDAY = LocalDate.of(2025, 8, 10);

    private Database engine;

    @BeforeEach
    void putTheStationOnFile() {
        engine = AiredDays.aStationOnFile();
    }

    private static Order anOrder(int perWeek, int lines) {
        return new Order(
                        "25-1044",
                        "HYVEE",
                        LocalDate.of(2025, 7, 21),
                        "DHOLM",
                        IntStream.rangeClosed(1, lines)
                                .mapToObj(one -> new Line(
                                        one,
                                        "KWLO",
                                        "AMD",
                                        FIXED,
                                        THIRTY,
                                        Money.parse("60.00"),
                                        Flight.build(MONDAY, FRIDAY, "M-F", perWeek)))
                                .toList())
                .confirmed();
    }

    /** A day where every spot on the log was skipped by the automation. */
    private static void aSkippedDay(UnitOfWork uow, int lines) {
        uow.orders().save(anOrder(1, lines));
        List<Spot> spots = IntStream.range(0, lines)
                .mapToObj(one -> new Spot(7 + one * 2, 2, "25-1044", one + 1))
                .toList();
        aDay(uow, spots, List.of(), MONDAY, true);
    }

    private static void aSkippedDay(UnitOfWork uow) {
        aSkippedDay(uow, 1);
    }

    private static Raised raiseOwing(UnitOfWork uow, LocalDate first, LocalDate last, boolean scheduled) {
        return HouseLog.quietly(() -> Raising.raiseOwing(uow, first, last, scheduled, Optional.empty()));
    }

    @Test
    void aCleanNightRaisesNothing() {
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1, 1));
            aDay(uow, List.of(new Spot(7, 2, "25-1044", 1)), List.of(ran("07:10:00")), MONDAY, true);
            report = raiseOwing(uow, MONDAY, SUNDAY, false);
        }
        assertThat(report.found()).isZero();
        assertThat(report.added()).isZero();
        assertThat(report.changed()).isFalse();
    }

    @Test
    void aSkippedSpotIsPutOnFile() {
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1, 1));
            aDay(
                    uow,
                    List.of(new Spot(7, 2, "25-1044", 1)),
                    List.of(ran("07:10:00", "skip", "25-1044-1")),
                    MONDAY,
                    true);
            report = raiseOwing(uow, MONDAY, SUNDAY, false);
            uow.commit();
        }
        assertThat(report.added()).isEqualTo(1);
        assertThat(report.changed()).isTrue();
        List<Debt> owed;
        try (UnitOfWork uow = engine.unitOfWork()) {
            owed = uow.makegoods().ledger().stillOwed();
        }
        assertThat(owed).hasSize(1);
        assertThat(owed.get(0).order()).isEqualTo("25-1044");
        assertThat(owed.get(0).rate()).isEqualTo(Money.dollars(60));
    }

    @Test
    void runningTheSameNightTwiceDoesNotOweItTwice() {
        Raised first;
        Raised second;
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            first = raiseOwing(uow, MONDAY, SUNDAY, false);
            second = raiseOwing(uow, MONDAY, SUNDAY, false);
            uow.commit();
        }
        assertThat(first.added()).isEqualTo(1);
        assertThat(second.added()).isZero();
        assertThat(second.found()).isEqualTo(1);
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(1);
        }
    }

    @Test
    void theReportSaysWhyTheDebtIsOwed() {
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow, 2);
            report = raiseOwing(uow, MONDAY, SUNDAY, false);
        }
        assertThat(report.byCause()).isEqualTo(Map.of("did not run", 2));
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> Raising.raiseOwing(uow, SUNDAY, MONDAY))
                    .isInstanceOf(OwingError.class)
                    .hasMessageContaining("backwards");
        }
    }

    @Test
    void aDebtTheGatherNoLongerFindsStaysOnFile() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            uow.commit();
        }
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            // The log is revised, so nothing is aired and the gather finds nothing.
            new LogRepository(uow.entityManager()).revise("KWLO", MONDAY);
            report = raiseOwing(uow, MONDAY, SUNDAY, false);
            uow.commit();
        }
        assertThat(report.found()).isZero();
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().count()).isEqualTo(1);
        }
    }

    @Test
    void anOfferWhoseAirDateHasGoneByIsExpiredByTheRun() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            Debt debt = uow.makegoods().ledger().debts().get(0);
            uow.makegoods().propose(debt, FRIDAY, "AMD", Money.dollars(60));
            uow.commit();
        }
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            report = raiseOwing(uow, MONDAY, LocalDate.of(2025, 8, 17), false);
            uow.commit();
        }
        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.changed()).isTrue();
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().openOffers()).isEmpty();
        }
    }

    @Test
    void offersAreExpiredAgainstTheSpanAndNotTheClock() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            Debt debt = uow.makegoods().ledger().debts().get(0);
            uow.makegoods().propose(debt, LocalDate.of(2025, 8, 20), "AMD", Money.dollars(60));
            uow.commit();
        }
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            report = raiseOwing(uow, MONDAY, SUNDAY, false);
            uow.commit();
        }
        assertThat(report.expired()).isZero();
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.makegoods().openOffers()).hasSize(1);
        }
    }

    @Test
    void expiringCanBePointedAtADayOfItsOwn() {
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            Debt debt = uow.makegoods().ledger().debts().get(0);
            uow.makegoods().propose(debt, LocalDate.of(2025, 8, 20), "AMD", Money.dollars(60));
            report = HouseLog.quietly(
                    () -> Raising.raiseOwing(uow, MONDAY, SUNDAY, false, Optional.of(LocalDate.of(2025, 9, 1))));
        }
        assertThat(report.expired()).isEqualTo(1);
    }

    @Test
    void anAcceptedOfferIsNotExpired() {
        Raised report;
        OfferRow made;
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            Debt debt = uow.makegoods().ledger().debts().get(0);
            made = uow.makegoods().propose(debt, FRIDAY, "AMD", Money.dollars(60));
            uow.makegoods().settle(made.getId(), Offer.ACCEPTED);
            report = raiseOwing(uow, MONDAY, LocalDate.of(2025, 8, 17), false);
        }
        assertThat(report.expired()).isZero();
        assertThat(made.getState()).isSameAs(Offer.ACCEPTED);
    }

    @Test
    void whatIsOwedIsWorthWhatItWasSoldAt() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow, 2);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(owedValue(uow)).isEqualTo(Money.parse("120.00"));
            assertThat(owedValue(uow, Optional.of("25-1044"))).isEqualTo(Money.parse("120.00"));
            assertThat(owedValue(uow, Optional.of("25-9999"))).isEqualTo(new Money(0));
        }
    }

    @Test
    void anAcceptedOfferTakesTheDebtOffTheTotal() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow, 2);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            Debt debt = uow.makegoods().ledger().debts().get(0);
            OfferRow made = uow.makegoods().propose(debt, FRIDAY, "AMD", Money.dollars(60));
            uow.makegoods().settle(made.getId(), Offer.ACCEPTED);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(owedValue(uow)).isEqualTo(Money.parse("60.00"));
        }
    }

    @Test
    void theWorstLinesComeBackWorstFirst() {
        List<WorstLine> found;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(2, 2));
            aDay(
                    uow,
                    List.of(new Spot(7, 2, "25-1044", 1), new Spot(9, 2, "25-1044", 1), new Spot(11, 2, "25-1044", 2)),
                    List.of(),
                    MONDAY,
                    true);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            found = worstLines(uow);
        }
        assertThat(found.get(0)).isEqualTo(new WorstLine("25-1044", 1, 2));
        assertThat(found.get(1)).isEqualTo(new WorstLine("25-1044", 2, 1));
    }

    @Test
    void theWorstLinesCanBeCutShort() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(2, 2));
            aDay(uow, List.of(new Spot(7, 2, "25-1044", 1), new Spot(11, 2, "25-1044", 2)), List.of(), MONDAY, true);
            assertThat(worstLines(uow, 1)).isEmpty();
            raiseOwing(uow, MONDAY, SUNDAY, false);
            assertThat(worstLines(uow, 1)).hasSize(1);
        }
    }

    @Test
    void everyCauseIsNamedEvenWhenItIsZero() {
        Map<String, Integer> found;
        try (UnitOfWork uow = engine.unitOfWork()) {
            aSkippedDay(uow);
            raiseOwing(uow, MONDAY, SUNDAY, false);
            found = causes(uow);
        }
        assertThat(found).containsEntry("did not run", 1);
        assertThat(found).containsEntry("bumped", 0);
        assertThat(found).containsEntry("never scheduled", 0);
    }

    @Test
    void theNeverScheduledPassFindsWhatTheBuilderNeverPlaced() {
        Raised report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(5, 1));
            aDay(uow, List.of(new Spot(7, 2, "25-1044", 1)), List.of(ran("07:10:00")), MONDAY, true);
            report = raiseOwing(uow, MONDAY, SUNDAY, true);
            uow.commit();
        }
        assertThat(report.byCause()).containsEntry("never scheduled", 4);
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(causes(uow)).containsEntry("never scheduled", 4);
        }
    }
}
