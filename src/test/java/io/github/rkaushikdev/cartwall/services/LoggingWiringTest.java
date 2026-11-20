package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.AiredDays.HEADER;
import static io.github.rkaushikdev.cartwall.services.AiredDays.SIGNED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The events the office greps for, checked at the places they are written.
 *
 * <p>These are not tests of the logging module. They are tests that the three jobs which run
 * unattended say what they did, because an overnight run that fails silently is the only failure
 * mode that costs a day of air.
 */
class LoggingWiringTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final BroadcastWeek WEEK = new BroadcastWeek(MONDAY);
    private static final BroadcastMonth AUGUST = new BroadcastMonth(2025, 8);

    private ByteArrayOutputStream caught;
    private Database engine;
    private House house;

    @BeforeEach
    void catchTheLogAndPutTheClusterOnFile() {
        caught = new ByteArrayOutputStream();
        HouseLog.configure("info", caught);
        engine = TestDatabase.fresh();
        house = Fixtures.house();
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : List.of("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ")) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
            uow.commit();
        }
    }

    @AfterEach
    void letTheLogGo() {
        HouseLog.configure("info", new ByteArrayOutputStream());
    }

    private String text() {
        return caught.toString(StandardCharsets.UTF_8);
    }

    private List<String> events() {
        return Arrays.stream(text().split("\n"))
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(" ")[2])
                .toList();
    }

    private static Order anOrder(int perWeek) {
        return new Order(
                        "25-1044",
                        "HYVEE",
                        LocalDate.of(2025, 7, 21),
                        "DHOLM",
                        List.of(new Line(
                                1,
                                "KWLO",
                                "AMD",
                                FIXED,
                                THIRTY,
                                Money.dollars(60),
                                Flight.build(MONDAY, LocalDate.of(2025, 8, 8), "M-F", perWeek))))
                .confirmed();
    }

    private static Placement aSpot(int line) {
        return new Placement("25-1044", line, "HYVEE", THIRTY, FIXED, "C1204");
    }

    private static void airMonday(LogRepository logs, DayBoard board) {
        logs.save(board);
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
        logs.release("KWLO", MONDAY, SIGNED);
        logs.markAired("KWLO", MONDAY);
    }

    @Test
    void aBuildSaysWhatItPlacedAndWhatItWrote() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(10));
            LogBuild.buildWeek(uow, house, WEEK);
            uow.commit();
        }
        assertThat(events()).contains("build.finished");
        assertThat(text()).contains("placed=10", "wanted=10", "written=35");
    }

    @Test
    void aBuildThatCannotPlaceEverythingSaysWhichOrder() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(40));
            LogBuild.buildWeek(uow, house, WEEK);
            uow.commit();
        }
        assertThat(events()).contains("build.short");
        assertThat(text()).contains("order=25-1044");
    }

    @Test
    void aBuildThatLeavesAReleasedDayAloneSaysSo() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            LogRepository logs = new LogRepository(uow.entityManager());
            logs.save(DayBoard.build(house.programming().get("KWLO"), MONDAY));
            logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
            logs.release("KWLO", MONDAY, SIGNED);
            uow.orders().save(anOrder(10));
            LogBuild.buildWeek(uow, house, WEEK);
            uow.commit();
        }
        assertThat(events()).contains("build.skipped");
        assertThat(text()).contains("state=released");
    }

    @Test
    void postingAnAsRunSaysWhatAiredAndWhatDidNot() {
        ClockGrid grid = aGrid("KWLO");
        DayBoard board = DayBoard.build(grid, MONDAY);
        board.avail(7, 2).add(aSpot(1));
        board.avail(9, 2).add(aSpot(1));
        try (UnitOfWork uow = engine.unitOfWork()) {
            airMonday(new LogRepository(uow.entityManager()), board);
            String file = String.join(
                            "\n",
                            HEADER,
                            "2025-08-04,07:10:00,C1204,a title,30.0,played,25-1044-1",
                            "2025-08-04,09:10:00,C1204,a title,30.0,skip,25-1044-1")
                    + "\n";
            Posting.post(uow.entityManager(), Matcher.reconcile(DayLog.build(grid, board), AsRunFile.parse(file)));
            uow.commit();
        }
        assertThat(events()).contains("asrun.posted", "asrun.missed");
        assertThat(text()).contains("aired=1");
    }

    @Test
    void theInvoiceRunSaysWhatItRaised() {
        ClockGrid grid = aGrid("KWLO");
        DayBoard board = DayBoard.build(grid, MONDAY);
        board.avail(7, 2).add(aSpot(1));
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(10));
            airMonday(new LogRepository(uow.entityManager()), board);
            String file = HEADER + "\n2025-08-04,07:10:00,C1204,a title,30.0,played,25-1044-1\n";
            Posting.post(uow.entityManager(), Matcher.reconcile(DayLog.build(grid, board), AsRunFile.parse(file)));
            Invoicing.runMonth(uow, AUGUST, LocalDate.of(2025, 8, 25));
            uow.commit();
        }
        assertThat(events()).contains("invoice.run.started", "invoice.raised", "invoice.run.finished");
        assertThat(text()).contains("invoice=25-1001", "gross=$60.00");
    }

    @Test
    void runningTheInvoiceMonthTwiceSaysItSkipped() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            Invoicing.runMonth(uow, AUGUST, LocalDate.of(2025, 8, 25));
            uow.commit();
        }
        assertThat(events()).containsExactly("invoice.run.started", "invoice.run.finished");
        assertThat(text()).contains("raised=0");
    }
}
