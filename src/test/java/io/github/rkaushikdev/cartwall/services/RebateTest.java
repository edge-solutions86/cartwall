package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.AiredDays.HEADER;
import static io.github.rkaushikdev.cartwall.services.AiredDays.SIGNED;
import static io.github.rkaushikdev.cartwall.services.Rebate.chargeBook;
import static io.github.rkaushikdev.cartwall.services.Rebate.owedFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Charge;
import io.github.rkaushikdev.cartwall.domain.ChargeBook;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Election;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.reconcile.Matcher;
import io.github.rkaushikdev.cartwall.reconcile.Posting;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.services.Rebate.Owed;
import io.github.rkaushikdev.cartwall.services.Rebate.RebateReport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RebateTest {

    private static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;
    private static final ClassOfTime FIXED = ClassOfTime.FIXED;

    // A special election on 2025-08-26 puts its sixty day window over August.
    private static final LocalDate ELECTION = LocalDate.of(2025, 8, 26);
    private static final Race RACE = new Race("Iowa House 63", Election.SPECIAL, ELECTION);
    private static final BroadcastMonth AUGUST = new BroadcastMonth(2025, 8);

    // 2025-08-04 is inside the window, which opened on 06-27.
    private static final LocalDate AIR_DAY = LocalDate.of(2025, 8, 4);

    private Database engine;

    @BeforeEach
    void putTheStationAndACandidateOnFile() {
        engine = TestDatabase.fresh();
        AccountBook accounts = Fixtures.accounts();
        accounts.addAdvertiser(Advertiser.build("MERRICK", "Merrick for Iowa", "POLITICAL"));
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(accounts);
            uow.cards().file(Fixtures.cards().inForce("KWLO", CARD_DATE));
            uow.stations().saveGrid(Fixtures.programming().get("KWLO"));
            uow.commit();
        }
    }

    private static Order anOrder(String number, String advertiser, String rate, ClassOfTime grade) {
        return new Order(
                        number,
                        advertiser,
                        LocalDate.of(2025, 7, 21),
                        "DHOLM",
                        List.of(new Line(
                                1,
                                "KWLO",
                                "AMD",
                                grade,
                                THIRTY,
                                Money.parse(rate),
                                Flight.build(AIR_DAY, LocalDate.of(2025, 8, 8), "M-F", 2))))
                .confirmed();
    }

    private static Order anOrder(String number, String advertiser, String rate) {
        return anOrder(number, advertiser, rate, IMMEDIATE);
    }

    private void file(Order... orders) {
        try (UnitOfWork uow = engine.unitOfWork()) {
            for (Order order : orders) {
                uow.orders().save(order);
            }
            uow.commit();
        }
    }

    /** One spot on the log for line 1 of an order, with its cart. */
    private record Spot(int hour, String order, String cart) {}

    /** Put spots on a day, air them, post the as-run, raise the invoices. */
    private void bill(List<Spot> placements, List<String> asRun) {
        ClockGrid grid = aGrid("KWLO");
        DayBoard board = DayBoard.build(grid, AIR_DAY);
        for (Spot spot : placements) {
            board.avail(spot.hour(), 2).add(new Placement(spot.order(), 1, "HYVEE", THIRTY, IMMEDIATE, spot.cart()));
        }
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.addAll(asRun);
        try (UnitOfWork uow = engine.unitOfWork()) {
            LogRepository logs = new LogRepository(uow.entityManager());
            logs.save(board);
            logs.approve("KWLO", AIR_DAY, "T. Ronan", SIGNED);
            logs.release("KWLO", AIR_DAY, SIGNED);
            logs.markAired("KWLO", AIR_DAY);
            HouseLog.quietly(() -> {
                Posting.post(
                        uow.entityManager(),
                        Matcher.reconcile(DayLog.build(grid, board), AsRunFile.parse(String.join("\n", lines) + "\n")));
                Invoicing.runMonth(uow, AUGUST, LocalDate.of(2025, 8, 25));
            });
            uow.commit();
        }
    }

    private static String ran(String at, String cart, String reference) {
        return "2025-08-04," + at + "," + cart + ",a title,30.0,played," + reference;
    }

    private RebateReport owed(List<String> advertisers) {
        try (UnitOfWork uow = engine.unitOfWork()) {
            return owedFor(uow, RACE, advertisers);
        }
    }

    private void billTheCandidateAndHyVee() {
        bill(
                List.of(new Spot(7, "25-1044", "C1204"), new Spot(9, "25-2000", "C1205")),
                List.of(ran("07:10:00", "C1204", "25-1044-1"), ran("09:10:00", "C1205", "25-2000-1")));
    }

    @Test
    void theWindowOfASpecialElectionReachesBackSixtyDays() {
        assertThat(RACE.windowOpens()).isEqualTo(LocalDate.of(2025, 6, 27));
        assertThat(RACE.inWindow(AIR_DAY)).isTrue();
    }

    @Test
    void aRunWithNoPoliticalAccountsIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> owedFor(uow, RACE, List.of()))
                    .isInstanceOf(RebateError.class)
                    .hasMessageContaining("at least one political account");
        }
    }

    @Test
    void aCandidateChargedTheLowestIsOwedNothing() {
        file(anOrder("25-1044", "MERRICK", "18.00"), anOrder("25-2000", "HYVEE", "36.00"));
        billTheCandidateAndHyVee();
        RebateReport report = owed(List.of("MERRICK"));
        assertThat(report.checked()).isEqualTo(1);
        assertThat(report.isClean()).isTrue();
        assertThat(report.total()).isEqualTo(new Money(0));
    }

    @Test
    void aCandidateChargedAboveTheLowestIsOwedTheDifference() {
        file(anOrder("25-1044", "MERRICK", "36.00"), anOrder("25-2000", "HYVEE", "18.00"));
        billTheCandidateAndHyVee();
        RebateReport report = owed(List.of("MERRICK"));
        assertThat(report.isClean()).isFalse();
        assertThat(report.owed()).hasSize(1);
        Owed owed = report.owed().get(0);
        assertThat(owed.charged()).isEqualTo(Money.dollars(36));
        assertThat(owed.lowest()).isEqualTo(Money.dollars(18));
        assertThat(owed.each()).isEqualTo(Money.dollars(18));
        assertThat(owed.spots()).isEqualTo(1);
        assertThat(owed.amount()).isEqualTo(Money.dollars(18));
        assertThat(report.total()).isEqualTo(Money.dollars(18));
    }

    @Test
    void theAmountOwedMultipliesByTheSpotsThatRan() {
        file(anOrder("25-1044", "MERRICK", "36.00"), anOrder("25-2000", "HYVEE", "18.00"));
        bill(
                List.of(
                        new Spot(7, "25-1044", "C1204"),
                        new Spot(9, "25-1044", "C1204"),
                        new Spot(11, "25-2000", "C1205")),
                List.of(
                        ran("07:10:00", "C1204", "25-1044-1"),
                        ran("09:10:00", "C1204", "25-1044-1"),
                        ran("11:10:00", "C1205", "25-2000-1")));
        RebateReport report = owed(List.of("MERRICK"));
        assertThat(report.owed().get(0).spots()).isEqualTo(2);
        assertThat(report.total()).isEqualTo(Money.dollars(36));
    }

    @Test
    void aCheaperChargeInAnotherClassDoesNotLowerTheCandidatesRate() {
        file(anOrder("25-1044", "MERRICK", "36.00", IMMEDIATE), anOrder("25-2000", "HYVEE", "18.00", FIXED));
        billTheCandidateAndHyVee();
        assertThat(owed(List.of("MERRICK")).isClean()).isTrue();
    }

    @Test
    void theChargeBookIsBuiltFromWhatWasInvoiced() {
        file(anOrder("25-1044", "MERRICK", "36.00"), anOrder("25-2000", "HYVEE", "18.00"));
        billTheCandidateAndHyVee();
        ChargeBook book;
        try (UnitOfWork uow = engine.unitOfWork()) {
            book = chargeBook(uow, RACE.windowOpens(), RACE.electionDay());
        }
        assertThat(book.size()).isEqualTo(2);
        assertThat(book.lowest("KWLO", "AMD", IMMEDIATE, THIRTY, RACE.windowOpens(), RACE.electionDay()))
                .contains(Money.dollars(18));
    }

    @Test
    void anOrderThatWasNeverInvoicedIsNotInTheBook() {
        file(anOrder("25-3000", "SLUMB", "1.00"));
        ChargeBook book;
        try (UnitOfWork uow = engine.unitOfWork()) {
            book = chargeBook(uow, RACE.windowOpens(), RACE.electionDay());
        }
        assertThat(book.size()).isZero();
    }

    @Test
    void aBookCanBeHandedInSoAWhatIfCanBeRun() {
        file(anOrder("25-1044", "MERRICK", "36.00"));
        bill(List.of(new Spot(7, "25-1044", "C1204")), List.of(ran("07:10:00", "C1204", "25-1044-1")));
        ChargeBook proposed = new ChargeBook(
                List.of(new Charge("KWLO", "AMD", IMMEDIATE, THIRTY, Money.dollars(12), AIR_DAY, "SLUMB")));
        RebateReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            report = owedFor(uow, RACE, List.of("MERRICK"), Optional.of(proposed));
        }
        assertThat(report.total()).isEqualTo(Money.dollars(24));
    }

    @Test
    void theReportTotalsByAccountWorstFirst() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            AccountBook book = uow.accounts().book();
            book.addAdvertiser(Advertiser.build("HOLLIS", "Hollis for Iowa", "POLITICAL"));
            uow.accounts().saveBook(book);
            uow.orders().save(anOrder("25-1044", "MERRICK", "36.00"));
            uow.orders().save(anOrder("25-1500", "HOLLIS", "30.00"));
            uow.orders().save(anOrder("25-2000", "HYVEE", "18.00"));
            uow.commit();
        }
        bill(
                List.of(
                        new Spot(7, "25-1044", "C1204"),
                        new Spot(9, "25-1500", "C1206"),
                        new Spot(11, "25-2000", "C1205")),
                List.of(
                        ran("07:10:00", "C1204", "25-1044-1"),
                        ran("09:10:00", "C1206", "25-1500-1"),
                        ran("11:10:00", "C1205", "25-2000-1")));
        RebateReport report = owed(List.of("MERRICK", "HOLLIS"));
        Map<String, Money> tally = report.byAdvertiser();
        assertThat(tally.keySet()).containsExactly("MERRICK", "HOLLIS");
        assertThat(tally).containsEntry("MERRICK", Money.dollars(18));
        assertThat(tally).containsEntry("HOLLIS", Money.dollars(12));
        assertThat(report.forAdvertiser("MERRICK")).hasSize(1);
        assertThat(report.forAdvertiser("NOBODY")).isEmpty();
    }

    @Test
    void theReportReadsAsASentence() {
        assertThat(owed(List.of("MERRICK")))
                .hasToString("Iowa House 63 special 08/26/2025: 0 line(s) checked, 0 owed, $0.00 in total");
    }
}
